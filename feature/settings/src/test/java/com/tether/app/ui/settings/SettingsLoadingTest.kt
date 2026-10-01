package com.tether.app.ui.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.tether.app.ui.prefs.PreferenceKeys
import com.tether.app.ui.prefs.UiPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A store whose reads wait until [open] is set: the stored preferences "have not loaded yet". */
private class GatedStore(private val real: DataStore<Preferences>) : DataStore<Preferences> {
    val open = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val data: Flow<Preferences> = open.filter { it }.flatMapLatest { real.data }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = real.updateData(transform)
}

/**
 * T10.1 r2 (verifier F2): until the stored preferences have been read once, General can be neither
 * edited nor saved, so Save can never write the defaults over fields the operator did not touch.
 * Once the read lands, the draft starts from the stored values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SettingsLoadingTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    @Test fun nothingCanBeEditedOrSavedBeforeTheStoredPreferencesLoad() {
        // Stored: every switch the opposite of its default, and a kept folder.
        store.seed(
            PreferenceKeys.SHOW_ENDED_SESSIONS to false,
            PreferenceKeys.CONFIRM_BEFORE_END to false,
            PreferenceKeys.SHOW_THINKING to true,
            PreferenceKeys.DEFAULT_WORKSPACE to "/srv/kept",
        )
        val gated = GatedStore(store.store)
        val prefs = UiPrefs.on(gated)
        val state = SettingsDialogState()
        var closes = 0
        compose.setContent { SettingsUnderTest(prefs, state, onClose = { closes++ }) }
        compose.waitForIdle()

        val save = compose.onNodeWithTag(SettingsDialogTags.Save)
        val thinking = compose.onNodeWithTag(SettingsPanelTags.toggle(GeneralToggle.ShowThinking))
        save.assertIsNotEnabled()
        thinking.assertIsNotEnabled()
        compose.onNodeWithTag(SettingsPanelTags.UseCurrent).assertIsNotEnabled()
        thinking.performClick()
        save.performClick()
        compose.waitForIdle()
        assertEquals(null, state.draft)
        assertEquals(0, closes)

        // The read lands: the draft is the stored values, and General is live.
        gated.open.value = true
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitForIdle()
        assertEquals(GeneralDraft("/srv/kept", showEndedSessions = false, confirmBeforeEnd = false, showThinking = true), state.draft)
        save.assertIsEnabled()
        thinking.assertIsEnabled().assertIsOn()
        thinking.performClick()
        compose.waitForIdle()
        thinking.assertIsOff()
        save.performClick()
        compose.waitUntil(5_000) { closes == 1 }
        val raw = runBlocking { store.store.data.first().asMap().mapKeys { it.key.name } }
        assertEquals(false, raw[PreferenceKeys.SHOW_ENDED_SESSIONS])
        assertEquals(false, raw[PreferenceKeys.CONFIRM_BEFORE_END])
        assertEquals(false, raw[PreferenceKeys.SHOW_THINKING])
        assertEquals("/srv/kept", raw[PreferenceKeys.DEFAULT_WORKSPACE])
    }
}
