package com.tether.app.ui.usage

import com.tether.app.client.AccountsUsage
import com.tether.app.client.ClaudeClaimAnswer
import com.tether.app.client.CodexConsumeAnswer
import com.tether.app.client.FixedRouteHttp
import com.tether.app.client.UsageAnalytics
import com.tether.app.client.UsageCall
import com.tether.app.client.UsageFailure
import com.tether.app.client.UsageJson
import com.tether.app.client.UsageSource
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * T9.2 test payloads in the server's shapes (usage-analytics.mjs `summarize` + usage-service,
 * server.mjs `computeAccountsUsage`), read through the app's own parser, and a fixed clock:
 * Monday 2026-10-12 07:00Z, inside a DeepSeek peak window (06:00–10:00Z, not a holiday).
 */
object UsageFixtures {
    val NOW: Long = Instant.parse("2026-10-12T07:00:00Z").toEpochMilli()
    const val ORIGIN = "https://tether.example.test"
    val env = UsageEnv(now = { NOW }, locale = Locale.US, zone = java.time.ZoneId.of("UTC"), fixedNow = NOW)

    private val heat = List(168) { i -> if (i / 24 in 1..5 && i % 24 in 7..18) ((i * 37) % 900 + 50).toString() else "0" }.joinToString(",")

    private val days = (1..12).joinToString(",") { d ->
        val day = "2026-10-%02d".format(d)
        val read = 800_000 + d * 210_000
        """{"day":"$day","tokens":${read + 40_000 + 60_000 * (d % 4) + 20_000},"turns":${10 + d},"sessions":${1 + d % 3},"input":20000,"output":${60_000 * (d % 4)},"cacheRead":$read,"cacheCreation":40000}"""
    }

    val ANALYTICS_JSON: String = """
        {"ok":true,"generatedAt":$NOW,
         "totals":{"sessions":48,"tokens":28400000,"turns":1260,"input":312000,"output":1480000,"cacheRead":25900000,"cacheCreation":708000,
                   "subagentTurns":96,"subagentTokens":2100000,"costUSD":214.37,"costPartial":true,"avgTokensPerSession":591667,"cacheEfficiencyPct":96.2},
         "mostUsed":{"harness":{"key":"claude","value":22100000,"sharePct":77.8},"provider":{"key":"anthropic","value":22100000,"sharePct":77.8},
                     "agent":{"key":"code-reviewer","value":31,"sharePct":42.5},"model":{"key":"claude-opus-4-7","value":15800000,"sharePct":55.6}},
         "byHarness":[{"key":"claude","tokens":22100000,"sessions":34,"turns":980},{"key":"codex","tokens":5200000,"sessions":11,"turns":240},{"key":"dsh","tokens":1100000,"sessions":3,"turns":40}],
         "byProvider":[{"key":"anthropic","tokens":22100000,"sessions":34,"turns":980},{"key":"openai","tokens":5200000,"sessions":11,"turns":240},{"key":"deepseek","tokens":1100000,"sessions":3,"turns":40}],
         "byModel":[{"key":"claude-opus-4-7","tokens":15800000,"sessions":20},{"key":"claude-sonnet-4-6","tokens":6300000,"sessions":14},{"key":"gpt-5.5-codex","tokens":5200000,"sessions":11},{"key":"deepseek-flash","tokens":1100000,"sessions":3}],
         "byAgent":[{"key":"code-reviewer","count":31},{"key":"verifier","count":24},{"key":"executor","count":18}],
         "byTool":[{"key":"Read","count":820},{"key":"Bash","count":610},{"key":"Edit","count":402},{"key":"Grep","count":233},{"key":"Write","count":96},{"key":"Glob","count":71},{"key":"WebFetch","count":12},{"key":"Task","count":9},{"key":"TodoWrite","count":4}],
         "byProject":[{"key":"/home/op/git/tether-android","tokens":17000000,"sessions":30},{"key":"/home/op/git/tether","tokens":9000000,"sessions":14},{"key":"/tmp","tokens":2400000,"sessions":4}],
         "byDay":[$days],
         "heat":[$heat],
         "effort":{"medium":310,"high":512,"low":40},"web":{"search":3,"fetch":12},
         "tokenComposition":{"input":312000,"output":1480000,"cacheRead":25900000,"cacheCreation":708000},
         "topSessions":[
           {"sessionId":"0f3c9a1b-77aa","harness":"claude","model":"claude-opus-4-7","provider":"anthropic","cwd":"/home/op/git/tether-android","tokens":6200000,"turns":210,"updatedAt":$NOW,"input":40000,"output":310000,"cacheRead":5700000,"cacheCreation":150000,"costUSD":61.4,"costPartial":false},
           {"sessionId":"7d21e0c4-1b2c","harness":"codex","model":"gpt-5.5-codex","provider":"openai","cwd":"/home/op/git/tether","tokens":3100000,"turns":96,"updatedAt":$NOW,"input":90000,"output":240000,"cacheRead":2700000,"cacheCreation":0,"costUSD":0,"costPartial":true},
           {"sessionId":"b88f02aa-9c3d","harness":"dsh","model":null,"provider":"deepseek","cwd":null,"tokens":900000,"turns":22,"updatedAt":$NOW,"input":30000,"output":80000,"cacheRead":790000,"cacheCreation":0,"costUSD":0.42,"costPartial":true}]}
    """.trimIndent()

    val EMPTY_JSON: String = """{"ok":true,"generatedAt":$NOW,"totals":{"sessions":0,"tokens":0,"turns":0},"mostUsed":{},"byHarness":[],"byDay":[],"heat":[],"effort":{},"topSessions":[]}"""

