package com.tether.app.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ─────────────────────────────────────────────────────────────────────────────
// T9.2: the Usage page and the Accounts dialog (tether server.mjs:7917-8027).
//   GET  /api/usage[?since=YYYY-MM-DD]          usage-service.mjs `analytics` (usage-analytics.mjs `summarize`)
//   GET  /api/usage/accounts[?force=all|<id>]   server.mjs `computeAccountsUsage`
//   POST /api/codex/reset-credits/consume       { creditId?, sessionId? } → { ok, outcome, windows, resetCredits }
//   POST /api/usage/claude-reset-grants/claim   { accountId, grantId } → { ok, ...claimResetGrant(...) }
// The two GETs sit behind the ordinary /api/ gate; the two POSTs are `requireOwnerGrade`, which the
// paired app is (server.mjs:3327-3343), exactly as the web console's sign-in is. Every call goes
// through [FixedRouteHttp] (redirects off, the fixed route only, on the server the screen was drawn
// from, the body JSON as a cookie-authenticated POST needs). The client adds no rule of its own:
// what the server refuses is shown as the web shows it.
// ─────────────────────────────────────────────────────────────────────────────

/** What one call came to. */
sealed interface UsageCall<out T> {
    data class Ok<T>(val value: T, val origin: String) : UsageCall<T>

    data class Failed(val failure: UsageFailure) : UsageCall<Nothing>
}

/** Why a call has no answer to show. Each screen words these as the web words its own failures. */
sealed interface UsageFailure {
    /** No credential: nothing was sent. */
    data object SignedOut : UsageFailure

    data object LocalNetworkBlocked : UsageFailure

    /** The client is signed in to another server than the one the screen shows: nothing was sent. */
    data object OtherServer : UsageFailure

    /** No answer at all (the web's `TypeError` from `fetch()`). */
    data object Unreachable : UsageFailure

    /** A sign-in gateway answered instead of Tether (T6.8's rule). */
    data class Blocked(val code: Int) : UsageFailure

    /** Tether answered [code] (not 2xx); [error] is its JSON `error` sentence when it gave one (raw server text, bounded). */
    data class Http(val code: Int, val error: String?) : UsageFailure

    /** A 2xx whose body is not this route's answer (the web's `r.json()` failing). */
    data class Unusable(val code: Int) : UsageFailure
}

// ---- GET /api/usage --------------------------------------------------------------------------

/** usage-dashboard.tsx `UsagePayload.totals`. Missing numbers read as 0 (the web formats `n || 0`). */
data class UsageTotals(
    val sessions: Double,
    val tokens: Double,
    val turns: Double,
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheCreation: Double,
    val subagentTurns: Double,
    val subagentTokens: Double,
    val costUSD: Double,
    val costPartial: Boolean,
    val avgTokensPerSession: Double,
    val cacheEfficiencyPct: Double,
)

/** `TopEntry`: [key] is raw server text (bounded). */
data class UsageTopEntry(val key: String, val value: Double, val sharePct: Double)

data class UsageMostUsed(val harness: UsageTopEntry?, val provider: UsageTopEntry?, val agent: UsageTopEntry?, val model: UsageTopEntry?)

/** `DimObj` / `ModelDim`: [key] is raw server text (bounded). */
data class UsageDimension(val key: String, val tokens: Double, val sessions: Double, val turns: Double)

/** `Scalar`. */
data class UsageCount(val key: String, val count: Double)

/** `DayPoint`: [day] is the server's `YYYY-MM-DD`. */
data class UsageDay(
    val day: String,
    val tokens: Double,
    val turns: Double,
    val sessions: Double,
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheCreation: Double,
)

data class UsageComposition(val input: Double, val output: Double, val cacheRead: Double, val cacheCreation: Double)

/** `Session` (the top-sessions table): ids, model and cwd are raw server text (bounded). */
data class UsageSession(
    val sessionId: String,
    val model: String?,
    val cwd: String?,
    val tokens: Double,
    val turns: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheCreation: Double,
    val costUSD: Double,
    val costPartial: Boolean,
)

