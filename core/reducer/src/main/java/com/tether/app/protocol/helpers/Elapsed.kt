package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.numberToString
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsValue
import kotlin.math.floor

/** T2.2: faithful port of lib/elapsed.mjs. */
object Elapsed {

    // lib/elapsed.mjs:21 — "42s", "3m 7s", then an H:MM:SS clock past an hour; "" for junk.
    fun elapsedLabel(seconds: JsValue?): String {
        val s = (seconds as? JsNum)?.value
        if (s == null || !s.isFinite() || s < 0) return ""
        val whole = floor(s)
        if (whole < 60) return "${numberToString(whole)}s"
        val minutes = floor(whole / 60)
        val remainder = whole % 60
        if (minutes < 60) return if (remainder > 0) "${numberToString(minutes)}m ${numberToString(remainder)}s" else "${numberToString(minutes)}m"
        val hours = floor(minutes / 60)
        fun pad(value: Double) = numberToString(value).padStart(2, '0')
        return "${numberToString(hours)}:${pad(minutes % 60)}:${pad(remainder)}"
    }

    fun elapsedLabel(seconds: Double): String = elapsedLabel(JsNum(seconds))
}
