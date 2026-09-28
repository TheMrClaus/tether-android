package com.tether.app.ui

import com.tether.app.client.LoginResult
import com.tether.app.client.PairResult
import com.tether.app.client.SignInRequirements
import com.tether.app.client.SignedOutReason
import com.tether.app.ui.prefs.LoginVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginCopyTest {
    private val probe = SignInRequirements(usernameRequired = false, passwordLoginEnabled = true, passkeyCount = 0, passkeysUsable = false)

    @Test
    fun surfaceSelectionMatchesAppLoginPage() {
        assertEquals(LoginSurface.Instrument, loginSurfaceFor(LoginVariant.Instrument, studioFamily = false))
        assertEquals(LoginSurface.Studio, loginSurfaceFor(LoginVariant.Instrument, studioFamily = true))
        // Retro is the opt-in and ignores the theme family.
        assertEquals(LoginSurface.Retro, loginSurfaceFor(LoginVariant.Retro, studioFamily = false))
        assertEquals(LoginSurface.Retro, loginSurfaceFor(LoginVariant.Retro, studioFamily = true))
        assertEquals(LoginVariant.Instrument, LoginVariant.fromId(null))
        assertEquals(LoginVariant.Instrument, LoginVariant.fromId("studio"))
        assertEquals(LoginVariant.Retro, LoginVariant.fromId("retro"))
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
    fun instrumentStatusLabels() {
        assertEquals("probing", instrumentStatusLabel(LoginPhase.Ready, probing = true))
        assertEquals("locked", instrumentStatusLabel(LoginPhase.Ready, probing = false))
        assertEquals("verifying", instrumentStatusLabel(LoginPhase.Verifying, probing = false))
        assertEquals("unlocked", instrumentStatusLabel(LoginPhase.Success, probing = false))
        assertEquals("refused", instrumentStatusLabel(LoginPhase.Error, probing = true))
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
            "The server refused the sign-in (HTTP 401, not from Tether’s login; it asks for Basic authentication). " +
                "Something in front of Tether, such as a proxy or SSO gateway, wants its own sign-in. " +
                "Pair this device with a code instead.",
            loginErrorCopy(LoginResult.GatewayRefused(401, "Basic"), usernameHint = true),
        )
        assertTrue(loginErrorCopy(LoginResult.GatewayRefused(401, null))!!.startsWith("The server refused the sign-in (HTTP 401, not from Tether’s login). "))
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
        assertEquals("probing", instrumentStatusLabel(LoginPhase.Checking, probing = false))
    }
}
