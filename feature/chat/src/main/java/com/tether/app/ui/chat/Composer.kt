package com.tether.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
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
import com.tether.app.protocol.fold.liveBackgroundTaskCount
import com.tether.app.protocol.fold.openToolCount
import com.tether.app.protocol.helpers.QueueWait
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.Vocab
import com.tether.app.protocol.model.operatorQueuedMessages
import com.tether.app.client.ComposerControlsModel
import com.tether.app.client.ControlResult
import com.tether.app.client.InterruptResult
import com.tether.app.client.LEGACY_GROUP_VALUE
import com.tether.app.client.ModeVocabulary
import com.tether.app.client.OPENCODE_V2
import com.tether.app.client.SessionControl
import com.tether.app.client.LabelText
import com.tether.app.client.typedModelAllowed
import com.tether.app.client.looksLikeModelId
import com.tether.app.protocol.reduce.composerCommandList
import com.tether.app.protocol.reduce.TETHER_BLOCKED_COMMANDS
import com.tether.app.protocol.reduce.TETHER_DESYNC_COMMANDS
import com.tether.app.client.CommandGuard
import com.tether.app.client.RunCommandResult
import com.tether.app.protocol.DelegateMention
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import com.tether.app.protocol.reduce.resolveModelArg
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.SpinnerRing
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherSeam
import com.tether.app.ui.components.originalWords
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
import androidx.compose.runtime.saveable.rememberSaveable

/** T13.2 r2: the composer's Interrupt key. */
/** ta-8h5k: the composer as the chat screen stacks it (its intrinsic bounds, before any cut at the frame's bottom). */
internal const val CHAT_COMPOSER_TAG = "chat-composer"
internal const val INTERRUPT_KEY_TAG = "composer-interrupt"

/** ta-ceo (#229): the Stop confirmation's keys. */
internal const val STOP_ANYWAY_KEY_TAG = "composer-stop-anyway"
internal const val KEEP_RUNNING_KEY_TAG = "composer-keep-running"

/**
 * T6.7: what the operator is told when an Interrupt tap sent nothing (null: it was sent, or the
 * client already said why: [InterruptResult.NotConnected] toasts "The secure link is reconnecting").
 */
internal fun interruptRefusalCopy(result: InterruptResult): String? = when (result) {
    InterruptResult.Sent, InterruptResult.NotConnected -> null
    // ta-coik.24: NotLive means only "drawn for another server" (RealTetherClient interrupt).
    InterruptResult.NotLive -> OTHER_SERVER_NOT_SENT
    InterruptResult.Locked -> "This session can’t be interrupted from here."
    InterruptResult.NotCurrentTurn -> "That turn already ended — the turn running now was not interrupted."
}

/** T6.7 r3: why every interrupt control is locked while the turn is already being interrupted. */
internal const val INTERRUPTING_LOCK_COPY = "Interrupting… the turn is already stopping."

/** T13.2 r2: the run row of a copy that is not live ("Was running", still, not ticking). */
internal const val STALE_RUN_TAG = "composer-run-stale"

/**
 * T13.2 r2 (SYNC_DESIGN §4.2): what the composer's turn controls and readings stand on. There is no
 * default: every host says whether its copy is live.
 */
