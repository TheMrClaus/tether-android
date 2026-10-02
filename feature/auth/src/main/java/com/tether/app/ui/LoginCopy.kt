package com.tether.app.ui

import com.tether.app.client.IncompatibleReason
import com.tether.app.client.LabelText
import com.tether.app.client.Incompatibility
import com.tether.app.client.LoginResult
import com.tether.app.client.PairResult
import com.tether.app.client.SignInRequirements
import com.tether.app.client.SignedOutReason
import com.tether.app.ui.prefs.LoginVariant
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The login screen's non-visual logic, shared by the two surfaces and kept
 * free of Compose so it is unit-testable. Mirrors components/login/use-login-flow.ts
 * (the web's state machine) plus the native-only parts (server URL, pairing).
 */

/** The web's two sign-in screens (app/login/page.tsx), both dressed in Studio. */
enum class LoginSurface { Studio, Retro }

/**
 * app/login/page.tsx: Retro is the per-device opt-in layout; "Default" is Studio's
 * welcome sign-in. The former Instrument screen was retired with the theme families.
 */
fun loginSurfaceFor(variant: LoginVariant): LoginSurface =
    if (variant == LoginVariant.Retro) LoginSurface.Retro else LoginSurface.Studio

/** The two ways in: the browser password, or a code minted by a browser session. */
enum class AuthMode { Password, Pairing }

/**
 * use-login-flow.ts LoginPhase. [Verifying] is the web's verifying-password (or a pairing claim),
 * [VerifyingPasskey] its verifying-passkey (T10.5). [Checking] is native-only: a password submit that
 * arrived before the sign-in probe answered waits for it (ta-s4r), where the web simply hides the
 * form while probing.
 */
enum class LoginPhase { Ready, Checking, Verifying, VerifyingPasskey, Success, Error }

/**
 * use-login-flow.ts `passkeyReady`: a passkey is registered, the console's address allows one
 * (`passkeysUsable`), and this phone can run the ceremony (the web's `browserSupportsWebAuthn()`).
 * Unknown requirements (still probing, or the probe failed) offer no passkey, like the web.
 */
fun passkeyReady(requirements: SignInRequirements?, available: Boolean): Boolean =
    requirements != null && requirements.passkeyCount > 0 && requirements.passkeysUsable && available

/**
 * r2 (security F1): a passkey is offered only for an https address (the typed URL as the client
 * normalises it: no scheme = https). An http one offers none and says [PASSKEY_NEEDS_HTTPS] instead.
 */
fun passkeyAddressAllowed(rawUrl: String): Boolean {
    val trimmed = rawUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return false
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    return withScheme.toHttpUrlOrNull()?.isHttps == true
}

const val PASSKEY_NEEDS_HTTPS = com.tether.app.client.PasskeyLoginCopy.NEEDS_HTTPS

/** r2: retro-login.tsx:213, the password line's accessible name while a passkey is ready. */
const val RETRO_PASSWORD_WITH_PASSKEY = "Dashboard password — or press Enter alone to use a passkey"

/** ta-coik.1: the same for Retro's pairing-code line (the app's own path), where Enter alone is the passkey too. */
const val RETRO_CODE_WITH_PASSKEY = "Pairing code — or press Enter alone to use a passkey"

/** ta-coik.1: Studio's separator under the passkey key on the app's own Pairing path. */
const val PASSKEY_OR_PAIRING = "or pair this device with a code"

/**
 * r2 (security F4): server-supplied text (a refusal's `{error}`, a transport message) as the login
 * screen shows it: through the same cleanup the Devices panel uses (hidden and bidi characters dropped,
 * whitespace folded, bounded at [LabelText.MAX_ERROR]); [fallback] when nothing visible is left.
 */
fun serverText(text: String?, fallback: String): String = LabelText.error(text).ifEmpty { fallback }

/** The web's notice when the operator closes the passkey prompt (not an error: back to ready). */
const val PASSKEY_DISMISSED_NOTICE = "Passkey prompt dismissed."

/** Host shown in the readouts ("console · host"), or "" while the URL does not parse. */
fun hostnameOf(rawUrl: String): String {
    val trimmed = rawUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    return withScheme.toHttpUrlOrNull()?.host.orEmpty()
}

/**
 * use-login-flow.ts statusLines: the console's REAL sign-in readings, never
 * invented. [requirements] null + [probeFailed] false = still checking.
 */
fun statusLines(hostname: String, requirements: SignInRequirements?, probeFailed: Boolean): List<String> {
    val lines = mutableListOf("console · ${hostname.ifEmpty { "…" }}")
    if (requirements == null && !probeFailed) {
        lines += "sign-in requirements · checking…"
        return lines
    }
    val probe = requirements ?: SignInRequirements(
        usernameRequired = false,
        passwordLoginEnabled = true,
        passkeyCount = 0,
        passkeysUsable = false,
    )
    lines += if (probeFailed) "sign-in requirements · unreachable" else "sign-in requirements · ok"
    lines += if (probe.passkeyCount > 0) {
        "passkeys · ${probe.passkeyCount} registered${if (probe.passkeysUsable) "" else " · needs https"}"
    } else {
        "passkeys · none registered"
    }
    lines += "password sign-in · ${if (probe.passwordLoginEnabled) "on" else "off"}"
    return lines
}

/**
 * Whether the password form shows the username line. The probe said so, or the
 * probe could not be read at all (ta-s4r): a console with TETHER_USERNAME set
 * refuses a password-only login, so an unknown answer must leave a way to type
 * one. Still probing (null, not failed) hides it, like the web's form.
 */
fun usernameFieldShown(requirements: SignInRequirements?, probeFailed: Boolean): Boolean =
    requirements?.usernameRequired == true || (requirements == null && probeFailed)

/** The username line is shown only because the probe failed, so it may be left empty. */
fun usernameFieldOptional(requirements: SignInRequirements?, probeFailed: Boolean): Boolean =
    requirements == null && probeFailed

/** Under an optional username line (probe unreachable). */
const val USERNAME_OPTIONAL_HINT = "Only needed if this console has a username."

/** Appended to a refused password when no username went with it (ta-s4r). */
const val USERNAME_MISSING_HINT = "If this console has a username, enter it too."

/**
 * A refused password should also point at the username when none was sent and
 * the console was not positively known to have none: the server's 401 is the
 * same "Those credentials are not correct." for a missing username as for a
 * wrong password.
 */
fun usernameHintFor(requirements: SignInRequirements?, username: String): Boolean =
    username.isBlank() && requirements?.usernameRequired != false

/** Which side must update is known from /healthz (the D5 native window). */
fun versionCopy(incompatibility: Incompatibility): String = when (incompatibility.reason) {
    IncompatibleReason.ClientTooOld -> "This server needs a newer version of the app. Update Tether, then connect."
    IncompatibleReason.ServerTooOld -> "This server is older than the app. Update the server, then connect."
}

/**
 * Error line for a password attempt; null = success or the local-network flow takes over.
 * [usernameHint] (see [usernameHintFor]) adds [USERNAME_MISSING_HINT] to a refusal.
 */
fun loginErrorCopy(result: LoginResult, usernameHint: Boolean = false): String? = when (result) {
    // A dismissed passkey prompt is a notice, not an error ([PASSKEY_DISMISSED_NOTICE]).
    is LoginResult.Success, is LoginResult.LocalNetworkBlocked, is LoginResult.PasskeyDismissed -> null
    // ta-coik.1 r3: an earlier outcome stands; the screen ignores this one ([LoginScreen]).
    is LoginResult.Superseded -> null
    is LoginResult.PasskeyFailed -> serverText(result.message, "Passkey sign-in failed.")
    is LoginResult.BadPassword -> serverText(result.message, "Those credentials are not correct.") +
        if (usernameHint) " $USERNAME_MISSING_HINT" else ""
    is LoginResult.GatewayRefused -> gatewayRefusedCopy(result)
    is LoginResult.RateLimited -> serverText(result.message, "Too many attempts. Try again in a few minutes.")
    is LoginResult.PasswordDisabled ->
        serverText(result.message, "Password sign-in is turned off for this console.") +
            " Pair this device with a code instead."
    is LoginResult.Unreachable -> serverText(result.message, "Could not reach the server.")
    is LoginResult.VersionMismatch -> versionCopy(result.incompatibility)
}

/**
 * A 401 from something in front of Tether, never worded as a wrong password (ta-s4r): the
 * credentials were not checked by Tether at all, so retyping them cannot help.
 */
fun gatewayRefusedCopy(result: LoginResult.GatewayRefused): String =
    "The server refused the sign-in before Tether checked the password (HTTP ${result.status}" +
        (result.server?.let { ", from $it" } ?: "") +
        (result.scheme?.let { ", asking for $it authentication" } ?: "") + "). " +
        "A sign-in gateway (SSO or a proxy) guards the password login, so the app cannot use it here. " +
        "Pair this device with a code from the browser instead."

/** Error line for a pairing attempt; null = success or the local-network flow takes over. */
fun pairErrorCopy(result: PairResult): String? = when (result) {
    is PairResult.Success, is PairResult.LocalNetworkBlocked, is PairResult.Superseded -> null
    is PairResult.Rejected -> serverText(result.message, "That pairing code is not valid or has expired.")
    is PairResult.RateLimited -> serverText(result.message, "Too many pairing attempts. Try again in a few minutes.")
    is PairResult.NotSupported -> serverText(result.message, "This server does not support device pairing.")
    is PairResult.Unreachable -> serverText(result.message, "Could not reach the server.")
    is PairResult.VersionMismatch -> versionCopy(result.incompatibility)
}

/** Why the login screen is showing, when the server (not the user) ended the sign-in. */
fun signedOutCopy(reason: SignedOutReason): String = when (reason) {
    SignedOutReason.SessionExpired -> "Your session expired or was signed out. Sign in again."
    SignedOutReason.DeviceUnpaired -> "This device is no longer paired with the server. Pair it again with a new code."
    SignedOutReason.GatewayRefused ->
        "Something in front of the server refused this sign-in (a proxy or SSO gateway). " +
            "Check that /api/auth/session is exempt, then sign in again."
}

/**
 * Local validation before any request. Returns the error line, or null when
 * the attempt may go out.
 */
fun validateAttempt(
    mode: AuthMode,
    baseUrl: String,
    username: String,
    password: String,
    code: String,
    requirements: SignInRequirements?,
): String? = when {
    baseUrl.isBlank() -> "Enter the server URL."
    mode == AuthMode.Pairing && code.isBlank() -> "Enter the pairing code from your browser."
    mode == AuthMode.Password && requirements?.passwordLoginEnabled == false ->
        "Password sign-in is turned off for this console. Pair this device with a code instead."
    mode == AuthMode.Password && requirements?.usernameRequired == true && username.isBlank() -> "Enter your username."
    mode == AuthMode.Password && password.isEmpty() -> "Enter the password."
    else -> null
}
