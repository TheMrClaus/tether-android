package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.isNullish
import com.tether.app.protocol.fold.isInteger
import com.tether.app.protocol.fold.jsToString
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import java.time.ZoneId

/** T2.2: faithful port of lib/interrupt-notice.mjs (issue #184). The web reads the host zone. */
object InterruptNotice {

    // lib/interrupt-notice.mjs:9
    private val BY_COPY = mapOf(
        "message-send" to "by your message",
        "operator-stop" to "by Stop",
        "control-api" to "by a Control API client",
        "parent-cancel" to "by its parent session",
        "watchdog" to "by the watchdog (no output for too long)",
        "teardown" to "when the session was ended",
    )

    // lib/interrupt-notice.mjs:18 — zero-padded local HH:MM:SS.
    private fun clockTime(timestamp: JsValue?, zone: ZoneId): String {
        val ts = (timestamp as? JsNum)?.value
        if (ts == null || !ts.isFinite()) return ""
        val date = jsDate(ts, zone) ?: return "NaN:NaN:NaN"
        return "${pad2(date.hour)}:${pad2(date.minute)}:${pad2(date.second)}"
    }

    // lib/interrupt-notice.mjs:25
    private fun plural(count: Double, singular: String, pluralForm: String): String =
        "${com.tether.app.protocol.fold.numberToString(count)} ${if (count == 1.0) singular else pluralForm}"

    // lib/interrupt-notice.mjs:33 — "Interrupted at 09:58:53 by your message · 1 tool stopped · …".
    fun interruptedNoticeCopy(notice: JsValue?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (!truthy(notice)) return ""
        val time = clockTime(notice["at"], zone)
        val by = notice["by"].let { if (it == null) null else BY_COPY[jsToString(it)] } ?: ""
        val head = listOf("Interrupted", if (time.isNotEmpty()) "at $time" else "", by).filter { it.isNotEmpty() }.joinToString(" ")
        val parts = mutableListOf(head)
        val tools = notice["stoppedTools"].let { if (isInteger(it)) (it as JsNum).value else 0.0 }
        val background = notice["stoppedBackground"].let { if (isInteger(it)) (it as JsNum).value else 0.0 }
        if (tools > 0) parts.add("${plural(tools, "tool", "tools")} stopped")
        if (background > 0) parts.add("${plural(background, "background task", "background tasks")} killed")
        return parts.joinToString(" · ")
    }

    // lib/interrupt-notice.mjs:51 — turn_interrupted notices keyed by the turn they explain; latest wins.
    fun interruptNoticesByTurn(notices: JsValue?): Map<String, JsValue> {
        val byTurn = LinkedHashMap<String, JsValue>()
        if (isNullish(notices)) return byTurn
        for (notice in jsIterate(notices)) {
            if (notice["kind"].isStr("turn_interrupted")) {
                val turnId = notice["interruptedTurnId"] as? JsStr ?: continue
                byTurn[turnId.value] = notice!!
            }
        }
        return byTurn
    }
}