@androidx.compose.runtime.Immutable
class ComposerLiveness(
    /**
     * Why Interrupt (the key and a queued row's "Interrupt now") and the command keys cannot send
     * ([stopLockCopy]'s words); null = they can. ta-coik.22: the screen leaves a saved or catching-up
     * copy unlocked, as the web does ([commandKeyLock]); the client refuses off a live link.
     */
    val interruptLock: String?,
    /** Null while the copy is Live; else its freshness: the run row reads "Was running" and stops ticking. */
    val stale: com.tether.app.client.SessionSync?,
    /**
     * T6.7 r3: the turn whose interrupt the server reported failed ([com.tether.app.client.TetherClient.failedInterrupts]);
     * while it is still "cancelling", that failure unlocks the interrupt controls for a retry.
     */
    val failedInterruptTurn: String? = null,
) {
    companion object {
        /** A live copy that may be driven (previews, and tests of the live composer). */
        val Live = ComposerLiveness(interruptLock = null, stale = null)
    }
}

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
    /**
     * T6.7: interrupt the turn the tapped key was drawn for (its id): the ONE way an Interrupt (the
     * key or a queued row's "Interrupt now") reaches the client, which re-checks it all.
     */
    onInterrupt: (turnId: String) -> InterruptResult,
    onQueueEdit: (queueId: String, text: String) -> Unit,
    onQueueRemove: (queueId: String) -> Unit,
    onRequestControls: () -> Unit,
    /** T13.2 r2: whether the copy is live (Interrupt's lock, the run row's freshness). Required. */
    liveness: ComposerLiveness,
    modifier: Modifier = Modifier,
    /**
     * T7.4: the staged attachments of this session, staging picks, and the ONE send that carries
     * them (an explicit Send only; the client re-checks the link, the server and the session).
     */
    attachments: ComposerAttachments = ComposerAttachments.Unavailable,
    initialDraft: String? = null,
    awaitDraft: suspend () -> String = { "" },
    onDraftChange: (String) -> Unit = {},
    /** T6.4: the session's projection tree (the todo bar, the running background commands). */
    tree: com.tether.app.protocol.tree.JsObj? = null,
    /** T6.4: open / stop a background command (Stop is a tap-only operator control). */
    commandActions: CommandActions = CommandActions.Unavailable,
    /** T7.2: the session controls (Model / Effort / Mode / Fast, provider panels): tap-only, guarded. */
    controlActions: SessionControlActions = SessionControlActions.Unavailable,
    /** T7.2: the device's pinned legacy models (lib/model-picker.mjs groupModelOptions). */
    pinnedModels: List<String> = emptyList(),
    /** ta-coik.55: pin / unpin a legacy model in this server's [pinnedModels] (the Model menu's pin key); null draws none. */
    onToggleModelPin: ((String) -> Unit)? = null,
    /** T6.6: the session a handed-off source continued in (null: gone, or not handed off). */
    handoffTarget: AgentSession? = null,
    /** T6.6: open another session (the handoff lock's link). Navigation only, never a wire mutation. */
    onOpenSession: (String) -> Unit = {},
    /**
     * T7.3: the `!` command mode, the foreground command's Background key and the `@` Agents
     * (run-command / background-command / a delegated send): taps and explicit submits only, guarded.
     */
    runActions: ComposerCommandActions = ComposerCommandActions.Unavailable,
    /** T7.3 (v96): the slash palette opened — ask for the live command list with `warm` (a read). */
    onWarmControls: () -> Unit = {},
    /** ta-coik.19: this session's unresolved sends (the send-status row above the well). */
    sendRows: List<com.tether.app.client.PendingSendRow> = emptyList(),
    /** T8.4: the attach sheet's "Add issue or PR" reads (null: the row is not drawn). */
    github: ComposerGitHub? = null,
    /** T11.2: text shared from another app for this session, added to the end of the draft once it is hydrated. */
    inserts: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow(),
    /** T8.5: the `@` picker's Sessions and the takeover draft (null: neither is offered). */
    takeover: ComposerTakeover? = null,
    /** T8.6: the in-console browser toggle beside the paperclip (null: no key, as the web without `onToggleBrowser`). */
    browser: ComposerBrowser? = null,
    /** T8.6 part 2: the elements picked in the browser pane, riding the next send (null: none can be). */
    browserPicks: ComposerPicks? = null,
) {
    val t = LocalTetherTokens.current
    val metrics = composerMetrics()
    // ta-coik.20: the typed draft (and its cursor) survives a rotation, as a browser resize keeps the textarea.
    var field by rememberSaveable(session?.id, stateSaver = TextFieldValue.Saver) {
        val text = initialDraft ?: ""
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    val draft = field.text
    fun setDraft(text: String) {
        field = TextFieldValue(text, TextRange(text.length))
    }
    val currentOnDraftChange by rememberUpdatedState(onDraftChange)
    val currentAwaitDraft by rememberUpdatedState(awaitDraft)
    val currentInserts by rememberUpdatedState(inserts)
    LaunchedEffect(session?.id) {
        launch { snapshotFlow { field.text }.drop(1).collect { currentOnDraftChange(it) } }
        val stored = currentAwaitDraft()
        if (field.text.isEmpty() && stored.isNotEmpty()) setDraft(stored)
        currentInserts.collect { text ->
            setDraft(com.tether.app.ui.appendDraftText(field.text, text))
            // Mirrored at once: the change observer above may not have read its first value yet (drop(1)).
            currentOnDraftChange(field.text)
        }
    }
    val picked = attachments.staged
    val hasPicksDrawn = browserPicks?.items?.isNotEmpty() == true

    val activeTurn = projection?.activeTurnId?.let { projection.turnsById[it] }
    val busy = activeTurn != null
    val hasApproval = activeTurn?.pendingApprovals?.isNotEmpty() == true
    val hasQuestion = activeTurn?.pendingQuestions?.isNotEmpty() == true

    val claude = session?.provider == "claude"
    var menuDismissed by rememberSaveable(session?.id) { mutableStateOf(false) }
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

    // T6.7: the turn the Interrupt keys are drawn for; a tap interrupts exactly that turn or nothing.
    val interruptTurnId = if (activeTurn != null) projection?.activeTurnId else null
    // T6.7 r3: once the turn is being interrupted, every interrupt control of the session is locked
    // (a second tap could reach the turn the queue flushes into next, before this client sees it),
    // unless the server said that interrupt failed: then they unlock for a retry.
    val interruptLock = liveness.interruptLock
        ?: if (activeTurn?.status == Vocab.TURN_CANCELLING && liveness.failedInterruptTurn != interruptTurnId) INTERRUPTING_LOCK_COPY else null
    fun interruptTurn(drawnFor: String) {
        interruptRefusalCopy(onInterrupt(drawnFor))?.let(::flash)
    }

    // T7.3 (chat-view.tsx:2158-2167): `!` command mode, and whether the running turn is a FOREGROUND
    // command run (then Interrupt reads "Stop" and Background replaces Queue).
    val foregroundTurn = remember(tree) { CommandGuard.foregroundCommandTurn(tree) }
    val commandRunning = foregroundTurn != null && foregroundTurn == interruptTurnId

    // ta-ceo (issue #229, chat-view.tsx:1973-1979): what a Stop would destroy right now, read off the
    // projection (the server's own `_liveWork` reads, minus its warm-child level signal), and its
    // price. With live work Interrupt needs a second, informed tap that names the price; the
    // confirmation belongs to the turn it was asked for (a new turn or another session drops it), and
    // lapses when there is no price left to confirm or the copy cannot interrupt at all.
    val liveWork = remember(tree) { QueueWait.LiveWork(openToolCount(tree), liveBackgroundTaskCount(tree)) }
    val stopCost = QueueWait.stopCostCopy(liveWork)
    var confirmStop by remember(session?.id, interruptTurnId) { mutableStateOf(false) }
    LaunchedEffect(stopCost.isEmpty(), interruptLock != null, commandRunning) {
        if (stopCost.isEmpty() || interruptLock != null || commandRunning) confirmStop = false
    }
    val confirmingStop = confirmStop && stopCost.isNotEmpty() && interruptLock == null && !commandRunning
    val commandMode = runActions.commandMode && draft.startsWith("!")
    // ta-coik.22: the same lock as Interrupt ([commandKeyLock]: read-only / handed off, never a stale copy).
    val commandLock = liveness.interruptLock
    val readOnly = session?.readOnly == true
    val handedOffNow = !session?.handedOffTo.isNullOrEmpty()

    /** chat-view.tsx:3083-3102 dispatchCommand: `background` = the "Background" key. */
    fun dispatchCommand(background: Boolean) {
        if (session == null) return
        if (picked.isNotEmpty()) {
            flash("Command mode can’t include attachments — remove them to run a command.")
            return
        }
        val command = field.text.trim().drop(1).trim() // the leading "!"
        if (command.isEmpty()) {
            flash("Type a command after “!” to run it.")
            return
        }
        if (command.toByteArray(Charsets.UTF_8).size > CommandGuard.COMMAND_MAX_BYTES) {
            flash(COMMAND_TOO_LONG_COPY)
            return
        }
        if (!background && busy) {
            flash("Wait for the current turn to finish, or use “Send to background”.")
            return
        }
        commandLock?.let {
            flash(it)
            return
        }
        val result = runActions.onRun(command, background)
        if (result == RunCommandResult.Sent) setDraft("") else runRefusalCopy(result)?.let(::flash)
    }

    /** v54: move the foreground command of the turn the key was drawn for to the background. */
    fun backgroundTurn(drawnFor: String) {
        commandLock?.let {
            flash(it)
            return
        }
        backgroundRefusalCopy(runActions.onBackground(drawnFor))?.let(::flash)
    }

    // T7.3 (chat-view.tsx:2712-2834): the `@` Agents picker and the pending delegate mention. The
    // mention belongs to the session and the server it was picked on (a switch drops it).
    var atDismissed by rememberSaveable(session?.id) { mutableStateOf(false) }
    var delegateMention by rememberSaveable(session?.id, runActions.origin, stateSaver = DelegateMentionSaver) { mutableStateOf<DelegateMention?>(null) }
    // T8.5 (chat-view.tsx 90fbb9f :1809-1828): the takeover being composed (its source), its editable
    // text and the summary's fold; per session, like the web's per-session ChatView.
    var handoffSourceId by rememberSaveable(session?.id) { mutableStateOf<String?>(null) }
    var handoffText by rememberSaveable(session?.id) { mutableStateOf("") }
    var handoffSummaryCollapsed by rememberSaveable(session?.id) { mutableStateOf(true) }
    var handoffSeededFor by rememberSaveable(session?.id) { mutableStateOf<String?>(null) }
    fun atQueryNow(text: String): String? =
        if (session != null && !readOnly && !handedOffNow && !(runActions.commandMode && text.startsWith("!")) && handoffSourceId == null) atQueryOf(text) else null
    val atQuery = atQueryNow(draft)
    val agentRows = remember(atQuery, runActions.agents) { atQuery?.let { matchAgents(runActions.agents, it) } ?: emptyList() }
    // T8.5 (chat-view.tsx :2791-2802): the Sessions section, below the Agents.
    fun sessionRowsFor(query: String?): List<AgentSession> =
        if (query != null && session != null && takeover != null) handoffCandidates(takeover.sessions, session, query) else emptyList()
    val sessionRows = remember(atQuery, takeover?.sessions, session?.id, session?.cwd) { sessionRowsFor(atQuery) }
    val atMenuOpen = atQuery != null && !atDismissed && (agentRows.isNotEmpty() || sessionRows.isNotEmpty())
    val atActive = atQuery != null
    LaunchedEffect(atActive) { if (atActive && runActions.agents.isEmpty()) runActions.onRequestAgents() }
    /** The open `@` menu's rows NOW (agents first, then sessions), or null when it is closed. */
    fun liveAtMenu(): List<Any>? {
        val q = atQueryNow(field.text) ?: return null
        val rows = matchAgents(runActions.agents, q) + sessionRowsFor(q)
        return if (!atDismissed && rows.isNotEmpty()) rows else null
    }
    fun beginDelegate(entry: com.tether.app.client.ProviderCatalogEntry) {
        delegateMention = mentionFor(entry)
        atDismissed = true
        setDraft(stripAtToken(field.text))
    }
    /** chat-view.tsx :2834-2851 beginHandoff: ask for the brief and open the takeover draft. */
    fun beginHandoff(source: AgentSession) {
        handoffRefusal(source)?.let {
            flash(it)
            return
        }
        takeover?.onRequestBrief?.invoke(source.id)
        handoffSourceId = source.id
        atDismissed = true
        setDraft(stripAtToken(field.text))
    }
    fun pickAtRow(row: Any?) {
        when (row) {
            is com.tether.app.client.ProviderCatalogEntry -> beginDelegate(row)
            is AgentSession -> beginHandoff(row)
        }
    }
    val handoffEntry = handoffSourceId?.let { id -> takeover?.briefs?.get(id) }
    // chat-view.tsx :2911-2924: the brief seeds the editable text once per source, never over edits.
    LaunchedEffect(handoffSourceId, handoffEntry) {
        val id = handoffSourceId
        if (id == null) {
            handoffSeededFor = null
        } else if (handoffEntry != null && handoffSeededFor != id) {
            handoffText = handoffEntry.instruction
            handoffSummaryCollapsed = true
            handoffSeededFor = id
        }
    }
    /** chat-view.tsx :2925-2929. */
    fun cancelHandoff() {
        handoffSourceId?.let { takeover?.onClearBrief?.invoke(it) }
        handoffSourceId = null
        handoffText = ""
    }
    /** chat-view.tsx :2935-2950 commitHandoff: claim the source and start THIS session's turn. */
    fun commitHandoff() {
        val source = handoffSourceId ?: return
        val text = handoffText.trim()
        if (text.isEmpty()) {
            flash(HANDOFF_EMPTY_COPY)
            return
        }
        if (takeover?.onHandoff?.invoke(source, text) == true) {
            takeover.onClearBrief(source)
            handoffSourceId = null
            handoffText = ""
        } else {
            flash(HANDOFF_NOT_CONNECTED_COPY)
        }
    }
    val delegateEntry = delegateMention?.let { m -> runActions.agents.firstOrNull { it.provider == m.provider } }

    val models = controls?.models ?: emptyList()
    // T7.2: the row's state, derived from the session, its controls reply and the Codex catalog.
    val composerControls = remember(session, controls, controlActions.codex, controlActions.opencode, pinnedModels) {
        session?.let { ComposerControlsModel.derive(it, controls, controlActions.codex, pinnedModels, controlActions.opencode) }
    }
    // The web swaps the pill row for the sheet key below 64rem of VIEWPORT (globals.css:7347-7352).
    val wideRow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 1024
    var sheetAt by rememberSaveable(session?.id, stateSaver = SheetViewSaver) { mutableStateOf<SheetView?>(null) }
    // T7.3 (chat-view.tsx:2249-2273): Codex has no command catalog of its own; the one action Tether
    // can run from the palette is compaction, once the live catalog says it is dispatchable.
    val codexCompactReady = session?.provider == "codex" && session.engineGeneration == com.tether.app.client.CODEX_V2 &&
        controlActions.codex?.snapshot?.compactionStatus == "ready"
    val collator = remember { IcuJsCollator.forLocale() }
    val commands = remember(projection?.cliInventory, controls, session?.provider, codexCompactReady, collator) {
        if (session?.provider == "codex") {
            if (codexCompactReady) listOf(SessionCommandOption("compact", "Summarize conversation to prevent hitting the context limit", "", supported = true)) else emptyList()
        } else {
            composerCommandList(projection?.cliInventory?.commands, controls?.commands ?: emptyList(), collator)
        }.filter(::offerableCommand)
    }

    // The command-name fragment being typed ("/mod" -> "mod"), or null when the
    // draft isn't a bare slash command — drives whether the menu shows. T7.3: every
    // provider gets the palette (chat-view.tsx:2157); what differs is what it offers.
    fun slashQueryOf(text: String): String? =
        if (text.startsWith("/") && !text.drop(1).contains(" ")) text.drop(1) else null
    fun matchesFor(query: String?): List<SessionCommandOption> {
        if (query == null) return emptyList()
        val q = query.lowercase()
        return commands.filter { command ->
            command.name.lowercase().startsWith(q) ||
                command.aliases.orEmpty().any { it.lowercase().startsWith(q) }
        }
    }
    val slashQuery = slashQueryOf(draft)
    val menuMatches = remember(slashQuery, commands) { matchesFor(slashQuery) }
    val menuOpen = slashQuery != null && !menuDismissed && menuMatches.isNotEmpty() && !busy
    // v96 (chat-view.tsx:2707-2710): once per palette open, ask for the live list with `warm`.
    val slashActive = slashQuery != null
    LaunchedEffect(slashActive, readOnly) { if (slashActive && !readOnly) onWarmControls() }

    /**
     * The open menu's matches for the text in the field NOW (null = closed). Key and IME actions read
     * the live [field], never the composition-time [draft]: a keystroke and an Enter can land in one
     * frame, before the recomposition that would refresh [draft] (T7.1 verifier finding).
     */
    fun liveMenu(): List<SessionCommandOption>? {
        val query = slashQueryOf(field.text) ?: return null
        val matches = matchesFor(query)
        return if (!menuDismissed && matches.isNotEmpty() && !busy) matches else null
    }

    /**
     * T7.2: one operator choice to the client's guard; a refusal is said in words. Every switch,
     * the most permissive ones included, is sent on the tap, as on the web.
     */
    fun sendControl(control: SessionControl, notOfferedCopy: String? = null): Boolean {
        val result = controlActions.onControl(control)
        if (result != ControlResult.Sent) {
            (if (result == ControlResult.NotOffered && notOfferedCopy != null) notOfferedCopy else controlRefusalCopy(result))?.let(::flash)
        }
        return result == ControlResult.Sent
    }

    fun codexChooseModel(modelId: String) {
        val s = session ?: return
        val snapshot = controlActions.codex?.snapshot ?: return
        val model = snapshot.models.items.firstOrNull { it.id == modelId } ?: return
        // chat-view.tsx:2368-2372: keep the effort when the new model supports it, else its default.
        val wanted = s.reasoningEffort ?: model.defaultReasoningEffort
        val effortId = if (model.reasoningEfforts.any { it.id == wanted }) wanted else model.defaultReasoningEffort
        sendControl(SessionControl.CodexModelSelection(modelId, effortId, snapshot.revision))
    }

    /** chat-view.tsx:2896-2921. [unlisted]: a typed `/model` id the list does not carry. */
    fun chooseModel(value: String, unlisted: Boolean = false) {
        val s = session ?: return
        val c = composerControls ?: return
        if (c.codexV2) return codexChooseModel(value)
        if (value == LEGACY_GROUP_VALUE) return
        val model = models.firstOrNull { it.value == value }
        // Round 3 (F1): the flash reads the cleaned name, never raw server text.
        val displayName = model?.displayName?.let { LabelText.label(it) }?.ifEmpty { null } ?: LabelText.label(value).ifEmpty { LabelText.visibleValue(value) }
        val typedRefusal = if (unlisted) "“${LabelText.visibleValue(value)}” wasn’t accepted as a model id for this session — the model was not changed." else null
        if (sendControl(SessionControl.Model(value, typed = unlisted), typedRefusal)) {
            // A model that cannot express the current effort clears it (never a mismatched
            // --variant) — round 2 (L3): only once the model itself went out.
            val effort = s.reasoningEffort
            var effortRefusal: String? = null
            if (model != null && !effort.isNullOrEmpty() && model.variants.orEmpty().none { it.value == effort }) {
                // Round 3 (I-d): a refused clear is said, not swallowed.
                val cleared = controlActions.onControl(SessionControl.Effort(""))
                if (cleared != ControlResult.Sent) effortRefusal = "The model changed, but its reasoning effort was not reset: " + (controlRefusalCopy(cleared) ?: "")
            }
            val isDefaultChoice = value.isEmpty() || value == "default"
            flash(
                when {
                    isDefaultChoice -> "Model reset to the CLI default."
                    unlisted -> "Model set to ${LabelText.visibleValue(value)} — not in the known list, so the CLI validates it on the next turn."
                    else -> "Model set to $displayName."
                },
            )
            effortRefusal?.let(::flash)
            if (field.text.startsWith("/model")) setDraft("")
            onRequestControls()
        }
    }

    fun chooseEffort(value: String) {
        val c = composerControls ?: return
        if (c.codexV2) {
            val modelId = c.model?.value?.takeIf { it.isNotEmpty() } ?: return
            val revision = controlActions.codex?.snapshot?.revision ?: return
            sendControl(SessionControl.CodexModelSelection(modelId, value, revision))
            return
        }
        if (sendControl(SessionControl.Effort(value))) {
            // Round 4 (F1): the chosen option's cleaned label, never the raw value.
            val shown = c.effort?.options?.firstOrNull { it.value == value }?.label ?: LabelText.label(value).ifEmpty { LabelText.visibleValue(value) }
            flash(if (value.isNotEmpty()) "Reasoning effort set to $shown." else "Reasoning effort reset to the model's default.")
        }
    }

    fun chooseMode(value: String) {
        val c = composerControls ?: return
        if (c.codexV2) {
            val revision = controlActions.codex?.snapshot?.revision ?: return
            sendControl(SessionControl.CodexCollaboration(value, revision))
            return
        }
        val option = c.mode?.options?.firstOrNull { it.value == value }
        if (option?.disabled == true) return
        // chat-view.tsx:2500-2510: Auto and a danger agent are sent like any other mode.
        sendControl(SessionControl.Mode(value))
    }

    fun toggleAuto() {
        val c = composerControls ?: return
        val auto = c.auto ?: return
        if (c.codexV2) {
            val revision = controlActions.codex?.snapshot?.revision ?: return
            // chat-view.tsx:2552-2555 + 2488-2497: set-approval-policy "never" / null, on the tap.
            sendControl(SessionControl.CodexAutoApprove(!auto.on, revision))
            return
        }
        // chat-view.tsx:2556-2560: the same set-mode the Mode row sends.
        sendControl(SessionControl.Mode(if (auto.on) "default" else ModeVocabulary.AUTO))
    }

    val providerV2 = session != null && (composerControls?.codexV2 == true || (session.provider == "opencode" && session.engineGeneration == OPENCODE_V2))
    val handlers = ControlHandlers(
        chooseModel = { chooseModel(it) },
        toggleModelPin = onToggleModelPin,
        chooseEffort = ::chooseEffort,
        chooseMode = ::chooseMode,
        toggleAuto = ::toggleAuto,
        setFast = { enabled -> sendControl(SessionControl.FastMode(enabled)) },
        setAutoApprove = { on -> if (on != composerControls?.auto?.on) toggleAuto() },
        // T6.6 (chat-view.tsx:2544-2548): exactly the flip of the value the toggle was drawn with,
        // sent on the tap either way. r3: never while locked (offline, or a copy that is not live:
        // T13.2's rule), whichever key asked.
        setAutoContinue = { enabled ->
            val ac = composerControls?.autoContinue
            if (ac != null && enabled != ac.on && controlActions.lock == null) {
                if (sendControl(SessionControl.AutoContinueOnLimit(enabled))) flash(if (enabled) AUTO_CONTINUE_ON_FLASH else AUTO_CONTINUE_OFF_FLASH)
            }
        },
        openProviderControls = if (providerV2) {
            {
                if (composerControls?.codexV2 == true) controlActions.onRequestCodex() else controlActions.onRequestOpencode()
                sheetAt = SheetView.Provider
            }
        } else {
            null
        },
        openSheet = { view -> sheetAt = view },
        requestProviderControls = {
            if (composerControls?.codexV2 == true) controlActions.onRequestCodex() else controlActions.onRequestOpencode()
        },
    )

    /** chat-view.tsx:3015-3027: a listed match, else a plausible id passed through (the CLI validates it), else refused. */
    fun modelCommand(arg: String) {
        val match = resolveModelArg(arg, models)
        if (match != null) {
            chooseModel(match.value)
            return
        }
        if (looksLikeModelId(arg)) {
            chooseModel(arg, unlisted = true)
            return
        }
        flash("“${LabelText.label(arg)}” doesn’t look like a model id. Try /model to see what the CLI offers.")
    }

    /**
     * T7.3 (chat-view.tsx:2978-3056) runSlashCommand: true when the command was CONSUMED here (handled
     * natively, dispatched as a guarded control, or refused); false to let [submit] forward the raw
     * text to the CLI as an ordinary prompt — the web's passthrough: the CLI advertises exactly what it
     * can dispatch headless, and a leading "/" in prompt text is its own to parse.
     */
    fun runSlashCommand(raw: String): Boolean {
        val s = session ?: return false
        val body = raw.drop(1)
        val parts = body.split(Regex("\\s+"))
        val name = parts.first()
        val arg = parts.drop(1).joinToString(" ").trim()
        val info = commands.find { it.name == name || it.aliases.orEmpty().contains(name) }
        val canonical = info?.name ?: name

        // `/model <name>` mutates what Tether owns: a guarded control (T7.2), never on Codex. Bare
        // `/model` is a read and is forwarded to the CLI like any other command (chat-view.tsx 29537e0
        // :3081 `&& arg`, :193-198): the operator sees the CLI's own list of the ids it accepts.
        if (canonical == "model" || name == "model") {
            if (arg.isEmpty()) return false
            if (s.provider == "codex") return false
            if (!typedModelAllowed(s.provider) || composerControls?.model == null) return false
            modelCommand(arg)
            return true
        }
        // Codex's one dispatchable command: the same guarded provider action as "Compact context".
        if (canonical == "compact" && codexCompactReady) {
            val revision = controlActions.codex?.snapshot?.revision ?: return true
            if (sendControl(SessionControl.CodexCompaction(revision))) setDraft("")
            return true
        }
        if (canonical in TETHER_BLOCKED_COMMANDS || info?.supported == false) {
            flash("/${LabelText.label(canonical)} would end this session — run it from a terminal instead.")
            setDraft("")
            return true
        }
        if (canonical in TETHER_DESYNC_COMMANDS) {
            flash("/${LabelText.label(canonical)} changes what the model has in context — the transcript above is kept, but no longer matches it.")
        }
        return false
    }

    /** chat-view.tsx 29537e0 :3126-3134 acceptCommand: completing the name leaves the draft ready for an argument. */
    fun acceptCommand(command: SessionCommandOption) {
        menuDismissed = true
        if (!command.supported) {
            flash("/${LabelText.label(command.name)} would end this session — run it from a terminal instead.")
            setDraft("")
            return
        }
        // /model is not special-cased (chat-view.tsx 29537e0 :3126-3134): `/model ` invites the id the
        // picker's list may not carry, which is the whole point of typing it.
        setDraft("/${command.name} ")
    }

    fun submit() {
        if (session == null) return
        // The live field, not the composition-time [draft] (see [liveMenu]).
        val text = field.text.trim()
        val hasAttachments = picked.isNotEmpty()
        val pickItems = browserPicks?.items.orEmpty()
        val hasPicks = pickItems.isNotEmpty()
        if (text.isEmpty() && !hasAttachments && !hasPicks) return
        // v53/v54: `!` command mode gets first refusal (Enter runs it in the FOREGROUND).
        if (runActions.commandMode && field.text.startsWith("!")) {
            dispatchCommand(false)
            return
        }
        if (text.startsWith("/") && !hasAttachments && runSlashCommand(text)) return
        // #162 (chat-view.tsx 90fbb9f :3190-3210): picked elements ride the next prompt as a delimited
        // descriptor block, their screenshots as attachments. Idle only, never queued (a pick is
        // reference content for THIS prompt, not a standing instruction).
        if (hasPicks && browserPicks != null) {
            if (busy) {
                flash("Wait for the current turn to finish before sending selected elements.")
                return
            }
            val block = com.tether.app.client.BrowserPicks.formatDescriptorBlock(pickItems.map { it.descriptor }, browserPicks.pageUrl)
            val combined = listOf(block, text).filter { it.isNotEmpty() }.joinToString("\n\n")
            val shots = pickItems.mapNotNull { it.screenshot }
            if (!hasAttachments && shots.isEmpty()) {
                if (onSend(combined, emptyList())) {
                    setDraft("")
                    browserPicks.onClear()
                }
            } else {
                val result = browserPicks.send(combined, shots)
                if (result == com.tether.app.client.AttachmentSendResult.Sent) {
                    setDraft("")
                    browserPicks.onClear()
                } else {
                    attachmentRefusalCopy(result)?.let(::flash)
                }
            }
            return
        }
        // P2.4 (chat-view.tsx:3145-3167): a delegation rides an idle send only, never the queue.
        delegateMention?.let { mention ->
            if (text.isEmpty()) {
                flash("Describe what the delegated agent should do — the text goes to it as its prompt.")
                return
            }
            if (busy) {
                flash("Wait for the current turn to finish before delegating.")
                return
            }
            if (hasAttachments) {
                // T7.4: a delegation that carries attachments takes the attachments' one guarded path.
                val result = attachments.send(text, mention)
                if (result == com.tether.app.client.AttachmentSendResult.Sent) {
                    setDraft("")
                    delegateMention = null
                } else {
                    attachmentRefusalCopy(result)?.let(::flash)
                }
                return
            }
            if (runActions.onSendDelegated(text, emptyList(), mention)) {
                setDraft("")
                delegateMention = null
            } else {
                flash("That agent isn’t offered for this session any more — nothing was sent.")
            }
            return
        }
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
        } else if (hasAttachments) {
            // T7.4: this tap is the send. Like the web (use-tether.ts filePending), one that never reached
            // the wire is rolled back and refused: the draft and the chips stay and the flash says why
            // (offline: "Not connected — … were not sent."); one that went out is held in memory and
            // resent after a reconnect (up to 5 tries / 10 min), never persisted (ta-coik.3 r2).
            val result = attachments.send(text, null)
            if (result == com.tether.app.client.AttachmentSendResult.Sent) setDraft("") else attachmentRefusalCopy(result)?.let(::flash)
        } else {
            // Refused sends (§5.6 rollback) keep the draft.
            if (onSend(text, emptyList())) setDraft("")
        }
    }

    /** chat-view.tsx:3190-3248: the slash menu gets the keys first, then Enter submits. */
    fun onComposerKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || field.composition != null) return false
        val enter = (event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isShiftPressed
        // v54 (chat-view.tsx:2185-2199): Ctrl/Cmd+B backgrounds the RUNNING foreground command, and only then.
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        if (ctrl && !event.isShiftPressed && !event.isAltPressed && event.key == Key.B) {
            val drawnFor = foregroundTurn?.takeIf { it == interruptTurnId }
            if (drawnFor != null) {
                backgroundTurn(drawnFor)
                return true
            }
        }
        val menu = liveMenu()
        if (menu != null) {
            when {
                event.key == Key.Escape -> {
                    menuDismissed = true
                    return true
                }
                event.key == Key.Tab || enter -> {
                    menu.firstOrNull()?.let(::acceptCommand)
                    return true
                }
            }
        }
        val at = liveAtMenu()
        if (at != null) {
            when {
                event.key == Key.Escape -> {
                    atDismissed = true
                    return true
                }
                event.key == Key.Tab || enter -> {
                    pickAtRow(at.firstOrNull())
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
    // T7.4 (attach-sheet.tsx, touch shell): the paperclip opens the sheet; its rows open the Photo
    // Picker (no storage permission), the clipboard's pictures, or the document picker. What comes
    // back is staged (never sent): only Send transmits it.
    var attachSheetOpen by rememberSaveable(session?.id) { mutableStateOf(false) }
    val currentAttachments by rememberUpdatedState(attachments)
    fun stageSources(sources: List<AttachmentSource>) {
        if (sources.isEmpty()) return
        scope.launch { currentAttachments.stage(sources).lastOrNull()?.let(::flash) }
    }
    // M1 (r2): only another app's content:// provider is ever read (never file://, never our own).
    val uriPolicy = remember(context) { AttachmentUriPolicy.of(context) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(com.tether.app.protocol.helpers.AttachmentDraft.MAX_ATTACHMENTS)) { uris ->
        stageSources(uris.map { ContentUriSource(context.contentResolver, it, uriPolicy) })
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        stageSources(uris.orEmpty().map { ContentUriSource(context.contentResolver, it, uriPolicy) })
    }
    // T8.4 (attach-sheet.tsx :115-232): the GitHub list's state, kept per session and server so a
    // re-open of the sheet does not read again; a pick is staged like a file (chat-view.tsx :2695-2723).
    val githubSource = github?.source
    val githubOrigin = github?.origin
    val attachGitHub = remember(session?.id, githubSource, githubOrigin) {
        githubSource?.let { AttachGitHubController(it, githubOrigin, scope) }
    }
    androidx.compose.runtime.DisposableEffect(attachGitHub) { onDispose { attachGitHub?.cancel() } }
    // ta-coik.3: the camera row (the picture is staged like a pick, never sent).
    val takePhoto = rememberCameraCapture(onSources = ::stageSources, onFlash = ::flash)
    fun pasteImage() {
        // The clip is read here (the clipboard answers the focused app); its items are looked at
        // off the main thread (L3: a type query per item is a call into another app's provider).
        val clip = try {
            context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
        } catch (_: RuntimeException) {
            flash(AttachmentCopy.CLIPBOARD_UNREADABLE)
            return
        }
        scope.launch {
            val sources = try {
                withContext(Dispatchers.IO) { ClipboardImages.sources(clip, context.contentResolver, uriPolicy) }
            } catch (_: RuntimeException) {
                flash(AttachmentCopy.CLIPBOARD_UNREADABLE)
                return@launch
            }
            if (sources.isEmpty()) flash(AttachmentCopy.NO_CLIPBOARD_IMAGE) else currentAttachments.stage(sources).lastOrNull()?.let(::flash)
        }
    }

    val inputInteraction = remember { MutableInteractionSource() }
    val inputFocused by inputInteraction.collectIsFocusedAsState()

    Column(modifier = modifier.fillMaxWidth().background(t.graphite)) {
        // `.chat-composer { border-top: 1px solid var(--line-strong); box-shadow: inset 0 1px 0
        // var(--seam-lip) }`; Studio's deck has no border (studio.css:383).
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

                // ta-coik.19 (chat-view.tsx 90fbb9f :3754-3768): the operator never watches an idle-looking
                // chat while a send is unresolved.
                ComposerSendRow(sendRows)

                if (projection != null && session != null) {
                    TurnActivity(projection = projection, session = session, serverNow = serverNow, part = TurnActivityPart.Run, stale = liveness.stale)
                }

                // chat-view.tsx:3709-3776: sessions Tether does not set say so above the well.
                if (session != null && composerControls != null) {
                    if (composerControls.legacyCodexHint) LegacyCodexHintRow(session.provider)
                    // T6.6 (chat-view.tsx:3718-3723): a read-only session says why, in words.
                    if (session.readOnly) ReadOnlyRow()
                    composerControls.restored?.let { RestoredSettingsRow(session.provider, it) }
                } else if (session?.readOnly == true) {
                    ReadOnlyRow()
                }
                notice?.let { ComposerNotice(it) }
                // T6.4 (chat-view.tsx:3780-3830): the todo bar, then the RUNNING background commands.
                val progress = remember(tree) { selectProgress(tree) }
                progress?.let { TodoBar(it, session?.id) }
                val runningCommands = remember(tree) { runningBackgroundCommands(tree) }
                if (session != null) RunningCommandsBar(runningCommands, commandActions)
                // T6.6 (chat-view.tsx:3814-3833): a handed-off source's composer is replaced by
                // "Continued in →"; a read-only session's by the replay-only flag. No input, no keys.
                val handedOff = !session?.handedOffTo.isNullOrEmpty()
                if (session != null && handedOff) {
                    HandoffLockRow(handoffTarget, onOpenSession)
                } else if (session?.readOnly == true) {
                    ReplayOnlyFlag(session.provider)
                } else {
                if (commandMode) CommandModeFlag()
                if (menuOpen) {
                    SlashCommandMenu(matches = menuMatches, onAccept = { acceptCommand(it) })
                }
                if (atMenuOpen) {
                    MentionMenu(agentRows, onPick = ::beginDelegate, sessions = sessionRows, now = takeover?.now?.invoke() ?: 0L, onPickSession = ::beginHandoff)
                }
                handoffSourceId?.let { sourceId ->
                    HandoffDraftPanel(
                        source = takeover?.sessions?.firstOrNull { it.id == sourceId },
                        brief = handoffEntry,
                        text = handoffText,
                        onTextChange = { handoffText = it },
                        summaryCollapsed = handoffSummaryCollapsed,
                        onToggleSummary = { handoffSummaryCollapsed = !handoffSummaryCollapsed },
                        onCancel = ::cancelHandoff,
                        onCommit = ::commitHandoff,
                    )
                }

                // v133 (T15.6): only the operator's own drafts, as chat-view.tsx:1914
                // operatorQueuedMessages — a Tether notice ("system") is not his to edit or remove.
                val queued = operatorQueuedMessages(projection?.queuedMessages.orEmpty())
                if (queued.isNotEmpty()) {
                    QueuedMessages(
                        queued = queued,
                        onSave = onQueueEdit,
                        onRemove = onQueueRemove,
                        sessionId = session?.id,
                        interruptTurnId = interruptTurnId,
                        onInterruptNow = ::interruptTurn,
                        interruptLock = interruptLock,
                        liveWork = liveWork,
                        serverNow = serverNow,
                        stale = liveness.stale != null,
                    )
                }

                if (hasPicksDrawn) {
                    // chat-view.tsx :4097-4120 `.chat-attachments` ("Selected page elements to send"), above the files.
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Selected page elements to send" },
                        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
                        verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
                    ) {
                        browserPicks?.items?.forEach { pick ->
                            BrowserPickChip(pick = pick, onRemove = { browserPicks.onRemove(pick) })
                        }
                    }
                }

                if (picked.isNotEmpty()) {
                    // `.chat-attachments` ("Attachments to send"): wrapping chips, `space-xs` apart.
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Attachments to send" },
                        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
                        verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
                    ) {
                        picked.forEach { item ->
                            StagedAttachmentChip(item = item, onRemove = { attachments.onRemove(item.id) })
                        }
                    }
                }

                delegateMention?.let { mention ->
                    DelegateBar(mention = mention, entry = delegateEntry, onChange = { delegateMention = it }, onRemove = { delegateMention = null })
                }

                val liveControls = composerControls?.takeIf { it.live && session != null }
                ComposerWell(metrics = metrics, inputFocused = inputFocused, command = commandMode) {
                    ComposerInput(
                        value = field,
                        onValueChange = { next ->
                            // Re-arm the slash menu after an Escape/dismiss once the
                            // operator keeps editing a slash (web onDraftChange).
                            if (next.text != field.text && menuDismissed) menuDismissed = false
                            if (next.text != field.text && atDismissed) atDismissed = false
                            field = next
                        },
                        placeholder = if (busy) PLACEHOLDER_BUSY else PLACEHOLDER_IDLE,
                        enabled = session != null,
                        metrics = metrics,
                        onKey = ::onComposerKey,
                        onImeSend = {
                            val menu = liveMenu()
                            val at = liveAtMenu()
                            when {
                                menu != null -> menu.firstOrNull()?.let(::acceptCommand)
                                at != null -> pickAtRow(at.firstOrNull())
                                else -> submit()
                            }
                        },
                        interactionSource = inputInteraction,
                        command = commandMode,
                    )
                    ComposerToolbar(
                        metrics = metrics,
                        width = composerToolbarContentWidth(deckWidth - deck.start - deck.end),
                        onAttach = { attachSheetOpen = true },
                        // ta-ceo: on a phone the Stop confirmation takes the attach and settings keys'
                        // room for the moment it is open (its price must be read whole; the web phone
                        // hides the words instead, globals.css:7894).
                        showAttach = !(metrics.phone && confirmingStop),
                        browser = browser,
                        // The web keeps the paperclip live while a turn runs; submit() asks the operator to wait.
                        attachEnabled = session != null,
                        totals = if (projection != null && session != null) {
                            { TurnActivity(projection = projection, session = session, serverNow = serverNow, part = TurnActivityPart.Totals, stale = liveness.stale) }
                        } else {
                            null
                        },
                        // T7.2: from 64rem the pill row sits above the footer; below it, one sheet key.
                        options = if (liveControls != null && wideRow) {
                            { ComposerOptionsRow(liveControls, session!!.provider, controlActions.lock, handlers) }
                        } else {
                            null
                        },
                        settingsKey = if (liveControls != null && !wideRow && !(metrics.phone && confirmingStop)) {
                            { mod ->
                                val hasOther = liveControls.effort != null || liveControls.mode != null || liveControls.fastMode != null || liveControls.auto != null || liveControls.autoContinue != null || handlers.openProviderControls != null
                                SessionSettingsTrigger(
                                    label = liveControls.model?.label?.ifEmpty { null } ?: "Select model",
                                    provider = session!!.provider,
                                    autoOn = liveControls.auto?.on == true || (liveControls.mode?.current?.danger == true && !liveControls.unknownMode),
                                    unknownMode = liveControls.unknownMode,
                                    lock = controlActions.lock,
                                    hasOtherSettings = hasOther,
                                    onOpen = {
                                        onRequestControls()
                                        sheetAt = if (hasOther) SheetView.Root else SheetView.Model
                                    },
                                    modifier = mod,
                                    // ta-8h5k (M1): 48rem-63.99rem draws the 36 dp pill in its own row above the footer.
                                    pill = !metrics.phone,
                                )
                            }
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
                            sessionId = session?.id,
                            interruptTurnId = interruptTurnId,
                            onInterrupt = ::interruptTurn,
                            interruptLock = interruptLock,
                            stopCost = stopCost,
                            confirmingStop = confirmingStop,
                            onConfirmStop = { confirmStop = it },
                            commandMode = commandMode,
                            commandRunning = commandRunning,
                            canRun = draft.trim().length > 1,
                            onRun = ::dispatchCommand,
                            onBackground = ::backgroundTurn,
                            commandLock = commandLock,
                        )
                    }
                }
                }
            }
        }
    }
    if (attachSheetOpen && session != null) {
        AttachSheet(
            onDismiss = { attachSheetOpen = false },
            onPickImages = { imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onTakePhoto = takePhoto,
            onPasteImage = ::pasteImage,
            onPickFiles = { filePicker.launch(arrayOf("*/*")) },
            github = attachGitHub?.let { c ->
                AttachSheetGitHub(session.cwd, c) { repository, selection -> stageSources(listOf(GitHubWorkAttachmentSource(repository, selection))) }
            },
        )
    }
    val sheetEntry = sheetAt
    if (sheetEntry != null && session != null && composerControls != null) {
        val locked = controlActions.lock != null
        val panel: (@Composable () -> Unit)? = when {
            composerControls.codexV2 -> { { CodexControlsPanel(controlActions.codex, locked, { sendControl(it) }) } }
            session.provider == "opencode" && session.engineGeneration == OPENCODE_V2 -> {
                {
                    OpencodeControlsPanel(
                        state = controlActions.opencode,
                        selectedModel = session.model ?: "",
                        selectedVariant = session.reasoningEffort ?: "",
                        selectedMode = if (session.approvalPolicy == "never") "default" else session.permissionMode ?: "default",
                        locked = locked,
                        onControl = { sendControl(it) },
                        markedDanger = { mode -> ComposerControlsModel.opencodeAgentMarkedDanger(mode, controls, controlActions.opencode?.snapshot) },
                    )
                }
            }
            else -> null
        }
        SessionSettingsSheet(
            entry = sheetEntry,
            controls = composerControls,
            lock = controlActions.lock,
            handlers = handlers,
            providerPanel = panel,
            onDismiss = { sheetAt = null },
        )
    }
}

