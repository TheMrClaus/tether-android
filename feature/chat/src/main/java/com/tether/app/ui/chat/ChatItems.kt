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

    /** T6.2: spaced by `space-sm` (inside an activity group or a turn's rich details), not the turn gap. */
    val tight: Boolean get() = false

    /** v115: the "Load N earlier turns" key standing in for the trimmed leading turns. */
    data class LoadEarlier(val count: Int) : ChatItem {
        override val key: String get() = LOAD_EARLIER_KEY
        override val startsGroup: Boolean get() = true
    }

    data class Continuation(val turnId: String, override val startsGroup: Boolean) : ChatItem {
        override val key: String get() = "$turnId/continuation"
    }

    /**
     * A user / agent / thinking / tool block; [timeLabel] is the bubble's `HH:MM` ("" = none).
     * T6.2: [raw] is the block's projection-tree object (tool cards read it with JS semantics);
     * [grouped] = a tool card inside an open activity group (`.chat-activity-body`).
     */
    data class Block(
        val turnId: String,
        val block: TurnBlock,
        val timeLabel: String,
        override val startsGroup: Boolean,
        val raw: JsObj? = null,
        val grouped: Boolean = false,
    ) : ChatItem {
        override val key: String get() = "$turnId/${block.blockId}"
        override val tight: Boolean get() = grouped
    }

    /**
     * T6.2: a run of consecutive tool calls collapsed into one summary line (chat-view.tsx
     * `ToolActivityGroup`). Open, its cards follow as [Block] rows with `grouped = true`, so a
     * long run stays lazy row by row. [defaultOpen]: running, or it holds a Codex file change.
     */
    data class ToolGroup(
        val turnId: String,
        val firstBlockId: String,
        val summary: String,
        val running: Boolean,
        val hasErrors: Boolean,
        val defaultOpen: Boolean,
        val open: Boolean,
        override val startsGroup: Boolean,
    ) : ChatItem {
        override val key: String get() = groupKey(turnId, firstBlockId)
    }

    /** T6.2: a Codex turn's plan card (`CodexRichTurnDetails`). */
    data class TurnPlan(val turnId: String, val plan: PlanView, override val startsGroup: Boolean, override val tight: Boolean) : ChatItem {
        override val key: String get() = "$turnId/plan"
    }

    /** T6.2: a Codex turn's aggregate "Turn changes" diff (`turn/diff/updated`). */
    data class TurnDiff(val turnId: String, val unifiedDiff: String, override val startsGroup: Boolean, override val tight: Boolean) : ChatItem {
        override val key: String get() = "$turnId/diff"
    }

    /** T6.2: one Codex review (`review_started` / `review_completed`). */
    data class TurnReview(val turnId: String, val review: ReviewView, override val startsGroup: Boolean, override val tight: Boolean) : ChatItem {
        override val key: String get() = "$turnId/review/${review.reviewId}"
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

        /** The web's React key `group-<first block id>`, scoped to its turn. */
        fun groupKey(turnId: String, firstBlockId: String): String = "$turnId/group/$firstBlockId"
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
 * continuation marker, then its blocks segmented like the web's `segmentBlocks` — every run of
 * consecutive tool calls without media becomes one [ChatItem.ToolGroup] (its cards follow only
 * while [groupOpen] says it is open), every other block its own row (thinking only when
 * [showThinking] and non-empty; a message only with text or the interrupted mark; AskUserQuestion's
 * card suppressed — the question card stands in). A hidden block still breaks a run, as on the web.
 * Then, for a rich Codex session ([richCodex]), the turn's plan, aggregate diff and reviews;
 * denials, the api-retry marker, pending approval/question cards (T6.3) and a non-ok outcome.
 */
internal fun buildChatItems(
    projection: SessionProjection,
    tree: JsObj?,
    showThinking: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
    richCodex: Boolean = false,
    groupOpen: (key: String, default: Boolean) -> Boolean = { _, default -> default },
): List<ChatItem> {
    val items = ArrayList<ChatItem>(projection.turnOrder.size * 3)
    val trimmed = trimmedTurnCount(projection)
    if (trimmed > 0) items.add(ChatItem.LoadEarlier(trimmed))
    val treeTurns = tree?.get("turnsById") as? JsObj
    projection.turnOrder.forEachIndexed { turnIndex, turnId ->
        if (turnIndex < trimmed) return@forEachIndexed
        val turn = projection.turnsById[turnId] ?: return@forEachIndexed
        val turnObj = treeTurns?.get(turnId) as? JsObj
        val treeBlocks = turnObj?.get("blocksById") as? JsObj
        fun rawFor(blockId: String): JsObj? =
            treeBlocks?.get(blockId) as? JsObj ?: turn.blocksById[blockId]?.asTree()
        var first = true
        fun opens(): Boolean = first.also { first = false }
        if (turn.continuation) items.add(ChatItem.Continuation(turnId, opens()))
        for (segment in segmentBlocks(turn.blocks, ::rawFor)) {
            when (segment) {
                is ActivitySegment.Group -> {
                    val raws = segment.blockIds.mapNotNull(::rawFor)
                    if (raws.isEmpty()) continue
                    val firstId = segment.blockIds.first()
                    val default = groupOpenByDefault(raws)
                    val open = groupOpen(ChatItem.groupKey(turnId, firstId), default)
                    items.add(
                        ChatItem.ToolGroup(
                            turnId = turnId,
                            firstBlockId = firstId,
                            summary = activitySummary(raws),
                            running = groupHasRunning(raws),
                            hasErrors = groupHasErrors(raws),
                            defaultOpen = default,
                            open = open,
                            startsGroup = opens(),
                        ),
                    )
                    if (open) {
                        segment.blockIds.forEach { blockId ->
                            val block = turn.blocksById[blockId] ?: return@forEach
                            items.add(ChatItem.Block(turnId, block, "", startsGroup = false, raw = rawFor(blockId), grouped = true))
                        }
                    }
                }
                is ActivitySegment.Single -> {
                    val blockId = segment.blockId
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
                    val raw = if (block.kind == Vocab.BLOCK_TOOL) rawFor(blockId) else null
                    items.add(ChatItem.Block(turnId, block, time, opens(), raw = raw))
                }
            }
        }
        if (richCodex && turnObj != null) {
            // `CodexRichTurnDetails`: one `.turnDetails` stack (space-sm apart) after the blocks.
            var inDetails = false
            fun tight(): Boolean = inDetails.also { inDetails = true }
            transcriptPlan(turnObj)?.takeIf(::planShows)?.let { items.add(ChatItem.TurnPlan(turnId, it, opens(), tight())) }
            turnUnifiedDiff(turnObj)?.takeIf { it.isNotEmpty() && !hasInlineFileChangeDiffs(turnObj) }?.let {
                items.add(ChatItem.TurnDiff(turnId, it, opens(), tight()))
            }
            reviews(turnObj).forEach { items.add(ChatItem.TurnReview(turnId, it, opens(), tight())) }
        }
        turn.permissionDenials.forEachIndexed { index, denial -> items.add(ChatItem.Denial(turnId, index, denial, opens())) }
        if (turn.apiRetry != null && turn.status != Vocab.TURN_DONE) items.add(ChatItem.Retry(turn, opens()))
        turn.pendingApprovals.values.forEach { items.add(ChatItem.Approval(turnId, it, opens())) }
        turn.pendingQuestions.values.forEach { items.add(ChatItem.Question(turnId, it, opens())) }
        if (turn.outcome != null && turn.outcome != Vocab.OUTCOME_OK) items.add(ChatItem.Outcome(turn, opens()))
    }
    return items
}
