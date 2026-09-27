package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.isNullish
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue

/**
 * T2.2: faithful port of lib/spawn-marker.mjs — `tether:parent=<id>[;spawn=<key>]`.
 * D3: linkage is DISPLAY METADATA, never authorization.
 */
object SpawnMarker {

    // lib/spawn-marker.mjs:27
    const val SPAWN_MARKER_PREFIX = "tether:"
    const val CODEX_ORIGINATOR_ENV = "CODEX_INTERNAL_ORIGINATOR_OVERRIDE"
    const val SPAWN_MARKER_MAX_CHARS = 512

    // lib/spawn-marker.mjs:36
    private val MARKER_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")

    // lib/spawn-marker.mjs:38
    fun isValidMarkerId(value: JsValue?): Boolean = value is JsStr && MARKER_ID_PATTERN.matches(value.value)

    fun isValidMarkerId(value: String?): Boolean = value != null && MARKER_ID_PATTERN.matches(value)

    /** A parsed marker: [spawnKey] is null when absent or invalid. */
    data class Marker(val parentSessionId: String, val spawnKey: String?)

    // lib/spawn-marker.mjs:47 — `formatSpawnMarker({ parentSessionId, spawnKey = null } = {})`; throws on a bad id.
    fun formatSpawnMarker(options: JsValue? = null): String {
        val parentSessionId = options["parentSessionId"]
        val spawnKey = options["spawnKey"] ?: JsNull
        if (!isValidMarkerId(parentSessionId)) throw JsError("Error", "formatSpawnMarker: invalid parentSessionId")
        if (!isNullish(spawnKey) && !isValidMarkerId(spawnKey)) throw JsError("Error", "formatSpawnMarker: invalid spawnKey")
        val suffix = if (truthy(spawnKey)) ";spawn=${(spawnKey as JsStr).value}" else ""
        return "${SPAWN_MARKER_PREFIX}parent=${(parentSessionId as JsStr).value}$suffix"
    }

    fun formatSpawnMarker(parentSessionId: String, spawnKey: String? = null): String =
        formatSpawnMarker(JsObj.of("parentSessionId" to JsStr(parentSessionId), "spawnKey" to spawnKey?.let { JsStr(it) }))

    // lib/spawn-marker.mjs:58 — null for anything that does not START with `tether:parent=<valid id>`.
    fun parseSpawnMarker(value: JsValue?): Marker? {
        if (value !is JsStr || value.value.length > SPAWN_MARKER_MAX_CHARS) return null
        return parseSpawnMarker(value.value)
    }

    fun parseSpawnMarker(value: String): Marker? {
        if (value.length > SPAWN_MARKER_MAX_CHARS || !value.startsWith(SPAWN_MARKER_PREFIX)) return null
        val pairs = value.substring(SPAWN_MARKER_PREFIX.length).split(";")
        val first = pairs[0]
        val eq = first.indexOf('=')
        if (eq == -1 || first.substring(0, eq) != "parent") return null
        val parentSessionId = first.substring(eq + 1)
        if (!isValidMarkerId(parentSessionId)) return null
        var spawnKey: String? = null
        for (pair in pairs.drop(1)) {
            val at = pair.indexOf('=')
            if (at == -1) continue
            val key = pair.substring(0, at)
            val v = pair.substring(at + 1)
            if (key == "spawn" && spawnKey == null && isValidMarkerId(v)) spawnKey = v
            // Unknown keys are ignored on purpose (forward compatibility).
        }
        return Marker(parentSessionId, spawnKey)
    }

    // lib/spawn-marker.mjs:100 — `{ CODEX_INTERNAL_ORIGINATOR_OVERRIDE: marker }`, or {} for a bad id.
    fun parentSpawnMarkerEnv(parentSessionId: JsValue?): Map<String, String> {
        if (!isValidMarkerId(parentSessionId)) return emptyMap()
        return mapOf(CODEX_ORIGINATOR_ENV to formatSpawnMarker(JsObj.of("parentSessionId" to parentSessionId)))
    }
}