internal const val AUTO_CONTINUE_ON_FLASH = "Auto-continue is on — a limit hit schedules its own continuation."
internal const val AUTO_CONTINUE_OFF_FLASH = "Auto-continue is off."

private data class DeckPadding(val start: Dp, val top: Dp, val end: Dp, val bottom: Dp)

/**
 * `.chat-composer` padding: `space-sm` all round on a phone, `space-sm space-md` from 48rem;
 * Studio uses 0.625rem on a phone and `1rem max(2rem, (100% - 53rem) / 2) 1.25rem` wider, which
 * centres a well of at most 53rem.
 */
private fun composerDeckPadding(m: ComposerMetrics, width: Dp): DeckPadding {
    if (m.phone) return DeckPadding(10.dp, 10.dp, 10.dp, 10.dp)
    val side = maxOf(32.dp, (width - 848.dp) / 2)
    return DeckPadding(side, 16.dp, side, 20.dp)
    return if (m.phone) DeckPadding(8.dp, 8.dp, 8.dp, 8.dp) else DeckPadding(16.dp, 8.dp, 16.dp, 8.dp)
}

/**
 * ta-8h5k (A1): the web's `.chat-composer { flex: 0 0 auto }` over a `min-height: 0`, `overflow: hidden` frame: the
 * composer keeps its intrinsic height and the transcript above it gives way first (down to 0); whatever is still
 * too tall is cut from the composer's bottom edge (its bottom padding), never squeezed out of the key rows.
 * Compose would otherwise hand a non-weighted child only the height left over and crush its last row.
 */
