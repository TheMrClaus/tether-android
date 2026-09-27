package com.tether.app.protocol.helpers

import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsValue
import java.time.ZoneId

/** T2.2: faithful port of lib/message-time.mjs. The web reads the host zone; here it is a parameter. */
object MessageTime {

    // lib/message-time.mjs:12 — zero-padded "HH:MM" (never locale-formatted); "" for junk.
    fun messageClockTime(timestamp: JsValue?, zone: ZoneId = ZoneId.systemDefault()): String {
        val ts = (timestamp as? JsNum)?.value
        if (ts == null || !ts.isFinite()) return ""
        val date = jsDate(ts, zone) ?: return "NaN:NaN"
        return "${pad2(date.hour)}:${pad2(date.minute)}"
    }
}
