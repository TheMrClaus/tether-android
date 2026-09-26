package com.tether.app.ui.theme

/** The persisted preference: an explicit family or follow-the-system. */
enum class ThemeChoice(val id: String, val label: String) {
    System("system", "System"),
    Machine("machine", "Machine"),
    Night("night", "Night"),
    Tactile("tactile", "Tactile"),
    Precision("precision", "Precision");

    companion object {
        fun fromId(id: String?): ThemeChoice = when (id) {
            // Legacy stored value from the web app maps to the original theme.
            "quiet" -> Machine
            else -> entries.firstOrNull { it.id == id } ?: System
        }
    }
}
