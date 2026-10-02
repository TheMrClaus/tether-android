package com.tether.app.client

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

// ─────────────────────────────────────────────────────────────────────────────
// ta-9q2 (T10.1 slice 2) + ta-ebc (#231): the Settings Engines tab's Claude accounts, READ ONLY.
// ta-7rh: the changes (add, rename, remove, log in/out, sync) are a separate source,
// ClaudeAccountActions.kt, over FixedRouteHttp; this reader still sends nothing but its three GETs.
// tether 887c222 server.mjs ~8117-8297 (/api/claude-accounts):
//   GET /api/claude-accounts             { accounts: ClaudeAccountRow[] }   (each row carries `plan`, #231)
//   GET /api/claude-accounts/sync        { config, lastResult }
//   GET /api/claude-accounts/<id>/status { ok, id, loggedIn, authMethod, email? } | 404/409 { error }
// These sit behind the ordinary /api/ gate: any authenticated principal may read them. EVERY
// mutation (add, rename, remove, login, login code, cancel login, logout, sync PUT, sync run) is
// `requireOwnerGrade` and answers a device token 403. This file has no way to send one: the only
// method is GET and the only paths are the three above.
//
// #231 (lib/protocol.ts `ClaudeAccountPlan`): `plan.raw` carries Anthropic's internal tier codenames
// for debugging and must never be shown. It is NEVER DECODED here: no model below has a field it
// could land in, so it cannot reach the UI, a log line, a toString or storage.
// ─────────────────────────────────────────────────────────────────────────────

/** `ClaudeAccountPlan.source`: the live profile reading, the offline credentials snapshot, or a value this client does not know. */
enum class ClaudeAccountPlanSource { Profile, Credentials, Unknown }

/** lib/protocol.ts `ClaudeAccountPlan`, the two displayable fields and the source. `raw` is not kept (see the header). */
data class ClaudeAccountPlan(
    /** The popular name ("Team Premium 5x", "Max 20x", …): raw server text, bounded; drawn by the label rule. */
    val label: String,
    /** Raw server text, bounded; drawn by the label rule. */
    val organizationName: String?,
    val source: ClaudeAccountPlanSource,
)

/**
 * One `ClaudeAccountRow` (lib/claude-accounts.mjs `listAccounts` + server.mjs `claudeAccountsWithPlan`).
 * [label] and [configDir] are raw server text, bounded; draw them by the label and the one-line
 * (path) rules. [checkable]: [id] has the providers registry's shape, so a status read may name it
 * in a path; any other id is listed but never put in a URL.
 */
data class ClaudeAccount(
    val id: String,
    val label: String,
    val enabled: Boolean?,
    val configDir: String?,
    val hasConfigDir: Boolean,
    val managed: Boolean,
    val imported: Boolean,
    val syncEligible: Boolean,
    val plan: ClaudeAccountPlan?,
) {
    val checkable: Boolean get() = ClaudeAccountsJson.isAccountId(id)
}

/** `GET /api/claude-accounts/<id>/status`'s allowlist (lib/claude-accounts.mjs `parseAuthStatusJson`). Raw server text, bounded. */
data class ClaudeAccountStatus(
    val loggedIn: Boolean,
    val authMethod: String?,
    val email: String?,
    /** The server's own code (`unrecognized-status-output`): shown as the web shows it, by the label rule. */
    val error: String?,
)

/** lib/claude-account-sync.mjs `SYNC_MODES`; [Unknown] is a value a newer server may send. */
enum class ClaudeSyncMode { All, Selected, None, Unknown }

data class ClaudeSyncCategories(val plugins: Boolean, val skills: Boolean, val mcp: Boolean, val hooks: Boolean)

/** `readSyncConfig`. [primaryAccountId] is raw server text, only ever compared with an account id. */
data class ClaudeSyncConfig(val mode: ClaudeSyncMode, val categories: ClaudeSyncCategories, val primaryAccountId: String?)

/**
 * `lastResult` (lib/claude-account-sync.mjs `syncClaudeAccounts`), reduced to what the web's
 * `summarizeSyncResult` reads: [status], [error], [ranAt] and the two entry counts. The entries
 * themselves (with their paths) are counted and dropped.
 */