/** usage-dashboard.tsx `UsagePayload`, the parts the page draws. */
data class UsageAnalytics(
    val generatedAt: Double,
    val totals: UsageTotals,
    val mostUsed: UsageMostUsed,
    val byHarness: List<UsageDimension>,
    val byProvider: List<UsageDimension>,
    val byModel: List<UsageDimension>,
    val byAgent: List<UsageCount>,
    val byTool: List<UsageCount>,
    val byProject: List<UsageDimension>,
    val byDay: List<UsageDay>,
    /** 7 × 24 cells, Sunday first, UTC hours. */
    val heat: List<Double>,
    /** `effort`, in the server's key order. */
    val effort: List<Pair<String, Double>>,
    val tokenComposition: UsageComposition,
    val topSessions: List<UsageSession>,
)

// ---- GET /api/usage/accounts -----------------------------------------------------------------

/** lib/protocol.ts `UsageWindow`. */
data class AccountWindow(val usedPercent: Double?, val windowMinutes: Double, val resetsAt: Double?)

data class AccountWindows(val fiveHour: AccountWindow?, val weekly: AccountWindow?, val fable: AccountWindow?, val other: List<AccountWindow>)

/** `CodexResetCredit`, the fields the dialogs draw: [title] is raw server text (bounded). */
data class CodexResetCredit(val id: String, val title: String?, val expiresAt: Double?)

/** `CodexResetCreditsSummary`: [credits] null = the backend sent only a count. */
data class CodexResetCredits(val availableCount: Double, val credits: List<CodexResetCredit>?)

/**
 * `ClaudeResetGrantsReading`. [summary] is the `ClaudeResetGrantsSummary` object as sent (null when
 * none yet): the ported view helpers (ClaudeResetGrantsView) read it as the web's do.
 */
data class ClaudeResetGrantsReading(
    val summary: JsonObject?,
    val at: Double,
    val rateLimited: Boolean,
    val tokenExpired: Boolean,
    val unavailable: Boolean,
    val throttledByGuard: Boolean,
)

/** One account card (`AccountUsageEntry`, Codex's `CodexUsageEntry`). [id] and [label] are raw server text (bounded). */
data class AccountUsage(
    val id: String,
    val label: String,
    val isDefault: Boolean,
    val managed: Boolean,
    val windows: AccountWindows?,
    val at: Double?,
    val rateLimited: Boolean,
    /** A short code ("session", "http", "live", "unavailable"…), only ever compared. */
    val source: String?,
    val tokenExpired: Boolean,
    val throttledByGuard: Boolean,
    val retryAfter: Double?,
    val resetGrants: ClaudeResetGrantsReading?,
    val resetCredits: CodexResetCredits?,
)

/** `DeepSeekBalanceLine`: decimal strings as sent (raw server text, bounded). */
data class DeepSeekBalance(val currency: String, val total: String, val granted: String, val toppedUp: String)

/** `DeepSeekAccountReading`. [sources] are codes (`env`, `dsh`, …), looked up in a fixed table. */
data class DeepSeekAccount(
    val id: String,
    val fingerprints: List<String>,
    val sources: List<String>,
    val identity: String,
    val weakEvidence: Boolean,
    val at: Double?,
    val stale: Boolean,
    val isAvailable: Boolean,
    val balances: List<DeepSeekBalance>,
)

/** `DeepSeekUnavailableSource`: [error] is raw server text (bounded). */
data class DeepSeekUnavailable(val fingerprint: String, val sources: List<String>, val error: String)

/** `DeepSeekUsageEntry`: [ok] false carries [reason] (`no_key` / `api_error`) and maybe [error]. */
data class DeepSeekUsage(
    val ok: Boolean,
    val reason: String?,
    val error: String?,
    val accounts: List<DeepSeekAccount>,
    val unavailable: List<DeepSeekUnavailable>,
)

/** usage-accounts-dialog.tsx `AccountsUsagePayload`. */
data class AccountsUsage(
    val generatedAt: Double,
    val claude: List<AccountUsage>,
    val codex: List<AccountUsage>,
    val deepseek: DeepSeekUsage?,
)

// ---- the two POSTs ---------------------------------------------------------------------------

/** `/api/codex/reset-credits/consume`'s answer: [outcome] is the backend's code (raw, bounded), only ever compared. */
data class CodexConsumeAnswer(val outcome: String?)

