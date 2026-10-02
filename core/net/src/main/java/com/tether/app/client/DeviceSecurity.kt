package com.tether.app.client

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

// ─────────────────────────────────────────────────────────────────────────────
// T10.4: Settings → Devices, the web's SignInSecuritySection and PairedDevicesSection
// (components/sign-in-security.tsx, components/paired-devices.tsx, hooks/use-sign-in-security.ts,
// hooks/use-paired-devices.ts at tether 887c222). The routes (server.mjs 887c222 :7349-7541), every
// one `requireOwnerGrade`:
//   GET    /api/devices                    { devices, pairings }
//   POST   /api/devices/pair        {}     201 { code, expiresAt }      the plaintext code, ONCE
//   DELETE /api/devices                    { ok, revoked, appSessions, disconnected }   (revoke every device)
//   DELETE /api/devices/<id>               { ok, disconnected } | 404 { error }
//   GET    /api/auth/passkeys              { passkeys, passwordLoginEnabled, policySource, passkeysUsable, rpId }
//   PUT    /api/auth/passkeys/policy {passwordLoginEnabled}   { passwordLoginEnabled, policySource } | 409 { error }
//   PATCH  /api/auth/passkeys/<id>  {label}                    { passkey } | 404
//   DELETE /api/auth/passkeys/<id>         { ok } | 404 | 409 { error }
//   GET    /api/auth/sessions              { sessions }  (each with `current`)
//   DELETE /api/auth/sessions              { revoked, disconnected }   (sign out everywhere else)
//   DELETE /api/auth/sessions/<id>         { ok, disconnected } | 404
// The owner's 2026-10-02 decision (tether #236) makes these owner-grade for the app's sign-ins too;
// until it is deployed a phone sign-in gets 403 "This needs an owner sign-in …" ([SecurityResult.OwnerSignInNeeded]).
// `DELETE /api/devices/pairings` (cancel unclaimed codes) and passkey registration (a WebAuthn
// ceremony, T10.5) have no path here: the web offers neither from this panel (registration needs
// the browser's authenticator).
// ─────────────────────────────────────────────────────────────────────────────

/** How the app is signed in to the server a call went to: a paired-device bearer token, or a session cookie (a password or app-passkey sign-in). */
enum class AppSignIn { DeviceToken, SessionCookie }

/** One read of the client's (server, credential) pair: where a call may go, and with which kind of sign-in. */
class SecurityAuthority(val files: FilesAuthority, val signIn: AppSignIn?)

/** A paired device (lib/device-tokens.mjs `describe`). Never carries the token. [label] is raw server text, bounded. */
data class PairedDevice(val id: String, val label: String, val createdAt: Long, val lastSeenAt: Long) {
    /** [id] has a shape that may be put in a path; any other id is listed but cannot be revoked from here. */
    val actionable: Boolean get() = DeviceSecurityJson.isPathId(id)
}

/** A minted code nothing has claimed yet. Deliberately WITHOUT the code: the server keeps only a hash. */
data class OutstandingPairing(val label: String, val createdAt: Long, val expiresAt: Long)

data class DevicesList(val devices: List<PairedDevice>, val pairings: List<OutstandingPairing>)

/**
 * A freshly minted pairing code: a credential until it is claimed or expires (it mints a device
 * token). Not a data class: no generated `toString`, `component1` or `copy` can carry it; [toString]
 * prints nothing of it. Only [reveal] reads it, and only the revealed pairing card and its Copy key
 * call that. Never logged, saved or persisted.
 */
class PairingCode internal constructor(private val value: String) {
    /** The plaintext, for the revealed card and the Copy key only. */
    fun reveal(): String = value

    val length: Int get() = value.length

    /** Constant-time comparison (tests check what went where without printing it). */
    fun matches(candidate: String): Boolean =
        MessageDigest.isEqual(value.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))

    override fun toString(): String = "PairingCode(***)"
    override fun equals(other: Any?): Boolean = other is PairingCode && other.matches(value)
    override fun hashCode(): Int = 0
}

