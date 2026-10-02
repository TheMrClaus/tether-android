package com.tether.app.client

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

// ─────────────────────────────────────────────────────────────────────────────
// ta-7rh: the Claude account CHANGES the web's ClaudeAccountsSection and ClaudeAccountSyncSection
// make (components/settings-dialog.tsx 887c222 :1241-1867), now that the owner decided the app has the
// web's permissions (2026-10-02, tether #236). The routes (server.mjs 90fbb9f ~8265-8475), every one
// `requireOwnerGrade` except the poll:
//   POST   /api/claude-accounts            {nickname}              201 { profile } | 400 { error }
//   POST   /api/claude-accounts/<id>/rename {nickname}             200 { ok, id, label } | 400 | 404 | 409
//   DELETE /api/claude-accounts/<id>       {deleteCredentials}     200 { ok, removed, credentialsDeleted } | 404 | 409
//   POST   /api/claude-accounts/<id>/logout {}                     200 { ok, id, loggedOut } | 404 | 409
//   POST   /api/claude-accounts/<id>/login  {}                     200 { ok, status, url } | 409 { ok:false, error } | 404
//   GET    /api/claude-accounts/<id>/login/poll                    200 { ok, status, url, error }   (any principal)
//   POST   /api/claude-accounts/<id>/login/code {code}             200 { ok, status } | 409 { ok:false, error }
//   DELETE /api/claude-accounts/<id>/login                         200 { ok }
//   PUT    /api/claude-accounts/sync        {mode,categories,primaryAccountId}  200 { config, result } | 400
//   POST   /api/claude-accounts/sync/run    {}                     200 { config, result }
// A server without #236 answers a phone sign-in 403 "This needs an owner sign-in …"
// ([SecurityResult.OwnerSignInNeeded]); nothing else is assumed about it.
//
// Every call goes through [FixedRouteHttp]: redirects off, a fixed path on the paired origin, and only
// when that origin is the one the screen drew from (else [SecurityResult.NotSent], nothing sent). An
// account id goes in a path only in the providers registry's shape ([ClaudeAccountsJson.isAccountId]).
// The pasted authorization code ([ClaudeLoginCode]) prints nothing and is read only to build its one
// body; the server writes it to the CLI's stdin and never echoes it. Nothing here logs or stores.
// The login link the server relays is opened only when it is a plain https URL ([ClaudeLoginLink]).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The authorization code the operator pastes back (settings-dialog.tsx `submitCode`). It prints as
 * `***`; only this module reads it, to build the one request body that carries it.
 */
class ClaudeLoginCode(private val value: String) {
    internal fun reveal(): String = value.trim()

    val isBlank: Boolean get() = value.isBlank()

    override fun toString(): String = "ClaudeLoginCode(***)"
    override fun equals(other: Any?): Boolean = other is ClaudeLoginCode && other.value == value
    override fun hashCode(): Int = 0
}

/** lib/claude-accounts.mjs's login status machine, plus a value this client does not know. */
enum class ClaudeLoginStatus { Idle, PendingUrl, AwaitingCode, Success, Error, Unknown }

/**
 * The browser link `claude auth login` printed (lib/claude-accounts.mjs `parseLoginUrl`), kept only
 * when it is a plain absolute https URL: no user info, no whitespace or control character, a host,
 * at most [MAX_LENGTH]. [url] is the parsed URL's own spelling (what is opened is what was checked);
 * [host] is drawn beside the Open key so the operator sees where it goes.
 */
class ClaudeLoginLink private constructor(val url: String, val host: String) {
    override fun toString(): String = "ClaudeLoginLink($host)"
    override fun equals(other: Any?): Boolean = other is ClaudeLoginLink && other.url == url
    override fun hashCode(): Int = url.hashCode()

