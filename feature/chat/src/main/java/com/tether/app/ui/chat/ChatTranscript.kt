package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.CompositionLocalProvider
import com.tether.app.ui.text.CopyNoticeHost
import com.tether.app.ui.text.CopyNotices
import com.tether.app.ui.text.LocalCopyNotices
import com.tether.app.ui.text.SafeCopyClipboard
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.tether.app.ui.theme.LocalReducedMotion
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.Vocab
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Observes row compositions (test seam for the long-transcript performance check): called with
 * the row's key every time a transcript row actually (re)composes. Null in production.
 */
internal val LocalChatRowObserver = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** `.chat-scroll` padding and gaps for one skin × layout class (globals.css 4802-4827, 8457, 11235, 11930; studio.css 370-371, 453). */
internal class TranscriptSpacing(val padding: PaddingValues, val scrollGap: Dp, val turnGap: Dp)

internal fun transcriptSpacing(t: TetherTokens, phone: Boolean): TranscriptSpacing {
    val css = t.css
    return if (phone) {
                    TranscriptSpacing(PaddingValues(horizontal = 16.dp, vertical = 24.dp), scrollGap = 22.4.dp, turnGap = 16.dp)
                } else {
                    TranscriptSpacing(PaddingValues(horizontal = 32.dp, vertical = 32.dp), scrollGap = 28.dp, turnGap = 16.dp)
                }
}

/**
 * The transcript (chat-view.tsx `.chat-scroll`, 3317-3530): one lazy list of [ChatItem] rows with
 * stable keys, so a 5k-block session composes only what is on screen and a streaming delta
 * recomposes only the row it touched.
 *
 * Follow mode is the web's (chat-view.tsx:1825-1907): the view sticks to the newest content until
 * the reader scrolls UP by hand (a user drag — never inferred from position), and only the
 * "Jump to latest" key re-engages it. A bounded snapshot (v115) shows "Load N earlier turns" at
 * the top; tapping it asks for exactly those turns ([onFetchTurns] `(0, trimmedCount)`).
 */
@Composable
internal fun ChatTranscript(
    projection: SessionProjection,
    tree: JsObj?,
    showThinking: Boolean,
    onFetchTurns: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    roster: (@Composable () -> Unit)? = null,
    zone: ZoneId = ZoneId.systemDefault(),
    listState: LazyListState = rememberLazyListState(),
    showTimeline: Boolean = true,
    /** T5.3: the in-chat find over this transcript (null: the bar is closed). */
    find: TranscriptFind? = null,
    richCodex: Boolean = false,
    richOpencode: Boolean = false,
    /**
     * T6.3: what the approval and question cards may do (read through [LocalConsent]). The default
     * is fail-closed: every card renders disabled with "Connect to answer" and nothing is sent.
     */
    consent: ConsentActions = ConsentActions.Unavailable,
    /** T6.6: what the notices' X and the limit card may do (fail-closed default: nothing is sent). */
    notices: NoticeActions = NoticeActions.Unavailable,
    /** T6.3: `capabilities?.interactiveApprovals !== false` (chat-view.tsx:1929). */
    showApprovals: Boolean = true,
    /** T6.4: open a finished background command's output (its transcript chip). Keep it stable. */
    onOpenCommand: (commandId: String) -> Unit = {},
    /**
     * T6.5 (T13.2's saved-copy rule): true only while this copy is live ([ChatFreshness.isLive]).
     * Otherwise the timeline never says a reply is on its way.
     */
    liveCopy: Boolean = false,
    /** ta-coik.19: this session's unresolved and given-up sends, drawn at the foot of the list. */
    sends: SendBubbles = SendBubbles.None,
) {
    // Round 3: the card store in scope (the chat screen's), or one saved here.
    val cardStates = rememberCardStates()
    CompositionLocalProvider(LocalConsent provides consent, LocalCardStates provides cardStates, LocalNoticeActions provides notices) {
        ChatTranscriptBody(projection, tree, showThinking, onFetchTurns, modifier, roster, zone, listState, showTimeline, find, richCodex, richOpencode, showApprovals, consent.sessionId, onOpenCommand, liveCopy, sends)
    }
}

