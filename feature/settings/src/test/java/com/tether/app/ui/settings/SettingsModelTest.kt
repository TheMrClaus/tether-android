package com.tether.app.ui.settings

import com.tether.app.ui.prefs.LoginVariant
import com.tether.app.ui.prefs.TetherPreferences
import com.tether.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dialog's pure rules: the web's tabs, General's draft, the Appearance options. */
class SettingsModelTest {

    /** settings-dialog.tsx:880-888: seven tabs, this order, these labels. */
    @Test fun theTabsAreTheWebsInItsOrder() {
        assertEquals(
            listOf("General", "Appearance", "Devices", "Nodes", "Engines", "Metadata", "Advanced"),
            SettingsTab.entries.map { it.label },
        )
        assertEquals(listOf("general", "appearance", "devices", "nodes", "engines", "metadata", "advanced"), SettingsTab.entries.map { it.id })
    }

    /** settings-dialog.tsx:2008-2012: the three switches, the web's words, in its order. */
    @Test fun generalsSwitchesAreTheWebs() {
        assertEquals(listOf("Ended sessions", "Confirm before ending", "Show agent thinking"), GeneralToggle.entries.map { it.title })
        assertEquals("Protect running work from accidental taps", GeneralToggle.ConfirmBeforeEnd.caption)
    }

    @Test fun theDraftStartsAsTheStoredValuesAndTogglesOneFieldAtATime() {
        val stored = TetherPreferences.Default.copy(defaultWorkspace = "/w", showEndedSessions = false, confirmBeforeEnd = true, showThinking = false)
        val draft = GeneralDraft.of(stored)
        assertEquals(GeneralDraft("/w", showEndedSessions = false, confirmBeforeEnd = true, showThinking = false), draft)
        GeneralToggle.entries.forEach { toggle ->
            val flipped = draft.toggled(toggle)
            assertEquals(!draft.isOn(toggle), flipped.isOn(toggle))
            GeneralToggle.entries.filter { it != toggle }.forEach { other -> assertEquals(draft.isOn(other), flipped.isOn(other)) }
            assertEquals(draft, flipped.toggled(toggle))
        }
    }

    /** settings-dialog.tsx:2005: "Use current" takes the current workspace as it is, empty included. */
    @Test fun useCurrentTakesTheCurrentWorkspace() {
        val draft = GeneralDraft("/old", true, true, false)
        assertEquals("/srv/new", draft.usingCurrent("/srv/new").defaultWorkspace)
        assertEquals("", draft.usingCurrent("").defaultWorkspace)
    }

    /**
     * settings-dialog.tsx:2456: Save writes the drafted fields and keeps the live appearance (and,
     * here, every other field), so a theme picked while the dialog was open is never rolled back.
     */
    @Test fun saveWritesTheDraftedFieldsOverTheFreshModel() {
        val draft = GeneralDraft("/srv/a", showEndedSessions = false, confirmBeforeEnd = false, showThinking = true)
        val fresh = TetherPreferences.Default.copy(themeMode = ThemeMode.Dark, loginVariant = LoginVariant.Retro, sidebarCollapsed = true, pinnedProjects = listOf("/p"))
        val saved = draft.applyTo(fresh)
        assertEquals("/srv/a", saved.defaultWorkspace)
        assertFalse(saved.showEndedSessions)
        assertFalse(saved.confirmBeforeEnd)
        assertTrue(saved.showThinking)
        assertEquals(ThemeMode.Dark, saved.themeMode)
        assertEquals(LoginVariant.Retro, saved.loginVariant)
        assertTrue(saved.sidebarCollapsed)
        assertEquals(listOf("/p"), saved.pinnedProjects)
    }

    /** hooks/use-preferences.ts THEME_MODES / LOGIN_VARIANTS: the web's labels and hints. */
    @Test fun theAppearanceOptionsAreTheWebs() {
        assertEquals(listOf("Light", "Dark", "Follow system"), ThemeMode.entries.map { it.label })
        assertEquals(listOf(LoginVariant.Default, LoginVariant.Retro), AppearanceChoices.loginVariants.map { it.value })
        assertEquals(listOf("Default", "Retro terminal"), AppearanceChoices.loginVariants.map { it.label })
        assertEquals(
            listOf("Studio's welcome sign-in. Default.", "Full-screen terminal layout with a real login: prompt."),
            AppearanceChoices.loginVariants.map { it.hint },
        )
    }

    /** A rotation keeps the tab and the draft; a dialog that had not read the store keeps none. */
    @Test fun theStateSurvivesASave() {
        val state = SettingsDialogState(SettingsTab.Appearance, GeneralDraft("/x", false, true, true))
        val restored = with(SettingsDialogState.Saver) {
            val saved = androidx.compose.runtime.saveable.SaverScope { true }.save(state)!!
            restore(saved)!!
        }
        assertEquals(SettingsTab.Appearance, restored.tab)
        assertEquals(state.draft, restored.draft)
        val fresh = with(SettingsDialogState.Saver) { restore(androidx.compose.runtime.saveable.SaverScope { true }.save(SettingsDialogState())!!)!! }
        assertEquals(SettingsTab.General, fresh.tab)
        assertEquals(null, fresh.draft)
    }
}
