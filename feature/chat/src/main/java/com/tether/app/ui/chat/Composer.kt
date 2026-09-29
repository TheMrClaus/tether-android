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
import androidx.compose.ui.platform.testTag
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
import com.tether.app.client.ComposerControlsModel
import com.tether.app.client.ControlResult
import com.tether.app.client.LEGACY_GROUP_VALUE
import com.tether.app.client.ModeVocabulary
import com.tether.app.client.OPENCODE_V2
import com.tether.app.client.SessionControl
import com.tether.app.client.LabelText
import com.tether.app.client.typedModelAllowed
import com.tether.app.client.confirmedCopy
import com.tether.app.client.looksLikeModelId
import com.tether.app.protocol.reduce.composerCommandList
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
/** T13.2 r2: the composer's Interrupt key. */
internal const val INTERRUPT_KEY_TAG = "composer-interrupt"

/** T13.2 r2: the run row of a copy that is not live ("Was running", still, not ticking). */
internal const val STALE_RUN_TAG = "composer-run-stale"

private const val MAX_ATTACHMENTS = 10
private const val MAX_ATTACHMENT_BYTES = 9L * 1024 * 1024
private const val MAX_TOTAL_ATTACHMENT_BYTES = 18L * 1024 * 1024

/**
 * T13.2 r2 (SYNC_DESIGN §4.2): what the composer's turn controls and readings stand on. There is no
 * default: every host says whether its copy is live.
 */