/**
 * `/api/usage/claude-reset-grants/claim`'s answer (`ClaudeResetClaimResponse`), as sent: the ported
 * `claimOutcomeCopy` reads it as the web's does; [sent] is its `sent` flag (only `true` refreshes).
 */
data class ClaudeClaimAnswer(val body: JsonObject, val sent: Boolean)

/** The four calls, each to the server [origin] the screen was drawn from. */
interface UsageSource {
    suspend fun analytics(origin: String?, since: String?): UsageCall<UsageAnalytics>
    suspend fun accounts(origin: String?, force: String?): UsageCall<AccountsUsage>
    suspend fun consumeResetCredit(origin: String?, creditId: String?, sessionId: String?): UsageCall<CodexConsumeAnswer>
    suspend fun claimResetGrant(origin: String?, accountId: String, grantId: String): UsageCall<ClaudeClaimAnswer>

    /** No client (previews, fakes): nothing is ever sent. */
    object Unavailable : UsageSource {
        private val none = UsageCall.Failed(UsageFailure.SignedOut)
        override suspend fun analytics(origin: String?, since: String?): UsageCall<UsageAnalytics> = none
        override suspend fun accounts(origin: String?, force: String?): UsageCall<AccountsUsage> = none
        override suspend fun consumeResetCredit(origin: String?, creditId: String?, sessionId: String?): UsageCall<CodexConsumeAnswer> = none
        override suspend fun claimResetGrant(origin: String?, accountId: String, grantId: String): UsageCall<ClaudeClaimAnswer> = none
    }

    companion object {
        const val USAGE_PATH = "/api/usage"
        const val ACCOUNTS_PATH = "/api/usage/accounts"
        const val CONSUME_PATH = "/api/codex/reset-credits/consume"
        const val CLAIM_PATH = "/api/usage/claude-reset-grants/claim"

        /** The analytics answer lists every model, tool and day in the range; past this it is dropped unread. */
        const val MAX_BODY_BYTES: Long = 4L * 1024L * 1024L

        /** `computeAccountsUsage` may ask Anthropic, Codex and DeepSeek before it answers. */
        const val CALL_TIMEOUT_MS: Long = 60_000L
    }
}

