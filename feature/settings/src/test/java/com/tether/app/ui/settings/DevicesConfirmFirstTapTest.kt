package com.tether.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.5: the web's three Devices confirmations (paired-devices.tsx 90fbb9f :192-212,
 * sign-in-security.tsx) act on the FIRST tap of their danger key, whatever was shown a moment
 * before: no app-only arm delay. ta-coik.13 deleted the old ArmedConfirmKey (its last caller went in
 * ta-coik.11). Each dialog is composed on its own, the clock driven by hand; a tap is the key's
 * semantics action, counted synchronously.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DevicesConfirmFirstTapTest {
    @get:Rule val compose = createComposeRule()

    private var confirmed = 0
    private val count: () -> Unit = { confirmed++ }

    private fun host(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TetherTheme(TetherSkin.entries.first().mode) {
                CompositionLocalProvider(LocalReducedMotion provides true) { content() }
            }
        }
        frame()
    }

    private fun frame(ms: Long = 32) = compose.mainClock.advanceTimeBy(ms)

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        frame()
    }

    /** A state write made outside an input event, handed to the recomposer and drawn. */
    private fun replace(block: () -> Unit) {
        compose.runOnUiThread {
            block()
            Snapshot.sendApplyNotifications()
        }
        frame()
    }

    // ---- Settings → Devices: the web's confirmations, no arm delay (ta-coik.5) ---------------------

    /** The positive control is the count itself: the first tap, at the first frame, confirms. */
    private fun assertFirstTapConfirms(confirm: DevicesConfirm) {
        host { DevicesConfirmDialog(confirm, onCancel = {}, onConfirm = count) }
        tap(DevicesTags.ConfirmGo)
        assertEquals("the first tap confirms, as on the web", 1, confirmed)
    }

    @Test fun aRevokeConfirmationActsOnTheFirstTap() = assertFirstTapConfirms(DevicesConfirm.Revoke(DevicesFixtures.TABLET, SelfMatch.No))

    @Test fun revokingThisPhoneActsOnTheFirstTap() = assertFirstTapConfirms(DevicesConfirm.Revoke(DevicesFixtures.PHONE, SelfMatch.Yes))

    @Test fun aPasskeyConfirmationActsOnTheFirstTap() = assertFirstTapConfirms(DevicesConfirm.RemovePasskey(DevicesFixtures.LAPTOP_KEY))

    @Test fun signOutOthersActsOnTheFirstTap() = assertFirstTapConfirms(DevicesConfirm.SignOutOthers)

    /** A confirmation replaced while open (another device) is confirmed by the next tap, at once. */
    @Test fun aReplacedConfirmationActsOnTheNextTap() {
        var confirm by mutableStateOf<DevicesConfirm>(DevicesConfirm.Revoke(DevicesFixtures.PHONE, SelfMatch.No))
        host { DevicesConfirmDialog(confirm, onCancel = {}, onConfirm = count) }
        replace { confirm = DevicesConfirm.Revoke(DevicesFixtures.TABLET, SelfMatch.No) }
        tap(DevicesTags.ConfirmGo)
        assertEquals(1, confirmed)
        compose.onNodeWithTag(DevicesTags.ConfirmSheet, useUnmergedTree = true).assertExists()
    }
}