    companion object {
        /** An Anthropic authorize URL with its PKCE challenge and state is ~600 characters. */
        const val MAX_LENGTH = 4096

        fun parse(raw: String?): ClaudeLoginLink? {
            if (raw == null || raw.isEmpty() || raw.length > MAX_LENGTH) return null
            if (raw.any { it.isWhitespace() || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) return null
            if (!raw.startsWith("https://", ignoreCase = true)) return null
            val parsed = raw.toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.host.isEmpty()) return null
            val spelled = parsed.toString()
            if (spelled.length > MAX_LENGTH) return null
            return ClaudeLoginLink(spelled, parsed.host)
        }
    }
}

/**
 * `{ ok, status, url, error }` of the login routes. [linkRefused]: the server sent a link this client
 * will not open ([ClaudeLoginLink.parse] refused it). [error] is raw server text, bounded; drawn by
 * the label rule.
 */
data class ClaudeLoginState(
    val status: ClaudeLoginStatus,
    val link: ClaudeLoginLink?,
    val linkRefused: Boolean,
    val error: String?,
)

/** `DELETE /api/claude-accounts/<id>`'s answer: what was actually removed (lib/claude-accounts.mjs `removeAccount`). */
data class ClaudeAccountRemoved(val removed: Boolean, val credentialsDeleted: Boolean)

/** `PUT /sync` and `POST /sync/run`: the config the server kept and the pass it ran (null when none ran). */
data class ClaudeSyncSaved(val config: ClaudeSyncConfig, val result: ClaudeSyncResult?)

/** The owner-grade changes, each for the server [origin] the screen drew from. */
interface ClaudeAccountActions {
    suspend fun add(origin: String, nickname: String): SecurityResult<Unit>
    suspend fun rename(origin: String, accountId: String, nickname: String): SecurityResult<Unit>
    suspend fun remove(origin: String, accountId: String, deleteCredentials: Boolean): SecurityResult<ClaudeAccountRemoved>
    suspend fun logout(origin: String, accountId: String): SecurityResult<Unit>
    suspend fun startLogin(origin: String, accountId: String): SecurityResult<ClaudeLoginState>
    suspend fun pollLogin(origin: String, accountId: String): SecurityResult<ClaudeLoginState>
    suspend fun submitCode(origin: String, accountId: String, code: ClaudeLoginCode): SecurityResult<Unit>
    suspend fun cancelLogin(origin: String, accountId: String): SecurityResult<Unit>
    suspend fun saveSync(origin: String, config: ClaudeSyncConfig): SecurityResult<ClaudeSyncSaved>
    suspend fun runSync(origin: String): SecurityResult<ClaudeSyncSaved>

    /** No client (previews, fakes): nothing is ever sent. */
    object Unavailable : ClaudeAccountActions {
        private fun <T> none(): SecurityResult<T> = SecurityResult.SignedOut()
        override suspend fun add(origin: String, nickname: String): SecurityResult<Unit> = none()
        override suspend fun rename(origin: String, accountId: String, nickname: String): SecurityResult<Unit> = none()
        override suspend fun remove(origin: String, accountId: String, deleteCredentials: Boolean): SecurityResult<ClaudeAccountRemoved> = none()
        override suspend fun logout(origin: String, accountId: String): SecurityResult<Unit> = none()
        override suspend fun startLogin(origin: String, accountId: String): SecurityResult<ClaudeLoginState> = none()
        override suspend fun pollLogin(origin: String, accountId: String): SecurityResult<ClaudeLoginState> = none()
        override suspend fun submitCode(origin: String, accountId: String, code: ClaudeLoginCode): SecurityResult<Unit> = none()
        override suspend fun cancelLogin(origin: String, accountId: String): SecurityResult<Unit> = none()
        override suspend fun saveSync(origin: String, config: ClaudeSyncConfig): SecurityResult<ClaudeSyncSaved> = none()
        override suspend fun runSync(origin: String): SecurityResult<ClaudeSyncSaved> = none()
    }