/** [UsageSource] over [FixedRouteHttp] with the paired credential read per call through [authority]. */
class HttpUsage(
    http: okhttp3.OkHttpClient,
    private val authority: () -> FilesAuthority,
    maxBytes: Long = UsageSource.MAX_BODY_BYTES,
    callTimeoutMs: Long = UsageSource.CALL_TIMEOUT_MS,
) : UsageSource {
    private val route = FixedRouteHttp(http, maxBytes, callTimeoutMs)

    override suspend fun analytics(origin: String?, since: String?): UsageCall<UsageAnalytics> =
        get(origin, UsageSource.USAGE_PATH, since?.takeIf { it.isNotEmpty() }?.let { "since" to it }, UsageJson::analytics)

    override suspend fun accounts(origin: String?, force: String?): UsageCall<AccountsUsage> =
        get(origin, UsageSource.ACCOUNTS_PATH, force?.takeIf { it.isNotEmpty() }?.let { "force" to it }, UsageJson::accounts)

    override suspend fun consumeResetCredit(origin: String?, creditId: String?, sessionId: String?): UsageCall<CodexConsumeAnswer> {
        // codex-reset-credit-dialog.tsx: each key only when it has a value.
        val body = buildJsonObject {
            if (!creditId.isNullOrEmpty()) put("creditId", creditId)
            if (!sessionId.isNullOrEmpty()) put("sessionId", sessionId)
        }
        return post(origin, UsageSource.CONSUME_PATH, body) { CodexConsumeAnswer(UsageJson.text(it["outcome"], UsageJson.MAX_CODE)) }
    }

    override suspend fun claimResetGrant(origin: String?, accountId: String, grantId: String): UsageCall<ClaudeClaimAnswer> {
        val body = buildJsonObject {
            put("accountId", accountId)
            put("grantId", grantId)
        }
        return post(origin, UsageSource.CLAIM_PATH, body) { ClaudeClaimAnswer(it, UsageJson.truthy(it["sent"])) }
    }

    private suspend fun <T> get(origin: String?, path: String, query: Pair<String, String>?, parse: (JsonObject) -> T?): UsageCall<T> =
        settle(route.call(authority(), origin, FixedRouteHttp.Method.GET, path, query = query), parse = parse)

    /**
     * The two POSTs read their body however it came (the web's `response.json().catch(() => ({}))`):
     * a 2xx without a JSON object still settles, as an empty answer.
     */
    private suspend fun <T> post(origin: String?, path: String, body: JsonObject, parse: (JsonObject) -> T): UsageCall<T> =
        settle(route.call(authority(), origin, FixedRouteHttp.Method.POST, path, body), lenient = true, parse = parse)

    private fun <T> settle(outcome: FixedRouteHttp.Outcome, lenient: Boolean = false, parse: (JsonObject) -> T?): UsageCall<T> = when (outcome) {
        FixedRouteHttp.Outcome.SignedOut -> UsageCall.Failed(UsageFailure.SignedOut)
        FixedRouteHttp.Outcome.LocalNetworkBlocked -> UsageCall.Failed(UsageFailure.LocalNetworkBlocked)
        is FixedRouteHttp.Outcome.OtherOrigin -> UsageCall.Failed(UsageFailure.OtherServer)
        is FixedRouteHttp.Outcome.NotBuilt -> UsageCall.Failed(UsageFailure.Unreachable)
        is FixedRouteHttp.Outcome.Unreachable -> UsageCall.Failed(UsageFailure.Unreachable)
        is FixedRouteHttp.Outcome.Blocked -> UsageCall.Failed(UsageFailure.Blocked(outcome.code))
        is FixedRouteHttp.Outcome.Answered -> when {
            outcome.code !in 200..299 -> UsageCall.Failed(UsageFailure.Http(outcome.code, UsageJson.text(outcome.json?.get("error"), UsageJson.MAX_ERROR)?.takeIf { it.isNotBlank() }))
            else -> (outcome.json ?: if (lenient) JsonObject(emptyMap()) else null)?.let(parse)?.let { UsageCall.Ok(it, outcome.origin) }
                ?: UsageCall.Failed(UsageFailure.Unusable(outcome.code))
        }
    }
}

/**
 * The tolerant, bounded read of the two GET bodies: a field of the wrong type is absent (a number
 * reads 0, as the web's `n || 0`); every string is cut before it is kept; every list is bounded. A
 * body without its route's own shape (a gateway's `{"error":"login required"}`) is null.
 */
object UsageJson {
    /** Server text kept per key / label / model / path (drawn later by the label, code or path rule). */
    const val MAX_TEXT = 300

    /** A server sentence shown as the web shows it (an `error`). */
    const val MAX_ERROR = 500

    /** A code, only ever compared. */
    const val MAX_CODE = 64

    /** Rows kept per breakdown (models, tools, projects, days, accounts…). */
    const val MAX_ROWS = 2_000

    /** `heat`: 7 days × 24 hours. */
    const val HEAT_CELLS = 168