data class ClaudeSyncResult(
    val ranAt: Long?,
    /** A short code, only ever compared, never drawn. */
    val status: String,
    val changed: Int,
    val upToDate: Int,
    /** Raw server text, bounded; drawn by the label rule. */
    val error: String?,
)

/** `GET /api/claude-accounts/sync`. */
data class ClaudeAccountsSync(val config: ClaudeSyncConfig, val lastResult: ClaudeSyncResult?)

/**
 * What one GET came to (the [OverviewMetricsResult] shape, one more case). [origin] is the paired
 * server's canonical [serverOrigin] the call was made for, so a screen drops an answer about any
 * server but the one it shows. Null only when there was no server to ask.
 */
sealed interface ClaudeAccountsResult<out T> {
    val origin: String?

    data class Ok<T>(val value: T, override val origin: String) : ClaudeAccountsResult<T>

    /** No credential, or Tether's own 401 (the /api/ gate's JSON answer). */
    data class SignedOut(override val origin: String? = null) : ClaudeAccountsResult<Nothing>

    /** Tether's own 403 (JSON, no challenge). */
    data class Forbidden(override val origin: String) : ClaudeAccountsResult<Nothing>

    data object LocalNetworkBlocked : ClaudeAccountsResult<Nothing> {
        override val origin: String? get() = null
    }

    /** T6.8's rule ([HttpToolMedia.blockedBySignIn]): a sign-in gateway answered instead of Tether. Nothing is followed or kept. */
    data class Blocked(val code: Int, override val origin: String) : ClaudeAccountsResult<Nothing>

    /**
     * r2: one of the status route's OWN refusals (server.mjs `claudeAccountReply` at 887c222),
     * recognised by its status and its fixed shape; the web shows its `data.error`, and this client
     * shows the same sentence from [ClaudeAccountRefusal], never the reply's text. Any other non-2xx
     * (a gateway's `502 {"error": …}` included) is [Unavailable].
     */
    data class Refused(val code: Int, val reason: ClaudeAccountRefusal, override val origin: String) : ClaudeAccountsResult<Nothing>

    /** Unreachable, any other answer, a body over the cap, or a body this client cannot use. */
    data class Unavailable(val code: Int?, override val origin: String) : ClaudeAccountsResult<Nothing>
}

/**
 * r2: the status route's two refusals (lib/claude-accounts.mjs `claudeAccountReply` at 887c222),
 * each recognised by a fixed mark of Tether's own reply, with the server's sentence as fixed copy.
 */
enum class ClaudeAccountRefusal(val sentence: String) {
    /** 404 `{"error":"No such Claude account."}`: recognised by that exact sentence. */
    NoSuchAccount("No such Claude account."),

    /** 409 `{"error": …, "error_code":"not-managed"}`: recognised by the error_code. */
    NotManaged(
        "That profile's CLAUDE_CONFIG_DIR is not a Tether-managed account directory, so Tether will not sign it in or out. Edit it in Settings → Engines instead.",
    ),
}

/** The three device-readable GETs, with the paired credential. There is deliberately no write here. */
interface ClaudeAccountsSource {
    suspend fun list(): ClaudeAccountsResult<List<ClaudeAccount>>
    suspend fun sync(): ClaudeAccountsResult<ClaudeAccountsSync>

    /** [accountId] must be [ClaudeAccount.checkable]; any other id is refused here, before a request. */
    suspend fun status(accountId: String): ClaudeAccountsResult<ClaudeAccountStatus>

    /** No client (previews, fakes): nothing is ever fetched. */
    object Unavailable : ClaudeAccountsSource {
        override suspend fun list(): ClaudeAccountsResult<List<ClaudeAccount>> = ClaudeAccountsResult.SignedOut()
        override suspend fun sync(): ClaudeAccountsResult<ClaudeAccountsSync> = ClaudeAccountsResult.SignedOut()
        override suspend fun status(accountId: String): ClaudeAccountsResult<ClaudeAccountStatus> = ClaudeAccountsResult.SignedOut()
    }

