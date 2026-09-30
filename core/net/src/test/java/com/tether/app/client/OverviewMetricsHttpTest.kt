package com.tether.app.client

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T15.3 request shapes for `GET /api/overview/host` and `/api/overview/usage` (server.mjs ~7606-7616):
 * only the fixed route, on the paired origin, with the credential; never following a redirect; a
 * sign-in gateway told apart from Tether's own 401/403 (T6.8's rule); the body bounded (declared
 * and streamed) and read leniently.
 */
class OverviewMetricsHttpTest {
    private val server = MockWebServer()
    private val elsewhere = MockWebServer()
    private val noRedirects = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private var authority: FilesAuthority = FilesAuthority.SignedOut
    private lateinit var metrics: HttpOverviewMetrics

    private val json = "application/json; charset=utf-8"

    @Before fun setUp() {
        server.start()
        elsewhere.start()
        authority = FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_test") }
        metrics = HttpOverviewMetrics(noRedirects, authority = { authority })
    }

    @After fun tearDown() {
        server.shutdown()
        elsewhere.shutdown()
    }

    private fun take(): RecordedRequest = server.takeRequest(20, TimeUnit.SECONDS) ?: error("no request reached the server")

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", json).setBody(body)

    private val origin get() = "http://${server.hostName}:${server.port}"

    @Test fun theHostRouteIsReadOnThePairedOriginWithTheCredential() = runBlocking<Unit> {
        server.enqueue(ok(OverviewMetricsFixtures.HOST_JSON))
        val result = metrics.host()
        assertEquals(OverviewMetricsResult.Ok(OverviewMetricsFixtures.HOST, origin), result)
        val req = take()
        assertEquals("GET", req.method)
        assertEquals("/api/overview/host", req.path)
        assertEquals("Bearer tthr_test", req.getHeader("Authorization"))
        assertEquals("application/json", req.getHeader("Accept"))
        assertEquals("no-store", req.getHeader("Cache-Control"))
    }

    @Test fun theUsageRouteIsReadTheSameWay() = runBlocking<Unit> {
        server.enqueue(ok(OverviewMetricsFixtures.USAGE_PARTIAL_JSON))
        assertEquals(OverviewMetricsResult.Ok(OverviewMetricsFixtures.USAGE_PARTIAL, origin), metrics.usage())
        val req = take()
        assertEquals("/api/overview/usage", req.path)
        assertEquals("Bearer tthr_test", req.getHeader("Authorization"))
    }

    @Test fun aPairedOriginWithAPathOrQueryStillAsksOnlyTheFixedRoute() = runBlocking<Unit> {
        authority = FilesAuthority.Paired(server.url("/prefix/?x=1#f")) { it.header("Authorization", "Bearer tthr_test") }
        server.enqueue(ok(OverviewMetricsFixtures.HOST_JSON))
        metrics.host()
        assertEquals("/api/overview/host", take().path)
    }

    @Test fun aRedirectIsBlockedAndNeverFollowed() = runBlocking<Unit> {
        for (code in listOf(301, 302, 303, 307, 308)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", elsewhere.url("/api/overview/host")))
            assertEquals(OverviewMetricsResult.Blocked(code), metrics.host())
            take()
        }
        // A same-origin redirect (a gateway's own login path) is not followed either.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/login")))
        assertEquals(OverviewMetricsResult.Blocked(302), metrics.usage())
        take()
        assertEquals("one request per call: nothing followed", 6, server.requestCount)
        assertEquals(0, elsewhere.requestCount)
    }

    @Test fun aSignInPageIsBlockedButTethersOwnRefusalsAreNot() = runBlocking<Unit> {
        val cases = listOf(
            MockResponse().setResponseCode(401).setHeader("Content-Type", "text/html").setBody("<html>login</html>") to OverviewMetricsResult.Blocked(401),
            MockResponse().setResponseCode(403).setBody("Forbidden") to OverviewMetricsResult.Blocked(403),
            MockResponse().setResponseCode(401).setHeader("Content-Type", json).setHeader("WWW-Authenticate", "Bearer").setBody("{}") to OverviewMetricsResult.Blocked(401),
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html; charset=utf-8").setBody("<html>sign in</html>") to OverviewMetricsResult.Blocked(200),
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/xhtml+xml").setBody("<html/>") to OverviewMetricsResult.Blocked(200),
            // Tether's own answers: the /api/ gate's 401 (tests/integration/overview-feed-wire.test.mjs), a 403, a 500.
            MockResponse().setResponseCode(401).setHeader("Content-Type", json).setBody("""{"error":"Authentication required."}""") to OverviewMetricsResult.SignedOut,
            MockResponse().setResponseCode(403).setHeader("Content-Type", json).setBody("""{"error":"Forbidden."}""") to OverviewMetricsResult.Forbidden,
            MockResponse().setResponseCode(500).setHeader("Content-Type", json).setBody("""{"error":"boom"}""") to OverviewMetricsResult.Unavailable(500),
            MockResponse().setResponseCode(502).setHeader("Content-Type", "text/html").setBody("<html>bad gateway</html>") to OverviewMetricsResult.Unavailable(502),
            MockResponse().setResponseCode(404).setHeader("Content-Type", json).setBody("""{"error":"Not found."}""") to OverviewMetricsResult.Unavailable(404),
            // Another 2xx is not the route's answer.
            MockResponse().setResponseCode(204) to OverviewMetricsResult.Unavailable(204),
        )
        for ((index, case) in cases.withIndex()) {
            server.enqueue(case.first)
            assertEquals("case $index", case.second, metrics.host())
            take()
        }
    }

