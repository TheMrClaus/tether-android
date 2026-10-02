package com.tether.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.tether.app.client.EngineCard
import com.tether.app.client.EnvChange
import com.tether.app.client.RiskyEnvKeys
import com.tether.app.client.RunsSnapshot
import com.tether.app.client.SecretText
import com.tether.app.client.ServerSetting
import com.tether.app.ui.text.SafeText
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
 * ta-q9l (ta-dh1 r2 security review): a confirmation's key is armed for what it SHOWS. A value
 * replaced while the dialog is open (the edit, or its "Now") disarms the key and the whole
 * [CONFIRM_ARM_MS] window runs again, for every confirmation that shares [ArmedConfirmKey]. An equal
 * value recomposed keeps the key armed (no spurious re-arm), and a key given no shown value
 * (Devices) arms once, as before. Each dialog is composed on its own, the clock driven by hand; a
 * tap is the key's semantics action, counted synchronously.
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

    private fun textOf(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    /**
     * Armed (a tap counts: the positive control), then [change] while open: a tap right after, and
     * one just short of the window, count nothing; once the window has run again, a tap counts.
     */
    private fun assertReArms(tag: String, change: () -> Unit) {
        frame(CONFIRM_ARM_MS + 50)
        tap(tag)
        assertEquals("armed before the change", 1, confirmed)
        replace(change)
        tap(tag)
        assertEquals("a tap right after the value changed", 1, confirmed)
        frame(CONFIRM_ARM_MS - 150)
        tap(tag)
        assertEquals("a tap inside the window again", 1, confirmed)
        frame(200)
        tap(tag)
        assertEquals("re-armed", 2, confirmed)
    }

    private val codexCommand = EngineEdit(EngineCard.Codex, ServerSetting.CodexCommand, "/opt/codex", trimmed = false)

    // ---- engines ---------------------------------------------------------------------------------

    @Test fun anEngineConfirmationReArmsWhenTheEditIsReplaced() {
        var edit by mutableStateOf(codexCommand)
        host { EngineConfirmDialog(edit, now = "codex", onConfirm = count, onCancel = {}) }
        assertReArms(EngineTags.Confirm) { edit = codexCommand.copy(value = "/tmp/evil") }
        assertEquals("/tmp/evil", SafeText.original(textOf(EngineTags.ConfirmNew)))
    }

    @Test fun anEngineConfirmationReArmsWhenNowChanges() {
        var now by mutableStateOf("codex")
        host { EngineConfirmDialog(codexCommand, now = now, onConfirm = count, onCancel = {}) }
        assertReArms(EngineTags.Confirm) { now = "/opt/other" }
        assertEquals("/opt/other", SafeText.original(textOf(EngineTags.ConfirmNow)))
    }

    /** No spurious re-arm: an equal edit (a new but equal object) and an unrelated recomposition keep the key armed. */
    @Test fun anEqualValueRecomposedKeepsTheKeyArmed() {
        var edit by mutableStateOf(codexCommand)
        var unrelated by mutableStateOf(0)
        host {
            check(unrelated >= 0) // read: a change recomposes this scope
            EngineConfirmDialog(edit, now = "codex", onConfirm = count, onCancel = {})
        }
        frame(CONFIRM_ARM_MS + 50)
        replace { edit = codexCommand.copy() }
        replace { unrelated++ }
        tap(EngineTags.Confirm)
        assertEquals(1, confirmed)
    }

    // ---- the Claude CLI switch -------------------------------------------------------------------

    @Test fun theCliConfirmationReArmsWhenTheChoiceIsReplaced() {
        var next by mutableStateOf("2.1.220")
        host { ClaudeCliConfirmDialog(current = "Auto — newest installed", next = next, onConfirm = count, onCancel = {}) }
        assertReArms(ServerSettingsTags.CliConfirm) { next = "Bundled (SDK)" }
    }

    @Test fun theCliConfirmationReArmsWhenNowChanges() {
        var current by mutableStateOf("Auto — newest installed")
        host { ClaudeCliConfirmDialog(current = current, next = "2.1.220", onConfirm = count, onCancel = {}) }
        assertReArms(ServerSettingsTags.CliConfirm) { current = "2.1.225" }
    }

    // ---- custom providers ------------------------------------------------------------------------

    private val gemini = ProfileFixtures.list(ProfileFixtures.profiles(ProfileFixtures.gemini(extraEnv = ""","PATH":"/usr/bin""""))).profile("gemini")!!
    private val snapshot = RunsSnapshot.of(gemini)

    @Test fun aProfileCommandConfirmationReArmsWhenTheReviewIsReplaced() {
        val first = ProfileRunsReview("gemini", "Gemini CLI", "acp", home = false, parts = listOf("gemini", "--sandbox"), now = listOf("gemini"), normalized = false, snapshot = snapshot)
        var review by mutableStateOf(first)
        host { ProfileConfirmDialog(review, onConfirm = count, onCancel = {}) }
        assertReArms(ProfileTags.Confirm) { review = first.copy(parts = listOf("/tmp/evil")) }
    }

    @Test fun anEngineChangeConfirmationReArmsWhenTheReviewIsReplaced() {
        val first = ExtendsReview("gemini", "Gemini CLI", "acp", "claude", gemini.command.orEmpty(), gemini.homeDir, snapshot, riskyKeys = gemini.envKeys.filter(RiskyEnvKeys::risky))
        var review by mutableStateOf(first)
        host { ExtendsConfirmDialog(review, onConfirm = count, onCancel = {}) }
        assertReArms(ProfileTags.Confirm) { review = first.copy(to = "codex") }
    }

    @Test fun anEnvConfirmationReArmsWhenTheReviewIsReplaced() {
        val first = EnvReview("gemini", "Gemini CLI", EnvChange.Change("PATH", SecretText("FAKE-/opt/demo/bin")), gemini.envValue("PATH"), snapshot)
        var review by mutableStateOf(first)
        host { EnvConfirmDialog(review, onConfirm = count, onCancel = {}) }
        assertReArms(ProfileTags.Confirm) { review = first.copy(change = EnvChange.Change("PATH", SecretText("FAKE-/tmp/evil"))) }
    }

    // ---- Settings → Devices (ta-ban) ---------------------------------------------------------------

    @Test fun aRevokeConfirmationReArmsWhenTheDeviceIsReplaced() {
        var confirm by mutableStateOf<DevicesConfirm>(DevicesConfirm.Revoke(DevicesFixtures.PHONE, SelfMatch.No))
        host { DevicesConfirmDialog(confirm, onCancel = {}, onConfirm = count) }
        assertReArms(DevicesTags.ConfirmGo) { confirm = DevicesConfirm.Revoke(DevicesFixtures.TABLET, SelfMatch.No) }
        compose.onNodeWithTag(DevicesTags.ConfirmSheet, useUnmergedTree = true).assertExists()
    }

    @Test fun aRevokeConfirmationReArmsWhenWhetherItIsThisPhoneChanges() {
        var confirm by mutableStateOf<DevicesConfirm>(DevicesConfirm.Revoke(DevicesFixtures.PHONE, SelfMatch.No))
        host { DevicesConfirmDialog(confirm, onCancel = {}, onConfirm = count) }
        assertReArms(DevicesTags.ConfirmGo) { confirm = DevicesConfirm.Revoke(DevicesFixtures.PHONE, SelfMatch.Yes) }
    }

    @Test fun aPasskeyConfirmationReArmsWhenThePasskeyIsReplaced() {
        var confirm by mutableStateOf<DevicesConfirm>(DevicesConfirm.RemovePasskey(DevicesFixtures.LAPTOP_KEY))
        host { DevicesConfirmDialog(confirm, onCancel = {}, onConfirm = count) }
        assertReArms(DevicesTags.ConfirmGo) { confirm = DevicesConfirm.RemovePasskey(DevicesFixtures.YUBIKEY) }
    }

    @Test fun aConfirmationReplacedByAnotherKindReArms() {
        var confirm by mutableStateOf<DevicesConfirm>(DevicesConfirm.RemovePasskey(DevicesFixtures.LAPTOP_KEY))
        host { DevicesConfirmDialog(confirm, onCancel = {}, onConfirm = count) }
        assertReArms(DevicesTags.ConfirmGo) { confirm = DevicesConfirm.SignOutOthers }
    }

    /** No spurious re-arm in Devices either: an equal confirmation (a new but equal object) keeps the key armed. */
    @Test fun anEqualDevicesConfirmationKeepsTheKeyArmed() {
        var confirm by mutableStateOf<DevicesConfirm>(DevicesConfirm.Revoke(DevicesFixtures.PHONE, SelfMatch.No))
        host { DevicesConfirmDialog(confirm, onCancel = {}, onConfirm = count) }
        frame(CONFIRM_ARM_MS + 50)
        replace { confirm = DevicesConfirm.Revoke(DevicesFixtures.PHONE.copy(), SelfMatch.No) }
        tap(DevicesTags.ConfirmGo)
        assertEquals(1, confirmed)
    }

    // ---- the default (no shown value) -------------------------------------------------------------

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
}