@Composable
private fun ChatTranscriptBody(
    projection: SessionProjection,
    tree: JsObj?,
    showThinking: Boolean,
    onFetchTurns: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier,
    roster: (@Composable () -> Unit)?,
    zone: ZoneId,
    listState: LazyListState,
    showTimeline: Boolean,
    find: TranscriptFind?,
    richCodex: Boolean,
    richOpencode: Boolean,
    showApprovals: Boolean,
    consentSessionId: String?,
    onOpenCommand: (String) -> Unit,
    liveCopy: Boolean,
    sends: SendBubbles,
) {
    val t = LocalTetherTokens.current
    val phone = currentLayoutClass() == TetherLayoutClass.Phone
    val spacing = transcriptSpacing(t, phone)
    // T6.2: the reader's activity-group toggles, each remembered with the default it overrode
    // (a `<details open={default}>` resets when its default changes, as React drives it). The
    // reset is applied where the default is READ (GroupToggles.resolve, while the rows are built),
    // so a toggle whose default changed is gone before any later build can see the default flip back.
    val groupToggles = rememberSaveable(saver = GroupToggles.Saver) { GroupToggles() }
    val items = remember(projection, tree, showThinking, zone, richCodex, groupToggles.version, showApprovals, consentSessionId) {
        buildChatItems(projection, tree, showThinking, zone, richCodex, groupToggles::resolve, showApprovals, consentSessionId)
    }
    // L2: lazy keys must be unique or Compose throws; a repeated block id, run id or a command id
    // that spells another row's key gets an ordinal (the first keeps its own key).
    val lazyKeys = remember(items) { uniqueLazyKeys(items.map { it.key }) }
    val onToggleGroup: (ChatItem.ToolGroup) -> Unit = remember(groupToggles) { { group -> groupToggles.toggle(group) } }
    val toolRender = remember(richCodex, richOpencode, showThinking) { ToolRenderFlags(richCodex, richOpencode, showThinking) }
    val leading = if (roster != null) 1 else 0

    // Story points: the conversation timeline rail (T6.5) indexes operator prompts. They come from
    // the tree (T2.2's faithful port of lib/conversation-story-points.ts) at the owner's limits.
    val storyPoints = remember(tree) { TimelineModel.points(tree) }
    val storyPointIndex = remember(storyPoints) {
        storyPoints.withIndex().associate { (i, sp) -> "${sp.turnId}:${sp.blockId}" to i }
    }
    val storyPointToLazyIndex = remember(items, storyPointIndex, leading) {
        val m = HashMap<Int, Int>()
        items.forEachIndexed { i, item ->
            if (item is ChatItem.Block && item.block.kind == Vocab.BLOCK_USER_MESSAGE) {
                storyPointIndex["${item.turnId}:${item.block.blockId}"]?.let { sp -> m[sp] = i + leading }
            }
        }
        m
    }
    val itemKeyToSpIndex = remember(items, storyPointIndex) {
        val m = HashMap<Any, Int>()
        items.forEach { item ->
            if (item is ChatItem.Block && item.block.kind == Vocab.BLOCK_USER_MESSAGE) {
                storyPointIndex["${item.turnId}:${item.block.blockId}"]?.let { sp -> m[item.key] = sp }
            }
        }
        m
    }
    val hasTimeline = showTimeline && storyPoints.isNotEmpty()
    // conversation-timeline.module.css: the desktop rail docks left in the stage's gutter, the
    // mobile one right; here the layout class decides (the expanded layout is the web's desktop).
    val timelineSide = if (phone) TimelineSide.Right else TimelineSide.Left

    var sticky by remember { mutableStateOf(true) }
    val followGuard = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // A hand drag moving the content down = reading upward: stop following.
                if (source == NestedScrollSource.UserInput && available.y > 0f) sticky = false
                return Offset.Zero
            }
        }
    }
    // ta-coik.35: a scroll that is not a hand drag (TalkBack moving its focus to a row that is off screen
    // or partly cut, a switch control, a bring-into-view) never reaches the guard above, which sees only
    // pointer input. A reader moving up through the transcript leaves follow mode exactly as a hand drag
    // does. The follow code's own pins are exempt: the flag stays up for one frame after a pin, so the
    // collector below (which runs on the next snapshot apply) cannot read a pin as the reader's move.
    val ownScroll = remember { booleanArrayOf(false) }
    suspend fun pinToEnd(index: Int) {
        ownScroll[0] = true
        try {
            listState.scrollToItem(index, scrollOffset = Int.MAX_VALUE / 2)
            withFrameNanos { }
        } finally {
            ownScroll[0] = false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.lastScrolledBackward }
            .distinctUntilChanged()
            .collect { backward -> if (backward && !ownScroll[0]) sticky = false }
    }
    // ta-coik.19: the send bubbles are the list's last rows; following the newest content follows them.
    val sendRows = sends.pending.size + sends.failed.size
    val lastIndex = items.size + leading + sendRows - 1
    LaunchedEffect(items, sticky, sendRows) {
        if (sticky && lastIndex >= 0) pinToEnd(lastIndex)
    }
    // ta-coik.33: the newest row can grow after the rows were built (a card, an image or a long reply
    // laid out late), and the well can shrink under it (the keyboard, a banner): while following, the
    // view stays pinned to its bottom. Keyed on the last row's measured size and the viewport only, so
    // a scroll that is not a hand drag (TalkBack, a programmatic move) is never pulled back.
    LaunchedEffect(listState) {
        var before: LastRowLayout? = null
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 }
            LastRowLayout(info.viewportSize, last?.index, last?.size)
        }
            .distinctUntilChanged()
            .collect { now ->
                val was = before
                before = now
                if (was == null) return@collect
                val resized = now.viewport != was.viewport
                val grew = now.index != null && now.index == was.index && now.size != was.size
                if (!resized && !grew) return@collect
                // Never from inside the layout pass that reported the change, and never against a
                // scroll already under way: on the next frame, if still following and not at the end.
                withFrameNanos { }
                if (sticky && listState.canScrollForward && !listState.isScrollInProgress) {
                    val end = listState.layoutInfo.totalItemsCount - 1
                    if (end >= 0) pinToEnd(end)
                }
            }
    }

    // T5.3 chat-view.tsx:2066-2074: land on the active OCCURRENCE, centred. A jump stops the
    // follow mode (so the next streamed delta does not pull the view away from the match), brings
    // the row holding it on screen (a lazy row far away is not composed), waits for its text to
    // report the mark's bounds ([LocalFindActiveMark]), and centres them.
    val activeHit = find?.activeHit
    val activeKey = activeHit?.let { "${it.turnId}/${it.blockId}#${it.ordinal}" }
    val mark = remember(activeKey) { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    val viewport = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(activeKey) {
        val hit = activeHit ?: return@LaunchedEffect
        sticky = false
        val row = items.indexOfFirst { it is ChatItem.Block && it.turnId == hit.turnId && it.block.blockId == hit.blockId }
        if (row < 0) return@LaunchedEffect
        val index = row + leading
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
        var frames = 0
        while (mark[0] == null && frames++ < FIND_REPORT_FRAMES) withFrameNanos { }
        withFrameNanos { }
        val at = mark[0] ?: return@LaunchedEffect
        val box = viewport[0] ?: return@LaunchedEffect
        val delta = at.center.y - box.center.y
        if (reduced) listState.scrollBy(delta) else listState.animateScrollBy(delta)
    }
    val reportMark: (androidx.compose.ui.geometry.Rect) -> Unit = { mark[0] = it }

    val layoutPadding = PaddingValues(
        start = spacing.padding.calculateLeftPadding(LayoutDirection.Ltr) +
            if (hasTimeline && timelineSide == TimelineSide.Left) timelineColumnWidth(timelineSide) else 0.dp,
        end = spacing.padding.calculateRightPadding(LayoutDirection.Ltr) +
            if (hasTimeline && timelineSide == TimelineSide.Right) timelineColumnWidth(timelineSide) else 0.dp,
        top = spacing.padding.calculateTopPadding(),
        bottom = spacing.padding.calculateBottomPadding(),
    )

    val copyNotices = remember { CopyNotices() }
    Box(modifier.fillMaxSize().background(chatWellColor(t))) {
        CompositionLocalProvider(LocalFindActiveMark provides if (activeKey != null) reportMark else null, LocalCopyNotices provides copyNotices) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(followGuard)
                .onGloballyPositioned { viewport[0] = it.boundsInRoot() }
                .testTag("chat-transcript"),
            contentPadding = layoutPadding,
        ) {
            if (roster != null) {
                item(key = "subagent-roster", contentType = "roster") { roster() }
            }
            itemsIndexed(items, key = { index, _ -> lazyKeys[index] }, contentType = { _, item -> item.contentType() }) { index, item ->
                val gap = when {
                    index + leading == 0 -> 0.dp
                    item.startsGroup -> spacing.scrollGap
                    item.tight -> t.css.spaceSm
                    else -> spacing.turnGap
                }
                val marks = if (find != null && item is ChatItem.Block) {
                    findMarksFor(find.results, find.needle, find.activeHit, item.turnId, item.block.blockId)
                } else {
                    null
                }
                ChatRow(
                    item = item,
                    onFetchTurns = onFetchTurns,
                    modifier = Modifier.padding(top = gap),
                    find = marks,
                    toolRender = toolRender,
                    onToggleGroup = onToggleGroup,
                    onOpenCommand = onOpenCommand,
                    zone = zone,
                )
            }
            // chat-view.tsx 90fbb9f :3730-3737 (issue #135): unresolved and abandoned sends at the foot of
            // the transcript, so a send is never invisible while in flight, reconnecting, or lost.
            items(sends.pending, key = { "pending-send:${it.key}" }, contentType = { "pending-send" }) { row ->
                PendingSendBubble(row, Modifier.padding(top = if (items.size + leading == 0 && row === sends.pending.first()) 0.dp else spacing.scrollGap))
            }
            items(sends.failed, key = { "failed-send:${it.key}" }, contentType = { "failed-send" }) { row ->
                val first = items.size + leading == 0 && sends.pending.isEmpty() && row === sends.failed.first()
                FailedSendBubble(row, { sends.onDismiss(row.key) }, Modifier.padding(top = if (first) 0.dp else spacing.scrollGap))
            }
        }
        }

        if (hasTimeline) {
            ConversationTimeline(
                points = storyPoints,
                listState = listState,
                storyPointToLazyIndex = storyPointToLazyIndex,
                itemKeyToSpIndex = itemKeyToSpIndex,
                side = timelineSide,
                liveCopy = liveCopy,
                zone = zone,
                onScrolledUp = { sticky = false },
                modifier = Modifier.align(if (timelineSide == TimelineSide.Right) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft),
            )
        }

        if (!sticky) {
            val scope = rememberCoroutineScope()
            // `.chat-jump`: a round charcoal cap at the well's bottom-right (globals.css:4776-4800).
            TetherKey(
                onClick = {
                    sticky = true
                    scope.launch { if (lastIndex >= 0) pinToEnd(lastIndex) }
                },
                classes = KeyClasses.ChatJump,
                icon = TetherIcons.ArrowDown,
                iconSize = 18.dp,
                contentDescription = "Jump to latest",
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = t.css.spaceLg, bottom = t.css.spaceLg),
            )
        }

        // ta-blf r2: "N hidden control characters copied as ⟨U+…⟩", with "Copy raw".
        CopyNoticeHost(copyNotices, Modifier.align(Alignment.BottomCenter).padding(horizontal = t.css.spaceLg, vertical = t.css.spaceLg))
    }
}

