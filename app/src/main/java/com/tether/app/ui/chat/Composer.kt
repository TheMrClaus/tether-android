package com.tether.app.ui.chat

import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Loader
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.SessionCommandOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.QueuedMessage
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.Vocab
import com.tether.app.protocol.reduce.activeModel
import com.tether.app.protocol.reduce.composerCommandList
import com.tether.app.protocol.reduce.pickerModels
import com.tether.app.protocol.reduce.resolveModelArg
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Composer (visual-spec §4): waiting banner -> TurnActivity -> queued messages ->
 * input row (attach key · input well · SEND, or QUEUE + INTERRUPT while busy).
 */
/** Server caps (lib/protocol-validate.mjs LIMITS): 10 files, 9 MiB each, 18 MiB total. */
private const val MAX_ATTACHMENTS = 10
private const val MAX_ATTACHMENT_BYTES = 9L * 1024 * 1024
private const val MAX_TOTAL_ATTACHMENT_BYTES = 18L * 1024 * 1024

/** A file the operator picked, held in memory until the next idle send. */
private data class PickedAttachment(val attachment: Attachment, val sizeBytes: Long)

@Composable
fun Composer(
    session: AgentSession?,
    projection: SessionProjection?,
    controls: ServerMessage.SessionControls?,
    serverNow: () -> Long,
    onSend: (String, List<Attachment>) -> Boolean,
    onInterrupt: () -> Unit,
    onQueueEdit: (queueId: String, text: String) -> Unit,
    onQueueRemove: (queueId: String) -> Unit,
    onSetMode: (String) -> Unit,
    onSetModel: (String) -> Boolean,
    onRequestControls: () -> Unit,
    modifier: Modifier = Modifier,
    onAttachError: (String) -> Unit = {},
) {
    val t = LocalTetherTokens.current
    var draft by remember(session?.id) { mutableStateOf("") }
    var picked by remember(session?.id) { mutableStateOf(listOf<PickedAttachment>()) }

    val activeTurn = projection?.activeTurnId?.let { projection.turnsById[it] }
    val busy = activeTurn != null
    val hasApproval = activeTurn?.pendingApprovals?.isNotEmpty() == true
    val hasQuestion = activeTurn?.pendingQuestions?.isNotEmpty() == true

    val claude = session?.provider == "claude"
    var showModelPicker by remember(session?.id) { mutableStateOf(false) }
    var menuDismissed by remember(session?.id) { mutableStateOf(false) }
    var notice by remember(session?.id) { mutableStateOf<String?>(null) }
    var noticeSeq by remember(session?.id) { mutableStateOf(0) }

    // The web flash(): 6 s auto-dismiss; every flash re-times itself, even an
    // identical message (the counter makes it a fresh effect key).
    LaunchedEffect(notice, noticeSeq) {
        if (notice != null) {
            delay(6_000)
            notice = null
        }
    }
    fun flash(message: String) {
        noticeSeq += 1
        notice = message
    }

    val models = controls?.models ?: emptyList()
    val picker = remember(models, session?.model) { pickerModels(models, session?.model) }
    val active = remember(models, picker, session?.model) { activeModel(models, picker, session?.model) }
    val modelLabel = active?.displayName ?: (session?.model ?: "Default")
    val commands = remember(projection?.cliInventory, controls) {
        composerCommandList(projection?.cliInventory?.commands, controls?.commands ?: emptyList())
    }

    // The command-name fragment being typed ("/mod" -> "mod"), or null when the
    // draft isn't a bare slash command — drives whether the menu shows.
    val slashQuery = if (claude && draft.startsWith("/") && !draft.drop(1).contains(" ")) draft.drop(1) else null
    val menuMatches = remember(slashQuery, commands) {
        if (slashQuery == null) {
            emptyList()
        } else {
            val q = slashQuery.lowercase()
            commands.filter { command ->
                command.name.lowercase().startsWith(q) ||
                    command.aliases.orEmpty().any { it.lowercase().startsWith(q) }
            }
        }
    }
    val menuOpen = slashQuery != null && !menuDismissed && menuMatches.isNotEmpty() && !busy

    fun openModelPicker() {
        if (!claude) return
        onRequestControls() // refresh to the live list if the session has since warmed
        showModelPicker = true
        menuDismissed = true
    }

    fun chooseModel(model: SessionModelOption) {
        if (onSetModel(model.value)) {
            val isDefaultChoice = model.value.isEmpty() || model.value == "default"
            flash(if (isDefaultChoice) "Model reset to the CLI default." else "Model set to ${model.displayName}.")
            showModelPicker = false
            if (draft.startsWith("/model")) draft = ""
        }
    }

    // Native commands (currently /model) run in-app; everything else is flagged
    // terminal-only rather than sent as prompt text (the /model-as-text bug).
    fun runSlashCommand(raw: String) {
        val body = raw.drop(1)
        val name = body.split(Regex("\\s+")).first()
        val arg = body.removePrefix(name).trim()
        val info = commands.find { it.name == name || it.aliases.orEmpty().contains(name) }

        if (name == "model" || info?.name == "model") {
            if (arg.isEmpty()) {
                openModelPicker()
                draft = ""
                return
            }
            val match = resolveModelArg(arg, models)
            if (match != null) {
                chooseModel(match)
                return
            }
            flash("No model matches “$arg”. Choose one from the list.")
            openModelPicker()
            draft = ""
            return
        }
        if (info != null && !info.supported) {
            flash("/${info.name} isn’t available in Tether yet — run it from a terminal (claude --resume …).")
            draft = ""
            return
        }
        flash("Unknown command “/$name”. Type “/” to see what’s available.")
    }

    fun acceptCommand(command: SessionCommandOption) {
        menuDismissed = true
        if (command.name == "model" || command.aliases.orEmpty().contains("model")) {
            openModelPicker()
            draft = ""
            return
        }
        if (!command.supported) {
            flash("/${command.name} isn’t available in Tether yet — run it from a terminal.")
            draft = ""
            return
        }
        draft = "/${command.name} "
    }

    /** Slash commands are in-app control requests: never queued, no attachments. */
    fun trySlashCommand(text: String, hasAttachments: Boolean): Boolean {
        if (!claude || !text.startsWith("/") || hasAttachments) return false
        runSlashCommand(text)
        return true
    }

    fun submit() {
        val text = draft.trim()
        val hasAttachments = picked.isNotEmpty()
        if (text.isEmpty() && !hasAttachments) return
        if (trySlashCommand(text, hasAttachments)) return
        if (busy && hasAttachments) {
            // Attachments only ride an idle send — ask the operator to wait
            // rather than silently dropping the files (web submit()).
            flash("Wait for the current turn to finish before sending attachments.")
            return
        }
        if (busy) {
            // Queue path is text-only; attachments are only attachable while
            // idle, so none are pending here.
            if (text.isNotEmpty() && onSend(text, emptyList())) draft = ""
        } else {
            // Refused sends (§5.6 rollback) keep draft + chips.
            if (onSend(text, picked.map { it.attachment })) {
                draft = ""
                picked = emptyList()
            }
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val (loaded, failures) = withContext(Dispatchers.IO) {
                readAttachments(context, uris, picked.sumOf { it.sizeBytes })
            }
            if (loaded.isNotEmpty()) picked = (picked + loaded).take(MAX_ATTACHMENTS)
            if (failures > 0) {
                onAttachError(
                    "$failures file${if (failures == 1) "" else "s"} skipped — unreadable or over the 9 MB per-file limit.",
                )
            }
        }
    }

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

        if (claude) {
            ChatModeRow(
                permissionMode = session.permissionMode,
                modelLabel = modelLabel,
                onSetMode = onSetMode,
                onModelClick = { openModelPicker() },
            )
        }
        notice?.let { ComposerNotice(it) }
        if (menuOpen) {
            SlashCommandMenu(matches = menuMatches, onAccept = { acceptCommand(it) })
        }
        if (showModelPicker) {
            ModelPickerPanel(
                pickerModels = picker,
                sessionModel = session?.model,
                onChoose = { chooseModel(it) },
                onClose = { showModelPicker = false },
            )
        }

        projection?.queuedMessages?.forEach { queued ->
            QueuedRow(
                queued = queued,
                onEdit = { text -> onQueueEdit(queued.queueId, text) },
                onRemove = { onQueueRemove(queued.queueId) },
            )
        }

        if (picked.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                picked.forEach { item ->
                    AttachmentChip(
                        item = item,
                        onRemove = { picked = picked - item },
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TetherKey(
                onClick = { attachmentPicker.launch(arrayOf("*/*")) },
                variant = KeyVariant.Secondary,
                icon = Lucide.Paperclip,
                iconSize = 18.dp,
                enabled = session != null && !busy,
                contentDescription = "Attach",
            )
            com.tether.app.ui.components.TetherInputWell(
                value = draft,
                onValueChange = {
                    draft = it
                    // Re-arm the slash menu after an Escape/dismiss once the
                    // operator keeps editing a slash (web onDraftChange).
                    if (menuDismissed) menuDismissed = false
                },
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
                    onClick = { submit() },
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
                    onClick = { submit() },
                    variant = KeyVariant.Primary,
                    icon = Lucide.Send,
                    iconSize = 18.dp,
                    enabled = session != null && (draft.isNotBlank() || picked.isNotEmpty()),
                    showSlit = true,
                    contentDescription = "Send",
                )
            }
        }
    }
}

