package com.tether.app.client

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr

/**
 * T6.4 (L4): a defensive cap on background commands' folded output, applied by the CLIENT to every
 * tree it publishes (SessionStore.publish: live folds, hydration, a mirror rebuild, snapshots),
 * never inside the fold. The web's fold (events.mjs `background_command_output`) appends without a
 * bound and relies on the server, which streams at most 64 KiB per command
 * (session-manager.mjs BACKGROUND_COMMAND_LIVE_STREAM_CAP_BYTES); the reducer port stays
 * line-for-line conformant with it. [MAX_CHARS] sits well above what a conforming server can
 * stream, so the cap only acts on a server that exceeds its own limit: a command then keeps its
 * NEWEST [MAX_CHARS] UTF-16 units (whole segments, the oldest one cut at its start, never through a
 * surrogate pair) and reads `outputTruncated: true`, which the sheet words.
 *
 * Round 3: the whole tree is scanned, so no event id is looked up at all (an id the fold bounds is
 * found as the fold stored it, L-1), and `outputTruncated` only goes false -> true: a command the
 * PREVIOUS tree marked truncated stays so, although the next `background_command_updated` merges
 * only the segments and carries the server's own flag (L-2).
 */
object OutputIntakeCap {
    /** 4x the server's 64 KiB byte cap (one UTF-16 unit is at least one UTF-8 byte). */
    const val MAX_CHARS = 256 * 1024

    /** [next] with every command capped at [max], and every command [previous] marked truncated still marked. */
    fun capTree(previous: JsObj?, next: JsObj, max: Int = MAX_CHARS): JsObj {
        val commands = next["backgroundCommands"] as? JsArr ?: return next
        val wasTruncated = (previous?.get("backgroundCommands") as? JsArr)
            ?.mapNotNull { c -> (c as? JsObj)?.takeIf { it["outputTruncated"] == JsBool.TRUE }?.let { (it["commandId"] as? JsStr)?.value } }
            ?.toHashSet()
            .orEmpty()
        var out = commands
        for (i in commands.indices) {
            val command = commands[i] as? JsObj ?: continue
            var capped = capCommand(command, max)
            val id = (command["commandId"] as? JsStr)?.value
            if (id != null && id in wasTruncated && capped["outputTruncated"] != JsBool.TRUE) capped = capped.put("outputTruncated", JsBool.TRUE)
            if (capped !== command) out = out.set(i, capped)
        }
        return if (out === commands) next else next.put("backgroundCommands", out)
    }

    /** One command's segments capped at [max] (the same instance when under it). */
    fun capCommand(command: JsObj, max: Int = MAX_CHARS): JsObj {
        val segments = command["segments"] as? JsArr ?: return command
        var total = 0L
        for (s in segments) total += (((s as? JsObj)?.get("text") as? JsStr)?.value?.length ?: 0)
        if (total <= max) return command
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
        return command.with("segments" to JsArr.of(kept), "outputTruncated" to JsBool.TRUE)
    }
}