/** ta-coik.33: what the follow mode watches of the layout: the viewport, and the newest row when it is on screen. */
private data class LastRowLayout(val viewport: androidx.compose.ui.unit.IntSize, val index: Int?, val size: Int?)

/** The transcript well: `--mineral-deep` (`:root .chat-frame`), Studio's `--graphite` (studio.css:369). */
internal fun chatWellColor(t: TetherTokens): androidx.compose.ui.graphics.Color =
    t.graphite

private fun ChatItem.contentType(): String = when (this) {
    is ChatItem.Block -> block.kind
    else -> this::class.java.simpleName
}

/** One transcript row. Skippable: an equal [item] (same block instance) does not recompose. */
@Composable
private fun ChatRow(
    item: ChatItem,
    onFetchTurns: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    find: FindMarks? = null,
    toolRender: ToolRenderFlags = ToolRenderFlags.Default,
    onToggleGroup: (ChatItem.ToolGroup) -> Unit = {},
    onOpenCommand: (String) -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val observer = LocalChatRowObserver.current
    if (observer != null) SideEffect { observer(item.key) }
    Box(modifier.fillMaxWidth()) {
        // T6.7: the transcript's words are selectable and copyable, as on the web, one row at a time:
        // a selection can never run across the transcript or into the header and composer (the
        // runaway selection the web fixed, globals.css:136-160). Rows that are controls, not
        // reading, stay out of it. ta-blf: a copy never carries a hidden control the reader did not
        // see ([SafeCopyClipboard]; the copy notice offers "Copy raw").
        if (item.selectableText) {
            SelectableRow { ChatRowContent(item, onFetchTurns, find, toolRender, onToggleGroup, onOpenCommand, zone) }
        } else {
            ChatRowContent(item, onFetchTurns, find, toolRender, onToggleGroup, onOpenCommand, zone)
        }
    }
}

