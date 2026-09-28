package com.tether.app.ui.chat

import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.SessionCommandOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.Vocab
import com.tether.app.protocol.reduce.activeModel
import com.tether.app.protocol.reduce.composerCommandList
import com.tether.app.protocol.reduce.pickerModels
import com.tether.app.protocol.reduce.resolveModelArg
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.KeyWear
import com.tether.app.ui.components.SpinnerRing
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherSeam
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import com.tether.app.ui.util.elapsedLabel
import com.tether.app.ui.util.spinnerWordFor
import com.tether.app.ui.util.tokenLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Server caps (lib/protocol-validate.mjs LIMITS): 10 files, 9 MiB each, 18 MiB total. */
private const val MAX_ATTACHMENTS = 10
private const val MAX_ATTACHMENT_BYTES = 9L * 1024 * 1024
private const val MAX_TOTAL_ATTACHMENT_BYTES = 18L * 1024 * 1024

/** A file the operator picked, held in memory until the next idle send. */
private data class PickedAttachment(val attachment: Attachment, val sizeBytes: Long)

/**
 * The session composer (tether components/chat-view.tsx `.chat-composer`, :3681-4516): the
 * waiting banners, the turn's run row, the mode row and notices, the slash menu, the queue, the
 * attachment chips, then ONE recessed well — the multiline text field on top and the key bank at
 * its foot (attach, the session-total readout where it fits, then Send, or Queue + Interrupt
 * while a turn runs).
 *
 * Send / queue rules (chat-view.tsx:3104-3188): an idle session sends; a busy one QUEUES the
 * text (`queue-add`, flushed by the server at the next turn boundary); attachments ride only an
 * idle send, so while busy the operator is asked to wait instead of losing the files; a refused
 * send keeps the draft. Enter sends (Shift+Enter breaks the line; never mid-IME-composition),
 * and the soft keyboard's action key is Send, as the phone web's Enter is.
 *
 * T2.3/T7.1 drafts: the editor keeps its own state (no async round-trip under the cursor). It
 * opens on this session's draft when already loaded ([initialDraft]), otherwise hydrates once the
 * stored one is read ([awaitDraft]) unless the operator already started typing, and mirrors
 * every change back ([onDraftChange]; "" after a send removes the stored draft).
 */
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
    initialDraft: String? = null,
    awaitDraft: suspend () -> String = { "" },
    onDraftChange: (String) -> Unit = {},
) {
    val t = LocalTetherTokens.current
    val metrics = composerMetrics()
    var field by remember(session?.id) {
        val text = initialDraft ?: ""
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    val draft = field.text
    fun setDraft(text: String) {
        field = TextFieldValue(text, TextRange(text.length))
    }
    val currentOnDraftChange by rememberUpdatedState(onDraftChange)
    val currentAwaitDraft by rememberUpdatedState(awaitDraft)
    LaunchedEffect(session?.id) {
        launch { snapshotFlow { field.text }.drop(1).collect { currentOnDraftChange(it) } }
        val stored = currentAwaitDraft()
        if (field.text.isEmpty() && stored.isNotEmpty()) setDraft(stored)
    }
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
            if (draft.startsWith("/model")) setDraft("")
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
                setDraft("")
                return
            }
            val match = resolveModelArg(arg, models)
            if (match != null) {
                chooseModel(match)
                return
            }
            flash("No model matches “$arg”. Choose one from the list.")
            openModelPicker()
            setDraft("")
            return
        }
        if (info != null && !info.supported) {
            flash("/${info.name} isn’t available in Tether yet — run it from a terminal (claude --resume …).")
            setDraft("")
            return
        }
        flash("Unknown command “/$name”. Type “/” to see what’s available.")
    }

    fun acceptCommand(command: SessionCommandOption) {
        menuDismissed = true
        if (command.name == "model" || command.aliases.orEmpty().contains("model")) {
            openModelPicker()
            setDraft("")
            return
        }
        if (!command.supported) {
            flash("/${command.name} isn’t available in Tether yet — run it from a terminal.")
            setDraft("")
            return
        }
        setDraft("/${command.name} ")
    }

    /** Slash commands are in-app control requests: never queued, no attachments. */
    fun trySlashCommand(text: String, hasAttachments: Boolean): Boolean {
        if (!claude || !text.startsWith("/") || hasAttachments) return false
        runSlashCommand(text)
        return true
    }

    fun submit() {
        if (session == null) return
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
            if (text.isNotEmpty() && onSend(text, emptyList())) setDraft("")
        } else {
            // Refused sends (§5.6 rollback) keep draft + chips.
            if (onSend(text, picked.map { it.attachment })) {
                setDraft("")
                picked = emptyList()
            }
        }
    }

    /** chat-view.tsx:3190-3248: the slash menu gets the keys first, then Enter submits. */
    fun onComposerKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || field.composition != null) return false
        val enter = (event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isShiftPressed
        if (menuOpen) {
            when {
                event.key == Key.Escape -> {
                    menuDismissed = true
                    return true
                }
                event.key == Key.Tab || enter -> {
                    menuMatches.firstOrNull()?.let(::acceptCommand)
                    return true
                }
            }
        }
        if (isSubmitKey(event, field)) {
            submit()
            return true
        }
        return false
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

    val inputInteraction = remember { MutableInteractionSource() }
    val inputFocused by inputInteraction.collectIsFocusedAsState()

    Column(modifier = modifier.fillMaxWidth().background(t.graphite)) {
        // `.chat-composer { border-top: 1px solid var(--line-strong); box-shadow: inset 0 1px 0
        // var(--seam-lip) }`; Studio's deck has no border (studio.css:383).
        if (!metrics.studio) TetherSeam()
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val deckWidth = maxWidth
            val deck = composerDeckPadding(metrics, deckWidth)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = deck.start, top = deck.top, end = deck.end, bottom = deck.bottom),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasApproval || hasQuestion) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        if (hasApproval) {
                            Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.warning, modifier = Modifier.size(14.dp))
                            Text(
                                "Waiting for your approval before the turn can continue.",
                                color = t.ink,
                                fontFamily = Manrope,
                                fontWeight = TetherWeights.body,
                                fontSize = 12.8.sp,
                            )
                        } else {
                            Icon(TetherIcons.CircleHelp, contentDescription = null, tint = t.questionInk, modifier = Modifier.size(14.dp))
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
                    TurnActivity(projection = projection, session = session, serverNow = serverNow, part = TurnActivityPart.Run)
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

                val queued = projection?.queuedMessages.orEmpty()
                if (queued.isNotEmpty()) {
                    QueuedMessages(
                        queued = queued,
                        onSave = onQueueEdit,
                        onRemove = onQueueRemove,
                        onInterruptNow = onInterrupt,
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

                ComposerWell(metrics = metrics, inputFocused = inputFocused) {
                    ComposerInput(
                        value = field,
                        onValueChange = { next ->
                            // Re-arm the slash menu after an Escape/dismiss once the
                            // operator keeps editing a slash (web onDraftChange).
                            if (next.text != field.text && menuDismissed) menuDismissed = false
                            field = next
                        },
                        placeholder = if (busy) PLACEHOLDER_BUSY else PLACEHOLDER_IDLE,
                        enabled = session != null,
                        metrics = metrics,
                        onKey = ::onComposerKey,
                        onImeSend = {
                            if (menuOpen) menuMatches.firstOrNull()?.let(::acceptCommand) else submit()
                        },
                        interactionSource = inputInteraction,
                    )
                    ComposerToolbar(
                        metrics = metrics,
                        width = deckWidth - deck.start - deck.end,
                        onAttach = { attachmentPicker.launch(arrayOf("*/*")) },
                        // The web keeps the paperclip live while a turn runs; submit() asks the operator to wait.
                        attachEnabled = session != null,
                        totals = if (projection != null && session != null) {
                            { TurnActivity(projection = projection, session = session, serverNow = serverNow, part = TurnActivityPart.Totals) }
                        } else {
                            null
                        },
                    ) {
                        ComposerActions(
                            metrics = metrics,
                            busy = busy,
                            canQueue = draft.isNotBlank(),
                            canSend = session != null && (draft.isNotBlank() || picked.isNotEmpty()),
                            onSubmit = ::submit,
                            onInterrupt = onInterrupt,
                        )
                    }
                }
            }
        }
    }
}

