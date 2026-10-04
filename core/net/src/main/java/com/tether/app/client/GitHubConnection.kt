package com.tether.app.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

// ─────────────────────────────────────────────────────────────────────────────
// ta-coik.21: Settings → Advanced → "GitHub connection", the web's GitHubConnectionSection
// (components/settings-dialog.tsx 90fbb9f :946-1168). The routes (server.mjs 90fbb9f :8213-8263, the
// helpers in lib/github-auth.mjs), every one `requireOwnerGrade` except the status GET:
//   GET    /api/github/connection             200 { ghInstalled, ghVersion, authenticated, account, scopes, managedToken }
//   POST   /api/github/connection/login        200 { ok:true } | 409 { ok:false, error }      (`startDeviceLogin`)
//   GET    /api/github/connection/login/poll   200 { ok, status, deviceCode, verificationUri, error }  (`pollDeviceLogin`)
//   DELETE /api/github/connection/login        200 { ok:true }                                (`cancelDeviceLogin`)
//   POST   /api/github/connection/token {token} 200 { ok, account, scopes } | 400 { ok:false, error }
//   POST   /api/github/connection/logout       200 { ok:true }                                (forgets Tether's PAT only)
// A refused owner-grade call answers 403 "This needs an owner sign-in …" (server.mjs :3321-3327).
//
// Every call goes through [FixedRouteHttp]: redirects off, a fixed path on the paired origin, and only
// when that origin is the one the screen drew from (else [SecurityResult.NotSent], nothing sent).
//
// The token ([GitHubToken]) prints as `***`; only this file reads it, to build the ONE request body
// that carries it (the web's `JSON.stringify({ token: tokenInput.trim() })`). The server never echoes
// it (lib/github-auth.mjs: status and verify return booleans, the account and the scopes only), and no
// model here has a field it could land in. Nothing here logs or stores.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A personal access token typed into Settings (settings-dialog.tsx `tokenInput`). It prints as `***`
 * and compares by identity only; [reveal] is module-internal and used once, for the request body.
 */
class GitHubToken(private val value: String) {
    internal fun reveal(): String = value.trim()

    val isBlank: Boolean get() = value.isBlank()

    override fun toString(): String = "GitHubToken(***)"
}

/** settings-dialog.tsx :946-953 `GitHubConnectionStatus` (lib/github-auth.mjs `statusGitHubConnection`). Server text, bounded. */
data class GitHubConnectionStatus(
    val ghInstalled: Boolean,
    val ghVersion: String?,
    val authenticated: Boolean,
    val account: String?,
    val scopes: List<String>,
    val managedToken: Boolean,
)

/** settings-dialog.tsx :955-961 `GitHubDevicePoll.status`, plus a value this client does not know (or none). */
enum class GitHubDeviceStatus { Idle, Pending, Complete, Error, Unknown }

/** settings-dialog.tsx :955-961 `GitHubDevicePoll` (lib/github-auth.mjs `pollDeviceLogin`). Server text, bounded. */
data class GitHubDevicePoll(
    val ok: Boolean,
    val status: GitHubDeviceStatus,
    val deviceCode: String?,
    val verificationUri: String?,
    val error: String?,
) {
    companion object {
        /** settings-dialog.tsx :1031: what `startDevice` shows until the first poll lands. */
        val Started = GitHubDevicePoll(ok = true, status = GitHubDeviceStatus.Pending, deviceCode = null, verificationUri = null, error = null)
    }
}

/** `POST /token`'s answer: who the token signs in as (never the token). */
data class GitHubTokenSaved(val account: String?, val scopes: List<String>)

/** The six calls of the section, each for the server [origin] the screen drew from. */
interface GitHubConnectionSource {
    suspend fun status(origin: String): SecurityResult<GitHubConnectionStatus>
    suspend fun startLogin(origin: String): SecurityResult<Unit>
    suspend fun pollLogin(origin: String): SecurityResult<GitHubDevicePoll>
    suspend fun cancelLogin(origin: String): SecurityResult<Unit>
    suspend fun saveToken(origin: String, token: GitHubToken): SecurityResult<GitHubTokenSaved>
    suspend fun logout(origin: String): SecurityResult<Unit>

    /** No client (previews, fakes): nothing is ever sent. */
    object Unavailable : GitHubConnectionSource {
        private fun <T> none(): SecurityResult<T> = SecurityResult.SignedOut()
        override suspend fun status(origin: String): SecurityResult<GitHubConnectionStatus> = none()
        override suspend fun startLogin(origin: String): SecurityResult<Unit> = none()
        override suspend fun pollLogin(origin: String): SecurityResult<GitHubDevicePoll> = none()
        override suspend fun cancelLogin(origin: String): SecurityResult<Unit> = none()
        override suspend fun saveToken(origin: String, token: GitHubToken): SecurityResult<GitHubTokenSaved> = none()
        override suspend fun logout(origin: String): SecurityResult<Unit> = none()
    }

