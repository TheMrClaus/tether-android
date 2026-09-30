package com.tether.app.client

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T15.3 on the real client: [TetherClient.overviewMetrics] carries the one credential in force to
 * the paired server only, never follows a redirect elsewhere, tags every reading with the origin it
 * came from, and sends nothing once signed out.
 */
class OverviewMetricsClientTest {
    private val h = ConnectionHarness()
    private val other = MockWebServer()

    @After fun tearDown() {
        h.close()
        runCatching { other.shutdown() }
    }

    private fun take(): RecordedRequest = h.server.takeRequest(20, TimeUnit.SECONDS)!!

    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(20_000) { flow.first(predicate) } }

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
        await(h.client.signedOutReason) { it == SignedOutReason.GatewayRefused }
        take()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private val origin get() = "http://${h.server.hostName}:${h.server.port}"

    @Test fun aPairedDeviceSendsItsBearerTokenAndNoCookie() = runBlocking<Unit> {
        loadedButIdle(token = "tthr_device")
        h.server.enqueue(json(OverviewMetricsFixtures.HOST_JSON))
        assertEquals(OverviewMetricsResult.Ok(OverviewMetricsFixtures.HOST, origin), h.client.overviewMetrics.host())
        val req = take()
        assertEquals("/api/overview/host", req.path)
        assertEquals("Bearer tthr_device", req.getHeader("Authorization"))
        assertNull(req.getHeader("Cookie"))
        // r2: the tag is exactly the canonical identity a screen derives from the client's server URL.
        assertEquals(origin, serverOrigin(h.client.serverUrl.value))
    }

    @Test fun aPasswordSessionSendsItsCookieWithTheConsoleOriginAndNoBearer() = runBlocking<Unit> {
        loadedButIdle(cookie = "cookie")
        h.server.enqueue(json(OverviewMetricsFixtures.USAGE_PARTIAL_JSON))
        assertEquals(OverviewMetricsResult.Ok(OverviewMetricsFixtures.USAGE_PARTIAL, origin), h.client.overviewMetrics.usage())
        val req = take()
        assertEquals("/api/overview/usage", req.path)
        assertEquals("tether_session=cookie", req.getHeader("Cookie"))
        assertEquals(origin, req.getHeader("Origin"))
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun aRedirectToAnotherOriginNeverReceivesTheCredential() = runBlocking<Unit> {
        other.start()
        loadedButIdle(cookie = "cookie")
        h.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/api/overview/host")))
        assertEquals(OverviewMetricsResult.Blocked(302, origin), h.client.overviewMetrics.host())
        h.server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/api/overview/usage")))
        assertEquals(OverviewMetricsResult.Blocked(307, origin), h.client.overviewMetrics.usage())
        assertEquals(0, other.requestCount)
    }

    @Test fun afterLogoutNothingIsSent() = runBlocking<Unit> {
        loadedButIdle(token = "tthr_device")
        h.client.logout()
        val before = h.server.requestCount
        assertEquals(OverviewMetricsResult.SignedOut(), h.client.overviewMetrics.host())
        assertEquals(OverviewMetricsResult.SignedOut(), h.client.overviewMetrics.usage())
        assertEquals(before, h.server.requestCount)
    }
}
