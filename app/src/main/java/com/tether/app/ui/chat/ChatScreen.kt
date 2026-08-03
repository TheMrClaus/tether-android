package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Loader
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.PendingApproval
import com.tether.app.protocol.model.PendingQuestion
import com.tether.app.protocol.model.PermissionDenialProjection
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.Vocab
import com.tether.app.protocol.reduce.RUN_RUNNING
import com.tether.app.protocol.reduce.collectSubagentRuns
import com.tether.app.protocol.reduce.subagentRosterSummary
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.components.KeyVariant
import com.tether.app.ui.components.SpinnerRing
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.components.StatusDot
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.WaitingPingDot
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import com.tether.app.ui.util.compactNumber
import com.tether.app.ui.util.compactPath
import com.tether.app.ui.util.statusCopy
import kotlinx.coroutines.launch

/** One renderable transcript item, keyed stably for the LazyColumn. */
private sealed interface ChatItem {
    val key: String

    data class Continuation(val turnId: String) : ChatItem {
        override val key = "$turnId/continuation"
    }

    data class Block(val turnId: String, val block: TurnBlock) : ChatItem {
        override val key = "$turnId/${block.blockId}"
    }

    data class Denial(val turnId: String, val index: Int, val denial: PermissionDenialProjection) : ChatItem {
        override val key = "$turnId/denial/$index"
    }

    data class Retry(val turn: TurnProjection) : ChatItem {
        override val key = "${turn.turnId}/retry"
    }

    data class Approval(val turnId: String, val approval: PendingApproval) : ChatItem {
        override val key = "$turnId/approval/${approval.requestId}"
    }

    data class Question(val turnId: String, val question: PendingQuestion) : ChatItem {
        override val key = "$turnId/question/${question.requestId}"
    }

    data class Outcome(val turn: TurnProjection) : ChatItem {
        override val key = "${turn.turnId}/outcome"
    }
}

private fun buildChatItems(projection: SessionProjection, showThinking: Boolean): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    for (turnId in projection.turnOrder) {
        val turn = projection.turnsById[turnId] ?: continue
        if (turn.continuation) items.add(ChatItem.Continuation(turnId))
        for (blockId in turn.blocks) {
            val block = turn.blocksById[blockId] ?: continue
            when (block.kind) {
                Vocab.BLOCK_THINKING -> {
                    if (showThinking && !block.text.isNullOrBlank()) items.add(ChatItem.Block(turnId, block))
                }
                Vocab.BLOCK_TOOL -> {
                    // AskUserQuestion tool blocks are replaced by the question card.
                    if (block.name != "AskUserQuestion") items.add(ChatItem.Block(turnId, block))
                }
                else -> items.add(ChatItem.Block(turnId, block))
            }
        }
        turn.permissionDenials.forEachIndexed { index, denial ->
            items.add(ChatItem.Denial(turnId, index, denial))
        }
        if (turn.apiRetry != null && turn.status == Vocab.TURN_RUNNING) items.add(ChatItem.Retry(turn))
        turn.pendingApprovals.values.forEach { items.add(ChatItem.Approval(turnId, it)) }
        turn.pendingQuestions.values.forEach { items.add(ChatItem.Question(turnId, it)) }
        if (turn.status == Vocab.TURN_DONE && turn.outcome != null && turn.outcome != Vocab.OUTCOME_OK) {
            items.add(ChatItem.Outcome(turn))
        }
    }
    return items
}

