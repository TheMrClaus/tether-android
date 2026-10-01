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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ta-9q2 request shapes for the three device-readable Claude-accounts GETs (tether 887c222
 * server.mjs:8145, :8172, :8276): only the fixed routes, by GET, on the paired origin, with the
 * credential; a redirect never followed; a sign-in gateway told apart from Tether's own answers;
 * the body bounded; anything that is not the route's JSON unavailable.
 */
class ClaudeAccountsHttpTest {
    private val server = MockWebServer()
    private val elsewhere = MockWebServer()
    private val noRedirects = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private var authority: FilesAuthority = FilesAuthority.SignedOut
    private lateinit var accounts: HttpClaudeAccounts

    private val json = "application/json; charset=utf-8"

    @Before fun setUp() {
        server.start()
        elsewhere.start()
        authority = FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_test") }
        accounts = HttpClaudeAccounts(noRedirects, authority = { authority })
    }

    @After fun tearDown() {
        server.shutdown()
        elsewhere.shutdown()
    }

    private fun take(): RecordedRequest = server.takeRequest(20, TimeUnit.SECONDS) ?: error("no request reached the server")

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", json).setBody(body)

    private val origin get() = "http://${server.hostName}:${server.port}"

    @Test fun theListIsReadOnThePairedOriginWithTheCredential() = runBlocking<Unit> {
        server.enqueue(ok(ClaudeAccountsFixtures.LIST_JSON))
        assertEquals(ClaudeAccountsResult.Ok(ClaudeAccountsFixtures.LIST, origin), accounts.list())
        val req = take()
        assertEquals("GET", req.method)
        assertEquals("/api/claude-accounts", req.path)
        assertEquals("Bearer tthr_test", req.getHeader("Authorization"))
        assertEquals("application/json", req.getHeader("Accept"))
        assertEquals("no-store", req.getHeader("Cache-Control"))
        assertEquals(0L, req.bodySize)
    }

    @Test fun theSyncStateAndAStatusAreReadTheSameWay() = runBlocking<Unit> {
        server.enqueue(ok(ClaudeAccountsFixtures.SYNC_JSON))
        assertEquals(ClaudeAccountsResult.Ok(ClaudeAccountsFixtures.SYNC, origin), accounts.sync())
        take().let {
            assertEquals("GET", it.method)
            assertEquals("/api/claude-accounts/sync", it.path)
            assertEquals("Bearer tthr_test", it.getHeader("Authorization"))
        }
        server.enqueue(ok(ClaudeAccountsFixtures.STATUS_LOGGED_IN_JSON))
        assertEquals(ClaudeAccountsResult.Ok(ClaudeAccountsFixtures.STATUS_LOGGED_IN, origin), accounts.status("claude-work"))
        take().let {
            assertEquals("GET", it.method)
            assertEquals("/api/claude-accounts/claude-work/status", it.path)
        }
    }

    /** A server-supplied id is put in a path only in the registry's shape: nothing else is ever asked. */
    @Test fun anIdOutsideTheRegistrysShapeSendsNothing() = runBlocking<Unit> {
        for (id in listOf("", ".", "..", "../devices", "claude/../../api/devices", "claude%2F..", "claude?x=1", "claude#f", "Claude", "claude.work")) {
            assertEquals(id, ClaudeAccountsResult.Unavailable(null, origin), accounts.status(id))
        }
        assertEquals(0, server.requestCount)
        authority = FilesAuthority.SignedOut
        assertEquals(ClaudeAccountsResult.SignedOut(), accounts.status(".."))
    }

    @Test fun aPairedOriginWithAPathOrQueryStillAsksOnlyTheFixedRoute() = runBlocking<Unit> {
        authority = FilesAuthority.Paired(server.url("/prefix/?x=1#f")) { it.header("Authorization", "Bearer tthr_test") }
        server.enqueue(ok(ClaudeAccountsFixtures.LIST_JSON))
        accounts.list()
        assertEquals("/api/claude-accounts", take().path)
        server.enqueue(ok(ClaudeAccountsFixtures.STATUS_LOGGED_IN_JSON))
        accounts.status("claude-work")
        assertEquals("/api/claude-accounts/claude-work/status", take().path)
    }

    @Test fun aRedirectIsBlockedAndNeverFollowed() = runBlocking<Unit> {
        for (code in listOf(301, 302, 303, 307, 308)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", elsewhere.url("/api/claude-accounts")))
            assertEquals(ClaudeAccountsResult.Blocked(code, origin), accounts.list())
            take()
        }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/login")))
        assertEquals(ClaudeAccountsResult.Blocked(302, origin), accounts.sync())
        take()
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", elsewhere.url("/api/claude-accounts/claude-work/status")))
        assertEquals(ClaudeAccountsResult.Blocked(307, origin), accounts.status("claude-work"))
        take()
        assertEquals("one request per call: nothing followed", 7, server.requestCount)
        assertEquals(0, elsewhere.requestCount)
    }