/** The mint call's answer (use-paired-devices.ts `FreshPairingCode`). Not a data class (see [PairingCode]). */
class FreshPairingCode(val code: PairingCode, val expiresAt: Long) {
    override fun toString(): String = "FreshPairingCode(code=***, expiresAt=$expiresAt)"
}

data class DeviceRevoked(val disconnected: Int)

data class DevicesRevokedAll(val revoked: Int, val appSessions: Int, val disconnected: Int)

/** use-sign-in-security.ts `Passkey`, the fields the panel draws. [label] is raw server text, bounded. */
data class Passkey(val id: String, val label: String, val createdAt: Long, val lastUsedAt: Long, val backedUp: Boolean) {
    val actionable: Boolean get() = DeviceSecurityJson.isPathId(id)
}

enum class PasskeyPolicySource { Env, Stored }

data class PasswordPolicy(val passwordLoginEnabled: Boolean, val source: PasskeyPolicySource)

data class PasskeysView(val passkeys: List<Passkey>, val policy: PasswordPolicy, val passkeysUsable: Boolean)

/** `SignInSessionMethod`; anything else reads as [Password], as on the web. */
enum class SessionMethod { Password, Passkey, Service, AppPasskey }

/** One signed-in session (lib/auth-sessions.mjs `publicRecord` + `current`). [userAgent] is raw text, bounded. */
data class SecuritySession(
    val id: String,
    val method: SessionMethod,
    val createdAt: Long,
    val lastSeenAt: Long,
    val userAgent: String,
    val current: Boolean,
) {
    val actionable: Boolean get() = DeviceSecurityJson.isPathId(id)
}

data class SessionsRevoked(val revoked: Int)

/**
 * What one call came to. [origin] is the canonical origin of the server the call went to (or was
 * refused for), so a screen drops an answer about any server but the one it shows.
 */
sealed interface SecurityResult<out T> {
    val origin: String?

    /** [signIn]: how the app was signed in for this call. */
    data class Ok<T>(val value: T, override val origin: String, val signIn: AppSignIn?) : SecurityResult<T>

    /** No credential, or Tether's own 401. */
    data class SignedOut(override val origin: String? = null) : SecurityResult<Nothing>

    /**
     * Tether's owner-grade refusal: 403 `{"error":"This needs an owner sign-in …"}` (the sentence
     * changes with tether #236; it is recognised by its fixed opening, never shown as is).
     */
    data class OwnerSignInNeeded(override val origin: String) : SecurityResult<Nothing>

    /**
     * Tether refused with its own `{error}` sentence (400/403/404/409/429/500 JSON): the web shows
     * that sentence; so does the app, cleaned by the label rule. [message] is raw, bounded.
     */
    data class Refused(val code: Int, val message: String, override val origin: String) : SecurityResult<Nothing>

    data object LocalNetworkBlocked : SecurityResult<Nothing> {
        override val origin: String? get() = null
    }

    /** A sign-in gateway answered instead of Tether (T6.8's rule). Nothing followed or kept. */
    data class Blocked(val code: Int, override val origin: String) : SecurityResult<Nothing>

    /** Unreachable, any other answer, a body over the cap or without the route's shape. */
    data class Unavailable(val code: Int?, override val origin: String) : SecurityResult<Nothing>

    /** Not sent: the client is signed in to another server than the one the screen was drawn from (or an id no route may name). */
    data class NotSent(override val origin: String?) : SecurityResult<Nothing>
}

/**
 * The panel's calls, with the paired credential. Every call names the server ([origin]) the screen
 * was drawn from; it goes only there. There is no other way to these routes from the UI.
 */
