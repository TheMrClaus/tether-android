package com.tether.app.ui.settings

import com.tether.app.client.AppSignIn
import com.tether.app.client.PasskeyPolicySource
import com.tether.app.client.PasswordPolicy
import com.tether.app.client.SecurityResult
import com.tether.app.ui.settings.DevicesFixtures.NOW
import com.tether.app.ui.settings.DevicesFixtures.PHONE
import com.tether.app.ui.settings.DevicesFixtures.TABLET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T10.4: the Devices panel's pure decisions. */
class DevicesModelTest {
    @Test fun whichDeviceIsThisPhone() {
        // A cookie sign-in holds no device token in force: no device is this phone.
        assertEquals(SelfMatch.No, DevicesRules.selfMatch(AppSignIn.SessionCookie, listOf(PHONE), PHONE))
        // A device token's own device is in the list: alone, it is this phone.
        assertEquals(SelfMatch.Yes, DevicesRules.selfMatch(AppSignIn.DeviceToken, listOf(PHONE), PHONE))
        // Among several, it may be any of them (no route says which).
        assertEquals(SelfMatch.Maybe, DevicesRules.selfMatch(AppSignIn.DeviceToken, listOf(PHONE, TABLET), PHONE))
        assertEquals(SelfMatch.Maybe, DevicesRules.selfMatch(null, listOf(PHONE), PHONE))
    }

    /** tether #240 (unmerged): a device-token sign-in's own entry carries `current`; it then decides, exactly. */
    @Test fun theCurrentFlagDecidesWhenTheServerSendsIt() {
        val mine = PHONE.copy(current = true)
        val list = listOf(mine, TABLET)
        assertEquals(SelfMatch.Yes, DevicesRules.selfMatch(AppSignIn.DeviceToken, list, mine))
        assertEquals("marked elsewhere: not this phone", SelfMatch.No, DevicesRules.selfMatch(AppSignIn.DeviceToken, list, TABLET))
        // Alone and marked, or alone on an older server: this phone either way.
        assertEquals(SelfMatch.Yes, DevicesRules.selfMatch(AppSignIn.DeviceToken, listOf(mine), mine))
        // A cookie sign-in is never a device, flag or not.
        assertEquals(SelfMatch.No, DevicesRules.selfMatch(AppSignIn.SessionCookie, list, mine))
    }

    /** paired-devices.tsx 90fbb9f :201-206: the web's words, the same for this phone as for any device (ta-coik.5). */
    @Test fun theRevokeConfirmationSaysWhatTheWebSays() {
        val web = listOf("Pixel 8 loses access immediately and its live connection is closed. It has to be paired again with a new code.")
        assertEquals(web, DevicesRules.revokeBody("Pixel 8"))
        assertEquals(listOf(DevicesCopy.revokeBody("Pixel 8")), DevicesRules.revokeBody("Pixel 8"))
    }

    @Test fun theWebsCountsAndClock() {
        // paired-devices.tsx: unclaimed and live, the shown code matched out by its expiry.
        val pairings = listOf(
            com.tether.app.client.OutstandingPairing("", NOW - 1, NOW + 1_000),
            com.tether.app.client.OutstandingPairing("", NOW - 1, NOW + 2_000),
            com.tether.app.client.OutstandingPairing("", NOW - 1, NOW - 5),
        )
        assertEquals(2, DevicesRules.otherPairings(pairings, NOW, null))
        assertEquals(1, DevicesRules.otherPairings(pairings, NOW, NOW + 2_000))
        assertEquals("Creates a one-time code, good for five minutes and one device.", DevicesCopy.pairHint(0))
        assertEquals("Creates a one-time code. 2 earlier codes are still unclaimed and cannot be shown again.", DevicesCopy.pairHint(2))
        assertEquals(299, DevicesRules.secondsLeft(NOW + 299_000, NOW))
        assertEquals(1, DevicesRules.secondsLeft(NOW + 500, NOW))
        assertEquals(0, DevicesRules.secondsLeft(NOW - 9_000, NOW))
        assertEquals("Device revoked. 1 live connection closed.", DevicesCopy.revoked(1))
        assertEquals("Device revoked.", DevicesCopy.revoked(0))
        assertEquals("Signed out 3 other sessions.", DevicesCopy.othersSignedOut(3))
        assertEquals("No other sessions to sign out.", DevicesCopy.othersSignedOut(0))
    }

