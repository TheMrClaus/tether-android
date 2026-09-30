package com.tether.app.client

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T6.8: the tool-media fetch on the real client, against exactly what the server sends. The
 * responses below were captured from an isolated fake-engine server at the production commit
 * (protocol v135): a tool result's base64 PNG, materialized to a `media_ref`, then fetched with a
 * paired-device bearer. The body is that PNG (named by its own sha256); the headers are the
 * route's own (lib/tool-media-store.mjs `streamToolMedia`). The last case is the same request
 * behind a sign-in gateway that only exempts the README's five native paths: the gateway answers
 * `/api/tool-media/…` with a redirect to its login page, which is never followed.
 */
class ToolMediaWireReplayTest {
    private val h = ConnectionHarness()
    private val login = MockWebServer()

    @After fun tearDown() {
        h.close()
        runCatching { login.shutdown() }
    }

    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAGAAAAA8CAIAAAAWtijjAAAAdElEQVR4nO3QsQmAAAAEsRcV99/YCSyFKwKZIMe2eydfrj3nxidBggQJEhQmSJAgQYLCBAkSJEhQmCBBggQJChMkSJAgQWGCBAkSJChMkCBBggSFCRIkSJCgMEGCBAkSFCZIkCBBgsIECRIkSFCYIEGCfvUCjWpH4ylRax0AAAAASUVORK5CYII=",
    )

    /** The server's `url` for [png]: `/api/tool-media/<sha256 of the bytes>.png`. */
    private val url = "/api/tool-media/e0c7fa51151aa052a8da3332dfbca5b8bd02c4176ad6dcc91bc4525c0cd000b6.png"

    /** The route's 200, header for header as captured (Date aside). */
    private fun captured200() = MockResponse()
        .setResponseCode(200)
        .setHeader("Cache-Control", "private, max-age=31536000, immutable")
        .setHeader("Connection", "keep-alive")
        .setHeader("Content-Type", "image/png")
        .setHeader("Keep-Alive", "timeout=5")
        .setBody(Buffer().write(png))

    private fun take(): RecordedRequest = h.server.takeRequest(20, TimeUnit.SECONDS)!!

    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(20_000) { flow.first(predicate) } }

    private fun signedIn(cookie: Credential.Cookie? = null, token: String? = null) {
        h.server.start()
        val base = h.server.url("/").toString().trimEnd('/')
        h.settings = InMemorySettings(
            initialBaseUrl = base,
            initialCookie = cookie?.value,
            initialDeviceToken = token,
            initialCookieName = cookie?.name ?: Credential.Cookie.LEGACY_NAME,
        )
        h.client = RealTetherClient(
            settings = h.settings,
            httpClient = OkHttpClient(),
            scope = h.scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = h.scheduler,
        )
        // The connect is refused so no socket competes with the fetch for the server's queue.
        h.server.enqueue(MockResponse().setResponseCode(401))
        h.client.start()
        await(h.client.signedOutReason) { it == SignedOutReason.GatewayRefused }
        take()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun thePairedDeviceGetsTheServersPictureByteForByte() = runBlocking {
        assertEquals("the server names the file by its content", url.substringAfterLast('/').substringBefore('.'), sha256(png))
        signedIn(token = "tthr_device")
        h.server.enqueue(captured200())
        val sink = ByteArrayOutputStream()
        assertEquals(ToolMediaResult.Ok(png.size.toLong(), "image/png"), h.client.toolMedia.fetch(url, 32L * 1024 * 1024, sink))
        assertArrayEquals(png, sink.toByteArray())
        val req = take()
        assertEquals("GET", req.method)
        assertEquals(url, req.path)
        assertEquals("Bearer tthr_device", req.getHeader("Authorization"))
        assertEquals("image/png", req.getHeader("Accept"))
        assertNull(req.getHeader("Cookie"))
    }

    @Test fun aCookieSessionIssuedUnderTheHostPrefixedNameGetsItToo() = runBlocking {
        signedIn(cookie = Credential.Cookie("sess", Credential.Cookie.HOST_NAME))
        h.server.enqueue(captured200())
        val sink = ByteArrayOutputStream()
        assertEquals(ToolMediaResult.Ok(png.size.toLong(), "image/png"), h.client.toolMedia.fetch(url, 32L * 1024 * 1024, sink))
        assertArrayEquals(png, sink.toByteArray())
        val req = take()
        assertEquals("${Credential.Cookie.HOST_NAME}=sess", req.getHeader("Cookie"))
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun behindASignInGatewayTheRedirectToItsLoginPageIsAFailureNeverFollowed() = runBlocking {
        login.start()
        signedIn(token = "tthr_device")
        h.server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", login.url("/?rd=${url.replace("/", "%2F")}"))
                .setHeader("Content-Type", "text/html")
                .setBody("<html>login</html>"),
        )
        val sink = ByteArrayOutputStream()
        assertEquals(ToolMediaResult.Failed(302), h.client.toolMedia.fetch(url, 32L * 1024 * 1024, sink))
        assertEquals("nothing of the login page is kept", 0, sink.size())
        assertEquals("the bearer is never carried to the gateway's login origin", 0, login.requestCount)
    }
}