interface DeviceSecuritySource {
    suspend fun devices(origin: String): SecurityResult<DevicesList>
    suspend fun pair(origin: String): SecurityResult<FreshPairingCode>
    suspend fun revokeDevice(origin: String, deviceId: String): SecurityResult<DeviceRevoked>
    suspend fun revokeAllDevices(origin: String): SecurityResult<DevicesRevokedAll>
    suspend fun passkeys(origin: String): SecurityResult<PasskeysView>
    suspend fun renamePasskey(origin: String, passkeyId: String, label: String): SecurityResult<Unit>
    suspend fun removePasskey(origin: String, passkeyId: String): SecurityResult<Unit>
    suspend fun setPasswordLogin(origin: String, enabled: Boolean): SecurityResult<PasswordPolicy>
    suspend fun sessions(origin: String): SecurityResult<List<SecuritySession>>
    suspend fun revokeSession(origin: String, sessionId: String): SecurityResult<Unit>
    suspend fun revokeOtherSessions(origin: String): SecurityResult<SessionsRevoked>

    /** No client (previews, fakes): nothing is ever sent. */
    object Unavailable : DeviceSecuritySource {
        private fun <T> none(): SecurityResult<T> = SecurityResult.SignedOut()
        override suspend fun devices(origin: String): SecurityResult<DevicesList> = none()
        override suspend fun pair(origin: String): SecurityResult<FreshPairingCode> = none()
        override suspend fun revokeDevice(origin: String, deviceId: String): SecurityResult<DeviceRevoked> = none()
        override suspend fun revokeAllDevices(origin: String): SecurityResult<DevicesRevokedAll> = none()
        override suspend fun passkeys(origin: String): SecurityResult<PasskeysView> = none()
        override suspend fun renamePasskey(origin: String, passkeyId: String, label: String): SecurityResult<Unit> = none()
        override suspend fun removePasskey(origin: String, passkeyId: String): SecurityResult<Unit> = none()
        override suspend fun setPasswordLogin(origin: String, enabled: Boolean): SecurityResult<PasswordPolicy> = none()
        override suspend fun sessions(origin: String): SecurityResult<List<SecuritySession>> = none()
        override suspend fun revokeSession(origin: String, sessionId: String): SecurityResult<Unit> = none()
        override suspend fun revokeOtherSessions(origin: String): SecurityResult<SessionsRevoked> = none()
    }

    companion object {
        const val DEVICES_PATH = "/api/devices"
        const val PAIR_PATH = "/api/devices/pair"
        const val PASSKEYS_PATH = "/api/auth/passkeys"
        const val POLICY_PATH = "/api/auth/passkeys/policy"
        const val SESSIONS_PATH = "/api/auth/sessions"
        fun devicePath(id: String) = "$DEVICES_PATH/$id"
        fun passkeyPath(id: String) = "$PASSKEYS_PATH/$id"
        fun sessionPath(id: String) = "$SESSIONS_PATH/$id"

        /** The most body read: a list of 16 devices, 3 pairings, the passkeys and every live session is a few KiB. */
        const val MAX_BODY_BYTES: Long = 128L * 1024L

        const val CALL_TIMEOUT_MS: Long = 20_000L

        /** server.mjs `requireOwnerGrade`'s sentence opens with this at 887c222 and after tether #236. */
        const val OWNER_REFUSAL_OPENING = "This needs an owner sign-in"
    }
}

/**
 * [DeviceSecuritySource] over [FixedRouteHttp]: redirects off, the fixed route only, on the paired
 * origin only when it is the drawing origin, JSON in and out, the body capped, the gateway rule.
 */
