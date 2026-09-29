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
 * line-for-line conformant with it.
 *
 * A command keeps its NEWEST [MAX_CHARS] UTF-16 units in at most [MAX_SEGMENTS] segments (whole
 * segments, the oldest kept one cut at its start, never through a surrogate pair); empty or
 * malformed segments are dropped. Round 4 (B2): `outputTruncated` is the server's flag OR the kept
 * output being AT a bound ([MAX_CHARS] characters or [MAX_SEGMENTS] segments). That is data, not
 * history: it survives the next `background_command_updated` (which carries the server's flag and
 * merges only the segments) and a restart from a checkpoint that holds exactly the capped output,
 * and a legitimate full snapshot from a conforming server (well under both bounds) clears it. No
 * previous-tree flag carry-over is kept: nothing needs it any more.
 *
 * Round 4 (B1): the cost is bounded by what CHANGED. A command whose object is the very one the
 * previous tree held under its commandId was capped when that tree was published and is skipped,
 * so a publish scans only the commands an event touched (each at most [MAX_SEGMENTS] long), not
 * every stored segment.
 */
object OutputIntakeCap {
    /** 4x the server's 64 KiB byte cap (one UTF-16 unit is at least one UTF-8 byte). */
    const val MAX_CHARS = 256 * 1024

    /** Segments kept per command (a conforming server can legally alternate streams per character). */
    const val MAX_SEGMENTS = 4096

    /** [next] with every command that is not [previous]'s own object (by commandId) capped. */
    fun capTree(previous: JsObj?, next: JsObj, maxChars: Int = MAX_CHARS, maxSegments: Int = MAX_SEGMENTS): JsObj {
        val commands = next["backgroundCommands"] as? JsArr ?: return next
        val before = previous?.get("backgroundCommands") as? JsArr
        if (before === commands) return next
        val known = HashMap<String, JsObj>()
        before?.forEach { c ->
            val o = c as? JsObj ?: return@forEach
            (o["commandId"] as? JsStr)?.value?.let { known[it] = o }
        }
        var out = commands
        for (i in commands.indices) {
            val command = commands[i] as? JsObj ?: continue
            val id = (command["commandId"] as? JsStr)?.value
            // Reference identity on purpose: the previous tree's object was capped when it was published.
            if (id != null && known[id] === command) continue
            val capped = capCommand(command, maxChars, maxSegments)
            if (capped !== command) out = out.set(i, capped)
        }
        return if (out === commands) next else next.put("backgroundCommands", out)
    }

    /** One command capped (the same instance when nothing changes). */
    fun capCommand(command: JsObj, maxChars: Int = MAX_CHARS, maxSegments: Int = MAX_SEGMENTS): JsObj {
        val segments = command["segments"] as? JsArr ?: return command
        val serverFlag = command["outputTruncated"] == JsBool.TRUE
        var total = 0L
        var valid = 0
        var malformed = false
        for (s in segments) {
            val len = ((s as? JsObj)?.get("text") as? JsStr)?.value?.length ?: 0
            if (len == 0) {
                malformed = true
            } else {
                total += len
                valid++
            }
        }
        val over = total > maxChars || valid > maxSegments
        var kept: JsArr = segments
        var keptChars = total
        var keptCount = valid
        if (over || malformed) {
            val list = ArrayDeque<JsObj>()
            var budget = maxChars.toLong()
            keptChars = 0
            for (i in segments.indices.reversed()) {
                if (budget <= 0 || list.size >= maxSegments) break
                val seg = segments[i] as? JsObj ?: continue
                val text = (seg["text"] as? JsStr)?.value ?: continue
                if (text.isEmpty()) continue
                if (text.length <= budget) {
                    list.addFirst(seg)
                    budget -= text.length
                    keptChars += text.length
                } else {
                    var cut = (text.length - budget).toInt()
                    if (cut < text.length && Character.isLowSurrogate(text[cut])) cut++
                    val rest = text.substring(cut)
                    if (rest.isNotEmpty()) {
                        list.addFirst(seg.put("text", JsStr(rest)))
                        keptChars += rest.length
                    }
                    budget = 0
                }
            }
            kept = JsArr.of(list)
            keptCount = list.size
        }
        val flag = serverFlag || over || keptChars >= maxChars || keptCount >= maxSegments
        if (kept === segments && flag == serverFlag) return command
        return command.with("segments" to kept, "outputTruncated" to JsBool.of(flag))
    }
}
