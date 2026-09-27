package com.tether.app.client

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T11.1: RealTetherClient's [TetherClient.files] carries the ONE credential in force (paired
 * device token or password cookie, exactly as the socket does) to the paired server only.
 */
class WorkspaceFilesClientTest {
    private val h = ConnectionHarness()
    private val other = MockWebServer()

    @After fun tearDown() {
        h.close()
        runCatching { other.shutdown() }
    }

    private fun take(): RecordedRequest = h.server.takeRequest(10, TimeUnit.SECONDS)!!

    private val listing = """{"current":"/w","parent":null,"breadcrumbs":[{"name":"w","path":"/w"}],"entries":[]}"""

    /** start() with a gateway-refused probe: the credential is loaded in memory, no socket. */
    private fun loadedButIdle(cookie: String? = null, token: String? = null) {
        h.server.start()
        val base = h.server.url("/").toString().trimEnd('/')
        h.settings = InMemorySettings(initialBaseUrl = base, initialCookie = cookie, initialDeviceToken = token)
        h.client = RealTetherClient(
            settings = h.settings,
            httpClient = OkHttpClient(),
            scope = h.scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = h.scheduler,
        )
        h.server.enqueue(MockResponse().setResponseCode(401))
        h.client.start()
        h.await(h.client.signedOutReason) { it == SignedOutReason.GatewayRefused }
        take()
    }

    @Test fun aPairedDeviceSendsItsBearerTokenAndNoCookie() = runBlocking {
        loadedButIdle(token = "tthr_device")
        h.server.enqueue(MockResponse().setBody(listing))
        assert(h.client.files.list("/w") is FilesResult.Ok)
        val req = take()
        assertEquals("/api/files/list?path=%2Fw", req.path)
        assertEquals("Bearer tthr_device", req.getHeader("Authorization"))
        assertNull(req.getHeader("Cookie"))
    }

    @Test fun aPasswordSessionSendsItsCookieAndNoBearer() = runBlocking {
        loadedButIdle(cookie = "cookie")
        h.server.enqueue(MockResponse().setBody("""{"ok":true,"parent":"/w"}"""))
        h.client.files.delete("/w/a.txt")
        val req = take()
        assertEquals("DELETE", req.method)
        assertEquals("tether_session=cookie", req.getHeader("Cookie"))
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun aRedirectToAnotherOriginNeverReceivesTheCookieOrToken() = runBlocking {
        other.start()
        loadedButIdle(cookie = "cookie")
        h.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/api/files/list?path=%2F")))
        assertEquals(FilesResult.Failed("This folder could not be opened.", 302), h.client.files.list("/w"))
        h.server.enqueue(MockResponse().setResponseCode(308).setHeader("Location", other.url("/api/files/upload?path=%2F&name=x")))
        val result = h.client.files.upload("/w", "x", object : UploadSource {
            override val length: Long = 3
            override fun open() = "abc".byteInputStream()
        })
        assertEquals(FilesResult.Failed("\"x\" could not be uploaded.", 308), result)
        assertEquals(0, other.requestCount)
    }

    @Test fun afterLogoutNothingIsSent() = runBlocking {
        loadedButIdle(token = "tthr_device")
        h.client.logout()
        val before = h.server.requestCount
        assertEquals(FilesResult.Failed("Sign in to browse workspace files."), h.client.files.list("/w"))
        assertEquals(before, h.server.requestCount)
    }

    @Test fun whileTheLocalNetworkIsBlockedNothingIsSent() = runBlocking {
        // A LAN server while Android 17's local-network permission is missing: every request is
        // counted and refused by the interceptor, so "nothing sent" is observable.
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { attempts.incrementAndGet(); throw java.io.IOException("no network here") }.build()
        h.client = RealTetherClient(
            settings = InMemorySettings(initialBaseUrl = "http://192.168.1.20:8080", initialDeviceToken = "tthr_device"),
            httpClient = http,
            scope = h.scope,
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = h.scheduler,
            localNetworkAccess = { true },
        )
        h.client.start()
        h.await(h.client.connection) { it == ConnectionState.LocalNetworkBlocked }
        assertEquals(FilesResult.Failed("Local network access is blocked."), h.client.files.list("/w"))
        assertEquals(0, attempts.get())
    }
}
