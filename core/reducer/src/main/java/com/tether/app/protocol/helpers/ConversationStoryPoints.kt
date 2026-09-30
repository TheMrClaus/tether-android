package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.isJsWhitespace
import com.tether.app.protocol.fold.isNullish
import com.tether.app.protocol.fold.jsToString
import com.tether.app.protocol.fold.jsTrim
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsValue
import java.time.ZoneId

/**
 * T2.2: faithful port of lib/conversation-story-points.ts over the v128 projection tree (the
 * reducer's JsObj). The web's limits are [PROMPT_MAX] = 220 / [REPLY_MAX] = 260; they are
 * parameters: the Android timeline (T6.5, feature/chat TimelineModel) passes its deliberate
 * wider-bubble 270/320 (owner decision, logged on T2.2).
 */
object ConversationStoryPoints {

    /** One operator prompt: [ts] is the turn's `startedAt` (null for a pre-v37 turn). */
    data class StoryPoint(val turnId: String, val blockId: String, val prompt: String, val reply: String, val ts: Double?)

    // lib/conversation-story-points.ts:21
    const val PROMPT_MAX = 220
    const val REPLY_MAX = 260

    // lib/conversation-story-points.ts:24 — trim, collapse `\s+` (JS whitespace) to one space, ellipsize.
    fun truncate(text: String, max: Int): String {
        val clean = collapseWhitespace(jsTrim(text))
        if (clean.length <= max) return clean
        return clean.substring(0, maxOf(0, max - 1)) + "…"
    }

    private fun collapseWhitespace(s: String): String {
        val out = StringBuilder(s.length)
        var inRun = false
        for (c in s) {
            if (isJsWhitespace(c)) {
                if (!inRun) out.append(' ')
                inRun = true
            } else {
                out.append(c)
                inRun = false
            }
        }
        return out.toString()
    }

    // lib/conversation-story-points.ts:30
    private fun promptFromBlock(block: JsValue, promptMax: Int): String {
        val text = block["text"]
        if (truthy(text)) return truncate(jsToString(text), promptMax)
        val attachments = block["attachments"] as? JsArr
        if (block["kind"].isStr("user_message") && attachments != null && attachments.isNotEmpty()) {
            // `.map((attachment) => attachment.name).join(", ")` — join prints null/undefined as "".
            val names = attachments.joinToString(", ") { a -> a["name"].let { if (isNullish(it)) "" else jsToString(it) } }
            return truncate(names, promptMax)
        }
        return ""
    }

    // lib/conversation-story-points.ts:47 — one jump point per user message; continuation turns skipped.
    fun storyPointsFromSession(
        state: JsValue?,
        promptMax: Int = PROMPT_MAX,
        replyMax: Int = REPLY_MAX,
    ): List<StoryPoint> {
        if (!truthy(state)) return emptyList()
        val turnOrder = state["turnOrder"] as? JsArr ?: return emptyList()
        if (turnOrder.isEmpty()) return emptyList()

        val points = ArrayList<StoryPoint>()
        val turnsById = state["turnsById"] as? JsObj ?: JsObj.EMPTY
        for (turnIdValue in turnOrder) {
            val turnId = jsToString(turnIdValue)
            val turn = turnsById[turnId]
            if (!truthy(turn) || truthy(turn["continuation"])) continue

            val blocksById = turn["blocksById"] as? JsObj ?: JsObj.EMPTY
            val blocks = (turn["blocks"] as? JsArr ?: JsArr.EMPTY).mapNotNull { id -> blocksById[jsToString(id)]?.takeIf { truthy(it) } }

            for (index in blocks.indices) {
                val block = blocks[index]
                if (!block["kind"].isStr("user_message")) continue

                var reply = ""
                for (cursor in index + 1 until blocks.size) {
                    val candidate = blocks[cursor]
                    if (candidate["kind"].isStr("user_message")) break
                    if (candidate["kind"].isStr("message") && truthy(candidate["text"])) {
                        reply = truncate(jsToString(candidate["text"]), replyMax)
                        break
                    }
                }

                points.add(
                    StoryPoint(
                        turnId = turnId,
                        blockId = jsToString(block["blockId"]),
                        prompt = promptFromBlock(block, promptMax),
                        reply = reply,
                        ts = (turn["startedAt"] as? JsNum)?.value,
                    ),
                )
            }
        }
        return points
    }

    // lib/conversation-story-points.ts:88 — "HH:MM" in the viewer's zone; "" for a null ts.
    fun storyPointTimeLabel(ts: Double?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (ts == null) return ""
        val date = jsDate(ts, zone) ?: return "NaN:NaN"
        return "${pad2(date.hour)}:${pad2(date.minute)}"
    }
}