internal fun Modifier.keepsIntrinsicHeight(): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
        val height = placeable.height.coerceAtMost(constraints.maxHeight)
        layout(placeable.width, height) { placeable.place(0, 0) }
    }
    .clipToBounds()

/** The strip the totals readout sits in (clipped to the leftover between the keys). */
internal const val COMPOSER_TOTALS_TAG = "composer-totals"

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
    showAttach: Boolean = true,
    browser: ComposerBrowser? = null,
    options: (@Composable () -> Unit)? = null,
    settingsKey: (@Composable (Modifier) -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val t = LocalTetherTokens.current
    val gap = 8.dp
    val padding = Modifier.padding(start = COMPOSER_TOOLBAR_SIDE, top = 4.dp, end = COMPOSER_TOOLBAR_SIDE, bottom = 10.4.dp)
    Column(Modifier.fillMaxWidth().then(padding)) {
    options?.invoke()
    // ta-8h5k (M1, globals.css 10547-10555 + studio.css:394): from 48rem the toolbar is a column with an 8 px gap, so
    // below 64rem the sheet key is its own row (content width, 36 tall) above the footer, as `options` is from 64rem.
    if (settingsKey != null && !metrics.phone) {
        Row(Modifier.fillMaxWidth().padding(bottom = gap), verticalAlignment = Alignment.CenterVertically) { settingsKey(Modifier) }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        val attachSize = TetherDimens.touchTargetDp
        if (showAttach) {
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
            // T8.6 (chat-view.tsx 90fbb9f :4510-4524): the browser key, next to "add context".
            if (browser != null) ComposerBrowserKey(browser, attachSize)
        }
        // Phone (globals.css:11936): the sheet key takes the free width; wider it sits at content width.
        if (settingsKey != null && metrics.phone) settingsKey(Modifier.weight(1f))
        // `@container (max-width: 28rem)` hides the totals: they show above 448 of the toolbar's content width.
        if (totals != null && !metrics.phone && width > 448.dp) {
            // `.chat-activity-totals { overflow: hidden; white-space: nowrap }` between the keys, its automatic minimum 0, with
            // the keys at their natural width (`.chat-composer-actions { flex: 0 0 auto }`): the totals take what is left, start
            // aligned, and clip hard when it is less (no ellipsis, no hidden key). At a scale where they all fit, this is the
            // totals then the free width then the actions.
            Box(Modifier.weight(1f).clipToBounds().testTag(COMPOSER_TOTALS_TAG), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true)) { totals() }
            }
        } else if (settingsKey == null || !metrics.phone) {
            Spacer(Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
    }
}

/**
 * `.chat-composer-end` actions (chat-view.tsx:4459-4511): Send when idle; Queue + Interrupt while a
 * turn runs. Below 48rem the keys are 44dp icon-only squares (labels stay the accessible names)
 * and a disabled Queue key is hidden (globals.css:11944); wider they carry their legends.
 */
@Composable
private fun RowScope.ComposerActions(
    metrics: ComposerMetrics,
    busy: Boolean,
    canQueue: Boolean,
    canSend: Boolean,
    onSubmit: () -> Unit,
    sessionId: String?,
    /** T6.7: the turn the Interrupt key is drawn for (the active turn); null = none runs. */
    interruptTurnId: String?,
    onInterrupt: (turnId: String) -> Unit,
    /** T13.2 r2: why Interrupt cannot send (a copy that is not live); null = it can. */
    interruptLock: String?,
    /** ta-ceo (#229): what Stop would destroy ([QueueWait.stopCostCopy]); "" = nothing but the turn. */
    stopCost: String = "",
    /** ta-ceo: the first Interrupt tap asked for the second, informed one (Keep running · Stop anyway). */
    confirmingStop: Boolean = false,
    onConfirmStop: (Boolean) -> Unit = {},
    /** T7.3: the draft is a `!` command (idle: Send to agent + Background). */
    commandMode: Boolean = false,
    /** T7.3: the running turn is a foreground command (Background + Stop instead of Queue + Interrupt). */
    commandRunning: Boolean = false,
    /** T7.3: the draft holds a command after the "!". */
    canRun: Boolean = false,
    onRun: (background: Boolean) -> Unit = {},
    onBackground: (turnId: String) -> Unit = {},
    /** T7.3: why a command key cannot send (a copy that is not live); null = it can. */
    commandLock: String? = null,
) {
    val height = TetherDimens.touchTargetDp
    val labelled = !metrics.phone
    val fontSize = 12.48.sp
    val keyModifier = if (labelled) Modifier.height(height).widthIn(min = 44.dp) else Modifier.size(44.dp)
    val padding = when {
        !labelled -> 0.dp
        else -> 16.dp
    }
    // T7.3: one command key (`chat-send chat-send--command[-bg]`): the web's `:root .chat-send`
    // material rule (0,2,0) outranks the variant's own colours (0,1,0), so it is drawn as a Send key;
    // the Terminal / SendToBack glyph and the words tell them apart. ta-coik.13: it acts on the
    // first tap, as on the web (chat-view.tsx 90fbb9f :4578-4596, no arm delay); a press on a key
    // whose meaning changed under the finger is dropped ([StaleTapGuard]).
    @Composable
    fun CommandKey(identity: Any, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, tag: String, onTap: () -> Unit) {
        val live = enabled && commandLock == null
        StaleTapGuard(identity) { guard ->
            TetherKey(
                onClick = { if (live) onTap() },
                classes = KeyClasses.ChatSend,
                label = if (labelled) label else null,
                icon = icon,
                iconSize = 18.dp,
                fontSize = fontSize,
                enabled = live,
                minHeight = height,
                modifier = keyModifier
                    .then(guard)
                    .testTag(tag),
                contentPadding = padding,
                contentDescription = if (commandLock == null) description else "$description, unavailable: $commandLock",
            )
        }
    }
    if (busy && commandRunning && interruptTurnId != null) {
        // chat-view.tsx:4462-4476: a foreground command runs — Background (the touch Ctrl+B) + Stop.
        val drawnFor = interruptTurnId
        CommandKey(Triple("background", sessionId, drawnFor), "Background", TetherIcons.SendToBack, "Send this command to the background", true, BACKGROUND_KEY_TAG) { onBackground(drawnFor) }
        // ta-coik.13: the first tap stops it, as on the web (chat-view.tsx 90fbb9f :4566-4573).
        StaleTapGuard(Triple("interrupt", sessionId, drawnFor)) { guard ->
            TetherKey(
                onClick = { if (interruptLock == null) onInterrupt(drawnFor) },
                classes = KeyClasses.ChatInterrupt,
                label = if (labelled) "Stop" else null,
                icon = TetherIcons.CircleStop,
                iconSize = 18.dp,
                fontSize = fontSize,
                enabled = interruptLock == null,
                minHeight = height,
                modifier = keyModifier
                    .then(guard)
                    .testTag(INTERRUPT_KEY_TAG),
                contentPadding = padding,
                contentDescription = if (interruptLock == null) "Stop the command" else "Stop the command, unavailable: $interruptLock",
            )
        }
    } else if (busy) {
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
                contentDescription = "Queue message",
            )
        }
        // T13.2 r2: a copy that is not live cannot interrupt (its "busy" is not a turn running now).
        // T6.7: bound to the turn it is drawn for. ta-coik.13: it acts on the first tap, as on the web
        // (chat-view.tsx 90fbb9f :4559-4573, no arm delay); a press that began before the turn changed
        // is dropped ([StaleTapGuard]), and never through an overlay.
        val drawnFor = interruptTurnId
        if (confirmingStop && drawnFor != null) {
            // ta-ceo (chat-view.tsx:4543-4558): Stop is turn-wide — with live work the second tap
            // names the price. The pair is always labelled (a phone's icon-only key would hide the
            // price). ta-coik.13: "Stop anyway" acts on its first tap, as on the web (:4552-4558).
            TetherKey(
                onClick = { onConfirmStop(false) },
                classes = KeyClasses.ChatSend,
                label = "Keep running",
                fontSize = fontSize,
                minHeight = height,
                modifier = Modifier.heightIn(min = height).widthIn(min = 44.dp).testTag(KEEP_RUNNING_KEY_TAG),
                contentPadding = 16.dp,
                contentDescription = "Keep the turn running",
            )
            StaleTapGuard(Triple("interrupt-confirm", sessionId, drawnFor)) { guard ->
                TetherKey(
                    onClick = {
                        if (interruptLock == null) {
                            onConfirmStop(false)
                            onInterrupt(drawnFor)
                        }
                    },
                    classes = KeyClasses.ChatInterrupt,
                    label = "$stopCost — Stop anyway",
                    icon = TetherIcons.CircleStop,
                    iconSize = 18.dp,
                    fontSize = fontSize,
                    enabled = interruptLock == null,
                    minHeight = height,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(min = height)
                        .then(guard)
                        .testTag(STOP_ANYWAY_KEY_TAG),
                    contentPadding = 16.dp,
                    contentDescription = "$stopCost — stop anyway",
                    // The price is never cut: the key grows a line instead.
                    maxLines = Int.MAX_VALUE,
                )
            }
            return
        }
        val live = interruptLock == null && drawnFor != null
        StaleTapGuard(Triple("interrupt", sessionId, drawnFor)) { guard ->
            TetherKey(
                onClick = {
                    if (live && drawnFor != null) {
                        // issue #229: with live work the first tap asks; without, it interrupts at once.
                        if (stopCost.isNotEmpty()) onConfirmStop(true) else onInterrupt(drawnFor)
                    }
                },
                classes = KeyClasses.ChatInterrupt,
                label = if (labelled) "Interrupt" else null,
                icon = TetherIcons.CircleStop,
                iconSize = 18.dp,
                fontSize = fontSize,
                enabled = interruptLock == null,
                minHeight = height,
                modifier = keyModifier
                    .then(guard)
                    // No turn to bind it to: drawn as the key, but it does nothing.
                    .semantics { if (!live) disabled() }
                    .testTag(INTERRUPT_KEY_TAG),
                contentPadding = padding,
                contentDescription = if (interruptLock == null) "Interrupt the current turn" else "Interrupt the current turn, unavailable: $interruptLock",
            )
        }
    } else if (commandMode) {
        // chat-view.tsx:4479-4499: run in the foreground (Enter) or detached.
        CommandKey(Pair("run", sessionId), "Send to agent", TetherIcons.Terminal, "Run command and send output to the agent", canRun, RUN_KEY_TAG) { onRun(false) }
        CommandKey(Pair("run-background", sessionId), "Background", TetherIcons.SendToBack, "Run command in the background", canRun, RUN_BACKGROUND_KEY_TAG) { onRun(true) }
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
            contentDescription = "Send message",
        )
    }
}

