package com.tether.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.tether.app.client.AppSignIn
import com.tether.app.client.DeviceSecuritySource
import com.tether.app.client.FreshPairingCode
import com.tether.app.client.LabelText
import com.tether.app.client.OutstandingPairing
import com.tether.app.client.PairedDevice
import com.tether.app.client.Passkey
import com.tether.app.client.PasskeyAuthenticator
import com.tether.app.client.PasskeyCeremony
import com.tether.app.client.PasskeyPolicySource
import com.tether.app.client.PasskeyRules
import com.tether.app.client.PasskeysView
import com.tether.app.client.SecurityResult
import com.tether.app.client.SecuritySession
import com.tether.app.client.SessionMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * T10.4: Settings -> Devices below Notifications (settings-dialog.tsx 887c222 :2101-2102):
 * SignInSecuritySection (Passkeys, Signed-in sessions) then PairedDevicesSection, editable from the
 * phone like the web (owner, 2026-10-02; tether #236). Until #236 is deployed the server answers a
 * phone sign-in 403 "This needs an owner sign-in …": the panel says so once, keeps what it can show,
 * and asks again only when the operator taps Check again (never by itself).
 */

/** The web's words (paired-devices.tsx, sign-in-security.tsx, the two hooks) and the app's own where the web has none. */
object DevicesCopy {
    // Paired devices (paired-devices.tsx).
    const val DEVICES_TITLE = "Paired devices"
    const val DEVICES_CAPTION = "Native clients that hold their own access token for this server."
    const val CODE_LABEL = "Pairing code"
    const val CODE_SHOWN_ONCE = "Shown once."
    const val CODE_NOTE = " Tether stores only a hash of this code, so it cannot be displayed again — enter it in the app before it expires, or pair again for a new one."
    const val COPY = "Copy code"
    const val COPIED = "Copied"
    const val EXPIRED = "Expired. Pair again for a new code."
    fun expiresIn(label: String) = "Expires in $label"
    fun pairedLine(paired: String, seen: String) = "paired $paired · last seen $seen"
    fun revokeLabel(label: String) = "Revoke $label"
    const val REVOKE = "Revoke"
    const val DEVICES_EMPTY = "Pairing lets the Tether Android app sign in with a token of its own, which is how a phone connects when this server sits behind an SSO proxy it cannot log in to."
    const val DEVICES_CHECKING = "Checking paired devices…"
    const val PAIR = "Pair a device"
    const val PAIRING = "Pairing…"
    fun pairHint(others: Int) = when (others) {
        0 -> "Creates a one-time code, good for five minutes and one device."
        1 -> "Creates a one-time code. One earlier code is still unclaimed and cannot be shown again."
        else -> "Creates a one-time code. $others earlier codes are still unclaimed and cannot be shown again."
    }
    const val REVOKE_TITLE = "Revoke this device?"
    fun revokeBody(label: String) = "$label loses access immediately and its live connection is closed. It has to be paired again with a new code."
    const val REVOKE_CONFIRM = "Revoke device"
    fun revoked(disconnected: Int) =
        if (disconnected > 0) "Device revoked. $disconnected live connection${if (disconnected == 1) "" else "s"} closed." else "Device revoked."
    const val DEVICES_LOAD_FAILED = "Paired devices could not be loaded."
    const val PAIR_FAILED = "A pairing code could not be created."
    const val UNUSABLE_CODE = "The server returned an unusable pairing code."
    const val REVOKE_FAILED = "That device could not be revoked."

    // Passkeys and sessions (sign-in-security.tsx).
    const val PASSKEYS_TITLE = "Passkeys"
    const val PASSKEYS_CAPTION = "A passkey replaces the password with your device’s own screen lock or security key. It is phishing-resistant, and you can register one for each device you sign in from."
    const val SYNCED = "Synced"
    fun passkeyLine(added: String, used: String?) = "added $added · " + (used?.let { "last used $it" } ?: "never used")
    fun renameLabel(label: String) = "Rename $label"
    fun removeLabel(label: String) = "Remove $label"
    const val REMOVE = "Remove"
    const val PASSKEYS_EMPTY = "No passkeys yet. Add one to sign in without a password."
    const val PASSKEYS_CHECKING = "Checking passkeys…"
    const val PASSKEYS_NEED_HTTPS = "Passkeys need HTTPS (or localhost) to work — this console is neither right now."
    const val ADD_PASSKEY = "Add a passkey"
    const val ADDING_PASSKEY = "Adding…"
    /** The web's placeholder says "this laptop"; the app is a phone. */
    const val PASSKEY_LABEL_PLACEHOLDER = "Label (e.g. this phone)"
    const val PASSKEY_LABEL = "Passkey label"
    const val DEFAULT_PASSKEY_LABEL = "Passkey"
    const val PASSKEY_DISMISSED = "Passkey prompt dismissed."
    const val PASSKEY_DUPLICATE = "This authenticator already has a passkey for Tether."
    const val ADD_PASSKEY_FAILED = "The passkey could not be added."
    const val PASSWORD_TITLE = "Password sign-in"
    const val PASSWORD_ENV = "Set by TETHER_PASSKEY_REQUIRED in the environment"
    const val PASSWORD_NEEDS_PASSKEY = "Add a passkey before turning off the password"
    const val PASSWORD_ON = "Sign in with a password, in addition to any passkeys"
    /** r2 (security F6): the app's own words for why a device-token sign-in cannot turn the password off. */
    const val PASSWORD_NEEDS_PASSKEY_SIGN_IN = "Turning the password off needs a passkey sign-in, which proves the passkey works first. This phone signed in as a paired device, so turn it off from a passkey sign-in."
    const val REMOVE_PASSKEY_TITLE = "Remove this passkey?"
    fun removePasskeyBody(label: String) = "$label will no longer be able to sign in to Tether."
    const val REMOVE_PASSKEY_CONFIRM = "Remove passkey"
    const val SESSIONS_TITLE = "Signed-in sessions"
    const val SESSIONS_CAPTION = "Every browser or device currently signed in to this console."
    fun sessionLine(started: String, active: String) = "started $started · active $active"
    const val UNKNOWN_DEVICE = "Unknown device"
    const val SIGN_OUT = "Sign out"
    const val SIGN_OUT_LABEL = "Sign out this session"
    const val SESSIONS_CHECKING = "Checking sessions…"
    const val SIGN_OUT_OTHERS = "Sign out everywhere else"
    const val SIGN_OUT_OTHERS_TITLE = "Sign out every other session?"
    const val PASSKEY_ADDED = "Passkey added."
    const val PASSKEY_REMOVED = "Passkey removed."
    const val SESSION_SIGNED_OUT = "Session signed out."
    fun othersSignedOut(revoked: Int) =
        if (revoked > 0) "Signed out $revoked other session${if (revoked == 1) "" else "s"}." else "No other sessions to sign out."
    const val SECURITY_LOAD_FAILED = "Sign-in security could not be loaded."
    const val RENAME_FAILED = "That passkey could not be renamed."
    const val REMOVE_PASSKEY_FAILED = "That passkey could not be removed."
    const val POLICY_FAILED = "That setting could not be changed."
    const val SESSION_FAILED = "That session could not be signed out."
    const val OTHERS_FAILED = "Other sessions could not be signed out."
    fun method(method: SessionMethod) = when (method) {
        SessionMethod.Passkey -> "Passkey"
        SessionMethod.Service -> "Service window"
        SessionMethod.AppPasskey -> "Android app (passkey)"
        SessionMethod.Password -> "Password"
    }

    // The app's own.
    /** The web's "This browser" tag: the app is a phone. */
    const val THIS_DEVICE = "This device"
    const val REVEAL_CODE = "Reveal pairing code"
    const val HIDE_CODE = "Hide pairing code"
    const val CODE_HIDDEN = "Pairing code, hidden"
    fun codeSpoken(code: String) = "Pairing code " + code.toCharArray().joinToString(" ")
    const val MASK = "•••• ••••"
    /** T10.5: the app's own words for what only a phone can meet. */
    const val PASSKEY_UNSUPPORTED = "No passkey provider on this phone can create one. Turn one on in Android Settings, then try again."
    const val PASSKEY_UNAVAILABLE = "This phone cannot create passkeys here."
    /** r2 (security F1). */
    const val PASSKEY_NEEDS_HTTPS = com.tether.app.client.PasskeyLoginCopy.NEEDS_HTTPS
    /** r2 (security F5): the ceremony made a passkey the server never saved. */
    const val PASSKEY_ORPHANED = "The passkey was created on this phone but not saved on the server; you can remove it from your passkey manager."
    /** r2: the web's `passkey-label-input` maxLength (the server cuts at 64). */
    const val PASSKEY_LABEL_MAX = 60
    const val PASSKEY_WRONG_RP = "This server asked for a passkey for another address, so none was created. Add it from the address the console itself uses."
    const val SIGN_OUT_OTHERS_BODY = "This phone stays signed in. Every other session closes immediately."
    const val SELF_SIGNS_OUT = "This is the phone you are using: it is signed out of this server, and you sign in again from the start screen."
    const val MAYBE_SELF = "If this is the phone you are using, it is signed out of this server too, and you sign in again from the start screen."
    const val SIGNED_OUT_HERE = "This phone was signed out of this server. Sign in again from the start screen."
    const val OWNER_NEEDED = "This server has not been updated yet to let the app manage devices and sign-in security: it asks for an owner sign-in. Once the server is updated this works from the phone like the web; until then, use the web console."
    const val CHECK_AGAIN = "Check again"
    const val SIGNED_OUT = "This phone is not signed in to this server."
    const val NOT_SENT = "Nothing was sent: the app is now signed in to another server."
    const val LOCAL_NETWORK = "Local network access is blocked, so this server cannot be reached."
    fun blocked(code: Int) = "A sign-in page answered instead of Tether (HTTP $code). Nothing was sent past it."
}

/** Which of the two web hooks a write belongs to (each has its own `busy`). */
enum class DevicesArea { Devices, Security }

enum class DevicesAction { Pair, Revoke, Rename, AddPasskey, RemovePasskey, Policy, SignOutSession, SignOutOthers }

/** A line under a section: [error] true is the web's `role="alert"` warning, else its `role="status"` notice. */
data class DevicesLine(val text: String, val error: Boolean)

/**
 * Whether a paired device is the one this phone is signed in with. A session-cookie sign-in holds no
 * device token in force, so no device is this phone. For a device token: tether #240 (unmerged) marks
 * the caller's own entry `current: true`; when an entry is so marked it is this phone and no other
 * is. An older server marks none: the token's own device is necessarily in the list, so when it is
 * the only one it is this phone; otherwise it may be any of them.
 */
enum class SelfMatch { No, Yes, Maybe }

/** The pure decisions, tested on their own. */
object DevicesRules {
    fun selfMatch(signIn: AppSignIn?, devices: List<PairedDevice>, device: PairedDevice): SelfMatch = when (signIn) {
        AppSignIn.SessionCookie -> SelfMatch.No
        AppSignIn.DeviceToken -> when {
            devices.any { it.current } -> if (device.current) SelfMatch.Yes else SelfMatch.No
            devices.size == 1 && devices[0].id == device.id -> SelfMatch.Yes
            else -> SelfMatch.Maybe
        }
        null -> SelfMatch.Maybe
    }

    fun revokeBody(label: String, self: SelfMatch): List<String> = listOfNotNull(
        DevicesCopy.revokeBody(label),
        when (self) {
            SelfMatch.Yes -> DevicesCopy.SELF_SIGNS_OUT
            SelfMatch.Maybe -> DevicesCopy.MAYBE_SELF
            SelfMatch.No -> null
        },
    )

    /** paired-devices.tsx `otherPairings`: unclaimed codes still live, the one on screen matched out by its expiry. */
    fun otherPairings(pairings: List<OutstandingPairing>, now: Long, shownExpiresAt: Long?): Int =
        pairings.count { it.expiresAt > now && it.expiresAt != shownExpiresAt }

    /** Seconds left on a code (the web's `Math.round`), never below 0. */
    fun secondsLeft(expiresAt: Long, now: Long): Long = maxOf(0L, Math.round((expiresAt - now) / 1000.0))

    /**
     * sign-in-security.tsx's toggle note and whether the switch may be used. r2 (security F6): the
     * server refuses turning the password OFF from a sign-in that proved no passkey (a device token
     * always gets 409), so from a device token the off direction is not offered and the note says why;
     * turning it back ON stays possible.
     */
    fun passwordNote(view: PasskeysView, signIn: AppSignIn?): String = when {
        view.policy.source == PasskeyPolicySource.Env -> DevicesCopy.PASSWORD_ENV
        view.passkeys.isEmpty() -> DevicesCopy.PASSWORD_NEEDS_PASSKEY
        view.policy.passwordLoginEnabled && signIn == AppSignIn.DeviceToken -> DevicesCopy.PASSWORD_NEEDS_PASSKEY_SIGN_IN
        else -> DevicesCopy.PASSWORD_ON
    }

    fun passwordToggleable(view: PasskeysView, signIn: AppSignIn?): Boolean = when {
        view.policy.source == PasskeyPolicySource.Env -> false
        !view.policy.passwordLoginEnabled -> true
        else -> view.passkeys.isNotEmpty() && signIn != AppSignIn.DeviceToken
    }

    /** `truncateUserAgent`, by the label rule (hidden characters dropped first). */
    fun userAgent(ua: String): String {
        val clean = LabelText.clean(ua, 400)
        if (clean.isEmpty()) return DevicesCopy.UNKNOWN_DEVICE
        return if (clean.length > 64) com.tether.app.client.TextCut.cut(clean, 61).trimEnd() + "…" else clean
    }

    /** A server label by the label rule; one that cleans to nothing falls back like the web's reader. */
    fun label(text: String, fallback: String): String = LabelText.title(text, LabelText.MAX_LABEL).ifEmpty { fallback }

    /**
     * A failed call's line: Tether's own sentence cleaned by the label rule (the web shows it), or
     * the app's words for what the web cannot meet; [fallback] the web's for anything else.
     * OwnerSignInNeeded and SignedOut are panel states, not lines (null).
     */
    fun failure(result: SecurityResult<*>, fallback: String): String? = when (result) {
        is SecurityResult.Ok -> null
        is SecurityResult.OwnerSignInNeeded, is SecurityResult.SignedOut -> null
        is SecurityResult.Refused -> LabelText.error(result.message).ifEmpty { fallback }
        SecurityResult.LocalNetworkBlocked -> DevicesCopy.LOCAL_NETWORK
        is SecurityResult.Blocked -> DevicesCopy.blocked(result.code)
        is SecurityResult.NotSent -> DevicesCopy.NOT_SENT
        is SecurityResult.Unavailable -> fallback
    }
}

/**
 * The fresh code on screen (the web's component state: the ONLY copy in existence). [code] is null
 * once it has expired (the card then says so with nothing left to reveal or copy).
 */
class ShownCode(val code: FreshPairingCode?, val expiresAt: Long, internal val serial: Long) {
    override fun toString(): String = "ShownCode(expiresAt=$expiresAt, live=${code != null})"
}

/** A state a seeded shot hands the controller (timing-free): nothing is fetched while it stands. */
data class DevicesSeed(
    val devices: List<PairedDevice>? = null,
    val pairings: List<OutstandingPairing> = emptyList(),
    val passkeys: PasskeysView? = null,
    val sessions: List<SecuritySession>? = null,
    val signIn: AppSignIn? = null,
    /** The owner-grade refusal, for both areas. */
    val ownerNeeded: Boolean = false,
    val devicesLine: DevicesLine? = null,
    val securityLine: DevicesLine? = null,
    /** A code already on screen (the goldens' FAKE one). */
    val code: FreshPairingCode? = null,
    /** T10.5: a Sign-in security write in flight (the goldens' "Adding…"). */
    val securityBusy: DevicesAction? = null,
)

/**
 * The panel's state and calls for ONE server ([origin]), held by the dialog (not the tab): a tab
 * change neither cancels a call nor loses its answer or the code on screen; closing Settings drops
 * it all (the code with it, as the web's dialog close does). Never saved state: a rotation or a
 * recreation starts with nothing and sends nothing but the opening reads.
 *
 * Writes follow the web's two hooks: one in flight per area ([busy] set in the tap's own frame, so
 * a second tap starts nothing), each followed by a re-read; reads overlap safely (only the newest
 * may write the lists). Every answer must be about [origin], or it is dropped. Nothing is retried.
 */
@Stable
class DevicesController(
    private val source: DeviceSecuritySource,
    val origin: String?,
    parent: CoroutineScope,
    seed: DevicesSeed? = null,
    private val clipboard: PairingClipboard = PairingClipboard.None,
    /** The wall clock a code's expiry is measured on (a seam for the tests). */
    private val now: () -> Long = { System.currentTimeMillis() },
    /** T10.5: Credential Manager on a device, a fake in tests; [PasskeyAuthenticator.None] offers nothing. */
    val authenticator: PasskeyAuthenticator = PasskeyAuthenticator.None,
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)

    var devices: List<PairedDevice>? by mutableStateOf(seed?.devices)
        private set
    var pairings: List<OutstandingPairing> by mutableStateOf(seed?.pairings.orEmpty())
        private set
    var passkeys: PasskeysView? by mutableStateOf(seed?.passkeys)
        private set
    var sessions: List<SecuritySession>? by mutableStateOf(seed?.sessions)
        private set
    var signIn: AppSignIn? by mutableStateOf(seed?.signIn)
        private set
    /**
     * r2 (verifier F3): the owner-grade refusal, PER AREA (the web's two hooks): one area's reads
     * succeeding never clears the other's note.
     */
    var devicesOwnerNeeded: Boolean by mutableStateOf(seed?.ownerNeeded == true)
        private set
    var securityOwnerNeeded: Boolean by mutableStateOf(seed?.ownerNeeded == true)
        private set

    fun ownerNeeded(area: DevicesArea): Boolean = if (area == DevicesArea.Devices) devicesOwnerNeeded else securityOwnerNeeded
    var signedOut: Boolean by mutableStateOf(false)
        private set
    var devicesLine: DevicesLine? by mutableStateOf(seed?.devicesLine)
        private set
    var securityLine: DevicesLine? by mutableStateOf(seed?.securityLine)
        private set
    var devicesBusy: DevicesAction? by mutableStateOf(null)
        private set
    var securityBusy: DevicesAction? by mutableStateOf(seed?.securityBusy)
        private set
    var shown: ShownCode? by mutableStateOf(seed?.code?.let { ShownCode(it, it.expiresAt, 0L) })
        private set
    /** T10.5: bumped by every passkey this panel added (the label field clears on it, as the web's does). */
    var passkeysAdded: Int by mutableStateOf(0)
        private set

    private var opened = seed != null
    private var expiryJob: Job? = null
    private var devicesTicket = 0L
    private var securityTicket = 0L
    private var codeSerial = 0L

    /** The dialog composed this controller: a seeded code's expiry starts counting (r2, security F7). */
    fun activate() {
        shown?.let(::scheduleExpiry)
    }

    /** The Devices tab opened: read once per controller (a seed counts as read). */
    fun open() {
        if (opened) return
        opened = true
        refresh()
    }

    /** Both readers (the web's two `refresh()`s); Check again calls it too. */
    fun refresh() {
        if (origin == null) {
            signedOut = true
            return
        }
        refreshDevices()
        refreshSecurity()
    }

    /**
     * [afterMaybeSelf] (r2, security F2): the re-read right after revoking a device that may have been
     * this phone. Tether's own 401 then means it was: the client is told the credential is dead
     * (compare-and-clear) and the panel signs out here, instead of waiting for the socket close.
     */
    private fun refreshDevices(afterMaybeSelf: Boolean = false): Job? {
        val o = origin ?: return null
        val ticket = ++devicesTicket
        return scope.launch {
            val r = source.devices(o)
            if (ticket != devicesTicket || !mine(r)) return@launch
            when (r) {
                is SecurityResult.Ok -> {
                    devices = r.value.devices
                    pairings = r.value.pairings
                    signIn = r.signIn
                    devicesOwnerNeeded = false
                    if (devicesLine?.error == true) devicesLine = null
                }
                is SecurityResult.SignedOut -> if (afterMaybeSelf && r.origin != null) {
                    r.handle?.let(source::credentialRejected)
                    signedOutHere()
                } else {
                    settle(r, DevicesArea.Devices, DevicesCopy.DEVICES_LOAD_FAILED)
                }
                else -> settle(r, DevicesArea.Devices, DevicesCopy.DEVICES_LOAD_FAILED)
            }
        }
    }

    private fun refreshSecurity(): Job? {
        val o = origin ?: return null
        val ticket = ++securityTicket
        return scope.launch {
            val p = async { source.passkeys(o) }
            val s = async { source.sessions(o) }
            val pr = p.await()
            val sr = s.await()
            if (ticket != securityTicket || !mine(pr) || !mine(sr)) return@launch
            if (pr is SecurityResult.Ok && sr is SecurityResult.Ok) {
                passkeys = pr.value
                sessions = sr.value
                signIn = pr.signIn
                securityOwnerNeeded = false
                if (securityLine?.error == true) securityLine = null
            } else {
                settle(if (pr !is SecurityResult.Ok) pr else sr, DevicesArea.Security, DevicesCopy.SECURITY_LOAD_FAILED)
            }
        }
    }

    /** "Pair a device": no confirmation (the web). The code lands here only, and only for this server. */
    fun pair(): Boolean = write(DevicesArea.Devices, DevicesAction.Pair) { o ->
        val r = source.pair(o)
        if (!mine(r)) return@write
        if (r is SecurityResult.Ok) {
            forgetCode()
            val fresh = ShownCode(r.value, r.value.expiresAt, ++codeSerial)
            shown = fresh
            scheduleExpiry(fresh)
            signIn = r.signIn
            refreshDevices()
        } else {
            settle(r, DevicesArea.Devices, if (r is SecurityResult.Unavailable && r.code in 200..299) DevicesCopy.UNUSABLE_CODE else DevicesCopy.PAIR_FAILED)
        }
    }

    /**
     * Revoke one device (asked first by the panel, as on the web). [self]: this phone, as far as known.
     * A row the server's id does not let us name (r2, verifier F1/F2) is never sent. Once this phone is
     * known revoked, the client drops the dead credential at once (r2, security F2).
     */
    fun revoke(device: PairedDevice, self: SelfMatch): Boolean {
        if (!device.actionable) return false
        return write(DevicesArea.Devices, DevicesAction.Revoke) { o ->
            val r = source.revokeDevice(o, device.id)
            if (!mine(r)) return@write
            if (r is SecurityResult.Ok) {
                devicesLine = DevicesLine(DevicesCopy.revoked(r.value.disconnected), error = false)
                if (self == SelfMatch.Yes) {
                    r.handle?.let(source::credentialRejected)
                    return@write signedOutHere()
                }
            } else {
                settle(r, DevicesArea.Devices, DevicesCopy.REVOKE_FAILED)
            }
            // The web re-lists either way (a 404 means the list on screen is the stale thing).
            refreshDevices(afterMaybeSelf = r is SecurityResult.Ok && self == SelfMatch.Maybe)
        }
    }

    fun renamePasskey(passkey: Passkey, label: String): Boolean {
        if (!passkey.actionable) return false
        val next = label.trim()
        if (next.isEmpty() || next == passkey.label) return false
        return write(DevicesArea.Security, DevicesAction.Rename) { o ->
            val r = source.renamePasskey(o, passkey.id, next)
            if (!mine(r)) return@write
            if (r !is SecurityResult.Ok) settle(r, DevicesArea.Security, DevicesCopy.RENAME_FAILED)
            refreshSecurity()
        }
    }

    /**
     * T10.5, the web's registerPasskey: a challenge, the ceremony on this phone, the answer with
     * [label] (`label.trim() || "Passkey"`), then a re-read. No confirmation of the app's own: the tap
     * opens Credential Manager's prompt, which the operator unlocks or closes. The prompt is asked
     * only for this server's own rpId (PasskeyRules.rpIdMatches); anything else creates nothing.
     */
    fun addPasskey(label: String): Boolean {
        if (!authenticator.available || passkeys?.passkeysUsable != true) return false
        // r2 (security F1): no ceremony and no call for an http server.
        if (origin == null || !PasskeyRules.ceremonyAllowed(origin)) return false
        return write(DevicesArea.Security, DevicesAction.AddPasskey) { o ->
            val options = source.passkeyRegistrationOptions(o)
            if (!mine(options)) return@write
            if (options !is SecurityResult.Ok) return@write settle(options, DevicesArea.Security, DevicesCopy.ADD_PASSKEY_FAILED)
            val challenge = options.value
            if (!PasskeyRules.rpIdMatches(challenge.rpId, o)) return@write failLine(DevicesCopy.PASSKEY_WRONG_RP)
            val answer = when (val ceremony = authenticator.register(challenge.optionsJson())) {
                is PasskeyCeremony.Done -> PasskeyRules.response(ceremony) ?: return@write failLine(DevicesCopy.ADD_PASSKEY_FAILED)
                PasskeyCeremony.Dismissed -> return@write failLine(DevicesCopy.PASSKEY_DISMISSED)
                PasskeyCeremony.Duplicate -> return@write failLine(DevicesCopy.PASSKEY_DUPLICATE)
                PasskeyCeremony.Unsupported, PasskeyCeremony.NoCredential -> return@write failLine(DevicesCopy.PASSKEY_UNSUPPORTED)
                PasskeyCeremony.Failed -> return@write failLine(DevicesCopy.ADD_PASSKEY_FAILED)
            }
            // r2 (security F5): from here a passkey exists on this phone. Unless the server confirms it
            // saved it, the line says so (whatever else went wrong), so the operator can remove it.
            val r = try {
                source.registerPasskey(o, challenge.challengeId, answer, label.trim().ifEmpty { DevicesCopy.DEFAULT_PASSKEY_LABEL })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@write failLine(DevicesCopy.PASSKEY_ORPHANED)
            }
            if (r is SecurityResult.Ok && mine(r)) {
                securityLine = DevicesLine(DevicesCopy.PASSKEY_ADDED, false)
                passkeysAdded += 1
                refreshSecurity()
            } else {
                if (r is SecurityResult.OwnerSignInNeeded && mine(r)) securityOwnerNeeded = true
                // r3: Tether's own 401 still signs the panel out, as settle() does; the unsaved line stays.
                if (r is SecurityResult.SignedOut && mine(r)) signedOut = true
                val why = (r as? SecurityResult.Refused)?.takeIf { mine(it) }?.let { DevicesRules.failure(it, DevicesCopy.ADD_PASSKEY_FAILED) }
                failLine(listOfNotNull(why, DevicesCopy.PASSKEY_ORPHANED).joinToString(" "))
            }
        }
    }

    private fun failLine(text: String) {
        securityLine = DevicesLine(text, error = true)
    }

    fun removePasskey(passkey: Passkey): Boolean {
        if (!passkey.actionable) return false
        return write(DevicesArea.Security, DevicesAction.RemovePasskey) { o ->
            val r = source.removePasskey(o, passkey.id)
            if (!mine(r)) return@write
            if (r is SecurityResult.Ok) securityLine = DevicesLine(DevicesCopy.PASSKEY_REMOVED, false) else settle(r, DevicesArea.Security, DevicesCopy.REMOVE_PASSKEY_FAILED)
            refreshSecurity()
        }
    }

    /**
     * The password switch: no confirmation (the web); the server's policy answer is shown as it says.
     * r2 (security F6): never OFF from a device-token sign-in (the server always refuses it).
     */
    fun setPasswordLogin(enabled: Boolean): Boolean = if (!enabled && signIn == AppSignIn.DeviceToken) false else write(DevicesArea.Security, DevicesAction.Policy) { o ->
        val r = source.setPasswordLogin(o, enabled)
        if (!mine(r)) return@write
        if (r is SecurityResult.Ok) {
            passkeys = passkeys?.copy(policy = r.value)
        } else {
            settle(r, DevicesArea.Security, DevicesCopy.POLICY_FAILED)
        }
    }

    /** One session's Sign out: no confirmation (the web); the current session has no key. */
    fun revokeSession(session: SecuritySession): Boolean {
        if (session.current || !session.actionable) return false
        return write(DevicesArea.Security, DevicesAction.SignOutSession) { o ->
            val r = source.revokeSession(o, session.id)
            if (!mine(r)) return@write
            if (r is SecurityResult.Ok) securityLine = DevicesLine(DevicesCopy.SESSION_SIGNED_OUT, false) else settle(r, DevicesArea.Security, DevicesCopy.SESSION_FAILED)
            refreshSecurity()
        }
    }

    /** "Sign out everywhere else" (asked first by the panel, as on the web). */
    fun revokeOtherSessions(): Boolean = write(DevicesArea.Security, DevicesAction.SignOutOthers) { o ->
        val r = source.revokeOtherSessions(o)
        if (!mine(r)) return@write
        if (r is SecurityResult.Ok) securityLine = DevicesLine(DevicesCopy.othersSignedOut(r.value.revoked), false) else settle(r, DevicesArea.Security, DevicesCopy.OTHERS_FAILED)
        refreshSecurity()
    }

    /** The code on screen expired: the plaintext goes (the card still says it expired). */
    fun expire(serial: Long) {
        val s = shown ?: return
        if (s.serial != serial || s.code == null) return
        clipboard.clearIfHolds(s.code.code)
        shown = ShownCode(null, s.expiresAt, s.serial)
    }

    /** Copy the code on screen: marked sensitive, cleared again shortly after (see [PairingClipboard]). */
    fun copyCode(): Boolean {
        val code = shown?.code ?: return false
        return clipboard.copy(code.code)
    }

    /**
     * r2 (security F7): the code's expiry runs in the controller's own scope, so the plaintext (and its
     * clipboard copy) goes when the code runs out even while the card is off screen (another tab).
     * Checked at most every [EXPIRY_STEP_MS] (a wall clock that jumps is caught), [EXPIRY_CHECKS] times.
     */
    private fun scheduleExpiry(s: ShownCode) {
        expiryJob?.cancel()
        if (s.code == null) return
        expiryJob = scope.launch {
            repeat(EXPIRY_CHECKS) {
                val left = s.expiresAt - now()
                if (left <= 0) return@launch expire(s.serial)
                delay(left.coerceAtMost(EXPIRY_STEP_MS))
            }
        }
    }

    /** Settings closed or another server: the code goes, and its copy with it; nothing in flight answers here any more. */
    fun dispose() {
        forgetCode()
        job.cancel()
    }

    private fun forgetCode() {
        expiryJob?.cancel()
        shown?.code?.let { clipboard.clearIfHolds(it.code) }
        shown = null
    }

    private fun signedOutHere() {
        forgetCode()
        devicesLine = DevicesLine(DevicesCopy.SIGNED_OUT_HERE, error = false)
        signedOut = true
    }

    private fun mine(r: SecurityResult<*>): Boolean = origin != null && (r.origin == null || r.origin == origin)

    private fun settle(r: SecurityResult<*>, area: DevicesArea, fallback: String) {
        when (r) {
            is SecurityResult.OwnerSignInNeeded -> if (area == DevicesArea.Devices) devicesOwnerNeeded = true else securityOwnerNeeded = true
            is SecurityResult.SignedOut -> signedOut = true
            else -> {
                val line = DevicesLine(DevicesRules.failure(r, fallback) ?: fallback, error = true)
                if (area == DevicesArea.Devices) devicesLine = line else securityLine = line
            }
        }
    }

    private fun write(area: DevicesArea, action: DevicesAction, call: suspend (String) -> Unit): Boolean {
        val o = origin ?: return false
        if (signedOut) return false
        if ((if (area == DevicesArea.Devices) devicesBusy else securityBusy) != null) return false
        if (area == DevicesArea.Devices) {
            devicesBusy = action
            devicesLine = null
        } else {
            securityBusy = action
            securityLine = null
        }
        scope.launch {
            try {
                call(o)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Never silent: the web's generic failure for that area.
                val line = DevicesLine(if (area == DevicesArea.Devices) DevicesCopy.DEVICES_LOAD_FAILED else DevicesCopy.SECURITY_LOAD_FAILED, true)
                if (area == DevicesArea.Devices) devicesLine = line else securityLine = line
            } finally {
                if (area == DevicesArea.Devices) devicesBusy = null else securityBusy = null
            }
        }
        return true
    }

    private companion object {
        const val EXPIRY_STEP_MS = 15_000L
        const val EXPIRY_CHECKS = 240
    }
}

/**
 * The controller for the dialog: one per (source, server), in plain `remember` (never saved), and
 * disposed (the code and its clipboard copy dropped, its calls cancelled) when the dialog closes or
 * the server changes.
 */
@Composable
fun rememberDevicesController(
    source: DeviceSecuritySource,
    origin: String?,
    seed: DevicesSeed? = null,
    clipboard: PairingClipboard = PairingClipboard.None,
    now: () -> Long = { System.currentTimeMillis() },
    authenticator: PasskeyAuthenticator = PasskeyAuthenticator.None,
): DevicesController {
    val scope = rememberCoroutineScope()
    val controller = remember(source, origin, authenticator) { DevicesController(source, origin, scope, seed?.takeIf { origin != null }, clipboard, now, authenticator) }
    LaunchedEffect(controller) { controller.activate() }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    return controller
}

/**
 * What the Devices panel draws: [controller] for the server signed in to (its origin null when
 * signed out), [now] the clock the countdown and the "last seen" lines read (a seam for the goldens).
 */
data class DevicesBinding(
    val controller: DevicesController?,
    val now: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        val None = DevicesBinding(null)
    }
}
