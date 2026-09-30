package com.tether.app.ui.prefs

import com.tether.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2.3: the preference model against the web source — tether hooks/use-preferences.ts
 * (defaults :237-260, parse/repair :319-346) and lib/panel-widths.mjs (:66-74).
 */
class TetherPreferencesTest {

    private val d = TetherPreferences.Default

    /** use-preferences.ts:237-260, field by field (the three xterm fields are not mirrored). */
    @Test
    fun defaultsMatchTheWeb() {
        val table: List<Pair<String, Any?>> = listOf(
            "themeMode system" to (d.themeMode == ThemeMode.System),
            "loginVariant default" to (d.loginVariant == LoginVariant.Default),
            "defaultWorkspace '' (:241)" to (d.defaultWorkspace == ""),
            "showEndedSessions true (:245)" to (d.showEndedSessions),
            "confirmBeforeEnd true (:246)" to (d.confirmBeforeEnd),
            "showThinking false (:247)" to (!d.showThinking),
            "sidebarCollapsed false (:248)" to (!d.sidebarCollapsed),
            "sidebarWidth null (:249)" to (d.sidebarWidth == null),
            "inspectorWidth null (:250)" to (d.inspectorWidth == null),
            "pinnedProjects [] (:251)" to d.pinnedProjects.isEmpty(),
            "collapsedWorkspaces [] (:252)" to d.collapsedWorkspaces.isEmpty(),
            "lastSeenSessions {} (:253)" to d.lastSeenSessions.isEmpty(),
            "lastOpenedSession null (:254)" to (d.lastOpenedSession == null),
            "sidebarActiveOnly false (:255)" to (!d.sidebarActiveOnly),
            "sidebarUnreadOnly false (:256)" to (!d.sidebarUnreadOnly),
            "sidebarHideAgentRuns true (:257)" to (d.sidebarHideAgentRuns),
            "sidebarSort created (:258)" to (d.sidebarSort == SidebarSort.Created),
            "pinnedModels [] (:259)" to d.pinnedModels.isEmpty(),
        )
        for ((name, ok) in table) assertEquals(name, true, ok)
        assertEquals(TetherPreferences.Default, TetherPreferences.parse(emptyMap()))
        assertEquals(setOf("terminalFontSize", "scrollback", "showQuickKeys"), TetherPreferences.BROWSER_ONLY.keys)
    }

    /**
     * T15.5 (lib/theme-mode.mjs normalizeThemeMode + LEGACY_THEME_MODES): every appearance shape
     * this app has stored — the flat `theme_choice` (up to 0.6.x), the `theme_family` +
     * `theme_mode` pair (0.7.x to 0.8.0), `theme_mode` alone (now) — reads as a Studio mode. A valid
     * stored mode wins whatever family it was paired with; the family itself never matters.
     */
    @Test
    fun everyStoredAppearanceMigratesToAStudioMode() {
        fun mode(family: Any?, mode: Any?, legacy: Any?) = TetherPreferences.parse(
            mapOf(
                PreferenceKeys.THEME_FAMILY to family,
                PreferenceKeys.THEME_MODE to mode,
                PreferenceKeys.LEGACY_THEME to legacy,
            ),
        ).themeMode
        val table: List<Pair<Triple<Any?, Any?, Any?>, ThemeMode>> = listOf(
            // Fresh install.
            Triple(null, null, null) to ThemeMode.System,
            // Up to 0.6.x: the flat id alone (the app's five ids + the web's "quiet").
            Triple(null, null, "system") to ThemeMode.System,
            Triple(null, null, "machine") to ThemeMode.Dark,
            Triple(null, null, "night") to ThemeMode.Dark,
            Triple(null, null, "quiet") to ThemeMode.Dark,
            Triple(null, null, "tactile") to ThemeMode.Light,
            Triple(null, null, "precision") to ThemeMode.Light,
            Triple(null, null, "studio") to ThemeMode.Light,
            Triple(null, null, "studio-dark") to ThemeMode.Dark,
            // 0.7.x to 0.8.0: every family x mode pair keeps its mode.
            Triple("tactile", "light", null) to ThemeMode.Light,
            Triple("tactile", "dark", null) to ThemeMode.Dark,
            Triple("tactile", "system", null) to ThemeMode.System,
            Triple("precision", "light", null) to ThemeMode.Light,
            Triple("precision", "dark", null) to ThemeMode.Dark,
            Triple("precision", "system", null) to ThemeMode.System,
            Triple("studio", "light", null) to ThemeMode.Light,
            Triple("studio", "dark", null) to ThemeMode.Dark,
            Triple("studio", "system", null) to ThemeMode.System,
            // A pair beside a leftover flat id: the stored mode wins.
            Triple("studio", "dark", "tactile") to ThemeMode.Dark,
            Triple("precision", "light", "machine") to ThemeMode.Light,
            // A family without a usable mode: the flat id decides, else the system.
            Triple("tactile", null, "machine") to ThemeMode.Dark,
            Triple("tactile", "sepia", null) to ThemeMode.System,
            // Now: the mode alone.
            Triple(null, "light", null) to ThemeMode.Light,
            Triple(null, "dark", null) to ThemeMode.Dark,
            Triple(null, "system", null) to ThemeMode.System,
            // Junk and wrong types follow the system.
            Triple("junk", "sepia", "neon") to ThemeMode.System,
            Triple(7, true, 3.5) to ThemeMode.System,
        )
        for ((input, expected) in table) {
            assertEquals("$input", expected, mode(input.first, input.second, input.third))
        }
    }