@androidx.compose.runtime.Immutable
class ComposerLiveness(
    /**
     * Why Interrupt (the key and a queued row's "Interrupt now") cannot send ([stopLockCopy]'s words);
     * null = it can. A saved or catching-up copy's "busy" is not a turn that is running now.
     */
    val interruptLock: String?,
    /** Null while the copy is Live; else its freshness: the run row reads "Was running" and stops ticking. */
    val stale: com.tether.app.client.SessionSync?,
) {
    companion object {
        /** A live copy that may be driven (previews, and tests of the live composer). */
        val Live = ComposerLiveness(interruptLock = null, stale = null)
    }
}

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
    onRequestControls: () -> Unit,
    /** T13.2 r2: whether the copy is live (Interrupt's lock, the run row's freshness). Required. */
    liveness: ComposerLiveness,
    modifier: Modifier = Modifier,
    onAttachError: (String) -> Unit = {},
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
    /** T6.6: the session a handed-off source continued in (null: gone, or not handed off). */
    handoffTarget: AgentSession? = null,
    /** T6.6: open another session (the handoff lock's link). Navigation only, never a wire mutation. */
    onOpenSession: (String) -> Unit = {},
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
    // T7.2: the row's state, derived from the session, its controls reply and the Codex catalog.
    val composerControls = remember(session, controls, controlActions.codex, controlActions.opencode, pinnedModels) {
        session?.let { ComposerControlsModel.derive(it, controls, controlActions.codex, pinnedModels, controlActions.opencode) }
    }
    // The web swaps the pill row for the sheet key below 64rem of VIEWPORT (globals.css:7347-7352).
    val wideRow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 1024
    var sheetAt by remember(session?.id) { mutableStateOf<SheetView?>(null) }
    var escalation by remember(session?.id) { mutableStateOf<Escalation?>(null) }
    val commands = remember(projection?.cliInventory, controls) {
        composerCommandList(projection?.cliInventory?.commands, controls?.commands ?: emptyList())
    }

    // The command-name fragment being typed ("/mod" -> "mod"), or null when the
    // draft isn't a bare slash command — drives whether the menu shows.
    fun slashQueryOf(text: String): String? =
        if (claude && text.startsWith("/") && !text.drop(1).contains(" ")) text.drop(1) else null
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

    fun openModelPicker() {
        if (composerControls?.model == null) return
        onRequestControls() // refresh to the live list if the session has since warmed
        sheetAt = SheetView.Model
        menuDismissed = true
    }

    /** The dialog's words for a control that needs the operator's confirmation. */
    fun escalationFor(control: SessionControl): Escalation? {
        val confirmed = control.confirmedCopy() ?: return null
        val c = composerControls
        return when (control) {
            is SessionControl.Mode -> {
                val option = c?.mode?.options?.firstOrNull { it.value == control.value }
                Escalation(option?.label ?: if (control.value == ModeVocabulary.AUTO) "Auto" else LabelText.visibleValue(control.value), escalationBody(option?.description ?: c?.auto?.hint), confirmed)
            }
            is SessionControl.OpencodeMode -> {
                val agent = controlActions.opencode?.snapshot?.modes?.items?.firstOrNull { it.value == control.mode }
                Escalation(ComposerControlsModel.opencodeAgentLabel(control.mode, agent?.label ?: ""), escalationBody(agent?.hint?.ifEmpty { null }), confirmed)
            }
            is SessionControl.CodexAutoApprove -> Escalation("Auto approve", escalationBody(c?.auto?.hint), confirmed)
            // T6.6: only while the toggle is still drawn "off" (the grant it offers).
            is SessionControl.AutoContinueOnLimit ->
                c?.autoContinue?.takeIf { !it.on && control.enabled }?.let { Escalation("Auto-continue", AUTO_CONTINUE_CONFIRM_BODY, confirmed, danger = false) }
            else -> null
        }
    }

    /**
     * T7.2: one operator choice to the client's guard; a refusal is said in words. Round 2 (L4): a
     * change the client wants confirmed opens the confirmation for exactly that control.
     */
    fun sendControl(control: SessionControl, notOfferedCopy: String? = null): Boolean {
        val result = controlActions.onControl(control)
        if (result == ControlResult.NeedsConfirmation) {
            escalationFor(control)?.let { escalation = it; return false }
        }
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
        // Round 3 (F1): the confirmation reads the cleaned name, never raw server text.
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
        if (option?.danger == true || value == ModeVocabulary.AUTO) {
            if (c.mode?.value == value) return
            escalation = escalationFor(SessionControl.Mode(value))
            return
        }
        sendControl(SessionControl.Mode(value))
    }

    fun toggleAuto() {
        val c = composerControls ?: return
        val auto = c.auto ?: return
        if (c.codexV2) {
            val revision = controlActions.codex?.snapshot?.revision ?: return
            if (auto.on) sendControl(SessionControl.CodexAutoApprove(false, revision))
            else escalation = escalationFor(SessionControl.CodexAutoApprove(true, revision))
            return
        }
        // chat-view.tsx:2486-2495: the same set-mode the Mode row sends.
        if (auto.on) sendControl(SessionControl.Mode("default"))
        else escalation = escalationFor(SessionControl.Mode(ModeVocabulary.AUTO))
    }

    val providerV2 = session != null && (composerControls?.codexV2 == true || (session.provider == "opencode" && session.engineGeneration == OPENCODE_V2))
    val handlers = ControlHandlers(
        chooseModel = { chooseModel(it) },
        chooseEffort = ::chooseEffort,
        chooseMode = ::chooseMode,
        toggleAuto = ::toggleAuto,
        setFast = { enabled -> sendControl(SessionControl.FastMode(enabled)) },
        setAutoApprove = { on -> if (on != composerControls?.auto?.on) toggleAuto() },
        // T6.6 (chat-view.tsx:2480): exactly the flip of the value the toggle was drawn with.
        // Turning it on is a grant: it only asks (the confirmation sends); turning it off sends.
        setAutoContinue = { enabled ->
            val ac = composerControls?.autoContinue
            if (ac != null && enabled != ac.on) {
                if (enabled) {
                    escalation = escalationFor(SessionControl.AutoContinueOnLimit(true))
                } else if (sendControl(SessionControl.AutoContinueOnLimit(false))) {
                    flash(AUTO_CONTINUE_OFF_FLASH)
                }
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

    // Native commands (currently /model) run in-app; everything else is flagged
    // terminal-only rather than sent as prompt text (the /model-as-text bug).
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
            modelCommand(arg)
            return
        }
        if (info != null && !info.supported) {
            flash("/${LabelText.label(info.name)} isn’t available in Tether yet — run it from a terminal (claude --resume …).")
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
            flash("/${LabelText.label(command.name)} isn’t available in Tether yet — run it from a terminal.")
            setDraft("")
            return
        }
        setDraft("/${command.name} ")
    }

    /** Slash commands are in-app control requests: never queued, no attachments. */
    fun trySlashCommand(text: String, hasAttachments: Boolean): Boolean {
        if (!text.startsWith("/") || hasAttachments) return false
        if (!claude) {
            // Round 3 (F2): `/model <arg>` pins a model on every engine with a model select but
            // Codex, as the web does (chat-view.tsx:3015); the rest of the slash menu is T7.3's.
            val arg = MODEL_ARG.find(text)?.groupValues?.get(1)?.trim().orEmpty()
            val s = session ?: return false
            if (arg.isEmpty() || !typedModelAllowed(s.provider) || composerControls?.model == null) return false
            modelCommand(arg)
            return true
        }
        runSlashCommand(text)
        return true
    }

    fun submit() {
        if (session == null) return
        // The live field, not the composition-time [draft] (see [liveMenu]).
        val text = field.text.trim()
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
                if (menuOpen) {
                    SlashCommandMenu(matches = menuMatches, onAccept = { acceptCommand(it) })
                }

                val queued = projection?.queuedMessages.orEmpty()
                if (queued.isNotEmpty()) {
                    QueuedMessages(
                        queued = queued,
                        onSave = onQueueEdit,
                        onRemove = onQueueRemove,
                        onInterruptNow = onInterrupt,
                        interruptLock = liveness.interruptLock,
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

                val liveControls = composerControls?.takeIf { it.live && session != null }
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
                            val menu = liveMenu()
                            if (menu != null) menu.firstOrNull()?.let(::acceptCommand) else submit()
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
                        settingsKey = if (liveControls != null && !wideRow) {
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
                            onInterrupt = onInterrupt,
                            interruptLock = liveness.interruptLock,
                        )
                    }
                }
                }
            }
        }
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
                        needsConfirmation = { mode -> ComposerControlsModel.opencodeAgentNeedsConfirmation(mode, controls, controlActions.opencode?.snapshot) },
                        onDangerMode = { control -> escalation = escalationFor(control) },
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
    escalation?.let { pending ->
        EscalationDialog(
            label = pending.label,
            body = pending.body,
            danger = pending.danger,
            sessionName = session?.name,
            onConfirm = {
                // Round 3 (I-a): what is confirmed is what is on screen now; if the row moved under
                // the dialog (a new label or hint), show the new words instead of sending.
                val fresh = escalationFor(pending.control)
                if (fresh == null) {
                    escalation = null
                } else if (fresh.label != pending.label || fresh.body != pending.body) {
                    escalation = fresh
                } else {
                    escalation = null
                    if (sendControl(pending.control) && pending.control is SessionControl.AutoContinueOnLimit) flash(AUTO_CONTINUE_ON_FLASH)
                }
            },
            onCancel = { escalation = null },
        )
    }
}