    @Test fun aSignInPageIsBlockedButTethersOwnAnswersAreNot() = runBlocking<Unit> {
        val cases = listOf(
            MockResponse().setResponseCode(401).setHeader("Content-Type", "text/html").setBody("<html>login</html>") to ClaudeAccountsResult.Blocked(401, origin),
            MockResponse().setResponseCode(403).setBody("Forbidden") to ClaudeAccountsResult.Blocked(403, origin),
            MockResponse().setResponseCode(401).setHeader("Content-Type", json).setHeader("WWW-Authenticate", "Bearer").setBody("{}") to ClaudeAccountsResult.Blocked(401, origin),
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html; charset=utf-8").setBody("<html>sign in</html>") to ClaudeAccountsResult.Blocked(200, origin),
            // Tether's own answers.
            MockResponse().setResponseCode(401).setHeader("Content-Type", json).setBody("""{"error":"Authentication required."}""") to ClaudeAccountsResult.SignedOut(origin),
            MockResponse().setResponseCode(403).setHeader("Content-Type", json).setBody("""{"error":"This needs an owner sign-in (password or passkey in a browser)."}""") to ClaudeAccountsResult.Forbidden(origin),
            MockResponse().setResponseCode(500).setHeader("Content-Type", json).setBody("""{"error":"boom"}""") to ClaudeAccountsResult.Refused(500, "boom", origin),
            MockResponse().setResponseCode(500).setHeader("Content-Type", json).setBody("{}") to ClaudeAccountsResult.Unavailable(500, origin),
            MockResponse().setResponseCode(502).setHeader("Content-Type", "text/html").setBody("<html>bad gateway</html>") to ClaudeAccountsResult.Unavailable(502, origin),
            MockResponse().setResponseCode(204) to ClaudeAccountsResult.Unavailable(204, origin),
        )
        for ((index, case) in cases.withIndex()) {
            server.enqueue(case.first)
            assertEquals("case $index", case.second, accounts.list())
            take()
        }
    }

