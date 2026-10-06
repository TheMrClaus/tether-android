package com.tether.app.client

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T9.2 wire tests, against the web's own requests and the server's answers (tether main):
 * usage-dashboard.tsx:96 `GET /api/usage?since=YYYY-MM-DD`, usage-accounts-dialog.tsx:532
 * `GET /api/usage/accounts?force=<all|id>`, codex-reset-credit-dialog.tsx:116-123 and
 * claude-reset-grant-dialog.tsx:76-80 (POST, JSON, each key only when it has a value), server.mjs
 * 7917-8027 (shapes, owner-grade 403, 400/502 `{ error }`, `{ ok: true, ...result }`).
 */
class UsageHttpTest {
    private val server = MockWebServer()
    private val noRedirects = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private var authority: FilesAuthority = FilesAuthority.SignedOut
    private lateinit var usage: HttpUsage
    private val json = "application/json; charset=utf-8"

    @Before fun setUp() {
        server.start()
        authority = FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_test") }
        usage = HttpUsage(noRedirects, authority = { authority })
    }

    @After fun tearDown() = server.shutdown()

    private val origin get() = "http://${server.hostName}:${server.port}"

    private fun take(): RecordedRequest = server.takeRequest(20, TimeUnit.SECONDS) ?: error("no request reached the server")

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", json).setBody(body)

    @Test fun analyticsAsksTheRangeAndReadsTheSummary() = runBlocking<Unit> {
        server.enqueue(ok(UsageWireFixtures.ANALYTICS))
        val call = usage.analytics(origin, "2026-09-03")
        val req = take()
        assertEquals("GET", req.method)
        assertEquals("/api/usage?since=2026-09-03", req.path)
        assertEquals("Bearer tthr_test", req.getHeader("Authorization"))
        val a = (call as UsageCall.Ok).value
        assertEquals(1_759_478_400_000.0, a.generatedAt, 0.0)
        assertEquals(12.0, a.totals.sessions, 0.0)
        assertEquals(96.3, a.totals.cacheEfficiencyPct, 0.0)
        assertTrue(a.totals.costPartial)
        assertEquals("claude", a.mostUsed.harness?.key)
        assertEquals(81.2, a.mostUsed.harness!!.sharePct, 0.0)
        assertNull(a.mostUsed.agent)
        assertEquals(listOf("claude", "codex"), a.byHarness.map { it.key })
        assertEquals(3.0, a.byHarness[1].turns, 0.0)
        assertEquals(listOf("Read" to 40.0, "Bash" to 12.0), a.byTool.map { it.key to it.count })
        assertEquals(listOf("2026-10-01", "2026-10-02"), a.byDay.map { it.day })
        assertEquals(168, a.heat.size)
        assertEquals(listOf("high" to 9.0, "low" to 2.0), a.effort)
        assertEquals("/home/op/git/tether", a.topSessions.single().cwd)
    }

    @Test fun allTimeSendsNoQuery() = runBlocking<Unit> {
        server.enqueue(ok(UsageWireFixtures.ANALYTICS))
        usage.analytics(origin, null)
        assertEquals("/api/usage", take().path)
    }

    @Test fun accountsForceIsEncodedLikeEncodeUriComponent() = runBlocking<Unit> {
        server.enqueue(ok(UsageWireFixtures.ACCOUNTS))
        server.enqueue(ok(UsageWireFixtures.ACCOUNTS))
        server.enqueue(ok(UsageWireFixtures.ACCOUNTS))
        usage.accounts(origin, null)
        assertEquals("/api/usage/accounts", take().path)
        usage.accounts(origin, "all")
        assertEquals("/api/usage/accounts?force=all", take().path)
        usage.accounts(origin, "claude work&x=1")
        assertEquals("/api/usage/accounts?force=claude%20work%26x%3D1", take().path)
    }

