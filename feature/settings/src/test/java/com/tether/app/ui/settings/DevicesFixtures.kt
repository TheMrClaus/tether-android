package com.tether.app.ui.settings

import com.tether.app.client.AppSignIn
import com.tether.app.client.DeviceRevoked
import com.tether.app.client.DeviceSecuritySource
import com.tether.app.client.DevicesList
import com.tether.app.client.DevicesRevokedAll
import com.tether.app.client.FreshPairingCode
import com.tether.app.client.OutstandingPairing
import com.tether.app.client.PairedDevice
import com.tether.app.client.PairingCode
import com.tether.app.client.Passkey
import com.tether.app.client.PasskeyPolicySource
import com.tether.app.client.PasskeysView
import com.tether.app.client.PasswordPolicy
import com.tether.app.client.SecurityResult
import com.tether.app.client.SecuritySession
import com.tether.app.client.SessionMethod
import com.tether.app.client.SessionsRevoked
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred

/** T10.4: the Devices panel's seeds. Every code here is a sentinel or obviously FAKE. */
object DevicesFixtures {
    const val ORIGIN = "https://console.example.test"
    const val OTHER_ORIGIN = "https://other-console.example.test"

    /** Looked for in semantics, logs, preferences, saved state and the clipboard (the server's alphabet, 8 long). */
    const val SENTINEL = "SNTL7Q9Z"

    /** What the revealed golden shows: obviously not a live code (it is never sent anywhere). */
    const val FAKE_CODE = "FAKE2345"

    const val NOW = 1_759_400_000_000L

    val PHONE = PairedDevice("a1b2c3d4e5f60718", "Pixel 8", NOW - 3 * 86_400_000L, NOW - 5 * 60_000L)
    val TABLET = PairedDevice("0f1e2d3c4b5a6978", "Galaxy Tab", NOW - 20 * 86_400_000L, NOW - 26 * 3_600_000L)
    val DEVICES = listOf(PHONE, TABLET)
    val PAIRINGS = listOf(OutstandingPairing("Paired device", NOW - 60_000L, NOW + 240_000L))

    val LAPTOP_KEY = Passkey("cred-AbC_123", "Laptop", NOW - 40 * 86_400_000L, NOW - 2 * 3_600_000L, backedUp = true)
    val YUBIKEY = Passkey("cred-XyZ_789", "YubiKey", NOW - 90 * 86_400_000L, 0L, backedUp = false)
    val PASSKEYS = PasskeysView(listOf(LAPTOP_KEY, YUBIKEY), PasswordPolicy(true, PasskeyPolicySource.Stored), passkeysUsable = true)
    val NO_PASSKEYS = PasskeysView(emptyList(), PasswordPolicy(true, PasskeyPolicySource.Stored), passkeysUsable = true)

    val BROWSER = SecuritySession("5e55a1d0000000000000000000000001", SessionMethod.Password, NOW - 86_400_000L, NOW - 10 * 60_000L, "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0", current = false)
    val APP = SecuritySession("5e55a1d0000000000000000000000002", SessionMethod.AppPasskey, NOW - 3 * 3_600_000L, NOW - 60_000L, "okhttp/5.4.0", current = true)
    val PASSWORD_HERE = APP.copy(method = SessionMethod.Password)
    val SESSIONS = listOf(BROWSER, APP)

    fun code(value: String = SENTINEL, expiresAt: Long = NOW + 299_000L) = FreshPairingCode(PairingCode(value), expiresAt)

    fun <T> ok(value: T, signIn: AppSignIn? = AppSignIn.SessionCookie, origin: String = ORIGIN) = SecurityResult.Ok(value, origin, signIn)

    /** A seed: everything loaded for [ORIGIN]. */
    fun seed(signIn: AppSignIn = AppSignIn.SessionCookie, code: FreshPairingCode? = null, devices: List<PairedDevice> = DEVICES, sessions: List<SecuritySession> = SESSIONS) = DevicesSeed(
        devices = devices,
        pairings = PAIRINGS,
        passkeys = PASSKEYS,
        sessions = sessions,
        signIn = signIn,
        code = code,
    )
}

/**
 * A source that records each call and leaves it waiting until the test answers it (the way the
 * real one waits for the network). [calls] names each call; reads and writes alike.
 */
