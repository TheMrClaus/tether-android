package com.tether.app.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.tether.app.ui.prefs.PreferenceKeys
import com.tether.app.ui.prefs.ServerPreferences
import com.tether.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.52: Settings reads and writes the preferences of the server it is open for (the web's
 * `tether.preferences.v1` is per origin): General's Save and Appearance's picks land in that server's
 * record, and another server's stays as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SettingsPerServerTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val a = "https://a.example:443"
    private val b = "https://b.example:443"

    private fun waitSelected(t: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(t).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.Selected) == true }

    @Test fun generalAndAppearanceAreTheServersOwn() {
        runBlocking {
            store.prefs.updatePreferencesFor(a) { it.copy(themeMode = ThemeMode.Light, defaultWorkspace = "/srv/a") }
            store.prefs.updatePreferencesFor(b) { it.copy(themeMode = ThemeMode.Light, showThinking = true, defaultWorkspace = "/srv/b") }
        }
        val state = SettingsDialogState()
        var closes = 0
        compose.setContent { SettingsUnderTest(store.prefs, state, onClose = { closes++ }, serverUrl = MutableStateFlow("https://A.example/")) }
        compose.waitUntil(5_000) { state.draft != null }
        assertEquals("the draft is A's", GeneralDraft("/srv/a", showEndedSessions = true, confirmBeforeEnd = true, showThinking = false), state.draft)
        compose.waitUntil(5_000) { compose.isDrawnEnabled(SettingsDialogTags.Save) }
        compose.onNodeWithTag(SettingsDialogTags.tab(SettingsTab.Appearance)).performClick()
        compose.waitForIdle()
        waitSelected(SettingsPanelTags.themeMode(ThemeMode.Light))
        compose.onNodeWithTag(SettingsPanelTags.themeMode(ThemeMode.Dark)).performClick()
        compose.waitUntil(5_000) { store.storedFor(a)[PreferenceKeys.THEME_MODE] == "dark" }
        compose.onNodeWithTag(SettingsDialogTags.tab(SettingsTab.General)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsPanelTags.toggle(GeneralToggle.ShowThinking)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { state.draft?.showThinking == true }
        compose.onNodeWithTag(SettingsDialogTags.Save).performClick()
        compose.waitUntil(5_000) { closes == 1 }
        compose.waitUntil(5_000) { store.storedFor(a)[PreferenceKeys.SHOW_THINKING] == true }
        val stored = runBlocking { store.prefs.preferences.first() }
        assertEquals(ServerPreferences(themeMode = ThemeMode.Dark, showThinking = true, defaultWorkspace = "/srv/a"), stored.preferencesByOrigin[a])
        assertEquals("B's record is as it was", ServerPreferences(themeMode = ThemeMode.Light, showThinking = true, defaultWorkspace = "/srv/b"), stored.preferencesByOrigin[b])
        assertEquals("nothing device-wide", ServerPreferences.Default, ServerPreferences.of(stored))
        assertEquals("nor for no server", null, stored.preferencesByOrigin[""])
    }
}