    companion object {
        const val STATUS_PATH = "/api/github/connection"
        const val LOGIN_PATH = "/api/github/connection/login"
        const val POLL_PATH = "/api/github/connection/login/poll"
        const val TOKEN_PATH = "/api/github/connection/token"
        const val LOGOUT_PATH = "/api/github/connection/logout"

        /** Every answer is a few hundred bytes (a status lists the token's scopes). */
        const val MAX_BODY_BYTES: Long = 64L * 1024L

        /**
         * The status runs `gh --version` then `gh auth status` on the server, each bounded at 15 s
         * (lib/github-auth.mjs COMMAND_TIMEOUT_MS); a token is verified by one `gh auth status`.
         */
        const val CALL_TIMEOUT_MS: Long = 45_000L
    }
}

/** [GitHubConnectionSource] over [FixedRouteHttp], with the client's per-call (server, credential) read. */
class HttpGitHubConnection(
    http: OkHttpClient,
    private val authority: () -> FilesAuthority,
    maxBytes: Long = GitHubConnectionSource.MAX_BODY_BYTES,
    callTimeoutMs: Long = GitHubConnectionSource.CALL_TIMEOUT_MS,
) : GitHubConnectionSource {
    private val route = FixedRouteHttp(http, maxBytes, callTimeoutMs)

    // settings-dialog.tsx :983: `fetch("/api/github/connection", { cache: "no-store" })`.
    override suspend fun status(origin: String): SecurityResult<GitHubConnectionStatus> =
        call(origin, FixedRouteHttp.Method.GET, GitHubConnectionSource.STATUS_PATH, null) { it?.let(GitHubConnectionJson::status) }

    // :1027 POST with a JSON content type (the server reads no body; FixedRouteHttp sends `{}`).
    // r2 (verifier): success is `res.ok` alone (:1029), whatever the body.
    override suspend fun startLogin(origin: String): SecurityResult<Unit> =
        call(origin, FixedRouteHttp.Method.POST, GitHubConnectionSource.LOGIN_PATH, JsonObject(emptyMap())) { Unit }

    // :1006-1007 the poll GET: any JSON object is read; a 2xx that is not JSON is the web's failed
    // `res.json()` ("no response", the controller's).
    override suspend fun pollLogin(origin: String): SecurityResult<GitHubDevicePoll> =
        call(origin, FixedRouteHttp.Method.GET, GitHubConnectionSource.POLL_PATH, null) { it?.let(GitHubConnectionJson::poll) }

    // :1040 DELETE, no body (best effort on the web: its answer is never read).
    override suspend fun cancelLogin(origin: String): SecurityResult<Unit> =
        call(origin, FixedRouteHttp.Method.DELETE, GitHubConnectionSource.LOGIN_PATH, null) { Unit }

    // :1047-1051 `{ token: tokenInput.trim() }`; the web's button is disabled for a blank one, so
    // nothing is sent for it here either. r2: saved on `res.ok` alone (:1053), whatever the body.
    override suspend fun saveToken(origin: String, token: GitHubToken): SecurityResult<GitHubTokenSaved> {
        val value = token.reveal()
        if (value.isEmpty()) return SecurityResult.NotSent(origin)
        return call(origin, FixedRouteHttp.Method.POST, GitHubConnectionSource.TOKEN_PATH, buildJsonObject { put("token", value) }) {
            it?.let(GitHubConnectionJson::saved) ?: GitHubTokenSaved(null, emptyList())
        }
    }

    // :1067 POST, no body read by the server. r2 (verifier): success is `res.ok` alone (:1068), whatever the body.
    override suspend fun logout(origin: String): SecurityResult<Unit> =
        call(origin, FixedRouteHttp.Method.POST, GitHubConnectionSource.LOGOUT_PATH, JsonObject(emptyMap())) { Unit }

    /** [parse] gets the body as an object, or null when it is not JSON (or not an object, or over the cap). */
    private suspend fun <T> call(
        origin: String,
        method: FixedRouteHttp.Method,
        path: String,
        body: JsonObject?,
        parse: (JsonObject?) -> T?,
    ): SecurityResult<T> = when (val out = route.call(authority(), origin, method, path, body)) {
        FixedRouteHttp.Outcome.SignedOut -> SecurityResult.SignedOut()
        FixedRouteHttp.Outcome.LocalNetworkBlocked -> SecurityResult.LocalNetworkBlocked
        is FixedRouteHttp.Outcome.OtherOrigin -> SecurityResult.NotSent(out.origin)
        is FixedRouteHttp.Outcome.NotBuilt -> SecurityResult.NotSent(out.origin)
        is FixedRouteHttp.Outcome.Blocked -> SecurityResult.Blocked(out.code, out.origin)
        is FixedRouteHttp.Outcome.Unreachable -> SecurityResult.Unavailable(null, out.origin)
        is FixedRouteHttp.Outcome.Answered -> answered(out, parse)
    }

    /**
     * Tether's answer, read as the web reads it: a 2xx is the route's answer ([parse] decides what it
     * needs of the body); its own 401 is signed out; the owner-grade 403 is recognised by its fixed
     * opening; r2 (verifier): ANY other status with a JSON object is [SecurityResult.Refused] with its
     * `error` (a 5xx's too: the web shows `data.error`), empty when it has none (the caller's fallback,
     * the web's `|| "…"`; on the poll, a JSON answer with no status, which ends the flow quietly). A
     * non-JSON refusal is [SecurityResult.Unavailable]. A sign-in gateway never gets here ([FixedRouteHttp]'s
     * Blocked rule, T6.8).
     */
    private fun <T> answered(out: FixedRouteHttp.Outcome.Answered, parse: (JsonObject?) -> T?): SecurityResult<T> {
        val json = out.json
        val error = json?.let(DeviceSecurityJson::errorSentence)
        return when {
            out.code in 200..299 -> parse(json)?.let { SecurityResult.Ok(it, out.origin, null) } ?: SecurityResult.Unavailable(out.code, out.origin)
            out.code == 401 && out.jsonType -> SecurityResult.SignedOut(out.origin)
            out.code == 403 && error != null && error.startsWith(DeviceSecuritySource.OWNER_REFUSAL_OPENING) -> SecurityResult.OwnerSignInNeeded(out.origin)
            json != null -> SecurityResult.Refused(out.code, error.orEmpty(), out.origin)
            else -> SecurityResult.Unavailable(out.code, out.origin)
        }
    }
}

