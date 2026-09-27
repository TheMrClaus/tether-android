package com.tether.app.ui.chat

import androidx.compose.runtime.Immutable
import com.tether.app.protocol.helpers.MessageTime
import com.tether.app.protocol.model.PendingApproval
import com.tether.app.protocol.model.PendingQuestion
import com.tether.app.protocol.model.PermissionDenialProjection
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.Vocab
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsValue
import java.time.ZoneId

/**
 * One transcript row of the chat view, keyed stably for the LazyColumn (`<turnId>/<blockId>`,
 * the web's React keys): a streaming delta replaces ONE block, every other row keeps an equal
 * item — the adapter memoizes untouched blocks by identity — so Compose skips it (the web's
 * `memo(BlockView)` on block identity, chat-view.tsx:533-540).
 *
 * [startsGroup]: the row opens a `.chat-turn` (or is a direct `.chat-scroll` child such as the
 * load-earlier key), so it is spaced by the scroll gap; other rows by the turn gap.
 */
@Immutable
internal sealed interface ChatItem {
    val key: String
    val startsGroup: Boolean

    /** v115: the "Load N earlier turns" key standing in for the trimmed leading turns. */
    data class LoadEarlier(val count: Int) : ChatItem {
        override val key: String get() = LOAD_EARLIER_KEY
        override val startsGroup: Boolean get() = true
    }

    data class Continuation(val turnId: String, override val startsGroup: Boolean) : ChatItem {
        override val key: String get() = "$turnId/continuation"
    }

    /** A user / agent / thinking / tool block; [timeLabel] is the bubble's `HH:MM` ("" = none). */
    data class Block(
        val turnId: String,
        val block: TurnBlock,
        val timeLabel: String,
        override val startsGroup: Boolean,
    ) : ChatItem {
        override val key: String get() = "$turnId/${block.blockId}"
    }

    data class Denial(val turnId: String, val index: Int, val denial: PermissionDenialProjection, override val startsGroup: Boolean) : ChatItem {
        override val key: String get() = "$turnId/denial/$index"
    }

    data class Retry(val turn: TurnProjection, override val startsGroup: Boolean) : ChatItem {
        override val key: String get() = "${turn.turnId}/retry"
    }

    data class Approval(val turnId: String, val approval: PendingApproval, override val startsGroup: Boolean) : ChatItem {
        override val key: String get() = "$turnId/approval/${approval.requestId}"
    }

    data class Question(val turnId: String, val question: PendingQuestion, override val startsGroup: Boolean) : ChatItem {
        override val key: String get() = "$turnId/question/${question.requestId}"
    }

    data class Outcome(val turn: TurnProjection, override val startsGroup: Boolean) : ChatItem {
        override val key: String get() = "${turn.turnId}/outcome"
    }

    companion object {
        const val LOAD_EARLIER_KEY = "load-earlier"
    }
}

/**
 * v115 bounded snapshots (chat-view.tsx:3489-3500): how many LEADING turns arrived trimmed —
 * `blocks` and `blocksById` both empty. Trimmed turns are always a prefix, so the count stops at
 * the first turn with content (or a turn id with no turn).
 */
internal fun trimmedTurnCount(projection: SessionProjection): Int {
    var count = 0
    for (turnId in projection.turnOrder) {
        val turn = projection.turnsById[turnId]
        if (turn != null && turn.blocks.isEmpty() && turn.blocksById.isEmpty()) count++ else break
    }
    return count
}

/**
 * The `fetch-turns` range the load-earlier key asks for: `fetchTurns(session.id, 0, trimmedCount)`
 * (chat-view.tsx:3503) — 0-based, exclusive end (server.mjs:8757-8760).
 */
internal fun loadEarlierRange(trimmedCount: Int): IntRange = 0 until trimmedCount

/** "Load 3 earlier turns" / "Load 1 earlier turn" (chat-view.tsx:3512). */
internal fun loadEarlierLabel(count: Int): String = "Load $count earlier ${if (count == 1) "turn" else "turns"}"

