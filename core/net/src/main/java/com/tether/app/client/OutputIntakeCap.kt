package com.tether.app.client

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr

/**
 * T6.4 (L4): a defensive cap on a background command's folded output, applied by the CLIENT after
 * the fold, never inside it. The web's fold (events.mjs `background_command_output`) appends
 * without a bound and relies on the server, which streams at most 64 KiB per command
 * (session-manager.mjs BACKGROUND_COMMAND_LIVE_STREAM_CAP_BYTES); the reducer port stays
 * line-for-line conformant with it. [MAX_CHARS] sits well above what a conforming server can
 * stream, so the cap only acts on a server that exceeds its own limit: the command then keeps
 * its NEWEST [MAX_CHARS] UTF-16 units (whole segments, the oldest one cut at its start, never
 * through a surrogate pair) and reads `outputTruncated: true`, which the sheet already words.
 */
object OutputIntakeCap {
    /** 4× the server's 64 KiB byte cap (one UTF-16 unit is at least one UTF-8 byte). */
    const val MAX_CHARS = 256 * 1024

    fun apply(tree: JsObj, event: JsObj, max: Int = MAX_CHARS): JsObj {
        if ((event["type"] as? JsStr)?.value != "background_command_output") return tree
        val commandId = (event["commandId"] as? JsStr)?.value ?: return tree
        val commands = tree["backgroundCommands"] as? JsArr ?: return tree
        val index = commands.indexOfFirst { ((it as? JsObj)?.get("commandId") as? JsStr)?.value == commandId }
        if (index < 0) return tree
        val command = commands[index] as JsObj
        val segments = command["segments"] as? JsArr ?: return tree
        var total = 0L
        for (s in segments) total += (((s as? JsObj)?.get("text") as? JsStr)?.value?.length ?: 0)
        if (total <= max) return tree
        val kept = ArrayDeque<JsObj>()
        var budget = max
        for (i in segments.indices.reversed()) {
            if (budget <= 0) break
            val seg = segments[i] as? JsObj ?: continue
            val text = (seg["text"] as? JsStr)?.value ?: continue
            if (text.length <= budget) {
                kept.addFirst(seg)
                budget -= text.length
            } else {
                var cut = text.length - budget
                if (cut < text.length && Character.isLowSurrogate(text[cut])) cut++
                kept.addFirst(seg.put("text", JsStr(text.substring(cut))))
                budget = 0
            }
        }
        val capped = command.with("segments" to JsArr.of(kept), "outputTruncated" to JsBool.TRUE)
        return tree.put("backgroundCommands", commands.set(index, capped))
    }
}