/** One picked-but-unsent attachment: icon + name/size + remove (visual-spec §4 chips). */
@Composable
private fun AttachmentChip(item: PickedAttachment, onRemove: () -> Unit) {
    val t = LocalTetherTokens.current
    Row(
        modifier = Modifier
            .background(t.mineralDeep, RoundedCornerShape(TetherDimens.radiusMd))
            .border(1.dp, t.lineStrong, RoundedCornerShape(TetherDimens.radiusMd))
            .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Lucide.FileText, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
        Text(
            item.attachment.name,
            color = t.ink,
            fontFamily = Manrope,
            fontWeight = TetherWeights.label,
            fontSize = 11.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
        )
        Text(
            humanSize(item.sizeBytes),
            color = t.faint,
            fontFamily = Manrope,
            fontSize = 10.4.sp,
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Lucide.X, contentDescription = "Remove ${item.attachment.name}", tint = t.muted, modifier = Modifier.size(13.dp))
        }
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * Read picked URIs into base64 attachments (caller supplies the IO context).
 * Skips — and counts — anything unreadable, over the per-file cap, or pushing
 * the running total (seeded with the already-picked bytes) over the total cap.
 */
private fun readAttachments(
    context: android.content.Context,
    uris: List<android.net.Uri>,
    alreadyPickedBytes: Long,
): Pair<List<PickedAttachment>, Int> {
    val loaded = ArrayList<PickedAttachment>()
    var failures = 0
    var total = alreadyPickedBytes
    for (uri in uris) {
        if (loaded.size >= MAX_ATTACHMENTS) { failures++; continue }
        try {
            var name: String? = null
            var size = -1L
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                        ?.let { name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                        ?.let { if (!cursor.isNull(it)) size = cursor.getLong(it) }
                }
            }
            if (size > MAX_ATTACHMENT_BYTES || (size >= 0 && total + size > MAX_TOTAL_ATTACHMENT_BYTES)) {
                failures++
                continue
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty() || bytes.size.toLong() > MAX_ATTACHMENT_BYTES ||
                total + bytes.size > MAX_TOTAL_ATTACHMENT_BYTES
            ) {
                failures++
                continue
            }
            total += bytes.size
            loaded += PickedAttachment(
                attachment = Attachment(
                    name = name?.substringAfterLast('/')?.ifBlank { null } ?: "file",
                    mediaType = context.contentResolver.getType(uri) ?: "application/octet-stream",
                    data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                ),
                sizeBytes = bytes.size.toLong(),
            )
        } catch (_: Exception) {
            failures++
        }
    }
    return Pair(loaded, failures)
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