/**
 * T6.7: one row's words in their own selection (a selection never runs across rows), copied through
 * [SafeCopyClipboard] (ta-blf: never a hidden control the reader did not see). The row's keys still
 * take their taps; a key that must never join a selection says so itself ([NoticeDismissButton]).
 */
@Composable
internal fun SelectableRow(content: @Composable () -> Unit) {
    val base = androidx.compose.ui.platform.LocalClipboard.current
    val notices = LocalCopyNotices.current
    val clipboard = remember(base, notices) { SafeCopyClipboard(base, notices) }
    CompositionLocalProvider(androidx.compose.ui.platform.LocalClipboard provides clipboard) {
        SelectionContainer { content() }
    }
}

/**
 * T6.7: whether a row's words take part in text selection. r2: an ALLOWLIST: the blocks (bubbles,
 * thinking, tool cards), denials, answered questions, outcome and session-error rows, a Codex turn's
 * plan / diff / review, and the continuation and retry markers. ta-coik.22: and, as on the web
 * (globals.css 90fbb9f sets no `user-select: none` on them), the cards and notices: the approval,
 * question and limit cards (and the scheduled resume), the provider, compaction and session
 * notices; their keys keep acting on a tap. Out stay the single-control rows (Load earlier, an
 * activity group's summary — `user-select: none` on the web too, globals.css :4611-4617 — and a
 * background command chip, a button on the web). A new row kind is out until it is added here.
 */
