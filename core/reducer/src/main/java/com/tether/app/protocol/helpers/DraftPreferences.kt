package com.tether.app.protocol.helpers

/**
 * T2.2: lib/draft-preferences.mjs. Only the storage key is pure: read/write are localStorage I/O
 * (T2.3 maps them onto a DataStore); the pure merge is [DraftForm.mergeDraftPreferences].
 */
object DraftPreferences {

    // lib/draft-preferences.mjs:10
    const val DRAFT_PREFS_KEY = "tether:draftPreferences.v1"
}
