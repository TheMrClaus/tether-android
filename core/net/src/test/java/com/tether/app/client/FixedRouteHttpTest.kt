package com.tether.app.client

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * r2 (security F4): the shared fixed-route helper refuses on its own what a later caller (ta-0d9)
 * might get wrong: a path that is not plain segments, a request that cannot be built or signed, and
 * a signed request that no longer goes to the drawing origin. Each sends nothing.
 */
class FixedRouteHttpTest {
    private val server = MockWebServer()
    private val elsewhere = MockWebServer()
    private val noRedirects = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private val route = FixedRouteHttp(noRedirects, maxBytes = 4096, callTimeoutMs = 5_000)

    @Before fun setUp() {
        server.start()
        elsewhere.start()
    }

    @After fun tearDown() {
        server.shutdown()
        elsewhere.shutdown()
    }

    private val origin get() = "http://${server.hostName}:${server.port}"
    private val paired get() = FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_test") }

    @Test fun onlyAPathOfPlainSegmentsIsSent() = runBlocking<Unit> {
        for (path in listOf("", "/", "api/devices", "/api/devices/", "//api", "/api//devices", "/api/./devices", "/api/../x", "/api/%2e%2e", "/api/devices?x=1",
            "/api/devices#f", "/api/de vices", "/api/dévices", "/api/devices;x", "/api\\devices", "/api/devices/\n")) {
            assertEquals(path, FixedRouteHttp.Outcome.NotBuilt(origin), route.call(paired, origin, FixedRouteHttp.Method.DELETE, path))
        }
        assertEquals(0, server.requestCount)
        // Control: a plain path goes.
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
        assertTrue(route.call(paired, origin, FixedRouteHttp.Method.GET, "/api/devices") is FixedRouteHttp.Outcome.Answered)
        assertEquals(1, server.requestCount)
    }

    @Test fun aSignerThatThrowsSendsNothing() = runBlocking<Unit> {
        val throwing = FilesAuthority.Paired(server.url("/")) { error("no key") }
        assertEquals(FixedRouteHttp.Outcome.NotBuilt(origin), route.call(throwing, origin, FixedRouteHttp.Method.POST, "/api/devices/pair"))
        val badHeader = FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer \n injected") }
        assertEquals(FixedRouteHttp.Outcome.NotBuilt(origin), route.call(badHeader, origin, FixedRouteHttp.Method.GET, "/api/devices"))
        assertEquals(0, server.requestCount)
    }

    @Test fun aSignedRequestMovedToAnotherOriginSendsNothing() = runBlocking<Unit> {
        val moving = FilesAuthority.Paired(server.url("/")) { it.url(elsewhere.url("/api/devices")).header("Authorization", "Bearer tthr_test") }
        assertEquals(FixedRouteHttp.Outcome.NotBuilt(origin), route.call(moving, origin, FixedRouteHttp.Method.GET, "/api/devices"))
        assertEquals(0, server.requestCount)
        assertEquals(0, elsewhere.requestCount)
    }
}