internal val ChatItem.selectableText: Boolean
    get() = when (this) {
        is ChatItem.Block, is ChatItem.Denial, is ChatItem.Answered, is ChatItem.Outcome, is ChatItem.SessionError,
        is ChatItem.TurnPlan, is ChatItem.TurnDiff, is ChatItem.TurnReview,
        is ChatItem.Continuation, is ChatItem.Retry,
        is ChatItem.Approval, is ChatItem.Question, is ChatItem.RateLimit,
        is ChatItem.ProviderNotice, is ChatItem.Compaction, is ChatItem.SessionNotice,
        -> true
        is ChatItem.LoadEarlier, is ChatItem.ToolGroup, is ChatItem.BgCommand -> false
    }

@Composable
private fun ChatRowContent(
    item: ChatItem,
    onFetchTurns: (Int, Int) -> Unit,
    find: FindMarks?,
    toolRender: ToolRenderFlags,
    onToggleGroup: (ChatItem.ToolGroup) -> Unit,
    onOpenCommand: (String) -> Unit,
    zone: ZoneId,
) {
    run {
        when (item) {
            // T13.2: a saved copy cannot fetch them: say so instead of offering a dead key.
            is ChatItem.LoadEarlier -> if (LocalOlderTurnsUnavailable.current) OlderTurnsNotDownloaded() else LoadEarlierKey(item.count) {
                val range = loadEarlierRange(item.count)
                onFetchTurns(range.first, range.last + 1)
            }
            is ChatItem.Continuation -> ContinuationMarker()
            is ChatItem.Block -> when (item.block.kind) {
                Vocab.BLOCK_USER_MESSAGE -> UserBubble(item.block, timeLabel = item.timeLabel, find = find)
                Vocab.BLOCK_MESSAGE -> AgentBubble(item.block, timeLabel = item.timeLabel, find = find)
                Vocab.BLOCK_THINKING -> ThinkingCard(item.block)
                Vocab.BLOCK_TOOL -> ToolBlockView(item.raw ?: remember(item.block) { item.block.asTree() }, toolRender, nested = item.grouped)
                // T7.3: the v54 `!` command panel (command_output_started / _delta / _completed).
                COMMAND_OUTPUT_BLOCK -> remember(item.raw) { commandOutputView(item.raw) }?.let { CommandOutputPanel(it) }
                else -> {}
            }
            is ChatItem.Denial -> PermissionDenialCard(item.denial, item.target, item.run, nested = item.nested)
            is ChatItem.Answered -> AnsweredQuestionCard(item.answered)
            is ChatItem.Retry -> item.turn.apiRetry?.let { ApiRetryMarker(it) }
            is ChatItem.Approval -> ApprovalCard(item.approval)
            is ChatItem.Question -> QuestionCard(item.question, answered = item.answered)
            is ChatItem.Outcome -> OutcomeBadge(item.turn, interrupt = item.interrupt, zone = zone)
            is ChatItem.ProviderNotice -> ProviderNoticeRow(item.notice)
            is ChatItem.Compaction -> CompactionRow(item.compaction)
            is ChatItem.SessionNotice -> SessionNoticeRow(item.notice)
            is ChatItem.RateLimit -> if (item.prompt.status == "awaiting_choice") RateLimitCard(item.prompt, zone = zone) else ScheduledResumeRow(item.prompt, zone = zone)
            is ChatItem.ToolGroup -> ToolActivityHeader(
                summary = item.summary,
                running = item.running,
                hasErrors = item.hasErrors,
                open = item.open,
                onToggle = { onToggleGroup(item) },
            )
            is ChatItem.TurnPlan -> CodexPlanCard(item.plan)
            is ChatItem.TurnDiff -> CodexUnifiedDiff(item.unifiedDiff)
            is ChatItem.TurnReview -> CodexReviewCard(item.review)
            is ChatItem.BgCommand -> BackgroundCommandChip(item.command) { onOpenCommand(item.command.commandId) }
            is ChatItem.SessionError -> SessionErrorRow(item.text)
        }
    }
}

