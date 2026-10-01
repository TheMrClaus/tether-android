package com.tether.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
 *
 * ta-b72: this file's classes use the v2 rule, so the store's IO-thread reads and writes resume on
 * the test thread (see [SettingsBehaviourTest]).
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
        assertEquals(GeneralDraft("/srv/kept", showEndedSessions = false, confirmBeforeEnd = false, showThinking = true), state.draft)
        // ta-b72 (load flake): the screen is WAITED on until it draws that draft, then read.
        compose.waitUntil(5_000) { compose.isDrawnEnabled(SettingsDialogTags.Save) && compose.isDrawnEnabled(SettingsPanelTags.toggle(GeneralToggle.ShowThinking)) }
        compose.waitForIdle()
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

/** A store whose writes wait until [open] is set: a slow write. */
private class SlowWriteStore(private val real: DataStore<Preferences>) : DataStore<Preferences> {
    val open = MutableStateFlow(false)
    override val data: Flow<Preferences> = real.data
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        open.first { it }
        return real.updateData(transform)
    }
}

/**
 * ta-t7l r3: Save closes the dialog only after the General draft's write has landed, and a dialog
 * that leaves composition while the write is in flight (Back during a slow write) still writes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SettingsSaveTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private fun stored() = runBlocking { store.store.data.first().asMap().mapKeys { it.key.name } }

    /** ta-b72: the draft (read on the store's IO thread) is drawn: the switch and Save are live. */
    private fun waitReady() = compose.waitUntil(5_000) {
        compose.isDrawnEnabled(SettingsDialogTags.Save) && compose.isDrawnEnabled(SettingsPanelTags.toggle(GeneralToggle.ShowThinking))
    }

    @Test fun saveClosesOnlyAfterTheWriteLands() {
        val slow = SlowWriteStore(store.store)
        val state = SettingsDialogState()
        var closes = 0
        compose.setContent { SettingsUnderTest(UiPrefs.on(slow), state, onClose = { closes++ }) }
        compose.waitUntil(5_000) { state.draft != null }
        waitReady()
        compose.onNodeWithTag(SettingsPanelTags.toggle(GeneralToggle.ShowThinking)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { state.draft?.showThinking == true }
        compose.onNodeWithTag(SettingsDialogTags.Save).performClick()
        compose.waitForIdle()
        // The write is held: not stored, and the dialog is still open.
        assertEquals(0, closes)
        assertEquals(null, stored()[PreferenceKeys.SHOW_THINKING])
        slow.open.value = true
        compose.waitUntil(5_000) { closes == 1 }
        // Closed: the write had already landed.
        assertEquals(true, stored()[PreferenceKeys.SHOW_THINKING])
    }

    @Test fun aDialogDismissedWhileSavingStillWrites() {
        val slow = SlowWriteStore(store.store)
        val state = SettingsDialogState()
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) SettingsUnderTest(UiPrefs.on(slow), state) }
        compose.waitUntil(5_000) { state.draft != null }
        waitReady()
        compose.onNodeWithTag(SettingsPanelTags.toggle(GeneralToggle.ShowThinking)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { state.draft?.showThinking == true }
        compose.onNodeWithTag(SettingsDialogTags.Save).performClick()
        compose.waitForIdle()
        // Back while the write is held: the dialog leaves composition (its scope is cancelled).
        shown = false
        compose.waitForIdle()
        slow.open.value = true
        compose.waitUntil(5_000) { stored()[PreferenceKeys.SHOW_THINKING] == true }
    }
}
