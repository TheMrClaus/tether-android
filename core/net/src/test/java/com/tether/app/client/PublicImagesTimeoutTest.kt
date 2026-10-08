package com.tether.app.client

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** ta-daw9: the public-picture client has no call or read limit, like the browser's `<img>`; a real failure still fails. */
class PublicImagesTimeoutTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.shutdown()

    @Test fun theDefaultClientHasNoCallLimitAndKeepsItsConnectTimeout() {
        val client = HttpPublicImages.defaultClient()
        assertEquals("no call timeout (0 = none)", 0, client.callTimeoutMillis)
        assertEquals(15_000, client.connectTimeoutMillis)
    }

    @Test fun aPictureThatStallsPastTheClientsReadTimeoutStillCompletes() = runBlocking {
        val body = ByteArray(2048) { it.toByte() }
        // Headers and then the body each take several times the injected client's 250 ms read timeout.
        server.enqueue(MockResponse().setBody(Buffer().write(body)).setHeadersDelay(900, TimeUnit.MILLISECONDS).setBodyDelay(900, TimeUnit.MILLISECONDS))
        val images = HttpPublicImages(OkHttpClient.Builder().readTimeout(250, TimeUnit.MILLISECONDS).build())
        val sink = ByteArrayOutputStream()
        assertEquals(ToolMediaResult.Ok(2048, ""), images.fetch(server.url("/a.png").toString(), 1024 * 1024, sink))
        assertArrayEquals(body, sink.toByteArray())
    }

    @Test fun aDroppedConnectionAndAnHttpErrorStillFail() = runBlocking {
        val images = HttpPublicImages(OkHttpClient.Builder().readTimeout(250, TimeUnit.MILLISECONDS).build())
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4096))).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertEquals(ToolMediaResult.Failed(), images.fetch(server.url("/a.png").toString(), 1024 * 1024, ByteArrayOutputStream()))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(ToolMediaResult.Failed(500), images.fetch(server.url("/b.png").toString(), 1024 * 1024, ByteArrayOutputStream()))
    }
}