    companion object {
        const val LIST_PATH = "/api/claude-accounts"
        const val SYNC_PATH = "/api/claude-accounts/sync"
        fun statusPath(accountId: String) = "/api/claude-accounts/$accountId/status"

        /**
         * The most body read. A list of the registry's 64 profiles with their plans is ~40 KiB; a sync
         * result lists one entry per account and category. Anything past this is dropped unread.
         */
        const val MAX_BODY_BYTES: Long = 256L * 1024L

        /** A call that has not finished by now is abandoned (a status probe spawns the CLI on the server). */
        const val CALL_TIMEOUT_MS: Long = 20_000L
    }
}

/**
 * [ClaudeAccountsSource] over OkHttp, [HttpOverviewMetrics]'s pattern: [http] MUST NOT follow
 * redirects (OkHttp keeps a hand-set `Cookie` across a cross-host redirect); each call reads the
 * (server, credential) pair together through [authority], builds a FIXED path on that origin,
 * re-checks the request is on it before sending, and bounds the body while it streams. Only GET.
 * Cancelling the caller cancels the socket.
 */
class HttpClaudeAccounts(
    private val http: OkHttpClient,
    private val authority: () -> FilesAuthority,
    // Parameters only so tests can reach the bounds cheaply.
    private val maxBytes: Long = ClaudeAccountsSource.MAX_BODY_BYTES,
    private val callTimeoutMs: Long = ClaudeAccountsSource.CALL_TIMEOUT_MS,
) : ClaudeAccountsSource {

    init {
        require(!http.followRedirects && !http.followSslRedirects) {
            "HttpClaudeAccounts needs a client that never follows redirects (the credential must stay on its origin)"
        }
    }

    // r2: the list and sync routes have no refusal of their own (server.mjs:8145, :8172 answer 200 or
    // the /api/ gate's 401); only the status route's two are recognised.
    override suspend fun list(): ClaudeAccountsResult<List<ClaudeAccount>> = get(ClaudeAccountsSource.LIST_PATH, ClaudeAccountsJson::accounts)

    override suspend fun sync(): ClaudeAccountsResult<ClaudeAccountsSync> = get(ClaudeAccountsSource.SYNC_PATH, ClaudeAccountsJson::sync)

    override suspend fun status(accountId: String): ClaudeAccountsResult<ClaudeAccountStatus> {
        // The id is server data: only the registry's own shape ([a-z][a-z0-9-]*, no dot, slash or
        // escape) is ever put in a path, so it can never name another route.
        if (!ClaudeAccountsJson.isAccountId(accountId)) {
            return when (val a = authority()) {
                FilesAuthority.SignedOut -> ClaudeAccountsResult.SignedOut()
                FilesAuthority.LocalNetworkBlocked -> ClaudeAccountsResult.LocalNetworkBlocked
                is FilesAuthority.Paired -> serverOrigin(a.origin.toString())?.let { ClaudeAccountsResult.Unavailable(null, it) }
                    ?: ClaudeAccountsResult.SignedOut()
            }
        }
        return get(ClaudeAccountsSource.statusPath(accountId), ClaudeAccountsJson::status, ClaudeAccountsJson::statusRefusal)
    }

    private suspend fun <T> get(
        path: String,
        parse: (JsonObject) -> T?,
        refusal: (Int, JsonObject) -> ClaudeAccountRefusal? = { _, _ -> null },
    ): ClaudeAccountsResult<T> {
        val paired = when (val a = authority()) {
            FilesAuthority.SignedOut -> return ClaudeAccountsResult.SignedOut()
            FilesAuthority.LocalNetworkBlocked -> return ClaudeAccountsResult.LocalNetworkBlocked
            is FilesAuthority.Paired -> a
        }
        val target = paired.origin.newBuilder().encodedPath(path).query(null).fragment(null).build()
        val request = paired.sign(
            Request.Builder().url(target).header("Accept", "application/json").header("Cache-Control", "no-store"),
        ).get().build()
        val origin = serverOrigin(paired.origin.toString()) ?: return ClaudeAccountsResult.SignedOut()
        // Nothing but the fixed route, by GET, on the paired origin ever carries the credential.
        if (request.method != "GET" || !sameOrigin(request.url, paired.origin) || request.url.encodedPath != path || request.url.query != null) {
            return ClaudeAccountsResult.Unavailable(null, origin)
        }
        val call = http.newCall(request)
        call.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        return try {
            callCancellably(call) { response -> read(response, origin, parse, refusal) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            ClaudeAccountsResult.Unavailable(null, origin)
        } catch (_: RuntimeException) {
            ClaudeAccountsResult.Unavailable(null, origin)
        } catch (_: OutOfMemoryError) {
            ClaudeAccountsResult.Unavailable(null, origin)
        }
    }

    private fun <T> read(
        response: Response,
        origin: String,
        parse: (JsonObject) -> T?,
        refusal: (Int, JsonObject) -> ClaudeAccountRefusal?,
    ): ClaudeAccountsResult<T> {
        val declaredType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
        if (HttpToolMedia.blockedBySignIn(response.code, declaredType, response.header("WWW-Authenticate") != null)) {
            return ClaudeAccountsResult.Blocked(response.code, origin)
        }
        when {
            response.code == 401 -> return ClaudeAccountsResult.SignedOut(origin)
            response.code == 403 -> return ClaudeAccountsResult.Forbidden(origin)
            response.code != 200 -> {
                // r2: only a refusal recognisably the route's own is named; its words are fixed copy,
                // never the reply's text. Anything else (a gateway's JSON error too) is unavailable.
                if (declaredType != "application/json" || response.code !in 400..499) return ClaudeAccountsResult.Unavailable(response.code, origin)
                val reason = readCapped(response)?.let(ClaudeAccountsJson::parseObject)?.let { refusal(response.code, it) }
                return if (reason != null) ClaudeAccountsResult.Refused(response.code, reason, origin) else ClaudeAccountsResult.Unavailable(response.code, origin)
            }
            // The server's json() always says so; a 200 in any other type is not its answer.
            declaredType != "application/json" -> return ClaudeAccountsResult.Unavailable(response.code, origin)
        }
        val text = readCapped(response) ?: return ClaudeAccountsResult.Unavailable(response.code, origin)
        val obj = ClaudeAccountsJson.parseObject(text) ?: return ClaudeAccountsResult.Unavailable(response.code, origin)
        val value = parse(obj) ?: return ClaudeAccountsResult.Unavailable(response.code, origin)
        return ClaudeAccountsResult.Ok(value, origin)
    }

    /** The body as text, or null when it is (declared or streamed) over [maxBytes]: never more than that is buffered. */
    private fun readCapped(response: Response): String? {
        val body = response.body
        val declared = body.contentLength()
        if (declared > maxBytes) return null
        val source = body.source()
        if (source.request(maxBytes + 1)) return null
        return source.buffer.readUtf8()
    }

    private suspend fun <T> callCancellably(call: Call, block: (Response) -> T): T = coroutineScope {
        val guard = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) { call.execute().use(block) }
        } finally {
            guard.cancel()
        }
    }

    private companion object {
        fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }
}