    /** server.mjs `claudeAccountReply`: an unknown account is 404, a profile Tether does not drive 409, each with its sentence. */
    @Test fun aStatusRefusalCarriesTheServersSentence() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", json).setBody("""{"error":"No such Claude account."}"""))
        assertEquals(ClaudeAccountsResult.Refused(404, "No such Claude account.", origin), accounts.status("claude-gone"))
        take()
        val notManaged = "That profile's CLAUDE_CONFIG_DIR is not a Tether-managed account directory, so Tether will not sign it in or out. Edit it in Settings → Engines instead."
        server.enqueue(MockResponse().setResponseCode(409).setHeader("Content-Type", json).setBody("""{"error":"$notManaged","error_code":"not-managed"}"""))
        assertEquals(ClaudeAccountsResult.Refused(409, notManaged.take(ClaudeAccountsJson.MAX_TEXT), origin), accounts.status("claude-zai"))
        take()
        // A refusal that is not Tether's JSON, or carries no sentence, is just unavailable.
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "text/plain").setBody("""{"error":"x"}"""))
        assertEquals(ClaudeAccountsResult.Unavailable(404, origin), accounts.status("claude-a"))
        take()
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", json).setBody("""{"error":42}"""))
        assertEquals(ClaudeAccountsResult.Unavailable(404, origin), accounts.status("claude-a"))
        take()
        // A refusal over the cap is not read.
        val small = HttpClaudeAccounts(noRedirects, authority = { authority }, maxBytes = 64)
        server.enqueue(MockResponse().setResponseCode(409).setHeader("Content-Type", json).setBody("""{"error":"${"x".repeat(200)}"}"""))
        assertEquals(ClaudeAccountsResult.Unavailable(409, origin), small.status("claude-a"))
        take()
    }

    /** A 200 is Tether's answer only in its own type and shape (a gateway's `{"error":"login required"}` is not). */
    @Test fun aTwoHundredThatIsNotTethersAnswerIsUnavailable() = runBlocking<Unit> {
        val cases = listOf(
            MockResponse().setHeader("Content-Type", "text/plain").setBody(ClaudeAccountsFixtures.LIST_JSON),
            MockResponse().setBody(ClaudeAccountsFixtures.LIST_JSON).removeHeader("Content-Type"),
            MockResponse().setHeader("Content-Type", "application/problem+json").setBody(ClaudeAccountsFixtures.LIST_JSON),
            ok("""{"error":"login required"}"""),
            ok("{}"),
            ok("not json"),
            ok("[]"),
            ok(""),
            ok("[".repeat(200_000)),
        )
        for ((index, response) in cases.withIndex()) {
            server.enqueue(response)
            assertEquals("list case $index", ClaudeAccountsResult.Unavailable(200, origin), accounts.list())
            take()
        }
        for (body in listOf("""{"error":"login required"}""", """{"lastResult":null}""", "nope")) {
            server.enqueue(ok(body))
            assertEquals(body, ClaudeAccountsResult.Unavailable(200, origin), accounts.sync())
            take()
        }
        for (body in listOf("""{"ok":true}""", """{"loggedIn":"yes"}""", "<html/>")) {
            server.enqueue(ok(body))
            assertEquals(body, ClaudeAccountsResult.Unavailable(200, origin), accounts.status("claude-a"))
            take()
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "Application/JSON; charset=utf-8").setBody(ClaudeAccountsFixtures.LIST_JSON))
        assertTrue(accounts.list() is ClaudeAccountsResult.Ok)
        take()
    }

    @Test fun aHugeBodyIsDroppedWhetherDeclaredOrStreamed() = runBlocking<Unit> {
        val small = HttpClaudeAccounts(noRedirects, authority = { authority }, maxBytes = 1024)
        val padded = """{"accounts":[],"pad":"${"x".repeat(4096)}"}"""
        server.enqueue(ok(padded))
        assertEquals(ClaudeAccountsResult.Unavailable(200, origin), small.list())
        take()
        server.enqueue(MockResponse().setHeader("Content-Type", json).setChunkedBody(padded, 128))
        assertEquals(ClaudeAccountsResult.Unavailable(200, origin), small.list())
        take()
        val prefix = """{"accounts":[],"pad":""""
        val exact = prefix + "x".repeat(1024 - prefix.length - 2) + "\"}"
        assertEquals(1024, exact.length)
        server.enqueue(MockResponse().setHeader("Content-Type", json).setChunkedBody(exact, 100))
        assertEquals(ClaudeAccountsResult.Ok(emptyList<ClaudeAccount>(), origin), small.list())
        take()
    }

    @Test fun theDefaultCapIsAQuarterMebibyte() = runBlocking<Unit> {
        server.enqueue(MockResponse().setHeader("Content-Type", json).setChunkedBody("{\"accounts\":[],\"pad\":\"" + "x".repeat(270_000) + "\"}", 8192))
        assertEquals(ClaudeAccountsResult.Unavailable(200, origin), accounts.list())
        take()
    }

    @Test fun signedOutOrLocalNetworkBlockedSendsNothing() = runBlocking<Unit> {
        authority = FilesAuthority.SignedOut
        assertEquals(ClaudeAccountsResult.SignedOut(), accounts.list())
        assertEquals(ClaudeAccountsResult.SignedOut(), accounts.sync())
        assertEquals(ClaudeAccountsResult.SignedOut(), accounts.status("claude-a"))
        authority = FilesAuthority.LocalNetworkBlocked
        assertEquals(ClaudeAccountsResult.LocalNetworkBlocked, accounts.list())
        assertEquals(ClaudeAccountsResult.LocalNetworkBlocked, accounts.status("claude-a"))
        assertEquals(0, server.requestCount)
    }

    /** Each call reads the authority afresh and tags the answer with the origin it asked, so a screen can drop another server's reply. */
    @Test fun eachCallIsTaggedWithTheOriginItAsked() = runBlocking<Unit> {
        server.enqueue(ok(ClaudeAccountsFixtures.LIST_JSON))
        assertEquals(origin, accounts.list().origin)
        assertEquals("Bearer tthr_test", take().getHeader("Authorization"))
        authority = FilesAuthority.Paired(elsewhere.url("/")) { it.header("Authorization", "Bearer tthr_other") }
        elsewhere.enqueue(ok(ClaudeAccountsFixtures.LIST_JSON))
        val b = accounts.list()
        assertEquals("http://${elsewhere.hostName}:${elsewhere.port}", b.origin)
        assertEquals("Bearer tthr_other", elsewhere.takeRequest(20, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        elsewhere.enqueue(MockResponse().setResponseCode(500))
        assertEquals(b.origin, accounts.status("claude-work").origin)
        assertEquals(1, server.requestCount)
    }

    @Test fun anUnreachableOrHangingServerIsUnavailable() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(ClaudeAccountsResult.Unavailable(null, origin), accounts.list())
        val quick = HttpClaudeAccounts(noRedirects, authority = { authority }, callTimeoutMs = 300)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(ClaudeAccountsResult.Unavailable(null, origin), withTimeout(10_000) { quick.status("claude-a") })
    }

    @Test fun cancellingTheCallerCancelsTheRequest() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = async(start = CoroutineStart.UNDISPATCHED) { accounts.list() }
        take()
        val started = System.nanoTime()
        call.cancel()
        withTimeout(5_000) { call.join() }
        assertTrue(call.isCancelled)
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
    }

    @Test fun aClientThatFollowsRedirectsIsRefused() {
        val refused = runCatching { HttpClaudeAccounts(OkHttpClient(), authority = { authority }) }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
        assertNull(runCatching { HttpClaudeAccounts(noRedirects, authority = { authority }) }.exceptionOrNull())
    }
}
