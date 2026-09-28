package com.tether.app.ui

import com.tether.app.client.IncompatibleReason
import com.tether.app.client.Incompatibility
import com.tether.app.client.LoginResult
import com.tether.app.client.PairResult
import com.tether.app.client.SignInRequirements
import com.tether.app.client.SignedOutReason
import com.tether.app.ui.prefs.LoginVariant
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The login screen's non-visual logic, shared by the three surfaces and kept
 * free of Compose so it is unit-testable. Mirrors components/login/use-login-flow.ts
 * (the web's state machine) plus the native-only parts (server URL, pairing).
 */

/** The three web sign-in screens (PLAN D12). */
enum class LoginSurface { Instrument, Studio, Retro }

/**
 * app/login/page.tsx: Retro is the per-device opt-in and ignores the theme;
 * otherwise the theme family decides — Studio's welcome for Studio, the
 * Instrument terminal readout for every other family.
 */
fun loginSurfaceFor(variant: LoginVariant, studioFamily: Boolean): LoginSurface = when {
    variant == LoginVariant.Retro -> LoginSurface.Retro
    studioFamily -> LoginSurface.Studio
    else -> LoginSurface.Instrument
}

/** The two ways in: the browser password, or a code minted by a browser session. */
enum class AuthMode { Password, Pairing }

/**
 * use-login-flow.ts LoginPhase, minus the passkey states (T10.5). [Checking] is
 * native-only: a password submit that arrived before the sign-in probe answered
 * waits for it (ta-s4r), where the web simply hides the form while probing.
 */
enum class LoginPhase { Ready, Checking, Verifying, Success, Error }

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

/** Instrument frame-bar status (instrument-login.tsx statusState). */
fun instrumentStatusLabel(phase: LoginPhase, probing: Boolean): String = when {
    phase == LoginPhase.Checking -> "probing"
    phase == LoginPhase.Verifying -> "verifying"
    phase == LoginPhase.Success -> "unlocked"
    phase == LoginPhase.Error -> "refused"
    probing -> "probing"
    else -> "locked"
}

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
    is LoginResult.Success, is LoginResult.LocalNetworkBlocked -> null
    is LoginResult.BadPassword -> result.message.ifBlank { "Those credentials are not correct." } +
        if (usernameHint) " $USERNAME_MISSING_HINT" else ""
    is LoginResult.GatewayRefused -> gatewayRefusedCopy(result)
    is LoginResult.RateLimited -> result.message.ifBlank { "Too many attempts. Try again in a few minutes." }
    is LoginResult.PasswordDisabled ->
        result.message.ifBlank { "Password sign-in is turned off for this console." } +
            " Pair this device with a code instead."
    is LoginResult.Unreachable -> result.message.ifBlank { "Could not reach the server." }
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
    is PairResult.Success, is PairResult.LocalNetworkBlocked -> null
    is PairResult.Rejected -> result.message.ifBlank { "That pairing code is not valid or has expired." }
    is PairResult.RateLimited -> result.message.ifBlank { "Too many pairing attempts. Try again in a few minutes." }
    is PairResult.NotSupported -> result.message.ifBlank { "This server does not support device pairing." }
    is PairResult.Unreachable -> result.message.ifBlank { "Could not reach the server." }
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