    /** loginVariant (:340) and sidebarSort (:338): only the explicit opt-in value is honoured. */
    @Test
    fun enumsAcceptOnlyTheExplicitOptIn() {
        fun parse(key: String, value: Any?) = TetherPreferences.parse(mapOf(key to value))
        assertEquals(LoginVariant.Retro, parse(PreferenceKeys.LOGIN_VARIANT, "retro").loginVariant)
        // The former "instrument" (and junk) reads as Default: junk can never pick Retro.
        for (junk in listOf("default", "instrument", "RETRO", "", 1, null)) {
            assertEquals("$junk", LoginVariant.Default, parse(PreferenceKeys.LOGIN_VARIANT, junk).loginVariant)
        }
        assertEquals(SidebarSort.LastActive, parse(PreferenceKeys.SIDEBAR_SORT, "last-active").sidebarSort)
        for (junk in listOf("created", "lastActive", "", false, null)) {
            assertEquals("$junk", SidebarSort.Created, parse(PreferenceKeys.SIDEBAR_SORT, junk).sidebarSort)
        }
    }

    /** sidebarWidth / inspectorWidth (:342-343) through parseStoredPanelWidth (panel-widths.mjs:66-74). */
    @Test
    fun panelWidthsAreFailSoft() {
        fun width(value: Any?) = TetherPreferences.parse(mapOf(PreferenceKeys.SIDEBAR_WIDTH to value)).sidebarWidth
        val table: List<Pair<Any?, Int?>> = listOf(
            320 to 320,
            320.6 to 321, // Math.round
            -40 to 0, // never negative
            99_999 to 8192, // STORED_WIDTH_CEILING_PX
            "288" to 288, // numeric string (hand-edited store)
            " " to null,
            "wide" to null,
            Double.NaN to null,
            Double.POSITIVE_INFINITY to null,
            true to null,
            null to null,
        )
        for ((input, expected) in table) assertEquals("$input", expected, width(input))
        assertEquals(
            300,
            TetherPreferences.parse(mapOf(PreferenceKeys.INSPECTOR_WIDTH to 300L)).inspectorWidth,
        )
    }

    /** A wrongly-typed value for any field reads as that field's default — never a crash. */
    @Test
    fun wrongTypesFallBackPerField() {
        val junk: Map<String, Any?> = listOf(
            PreferenceKeys.DEFAULT_WORKSPACE, PreferenceKeys.SHOW_ENDED_SESSIONS, PreferenceKeys.CONFIRM_BEFORE_END,
            PreferenceKeys.SHOW_THINKING, PreferenceKeys.SIDEBAR_COLLAPSED, PreferenceKeys.PINNED_PROJECTS,
            PreferenceKeys.COLLAPSED_WORKSPACES, PreferenceKeys.LAST_SEEN_SESSIONS, PreferenceKeys.LAST_OPENED_CWD,
            PreferenceKeys.LAST_OPENED_SESSION_ID, PreferenceKeys.SIDEBAR_ACTIVE_ONLY, PreferenceKeys.SIDEBAR_UNREAD_ONLY,
            PreferenceKeys.SIDEBAR_HIDE_AGENT_RUNS, PreferenceKeys.PINNED_MODELS,
        ).associateWith { key -> if (key.startsWith("show") || key.startsWith("sidebar") || key.startsWith("confirm")) "yes" else 42 }
        assertEquals(TetherPreferences.Default, TetherPreferences.parse(junk))
        // One good field among junk survives on its own.
        val mixed = TetherPreferences.parse(junk + (PreferenceKeys.SHOW_THINKING to true))
        assertEquals(TetherPreferences.Default.copy(showThinking = true), mixed)
    }

    @Test
    fun listsMapsAndLastOpenedParse() {
        val parsed = TetherPreferences.parse(
            mapOf(
                PreferenceKeys.PINNED_PROJECTS to "/srv/a\n\n/srv/b",
                PreferenceKeys.PINNED_MODELS to "claude-opus-4\n",
                PreferenceKeys.COLLAPSED_WORKSPACES to "/srv/b",
                PreferenceKeys.LAST_SEEN_SESSIONS to "h1\t1700000000000\nbad-line\nh2\tNaN\nh3\t-5\nh4\t42",
                PreferenceKeys.LAST_OPENED_CWD to "/srv/a",
                PreferenceKeys.LAST_OPENED_SESSION_ID to "s1",
            ),
        )
        assertEquals(listOf("/srv/a", "/srv/b"), parsed.pinnedProjects)
        assertEquals(listOf("claude-opus-4"), parsed.pinnedModels)
        assertEquals(listOf("/srv/b"), parsed.collapsedWorkspaces)
        assertEquals(mapOf("h1" to 1_700_000_000_000L, "h4" to 42L), parsed.lastSeenSessions)
        assertEquals(LastOpenedSession("/srv/a", "s1", null), parsed.lastOpenedSession)
        // A half-stored lastOpenedSession (no session id) is null, not a broken record.
        assertNull(TetherPreferences.parse(mapOf(PreferenceKeys.LAST_OPENED_CWD to "/srv/a")).lastOpenedSession)
        assertTrue(TetherPreferences.joinSeen(mapOf("ok" to 1L, "bad\tid" to 2L)) == "ok\t1")
    }
}