/** The chat workspace: workspace header, transcript, composer (visual-spec §4). */
@Composable
fun ChatScreen(
    vm: TetherViewModel,
    session: AgentSession?,
    projection: SessionProjection?,
    workspaceRoot: String?,
    prefs: UiPrefs,
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {},
) {
    val t = LocalTetherTokens.current
    val showThinking by prefs.showThinking.collectAsStateWithLifecycle(initialValue = true)
    val controlsMap by vm.client.sessionControls.collectAsStateWithLifecycle()

    val selectedRunIds by vm.selectedRunIdBySession.collectAsStateWithLifecycle()
    val runs = remember(projection) { collectSubagentRuns(projection) }
    // Resolve by lookup, never by trusting the stored id: a run that vanished
    // from a re-snapshot degrades to the Session tab on its own.
    val activeRun = session?.let { selectedRunIds[it.id] }?.let { id -> runs.firstOrNull { it.runId == id } }

    LaunchedEffect(session?.id, session?.provider) {
        val s = session
        if (s != null && s.provider == "claude") vm.client.requestSessionControls(s.id)
    }

    Column(modifier.background(t.mineralDeep)) {
        if (session != null) {
            WorkspaceHeader(vm = vm, session = session, workspaceRoot = workspaceRoot)
        }

        if (session != null && runs.isNotEmpty()) {
            SubagentTabs(
                runs = runs,
                activeRunId = activeRun?.runId,
                onSelect = { runId -> vm.selectRun(session.id, runId) },
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                session == null -> EmptyCentered(
                    title = "No session selected",
                    hint = "Open the menu to pick or create a session.",
                ) {
                    TetherKey(onClick = onOpenDrawer, variant = KeyVariant.Secondary, label = "Sessions")
                }

                projection == null -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    SpinningIcon(Lucide.Loader, tint = t.muted, size = 18.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Connecting to the session…",
                        color = t.muted,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.body,
                        fontSize = 13.6.sp,
                    )
                }

                projection.turnOrder.isEmpty() -> EmptyCentered(
                    label = "HEADLESS AGENT",
                    title = "Send a message to start the conversation.",
                    hint = "The agent runs on your server and streams every step back here.",
                )

                activeRun != null -> RunTab(
                    vm = vm,
                    sessionId = session.id,
                    projection = projection,
                    run = activeRun,
                    showThinking = showThinking,
                )

                else -> Transcript(
                    vm = vm,
                    sessionId = session.id,
                    projection = projection,
                    showThinking = showThinking,
                    roster = if (runs.isNotEmpty()) {
                        {
                            SubagentRoster(
                                runs = runs,
                                summary = subagentRosterSummary(runs),
                                activeRunId = null,
                                onSelect = { runId -> vm.selectRun(session.id, runId) },
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        Composer(
            session = session,
            projection = projection,
            controls = session?.let { controlsMap[it.id] },
            serverNow = { vm.serverNow(session?.id) },
            onSend = { text, attachments -> session?.let { vm.sendOrQueue(it.id, text, attachments) } ?: false },
            onInterrupt = { session?.let { vm.client.interrupt(it.id) } },
            onQueueEdit = { queueId, text -> session?.let { vm.client.queueEdit(it.id, queueId, text) } },
            onQueueRemove = { queueId -> session?.let { vm.client.queueRemove(it.id, queueId) } },
            onSetMode = { mode -> session?.let { vm.client.setMode(it.id, mode) } },
            onSetModel = { model -> session?.let { vm.client.setModel(it.id, model) } ?: false },
            onRequestControls = { session?.let { vm.client.requestSessionControls(it.id) } },
            onAttachError = { message -> vm.reportLocalError(message) },
        )
    }
}

@Composable
private fun Transcript(
    vm: TetherViewModel,
    sessionId: String,
    projection: SessionProjection,
    showThinking: Boolean,
    roster: (@Composable () -> Unit)? = null,
) {
    val t = LocalTetherTokens.current
    val items = remember(projection, showThinking) { buildChatItems(projection, showThinking) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val stickPx = with(density) { 80.dp.toPx() }

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 &&
                (last.offset + last.size) <= info.viewportEndOffset + stickPx
        }
    }
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { atBottom }.collect { stick = it }
    }
    // Follow the stream while stuck to the bottom.
    LaunchedEffect(items) {
        if (stick && items.isNotEmpty()) {
            listState.scrollToItem(items.lastIndex, scrollOffset = Int.MAX_VALUE / 2)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (roster != null) {
                item(key = "subagent-roster") { roster() }
            }
            items.forEach { item ->
                item(key = item.key) {
                    when (item) {
                        is ChatItem.Continuation -> ContinuationMarker()
                        is ChatItem.Block -> when (item.block.kind) {
                            Vocab.BLOCK_USER_MESSAGE -> UserBubble(item.block)
                            Vocab.BLOCK_MESSAGE -> AgentBubble(item.block)
                            Vocab.BLOCK_THINKING -> ThinkingCard(item.block)
                            Vocab.BLOCK_TOOL -> ToolCard(item.block)
                            else -> {}
                        }
                        is ChatItem.Denial -> DenialCard(item.denial)
                        is ChatItem.Retry -> item.turn.apiRetry?.let { ApiRetryMarker(it) }
                        is ChatItem.Approval -> ApprovalCard(
                            approval = item.approval,
                            onChoice = { choiceId, decision ->
                                vm.client.approval(sessionId, item.approval.requestId, choiceId, decision)
                            },
                        )
                        is ChatItem.Question -> QuestionCard(
                            question = item.question,
                            onSubmit = { answers, response ->
                                vm.client.answerQuestion(sessionId, item.question.requestId, answers, response)
                            },
                        )
                        is ChatItem.Outcome -> OutcomeBadge(item.turn)
                    }
                }
            }
        }

        if (!atBottom) {
            val scope = rememberCoroutineScope()
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .background(t.keyFace, RoundedCornerShape(999.dp))
                    .border(1.dp, t.lineStrong, RoundedCornerShape(999.dp))
                    .clickable {
                        stick = true
                        scope.launch {
                            if (items.isNotEmpty()) {
                                listState.animateScrollToItem(items.lastIndex, scrollOffset = Int.MAX_VALUE / 2)
                            }
                        }
                    }
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Lucide.ArrowDown, contentDescription = null, tint = t.ink, modifier = Modifier.size(15.dp))
                Text(
                    "Latest",
                    color = t.ink,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.label,
                    fontSize = 12.5.sp,
                )
            }
        }
    }
}

/**
 * A sub-agent run tab: the run panel replaces the transcript, but the active
 * turn's pending approval/question cards render below it on every tab — a
 * card the turn is stalled on must never be hidden behind a tab.
 */
@Composable
private fun RunTab(
    vm: TetherViewModel,
    sessionId: String,
    projection: SessionProjection,
    run: com.tether.app.protocol.reduce.SubagentRun,
    showThinking: Boolean,
) {
    val listState = rememberLazyListState()
    val activeTurn = projection.activeTurnId?.let { projection.turnsById[it] }
    val pending = activeTurn?.pendingApprovals?.values?.toList().orEmpty()
    val pendingQ = activeTurn?.pendingQuestions?.values?.toList().orEmpty()

    // Web parity: a running run follows the newest activity as its thread
    // grows (steps stream in, pending cards arrive); a finished run parks at
    // the top. Re-key on the growing content so the effect re-fires, and read
    // the current last index inside the effect so it tracks new items.
    LaunchedEffect(run.runId, run.steps, pending.size, pendingQ.size, run.status) {
        val lastIndex = pending.size + pendingQ.size // panel is item 0; cards follow
        if (run.status == RUN_RUNNING) {
            listState.scrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE / 2)
        } else {
            listState.scrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "run-panel") {
            SubagentRunPanel(run = run, showThinking = showThinking)
        }
        pending.forEach { approval ->
            item(key = "approval/${approval.requestId}") {
                ApprovalCard(
                    approval = approval,
                    onChoice = { choiceId, decision ->
                        vm.client.approval(sessionId, approval.requestId, choiceId, decision)
                    },
                )
            }
        }
        pendingQ.forEach { question ->
            item(key = "question/${question.requestId}") {
                QuestionCard(
                    question = question,
                    onSubmit = { answers, response ->
                        vm.client.answerQuestion(sessionId, question.requestId, answers, response)
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyCentered(
    title: String,
    hint: String,
    label: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val t = LocalTetherTokens.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (label != null) {
            Text(
                label,
                color = t.faint,
                fontFamily = Manrope,
                fontWeight = TetherWeights.heading,
                fontSize = 10.7.sp,
                letterSpacing = 0.08.em,
            )
            Spacer(Modifier.height(10.dp))
        }
        Text(
            title,
            color = t.ink,
            fontFamily = Manrope,
            fontWeight = TetherWeights.label,
            fontSize = 16.8.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            hint,
            color = t.muted,
            fontFamily = Manrope,
            fontWeight = TetherWeights.body,
            fontSize = 13.6.sp,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** Workspace header: session name + status badge + actions; mono path line. */
@Composable
private fun WorkspaceHeader(vm: TetherViewModel, session: AgentSession, workspaceRoot: String?) {
    val t = LocalTetherTokens.current
    var showTelemetry by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(t.graphite)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                session.name,
                color = t.white,
                fontFamily = Manrope,
                fontWeight = TetherWeights.heading,
                fontSize = 15.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val statusColor = when (session.status) {
                "active" -> t.running
                "waiting" -> t.violet
                else -> t.faint
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(horizontal = 6.dp),
            ) {
                when (session.status) {
                    "active" -> SpinnerRing(color = statusColor, size = 10.4.dp)
                    "waiting" -> WaitingPingDot(color = statusColor)
                    else -> StatusDot(color = statusColor)
                }
                Text(
                    statusCopy(session.status),
                    color = statusColor,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.label,
                    fontSize = 11.2.sp,
                )
            }
            IconButton(onClick = { showTelemetry = true }, modifier = Modifier.size(TetherDimens.touchTargetDp)) {
                Icon(Lucide.Gauge, contentDescription = "Telemetry", tint = t.muted, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { vm.client.pin(session.id, !session.pinned) }, modifier = Modifier.size(TetherDimens.touchTargetDp)) {
                Icon(
                    Lucide.Pin,
                    contentDescription = if (session.pinned) "Unpin" else "Pin",
                    tint = if (session.pinned) t.violet else t.muted,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (session.status != "exited") {
                IconButton(onClick = { confirmEnd = true }, modifier = Modifier.size(TetherDimens.touchTargetDp)) {
                    Icon(Lucide.CircleStop, contentDescription = "End session", tint = t.brick, modifier = Modifier.size(16.dp))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                compactPath(session.cwd, workspaceRoot),
                color = t.faint,
                fontFamily = JetBrainsMono,
                fontSize = 10.9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            session.metrics?.let { metrics ->
                val parts = buildList {
                    metrics.model?.let { add(it) }
                    metrics.totalTokens?.let { add("${compactNumber(it)} tok") }
                    metrics.contextPercent?.let { add("${it.toInt()}% ctx") }
                }
                if (parts.isNotEmpty()) {
                    val ctx = metrics.contextPercent ?: 0.0
                    Text(
                        parts.joinToString(" · "),
                        color = when {
                            ctx >= 90 -> t.danger
                            ctx >= 75 -> t.warning
                            else -> t.faint
                        },
                        fontFamily = JetBrainsMono,
                        fontSize = 10.6.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }

    if (showTelemetry) {
        TetherDialog(onDismiss = { showTelemetry = false }, title = "Telemetry") {
            val rows = buildList {
                add("Provider" to session.provider)
                session.model?.let { add("Model" to it) }
                session.metrics?.effort?.let { add("Effort" to it) }
                session.metrics?.totalTokens?.let { add("Total tokens" to compactNumber(it)) }
                session.metrics?.contextPercent?.let { add("Context" to "${it.toInt()}%") }
                session.metrics?.gitBranch?.let { add("Branch" to it) }
                add("Directory" to session.cwd)
            }
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        label.uppercase(),
                        color = t.faint,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.strong,
                        fontSize = 9.9.sp,
                        letterSpacing = 0.06.em,
                        modifier = Modifier.weight(0.4f),
                    )
                    Text(
                        value,
                        color = t.ink,
                        fontFamily = JetBrainsMono,
                        fontSize = 11.8.sp,
                        modifier = Modifier.weight(0.6f),
                    )
                }
            }
        }
    }

    if (confirmEnd) {
        TetherDialog(onDismiss = { confirmEnd = false }, title = "End session") {
            Text(
                "Stop the agent process for \"${session.name}\"?",
                color = t.ink,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 13.6.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TetherKey(onClick = { confirmEnd = false }, variant = KeyVariant.Secondary, label = "Cancel")
                TetherKey(
                    onClick = {
                        confirmEnd = false
                        vm.client.kill(session.id)
                    },
                    variant = KeyVariant.Brick,
                    label = "End session",
                    icon = Lucide.CircleStop,
                )
            }
        }
    }
}
