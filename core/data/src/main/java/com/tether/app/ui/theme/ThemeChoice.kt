package com.tether.app.ui.theme

/**
 * The web's two theme axes (tether `hooks/use-preferences.ts`): FAMILY is the material,
 * MODE is the lighting. A family × mode pair resolves to one of the six skins
 * (`TetherSkin` in :core:designsystem, generated from the token export's skinMap).
 */
enum class ThemeFamily(val id: String, val label: String, val hint: String) {
    Tactile("tactile", "Tactile Console", "Molded ABS, dusty-sage keys, deep vintage travel."),
    Precision("precision", "Precision Machine", "Matte ceramic and titanium, mineral keys, shallow machined travel."),
    Studio("studio", "Studio", "Clear blue accents. A focused space for your work."),
    ;

    companion object {
        fun fromId(id: String?): ThemeFamily? = entries.firstOrNull { it.id == id }
    }
}

enum class ThemeMode(val id: String, val label: String, val hint: String) {
    Light("light", "Light", "Always the light finish of the chosen theme."),
    Dark("dark", "Dark", "Always the dark finish of the chosen theme."),
    System("system", "System", "Follow this device's light/dark setting."),
    ;

    /** The web's resolveThemeMode: `system` becomes the lighting the device asks for. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        Light -> false
        Dark -> true
        System -> systemDark
    }

    companion object {
        fun fromId(id: String?): ThemeMode? = entries.firstOrNull { it.id == id }
    }
}

/** The persisted theme preference: both axes, stored separately so light/dark never loses the family. */
data class ThemeChoice(val family: ThemeFamily, val mode: ThemeMode) {
    companion object {
        /** Web default: Precision Machine following the system. */
        val Default = ThemeChoice(ThemeFamily.Precision, ThemeMode.System)

        /**
         * The web's LEGACY_THEMES: pre-two-axis flat theme ids (which this app also stored,
         * as `theme_choice`) are migrated onto the pair, not honoured.
         */
        val LEGACY: Map<String, ThemeChoice> = mapOf(
            "tactile" to ThemeChoice(ThemeFamily.Tactile, ThemeMode.Light),
            "night" to ThemeChoice(ThemeFamily.Tactile, ThemeMode.Dark),
            "precision" to ThemeChoice(ThemeFamily.Precision, ThemeMode.Light),
            "machine" to ThemeChoice(ThemeFamily.Precision, ThemeMode.Dark),
            "quiet" to ThemeChoice(ThemeFamily.Precision, ThemeMode.Dark),
            "system" to ThemeChoice(ThemeFamily.Precision, ThemeMode.System),
        )

        /**
         * The web's normalizeThemeSelection: prefer the two-axis fields, fall back per axis to
         * the migrated legacy flat id, then to [Default].
         */
        fun normalize(family: String?, mode: String?, legacy: String?): ThemeChoice {
            val f = ThemeFamily.fromId(family)
            val m = ThemeMode.fromId(mode)
            if (f != null && m != null) return ThemeChoice(f, m)
            val old = legacy?.let(LEGACY::get)
            return ThemeChoice(f ?: old?.family ?: Default.family, m ?: old?.mode ?: Default.mode)
        }
    }
}