    /** sign-in-security.tsx 90fbb9f :89-95: `toggleDisabled = env || zeroPasskeys`, and its note. */
    @Test fun thePasswordSwitchFollowsTheWeb() {
        val env = DevicesFixtures.PASSKEYS.copy(policy = PasswordPolicy(true, PasskeyPolicySource.Env))
        assertEquals(DevicesCopy.PASSWORD_ENV, DevicesRules.passwordNote(env))
        assertFalse(DevicesRules.passwordToggleable(env))
        assertEquals(DevicesCopy.PASSWORD_NEEDS_PASSKEY, DevicesRules.passwordNote(DevicesFixtures.NO_PASSKEYS))
        assertFalse(DevicesRules.passwordToggleable(DevicesFixtures.NO_PASSKEYS))
        assertEquals(DevicesCopy.PASSWORD_ON, DevicesRules.passwordNote(DevicesFixtures.PASSKEYS))
        assertTrue(DevicesRules.passwordToggleable(DevicesFixtures.PASSKEYS))
        val off = DevicesFixtures.PASSKEYS.copy(policy = PasswordPolicy(false, PasskeyPolicySource.Stored))
        assertTrue(DevicesRules.passwordToggleable(off))
        assertEquals(DevicesCopy.PASSWORD_ON, DevicesRules.passwordNote(off))
    }

    @Test fun serverTextIsDrawnByTheLabelRule() {
        assertEquals("Pixel 8", DevicesRules.label("Pixel‮ 8​", "Paired device"))
        // ta-28i: a label of only hidden characters is spelled out, never drawn as nothing.
        assertEquals(com.tether.app.client.LabelText.visibleValue("​‮"), DevicesRules.label("​‮", "Paired device"))
        assertEquals("Paired device", DevicesRules.label("", "Paired device"))
        assertEquals(DevicesCopy.UNKNOWN_DEVICE, DevicesRules.userAgent(""))
        val ua = DevicesRules.userAgent("x".repeat(200))
        assertEquals(62, ua.length)
        assertTrue(ua.endsWith("…"))
        assertEquals("Mozilla Firefox", DevicesRules.userAgent("Mozilla\n‮Firefox"))
    }

    @Test fun failuresAreWordedAsTheWebOrTheAppSays() {
        val o = DevicesFixtures.ORIGIN
        assertEquals("That device is gone.", DevicesRules.failure(SecurityResult.Refused(404, "That device‮ is gone.\n", o), "fallback"))
        assertEquals("fallback", DevicesRules.failure(SecurityResult.Refused(409, "​", o), "fallback"))
        assertEquals("fallback", DevicesRules.failure(SecurityResult.Unavailable(502, o), "fallback"))
        assertEquals(DevicesCopy.blocked(302), DevicesRules.failure(SecurityResult.Blocked(302, o), "fallback"))
        assertEquals(DevicesCopy.NOT_SENT, DevicesRules.failure(SecurityResult.NotSent(o), "fallback"))
        assertNull("a panel state, not a line", DevicesRules.failure(SecurityResult.OwnerSignInNeeded(o), "fallback"))
        assertNull(DevicesRules.failure(SecurityResult.SignedOut(o), "fallback"))
    }

    @Test fun theShownCodeNeverPrintsItsCode() {
        val shown = ShownCode(DevicesFixtures.code(), NOW, 1)
        assertFalse(shown.toString().contains(DevicesFixtures.SENTINEL))
        assertFalse(DevicesSeed(code = DevicesFixtures.code()).toString().contains(DevicesFixtures.SENTINEL))
    }
}
