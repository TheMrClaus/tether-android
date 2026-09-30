package com.tether.app.ui.prefs

import com.tether.app.ui.theme.ThemeChoice
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
            "themeFamily precision (:238)" to (d.theme.family == ThemeFamily.Precision),
            "themeMode system (:239)" to (d.theme.mode == ThemeMode.System),
            "loginVariant instrument (:240)" to (d.loginVariant == LoginVariant.Instrument),
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

    /** normalizeThemeSelection (use-preferences.ts:116-129) + LEGACY_THEMES (:100-110). */
    @Test
    fun themePairIsMigratedAndRepaired() {
        fun theme(family: Any?, mode: Any?, legacy: Any?) = TetherPreferences.parse(
            mapOf(
                PreferenceKeys.THEME_FAMILY to family,
                PreferenceKeys.THEME_MODE to mode,
                PreferenceKeys.LEGACY_THEME to legacy,
            ),
        ).theme
        val table = listOf(
            Triple(Triple("studio", "dark", "night"), ThemeFamily.Studio, ThemeMode.Dark), // pair wins
            Triple(Triple(null, null, "night"), ThemeFamily.Tactile, ThemeMode.Dark),
            Triple(Triple(null, null, "quiet"), ThemeFamily.Precision, ThemeMode.Dark),
            Triple(Triple(null, null, "system"), ThemeFamily.Precision, ThemeMode.System),
            Triple(Triple("tactile", null, "machine"), ThemeFamily.Tactile, ThemeMode.Dark), // per axis
            Triple(Triple("junk", "sepia", "neon"), ThemeFamily.Precision, ThemeMode.System),
            Triple(Triple(7, true, 3.5), ThemeFamily.Precision, ThemeMode.System), // wrong types
        )
        for ((input, family, mode) in table) {
            assertEquals("$input", ThemeChoice(family, mode), theme(input.first, input.second, input.third))
        }
    }

    /** loginVariant (:340) and sidebarSort (:338): only the explicit opt-in value is honoured. */
    @Test
    fun enumsAcceptOnlyTheExplicitOptIn() {
        fun parse(key: String, value: Any?) = TetherPreferences.parse(mapOf(key to value))
        assertEquals(LoginVariant.Retro, parse(PreferenceKeys.LOGIN_VARIANT, "retro").loginVariant)
        for (junk in listOf("instrument", "RETRO", "", 1, null)) {
            assertEquals("$junk", LoginVariant.Instrument, parse(PreferenceKeys.LOGIN_VARIANT, junk).loginVariant)
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
