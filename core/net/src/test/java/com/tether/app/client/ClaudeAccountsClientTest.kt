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
 * ta-9q2 on the real client: [TetherClient.claudeAccounts] carries the one credential in force to
 * the paired server only, by GET only, never follows a redirect elsewhere, tags every answer with
 * the origin it came from, and sends nothing once signed out.
 */
class ClaudeAccountsClientTest {
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

    @Test fun aPairedDeviceReadsAllThreeByGetWithItsBearerTokenAndNoCookie() = runBlocking<Unit> {
        loadedButIdle(token = "tthr_device")
        h.server.enqueue(json(ClaudeAccountsFixtures.LIST_JSON))
        assertEquals(ClaudeAccountsResult.Ok(ClaudeAccountsFixtures.LIST, origin), h.client.claudeAccounts.list())
        h.server.enqueue(json(ClaudeAccountsFixtures.SYNC_JSON))
        assertEquals(ClaudeAccountsResult.Ok(ClaudeAccountsFixtures.SYNC, origin), h.client.claudeAccounts.sync())
        h.server.enqueue(json(ClaudeAccountsFixtures.STATUS_LOGGED_IN_JSON))
        assertEquals(ClaudeAccountsResult.Ok(ClaudeAccountsFixtures.STATUS_LOGGED_IN, origin), h.client.claudeAccounts.status("claude-work"))
        val paths = (1..3).map { take() }.onEach { req ->
            assertEquals("GET", req.method)
            assertEquals("Bearer tthr_device", req.getHeader("Authorization"))
            assertNull(req.getHeader("Cookie"))
        }.map { it.path }
        assertEquals(listOf("/api/claude-accounts", "/api/claude-accounts/sync", "/api/claude-accounts/claude-work/status"), paths)
        assertEquals(origin, serverOrigin(h.client.serverUrl.value))
    }

    @Test fun aPasswordSessionSendsItsCookieWithTheConsoleOrigin() = runBlocking<Unit> {
        loadedButIdle(cookie = "cookie")
        h.server.enqueue(json(ClaudeAccountsFixtures.LIST_JSON))
        assertEquals(ClaudeAccountsResult.Ok(ClaudeAccountsFixtures.LIST, origin), h.client.claudeAccounts.list())
        val req = take()
        assertEquals("tether_session=cookie", req.getHeader("Cookie"))
        assertEquals(origin, req.getHeader("Origin"))
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun aRedirectToAnotherOriginNeverReceivesTheCredential() = runBlocking<Unit> {
        other.start()
        loadedButIdle(token = "tthr_device")
        h.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/api/claude-accounts")))
        assertEquals(ClaudeAccountsResult.Blocked(302, origin), h.client.claudeAccounts.list())
        h.server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/api/claude-accounts/sync")))
        assertEquals(ClaudeAccountsResult.Blocked(307, origin), h.client.claudeAccounts.sync())
        assertEquals(0, other.requestCount)
    }

    @Test fun afterLogoutNothingIsSent() = runBlocking<Unit> {
        loadedButIdle(token = "tthr_device")
        h.client.logout()
        val before = h.server.requestCount
        assertEquals(ClaudeAccountsResult.SignedOut(), h.client.claudeAccounts.list())
        assertEquals(ClaudeAccountsResult.SignedOut(), h.client.claudeAccounts.sync())
        assertEquals(ClaudeAccountsResult.SignedOut(), h.client.claudeAccounts.status("claude-work"))
        assertEquals(before, h.server.requestCount)
    }
}
