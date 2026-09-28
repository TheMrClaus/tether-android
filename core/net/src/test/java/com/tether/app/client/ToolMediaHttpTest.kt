package com.tether.app.client

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * T6.2 request shapes for `GET /api/tool-media/<sha256>.<ext>` (server.mjs:7184,
 * lib/tool-media-store.mjs): only a server-shaped path is ever requested, on the paired origin,
 * with the credential, never following a redirect, the content type allow-listed and matched to
 * the extension, and the body bounded (declared and streamed).
 */
class ToolMediaHttpTest {
    private val server = MockWebServer()
    private val elsewhere = MockWebServer()
    private val noRedirects = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private var authority: FilesAuthority = FilesAuthority.SignedOut
    private lateinit var media: HttpToolMedia

    private val hash = "a".repeat(64)
    private val png = "/api/tool-media/$hash.png"
    private val bytes = ByteArray(300) { it.toByte() }

    @Before fun setUp() {
        server.start()
        elsewhere.start()
        authority = FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_test") }
        media = HttpToolMedia(noRedirects) { authority }
    }

    @After fun tearDown() {
        server.shutdown()
        elsewhere.shutdown()
    }

    private fun take(): RecordedRequest = server.takeRequest(20, TimeUnit.SECONDS) ?: error("no request reached the server")

    private fun image(type: String = "image/png", body: ByteArray = bytes) =
        MockResponse().setHeader("Content-Type", type).setBody(Buffer().write(body))

    @Test fun aValidPathIsFetchedOnThePairedOriginWithTheCredential() = runBlocking {
        server.enqueue(image())
        val sink = ByteArrayOutputStream()
        assertEquals(ToolMediaResult.Ok(300, "image/png"), media.fetch(png, 1024, sink))
        val req = take()
        assertEquals("GET", req.method)
        assertEquals(png, req.path)
        assertEquals("Bearer tthr_test", req.getHeader("Authorization"))
        assertEquals("image/png", req.getHeader("Accept"))
        assertArrayEquals(bytes, sink.toByteArray())
    }

    @Test fun everyExtensionTheServerStoresIsAccepted() = runBlocking {
        for ((ext, type) in ToolMediaSource.CONTENT_TYPE_BY_EXT) {
            server.enqueue(image(type = "$type; charset=binary"))
            assertEquals(ext, ToolMediaResult.Ok(300, type), media.fetch("/api/tool-media/$hash.$ext", 1024, ByteArrayOutputStream()))
            assertEquals("/api/tool-media/$hash.$ext", take().path)
        }
    }

    @Test fun anythingButTheServersFilenameShapeIsRefusedWithoutARequest() = runBlocking {
        val refused = listOf(
            elsewhere.url("/api/tool-media/$hash.png").toString(), // another origin
            "//evil.test/api/tool-media/$hash.png", // protocol-relative
            "https://evil.test/api/tool-media/$hash.png",
            "/api/tool-media/${"A".repeat(64)}.png", // uppercase hex
            "/api/tool-media/${"a".repeat(63)}.png",
            "/api/tool-media/$hash.svg", // not a stored type
            "/api/tool-media/$hash.PNG",
            "/api/tool-media/$hash.png?x=1",
            "/api/tool-media/$hash.png#f",
            "/api/tool-media/../files?path=%2Fetc%2Fpasswd",
            "/api/tool-media/$hash.png\n",
            "/api/tool-media/$hash.png/..",
            "/api/files?path=/etc/passwd",
            "data:image/png;base64,AAAA",
            "",
        )
        for (url in refused) assertEquals(url, ToolMediaResult.Refused, media.fetch(url, 1024, ByteArrayOutputStream()))
        assertEquals(0, server.requestCount)
        assertEquals(0, elsewhere.requestCount)
    }

    @Test fun aRedirectIsNeverFollowedAndNeverTrusted() = runBlocking {
        for (code in listOf(301, 302, 303, 307, 308)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", elsewhere.url("/api/tool-media/$hash.png")))
            assertEquals(ToolMediaResult.Failed(code), media.fetch(png, 1024, ByteArrayOutputStream()))
            take()
        }
        assertEquals(0, elsewhere.requestCount)
    }

    @Test fun aContentTypeOffTheAllowListOrForAnotherExtensionFails() = runBlocking {
        for (type in listOf("text/html", "image/svg+xml", "image/jpeg", "application/octet-stream")) {
            server.enqueue(image(type = type))
            assertEquals(type, ToolMediaResult.Failed(200), media.fetch(png, 1024, ByteArrayOutputStream()))
            take()
        }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes))) // no Content-Type at all
        assertEquals(ToolMediaResult.Failed(200), media.fetch(png, 1024, ByteArrayOutputStream()))
    }

    @Test fun aDeclaredLengthPastTheCapIsRefusedBeforeReading() = runBlocking {
        server.enqueue(image(body = ByteArray(2048)))
        val sink = ByteArrayOutputStream()
        assertEquals(ToolMediaResult.TooLarge, media.fetch(png, 1024, sink))
        assertEquals(0, sink.size())
    }

    @Test fun aStreamedBodyPastTheCapIsCutOff() = runBlocking {
        // Chunked: no Content-Length to refuse up front, so the stream count stops it.
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setChunkedBody(Buffer().write(ByteArray(4096)), 256))
        val sink = ByteArrayOutputStream()
        assertEquals(ToolMediaResult.TooLarge, media.fetch(png, 1024, sink))
        assert(sink.size() <= 1024) { "kept ${sink.size()} bytes past the cap" }
    }

    @Test fun errorsAndSignedOutStatesSendNothingOrFail() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Not found."}"""))
        assertEquals(ToolMediaResult.Failed(404), media.fetch(png, 1024, ByteArrayOutputStream()))
        take()
        authority = FilesAuthority.SignedOut
        assertEquals(ToolMediaResult.SignedOut, media.fetch(png, 1024, ByteArrayOutputStream()))
        authority = FilesAuthority.LocalNetworkBlocked
        assertEquals(ToolMediaResult.LocalNetworkBlocked, media.fetch(png, 1024, ByteArrayOutputStream()))
        assertEquals(1, server.requestCount)
    }

    @Test fun anUnreachableServerIsAFailureNotACrash() = runBlocking {
        val dead = MockWebServer().apply { start() }
        val url = dead.url("/")
        dead.shutdown()
        authority = FilesAuthority.Paired(url) { it }
        assertEquals(ToolMediaResult.Failed(), media.fetch(png, 1024, ByteArrayOutputStream()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aClientThatFollowsRedirectsIsRejected() {
        HttpToolMedia(OkHttpClient()) { authority }
    }

    @Test fun theDefaultSourceNeverTouchesTheNetwork() = runBlocking {
        assertEquals(ToolMediaResult.SignedOut, ToolMediaSource.Unavailable.fetch(png, 1024, ByteArrayOutputStream()))
        assertNull(ToolMediaSource.extensionOf("/api/tool-media/$hash.png.png"))
    }
}