    companion object {
        const val ACCOUNTS_PATH = "/api/claude-accounts"
        const val SYNC_PATH = "/api/claude-accounts/sync"
        const val SYNC_RUN_PATH = "/api/claude-accounts/sync/run"
        fun accountPath(id: String) = "$ACCOUNTS_PATH/$id"
        fun renamePath(id: String) = "$ACCOUNTS_PATH/$id/rename"
        fun logoutPath(id: String) = "$ACCOUNTS_PATH/$id/logout"
        fun loginPath(id: String) = "$ACCOUNTS_PATH/$id/login"
        fun pollPath(id: String) = "$ACCOUNTS_PATH/$id/login/poll"
        fun codePath(id: String) = "$ACCOUNTS_PATH/$id/login/code"

        /** lib/claude-accounts.mjs NICKNAME_MAX (JS length: UTF-16 units, as Kotlin's). */
        const val NICKNAME_MAX = 64

        /** The most of a pasted code sent (an Anthropic code with its state is ~100 characters). */
        const val CODE_MAX = 2048

        /** lib/claude-account-sync.mjs `sanitizeSyncConfig`'s bound on `primaryAccountId`. */
        const val PRIMARY_MAX = 128

        /** A reply is a few hundred bytes; a sync result lists one entry per account and category. */
        const val MAX_BODY_BYTES: Long = 256L * 1024L

        /** A logout or a sync pass runs a child on the server. */
        const val CALL_TIMEOUT_MS: Long = 30_000L
    }
}

/**
 * The fixed sentences shown for the routes' own short refusal codes (the web shows the raw code, or
 * its own fallback). Any other refusal shows Tether's sentence by the label rule.
 */
object ClaudeAccountActionCopy {
    const val ALREADY_IN_PROGRESS = "A login is already in progress."
    const val NO_SUCH_ACCOUNT = "No such Claude account."
    const val NOT_A_CLAUDE_ACCOUNT = "That profile is not a Claude account."
    const val NO_ACTIVE_LOGIN = "No login is waiting for a code. Start the login again."
    const val EMPTY_CODE = "Paste the code first."
    const val CODE_NOT_DELIVERED = "The code could not be handed to the login. Start the login again."

    fun forCode(code: String?): String? = when (code) {
        "already-in-progress" -> ALREADY_IN_PROGRESS
        "not-managed" -> ClaudeAccountRefusal.NotManaged.sentence
        "not-found" -> NO_SUCH_ACCOUNT
        "not-a-claude-account" -> NOT_A_CLAUDE_ACCOUNT
        "no-active-login" -> NO_ACTIVE_LOGIN
        "empty-code" -> EMPTY_CODE
        "code-not-delivered" -> CODE_NOT_DELIVERED
        else -> null
    }
}