/**
 * The tolerant, bounded read of the three bodies: a field of the wrong type is absent; an unknown
 * value is kept as unknown; every string is cut before it is kept; a body without its route's own
 * shape (a gateway's `{"error":"login required"}`) is null, never an empty answer.
 */
object ClaudeAccountsJson {
    /** Server text kept per label / name / sentence (drawn later by the label rule, which bounds it again). */
    const val MAX_TEXT = 200

    /** A CLAUDE_CONFIG_DIR (the registry bounds a home at 4096). */
    const val MAX_PATH = 4096

    /** A code, only ever compared. */
    const val MAX_CODE = 64

    /** lib/providers-registry.mjs MAX_PROFILES, plus the synthetic host-default row. */
    const val MAX_ACCOUNTS = 65

    /** Sync entries counted (one per account, category and item). */
    const val MAX_ENTRIES = 4096

    /** lib/providers-registry.mjs `ID_PATTERN` and `PROFILE_ID_MAX`. */
    private val ACCOUNT_ID = Regex("^[a-z][a-z0-9-]{0,63}$")

    /**
     * The deepest thing read is four levels down (`accounts[].plan`, `lastResult.entries[]`). r2:
     * a container that opens deeper than this is replaced by `null` before parsing
     * ([com.tether.app.protocol.ServerMessage.flattenDeeperThan]), so a deep subtree the client
     * never reads (a nested `plan.raw`) costs neither the parser's stack nor the row.
     */
    private const val MAX_DEPTH = 8

