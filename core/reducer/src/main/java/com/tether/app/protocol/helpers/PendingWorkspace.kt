package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.strictEquals
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.js
import kotlin.math.max

/**
 * T2.2: faithful port of lib/pending-workspace.mjs — the durable workspace-activation intent
 * (issue #141). One slot (`{ cwd, requestId, sentAt, tries, firstAt }` or null); confirmed only by
 * a `histories` reply echoing the intent's requestId.
 */
object PendingWorkspace {

    // lib/pending-workspace.mjs:47 — shared with pending-input so both agree on what "dead" means.
    const val UNACKED_CLOSE_MS = PendingInput.UNACKED_CLOSE_MS

    private fun isIdle(intent: JsValue?) = strictEquals(intent["sentAt"], JsNum(0.0))

    // lib/pending-workspace.mjs:55 — a fresh intent (`sentAt: 0` = not on the wire).
    fun beginIntent(cwd: JsValue?, requestId: JsValue?, now: Double): JsObj =
        JsObj.of("cwd" to cwd, "requestId" to requestId, "sentAt" to js(0), "tries" to js(0), "firstAt" to js(now))

    // lib/pending-workspace.mjs:60 — stamp as transmitted; a no-op when idle (no intent).
    fun markSent(intent: JsValue?, now: Double): JsValue? {
        if (!truthy(intent)) return intent
        return (intent as JsObj).with("sentAt" to js(now), "tries" to js(jsToNumber(intent["tries"]) + 1))
    }

    // lib/pending-workspace.mjs:71
    fun resetInFlight(intent: JsValue?): JsValue? {
        if (!truthy(intent) || isIdle(intent)) return intent
        return (intent as JsObj).put("sentAt", js(0))
    }

    // lib/pending-workspace.mjs:81 — matches on requestId ALONE; `{ intent, confirmed }`.
    fun confirmIntent(intent: JsValue?, requestId: JsValue?): JsObj {
        if (!truthy(intent)) return JsObj.of("intent" to JsNull, "confirmed" to JsBool.FALSE)
        if (requestId is JsStr && strictEquals(requestId, intent["requestId"])) {
            return JsObj.of("intent" to JsNull, "confirmed" to JsBool.TRUE)
        }
        return JsObj.of("intent" to intent, "confirmed" to JsBool.FALSE)
    }

    // lib/pending-workspace.mjs:94
    fun inFlightAge(intent: JsValue?, now: Double): Double {
        if (!truthy(intent) || isIdle(intent)) return 0.0
        return max(0.0, now - jsToNumber(intent["sentAt"]))
    }

    // lib/pending-workspace.mjs:106
    fun isDrainDue(intent: JsValue?, now: Double): Boolean {
        if (!truthy(intent)) return false
        return isIdle(intent) || now - jsToNumber(intent["sentAt"]) > UNACKED_CLOSE_MS
    }

    // lib/pending-workspace.mjs:124 — `{ cwd, requestId, phase: "opening" | "stalled" }`, or null.
    fun describeIntent(intent: JsValue?, options: JsValue? = null): JsObj? {
        if (!truthy(intent)) return null
        requireNotJsNull(options, "now")
        val now = jsToNumber(options["now"])
        val socketOpen = truthy(options["socketOpen"])
        val lastServerFrameAt = options["lastServerFrameAt"]?.let { jsToNumber(it) } ?: 0.0
        val linkStale = now - lastServerFrameAt > UNACKED_CLOSE_MS
        val phase = when {
            !socketOpen || isIdle(intent) -> "stalled"
            now - jsToNumber(intent["sentAt"]) > UNACKED_CLOSE_MS && linkStale -> "stalled"
            else -> "opening"
        }
        return JsObj.of("cwd" to intent["cwd"], "requestId" to intent["requestId"], "phase" to JsStr(phase))
    }
}
