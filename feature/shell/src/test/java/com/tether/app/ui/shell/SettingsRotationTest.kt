package com.tether.app.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.PreferenceKeys
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.settings.GeneralToggle
import com.tether.app.ui.settings.SettingsDialogTags
import com.tether.app.ui.settings.SettingsPanelTags
import com.tether.app.ui.settings.SettingsTab
import com.tether.app.ui.theme.TetherTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T10.1 r2 (verifier F1): a rotation recreates the activity (MainActivity keeps no configChanges);
 * an open Settings comes back open, on its tab, with its unsaved draft, and nothing is written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w600dp-h1000dp-mdpi")
class SettingsRotationTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val storeJob = Job()

    @After fun closeStore() = storeJob.cancel()

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(600, 1000)
    }

    @Test fun anOpenSettingsAndItsDraftSurviveARecreation() {
        val client = ShellConsentClient().also {
            it.show(AgentSession(id = "s1", provider = "claude", name = "x", cwd = "/w", status = "active", startedAt = 1, updatedAt = 1), freshTree())
        }
        val vm = TetherViewModel(client)
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob)) { File(tmp.root, "ui.preferences_pb") }
        val prefs = UiPrefs.on(store)
        val restorer = StateRestorationTester(rule)
        restorer.setContent { TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } } }
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("Settings")[0].performClick()
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
        rule.onNodeWithTag(SettingsDialogTags.tab(SettingsTab.General)).assertIsSelected()
        rule.onNodeWithTag(tag).assertIsOff()
        // Still a draft: the preference model was never saved (the shell's own last view aside).
        val stored = runBlocking { store.data.first().asMap().keys.map { it.name } }
        assertEquals(emptyList<String>(), stored.filter { it == PreferenceKeys.CONFIRM_BEFORE_END })
    }
}
