package com.tether.app.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.tether.app.push.PushScope
import com.tether.app.ui.prefs.LoginVariant
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.ThemeMode
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A preferences store whose disk refuses every write (the edit itself still runs). */
private class RefusingPrefsStore : DataStore<Preferences> {
    private val disk = MutableStateFlow(emptyPreferences())
    @Volatile var attempts = 0
    override val data: Flow<Preferences> = disk
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        attempts++
        transform(disk.value)
        throw IOException("No space left on device")
    }
}

/**
 * ta-8yn9: Settings' apply-at-once writes (Appearance, the Notifications row) on a disk that
 * refuses them: each pick still applies and is drawn (in memory, like the web's best-effort
 * localStorage save), no error is shown (Save shows none for the same failure), and nothing crashes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class RefusedPreferenceWriteTest {
    @get:Rule val compose = createComposeRule()

    private val disk = RefusingPrefsStore()
    private val prefs = UiPrefs.on(disk)
    private val state = SettingsDialogState()

    private fun read() = runBlocking(Dispatchers.IO) { prefs.preferences.first().forServer(null) }

    private fun show(tab: SettingsTab) {
        grantNotificationPermission()
        compose.setContent { SettingsUnderTest(prefs, state) }
        compose.waitUntil(5_000) { state.draft != null }
        compose.onNodeWithTag(SettingsDialogTags.tab(tab)).performClick()
        compose.waitForIdle()
    }

    private fun waitSelected(t: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(t).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.Selected) == true }

    @Test fun appearancePicksApplyWhenTheDiskRefusesThem() {
        show(SettingsTab.Appearance)
        compose.onNodeWithTag(SettingsPanelTags.themeMode(ThemeMode.Dark)).performClick()
        waitSelected(SettingsPanelTags.themeMode(ThemeMode.Dark))
        compose.onNodeWithTag(SettingsPanelTags.loginVariant("retro")).performClick()
        waitSelected(SettingsPanelTags.loginVariant("retro"))
        assertEquals(ThemeMode.Dark to LoginVariant.Retro, read().let { it.themeMode to it.loginVariant })
        assertTrue("both picks were written, and refused", disk.attempts >= 2)
    }

    @Test fun theNotificationsRowAppliesWhenTheDiskRefusesIt() {
        show(SettingsTab.Devices)
        compose.onNodeWithText("Pinned sessions").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking(Dispatchers.IO) { prefs.pushScope.first() } == PushScope.Pinned }
        // T12.2: the web's Disable (notifications allowed, the registration in place).
        compose.onNodeWithText("Disable").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Enable").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(runBlocking(Dispatchers.IO) { prefs.pushEnabled.first() })
        assertTrue("both were written, and refused", disk.attempts >= 2)
    }
}
