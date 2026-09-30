package com.tether.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The web's appearance model (tether lib/theme-mode.mjs, T15.5): Studio is the only visual
 * system; the mode resolves to one of its two skins, and every retired stored value migrates.
 */
class ThemeModelTest {

    @Test
    fun modeResolvesToAStudioSkinLikeTheWeb() {
        // mode, systemDark -> skin id (resolveThemeSkin)
        val table = listOf(
            (ThemeMode.Light to false) to "studio",
            (ThemeMode.Light to true) to "studio",
            (ThemeMode.Dark to false) to "studio-dark",
            (ThemeMode.Dark to true) to "studio-dark",
            (ThemeMode.System to false) to "studio",
            (ThemeMode.System to true) to "studio-dark",
        )
        assertEquals(ThemeMode.entries.size * 2, table.size)
        for ((input, skin) in table) {
            val (mode, systemDark) = input
            assertEquals("$mode systemDark=$systemDark", skin, mode.resolve(systemDark).id)
        }
    }

    @Test
    fun onlyTheTwoStudioSkinsExist() {
        assertEquals(listOf("studio", "studio-dark"), TetherSkin.entries.map { it.id })
        assertEquals(TetherSkin.Studio, TetherSkin.of(dark = false))
        assertEquals(TetherSkin.StudioDark, TetherSkin.of(dark = true))
        for (skin in TetherSkin.entries) assertEquals(skin, skin.mode.resolve(systemDark = !skin.isDark))
    }

    @Test
    fun defaultFollowsTheSystem() {
        // Web defaults: themeMode "system".
        assertEquals(ThemeMode.System, ThemeMode.Default)
        assertEquals(ThemeMode.System, ThemeMigration.normalize(null, null))
        assertEquals(TetherSkin.Studio, ThemeMode.Default.resolve(systemDark = false))
        assertEquals(TetherSkin.StudioDark, ThemeMode.Default.resolve(systemDark = true))
    }

    @Test
    fun pickerOffersLightDarkAndFollowSystemOnly() {
        // hooks/use-preferences.ts THEME_MODES, in order, with the web's labels.
        assertEquals(listOf("light", "dark", "system"), ThemeMode.entries.map { it.id })
        assertEquals(listOf("Light", "Dark", "Follow system"), ThemeMode.entries.map { it.label })
    }

    @Test
    fun legacyFlatThemesMigrateToAStudioMode() {
        // lib/theme-mode.mjs LEGACY_THEME_MODES, verbatim.
        val table = mapOf(
            "tactile" to ThemeMode.Light,
            "precision" to ThemeMode.Light,
            "studio" to ThemeMode.Light,
            "night" to ThemeMode.Dark,
            "machine" to ThemeMode.Dark,
            "quiet" to ThemeMode.Dark,
            "studio-dark" to ThemeMode.Dark,
            "system" to ThemeMode.System,
        )
        assertEquals(table, ThemeMigration.LEGACY_THEME_MODES)
        for ((legacy, expected) in table) {
            assertEquals(legacy, expected, ThemeMigration.normalize(null, legacy))
        }
        // Unknown flat ids fall back to following the system.
        assertEquals(ThemeMode.System, ThemeMigration.normalize(null, "neon"))
        assertEquals(ThemeMode.System, ThemeMigration.normalize(null, ""))
    }

    @Test
    fun aValidStoredModeAlwaysWins() {
        assertEquals(ThemeMode.Light, ThemeMigration.normalize("light", "machine"))
        assertEquals(ThemeMode.Dark, ThemeMigration.normalize("dark", "tactile"))
        assertEquals(ThemeMode.System, ThemeMigration.normalize("system", "night"))
        // An invalid stored mode falls back to the legacy flat id, then to the system.
        assertEquals(ThemeMode.Dark, ThemeMigration.normalize("bogus", "night"))
        assertEquals(ThemeMode.System, ThemeMigration.normalize("bogus", null))
        assertEquals(ThemeMode.System, ThemeMigration.normalize("Dark", null))
    }
}
