package com.tether.app.ui

import com.tether.app.client.Compatibility
import com.tether.app.client.IncompatibleReason
import com.tether.app.client.LoginResult
import com.tether.app.client.PairResult
import com.tether.app.client.SignInRequirements
import com.tether.app.client.SignedOutReason
import com.tether.app.protocol.ServerMessage
import com.tether.app.ui.prefs.LoginVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginCopyTest {
    private val probe = SignInRequirements(usernameRequired = false, passwordLoginEnabled = true, passkeyCount = 0, passkeysUsable = false)

    @Test
    fun surfaceSelectionMatchesAppLoginPage() {
        // app/login/page.tsx: Retro is the opt-in layout, Default is Studio's own sign-in.
        assertEquals(listOf(LoginSurface.Studio, LoginSurface.Retro), LoginSurface.entries)
        assertEquals(LoginSurface.Studio, loginSurfaceFor(LoginVariant.Default))
        assertEquals(LoginSurface.Retro, loginSurfaceFor(LoginVariant.Retro))
        assertEquals(LoginVariant.Default, LoginVariant.fromId(null))
        assertEquals(LoginVariant.Default, LoginVariant.fromId("studio"))
        // The retired "instrument" choice reads as Default (lib/theme-mode.mjs normalizeLoginVariant).
        assertEquals(LoginVariant.Default, LoginVariant.fromId("instrument"))
        assertEquals(LoginVariant.Retro, LoginVariant.fromId("retro"))
        assertEquals("default", LoginVariant.Default.id)
    }

    @Test
    fun statusLinesAreTheWebReadings() {
        assertEquals(
            listOf("console · tether.example.com", "sign-in requirements · checking…"),
            statusLines("tether.example.com", null, probeFailed = false),
        )
        assertEquals(
            listOf("console · h", "sign-in requirements · ok", "passkeys · none registered", "password sign-in · on"),
            statusLines("h", probe, probeFailed = false),
        )
        assertEquals(
            listOf("console · h", "sign-in requirements · ok", "passkeys · 2 registered · needs https", "password sign-in · off"),
            statusLines("h", probe.copy(passkeyCount = 2, passwordLoginEnabled = false), probeFailed = false),
        )
        assertEquals(
            listOf("console · …", "sign-in requirements · unreachable", "passkeys · none registered", "password sign-in · on"),
            statusLines("", null, probeFailed = true),
        )
    }

    @Test
    fun hostnameParsing() {
        assertEquals("tether.example.com", hostnameOf("tether.example.com/"))
        assertEquals("10.0.2.2", hostnameOf("http://10.0.2.2:4290"))
        assertEquals("", hostnameOf("  "))
    }

    @Test
    fun errorCopyUsesTheServerMessageAndPointsAtPairingWhenPasswordIsOff() {
        assertNull(loginErrorCopy(LoginResult.Success))
        assertNull(loginErrorCopy(LoginResult.LocalNetworkBlocked))
        assertEquals("nope", loginErrorCopy(LoginResult.BadPassword("nope")))
        assertEquals("Too many attempts. Try again in a few minutes.", loginErrorCopy(LoginResult.RateLimited("")))
        assertEquals("Off. Pair this device with a code instead.", loginErrorCopy(LoginResult.PasswordDisabled("Off.")))
        // ta-s4r: the username hint rides only on Tether's refusal, and only when it was asked for.
        assertEquals("nope $USERNAME_MISSING_HINT", loginErrorCopy(LoginResult.BadPassword("nope"), usernameHint = true))
        assertEquals(
            "The server refused the sign-in before Tether checked the password (HTTP 401, from nginx/1.27.1, " +
                "asking for Basic authentication). A sign-in gateway (SSO or a proxy) guards the password login, " +
                "so the app cannot use it here. Pair this device with a code from the browser instead.",
            loginErrorCopy(LoginResult.GatewayRefused(401, "Basic", "nginx/1.27.1"), usernameHint = true),
        )
        assertTrue(
            loginErrorCopy(LoginResult.GatewayRefused(403, null))!!
                .startsWith("The server refused the sign-in before Tether checked the password (HTTP 403). "),
        )
        assertNull(pairErrorCopy(PairResult.Success))
        assertEquals("That pairing code is not valid or has expired.", pairErrorCopy(PairResult.Rejected("")))
    }

    @Test
    fun signedOutCopyNamesTheCause() {
        assertTrue(signedOutCopy(SignedOutReason.SessionExpired).contains("expired"))
        assertTrue(signedOutCopy(SignedOutReason.DeviceUnpaired).contains("paired"))
        assertTrue(signedOutCopy(SignedOutReason.GatewayRefused).contains("/api/auth/session"))
    }

    @Test
    fun validationBeforeAnyRequest() {
        assertEquals("Enter the server URL.", validateAttempt(AuthMode.Password, " ", "", "pw", "", probe))
        assertEquals("Enter the pairing code from your browser.", validateAttempt(AuthMode.Pairing, "h", "", "", " ", probe))
        assertEquals("Enter your username.", validateAttempt(AuthMode.Password, "h", "", "pw", "", probe.copy(usernameRequired = true)))
        assertTrue(validateAttempt(AuthMode.Password, "h", "", "pw", "", probe.copy(passwordLoginEnabled = false))!!.contains("turned off"))
        assertEquals("Enter the password.", validateAttempt(AuthMode.Password, "h", "", "", "", probe))
        assertNull(validateAttempt(AuthMode.Password, "h", "", "pw", "", null))
        assertNull(validateAttempt(AuthMode.Pairing, "h", "", "", "ABCD1234", probe))
    }

    @Test
    fun theUsernameLineFollowsTheProbeAndStaysOfferedWhenTheProbeFailed() {
        // Still probing: hidden, like the web's form.
        assertEquals(false, usernameFieldShown(null, probeFailed = false))
        // Known: exactly what the console said.
        assertEquals(false, usernameFieldShown(probe, probeFailed = false))
        assertEquals(true, usernameFieldShown(probe.copy(usernameRequired = true), probeFailed = false))
        assertEquals(false, usernameFieldOptional(probe.copy(usernameRequired = true), probeFailed = false))
        // Unknown after a failed probe: offered, and optional.
        assertEquals(true, usernameFieldShown(null, probeFailed = true))
        assertEquals(true, usernameFieldOptional(null, probeFailed = true))
        // The refusal hint: no username sent, and the console not known to have none.
        assertEquals(true, usernameHintFor(null, " "))
        assertEquals(false, usernameHintFor(probe, ""))
        assertEquals(false, usernameHintFor(null, "operator"))
    }

    /** ta-3uk: a v136 server's native server_too_old reply to the 137 hello reads as "update the server". */
    @Test
    fun serverTooOldMismatchReadsAsUpdateTheServer() {
        val frame = ServerMessage.VersionMismatch(
            136,
            "This Tether server is older than the app — update the server to reconnect.",
            nativeProtocolFloor = 129,
            serverProtocolVersion = 136,
            reason = "server_too_old",
        )
        val incompatibility = Compatibility.fromMismatch(frame)
        assertEquals(IncompatibleReason.ServerTooOld, incompatibility.reason)
        assertEquals("This server is older than the app. Update the server, then connect.", versionCopy(incompatibility))
        // The /healthz pre-flight against the same server reads the same.
        assertEquals(versionCopy(incompatibility), versionCopy(Compatibility.evaluate(136, 129)!!))
    }

    /** T10.5: use-login-flow.ts `passkeyReady` and the passkey outcomes' words. */
    @Test
    fun aPasskeyIsReadyOnlyWhenRegisteredUsableAndRunnable() {
        val ready = probe.copy(passkeyCount = 2, passkeysUsable = true)
        assertTrue(passkeyReady(ready, available = true))
        assertEquals(false, passkeyReady(ready, available = false))
        assertEquals(false, passkeyReady(ready.copy(passkeyCount = 0), available = true))
        assertEquals(false, passkeyReady(ready.copy(passkeysUsable = false), available = true))
        assertEquals("unknown requirements offer none, like the web", false, passkeyReady(null, available = true))
        // A dismissed prompt is a notice, not an error; a refusal is the server's own sentence.
        assertNull(loginErrorCopy(LoginResult.PasskeyDismissed))
        assertEquals("That passkey could not be verified.", loginErrorCopy(LoginResult.PasskeyFailed("That passkey could not be verified.")))
        assertEquals("Passkey sign-in failed.", loginErrorCopy(LoginResult.PasskeyFailed(" ")))
        assertEquals("Passkey prompt dismissed.", PASSKEY_DISMISSED_NOTICE)
    }

    /** r2 (security F4): server text on the login screen goes through the Devices panel's cleanup, bounded. */
    @Test
    fun serverTextIsCleanedAndBounded() {
        val hostile = "That\u200B passkey\u202E could not\u2066 be verified."
        assertEquals("That passkey could not be verified.", loginErrorCopy(LoginResult.PasskeyFailed(hostile)))
        assertEquals("Those credentials are not correct.", loginErrorCopy(LoginResult.BadPassword("Those\u200B credentials are not\u200F correct.")))
        assertEquals("Passkey sign-in failed.", loginErrorCopy(LoginResult.PasskeyFailed("\u200B\u202E")))
        val long = loginErrorCopy(LoginResult.Unreachable("x".repeat(5_000)))!!
        assertTrue("bounded: ${long.length}", long.length <= com.tether.app.client.LabelText.MAX_ERROR + 1)
        assertEquals("That pairing code is not valid.", pairErrorCopy(PairResult.Rejected("That\u200B pairing code is not valid.")))
    }
}
