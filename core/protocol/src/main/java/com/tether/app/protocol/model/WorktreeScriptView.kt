package com.tether.app.protocol.model

import com.tether.app.protocol.objList
import com.tether.app.protocol.str
import kotlinx.serialization.json.JsonObject

/**
 * One entry of a `worktree-scripts` snapshot's `scripts` (lib/protocol.ts WorktreeScript), read
 * tolerantly from the raw snapshot the client keeps: a wrongly typed field reads as null and never
 * drops the entry or the snapshot.
 *
 * v134 (issue #220): service pages are served only on their own origin. [proxyUrl] /
 * [proxyAuthUrl] / [proxyHost] are null when this viewer has no usable service origin, and
 * [proxyPath] is null unless the viewer reached the console over loopback on the daemon's machine
 * (so for the app it is effectively always null). [proxyUnavailable] says why there is no link.
 */
@JvmInline
value class WorktreeScriptView(val obj: JsonObject) {
    val name: String? get() = obj.str("name")

    /** "script" | "service". */
    val type: String? get() = obj.str("type")

    /** "idle" | "starting" | "running" | "stopping" | "exited" | "failed". */
    val status: String? get() = obj.str("status")

    val proxyHost: String? get() = obj.str("proxyHost")
    val proxyUrl: String? get() = obj.str("proxyUrl")
    val proxyPath: String? get() = obj.str("proxyPath")
    val proxyAuthUrl: String? get() = obj.str("proxyAuthUrl")

    /**
     * v134: why this service has no own-origin link for this viewer — "not-configured" |
     * "label-too-long" | "label-invalid" — or null when the server gave no reason (absent / null:
     * it has a link, or the server predates v134). Per lib/protocol.ts, any other present value
     * (an unknown string, a wrong type) reads as "not-configured".
     */
    val proxyUnavailable: String?
        get() {
            val raw = obj["proxyUnavailable"] ?: return null
            if (raw is kotlinx.serialization.json.JsonNull) return null
            return obj.str("proxyUnavailable")?.takeIf { it in SERVICE_UNAVAILABLE } ?: NOT_CONFIGURED
        }

    companion object {
        const val NOT_CONFIGURED = "not-configured"

        /** lib/protocol.ts WorktreeServiceUnavailable (v134). */
        val SERVICE_UNAVAILABLE: Set<String> = setOf(NOT_CONFIGURED, "label-too-long", "label-invalid")

        /** The object entries of [snapshot]'s `scripts` (anything else is skipped). */
        fun of(snapshot: JsonObject): List<WorktreeScriptView> = snapshot.objList("scripts").orEmpty().map(::WorktreeScriptView)
    }
}