class RecordingSecuritySource : DeviceSecuritySource {
    class Call(val name: String, val origin: String, val arg: String?, val reply: CompletableDeferred<SecurityResult<*>> = CompletableDeferred()) {
        override fun toString() = "$name($origin, $arg)"
    }

    val calls = CopyOnWriteArrayList<Call>()

    val writes: List<Call> get() = calls.filter { it.name !in READS }

    fun names(): List<String> = calls.map { it.name }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> record(name: String, origin: String, arg: String? = null): SecurityResult<T> {
        val call = Call(name, origin, arg)
        calls += call
        return call.reply.await() as SecurityResult<T>
    }

    /** Answer the oldest unanswered call named [name]. */
    fun answer(name: String, result: SecurityResult<*>) {
        val call = calls.firstOrNull { it.name == name && !it.reply.isCompleted } ?: error("no pending $name in ${names()}")
        call.reply.complete(result)
    }

    fun pending(name: String): Boolean = calls.any { it.name == name && !it.reply.isCompleted }

    /** Answer every pending read with the loaded fixtures (for [signIn]). */
    fun answerReads(
        signIn: AppSignIn? = AppSignIn.SessionCookie,
        devices: List<PairedDevice> = DevicesFixtures.DEVICES,
        passkeys: PasskeysView = DevicesFixtures.PASSKEYS,
        sessions: List<SecuritySession> = DevicesFixtures.SESSIONS,
        origin: String = DevicesFixtures.ORIGIN,
    ) {
        while (pending("devices")) answer("devices", SecurityResult.Ok(DevicesList(devices, DevicesFixtures.PAIRINGS), origin, signIn))
        while (pending("passkeys")) answer("passkeys", SecurityResult.Ok(passkeys, origin, signIn))
        while (pending("sessions")) answer("sessions", SecurityResult.Ok(sessions, origin, signIn))
    }

    override suspend fun devices(origin: String) = record<DevicesList>("devices", origin)
    override suspend fun pair(origin: String) = record<FreshPairingCode>("pair", origin)
    override suspend fun revokeDevice(origin: String, deviceId: String) = record<DeviceRevoked>("revokeDevice", origin, deviceId)
    override suspend fun revokeAllDevices(origin: String) = record<DevicesRevokedAll>("revokeAll", origin)
    override suspend fun passkeys(origin: String) = record<PasskeysView>("passkeys", origin)
    override suspend fun renamePasskey(origin: String, passkeyId: String, label: String) = record<Unit>("rename", origin, "$passkeyId=$label")
    override suspend fun removePasskey(origin: String, passkeyId: String) = record<Unit>("removePasskey", origin, passkeyId)
    override suspend fun setPasswordLogin(origin: String, enabled: Boolean) = record<PasswordPolicy>("policy", origin, enabled.toString())
    override suspend fun sessions(origin: String) = record<List<SecuritySession>>("sessions", origin)
    override suspend fun revokeSession(origin: String, sessionId: String) = record<Unit>("revokeSession", origin, sessionId)
    override suspend fun revokeOtherSessions(origin: String) = record<SessionsRevoked>("revokeOthers", origin)

    companion object {
        val READS = setOf("devices", "passkeys", "sessions")
    }
}

/**
 * The source behind a seeded shot: any call is a timing dependency (and a bug), so it fails the
 * shot. An AssertionError, not an Exception (the controller shows an Exception as a failure line).
 */
object NeverCalledSecurity : DeviceSecuritySource {
    private fun no(): Nothing = throw AssertionError("a seeded shot must not call the server")
    override suspend fun devices(origin: String) = no()
    override suspend fun pair(origin: String) = no()
    override suspend fun revokeDevice(origin: String, deviceId: String) = no()
    override suspend fun revokeAllDevices(origin: String) = no()
    override suspend fun passkeys(origin: String) = no()
    override suspend fun renamePasskey(origin: String, passkeyId: String, label: String) = no()
    override suspend fun removePasskey(origin: String, passkeyId: String) = no()
    override suspend fun setPasswordLogin(origin: String, enabled: Boolean) = no()
    override suspend fun sessions(origin: String) = no()
    override suspend fun revokeSession(origin: String, sessionId: String) = no()
    override suspend fun revokeOtherSessions(origin: String) = no()
}