/**
 * The tolerant, bounded read of the answers: a field of the wrong type is absent; an unknown status
 * is [GitHubDeviceStatus.Unknown]; every string is cut before it is kept.
 */
object GitHubConnectionJson {
    /** An account name or a sentence (drawn later by the label rule, which bounds it again). */
    const val MAX_TEXT = 200

    /** A version, a scope, a status word, a device code. */
    const val MAX_CODE = 64

    /** Scopes kept (a classic PAT has a few dozen at most). */
    const val MAX_SCOPES = 64

    /**
     * `statusGitHubConnection`'s object. Null without a boolean `authenticated`: not the route's
     * answer (a gateway's `{"error": …}` at 200), so it never replaces a good status.
     */
    fun status(o: JsonObject): GitHubConnectionStatus? {
        val authenticated = bool(o["authenticated"]) ?: return null
        return GitHubConnectionStatus(
            ghInstalled = bool(o["ghInstalled"]) == true,
            ghVersion = string(o["ghVersion"], MAX_CODE)?.takeIf { it.isNotBlank() },
            authenticated = authenticated,
            account = string(o["account"], MAX_TEXT)?.takeIf { it.isNotBlank() },
            scopes = scopes(o["scopes"]),
            managedToken = bool(o["managedToken"]) == true,
        )
    }

    /**
     * `pollDeviceLogin`'s object, read as the web reads it (:1007-1015: only `status` steers): any
     * object is an answer; a missing or unknown status is [GitHubDeviceStatus.Unknown], which (not
     * being "pending") ends the poll as the web's does.
     */
    fun poll(o: JsonObject): GitHubDevicePoll {
        // r3 (owner rule): the address is kept WHOLE, of any length (bounded only by the reply's
        // [GitHubConnectionSource.MAX_BODY_BYTES]), as the web's `<a href>` opens any length and as the
        // Claude login link keeps it (ClaudeAccountActions.kt [ClaudeLoginLink]); never cut (a cut one
        // would open somewhere else). Whether a browser opens it is the shared link rule's, on screen.
        val rawUri = (o["verificationUri"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        return GitHubDevicePoll(
            ok = bool(o["ok"]) == true,
            status = when (string(o["status"], MAX_CODE)) {
                "idle" -> GitHubDeviceStatus.Idle
                "pending" -> GitHubDeviceStatus.Pending
                "complete" -> GitHubDeviceStatus.Complete
                "error" -> GitHubDeviceStatus.Error
                else -> GitHubDeviceStatus.Unknown
            },
            deviceCode = string(o["deviceCode"], MAX_CODE)?.takeIf { it.isNotEmpty() },
            verificationUri = rawUri,
            error = string(o["error"], MAX_TEXT)?.takeIf { it.isNotBlank() },
        )
    }

    /** `{ ok, account, scopes }` of a saved token. */
    fun saved(o: JsonObject): GitHubTokenSaved =
        GitHubTokenSaved(account = string(o["account"], MAX_TEXT)?.takeIf { it.isNotBlank() }, scopes = scopes(o["scopes"]))

    private fun scopes(element: JsonElement?): List<String> =
        (element as? JsonArray).orEmpty().asSequence()
            .mapNotNull { string(it, MAX_CODE)?.takeIf { s -> s.isNotBlank() } }
            .take(MAX_SCOPES)
            .toList()

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
}
