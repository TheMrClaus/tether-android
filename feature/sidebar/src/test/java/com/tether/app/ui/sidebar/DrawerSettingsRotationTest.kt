package com.tether.app.ui.sidebar

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

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
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag(tag).assertIsOn().assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(tag).assertIsOff()

        restorer.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithTag(SettingsDialogTags.Dialog).assertExists()
        rule.onNodeWithTag(tag).assertIsOff()
    }
}
