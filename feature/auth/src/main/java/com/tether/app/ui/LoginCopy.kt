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

/** use-login-flow.ts LoginPhase, minus the passkey states (T10.5). */
enum class LoginPhase { Ready, Verifying, Success, Error }

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

/** Instrument frame-bar status (instrument-login.tsx statusState). */
fun instrumentStatusLabel(phase: LoginPhase, probing: Boolean): String = when {
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

/** Error line for a password attempt; null = success or the local-network flow takes over. */
fun loginErrorCopy(result: LoginResult): String? = when (result) {
    is LoginResult.Success, is LoginResult.LocalNetworkBlocked -> null
    is LoginResult.BadPassword -> result.message.ifBlank { "Those credentials are not correct." }
    is LoginResult.RateLimited -> result.message.ifBlank { "Too many attempts. Try again in a few minutes." }
    is LoginResult.PasswordDisabled ->
        result.message.ifBlank { "Password sign-in is turned off for this console." } +
            " Pair this device with a code instead."
    is LoginResult.Unreachable -> result.message.ifBlank { "Could not reach the server." }
    is LoginResult.VersionMismatch -> versionCopy(result.incompatibility)
}

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
