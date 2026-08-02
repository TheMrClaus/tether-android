package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Loader
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.QueuedMessage
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.Vocab
import com.tether.app.ui.components.KeyVariant
import com.tether.app.ui.components.SpinnerRing
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import com.tether.app.ui.util.elapsedLabel
import com.tether.app.ui.util.spinnerWordFor
import com.tether.app.ui.util.tokenLabel
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Composer (visual-spec §4): waiting banner -> TurnActivity -> queued messages ->
 * input row (attach key · input well · SEND, or QUEUE + INTERRUPT while busy).
 */
@Composable
fun Composer(
    session: AgentSession?,
    projection: SessionProjection?,
    serverNow: () -> Long,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onQueueEdit: (queueId: String, text: String) -> Unit,
    onQueueRemove: (queueId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    var draft by remember(session?.id) { mutableStateOf("") }

    val activeTurn = projection?.activeTurnId?.let { projection.turnsById[it] }
    val busy = activeTurn != null
    val hasApproval = activeTurn?.pendingApprovals?.isNotEmpty() == true
    val hasQuestion = activeTurn?.pendingQuestions?.isNotEmpty() == true

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(t.graphite)
            .navigationBarsPadding()
            .imePadding()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasApproval || hasQuestion) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                if (hasApproval) {
                    Icon(Lucide.TriangleAlert, contentDescription = null, tint = t.warning, modifier = Modifier.size(14.dp))
                    Text(
                        "Waiting for your approval before the turn can continue.",
                        color = t.ink,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.body,
                        fontSize = 12.8.sp,
                    )
                } else {
                    Icon(Lucide.CircleHelp, contentDescription = null, tint = t.questionInk, modifier = Modifier.size(14.dp))
                    Text(
                        "Answer the agent's question above to continue.",
                        color = t.ink,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.body,
                        fontSize = 12.8.sp,
                    )
                }
            }
        }

        if (projection != null && session != null) {
            TurnActivity(projection = projection, session = session, serverNow = serverNow)
        }

        projection?.queuedMessages?.forEach { queued ->
            QueuedRow(
                queued = queued,
                onEdit = { text -> onQueueEdit(queued.queueId, text) },
                onRemove = { onQueueRemove(queued.queueId) },
            )
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TetherKey(
                onClick = { /* attachments: v1 stub */ },
                variant = KeyVariant.Secondary,
                icon = Lucide.Paperclip,
                iconSize = 18.dp,
                enabled = false,
                contentDescription = "Attach",
            )
            com.tether.app.ui.components.TetherInputWell(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = if (busy) {
                    "The agent is working — your message will be queued and sent after this turn…"
                } else {
                    "Message the agent…"
                },
                enabled = session != null,
            )
            if (busy) {
                TetherKey(
                    onClick = {
                        if (draft.isNotBlank()) {
                            onSend(draft.trim())
                            draft = ""
                        }
                    },
                    variant = KeyVariant.Primary,
                    label = "Queue",
                    icon = Lucide.Send,
                    iconSize = 18.dp,
                    fontSize = 12.sp,
                    enabled = draft.isNotBlank(),
                    showSlit = true,
                    contentDescription = "Queue",
                )
                TetherKey(
                    onClick = onInterrupt,
                    variant = KeyVariant.Brick,
                    icon = Lucide.CircleStop,
                    iconSize = 18.dp,
                    contentDescription = "Interrupt",
                )
            } else {
                TetherKey(
                    onClick = {
                        if (draft.isNotBlank()) {
                            onSend(draft.trim())
                            draft = ""
                        }
                    },
                    variant = KeyVariant.Primary,
                    icon = Lucide.Send,
                    iconSize = 18.dp,
                    enabled = session != null && draft.isNotBlank(),
                    showSlit = true,
                    contentDescription = "Send",
                )
            }
        }
    }
}