/** A most-permissive change waiting for the operator's confirmation (never saved: a restore drops it). */
internal class Escalation(val label: String, val body: String, val control: SessionControl, val danger: Boolean = true)

/** T6.6: the auto-continue grant's confirmation (the web's toggle title, said before it is on). */
internal const val AUTO_CONTINUE_CONFIRM_BODY =
    "A rate/usage limit hit in this session will schedule its own continuation for right after the reset, without asking. It stays on for this session until you switch it back."
internal const val AUTO_CONTINUE_ON_FLASH = "Auto-continue is on — a limit hit schedules its own continuation."
internal const val AUTO_CONTINUE_OFF_FLASH = "Auto-continue is off."

/** `/model <arg>`: the argument after the command name. */
private val MODEL_ARG = Regex("^/model\\s+(.+)$", RegexOption.DOT_MATCHES_ALL)

internal fun escalationBody(hint: String?): String =
    (hint?.trimEnd('.')?.let { "$it." } ?: "The agent will run without asking, including destructive commands.") +
        " It stays on for this session until you switch it back."

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
    options: (@Composable () -> Unit)? = null,
    settingsKey: (@Composable (Modifier) -> Unit)? = null,
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
    Column(Modifier.fillMaxWidth().then(padding)) {
    options?.invoke()
    Row(
        modifier = Modifier.fillMaxWidth(),
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
        // Phone (globals.css:11936): the sheet key takes the free width; wider it sits at content width.
        if (settingsKey != null) {
            settingsKey(if (metrics.phone) Modifier.weight(1f) else Modifier.widthIn(max = 280.dp))
        }
        if (totals != null && !metrics.phone && width >= 448.dp) totals()
        if (settingsKey == null || !metrics.phone) Spacer(Modifier.weight(1f))
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
private fun ComposerActions(
    metrics: ComposerMetrics,
    busy: Boolean,
    canQueue: Boolean,
    canSend: Boolean,
    onSubmit: () -> Unit,
    onInterrupt: () -> Unit,
    /** T13.2 r2: why Interrupt cannot send (a copy that is not live); null = it can. */
    interruptLock: String?,
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
        // T13.2 r2: a copy that is not live cannot interrupt (its "busy" is not a turn running now).
        TetherKey(
            onClick = { if (interruptLock == null) onInterrupt() },
            classes = KeyClasses.ChatInterrupt,
            label = if (labelled) "Interrupt" else null,
            icon = TetherIcons.CircleStop,
            iconSize = 18.dp,
            fontSize = fontSize,
            enabled = interruptLock == null,
            minHeight = height,
            modifier = keyModifier.testTag(INTERRUPT_KEY_TAG),
            contentPadding = padding,
            contentDescription = if (interruptLock == null) "Interrupt the current turn" else "Interrupt the current turn, unavailable: $interruptLock",
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
