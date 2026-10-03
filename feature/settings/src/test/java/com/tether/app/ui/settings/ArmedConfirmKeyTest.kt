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
 * [ArmedConfirmKey] (ta-q9l): still the new-session composer's setup confirmation key (feature/shell
 * WorktreeUi.kt, until ta-coik.11), armed for what it SHOWS: a value replaced while it is open
 * disarms it and the [CONFIRM_ARM_MS] window runs again; an equal value recomposed keeps it armed.
 *
 * ta-coik.5: Settings uses it no more. The web's three Devices confirmations (paired-devices.tsx
 * 90fbb9f :192-212, sign-in-security.tsx) act on the FIRST tap of their danger key, whatever was
 * shown a moment before: no app-only arm delay. Each dialog is composed on its own, the clock driven
 * by hand; a tap is the key's semantics action, counted synchronously.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ArmedConfirmKeyTest {
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

    // ---- ArmedConfirmKey itself (its one remaining caller is feature/shell's) ---------------------

    @Test fun aKeyReArmsWhenWhatItShowsIsReplaced() {
        var shown by mutableStateOf("/opt/codex")
        host { ArmedConfirmKey("Create session", "armed-key", count, shown = shown) }
        frame(CONFIRM_ARM_MS + 50)
        tap("armed-key")
        assertEquals("armed before the change", 1, confirmed)
        replace { shown = "/tmp/evil" }
        tap("armed-key")
        assertEquals("a tap right after the value changed", 1, confirmed)
        frame(CONFIRM_ARM_MS - 150)
        tap("armed-key")
        assertEquals("a tap inside the window again", 1, confirmed)
        frame(200)
        tap("armed-key")
        assertEquals("re-armed", 2, confirmed)
    }

    @Test fun anEqualValueRecomposedKeepsTheKeyArmed() {
        var shown by mutableStateOf(listOf("a"))
        host { ArmedConfirmKey("Create session", "armed-key", count, shown = shown) }
        frame(CONFIRM_ARM_MS + 50)
        replace { shown = listOf("a") }
        tap("armed-key")
        assertEquals(1, confirmed)
    }

    @Test fun aKeyWithoutAShownValueArmsOnceAsBefore() {
        var label by mutableStateOf("Revoke")
        host { ArmedConfirmKey(label, "armed-key", count) }
        tap("armed-key")
        assertEquals("not armed at once", 0, confirmed)
        frame(CONFIRM_ARM_MS + 50)
        replace { label = "Remove" }
        tap("armed-key")
        assertEquals("keyed on nothing: stays armed", 1, confirmed)
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