class HttpDeviceSecurity(
    http: OkHttpClient,
    private val authority: () -> SecurityAuthority,
    maxBytes: Long = DeviceSecuritySource.MAX_BODY_BYTES,
    callTimeoutMs: Long = DeviceSecuritySource.CALL_TIMEOUT_MS,
) : DeviceSecuritySource {
    private val route = FixedRouteHttp(http, maxBytes, callTimeoutMs)
    override suspend fun devices(origin: String) = call(origin, FixedRouteHttp.Method.GET, DeviceSecuritySource.DEVICES_PATH, null, DeviceSecurityJson::devices)

    override suspend fun pair(origin: String) =
        call(origin, FixedRouteHttp.Method.POST, DeviceSecuritySource.PAIR_PATH, JsonObject(emptyMap()), DeviceSecurityJson::freshCode)

    override suspend fun revokeDevice(origin: String, deviceId: String) =
        withId(origin, deviceId) { call(origin, FixedRouteHttp.Method.DELETE, DeviceSecuritySource.devicePath(deviceId), null, DeviceSecurityJson::revoked) }

    override suspend fun revokeAllDevices(origin: String) =
        call(origin, FixedRouteHttp.Method.DELETE, DeviceSecuritySource.DEVICES_PATH, null, DeviceSecurityJson::revokedAll)

    override suspend fun passkeys(origin: String) = call(origin, FixedRouteHttp.Method.GET, DeviceSecuritySource.PASSKEYS_PATH, null, DeviceSecurityJson::passkeys)

    override suspend fun renamePasskey(origin: String, passkeyId: String, label: String) = withId(origin, passkeyId) {
        val body = buildJsonObject { put("label", TextCut.cut(label, DeviceSecurityJson.MAX_LABEL_SENT)) }
        call(origin, FixedRouteHttp.Method.PATCH, DeviceSecuritySource.passkeyPath(passkeyId), body) { o -> if (o["passkey"] is JsonObject) Unit else null }
    }

    override suspend fun removePasskey(origin: String, passkeyId: String) =
        withId(origin, passkeyId) { call(origin, FixedRouteHttp.Method.DELETE, DeviceSecuritySource.passkeyPath(passkeyId), null, DeviceSecurityJson::okUnit) }

    override suspend fun setPasswordLogin(origin: String, enabled: Boolean) =
        call(origin, FixedRouteHttp.Method.PUT, DeviceSecuritySource.POLICY_PATH, buildJsonObject { put("passwordLoginEnabled", enabled) }, DeviceSecurityJson::policy)

    override suspend fun sessions(origin: String) = call(origin, FixedRouteHttp.Method.GET, DeviceSecuritySource.SESSIONS_PATH, null, DeviceSecurityJson::sessions)

    override suspend fun revokeSession(origin: String, sessionId: String) =
        withId(origin, sessionId) { call(origin, FixedRouteHttp.Method.DELETE, DeviceSecuritySource.sessionPath(sessionId), null, DeviceSecurityJson::okUnit) }

    override suspend fun revokeOtherSessions(origin: String) =
        call(origin, FixedRouteHttp.Method.DELETE, DeviceSecuritySource.SESSIONS_PATH, null, DeviceSecurityJson::sessionsRevoked)

    /** A server-supplied id goes in a path only in a plain token shape: it can never name another route. */
    private inline fun <T> withId(origin: String, id: String, send: () -> SecurityResult<T>): SecurityResult<T> =
        if (DeviceSecurityJson.isPathId(id)) send() else SecurityResult.NotSent(origin)

    private suspend fun <T> call(
        origin: String,
        method: FixedRouteHttp.Method,
        path: String,
        body: JsonObject?,
        parse: (JsonObject) -> T?,
    ): SecurityResult<T> {
        val a = authority()
        return when (val out = route.call(a.files, origin, method, path, body)) {
            FixedRouteHttp.Outcome.SignedOut -> SecurityResult.SignedOut()
            FixedRouteHttp.Outcome.LocalNetworkBlocked -> SecurityResult.LocalNetworkBlocked
            is FixedRouteHttp.Outcome.OtherOrigin -> SecurityResult.NotSent(out.origin)
            is FixedRouteHttp.Outcome.NotBuilt -> SecurityResult.NotSent(out.origin)
            is FixedRouteHttp.Outcome.Blocked -> SecurityResult.Blocked(out.code, out.origin)
            is FixedRouteHttp.Outcome.Unreachable -> SecurityResult.Unavailable(null, out.origin)
            is FixedRouteHttp.Outcome.Answered -> answered(out, parse, a.signIn)
        }
    }

    private fun <T> answered(out: FixedRouteHttp.Outcome.Answered, parse: (JsonObject) -> T?, signIn: AppSignIn?): SecurityResult<T> {
        val json = out.json
        val error = json?.let { DeviceSecurityJson.errorSentence(it) }
        return when {
            out.code == 200 || out.code == 201 -> json?.let(parse)?.let { SecurityResult.Ok(it, out.origin, signIn) }
                ?: SecurityResult.Unavailable(out.code, out.origin)
            out.code == 401 && out.jsonType -> SecurityResult.SignedOut(out.origin)
            out.code == 403 && error != null && error.startsWith(DeviceSecuritySource.OWNER_REFUSAL_OPENING) -> SecurityResult.OwnerSignInNeeded(out.origin)
            out.code in REFUSAL_CODES && error != null -> SecurityResult.Refused(out.code, error, out.origin)
            else -> SecurityResult.Unavailable(out.code, out.origin)
        }
    }

    private companion object {
        /**
         * The statuses these routes answer with Tether's own `{error}` (server.mjs json(); a refused
         * write's 403 from lib/origin-guard.mjs; 429 a throttle; 500 the partial revoke-all). A 502,
         * 503 or 504 is a proxy's: its words are never shown.
         */
        val REFUSAL_CODES = setOf(400, 403, 404, 409, 413, 415, 429, 500)
    }
}