    @Test fun accountsPayloadParses() = runBlocking<Unit> {
        server.enqueue(ok(UsageWireFixtures.ACCOUNTS))
        val a = (usage.accounts(origin, null) as UsageCall.Ok).value
        val work = a.claude.first()
        assertEquals("default", work.id)
        assertTrue(work.isDefault)
        assertEquals(42.0, work.windows?.fiveHour?.usedPercent)
        assertEquals("http", work.source)
        val grants = work.resetGrants!!
        assertEquals(JsonPrimitive(true), grants.summary!!["eligible"])
        assertFalse(grants.tokenExpired)
        val codex = a.codex.single()
        assertNull(codex.windows?.fiveHour)
        assertEquals(1440.0, codex.windows!!.other.single().windowMinutes, 0.0)
        assertEquals(2.0, codex.resetCredits!!.availableCount, 0.0)
        assertEquals("cr_1", codex.resetCredits!!.credits!!.first().id)
        val ds = a.deepseek!!
        assertTrue(ds.ok)
        assertEquals(listOf("dsh", "pi"), ds.accounts.single().sources)
        assertEquals("12.00", ds.accounts.single().balances.single().total)
        assertEquals("9a8b7c6d", ds.unavailable.single().fingerprint)
    }

    @Test fun consumeSendsOnlyTheKeysItHas() = runBlocking<Unit> {
        server.enqueue(ok("""{"ok":true,"outcome":"reset","windows":null,"resetCredits":null}"""))
        val call = usage.consumeResetCredit(origin, "cr_1", null)
        val req = take()
        assertEquals("POST", req.method)
        assertEquals("/api/codex/reset-credits/consume", req.path)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("""{"creditId":"cr_1"}""", req.body.readUtf8())
        assertEquals("reset", (call as UsageCall.Ok).value.outcome)

        server.enqueue(ok("""{"ok":true,"outcome":"noCredit"}"""))
        usage.consumeResetCredit(origin, null, "sess-1")
        assertEquals("""{"sessionId":"sess-1"}""", take().body.readUtf8())

        server.enqueue(ok("""{"ok":true}"""))
        val bare = usage.consumeResetCredit(origin, null, null)
        assertEquals("{}", take().body.readUtf8())
        assertNull((bare as UsageCall.Ok).value.outcome)
    }

    @Test fun claimSendsAccountAndGrantAndKeepsTheAnswer() = runBlocking<Unit> {
        server.enqueue(ok("""{"ok":true,"outcome":"not_limited","sent":true,"result":"not_limited","resetsLeft":1}"""))
        val call = usage.claimResetGrant(origin, "claude-work", "g_123")
        val req = take()
        assertEquals("POST", req.method)
        assertEquals("/api/usage/claude-reset-grants/claim", req.path)
        assertEquals("""{"accountId":"claude-work","grantId":"g_123"}""", req.body.readUtf8())
        val answer = (call as UsageCall.Ok).value
        assertTrue(answer.sent)
        assertEquals(JsonPrimitive("not_limited"), answer.body["outcome"])

        server.enqueue(ok("""{"ok":true,"outcome":"rate_limited","sent":false}"""))
        assertFalse((usage.claimResetGrant(origin, "claude-work", "g_123") as UsageCall.Ok).value.sent)
        take()
    }