private data class DeckPadding(val start: Dp, val top: Dp, val end: Dp, val bottom: Dp)

/**
 * `.chat-composer` padding: `space-sm` all round on a phone, `space-sm space-md` from 48rem;
 * Studio uses 0.625rem on a phone and `1rem max(2rem, (100% - 53rem) / 2) 1.25rem` wider, which
 * centres a well of at most 53rem.
 */
private fun composerDeckPadding(m: ComposerMetrics, width: Dp): DeckPadding {
    if (m.studio) {
        if (m.phone) return DeckPadding(10.dp, 10.dp, 10.dp, 10.dp)
        val side = maxOf(32.dp, (width - 848.dp) / 2)
        return DeckPadding(side, 16.dp, side, 20.dp)
    }
    return if (m.phone) DeckPadding(8.dp, 8.dp, 8.dp, 8.dp) else DeckPadding(16.dp, 8.dp, 16.dp, 8.dp)
}

/**
 * `.chat-composer-toolbar`: on a phone one row (attach · flexible options slot · actions); wider,
 * the footer row carries attach, the SESSION readout (only when the toolbar is at least 28rem
 * wide, its container query) and the actions on the right. The options row (Model / Effort /
 * Mode) above the footer is T7.2's; until then the legacy mode row stays above the well.
 */
