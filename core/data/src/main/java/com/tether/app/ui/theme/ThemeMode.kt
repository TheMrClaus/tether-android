package com.tether.app.ui.theme

/**
 * Appearance (tether `lib/theme-mode.mjs`, `hooks/use-preferences.ts` THEME_MODES). Studio is
 * Tether's one visual system; the operator only picks its lighting — light, dark, or follow the
 * device. The mode resolves to one of the two Studio skins (`TetherSkin` in :core:designsystem,
 * generated from the token export's mode → skin map). The mode is the only persisted appearance
 * choice; retired theme preferences are decoded by [ThemeMigration].
 */
enum class ThemeMode(val id: String, val label: String, val hint: String) {
    Light("light", "Light", "Always the light Studio finish."),
    Dark("dark", "Dark", "Always the dark Studio finish."),
    System("system", "Follow system", "Match this device's light/dark setting."),
    ;

    /** The web's resolveThemeSkin: `system` becomes the lighting the device asks for. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        Light -> false
        Dark -> true
        System -> systemDark
    }

    companion object {
        /** Web default (`themeMode: "system"`). */
        val Default = System

        fun fromId(id: String?): ThemeMode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Migration of every appearance preference this app (or the web it mirrors) has ever stored, onto
 * the Studio [ThemeMode] (tether `lib/theme-mode.mjs` normalizeThemeMode / LEGACY_THEME_MODES,
 * OVERVIEW_STUDIO_PLAN.md §3). Stored shapes:
 *
 * - up to 0.6.x: one flat `theme_choice` id (`system` / `machine` / `night` / `tactile` /
 *   `precision`, and the web's older `quiet`);
 * - 0.7.x to 0.8.0: `theme_family` (`tactile` / `precision` / `studio`) + `theme_mode`;
 * - since T15.5: `theme_mode` alone.
 *
 * A valid stored mode always wins, whatever family it was paired with. Otherwise the legacy flat
 * id is decoded by [LEGACY_THEME_MODES]; anything else (absent, junk) follows the system. The
 * deprecated keys are dropped on the next save of the preference model.
 *
 * [LEGACY_THEME_MODES] is the ONLY place the retired theme names survive in shipping code.
 */
object ThemeMigration {
    /** Retired flat theme ids → the Studio mode that replaces them (the web's table, verbatim). */
    val LEGACY_THEME_MODES: Map<String, ThemeMode> = mapOf(
        "tactile" to ThemeMode.Light,
        "precision" to ThemeMode.Light,
        "studio" to ThemeMode.Light,
        "night" to ThemeMode.Dark,
        "machine" to ThemeMode.Dark,
        "quiet" to ThemeMode.Dark,
        "studio-dark" to ThemeMode.Dark,
        "system" to ThemeMode.System,
    )

    /** Stored `theme_mode` + legacy flat `theme_choice` → the mode. The family never matters. */
    fun normalize(mode: String?, legacyTheme: String?): ThemeMode =
        ThemeMode.fromId(mode) ?: legacyTheme?.let(LEGACY_THEME_MODES::get) ?: ThemeMode.Default
}