    fun analytics(obj: JsonObject): UsageAnalytics? {
        val totals = obj["totals"] as? JsonObject ?: return null
        val mostUsed = obj["mostUsed"] as? JsonObject
        val composition = obj["tokenComposition"] as? JsonObject
        return UsageAnalytics(
            generatedAt = num(obj["generatedAt"]),
            totals = UsageTotals(
                sessions = num(totals["sessions"]),
                tokens = num(totals["tokens"]),
                turns = num(totals["turns"]),
                input = num(totals["input"]),
                output = num(totals["output"]),
                cacheRead = num(totals["cacheRead"]),
                cacheCreation = num(totals["cacheCreation"]),
                subagentTurns = num(totals["subagentTurns"]),
                subagentTokens = num(totals["subagentTokens"]),
                costUSD = num(totals["costUSD"]),
                costPartial = truthy(totals["costPartial"]),
                avgTokensPerSession = num(totals["avgTokensPerSession"]),
                cacheEfficiencyPct = num(totals["cacheEfficiencyPct"]),
            ),
            mostUsed = UsageMostUsed(
                harness = top(mostUsed?.get("harness")),
                provider = top(mostUsed?.get("provider")),
                agent = top(mostUsed?.get("agent")),
                model = top(mostUsed?.get("model")),
            ),
            byHarness = rows(obj["byHarness"], ::dimension),
            byProvider = rows(obj["byProvider"], ::dimension),
            byModel = rows(obj["byModel"], ::dimension),
            byAgent = rows(obj["byAgent"], ::count),
            byTool = rows(obj["byTool"], ::count),
            byProject = rows(obj["byProject"], ::dimension),
            byDay = rows(obj["byDay"]) { o ->
                UsageDay(
                    day = text(o["day"], 32) ?: return@rows null,
                    tokens = num(o["tokens"]),
                    turns = num(o["turns"]),
                    sessions = num(o["sessions"]),
                    input = num(o["input"]),
                    output = num(o["output"]),
                    cacheRead = num(o["cacheRead"]),
                    cacheCreation = num(o["cacheCreation"]),
                )
            },
            heat = (obj["heat"] as? JsonArray).orEmpty().take(HEAT_CELLS).map(::num),
            effort = (obj["effort"] as? JsonObject).orEmpty().entries.take(MAX_ROWS).map { (k, v) -> TextCut.cut(k, MAX_TEXT) to num(v) },
            tokenComposition = UsageComposition(
                input = num(composition?.get("input")),
                output = num(composition?.get("output")),
                cacheRead = num(composition?.get("cacheRead")),
                cacheCreation = num(composition?.get("cacheCreation")),
            ),
            topSessions = rows(obj["topSessions"]) { o ->
                UsageSession(
                    sessionId = text(o["sessionId"], MAX_TEXT).orEmpty(),
                    model = text(o["model"], MAX_TEXT),
                    cwd = text(o["cwd"], 4_096),
                    tokens = num(o["tokens"]),
                    turns = num(o["turns"]),
                    output = num(o["output"]),
                    cacheRead = num(o["cacheRead"]),
                    cacheCreation = num(o["cacheCreation"]),
                    costUSD = num(o["costUSD"]),
                    costPartial = truthy(o["costPartial"]),
                )
            },
        )
    }

    fun accounts(obj: JsonObject): AccountsUsage? {
        val claude = obj["claude"] as? JsonArray ?: return null
        return AccountsUsage(
            generatedAt = num(obj["generatedAt"]),
            claude = rows(claude, ::account),
            codex = rows(obj["codex"], ::account),
            deepseek = (obj["deepseek"] as? JsonObject)?.let(::deepseek),
        )
    }

    private fun account(o: JsonObject): AccountUsage? {
        val id = text(o["id"], MAX_TEXT) ?: return null
        val windows = o["windows"] as? JsonObject
        return AccountUsage(
            id = id,
            label = text(o["label"], MAX_TEXT).orEmpty(),
            isDefault = truthy(o["isDefault"]),
            managed = truthy(o["managed"]),
            windows = windows?.let {
                AccountWindows(
                    fiveHour = window(it["fiveHour"]),
                    weekly = window(it["weekly"]),
                    fable = window(it["fable"]),
                    other = (it["other"] as? JsonArray).orEmpty().take(32).mapNotNull(::window),
                )
            },
            at = finite(o["at"]),
            rateLimited = truthy(o["rateLimited"]),
            source = text(o["source"], MAX_CODE),
            tokenExpired = truthy(o["tokenExpired"]),
            throttledByGuard = truthy(o["throttledByGuard"]),
            retryAfter = finite(o["retryAfter"]),
            resetGrants = (o["resetGrants"] as? JsonObject)?.let { g ->
                ClaudeResetGrantsReading(
                    summary = g["grants"] as? JsonObject,
                    at = num(g["at"]),
                    rateLimited = truthy(g["rateLimited"]),
                    tokenExpired = truthy(g["tokenExpired"]),
                    unavailable = truthy(g["unavailable"]),
                    throttledByGuard = truthy(g["throttledByGuard"]),
                )
            },
            resetCredits = (o["resetCredits"] as? JsonObject)?.let { c ->
                CodexResetCredits(
                    availableCount = num(c["availableCount"]),
                    credits = (c["credits"] as? JsonArray)?.take(MAX_ROWS)?.mapNotNull { e ->
                        val credit = e as? JsonObject ?: return@mapNotNull null
                        CodexResetCredit(
                            id = text(credit["id"], MAX_TEXT) ?: return@mapNotNull null,
                            title = text(credit["title"], MAX_TEXT),
                            expiresAt = finite(credit["expiresAt"]),
                        )
                    },
                )
            },
        )
    }