@Composable
private fun ComposerToolbar(
    metrics: ComposerMetrics,
    width: Dp,
    onAttach: () -> Unit,
    attachEnabled: Boolean,
    totals: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    val t = LocalTetherTokens.current
    val gap = when {
        metrics.studio -> 8.dp
        metrics.phone -> t.css.spaceXs
        else -> t.css.spaceSm
    }
    val padding = if (metrics.studio) {
        Modifier.padding(start = 10.4.dp, top = 4.dp, end = 10.4.dp, bottom = 10.4.dp)
    } else {
        Modifier.padding(start = t.css.spaceSm, top = t.css.spaceXs, end = t.css.spaceSm, bottom = t.css.spaceSm)
    }
    Row(
        modifier = Modifier.fillMaxWidth().then(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        val attachSize = if (metrics.studio || metrics.touchKeys) TetherDimens.touchTargetDp else 30.4.dp
        TetherKey(
            onClick = onAttach,
            classes = KeyClasses.Attach,
            icon = TetherIcons.Paperclip,
            iconSize = 18.dp,
            enabled = attachEnabled,
            minHeight = attachSize,
            modifier = Modifier.size(attachSize),
            contentDescription = "Add attachment",
        )
        if (totals != null && !metrics.phone && width >= 448.dp) totals()
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * `.chat-composer-end` actions (chat-view.tsx:4459-4511): Send when idle; Queue + Interrupt while a
 * turn runs. Below 48rem the keys are 44dp icon-only squares (labels stay the accessible names)
 * and a disabled Queue key is hidden (globals.css:11944); wider they carry their legends.
 */
@Composable
private fun ComposerActions(
    metrics: ComposerMetrics,
    busy: Boolean,
    canQueue: Boolean,
    canSend: Boolean,
    onSubmit: () -> Unit,
    onInterrupt: () -> Unit,
) {
    val height = if (metrics.studio || metrics.touchKeys) TetherDimens.touchTargetDp else 33.6.dp
    val labelled = !metrics.phone
    val fontSize = if (metrics.studio) 12.48.sp else 10.24.sp
    val keyModifier = if (labelled) Modifier.height(height).widthIn(min = if (metrics.studio) 44.dp else 73.6.dp) else Modifier.size(44.dp)
    val padding = when {
        !labelled -> 0.dp
        metrics.studio -> 16.dp
        else -> 12.dp
    }
    val wear = if (!labelled) KeyWear.SendCompact else null
    if (busy) {
        if (labelled || canQueue) {
            TetherKey(
                onClick = onSubmit,
                classes = KeyClasses.ChatSend,
                label = if (labelled) "Queue" else null,
                icon = TetherIcons.Send,
                iconSize = 18.dp,
                fontSize = fontSize,
                enabled = canQueue,
                minHeight = height,
                modifier = keyModifier,
                contentPadding = padding,
                wearPattern = wear,
                contentDescription = "Queue message",
            )
        }
        TetherKey(
            onClick = onInterrupt,
            classes = KeyClasses.ChatInterrupt,
            label = if (labelled) "Interrupt" else null,
            icon = TetherIcons.CircleStop,
            iconSize = 18.dp,
            fontSize = fontSize,
            minHeight = height,
            modifier = keyModifier,
            contentPadding = padding,
            contentDescription = "Interrupt the current turn",
        )
    } else {
        TetherKey(
            onClick = onSubmit,
            classes = KeyClasses.ChatSend,
            label = if (labelled) "Send" else null,
            icon = TetherIcons.Send,
            iconSize = 18.dp,
            fontSize = fontSize,
            enabled = canSend,
            minHeight = height,
            modifier = keyModifier,
            contentPadding = padding,
            wearPattern = wear,
            contentDescription = "Send message",
        )
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
        Icon(TetherIcons.FileText, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
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
            Icon(TetherIcons.X, contentDescription = "Remove ${item.attachment.name}", tint = t.muted, modifier = Modifier.size(13.dp))
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

/** components/turn-activity.tsx `part`: the run row above the well, the totals in its toolbar. */
enum class TurnActivityPart { Both, Run, Totals }

/**
 * TurnActivity (visual-spec §4): run row (spinner + verb + elapsed/tokens) and
 * SESSION TOTAL row. MUTED, never violet. Elapsed derives from run.startedAt vs
 * the event-anchored [serverNow] — never raw device wall-clock vs journal ts.
 * [part] as the web's (turn-activity.tsx:42-44, 124-139, 160-164): [TurnActivityPart.Run] is the
 * run row only (nothing when idle), [TurnActivityPart.Totals] the toolbar's "SESSION" readout.
 */
@Composable
fun TurnActivity(
    projection: SessionProjection,
    session: AgentSession,
    serverNow: () -> Long,
    part: TurnActivityPart = TurnActivityPart.Both,
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
    if (part == TurnActivityPart.Run && run == null) return

    val tabularStyle = TextStyle(fontFeatureSettings = "tnum")
    if (part == TurnActivityPart.Totals) {
        ComposerTotals(totalActiveMs, if (accountedTurns > 0) totalTokens else null)
        return
    }

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
                    overflow = TextOverflow.Ellipsis,
                )
                // `.chat-activity-metrics { margin-left: auto }`: the readings sit at the right edge.
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
        if (part == TurnActivityPart.Both) Row(
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

/**
 * `.chat-composer-totals` (globals.css 11434-11453, studio.css:396): "SESSION" 0.56rem/700 with
 * 0.1em tracking in `--faint`, then the time (and settled tokens) in JetBrains Mono 0.66rem
 * `--muted`. Studio writes the label as authored, "Session", in the UI face (0.65rem/500).
 */
@Composable
private fun ComposerTotals(totalActiveMs: Long, totalTokens: Long?) {
    val t = LocalTetherTokens.current
    val studio = t.skin.family == com.tether.app.ui.theme.ThemeFamily.Studio
    Row(
        Modifier.padding(horizontal = t.css.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.2.dp),
    ) {
        Text(
            if (studio) "Session" else "SESSION",
            color = t.faint,
            fontFamily = Manrope,
            fontWeight = if (studio) androidx.compose.ui.text.font.FontWeight(500) else TetherWeights.strong,
            fontSize = if (studio) 10.4.sp else 8.96.sp,
            letterSpacing = if (studio) 0.em else 0.1.em,
            maxLines = 1,
            modifier = Modifier.semanticsLabel("Session"),
        )
        val values = buildList {
            add(elapsedLabel(totalActiveMs / 1000).ifEmpty { "0s" })
            totalTokens?.let { add(tokenLabel(it)) }
        }
        for (value in values) {
            Text(value, color = t.muted, fontFamily = JetBrainsMono, fontSize = 10.56.sp, maxLines = 1)
        }
    }
}

/** CSS uppercase keeps the original words as the accessible name (T3.2 rule). */
private fun Modifier.semanticsLabel(label: String): Modifier =
    this.then(Modifier.clearAndSetSemantics { contentDescription = label })

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