/**
 * The tolerant, bounded read of the bodies, following the web's readers (readDevices, readPairings,
 * readPasskeys, readSessions): an entry without a string id is dropped, a field of the wrong type is
 * absent, every string is cut before it is kept, and a body without the route's own shape is null
 * (never an empty answer).
 */
object DeviceSecurityJson {
    const val MAX_TEXT = 200
    const val MAX_USER_AGENT = 512
    const val MAX_ERROR = 1000

    /** A WebAuthn credential id is at most 1023 bytes; base64url is 4/3 of that. */
    const val MAX_ID = 1400
    const val MAX_ROWS = 256

    /** lib/passkeys.mjs MAX_LABEL_LENGTH (the server cuts there too). */
    const val MAX_LABEL_SENT = 64

    /** The pairing code: lib/device-tokens.mjs mints 8 from its alphabet; a later server's may be longer, never other characters. */
    private val CODE = Regex("^[A-Za-z0-9]{4,32}$")
    private val PATH_ID = Regex("^[A-Za-z0-9_-]{1,$MAX_ID}$")

    fun isPathId(id: String): Boolean = PATH_ID.matches(id)

    fun devices(o: JsonObject): DevicesList? {
        val devices = o["devices"] as? JsonArray ?: return null
        val pairings = o["pairings"] as? JsonArray
        return DevicesList(
            devices = rows(devices) { r ->
                val id = string(r["id"], MAX_ID)?.takeIf { it.isNotEmpty() } ?: return@rows null
                PairedDevice(id, string(r["label"], MAX_TEXT)?.takeIf { it.isNotEmpty() } ?: "Paired device", time(r["createdAt"]), time(r["lastSeenAt"]))
            },
            pairings = rows(pairings ?: JsonArray(emptyList())) { r ->
                val expiresAt = number(r["expiresAt"])?.toLong() ?: return@rows null
                OutstandingPairing(string(r["label"], MAX_TEXT).orEmpty(), time(r["createdAt"]), expiresAt)
            },
        )
    }

    /** `{ code, expiresAt }`; the web refuses a reply without both ("The server returned an unusable pairing code."). */
    fun freshCode(o: JsonObject): FreshPairingCode? {
        val p = o["code"] as? JsonPrimitive ?: return null
        if (!p.isString || !CODE.matches(p.content)) return null
        val expiresAt = number(o["expiresAt"])?.toLong() ?: return null
        return FreshPairingCode(PairingCode(p.content), expiresAt)
    }

    fun revoked(o: JsonObject): DeviceRevoked? = if (bool(o["ok"]) == true) DeviceRevoked(count(o["disconnected"])) else null

    fun revokedAll(o: JsonObject): DevicesRevokedAll? =
        if (bool(o["ok"]) == true) DevicesRevokedAll(count(o["revoked"]), count(o["appSessions"]), count(o["disconnected"])) else null