    private fun window(element: JsonElement?): AccountWindow? {
        val o = element as? JsonObject ?: return null
        return AccountWindow(usedPercent = finite(o["usedPercent"]), windowMinutes = num(o["windowMinutes"]), resetsAt = finite(o["resetsAt"]))
    }

    private fun deepseek(o: JsonObject): DeepSeekUsage = DeepSeekUsage(
        ok = o["ok"] == JsonPrimitive(true),
        reason = text(o["reason"], MAX_CODE),
        error = text(o["error"], MAX_ERROR),
        accounts = rows(o["accounts"]) { a ->
            val balance = a["balance"] as? JsonObject
            DeepSeekAccount(
                id = text(a["id"], MAX_TEXT) ?: return@rows null,
                fingerprints = strings(a["fingerprints"], 32),
                sources = strings(a["sources"], MAX_CODE),
                identity = text(a["identity"], MAX_CODE).orEmpty(),
                weakEvidence = truthy(a["weakEvidence"]),
                at = finite(a["at"]),
                stale = truthy(a["stale"]),
                isAvailable = truthy(balance?.get("is_available")),
                balances = rows(balance?.get("balances")) { b ->
                    DeepSeekBalance(
                        currency = text(b["currency"], 16).orEmpty(),
                        total = text(b["total"], 64).orEmpty(),
                        granted = text(b["granted"], 64).orEmpty(),
                        toppedUp = text(b["topped_up"], 64).orEmpty(),
                    )
                },
            )
        },
        unavailable = rows(o["unavailable"]) { u ->
            DeepSeekUnavailable(
                fingerprint = text(u["fingerprint"], 32).orEmpty(),
                sources = strings(u["sources"], MAX_CODE),
                error = text(u["error"], MAX_ERROR).orEmpty(),
            )
        },
    )

    private fun dimension(o: JsonObject): UsageDimension? = UsageDimension(
        key = text(o["key"], MAX_TEXT) ?: return null,
        tokens = num(o["tokens"]),
        sessions = num(o["sessions"]),
        turns = num(o["turns"]),
    )

    private fun count(o: JsonObject): UsageCount? = UsageCount(text(o["key"], MAX_TEXT) ?: return null, num(o["count"]))

    private fun top(element: JsonElement?): UsageTopEntry? {
        val o = element as? JsonObject ?: return null
        return UsageTopEntry(text(o["key"], MAX_TEXT) ?: return null, num(o["value"]), num(o["sharePct"]))
    }

    private fun <T> rows(element: JsonElement?, row: (JsonObject) -> T?): List<T> =
        (element as? JsonArray).orEmpty().asSequence().take(MAX_ROWS).mapNotNull { (it as? JsonObject)?.let(row) }.toList()

    private fun strings(element: JsonElement?, max: Int): List<String> =
        (element as? JsonArray).orEmpty().take(64).mapNotNull { text(it, max) }

    /** A JSON string, cut at a cluster boundary to [max] UTF-16 units. */
    fun text(element: JsonElement?, max: Int): String? {
        val p = element as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return TextCut.cut(p.content, max)
    }

    /** A finite JSON number (not a numeric string), else null. */
    fun finite(element: JsonElement?): Double? {
        val p = element as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        return p.content.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** The web's `n || 0`. */
    private fun num(element: JsonElement?): Double = finite(element) ?: 0.0

    /** JavaScript truthiness of a field (the web reads these flags with `&&` / `?:`). */
    fun truthy(element: JsonElement?): Boolean {
        val p = element as? JsonPrimitive ?: return element is JsonObject || element is JsonArray
        if (p is JsonNull) return false
        if (p.isString) return p.content.isNotEmpty()
        return when (val c = p.content) {
            "true" -> true
            "false" -> false
            else -> c.toDoubleOrNull()?.let { it != 0.0 && !it.isNaN() } ?: false
        }
    }
}