/**
 * T13.2 r2: the run row of a copy that is not live: a still faint dot and "Was running · 12 min ago"
 * (neutral ink, no spinner, no ticking readings). TalkBack reads the words.
 */
@Composable
private fun StaleRunRow(stale: com.tether.app.client.SessionSync) {
    val t = LocalTetherTokens.current
    val wall = com.tether.app.ui.components.rememberTickingNow()
    val words = com.tether.app.ui.components.FreshnessCopy.qualifiedStatus("active", stale.lastVerifiedAt, wall) ?: "Was running"
    Row(
        Modifier
            .heightIn(min = 20.dp)
            .testTag(STALE_RUN_TAG)
            .clearAndSetSemantics { contentDescription = words },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        com.tether.app.ui.components.StatusDot(t.faint, size = 6.4.dp)
        Text(
            words,
            color = t.faint,
            fontFamily = Manrope,
            fontWeight = TetherWeights.label,
            fontSize = 12.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
    /**
     * T13.2 r2 (SYNC_DESIGN §4.2): null while the copy is live. Otherwise the run row claims nothing
     * about now: a still faint dot and "Was running · 12 min ago" instead of the spinner, the verb and
     * the ticking elapsed/token readings, and the session total stops counting.
     */
    stale: com.tether.app.client.SessionSync? = null,
) {
    val t = LocalTetherTokens.current
    val activeTurn = projection.activeTurnId?.let { projection.turnsById[it] }
    val run = activeTurn?.run

    var now by remember { mutableLongStateOf(serverNow()) }
    LaunchedEffect(activeTurn?.turnId, run?.index, stale != null) {
        // A saved copy's clock is frozen where it stood: its run is not known to be running now.
        while (run != null && stale == null) {
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
        if (run != null && stale != null) {
            StaleRunRow(stale)
        } else if (run != null) {
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
                modifier = Modifier.originalWords("Session total"),
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
    Row(
        Modifier.padding(horizontal = t.css.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.2.dp),
    ) {
        Text(
            "Session",
            color = t.faint,
            fontFamily = Manrope,
            fontWeight = androidx.compose.ui.text.font.FontWeight(500),
            fontSize = 10.4.sp,
            letterSpacing = 0.em,
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
