package com.tether.app.ui.chat

import androidx.compose.runtime.Immutable
import com.tether.app.protocol.helpers.MessageTime
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.Vocab
import com.tether.app.protocol.tree.JsArr
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

    /**
     * T6.3: a permission denial, placed after the call it refused (denial-target-model.mjs
     * placeDenials): [nested] inside an open activity group, else in its turn; [turnId] null for a
     * homeless one below the transcript. [ordinal] keeps a repeated toolId's key unique.
     */
    data class Denial(
        val turnId: String?,
        val denial: DenialView,
        val target: DenialTarget?,
        val run: RunRef?,
        val nested: Boolean,
        override val startsGroup: Boolean,
        override val tight: Boolean = false,
        val ordinal: Int = 0,
    ) : ChatItem {
        override val key: String
            get() = (if (turnId != null) "$turnId/denial/" else "late-denial/") + denial.toolId + (if (ordinal > 0) "#$ordinal" else "")
    }

    /** T6.3: a v104 answered-question record, in its AskUserQuestion slot or at its turn's tail. */
    data class Answered(val turnId: String, val answered: AnsweredView, override val startsGroup: Boolean) : ChatItem {
        override val key: String get() = "$turnId/answered/${answered.requestId}"
    }

    data class Retry(val turn: TurnProjection, override val startsGroup: Boolean) : ChatItem {
        override val key: String get() = "${turn.turnId}/retry"
    }

    /** T6.3: the active turn's pending approval, below the transcript (a `.chat-scroll` child). */
    data class Approval(val approval: ApprovalView) : ChatItem {
        // Round 3 (N1): the identity rides in the lazy key, so a request re-raised under the same id
        // with other content is a NEW row and never inherits the old row's saved slot.
        override val key: String get() = "approval/${approval.requestId}/${approval.contentFp}"
        override val startsGroup: Boolean get() = true
    }

    /** T6.3: the active turn's pending question; [answered] when an answer is already on record. */
    data class Question(val question: QuestionRequestView, val answered: Boolean) : ChatItem {
        override val key: String get() = "question/${question.requestId}/${question.contentFp}"
        override val startsGroup: Boolean get() = true
    }

    data class Outcome(val turn: TurnProjection, override val startsGroup: Boolean) : ChatItem {
        override val key: String get() = "${turn.turnId}/outcome"
    }

    /** T6.4: a finished background `!` command, anchored where it was launched (a `.chat-scroll` child). */
    data class BgCommand(val command: BackgroundCommandView) : ChatItem {
        override val key: String get() = "bg-${command.commandId}"
        override val startsGroup: Boolean get() = true
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
 * denials, the api-retry marker and a non-ok outcome.
 *
 * T6.3: a permission denial follows the call it refused (placeDenials; inside an open group, after
 * its block), else trails its turn; an answered AskUserQuestion shows its record in the tool's slot
 * (or at the turn's tail when the block is gone). Below every turn: the homeless denials, then the
 * ACTIVE turn's pending approvals (unless [showApprovals] is off: `interactiveApprovals === false`)
 * and questions, as chat-view.tsx:3544-3662 orders them.
 */
internal fun buildChatItems(
    projection: SessionProjection,
    tree: JsObj?,
    showThinking: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
    richCodex: Boolean = false,
    groupOpen: (key: String, default: Boolean) -> Boolean = { _, default -> default },
    showApprovals: Boolean = true,
    /** I-1: the client's key for this session (the cards' identity); null = the tree's own id (tests). */
    consentSessionId: String? = null,
): List<ChatItem> {
    val items = ArrayList<ChatItem>(projection.turnOrder.size * 3)
    val trimmed = trimmedTurnCount(projection)
    if (trimmed > 0) items.add(ChatItem.LoadEarlier(trimmed))
    // T6.3: the cards read the tree (the typed projection's when there is none).
    val state = cardTree(projection, tree)
    val placement = placeDenials(state)
    val runs by lazy(LazyThreadSafetyMode.NONE) { collectSubagentRuns(state) }
    val denialKeys = HashMap<String, Int>()
    fun runFor(d: DenialView): RunRef? =
        if (!d.subagent) null else runForToolId(runs, d.toolId)?.let { RunRef(it.runId, it.title, d.toolId) }
    fun denialItem(turnId: String?, turnObj: JsObj?, d: DenialView, nested: Boolean, startsGroup: Boolean, tight: Boolean): ChatItem.Denial {
        val base = (if (turnId != null) "$turnId/denial/" else "late-denial/") + d.toolId
        val ordinal = denialKeys.getOrDefault(base, 0).also { denialKeys[base] = it + 1 }
        val input = if (turnId != null) deniedToolInput(turnObj, d.toolId) else lateDenialToolInput(state, d.toolId)
        return ChatItem.Denial(turnId, d, denialTarget(input), runFor(d), nested, startsGroup, tight, ordinal)
    }
    val treeTurns = tree?.get("turnsById") as? JsObj
    val stateTurns = state["turnsById"] as? JsObj
    // T6.4 (chat-view.tsx:3480-3528): finished background commands join the turns by launch time.
    val finishedBg = finishedBackgroundCommands(state)
    var bgIndex = 0
    var lastTs = 0.0
    fun flushBg(ts: Double) {
        while (bgIndex < finishedBg.size && finishedBg[bgIndex].startedAt <= ts) items.add(ChatItem.BgCommand(finishedBg[bgIndex++]))
    }
    projection.turnOrder.forEachIndexed { turnIndex, turnId ->
        if (turnIndex < trimmed) return@forEachIndexed
        val turn = projection.turnsById[turnId] ?: return@forEachIndexed
        val turnObj = treeTurns?.get(turnId) as? JsObj
        // Monotonic clamp: a turn with no startedAt inherits the last known stamp.
        val startedAt = (turnObj?.get("startedAt") as? JsNum)?.value ?: turn.startedAt?.toDouble()
        lastTs = maxOf(lastTs, startedAt ?: lastTs)
        flushBg(lastTs)
        val treeBlocks = turnObj?.get("blocksById") as? JsObj
        fun rawFor(blockId: String): JsObj? =
            treeBlocks?.get(blockId) as? JsObj ?: turn.blocksById[blockId]?.asTree()
        var first = true
        fun opens(): Boolean = first.also { first = false }
        val cardTurn = stateTurns?.get(turnId) as? JsObj
        val turnDenials = placement.byTurn[turnId]
        val answeredList = (cardTurn?.get("answeredQuestions") as? JsArr)?.mapNotNull { (it as? JsObj)?.let(::answeredView) }.orEmpty()
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
                            turnDenials?.byBlock?.get(blockId)?.forEach { d ->
                                items.add(denialItem(turnId, cardTurn, d, nested = true, startsGroup = false, tight = true))
                            }
                        }
                    }
                }
                is ActivitySegment.Single -> {
                    val blockId = segment.blockId
                    val block = turn.blocksById[blockId] ?: continue
                    if (block.kind == Vocab.BLOCK_TOOL && block.name == "AskUserQuestion") {
                        // chat-view.tsx:3383-3393: the answer record takes the suppressed card's slot.
                        answeredList.firstOrNull { it.toolId == blockId }?.let { items.add(ChatItem.Answered(turnId, it, opens())) }
                        continue
                    }
                    val keep = when (block.kind) {
                        Vocab.BLOCK_THINKING -> showThinking && !block.text.isNullOrEmpty()
                        Vocab.BLOCK_MESSAGE -> !block.text.isNullOrEmpty() || block.aborted == true
                        else -> true
                    }
                    if (!keep) {
                        turnDenials?.byBlock?.get(blockId)?.forEach { d -> items.add(denialItem(turnId, cardTurn, d, nested = false, startsGroup = opens(), tight = false)) }
                        continue
                    }
                    val time = when (block.kind) {
                        Vocab.BLOCK_USER_MESSAGE -> blockTimeLabel(tree, turn, blockId, zone)
                        Vocab.BLOCK_MESSAGE -> if (block.done == true) blockTimeLabel(tree, turn, blockId, zone) else ""
                        else -> ""
                    }
                    val raw = if (block.kind == Vocab.BLOCK_TOOL) rawFor(blockId) else null
                    items.add(ChatItem.Block(turnId, block, time, opens(), raw = raw))
                    turnDenials?.byBlock?.get(blockId)?.forEach { d -> items.add(denialItem(turnId, cardTurn, d, nested = false, startsGroup = opens(), tight = false)) }
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
        turnDenials?.trailing?.forEach { d -> items.add(denialItem(turnId, cardTurn, d, nested = false, startsGroup = opens(), tight = false)) }
        val blocksById = cardTurn?.get("blocksById") as? JsObj
        answeredList.filter { blocksById?.has(it.toolId) != true }.forEach { items.add(ChatItem.Answered(turnId, it, opens())) }
        if (turn.apiRetry != null && turn.status != Vocab.TURN_DONE) items.add(ChatItem.Retry(turn, opens()))
        if (turn.outcome != null && turn.outcome != Vocab.OUTCOME_OK) items.add(ChatItem.Outcome(turn, opens()))
    }
    flushBg(Double.POSITIVE_INFINITY) // any command launched after the last turn
    placement.homeless.forEach { d -> items.add(denialItem(null, null, d, nested = false, startsGroup = true, tight = false)) }
    if (showApprovals) pendingApprovals(state, consentSessionId).forEach { items.add(ChatItem.Approval(it)) }
    val answeredIds = answeredRequestIds(state)
    pendingQuestions(state, consentSessionId).forEach { items.add(ChatItem.Question(it, answered = it.requestId in answeredIds)) }
    return items
}