/** [ClaudeAccountActions] over [FixedRouteHttp], with the client's per-call (server, credential) read. */
class HttpClaudeAccountActions(
    http: OkHttpClient,
    private val authority: () -> FilesAuthority,
    maxBytes: Long = ClaudeAccountActions.MAX_BODY_BYTES,
    callTimeoutMs: Long = ClaudeAccountActions.CALL_TIMEOUT_MS,
) : ClaudeAccountActions {
    private val route = FixedRouteHttp(http, maxBytes, callTimeoutMs)

    override suspend fun add(origin: String, nickname: String): SecurityResult<Unit> {
        val name = nickname.trim()
        if (name.isEmpty() || name.length > ClaudeAccountActions.NICKNAME_MAX) return SecurityResult.NotSent(origin)
        return call(origin, FixedRouteHttp.Method.POST, ClaudeAccountActions.ACCOUNTS_PATH, buildJsonObject { put("nickname", name) }) { o ->
            if (o["profile"] is JsonObject) Unit else null
        }
    }

    override suspend fun rename(origin: String, accountId: String, nickname: String): SecurityResult<Unit> = withId(origin, accountId) {
        val name = nickname.trim()
        if (name.isEmpty() || name.length > ClaudeAccountActions.NICKNAME_MAX) return@withId SecurityResult.NotSent(origin)
        call(origin, FixedRouteHttp.Method.POST, ClaudeAccountActions.renamePath(accountId), buildJsonObject { put("nickname", name) }, ::okUnit)
    }

    override suspend fun remove(origin: String, accountId: String, deleteCredentials: Boolean): SecurityResult<ClaudeAccountRemoved> = withId(origin, accountId) {
        call(origin, FixedRouteHttp.Method.DELETE, ClaudeAccountActions.accountPath(accountId), buildJsonObject { put("deleteCredentials", deleteCredentials) }) { o ->
            if (bool(o["ok"]) == true) ClaudeAccountRemoved(removed = bool(o["removed"]) == true, credentialsDeleted = bool(o["credentialsDeleted"]) == true) else null
        }
    }

    override suspend fun logout(origin: String, accountId: String): SecurityResult<Unit> = withId(origin, accountId) {
        call(origin, FixedRouteHttp.Method.POST, ClaudeAccountActions.logoutPath(accountId), JsonObject(emptyMap()), ::okUnit)
    }

    override suspend fun startLogin(origin: String, accountId: String): SecurityResult<ClaudeLoginState> = withId(origin, accountId) {
        call(origin, FixedRouteHttp.Method.POST, ClaudeAccountActions.loginPath(accountId), JsonObject(emptyMap()), ::loginState)
    }

    override suspend fun pollLogin(origin: String, accountId: String): SecurityResult<ClaudeLoginState> = withId(origin, accountId) {
        call(origin, FixedRouteHttp.Method.GET, ClaudeAccountActions.pollPath(accountId), null, ::loginState)
    }

    override suspend fun submitCode(origin: String, accountId: String, code: ClaudeLoginCode): SecurityResult<Unit> = withId(origin, accountId) {
        val value = code.reveal()
        if (value.isEmpty() || value.length > ClaudeAccountActions.CODE_MAX) return@withId SecurityResult.NotSent(origin)
        call(origin, FixedRouteHttp.Method.POST, ClaudeAccountActions.codePath(accountId), buildJsonObject { put("code", value) }, ::okUnit)
    }

    override suspend fun cancelLogin(origin: String, accountId: String): SecurityResult<Unit> = withId(origin, accountId) {
        call(origin, FixedRouteHttp.Method.DELETE, ClaudeAccountActions.loginPath(accountId), null, ::okUnit)
    }

    override suspend fun saveSync(origin: String, config: ClaudeSyncConfig): SecurityResult<ClaudeSyncSaved> {
        val mode = when (config.mode) {
            ClaudeSyncMode.All -> "all"
            ClaudeSyncMode.Selected -> "selected"
            ClaudeSyncMode.None -> "none"
            // A mode this client does not know is never written back.
            ClaudeSyncMode.Unknown -> return SecurityResult.NotSent(origin)
        }
        val primary = config.primaryAccountId
        if (primary != null && (primary.isEmpty() || primary.length > ClaudeAccountActions.PRIMARY_MAX)) return SecurityResult.NotSent(origin)
        val body = buildJsonObject {
            put("mode", mode)
            put(
                "categories",
                buildJsonObject {
                    put("plugins", config.categories.plugins)
                    put("skills", config.categories.skills)
                    put("hooks", config.categories.hooks)
                    put("mcp", config.categories.mcp)
                },
            )
            if (primary == null) put("primaryAccountId", JsonNull) else put("primaryAccountId", primary)
        }
        return call(origin, FixedRouteHttp.Method.PUT, ClaudeAccountActions.SYNC_PATH, body, ClaudeAccountsJson::syncSaved)
    }

    override suspend fun runSync(origin: String): SecurityResult<ClaudeSyncSaved> =
        call(origin, FixedRouteHttp.Method.POST, ClaudeAccountActions.SYNC_RUN_PATH, JsonObject(emptyMap()), ClaudeAccountsJson::syncSaved)

    /** A server-supplied id goes in a path only in the registry's own shape: it can never name another route. */
    private inline fun <T> withId(origin: String, id: String, send: () -> SecurityResult<T>): SecurityResult<T> =
        if (ClaudeAccountsJson.isAccountId(id)) send() else SecurityResult.NotSent(origin)

    private suspend fun <T> call(
        origin: String,
        method: FixedRouteHttp.Method,
        path: String,
        body: JsonObject?,
        parse: (JsonObject) -> T?,
    ): SecurityResult<T> = when (val out = route.call(authority(), origin, method, path, body)) {
        FixedRouteHttp.Outcome.SignedOut -> SecurityResult.SignedOut()
        FixedRouteHttp.Outcome.LocalNetworkBlocked -> SecurityResult.LocalNetworkBlocked
        is FixedRouteHttp.Outcome.OtherOrigin -> SecurityResult.NotSent(out.origin)
        is FixedRouteHttp.Outcome.NotBuilt -> SecurityResult.NotSent(out.origin)
        is FixedRouteHttp.Outcome.Blocked -> SecurityResult.Blocked(out.code, out.origin)
        is FixedRouteHttp.Outcome.Unreachable -> SecurityResult.Unavailable(null, out.origin)
        is FixedRouteHttp.Outcome.Answered -> answered(out, parse)
    }

    private fun <T> answered(out: FixedRouteHttp.Outcome.Answered, parse: (JsonObject) -> T?): SecurityResult<T> {
        val json = out.json
        val error = json?.let(DeviceSecurityJson::errorSentence)
        return when {
            out.code == 200 || out.code == 201 -> json?.let(parse)?.let { SecurityResult.Ok(it, out.origin, null) }
                ?: refusedOk(out, json)
            out.code == 401 && out.jsonType -> SecurityResult.SignedOut(out.origin)
            out.code == 403 && error != null && error.startsWith(DeviceSecuritySource.OWNER_REFUSAL_OPENING) -> SecurityResult.OwnerSignInNeeded(out.origin)
            out.code in REFUSAL_CODES && json != null && (error != null || codeOf(json) != null) ->
                SecurityResult.Refused(out.code, sentence(json, error), out.origin)
            else -> SecurityResult.Unavailable(out.code, out.origin)
        }
    }

    /** A 200 `{ ok: false, error: "<code>" }` (the code route answers 409, but be tolerant): a refusal, not an answer. */
    private fun <T> refusedOk(out: FixedRouteHttp.Outcome.Answered, json: JsonObject?): SecurityResult<T> {
        if (json != null && bool(json["ok"]) == false) {
            val sentence = sentence(json, DeviceSecurityJson.errorSentence(json))
            return SecurityResult.Refused(out.code, sentence, out.origin)
        }
        return SecurityResult.Unavailable(out.code, out.origin)
    }

    /** A known short code as fixed copy (error_code first: not-managed, not-a-claude-account); else Tether's sentence. */
    private fun sentence(json: JsonObject, error: String?): String =
        ClaudeAccountActionCopy.forCode(codeOf(json)) ?: ClaudeAccountActionCopy.forCode(error) ?: error.orEmpty()

    private fun codeOf(json: JsonObject): String? = (json["error_code"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { TextCut.cut(it, ClaudeAccountsJson.MAX_CODE) }

    private fun okUnit(o: JsonObject): Unit? = if (bool(o["ok"]) == true) Unit else null

    private fun loginState(o: JsonObject): ClaudeLoginState? {
        if (bool(o["ok"]) != true) return null
        return ClaudeAccountsJson.loginState(o)
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

    private companion object {
        /** The routes' own `{error}` statuses (a refused write's 403 from lib/origin-guard.mjs too). A 5xx is a proxy's or a crash: never shown. */
        val REFUSAL_CODES = setOf(400, 403, 404, 409, 413, 415, 429)
    }
}