/** Queued message row: recessed well with a 2dp violet left edge. */
@Composable
private fun QueuedRow(
    queued: QueuedMessage,
    onEdit: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(TetherDimens.radiusMd)
    var text by remember(queued.queueId) { mutableStateOf(queued.text) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.mineralDeep, shape)
            .border(1.dp, t.lineStrong, shape)
            .drawBehind {
                drawRoundRect(
                    color = t.violetStrong,
                    topLeft = Offset.Zero,
                    size = Size(2.dp.toPx(), size.height),
                )
            }
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SpinningIcon(Lucide.Loader, tint = t.violet, size = 13.dp)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { state ->
                    // Commit edits once, when focus leaves — not per keystroke.
                    if (!state.isFocused && text != queued.text) onEdit(text)
                },
            textStyle = TextStyle(
                color = t.ink,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 13.6.sp,
            ),
            cursorBrush = SolidColor(t.violet),
            maxLines = 3,
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(TetherDimens.touchTargetDp)) {
            Icon(Lucide.X, contentDescription = "Remove", tint = t.muted, modifier = Modifier.size(15.dp))
        }
    }
}

/**
 * TurnActivity (visual-spec §4): run row (spinner + verb + elapsed/tokens) and
 * SESSION TOTAL row. MUTED, never violet. Elapsed derives from run.startedAt vs
 * the event-anchored [serverNow] — never raw device wall-clock vs journal ts.
 */
@Composable
fun TurnActivity(
    projection: SessionProjection,
    session: AgentSession,
    serverNow: () -> Long,
) {
    val t = LocalTetherTokens.current
    val activeTurn = projection.activeTurnId?.let { projection.turnsById[it] }
    val run = activeTurn?.run

    var now by remember { mutableLongStateOf(serverNow()) }
    LaunchedEffect(activeTurn?.turnId, run?.index) {
        while (run != null) {
            now = serverNow()
            delay(1000)
        }
    }

    var totalActiveMs = 0L
    var totalTokens = 0L
    var accountedTurns = 0
    for (turnId in projection.turnOrder) {
        val turn = projection.turnsById[turnId] ?: continue
        totalActiveMs += turn.activeMs
        turn.run?.let { totalActiveMs += max(0L, now - it.startedAt) }
        val tokens = settledTurnTokens(turn)
        if (tokens != null) {
            totalTokens += tokens
            accountedTurns += 1
        }
    }
    val hasHistory = totalActiveMs > 0 || accountedTurns > 0
    if (run == null && !hasHistory) return

    val tabularStyle = TextStyle(fontFeatureSettings = "tnum")

    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        if (run != null) {
            val rawElapsed = now - run.startedAt
            val runSeconds = if (rawElapsed < 0) null else rawElapsed / 1000
            val runTokens = activeTurn.liveTokens?.let { max(0L, it - run.tokensStart) }
            val verb = when {
                activeTurn.status == Vocab.TURN_CANCELLING -> "Interrupting"
                activeTurn.apiRetry != null -> "Retrying"
                else -> spinnerWordFor(activeTurn.turnId, run.index)
            }
            Row(
                Modifier.height(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SpinnerRing(color = t.muted, size = 9.6.dp)
                Text(
                    "$verb…",
                    color = t.ink,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.label,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                val metrics = buildList {
                    runSeconds?.let { add(elapsedLabel(it)) }
                    runTokens?.let { add(tokenLabel(it)) }
                    session.metrics?.effort?.let { add("$it effort") }
                }
                Text(
                    metrics.joinToString(" · "),
                    color = t.muted,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.body,
                    fontSize = 12.5.sp,
                    style = tabularStyle,
                    maxLines = 1,
                )
            }
        }
        Row(
            Modifier.height(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "SESSION TOTAL",
                color = t.faint,
                fontFamily = Manrope,
                fontWeight = TetherWeights.strong,
                fontSize = 9.9.sp,
                letterSpacing = 0.06.em,
            )
            Text(
                elapsedLabel(totalActiveMs / 1000).ifEmpty { "0s" },
                color = t.muted,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 11.5.sp,
                style = tabularStyle,
            )
            if (accountedTurns > 0) {
                Text(
                    tokenLabel(totalTokens),
                    color = t.muted,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.body,
                    fontSize = 11.5.sp,
                    style = tabularStyle,
                )
            }
        }
    }
}

/** Tokens for a FINISHED turn, or null while it is still open (turn-activity.tsx). */
internal fun settledTurnTokens(turn: TurnProjection): Long? {
    if (turn.status != Vocab.TURN_DONE) return null
    val settled = turn.usage?.perTurnTokens
    val live = turn.liveTokens
    if (settled == null && live == null) return 0L
    if (settled == null) return live ?: 0L
    if (live == null) return settled
    return max(settled, live)
}