    @Test fun theServersRefusalCarriesItsSentence() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("Content-Type", json)
                .setBody("""{"error":"This needs an owner sign-in (password, passkey, the SSO gateway or the paired Tether app)."}"""),
        )
        val call = usage.claimResetGrant(origin, "a", "g")
        take()
        assertEquals(
            UsageCall.Failed(UsageFailure.Http(403, "This needs an owner sign-in (password, passkey, the SSO gateway or the paired Tether app).")),
            call,
        )
        server.enqueue(MockResponse().setResponseCode(502).setHeader("Content-Type", json).setBody("""{"error":"Could not reach Codex to redeem that reset. Confirm Codex is logged in and try again."}"""))
        assertEquals(
            UsageCall.Failed(UsageFailure.Http(502, "Could not reach Codex to redeem that reset. Confirm Codex is logged in and try again.")),
            usage.consumeResetCredit(origin, null, null),
        )
        take()
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        assertEquals(UsageCall.Failed(UsageFailure.Http(500, null)), usage.analytics(origin, null))
        take()
    }

    @Test fun aPostThatAnswersWithoutJsonStillSettles() = runBlocking<Unit> {
        // The web's `response.json().catch(() => ({}))`: an OK answer with no JSON is an empty answer.
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        assertEquals(UsageCall.Ok(CodexConsumeAnswer(null), origin), usage.consumeResetCredit(origin, null, null))
        take()
    }

    @Test fun aGetWithoutItsShapeIsUnusable() = runBlocking<Unit> {
        server.enqueue(ok("""{"error":"login required"}"""))
        assertEquals(UsageCall.Failed(UsageFailure.Unusable(200)), usage.accounts(origin, null))
        take()
        server.enqueue(ok("""{"ok":true}"""))
        assertEquals(UsageCall.Failed(UsageFailure.Unusable(200)), usage.analytics(origin, null))
        take()
    }

    @Test fun aRedirectIsAGatewayAndIsNeverFollowed() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://sso.example.test/login"))
        assertEquals(UsageCall.Failed(UsageFailure.Blocked(302)), usage.analytics(origin, null))
        take()
        assertEquals(1, server.requestCount)
    }

    @Test fun aScreenDrawnWithNoRecordedServerReachesTheCurrentOne() = runBlocking<Unit> {
        // ta-coik.70: the web's usage fetches are relative same-origin; no recorded origin is not a refusal.
        server.enqueue(ok(UsageWireFixtures.ACCOUNTS))
        assertTrue(usage.accounts(null, null) is UsageCall.Ok)
        assertEquals("/api/usage/accounts", take().path)
        server.enqueue(ok("""{"ok":true,"outcome":"reset"}"""))
        assertTrue(usage.consumeResetCredit(null, "cr_1", null) is UsageCall.Ok)
        assertEquals(UsageSource.CONSUME_PATH, take().path)
    }

    @Test fun nothingIsSentToAnotherServerOrWithoutACredential() = runBlocking<Unit> {
        assertEquals(UsageCall.Failed(UsageFailure.OtherServer), usage.accounts("https://other.example.test", null))
        authority = FilesAuthority.SignedOut
        assertEquals(UsageCall.Failed(UsageFailure.SignedOut), usage.claimResetGrant(origin, "a", "g"))
        assertEquals(0, server.requestCount)
    }

    @Test fun aDroppedConnectionIsUnreachable() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(UsageCall.Failed(UsageFailure.Unreachable), usage.consumeResetCredit(origin, "cr", null))
    }

    @Test fun anOversizedBodyIsDroppedUnread() = runBlocking<Unit> {
        val small = HttpUsage(noRedirects, authority = { authority }, maxBytes = 64)
        server.enqueue(ok(UsageWireFixtures.ANALYTICS))
        assertEquals(UsageCall.Failed(UsageFailure.Unusable(200)), small.analytics(origin, null))
        take()
    }

    @Test fun theQueryHelperRefusesAnythingButOneWord() = runBlocking<Unit> {
        val route = FixedRouteHttp(noRedirects, 1024, 5_000)
        val out = route.call(authority, origin, FixedRouteHttp.Method.GET, "/api/usage", query = "since&x" to "1")
        assertTrue(out is FixedRouteHttp.Outcome.NotBuilt)
        assertEquals(0, server.requestCount)
    }

    @Test fun lenientFieldsReadAsTheWebsDo() {
        val obj = FixedRouteHttp.parseObject("""{"totals":{"sessions":"7","tokens":null,"costPartial":1}}""") as JsonObject
        val a = UsageJson.analytics(obj)!!
        assertEquals(0.0, a.totals.sessions, 0.0) // a numeric string is not a number
        assertEquals(0.0, a.totals.tokens, 0.0)
        assertTrue(a.totals.costPartial) // JS truthiness
        assertTrue(a.byDay.isEmpty())
    }
}