    @Test fun aHugeBodyIsDroppedWhetherDeclaredOrStreamed() = runBlocking<Unit> {
        val small = HttpOverviewMetrics(noRedirects, authority = { authority }, maxBytes = 1024)
        val padded = """{"tokensToday":{"value":1,"label":"Tokens today","partial":false},"pad":"${"x".repeat(4096)}"}"""
        // Declared: Content-Length over the cap.
        server.enqueue(ok(padded))
        assertEquals(OverviewMetricsResult.Unavailable(200), small.usage())
        take()
        // Streamed: chunked, no length.
        server.enqueue(MockResponse().setHeader("Content-Type", json).setChunkedBody(padded, 128))
        assertEquals(OverviewMetricsResult.Unavailable(200), small.usage())
        take()
        // At the cap exactly it is read.
        val prefix = """{"tokensToday":{"value":1,"label":"Tokens today","partial":false},"pad":""""
        val exact = prefix + "x".repeat(1024 - prefix.length - 2) + "\"}"
        assertEquals(1024, exact.length)
        server.enqueue(MockResponse().setHeader("Content-Type", json).setChunkedBody(exact, 100))
        assertTrue(small.usage() is OverviewMetricsResult.Ok)
        take()
    }

    @Test fun theDefaultCapIsSixtyFourKibibytes() = runBlocking<Unit> {
        server.enqueue(MockResponse().setHeader("Content-Type", json).setChunkedBody("{\"pad\":\"" + "x".repeat(70_000) + "\"}", 4096))
        assertEquals(OverviewMetricsResult.Unavailable(200), metrics.host())
        take()
    }

    @Test fun anUnusableBodyIsUnavailableNotAnError() = runBlocking<Unit> {
        val bodies = listOf(
            "not json",
            "{\"cpu\":",
            "[]",
            "null",
            "42",
            "\"text\"",
            "[".repeat(200_000),
            "{\"a\":" + "[".repeat(50) + "]".repeat(50) + "}",
            "",
        )
        for (body in bodies) {
            server.enqueue(ok(body))
            assertEquals(body.take(20), OverviewMetricsResult.Unavailable(200), metrics.host())
            take()
        }
        // The usage route needs its tokensToday.
        server.enqueue(ok("""{"asOf":1,"coverage":[]}"""))
        assertEquals(OverviewMetricsResult.Unavailable(200), metrics.usage())
        take()
    }

    @Test fun signedOutOrLocalNetworkBlockedSendsNothing() = runBlocking<Unit> {
        authority = FilesAuthority.SignedOut
        assertEquals(OverviewMetricsResult.SignedOut, metrics.host())
        assertEquals(OverviewMetricsResult.SignedOut, metrics.usage())
        authority = FilesAuthority.LocalNetworkBlocked
        assertEquals(OverviewMetricsResult.LocalNetworkBlocked, metrics.host())
        assertEquals(0, server.requestCount)
    }

    @Test fun eachCallReadsTheAuthorityAfreshSoASwitchMovesTheCredentialWithIt() = runBlocking<Unit> {
        server.enqueue(ok(OverviewMetricsFixtures.HOST_JSON))
        assertEquals(origin, (metrics.host() as OverviewMetricsResult.Ok).origin)
        assertEquals("Bearer tthr_test", take().getHeader("Authorization"))
        // Server B, its own credential: the next call goes there, tagged with B, and A sees nothing more.
        authority = FilesAuthority.Paired(elsewhere.url("/")) { it.header("Authorization", "Bearer tthr_other") }
        elsewhere.enqueue(ok(OverviewMetricsFixtures.HOST_JSON))
        val b = metrics.host() as OverviewMetricsResult.Ok
        assertEquals("http://${elsewhere.hostName}:${elsewhere.port}", b.origin)
        assertEquals("Bearer tthr_other", elsewhere.takeRequest(20, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test fun anUnreachableServerIsUnavailable() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(OverviewMetricsResult.Unavailable(), metrics.host())
    }

    @Test fun aHangingServerTimesOut() = runBlocking<Unit> {
        val quick = HttpOverviewMetrics(noRedirects, authority = { authority }, callTimeoutMs = 300)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(OverviewMetricsResult.Unavailable(), withTimeout(10_000) { quick.host() })
    }

    @Test fun cancellingTheCallerCancelsTheRequest() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        // The default read timeout is 10 s: returning well before it means the socket was cancelled.
        val call = async(start = CoroutineStart.UNDISPATCHED) { metrics.host() }
        take()
        val started = System.nanoTime()
        call.cancel()
        withTimeout(5_000) { call.join() }
        assertTrue(call.isCancelled)
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
    }

    @Test fun aClientThatFollowsRedirectsIsRefused() {
        val refused = runCatching { HttpOverviewMetrics(OkHttpClient(), authority = { authority }) }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
        assertNull(runCatching { HttpOverviewMetrics(noRedirects, authority = { authority }) }.exceptionOrNull())
    }

    @Test fun aBodyInAnyEncodingIsReadAsUtf8WithoutFailing() = runBlocking<Unit> {
        val bytes = Buffer().writeUtf8("""{"scopeLabel":"""").write(byteArrayOf(0xC3.toByte(), 0x28)).writeUtf8("""","stale":false}""")
        server.enqueue(MockResponse().setHeader("Content-Type", json).setBody(bytes))
        val result = metrics.host() as OverviewMetricsResult.Ok
        assertEquals(false, result.value.stale)
        take()
    }
}
