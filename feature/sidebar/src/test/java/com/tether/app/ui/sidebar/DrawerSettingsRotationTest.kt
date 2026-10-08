package com.tether.app.ui.sidebar

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tether.app.ui.SessionDrawer
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.settings.GeneralToggle
import com.tether.app.ui.settings.SettingsDialogTags
import com.tether.app.ui.settings.SettingsPanelTags
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T10.1 r2 (verifier F1): the drawer's own Settings (no host supplies one) survives a recreation,
 * open and with its unsaved draft.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DrawerSettingsRotationTest {
    // ta-9dpl: the v2 rule (StandardTestDispatcher), as for every test reading IO-fed state (ta-b72):
    // under v1 the draft seed's DataStore read resumed the effect on the IO worker, which wrote the
    // draft off the main thread, and the recomposer could miss it for good (General stayed disabled).
    // ta-9dpl: the folder is the outer rule, deleted only once the composition is gone: a preference
    // write still on the disk at the end can no longer fail (and fail the test) under a live screen.
    val tmp = TemporaryFolder()
    val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(rule)

    private val storeJob = Job()

    @After fun closeStore() = storeJob.cancel()

    @Test fun theDrawersSettingsAndItsDraftSurviveARecreation() {
        val client = RecordingClient()
        val vm = TetherViewModel(client)
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob)) { File(tmp.root, "ui.preferences_pb") }
        val prefs = UiPrefs.on(store)
        val restorer = StateRestorationTester(rule)
        restorer.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                SessionDrawer(vm = vm, prefs = prefs, sessions = emptyList(), selectedId = null, workspaceRoot = SidebarFixtures.ROOT, onSelect = {}, onClose = {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Open settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SettingsDialogTags.Dialog).assertExists()
        val tag = SettingsPanelTags.toggle(GeneralToggle.ConfirmBeforeEnd)
        // ta-9j0x: the toggle reads On (the default) before the preferences load, but is enabled only
        // once the draft is seeded from them; a tap before that is dropped.
        rule.waitUntil(20_000) { runCatching { rule.onNodeWithTag(tag).assertIsOn().assertIsEnabled() }.isSuccess }
        // ta-v8dt: the card is 24 dp narrower now, so the row's centre falls inside the "Confirm before ending" tip glyph's
        // 48 dp touch target (the tap opened the tip bubble, which is why the row grew and stayed On). Tap the switch
        // track at the row's right end, which is what a reader taps.
        rule.onNodeWithTag(tag).performScrollTo().performTouchInput { click(Offset(width - 40.dp.toPx(), centerY)) }
        rule.waitForIdle()
        rule.onNodeWithTag(tag).assertIsOff()

        restorer.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithTag(SettingsDialogTags.Dialog).assertExists()
        rule.onNodeWithTag(tag).assertIsOff()
    }
}
