package com.tether.app.ui.settings

import com.tether.app.ui.prefs.LoginVariant
import com.tether.app.ui.prefs.TetherPreferences

/**
 * The dialog's tabs, in the web's order (settings-dialog.tsx:880-888 `SETTINGS_TABS`). Each tab's
 * panel is its own composable in this module (SettingsPanels.kt): the later T10.1 slices fill in
 * Engines (ta-9q2, ta-dh1, ta-q6p), Metadata and Advanced (ta-t7l); T10.3 fills Nodes and T10.4
 * the Devices slots, without touching the dialog's shell.
 */
enum class SettingsTab(val id: String, val label: String) {
    General("general", "General"),
    Appearance("appearance", "Appearance"),
    Devices("devices", "Devices"),
    Nodes("nodes", "Nodes"),
    Engines("engines", "Engines"),
    Metadata("metadata", "Metadata"),
    Advanced("advanced", "Advanced"),
}

/**
 * General's three switches (settings-dialog.tsx:2008-2012): title, caption and tip, the web's words.
 */
enum class GeneralToggle(val title: String, val caption: String, val tip: String) {
    ShowEndedSessions(
        "Ended sessions",
        "Keep completed sessions in the sidebar",
        "When on, finished sessions stay in the sidebar for review. Turn off to keep the list focused on active work.",
    ),
    ConfirmBeforeEnd(
        "Confirm before ending",
        "Protect running work from accidental taps",
        "Shows a confirmation prompt when you end a session, preventing accidental loss of running work.",
    ),
    ShowThinking(
        "Show agent thinking",
        "Reveal the model's extended-thinking blocks (collapsed by default)",
        "Reveals the model's chain-of-thought blocks in the conversation. Collapsed by default to reduce visual noise.",
    ),
}

/**
 * General's Save draft (settings-dialog.tsx:1935 `useState(preferences)`): seeded from the live
 * preferences when the dialog opens, edited locally, written only by Save settings, dropped by
 * Cancel / Close / Back (the web's `close()` and `onClose` reset it). Only the four General fields
 * are drafted; Appearance applies on click (see [AppearanceChoices]).
 */
data class GeneralDraft(
    val defaultWorkspace: String,
    val showEndedSessions: Boolean,
    val confirmBeforeEnd: Boolean,
    val showThinking: Boolean,
) {
    fun isOn(toggle: GeneralToggle): Boolean = when (toggle) {
        GeneralToggle.ShowEndedSessions -> showEndedSessions
        GeneralToggle.ConfirmBeforeEnd -> confirmBeforeEnd
        GeneralToggle.ShowThinking -> showThinking
    }

    /** settings-dialog.tsx:1963 `toggle(key)`. */
    fun toggled(toggle: GeneralToggle): GeneralDraft = when (toggle) {
        GeneralToggle.ShowEndedSessions -> copy(showEndedSessions = !showEndedSessions)
        GeneralToggle.ConfirmBeforeEnd -> copy(confirmBeforeEnd = !confirmBeforeEnd)
        GeneralToggle.ShowThinking -> copy(showThinking = !showThinking)
    }

    /** settings-dialog.tsx:2005 "Use current": the draft takes the current workspace as it is. */
    fun usingCurrent(currentWorkspace: String): GeneralDraft = copy(defaultWorkspace = currentWorkspace)

    /**
     * Save settings (settings-dialog.tsx:2456): the drafted fields over a FRESH read of the stored
     * model, so the live Appearance choice, and anything else changed while the dialog was open,
     * is kept (the web re-reads the two appearance fields from the live preference for the same
     * reason).
     */
    fun applyTo(preferences: TetherPreferences): TetherPreferences = preferences.copy(
        defaultWorkspace = defaultWorkspace,
        showEndedSessions = showEndedSessions,
        confirmBeforeEnd = confirmBeforeEnd,
        showThinking = showThinking,
    )

    companion object {
        fun of(preferences: TetherPreferences): GeneralDraft = GeneralDraft(
            defaultWorkspace = preferences.defaultWorkspace,
            showEndedSessions = preferences.showEndedSessions,
            confirmBeforeEnd = preferences.confirmBeforeEnd,
            showThinking = preferences.showThinking,
        )

        /** settings-dialog.tsx:2005: the row's caption when no default workspace is kept. */
        const val WORKSPACE_ROOT = "Workspace root"
    }
}

/** One Appearance option row (hooks/use-preferences.ts THEME_MODES / LOGIN_VARIANTS). */
data class AppearanceChoice<T>(val value: T, val label: String, val hint: String)

object AppearanceChoices {
    /** hooks/use-preferences.ts:51-54 `LOGIN_VARIANTS`, the web's labels and hints. */
    val loginVariants: List<AppearanceChoice<LoginVariant>> = listOf(
        AppearanceChoice(LoginVariant.Default, "Default", "Studio's welcome sign-in. Default."),
        AppearanceChoice(LoginVariant.Retro, "Retro terminal", "Full-screen terminal layout with a real login: prompt."),
    )
}