/**
 * `.load-earlier-button` (globals.css:6320-6339): centred, 0.78rem muted, `--tint-xs` on a 1px
 * `--border` (= `--line`), `--radius-sm`, padded `space-xs space-md`, at least 44px tall.
 */
@Composable
private fun LoadEarlierKey(count: Int, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .heightIn(min = 44.dp)
                .background(t.tintXs, shape)
                .border(1.dp, t.line, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs)
                .testTag(ChatItem.LOAD_EARLIER_KEY),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(loadEarlierLabel(count), style = type.body.copy(fontSize = 12.48.sp), color = t.muted)
        }
    }
}

/** T5.3: what the transcript needs from the find bar (chat-view.tsx findResults + the active hit). */
@androidx.compose.runtime.Immutable
internal class TranscriptFind(val results: FindResults, val needle: String, val activeHit: FindHit?)

/** How many frames the jump waits for the active mark's text to lay out and report. */
private const val FIND_REPORT_FRAMES = 10

/** T6.2: which renderer a tool block gets (chat-view.tsx `richCodex` / `richOpencode` gates). */
@androidx.compose.runtime.Immutable
internal data class ToolRenderFlags(val richCodex: Boolean, val richOpencode: Boolean, val showThinking: Boolean) {
    companion object {
        val Default = ToolRenderFlags(richCodex = false, richOpencode = false, showThinking = false)
    }
}

