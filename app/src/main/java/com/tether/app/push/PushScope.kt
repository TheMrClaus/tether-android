package com.tether.app.push

/**
 * Per-device push scope. The server uses this to decide which sessions a
 * registration row receives pushes for:
 *
 *  - [All]        → every event for this server.
 *  - [Attached]   → only events for a session the device has opened (attached).
 *  - [Pinned]     → only events for a session the device has pinned.
 *
 * Wire shape is the lowercase string used by `/api/push/fcm-register` — kept
 * here so the app and server never disagree on casing.
 */
enum class PushScope(val wire: String) {
    All("all"),
    Attached("attached"),
    Pinned("pinned"),
    ;

    companion object {
        fun fromWire(value: String?): PushScope? = when (value) {
            "all" -> All
            "attached" -> Attached
            "pinned" -> Pinned
            else -> null
        }
    }
}