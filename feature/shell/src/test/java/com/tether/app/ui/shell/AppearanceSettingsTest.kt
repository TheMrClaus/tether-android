package com.tether.app.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tether.app.ui.InterimSettingsDialog
import com.tether.app.ui.prefs.PreferenceKeys
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
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
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A Settings dialog on a preference store of its own, seeded with raw stored keys. */
abstract class AppearanceBase {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    private val job = Job()
    protected val store: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { File(tmp.root, "ui.preferences_pb") }
    }
    protected val prefs: UiPrefs by lazy { UiPrefs.on(store) }

    @After fun closeStore() = runBlocking { job.cancel() }

    protected fun seed(vararg raw: Pair<String, String>) = runBlocking {
        store.edit { p -> raw.forEach { (k, v) -> p[stringPreferencesKey(k)] = v } }
    }

    protected fun stored(): Map<String, Any> = runBlocking { store.data.first().asMap().mapKeys { it.key.name } }
}

/**
 * T15.5 (hooks/use-preferences.ts THEME_MODES): Settings → Appearance offers Studio's lighting
 * only — Light, Dark, Follow system — and no retired theme family. A 0.7.x-0.8.0 install's family
 * + mode reads as its mode; picking one stores the mode alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class AppearanceSettingsTest : AppearanceBase() {

    @Test fun thePickerOffersTheThreeModesAndNoFamily() {
        rule.setContent { TetherTheme { InterimSettingsDialog(prefs, onDismiss = {}) } }
        rule.onNodeWithText("APPEARANCE").assertExists()
        for (mode in listOf("Light", "Dark", "Follow system")) rule.onNodeWithText(mode).assertExists()
        for (retired in listOf("Tactile Console", "Precision Machine", "Studio", "THEME")) {
            assertEquals(retired, 0, rule.onAllNodesWithText(retired).fetchSemanticsNodes().size)
        }
        // A fresh install follows the system.
        rule.onNodeWithText("Follow system").assertIsSelected()
        rule.onNodeWithText("Light").assertIsNotSelected()
    }

    @Test fun anUpgradedFamilyAndModeShowsTheModeAndAPickStoresTheModeAlone() {
        seed(PreferenceKeys.THEME_FAMILY to "tactile", PreferenceKeys.THEME_MODE to "dark", PreferenceKeys.LEGACY_THEME to "night")
        rule.setContent { TetherTheme { InterimSettingsDialog(prefs, onDismiss = {}) } }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Dark").assertIsSelected() }.isSuccess }
        rule.onNodeWithText("Light").performClick()
        rule.waitUntil(5_000) { stored()[PreferenceKeys.THEME_MODE] == "light" }
        val raw = stored()
        assertEquals(null, raw[PreferenceKeys.THEME_FAMILY])
        assertEquals(null, raw[PreferenceKeys.LEGACY_THEME])
        rule.onNodeWithText("Light").assertIsSelected()
    }
}

/** The Appearance section in both Studio skins, at the web's phone viewport. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class AppearanceSettingsScreenshotTest(private val skin: TetherSkin) : AppearanceBase() {

    @Test fun settingsAppearance() {
        seed(PreferenceKeys.THEME_MODE to skin.mode.id)
        rule.setContent { TetherTheme(skin.mode) { InterimSettingsDialog(prefs, onDismiss = {}) } }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText(skin.mode.label).assertIsSelected() }.isSuccess }
        captureScreenRoboImage(
            "src/test/screenshots/settings-appearance/${skin.id}-phone.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}