    fun isAccountId(id: String): Boolean = ACCOUNT_ID.matches(id)

    fun parseObject(text: String): JsonObject? = try {
        val bounded = com.tether.app.protocol.ServerMessage.flattenDeeperThan(text, MAX_DEPTH)
        com.tether.app.protocol.TetherJson.parseToJsonElement(bounded) as? JsonObject
    } catch (_: Exception) {
        null
    }

    /**
     * `{ accounts: [...] }`. Null when `accounts` is not an array (the web reads that as no rows; here
     * it is not the server's answer, so it never replaces a good list). A row without a string `id`
     * is dropped, as is a second row with the same id; at most [MAX_ACCOUNTS] are kept.
     */
    fun accounts(obj: JsonObject): List<ClaudeAccount>? {
        val rows = obj["accounts"] as? JsonArray ?: return null
        val seen = HashSet<String>()
        return rows.asSequence()
            .mapNotNull { (it as? JsonObject)?.let(::account) }
            .filter { seen.add(it.id) }
            .take(MAX_ACCOUNTS)
            .toList()
    }

    private fun account(o: JsonObject): ClaudeAccount? {
        val id = string(o["id"], MAX_TEXT)?.takeIf { it.isNotEmpty() } ?: return null
        val configDir = string(o["configDir"], MAX_PATH)?.takeIf { it.isNotEmpty() }
        return ClaudeAccount(
            id = id,
            label = string(o["label"], MAX_TEXT).orEmpty(),
            enabled = bool(o["enabled"]),
            configDir = configDir,
            // settings-dialog.tsx shows the path only when `hasConfigDir`; a path must be there to show.
            hasConfigDir = bool(o["hasConfigDir"]) == true && configDir != null,
            managed = bool(o["managed"]) == true,
            imported = bool(o["imported"]) == true,
            syncEligible = bool(o["syncEligible"]) == true,
            plan = plan(o["plan"]),
        )
    }

    /**
     * `plan`: absent, null or not an object is no plan; a plan without a usable `label` is no plan
     * (there is nothing to show). Only `label`, `organizationName` and `source` are read: `raw` is
     * never looked at.
     */
    fun plan(element: JsonElement?): ClaudeAccountPlan? {
        val o = element as? JsonObject ?: return null
        val label = string(o["label"], MAX_TEXT)?.takeIf { it.isNotBlank() } ?: return null
        return ClaudeAccountPlan(
            label = label,
            organizationName = string(o["organizationName"], MAX_TEXT)?.takeIf { it.isNotBlank() },
            source = when (string(o["source"], MAX_CODE)) {
                "profile" -> ClaudeAccountPlanSource.Profile
                "credentials" -> ClaudeAccountPlanSource.Credentials
                else -> ClaudeAccountPlanSource.Unknown
            },
        )
    }

    /** `{ ok, id, loggedIn, authMethod, email? }`. Null without a boolean `loggedIn` (not the server's answer). */
    fun status(obj: JsonObject): ClaudeAccountStatus? {
        val loggedIn = bool(obj["loggedIn"]) ?: return null
        return ClaudeAccountStatus(
            loggedIn = loggedIn,
            authMethod = string(obj["authMethod"], MAX_CODE),
            email = string(obj["email"], MAX_TEXT)?.takeIf { it.isNotBlank() },
            error = string(obj["error"], MAX_TEXT)?.takeIf { it.isNotBlank() },
        )
    }