/** Payloads in the server's shapes (usage-analytics.mjs `summarize` + usage-service; server.mjs `computeAccountsUsage`). */
object UsageWireFixtures {
    val ANALYTICS: String = """
        {"ok":true,"generatedAt":1759478400000,
         "totals":{"sessions":12,"tokens":5400000,"turns":180,"input":120000,"output":300000,"cacheRead":4800000,"cacheCreation":180000,
                   "subagentTurns":14,"subagentTokens":410000,"costUSD":38.12,"costPartial":true,"avgTokensPerSession":450000,"cacheEfficiencyPct":96.3},
         "mostUsed":{"harness":{"key":"claude","value":4384800,"sharePct":81.2},"harnessBySessions":null,"harnessByTurns":null,
                     "provider":{"key":"anthropic","value":4384800,"sharePct":81.2},"agent":null,"model":{"key":"claude-opus-4-7","value":3000000,"sharePct":55.6}},
         "byHarness":[{"key":"claude","tokens":4384800,"sessions":9,"turns":150},{"key":"codex","tokens":1015200,"sessions":3,"turns":3}],
         "byProvider":[{"key":"anthropic","tokens":4384800,"sessions":9,"turns":150}],
         "byModel":[{"key":"claude-opus-4-7","tokens":3000000,"sessions":5,"turns":90}],
         "byAgent":[],"byTool":[{"key":"Read","count":40},{"key":"Bash","count":12}],
         "byProject":[{"key":"/home/op/git/tether","tokens":5000000,"sessions":10,"turns":170}],
         "byDay":[{"day":"2026-10-01","tokens":2000000,"turns":80,"sessions":5,"input":50000,"output":100000,"cacheRead":1800000,"cacheCreation":50000},
                  {"day":"2026-10-02","tokens":3400000,"turns":100,"sessions":7,"input":70000,"output":200000,"cacheRead":3000000,"cacheCreation":130000}],
         "heat":[${List(168) { if (it == 33) "900" else "0" }.joinToString(",")}],
         "effort":{"high":9,"low":2},"web":{"search":1,"fetch":2},
         "tokenComposition":{"input":120000,"output":300000,"cacheRead":4800000,"cacheCreation":180000},
         "topSessions":[{"sessionId":"0f3c9a1b-aaaa","harness":"claude","model":"claude-opus-4-7","provider":"anthropic","cwd":"/home/op/git/tether",
                         "tokens":2600000,"turns":60,"updatedAt":1759470000000,"input":1,"output":2,"cacheRead":3,"cacheCreation":4,"costUSD":12.5,"costPartial":false}]}
    """.trimIndent()

    val ACCOUNTS: String = """
        {"ok":true,"generatedAt":1759478400000,
         "claude":[{"id":"default","label":"Host default","isDefault":true,"managed":false,
                    "windows":{"fiveHour":{"usedPercent":42,"windowMinutes":300,"resetsAt":1759485600000},"weekly":{"usedPercent":91,"windowMinutes":10080,"resetsAt":1759700000000}},
                    "at":1759478300000,"rateLimited":false,"source":"http",
                    "resetGrants":{"grants":{"eligible":true,"atLimit":false,"exhausted":[],"availableCount":1,"nextGrantId":"g_1",
                                             "grants":[{"id":"g_1","label":"Usage-limit reset","resetsTotal":1,"resetsLeft":1,"endsAt":1761000000000,
                                                        "clears":["five_hour","seven_day"],"paused":false,"usableNow":true,"useRequiresLimit":false}],
                                             "weeklyResetsAt":1759700000000},
                                   "at":1759478300000,"rateLimited":false,"tokenExpired":false}}],
         "codex":[{"id":"codex","label":"Codex","isDefault":true,"managed":true,"source":"live",
                   "windows":{"weekly":{"usedPercent":12,"windowMinutes":10080},"other":[{"usedPercent":5,"windowMinutes":1440}]},
                   "at":1759478390000,"resetCredits":{"availableCount":2,"credits":[{"id":"cr_1","status":"available","resetType":"codexRateLimits","title":"Monthly reset","expiresAt":1760000000000}]}}],
         "deepseek":{"ok":true,
                     "accounts":[{"id":"ds-1","fingerprints":["1a2b3c4d"],"sources":["dsh","pi"],"identity":"key","weakEvidence":false,"at":1759478000000,"stale":false,
                                  "balance":{"is_available":true,"balances":[{"currency":"USD","total":"12.00","granted":"2.00","topped_up":"10.00"}]}}],
                     "unavailable":[{"fingerprint":"9a8b7c6d","sources":["opencode"],"error":"HTTP 401"}]}}
    """.trimIndent()
}