    fun okUnit(o: JsonObject): Unit? = if (bool(o["ok"]) == true) Unit else null

    fun passkeys(o: JsonObject): PasskeysView? {
        val list = o["passkeys"] as? JsonArray ?: return null
        return PasskeysView(
            passkeys = rows(list) { r ->
                val id = string(r["id"], MAX_ID)?.takeIf { it.isNotEmpty() } ?: return@rows null
                Passkey(id, string(r["label"], MAX_TEXT)?.takeIf { it.isNotEmpty() } ?: "Passkey", time(r["createdAt"]), time(r["lastUsedAt"]), bool(r["backedUp"]) == true)
            },
            policy = policyOf(o),
            passkeysUsable = bool(o["passkeysUsable"]) == true,
        )
    }

    /** `{ passwordLoginEnabled, policySource }`: null without a boolean `passwordLoginEnabled`. */
    fun policy(o: JsonObject): PasswordPolicy? = if (bool(o["passwordLoginEnabled"]) == null) null else policyOf(o)

    private fun policyOf(o: JsonObject) = PasswordPolicy(
        // The web: `!== false` (absent = on).
        passwordLoginEnabled = bool(o["passwordLoginEnabled"]) != false,
        source = if (string(o["policySource"], 16) == "env") PasskeyPolicySource.Env else PasskeyPolicySource.Stored,
    )

    fun sessions(o: JsonObject): List<SecuritySession>? {
        val list = o["sessions"] as? JsonArray ?: return null
        return rows(list) { r ->
            val id = string(r["id"], MAX_ID)?.takeIf { it.isNotEmpty() } ?: return@rows null
            SecuritySession(
                id = id,
                method = when (string(r["method"], 32)) {
                    "passkey" -> SessionMethod.Passkey
                    "service" -> SessionMethod.Service
                    "app-passkey" -> SessionMethod.AppPasskey
                    else -> SessionMethod.Password
                },
                createdAt = time(r["createdAt"]),
                lastSeenAt = time(r["lastSeenAt"]),
                userAgent = string(r["userAgent"], MAX_USER_AGENT).orEmpty(),
                current = bool(r["current"]) == true,
            )
        }
    }

    fun sessionsRevoked(o: JsonObject): SessionsRevoked? = if (o.containsKey("revoked")) SessionsRevoked(count(o["revoked"])) else null

    /** Tether's `{error}` sentence, bounded, or null. */
    fun errorSentence(o: JsonObject): String? = string(o["error"], MAX_ERROR)?.takeIf { it.isNotBlank() }

    private fun <T> rows(array: JsonArray, read: (JsonObject) -> T?): List<T> {
        val seen = HashSet<Any>()
        return array.asSequence().take(MAX_ROWS * 4).mapNotNull { (it as? JsonObject)?.let(read) }
            .filter { seen.add(idOf(it)) }
            .take(MAX_ROWS)
            .toList()
    }

    /** A second row with an id already listed is dropped (rows are keyed by id on screen). */
    private fun idOf(row: Any?): Any = when (row) {
        is PairedDevice -> "d:" + row.id
        is Passkey -> "p:" + row.id
        is SecuritySession -> "s:" + row.id
        else -> Any()
    }

    private fun string(e: JsonElement?, max: Int): String? {
        val p = e as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return TextCut.cut(p.content, max)
    }

    private fun bool(e: JsonElement?): Boolean? {
        val p = e as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        return when (p.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun number(e: JsonElement?): Double? {
        val p = e as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        return p.content.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** A time in ms, or 0 for anything else (the web's `Number(x) || 0`), bounded to a plausible range. */
    private fun time(e: JsonElement?): Long = number(e)?.takeIf { it > 0 && it < 1e15 }?.toLong() ?: 0L

    private fun count(e: JsonElement?): Int = number(e)?.takeIf { it >= 0 && it < 1e9 }?.toInt() ?: 0
}
