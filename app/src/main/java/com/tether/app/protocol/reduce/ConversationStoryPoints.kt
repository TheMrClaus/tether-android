package com.tether.app.protocol.reduce

import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.Vocab
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Pure derivation of conversation jump points from a SessionProjection —
 * Kotlin port of aidash/lib/conversation-story-points.ts.
 *
 * A jump point represents one message sent by the operator. It is anchored to
 * the rendered user_message block and carries the first assistant message that
 * follows it, so the timeline preview can show a compact prompt/reply pair.
 *
 * Continuation turns are intentionally omitted: the rail is an index of operator
 * prompts, not every event emitted by the agent.
 *
 * This module is deliberately pure: it reads only the projection shape it
 * needs (turnOrder, turnsById, per-turn blocks + blocksById) and returns a list
 * of points. No I/O, no Android types. Tested in isolation.
 */
data class StoryPoint(
    val turnId: String,
    val blockId: String,
    val prompt: String,
    val reply: String,
    val ts: Long?,
)

private const val PROMPT_MAX = 220
private const val REPLY_MAX = 260

private fun truncate(text: String, max: Int): String {
    val clean = text.trim().replace(Regex("\\s+"), " ")
    return if (clean.length <= max) clean else clean.take(max - 1) + "\u2026"
}

private fun promptFromBlock(block: TurnBlock): String {
    val text = block.text
    if (!text.isNullOrEmpty()) return truncate(text, PROMPT_MAX)
    if (block.kind == Vocab.BLOCK_USER_MESSAGE) {
        val attachments = block.attachments
        if (!attachments.isNullOrEmpty() && attachments.isNotEmpty()) {
            return truncate(attachments.joinToString(", ") { it.name }, PROMPT_MAX)
        }
    }
    return ""
}

/**
 * Derive one jump point for every user message. The reply preview is the first
 * assistant `message` block after that user message and before the next user
 * message in the same turn. Continuation turns are skipped.
 */
fun storyPointsFromSession(state: SessionProjection?): List<StoryPoint> {
    if (state == null || state.turnOrder.isEmpty()) return emptyList()

    val points = mutableListOf<StoryPoint>()

    for (turnId in state.turnOrder) {
        val turn: TurnProjection = state.turnsById[turnId] ?: continue
        if (turn.continuation) continue

        val blocks: List<TurnBlock> = turn.blocks
            .mapNotNull { id -> turn.blocksById[id] }

        var index = 0
        while (index < blocks.size) {
            val block = blocks[index]
            if (block.kind != Vocab.BLOCK_USER_MESSAGE) {
                index += 1
                continue
            }

            var reply = ""
            var cursor = index + 1
            while (cursor < blocks.size) {
                val candidate = blocks[cursor]
                if (candidate.kind == Vocab.BLOCK_USER_MESSAGE) break
                if (candidate.kind == Vocab.BLOCK_MESSAGE && !candidate.text.isNullOrEmpty()) {
                    reply = truncate(candidate.text, REPLY_MAX)
                    break
                }
                cursor += 1
            }

            points += StoryPoint(
                turnId = turnId,
                blockId = block.blockId,
                prompt = promptFromBlock(block),
                reply = reply,
                ts = turn.startedAt,
            )
            index += 1
        }
    }

    return points
}

/**
 * Format a story-point timestamp as a short HH:MM label for assistive text.
 * Returns "" when ts is null (a pre-v37 turn with no recorded start).
 */
fun storyPointTimeLabel(ts: Long?): String {
    if (ts == null) return ""
    val cal = Calendar.getInstance(TimeZone.getDefault(), Locale.getDefault())
    cal.timeInMillis = ts
    val hours = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    val minutes = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
    return "$hours:$minutes"
}