    private const val H = 3_600_000L

    val ACCOUNTS_JSON: String = """
        {"ok":true,"generatedAt":$NOW,
         "claude":[
          {"id":"default","label":"Host default","isDefault":true,"managed":false,
           "windows":{"fiveHour":{"usedPercent":42,"windowMinutes":300,"resetsAt":${NOW + 2 * H + 15 * 60_000}},"weekly":{"usedPercent":91,"windowMinutes":10080,"resetsAt":${NOW + 50 * H}}},
           "at":${NOW - 3 * 60_000},"rateLimited":false,"source":"http",
           "resetGrants":{"grants":{"eligible":true,"atLimit":true,"exhausted":["seven_day"],"availableCount":1,"nextGrantId":"g_1",
              "grants":[{"id":"g_1","label":"Usage-limit reset","resetsTotal":1,"resetsLeft":1,"endsAt":${NOW + 26 * 24 * H},"clears":["five_hour","seven_day"],"paused":false,"usableNow":true,"useRequiresLimit":false},
                        {"id":"g_0","label":"Welcome reset","resetsTotal":1,"resetsLeft":0,"endsAt":${NOW + 3 * 24 * H},"clears":["five_hour"],"paused":false,"usableNow":false,"useRequiresLimit":false}],
              "weeklyResetsAt":${NOW + 50 * H}},
            "at":${NOW - 3 * 60_000},"rateLimited":false,"tokenExpired":false}},
          {"id":"claude-work","label":"Work","isDefault":false,"managed":true,
           "windows":{"fiveHour":{"usedPercent":78,"windowMinutes":300,"resetsAt":${NOW + 40 * 60_000}},"weekly":{"usedPercent":33,"windowMinutes":10080,"resetsAt":${NOW + 120 * H}}},
           "at":${NOW - 25 * 60_000},"rateLimited":true,"source":"http",
           "resetGrants":{"grants":{"eligible":false,"ineligibleReason":"tier","atLimit":false,"exhausted":[],"availableCount":0,"grants":[]},"at":${NOW - 25 * 60_000},"rateLimited":false,"tokenExpired":false}},
          {"id":"claude-old","label":"Old laptop","isDefault":false,"managed":true,"windows":null,"tokenExpired":true,"source":"expired"}],
         "codex":[{"id":"codex","label":"Codex","isDefault":true,"managed":true,"source":"live",
                   "windows":{"weekly":{"usedPercent":12,"windowMinutes":10080,"resetsAt":${NOW + 90 * H}}},
                   "at":${NOW - 60_000},
                   "resetCredits":{"availableCount":2,"credits":[{"id":"cr_1","status":"available","resetType":"codexRateLimits","title":"Monthly reset","expiresAt":${NOW + 10 * 24 * H}},{"id":"cr_2","status":"available","resetType":"codexRateLimits"}]}}],
         "deepseek":{"ok":true,
           "accounts":[{"id":"ds-1","fingerprints":["1a2b3c4d"],"sources":["dsh","pi"],"identity":"key","weakEvidence":false,"at":${NOW - 2 * 60_000},"stale":false,
                        "balance":{"is_available":true,"balances":[{"currency":"USD","total":"12.00","granted":"2.00","topped_up":"10.00"}]}}],
           "unavailable":[{"fingerprint":"9a8b7c6d","sources":["opencode"],"error":"HTTP 401"}]}}
    """.trimIndent()

    fun analytics(json: String = ANALYTICS_JSON): UsageAnalytics = UsageJson.analytics(FixedRouteHttp.parseObject(json)!!)!!
    fun accounts(json: String = ACCOUNTS_JSON): AccountsUsage = UsageJson.accounts(FixedRouteHttp.parseObject(json)!!)!!
}

/** A [UsageSource] that records every call and answers from queues (default: the fixtures). */
class FakeUsageSource : UsageSource {
    data class Call(val kind: String, val origin: String?, val a: String?, val b: String?)

    val calls = mutableListOf<Call>()
    val analyticsAnswers = ArrayDeque<UsageCall<UsageAnalytics>>()
    val accountsAnswers = ArrayDeque<UsageCall<AccountsUsage>>()
    val consumeAnswers = ArrayDeque<UsageCall<CodexConsumeAnswer>>()
    val claimAnswers = ArrayDeque<UsageCall<ClaudeClaimAnswer>>()

    override suspend fun analytics(origin: String?, since: String?): UsageCall<UsageAnalytics> {
        calls += Call("analytics", origin, since, null)
        return analyticsAnswers.removeFirstOrNull() ?: UsageCall.Ok(UsageFixtures.analytics(), origin ?: "")
    }

    override suspend fun accounts(origin: String?, force: String?): UsageCall<AccountsUsage> {
        calls += Call("accounts", origin, force, null)
        return accountsAnswers.removeFirstOrNull() ?: UsageCall.Ok(UsageFixtures.accounts(), origin ?: "")
    }

    override suspend fun consumeResetCredit(origin: String?, creditId: String?, sessionId: String?): UsageCall<CodexConsumeAnswer> {
        calls += Call("consume", origin, creditId, sessionId)
        return consumeAnswers.removeFirstOrNull() ?: UsageCall.Ok(CodexConsumeAnswer("reset"), origin ?: "")
    }

    override suspend fun claimResetGrant(origin: String?, accountId: String, grantId: String): UsageCall<ClaudeClaimAnswer> {
        calls += Call("claim", origin, accountId, grantId)
        return claimAnswers.removeFirstOrNull() ?: UsageCall.Failed(UsageFailure.Unreachable)
    }

    fun of(kind: String) = calls.filter { it.kind == kind }
}