/**
 * `messageClockTime(block.ts ?? turnStartedAt)` (chat-view.tsx:557, 679): the block's journal
 * stamp from the projection tree, else the turn's `startedAt` (v37).
 */
internal fun blockTimeLabel(tree: JsObj?, turn: TurnProjection, blockId: String, zone: ZoneId): String {
    val turnObj = (tree?.get("turnsById") as? JsObj)?.get(turn.turnId) as? JsObj
    val blockObj = (turnObj?.get("blocksById") as? JsObj)?.get(blockId) as? JsObj
    val ts: JsValue? = blockObj?.get("ts")
    val stamp: JsValue? = when {
        ts is JsNum -> ts
        ts != null && ts !is JsNull -> ts // a non-number `ts` is not nullish: "" / NaN:NaN like the web
        else -> turnObj?.get("startedAt") ?: turn.startedAt?.let { JsNum(it.toDouble()) }
    }
    return MessageTime.messageClockTime(stamp, zone)
}

/**
 * The transcript rows for [projection], turn by turn in `turnOrder` (chat-view.tsx:3351-3527):
 * trimmed leading turns collapse into one [ChatItem.LoadEarlier]; each turn yields its
 * continuation marker, its blocks (thinking only when [showThinking] and non-empty; a message only
 * with text or the interrupted mark; AskUserQuestion's tool card suppressed — the question card
 * stands in), denials, the api-retry marker, pending approval/question cards (T6.3) and a non-ok
 * outcome. Tool rows render through T6.2's card; grouping them into activity summaries is T6.2's.
 */
internal fun buildChatItems(
    projection: SessionProjection,
    tree: JsObj?,
    showThinking: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ChatItem> {
    val items = ArrayList<ChatItem>(projection.turnOrder.size * 3)
    val trimmed = trimmedTurnCount(projection)
    if (trimmed > 0) items.add(ChatItem.LoadEarlier(trimmed))
    projection.turnOrder.forEachIndexed { turnIndex, turnId ->
        if (turnIndex < trimmed) return@forEachIndexed
        val turn = projection.turnsById[turnId] ?: return@forEachIndexed
        var first = true
        fun opens(): Boolean = first.also { first = false }
        if (turn.continuation) items.add(ChatItem.Continuation(turnId, opens()))
        for (blockId in turn.blocks) {
            val block = turn.blocksById[blockId] ?: continue
            val keep = when (block.kind) {
                Vocab.BLOCK_THINKING -> showThinking && !block.text.isNullOrEmpty()
                Vocab.BLOCK_MESSAGE -> !block.text.isNullOrEmpty() || block.aborted == true
                Vocab.BLOCK_TOOL -> block.name != "AskUserQuestion"
                else -> true
            }
            if (!keep) continue
            val time = when (block.kind) {
                Vocab.BLOCK_USER_MESSAGE -> blockTimeLabel(tree, turn, blockId, zone)
                Vocab.BLOCK_MESSAGE -> if (block.done == true) blockTimeLabel(tree, turn, blockId, zone) else ""
                else -> ""
            }
            items.add(ChatItem.Block(turnId, block, time, opens()))
        }
        turn.permissionDenials.forEachIndexed { index, denial -> items.add(ChatItem.Denial(turnId, index, denial, opens())) }
        if (turn.apiRetry != null && turn.status != Vocab.TURN_DONE) items.add(ChatItem.Retry(turn, opens()))
        turn.pendingApprovals.values.forEach { items.add(ChatItem.Approval(turnId, it, opens())) }
        turn.pendingQuestions.values.forEach { items.add(ChatItem.Question(turnId, it, opens())) }
        if (turn.outcome != null && turn.outcome != Vocab.OUTCOME_OK) items.add(ChatItem.Outcome(turn, opens()))
    }
    return items
}