    /** `{ config, lastResult }`. Null without a `config` object. */
    fun sync(obj: JsonObject): ClaudeAccountsSync? {
        val config = obj["config"] as? JsonObject ?: return null
        val categories = config["categories"] as? JsonObject
        fun on(key: String) = bool(categories?.get(key)) == true
        return ClaudeAccountsSync(
            config = ClaudeSyncConfig(
                mode = when (string(config["mode"], MAX_CODE)) {
                    "all" -> ClaudeSyncMode.All
                    "selected" -> ClaudeSyncMode.Selected
                    "none" -> ClaudeSyncMode.None
                    else -> ClaudeSyncMode.Unknown
                },
                categories = ClaudeSyncCategories(plugins = on("plugins"), skills = on("skills"), mcp = on("mcp"), hooks = on("hooks")),
                primaryAccountId = string(config["primaryAccountId"], MAX_TEXT)?.takeIf { it.isNotEmpty() },
            ),
            lastResult = syncResult(obj["lastResult"]),
        )
    }

    /**
     * ta-7rh: `PUT /sync` and `POST /sync/run` answer `{ config, result }` (the sync GET's two parts
     * under other names). Null without a `config` object.
     */
    fun syncSaved(obj: JsonObject): ClaudeSyncSaved? {
        val read = sync(JsonObject(mapOfNotNull("config" to obj["config"], "lastResult" to obj["result"]))) ?: return null
        return ClaudeSyncSaved(read.config, read.lastResult)
    }

    private fun mapOfNotNull(vararg pairs: Pair<String, JsonElement?>): Map<String, JsonElement> =
        pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    /**
     * ta-7rh: the login routes' `{ status, url, error }` (lib/claude-accounts.mjs `publicLoginState`).
     * The link is kept only when [ClaudeLoginLink.parse] accepts it; one it refuses is flagged, never
     * kept. A status this client does not know is [ClaudeLoginStatus.Unknown] (it keeps polling).
     */
    fun loginState(obj: JsonObject): ClaudeLoginState {
        val rawUrl = (obj["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val link = ClaudeLoginLink.parse(rawUrl)
        return ClaudeLoginState(
            status = when (string(obj["status"], MAX_CODE)) {
                "idle" -> ClaudeLoginStatus.Idle
                "pending-url" -> ClaudeLoginStatus.PendingUrl
                "awaiting-code" -> ClaudeLoginStatus.AwaitingCode
                "success" -> ClaudeLoginStatus.Success
                "error" -> ClaudeLoginStatus.Error
                else -> ClaudeLoginStatus.Unknown
            },
            link = link,
            linkRefused = rawUrl != null && rawUrl.isNotEmpty() && link == null,
            error = string(obj["error"], MAX_TEXT)?.takeIf { it.isNotBlank() },
        )
    }

    private val CHANGED = setOf("linked", "merged", "backed-up-existing")

    private fun syncResult(element: JsonElement?): ClaudeSyncResult? {
        val o = element as? JsonObject ?: return null
        val status = string(o["status"], MAX_CODE) ?: return null
        var changed = 0
        var upToDate = 0
        (o["entries"] as? JsonArray).orEmpty().asSequence().take(MAX_ENTRIES).forEach { e ->
            when (string((e as? JsonObject)?.get("status"), MAX_CODE)) {
                in CHANGED -> changed++
                "up-to-date" -> upToDate++
            }
        }
        return ClaudeSyncResult(
            ranAt = number(o["ranAt"])?.takeIf { it > 0 && it < 1e15 }?.toLong(),
            status = status,
            changed = changed,
            upToDate = upToDate,
            error = string(o["error"], MAX_TEXT)?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * r2: the status route's own refusals (lib/claude-accounts.mjs `claudeAccountReply`): 404 with
     * exactly its sentence, 409 with `error_code: "not-managed"`. Null for anything else.
     */
    fun statusRefusal(code: Int, obj: JsonObject): ClaudeAccountRefusal? = when {
        code == 404 && string(obj["error"], MAX_TEXT) == ClaudeAccountRefusal.NoSuchAccount.sentence -> ClaudeAccountRefusal.NoSuchAccount
        code == 409 && string(obj["error_code"], MAX_CODE) == "not-managed" -> ClaudeAccountRefusal.NotManaged
        else -> null
    }

    private fun string(element: JsonElement?, max: Int): String? {
        val p = element as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return TextCut.cut(p.content, max)
    }

    private fun bool(element: JsonElement?): Boolean? {
        val p = element as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        return when (p.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun number(element: JsonElement?): Double? {
        val p = element as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        return p.content.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
