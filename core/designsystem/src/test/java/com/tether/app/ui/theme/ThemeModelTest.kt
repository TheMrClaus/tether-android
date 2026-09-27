package com.tether.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** The web's theme model (hooks/use-preferences.ts): resolution, legacy migration, defaults. */
class ThemeModelTest {

    @Test
    fun familyTimesModeResolvesLikeTheWeb() {
        // family, mode, systemDark -> skin id (THEME_SKINS + resolveThemeMode)
        val table = listOf(
            Triple(ThemeFamily.Tactile, ThemeMode.Light, false) to "tactile",
            Triple(ThemeFamily.Tactile, ThemeMode.Light, true) to "tactile",
            Triple(ThemeFamily.Tactile, ThemeMode.Dark, false) to "night",
            Triple(ThemeFamily.Tactile, ThemeMode.Dark, true) to "night",
            Triple(ThemeFamily.Tactile, ThemeMode.System, false) to "tactile",
            Triple(ThemeFamily.Tactile, ThemeMode.System, true) to "night",
            Triple(ThemeFamily.Precision, ThemeMode.Light, false) to "precision",
            Triple(ThemeFamily.Precision, ThemeMode.Light, true) to "precision",
            Triple(ThemeFamily.Precision, ThemeMode.Dark, false) to "machine",
            Triple(ThemeFamily.Precision, ThemeMode.Dark, true) to "machine",
            Triple(ThemeFamily.Precision, ThemeMode.System, false) to "precision",
            Triple(ThemeFamily.Precision, ThemeMode.System, true) to "machine",
            Triple(ThemeFamily.Studio, ThemeMode.Light, false) to "studio",
            Triple(ThemeFamily.Studio, ThemeMode.Light, true) to "studio",
            Triple(ThemeFamily.Studio, ThemeMode.Dark, false) to "studio-dark",
            Triple(ThemeFamily.Studio, ThemeMode.Dark, true) to "studio-dark",
            Triple(ThemeFamily.Studio, ThemeMode.System, false) to "studio",
            Triple(ThemeFamily.Studio, ThemeMode.System, true) to "studio-dark",
        )
        assertEquals(ThemeFamily.entries.size * ThemeMode.entries.size * 2, table.size)
        for ((input, skin) in table) {
            val (family, mode, systemDark) = input
            assertEquals("$family/$mode systemDark=$systemDark", skin, ThemeChoice(family, mode).resolve(systemDark).id)
        }
        // All six skins are reachable.
        assertEquals(TetherSkin.entries.map { it.id }.toSet(), table.map { it.second }.toSet())
    }

    @Test
    fun defaultIsPrecisionFollowingTheSystem() {
        assertEquals(ThemeChoice(ThemeFamily.Precision, ThemeMode.System), ThemeChoice.Default)
        assertEquals(ThemeChoice.Default, ThemeChoice.normalize(null, null, null))
        assertEquals(TetherSkin.Precision, ThemeChoice.Default.resolve(systemDark = false))
        assertEquals(TetherSkin.Machine, ThemeChoice.Default.resolve(systemDark = true))
    }

    @Test
    fun legacyFlatThemeIdsMigrateLikeLegacyThemes() {
        val table = mapOf(
            "machine" to ThemeChoice(ThemeFamily.Precision, ThemeMode.Dark),
            "night" to ThemeChoice(ThemeFamily.Tactile, ThemeMode.Dark),
            "precision" to ThemeChoice(ThemeFamily.Precision, ThemeMode.Light),
            "tactile" to ThemeChoice(ThemeFamily.Tactile, ThemeMode.Light),
            "system" to ThemeChoice(ThemeFamily.Precision, ThemeMode.System),
            "quiet" to ThemeChoice(ThemeFamily.Precision, ThemeMode.Dark),
        )
        assertEquals(table, ThemeChoice.LEGACY)
        for ((legacy, expected) in table) {
            assertEquals(legacy, expected, ThemeChoice.normalize(null, null, legacy))
        }
        // Every value this app ever stored in theme_choice (the old enum ids) is covered.
        listOf("system", "machine", "night", "tactile", "precision").forEach {
            assertEquals(it, table.getValue(it), ThemeChoice.normalize(null, null, it))
        }
        // An unknown legacy id falls back to the default.
        assertEquals(ThemeChoice.Default, ThemeChoice.normalize(null, null, "neon"))
    }

    @Test
    fun twoAxisFieldsWinAndRepairPerAxis() {
        // Both axes stored: the legacy id is ignored.
        assertEquals(
            ThemeChoice(ThemeFamily.Studio, ThemeMode.Light),
            ThemeChoice.normalize("studio", "light", "machine"),
        )
        // One valid axis: the other comes from the legacy id, then the default.
        assertEquals(ThemeChoice(ThemeFamily.Studio, ThemeMode.Dark), ThemeChoice.normalize("studio", null, "machine"))
        assertEquals(ThemeChoice(ThemeFamily.Tactile, ThemeMode.Dark), ThemeChoice.normalize("bogus", "dark", "tactile"))
        assertEquals(ThemeChoice(ThemeFamily.Studio, ThemeMode.System), ThemeChoice.normalize("studio", "bogus", null))
    }
}