/** `BlockView` for a tool (chat-view.tsx:707-724): the engine's rich card when it has one, else the generic card. */
@Composable
internal fun ToolBlockView(block: JsObj, flags: ToolRenderFlags, nested: Boolean = false) {
    when {
        flags.richCodex && codexRichToolKind(block) != null -> CodexRichToolCard(block, nested)
        flags.richOpencode && opencodeRichToolKind(block) != null -> OpencodeRichToolCard(block, nested)
        else -> ToolCard(block, flags.showThinking, nested = nested)
    }
}

/** A reader's toggle of one activity group, and the default it overrode. */
internal data class GroupToggle(val default: Boolean, val open: Boolean)

/**
 * The reader's group toggles. [resolve] is the only reader: a toggle recorded against another
 * default is dropped there and then (React re-sets `open` when the prop changes), so it can never
 * come back even if the default flips twice between two builds of the rows. [version] is the
 * snapshot state a toggle bumps to rebuild the rows; a drop needs no rebuild (the row just built
 * already shows the default).
 */
internal class GroupToggles(initial: Map<String, GroupToggle> = emptyMap()) {
    private val map = HashMap(initial)
    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun resolve(key: String, default: Boolean): Boolean {
        val toggle = map[key] ?: return default
        if (toggle.default != default) {
            map.remove(key)
            return default
        }
        return toggle.open
    }

    fun toggle(group: ChatItem.ToolGroup) {
        map[group.key] = GroupToggle(group.defaultOpen, !group.open)
        version++
    }

    fun snapshot(): Map<String, GroupToggle> = HashMap(map)

    companion object {
        val Saver: Saver<GroupToggles, Any> = Saver(
            save = { store -> ArrayList(store.snapshot().map { (k, v) -> "${if (v.default) 1 else 0}${if (v.open) 1 else 0}$k" }) },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                GroupToggles((saved as List<String>).associate { it.substring(2) to GroupToggle(it[0] == '1', it[1] == '1') })
            },
        )
    }
}

/** The engine gates: `provider` + `engineGeneration` (chat-view.tsx:2122-2127). */
fun isRichCodexSession(provider: String?, engineGeneration: String?): Boolean =
    provider == "codex" && engineGeneration == "codex-app-server-v2"

fun isRichOpencodeSession(provider: String?, engineGeneration: String?): Boolean =
    provider == "opencode" && engineGeneration == "opencode-serve-v2"

/** L2: [keys] made unique, in order: the first keeps its key, a repeat gets the lowest free "<key>#<n>". */
internal fun uniqueLazyKeys(keys: List<String>): List<String> {
    val used = HashSet<String>(keys.size * 2)
    return keys.map { k -> uniqueKey(k, used) }
}

/** [key], or the lowest "<key>#<n>" not in [used]; records the result in [used]. */
internal fun uniqueKey(key: String, used: MutableSet<String>): String {
    if (used.add(key)) return key
    var n = 1
    while (!used.add("$key#$n")) n++
    return "$key#$n"
}
