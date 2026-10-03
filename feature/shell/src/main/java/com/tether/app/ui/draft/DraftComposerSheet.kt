package com.tether.app.ui.draft

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag as testTagProperty
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.client.ConnectionState
import com.tether.app.client.DraftComposerState
import com.tether.app.client.DraftSessionOptions
import com.tether.app.client.DraftSessionOptionsModel
import com.tether.app.client.DraftSubmitResult
import com.tether.app.client.NewSessionGuard
import com.tether.app.client.NewSessionRow
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.FolderPickerDialog
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.chat.AttachSheet
import com.tether.app.ui.chat.ControlPill
import com.tether.app.ui.chat.DraftAttachmentStager
import com.tether.app.ui.chat.StagedAttachmentChip
import com.tether.app.ui.chat.UserBubble
import com.tether.app.ui.chat.rememberDraftAttachmentPickers
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.LocalKeyboardInset
import com.tether.app.ui.components.StudioDialog
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.prefs.TetherPreferences
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.sidebar.SidebarController
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.util.compactPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * ta-abm (T8.1 slice 2): the new-session draft composer sheet — components/draft-composer.tsx inside
 * dashboard.tsx's `.draft-dialog` (887c222) — driven by ta-8cv's [com.tether.app.client.DraftComposerModel].
 *
 * The draft lives in the view model, as the web keeps useDraftComposer at dashboard level: the sheet
 * only draws it, so closing and reopening it, a rotation, a recreated activity and a session switch
 * keep the text, the folder, the provider pick and the attachments; a server switch drops them (the
 * engine's per-origin rule). r2: on a phone it docks to the bottom as the web's sheet does (the
 * keyboard lifts it, so Send stays above the keyboard); on an expanded window, the web's centred dialog.
 *
 * Contents, in the web's order: the project row (the working-folder chip with its quick picks and
 * "Browse for another folder…"), the provider and profile rows (ta-895's rendering, a tap picks the
 * row), the error / notice lines, the staged attachments, and the message well with the paperclip
 * (the T7.4 sheet) and Send. Send creates the session AND sends the first message, as on the web.
 * While the create is in flight the sheet gives way to [DraftLaunching] on the shell's stage.
 *
 * ta-2uq (slice 3): the live row's ModelSelector chip ([ModelSelectorChip]) replaces the provider
 * rows: it opens the model browser ([ModelBrowserFrame]), whose "all" view lists those rows, and a
 * pick sets the draft's row (provider / profile) and model together, as on the web.
 *
 * ta-xki (slice 4): the live row's Effort and Mode beside the chip (from 64rem), and below 64rem the
 * SessionSettingsSheet trigger row that carries Model, Effort and Mode instead ([DraftLiveRow],
 * [DraftSettingsTriggerRow], [DraftSettingsFrame]; the model chip opens the sheet on the browser).
 *
 * ta-23f (slice 5): the worktree select beside the folder chip and its detail row under the project
 * row ([WorktreeSelect], [WorktreeDetails]); while isolation is on the sheet asks the server what the
 * folder's repo offers (the engine's matched `worktree-inspect`) for the web's setup note. A Send that
 * may run the project's setup sends at once, as on the web (ta-coik.11).
 *
 * Left for a later slice (nothing is drawn for it, so nothing looks like a control that is not
 * there): the GitHub issues / PRs dialog (T8.4).
 */

/** Test tags of the sheet (behaviour tests and goldens). */
object DraftComposerTags {
    const val Sheet = "draft-sheet"
    const val Close = "draft-close"
    const val WorkspaceChip = "draft-workspace-chip"
    const val WorkspacePopover = "draft-workspace-popover"
    const val Browse = "draft-workspace-browse"
    const val LiveRow = ModelBrowserTags.LiveRow
    const val Error = "draft-error"
    const val Notice = "draft-notice"
    const val Readiness = "draft-readiness"
    const val Attachments = "draft-attachments"
    const val Input = "draft-input"
    const val Attach = "draft-attach"
    const val Send = "draft-send"
    const val Launching = "draft-launching"
    fun quickPick(path: String) = "draft-quick-pick:$path"
}

/** dashboard.tsx `.draft-dialog-head`. */
internal const val DRAFT_TITLE = "New session"
internal const val DRAFT_SUBTITLE = "Pick a provider and model, choose the folder, then send the first message."

/** draft-composer.tsx textarea: placeholder and accessible name. */
internal const val DRAFT_PLACEHOLDER = "Message the new session…"
internal const val DRAFT_INPUT_LABEL = "Message the new session"

/** How long an attachment flash stays (draft-composer.tsx flash: 6000 ms). */
internal const val DRAFT_NOTICE_MS = 6_000L

/** lib/draft-form.ts WorkspaceQuickPick. */
@Immutable
data class WorkspaceQuickPick(val kind: String, val label: String, val path: String)

/** lib/draft-form.ts buildWorkspaceQuickPicks (pinned → default → current → root, deduped, blanks skipped). */
fun workspaceQuickPicks(pinned: List<String>, defaultWorkspace: String?, currentWorkspace: String?, workspaceRoot: String?): List<WorkspaceQuickPick> {
    val input = com.tether.app.protocol.tree.JsObj.of(
        "pinnedProjects" to com.tether.app.protocol.tree.JsArr.of(pinned.map(::JsStr)),
        "defaultWorkspace" to JsStr(defaultWorkspace.orEmpty()),
        "currentWorkspace" to JsStr(currentWorkspace.orEmpty()),
        "workspaceRoot" to JsStr(workspaceRoot.orEmpty()),
    )
    return com.tether.app.protocol.helpers.DraftForm.buildWorkspaceQuickPicks(input).mapNotNull { v ->
        val o = v as? com.tether.app.protocol.tree.JsObj ?: return@mapNotNull null
        fun str(k: String) = (o[k] as? JsStr)?.value.orEmpty()
        WorkspaceQuickPick(str("kind"), str("label"), str("path"))
    }
}

/** dashboard.tsx draftProviderLabel: the row's label, else its engine's, else the engine id; "agent" with none. */
fun draftProviderLabel(row: NewSessionRow?, providers: List<ProviderInfo>): String {
    if (row == null) return "agent"
    val entryLabel = row.label?.let { com.tether.app.client.LabelText.label(it) }?.takeIf { it.isNotEmpty() }
    return entryLabel ?: providers.firstOrNull { it.id == row.choice.provider }?.label?.let { com.tether.app.client.LabelText.label(it) }?.takeIf { it.isNotEmpty() } ?: row.choice.provider
}

/** What the sheet draws (everything it reads, gathered by [DraftComposerHost]; the goldens seed it). */
@Immutable
class DraftSheetInputs(
    val draft: DraftComposerState,
    /** ta-2uq: what the Model chip and its browser draw. */
    val browser: ModelBrowserInputs,
    val quickPicks: List<WorkspaceQuickPick>,
    val workspaceRoot: String,
    /** The engine's first readiness reason ("" = ready), in the web's order. */
    val readiness: String,
    /** An attachment flash (draft-composer.tsx `notice`), shown while there is no error. */
    val notice: String? = null,
    /** ta-2uq: the model browser is up (the chip is drawn active). */
    val browserOpen: Boolean = false,
    /** ta-xki: the draft's Effort and Mode ([com.tether.app.client.DraftSessionOptionsModel]). */
    val options: DraftSessionOptions = DraftSessionOptions.None,
)

/** What the sheet's controls do. */
class DraftSheetActions(
    val onClose: () -> Unit = {},
    val onText: (String) -> Unit = {},
    /** ta-2uq: the Model chip (opens or closes the model browser). */
    val onModelChip: () -> Unit = {},
    val onPickFolder: (String) -> Unit = {},
    val onBrowse: () -> Unit = {},
    val onAttach: () -> Unit = {},
    val onRemoveAttachment: (Long) -> Unit = {},
    val onSubmit: () -> Unit = {},
    /** ta-xki: an Effort pick (a variant value). */
    val onSelectEffort: (String) -> Unit = {},
    /**
     * ta-xki: a Mode pick (an elevated one too: an ordinary choice, no confirmation), with the provider
     * the control was drawn for (r2, security F1: a pick drawn for another provider changes nothing).
     */
    val onSelectMode: (mode: String, drawnFor: String) -> Unit = { _, _ -> },
    /** ta-xki: opencode's Auto chip, with the provider it was drawn for. */
    val onToggleAuto: (drawnFor: String) -> Unit = {},
    /** ta-xki: the phone's sliders chip (the settings sheet's hub). */
    val onOpenSettings: () -> Unit = {},
    /** ta-23f: the worktree select ("local" or a mode). */
    val onSelectIsolation: (String) -> Unit = {},
    /** ta-23f: one worktree detail field typed or filled from a suggestion. */
    val onWorktreeField: (com.tether.app.client.WorktreeField, String) -> Unit = { _, _ -> },
)

private fun DraftComposerState.cwd(): String = (form["cwd"] as? JsStr)?.value.orEmpty()

private fun DraftComposerState.key(): String = (form["key"] as? JsStr)?.value.orEmpty()

/** draft-composer.tsx sendDisabled: nothing to send, a create in flight, or a readiness reason. */
internal fun sendDisabled(draft: DraftComposerState, readiness: String): Boolean =
    (draft.text.isBlank() && draft.staged.isEmpty()) || draft.creating || readiness.isNotEmpty()

/**
 * The sheet wired to the view model: up while the operator is composing ([TetherViewModel.draftOpen])
 * and no create holds the draft. Opening it asks for a fresh catalog (and again when the link comes
 * back while it is up), as ta-895's picker did.
 */
@Composable
fun DraftComposerHost(vm: TetherViewModel, prefs: UiPrefs) {
    val open by vm.draftOpen.collectAsStateWithLifecycle()
    val draft by vm.draftComposer.state.collectAsStateWithLifecycle()
    if (!open || draft.creating) return
    DraftComposerDialog(vm, prefs)
}

@Composable
private fun DraftComposerDialog(vm: TetherViewModel, prefs: UiPrefs) {
    val client = vm.client
    val composer = vm.draftComposer
    val draft by composer.state.collectAsStateWithLifecycle()
    val connection by client.connection.collectAsStateWithLifecycle()
    val catalog by client.providerCatalog.collectAsStateWithLifecycle()
    val live by client.providerCatalogLive.collectAsStateWithLifecycle()
    val providers by client.providers.collectAsStateWithLifecycle()
    val origin by client.consentOrigin.collectAsStateWithLifecycle()
    val root by client.workspaceRoot.collectAsStateWithLifecycle()
    val directories by client.directories.collectAsStateWithLifecycle()
    val picked by vm.currentWorkspace.collectAsStateWithLifecycle()
    val preferences by prefs.preferences.collectAsStateWithLifecycle(initialValue = TetherPreferences.Default)
    val linkEpoch by client.linkEpoch.collectAsStateWithLifecycle()
    val connected = connection == ConnectionState.Connected
    LaunchedEffect(connected) { if (connected) client.requestProviderCatalog() }
    // ta-23f: draft-composer.tsx's effect on [useWorktree, cwd]: ask what the folder's repo offers while
    // isolation is on (here also on a new socket, whose answer replaces the dropped one). Once per
    // folder per socket; the engine takes only the answer to its own question.
    val isolated = draft.form["useWorktree"] == com.tether.app.protocol.tree.JsBool.TRUE
    LaunchedEffect(isolated, draft.cwd(), linkEpoch, connected) { if (isolated && connected) composer.inspectWorktree() }
    // ta-2uq: the browser belongs to one server: a switch closes it (the draft is dropped with it).
    val browser = remember(origin) { ModelBrowserState() }
    // ta-xki: so does the phone's settings sheet (which embeds its own browser).
    val settings = remember(origin) { DraftSettingsState() }
    val wideRow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= LIVE_ROW_MIN_WIDTH_DP
    val collator = remember { com.tether.app.ui.chat.IcuJsCollator.forLocale() }

    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeAt by remember { mutableStateOf(0L) }
    LaunchedEffect(noticeAt) {
        if (notice != null) {
            delay(DRAFT_NOTICE_MS)
            notice = null
        }
    }
    fun flash(message: String) {
        notice = message
        noticeAt += 1
    }
    val stager = remember(composer) { DraftAttachmentStager(composer) }
    val pickers = rememberDraftAttachmentPickers(
        onSources = { sources -> scope.launch { stager.stage(sources).lastOrNull()?.let(::flash) } },
        onFlash = ::flash,
    )
    var attachOpen by rememberSaveable { mutableStateOf(false) }
    var browsing by rememberSaveable { mutableStateOf(false) }

    val current = SidebarController.resolveCurrentWorkspace(picked, preferences, root)
    val browserInputs = draftBrowserInputs(draft, if (live) catalog else null, providers, collator, System.currentTimeMillis())
    val inputs = DraftSheetInputs(
        draft = draft,
        browser = browserInputs,
        quickPicks = workspaceQuickPicks(preferences.pinnedProjects, preferences.defaultWorkspace, current, root),
        workspaceRoot = root.orEmpty(),
        readiness = composer.readiness(),
        notice = notice,
        browserOpen = if (wideRow) browser.open else settings.open,
        options = DraftSessionOptionsModel.of(draft),
    )
    val actions = DraftSheetActions(
        onClose = vm::closeDraft,
        onText = composer::setText,
        onModelChip = {
            when {
                // Below 64rem the model chip opens the settings sheet straight on the browser (the web's).
                !wideRow -> settings.openAt(DraftSettingsView.Model, draft.entries, draft.key())
                browser.open -> browser.close()
                else -> browser.openOn(draft.entries, draft.key())
            }
        },
        onOpenSettings = { settings.openAt(DraftSettingsView.Root, draft.entries, draft.key()) },
        onSelectEffort = { composer.selectEffort(it) },
        onSelectMode = { mode, drawnFor -> composer.selectMode(mode, drawnFor) },
        onToggleAuto = { drawnFor -> composer.toggleAuto(drawnFor) },
        onSelectIsolation = { composer.selectIsolation(it) },
        onWorktreeField = { field, value -> composer.setWorktreeField(field, value) },
        onPickFolder = composer::setCwd,
        onBrowse = {
            client.browse(draft.cwd().ifEmpty { root.orEmpty() }.ifEmpty { null })
            browsing = true
        },
        onAttach = { attachOpen = true },
        onRemoveAttachment = composer::removeAttachment,
        onSubmit = {
            // use-draft-composer.ts submit, drawn for the server this sheet was drawn for.
            if (composer.submit(origin) == DraftSubmitResult.NotOffered) client.requestProviderCatalog()
        },
    )
    Dialog(onDismissRequest = vm::closeDraft, properties = DraftDialogProperties) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        DraftComposerFrame(inputs, actions, focusOnOpen = true, wideRow = wideRow)
    }
    // model-browser.tsx handleSelect / draft-composer.tsx handleModelSelect: a pick on another row is
    // one atomic provider + model pick; on the same row, a model pick.
    val pickModel: (String, String) -> Unit = { entryKey, modelId ->
        if (entryKey != draft.key()) composer.selectProviderAndModel(entryKey, modelId) else composer.selectModel(modelId)
    }
    if (browser.open && wideRow) {
        val browserActions = ModelBrowserActions(
            onSelect = { entryKey, modelId ->
                pickModel(entryKey, modelId)
                browser.close()
            },
            // dashboard.tsx onRetryProvider: refreshProviders([key]), on the socket this was drawn on.
            onRetry = { key -> client.refreshProviders(key, linkEpoch) },
            onAddModel = composer::addCustomModel,
            onRemoveModel = composer::removeCustomModel,
            onClose = browser::close,
        )
        Dialog(onDismissRequest = browser::close, properties = DraftDialogProperties) {
            val view = LocalView.current
            SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
            ModelBrowserFrame(browserInputs, browser, browserActions, currentLayoutClass())
        }
    }
    if (settings.open && !wideRow) {
        // session-settings-sheet.tsx (catalog kind): its model view is the same browser; its picks
        // step the sheet itself (close from the view it opened on, else back to the hub).
        val settingsActions = DraftSettingsActions(
            browser = ModelBrowserActions(
                onSelect = pickModel,
                onRetry = { key -> client.refreshProviders(key, linkEpoch) },
                onAddModel = composer::addCustomModel,
                onRemoveModel = composer::removeCustomModel,
            ),
            onSelectEffort = { composer.selectEffort(it) },
            onSelectMode = { mode, drawnFor -> composer.selectMode(mode, drawnFor) },
            onToggleAuto = { drawnFor -> composer.toggleAuto(drawnFor) },
            onClose = settings::close,
        )
        Dialog(onDismissRequest = settings::close, properties = DraftDialogProperties) {
            val view = LocalView.current
            SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
            DraftSettingsFrame(inputs, settings, settingsActions, currentLayoutClass())
        }
    }
    if (browsing) {
        FolderPickerDialog(
            directories = directories,
            current = draft.cwd().ifEmpty { root },
            onDismiss = { browsing = false },
            onBrowse = { client.browse(it) },
            onChoose = { cwd ->
                browsing = false
                composer.setCwd(cwd)
            },
            title = "Choose a working folder",
        )
    }
    if (attachOpen) {
        AttachSheet(
            onDismiss = { attachOpen = false },
            onPickImages = pickers.pickImages,
            onPasteImage = pickers.pasteImage,
            onPickFiles = pickers.pickFiles,
        )
    }
}

/**
 * ta-2uq: what the Model chip and browser draw for [draft]: its merged rows, ta-895's rows for the
 * same list (the live catalog's, else the base providers'), its pick and custom ids.
 */
fun draftBrowserInputs(
    draft: DraftComposerState,
    liveCatalog: List<com.tether.app.client.ProviderCatalogEntry>?,
    providers: List<ProviderInfo>,
    collator: com.tether.app.protocol.helpers.JsCollator,
    now: Long,
): ModelBrowserInputs {
    val pending = liveCatalog == null
    val merged = draft.entries
    val rows = NewSessionGuard.rows(if (pending) null else merged, providers)
    return ModelBrowserInputs(
        entries = merged,
        rows = rows,
        providers = providers,
        catalogPending = pending,
        selectedKey = draft.key(),
        selectedModel = (draft.form["model"] as? JsStr)?.value.orEmpty(),
        customModels = draft.customModels,
        now = now,
        collator = collator,
    )
}

/** The sheet's window: edge to edge (the frame pads the bars it must), no platform dim. */
internal val DraftDialogProperties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

/**
 * The sheet in place (the inline form the goldens shoot). [TetherLayoutClass.Phone]: docked to the
 * bottom over the scrim, its top corners rounded, at most `100dvh - 3rem` of the space above the keyboard. [TetherLayoutClass.Expanded]: `.draft-dialog`, centred on the skin's scrim at
 * `min(46rem, 100vw - 1.5rem)`, at most `100dvh - 3rem` tall, a tap on the scrim closes it.
 */
@Composable
fun DraftComposerFrame(
    inputs: DraftSheetInputs,
    actions: DraftSheetActions,
    modifier: Modifier = Modifier,
    layout: TetherLayoutClass = currentLayoutClass(),
    /** r2 (F3): put the caret in the message box when the sheet opens (dashboard.tsx 381-384); off in goldens. */
    focusOnOpen: Boolean = false,
    /** ta-xki: the live row (from 64rem) or, below, the settings sheet's trigger row. */
    wideRow: Boolean = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= LIVE_ROW_MIN_WIDTH_DP,
) {
    val t = LocalTetherTokens.current
    val narrow = layout == TetherLayoutClass.Phone
    val keyboard = LocalKeyboardInset.current.current()
    val navBottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(dialogScrim(t))
            // `.draft-dialog` closes on a press on its backdrop (onMouseDown target === currentTarget).
            .pointerInput(Unit) { detectTapGestures { actions.onClose() } }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            // r2 (F1): the keyboard lifts the whole sheet (Compose dialogs do not resize their window):
            // the phone sheet docks on the keyboard, the expanded card centres in what it leaves.
            .padding(bottom = keyboard),
        // globals.css (max-width: 40rem): the phone sheet docks to the bottom, "so the composer sits
        // where the thumb already is and the on-screen keyboard pushes nothing off-screen".
        contentAlignment = if (narrow) Alignment.BottomCenter else Alignment.Center,
    ) {
        val shape = if (narrow) RoundedCornerShape(topStart = t.radiusLg, topEnd = t.radiusLg) else RoundedCornerShape(t.radiusLg)
        // `max-height: calc(100dvh - 3rem)`, of the space the keyboard leaves.
        val maxSheet = (maxHeight - 48.dp).coerceAtLeast(0.dp)
        val case = if (narrow) {
            Modifier.fillMaxWidth().heightIn(max = maxSheet)
        } else {
            Modifier.width(minOf(736.dp, maxWidth - 24.dp).coerceAtLeast(0.dp)).heightIn(max = maxSheet)
        }
        // `.draft-dialog-head p` is hidden below 40rem (it wraps and pushes the composer down).
        val subtitle = maxWidth >= 640.dp
        Column(
            case
                .testTag(DraftComposerTags.Sheet)
                .semantics { paneTitle = DRAFT_TITLE }
                // Phone: `border-width: 1px 0 0` (a top edge only); desktop: the 1px case.
                .cssSurface(shape, t.graphite, if (narrow) null else CssBorder(1.dp, t.lineStrong), if (narrow) emptyList() else StudioDialog.shadows)
                .then(if (narrow) Modifier.drawBehind { drawRect(t.lineStrong, Offset.Zero, Size(size.width, 1.dp.toPx())) } else Modifier)
                .clip(shape)
                // The card swallows taps (only the scrim, Close and Back close it); a gesture sink,
                // not a clickable, so its text is never merged into one node.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            DraftHeader(narrow, subtitle, actions.onClose)
            val side = if (narrow) t.css.spaceMd else t.css.spaceXl
            // The options scroll; the message well stays put at the sheet's foot (r2, F1), so Send is
            // always on screen, above the keyboard, whatever the rows above it hold.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = side, end = side, top = if (narrow) t.css.spaceMd else t.css.spaceLg),
            ) {
                DraftComposerOptions(inputs, actions, narrow, wideRow)
            }
            // `.draft-dialog .chat-composer` (Studio) bottom: phone max(space-md, safe-area-bottom),
            // space-sm over the keyboard (the sheet already sits on it); desktop space-xl.
            val bottom = when {
                !narrow -> t.css.spaceXl
                keyboard > 0.dp -> t.css.spaceSm
                else -> maxOf(t.css.spaceMd, navBottom)
            }
            Column(Modifier.fillMaxWidth().padding(start = side, end = side, bottom = bottom)) {
                DraftComposerFoot(inputs, actions, narrow, keyboardOpen = keyboard > 0.dp, focusOnOpen = focusOnOpen)
            }
        }
    }
}

/** `.draft-dialog-head` (Studio): the title over its subtitle, and Close. */
@Composable
private fun DraftHeader(narrow: Boolean, subtitle: Boolean, onClose: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(if (narrow) t.css.spaceLg else t.css.spaceXl),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    DRAFT_TITLE,
                    color = t.white,
                    // `:root .draft-dialog-head h2`: 1.3rem / 700.
                    style = androidx.compose.ui.text.TextStyle(fontFamily = type.ui, fontSize = 20.8.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(700), letterSpacing = (-0.02).em),
                    modifier = Modifier.semantics { heading() },
                )
                if (subtitle) {
                    Text(
                        DRAFT_SUBTITLE,
                        color = t.muted,
                        style = type.body.copy(fontSize = 12.48.sp, lineHeight = 1.6.em),
                        modifier = Modifier.padding(top = 6.4.dp),
                    )
                }
            }
            TetherKey(
                onClick = onClose,
                classes = KeyClasses.IconButton,
                icon = TetherIcons.X,
                iconSize = 17.dp,
                contentDescription = "Close new session",
                modifier = Modifier.testTag(DraftComposerTags.Close),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}

/** draft-composer.tsx's composer, in its order. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftComposerOptions(inputs: DraftSheetInputs, actions: DraftSheetActions, narrow: Boolean, wideRow: Boolean) {
    val t = LocalTetherTokens.current
    val draft = inputs.draft
    // `.chat-project-row`: which folder and, beside it, the worktree select (ta-23f; T8.4 adds the
    // GitHub dialog). A hairline under it sets the group off.
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Project and repository" }
            .drawBehind { drawRect(t.line, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
            .padding(bottom = if (narrow) t.css.spaceMd else t.css.spaceLg),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
        ) {
            WorkspaceSelector(draft.cwd(), inputs.quickPicks, inputs.workspaceRoot, actions.onPickFolder, actions.onBrowse)
            WorktreeSelect(draft.form, actions.onSelectIsolation)
            // Slot (T8.4): GitHubWorkDialog.
        }
    }
    Box(Modifier.height(if (narrow) t.css.spaceMd else t.css.spaceLg))

    // ta-23f: WorktreeDetails, its own row between the project row and the live row (absent when local).
    WorktreeDetails(draft.form, draft.worktreeSource, actions.onWorktreeField)

    // ta-2uq / ta-xki: `.chat-mode-row-live` ("Session options"): the ModelSelector chip, Effort and
    // Mode (and opencode's Auto); below 64rem the SessionSettingsSheet trigger row carries all three.
    if (wideRow) DraftLiveRow(inputs, actions) else DraftSettingsTriggerRow(inputs, actions)
    Box(Modifier.height(t.css.spaceMd))

    // draft-composer.tsx: the hook's error, else the attachment flash.
    if (draft.error.isNotEmpty()) {
        StatusLine(TetherIcons.TriangleAlert, draft.error, DraftComposerTags.Error)
    } else if (inputs.notice != null) {
        StatusLine(TetherIcons.Paperclip, inputs.notice, DraftComposerTags.Notice)
    }
    if (draft.staged.isNotEmpty()) {
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(bottom = t.css.spaceSm)
                .semantics { contentDescription = "Attachments to send" }
                .testTag(DraftComposerTags.Attachments),
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
            verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
        ) {
            draft.staged.forEach { item -> StagedAttachmentChip(item, onRemove = { actions.onRemoveAttachment(item.id) }) }
        }
    }
}

/** The sheet's foot: the message well with the paperclip and Send, and why Send is disabled. */
@Composable
private fun DraftComposerFoot(inputs: DraftSheetInputs, actions: DraftSheetActions, narrow: Boolean, keyboardOpen: Boolean, focusOnOpen: Boolean) {
    val t = LocalTetherTokens.current
    val draft = inputs.draft
    MessageWell(draft, inputs.readiness, narrow, keyboardOpen, actions, focusOnOpen)
    // App addition: the web only disables Send; the reason it is disabled is said in words here.
    if (draft.error.isEmpty() && inputs.readiness.isNotEmpty()) {
        Text(
            inputs.readiness,
            color = t.muted,
            style = LocalTetherTypography.current.body.copy(fontSize = 12.sp),
            modifier = Modifier
                .padding(top = t.css.spaceSm)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(DraftComposerTags.Readiness),
        )
    }
}

/** `.section-label`: mono 0.62rem / 650, tracked, uppercase, `--faint`. */
@Composable
private fun SectionLabel(text: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text.uppercase(),
        color = t.faint,
        style = type.mono.let { androidx.compose.ui.text.TextStyle(fontFamily = it, fontSize = 9.92.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(650), letterSpacing = 0.06.em) },
        modifier = Modifier.padding(bottom = t.css.spaceXs).semantics { heading() },
    )
}

/** `.chat-waiting` (role=status): a glyph and the words. */
@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tag: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = t.css.spaceSm)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Icon(icon, contentDescription = null, tint = t.warning, modifier = Modifier.size(14.dp))
        // ta-28i: the server's words (a refusal) are prose: any bidi control is a token.
        Text(com.tether.app.ui.text.proseText(text), style = type.body.copy(fontSize = 12.48.sp), color = t.ink)
    }
}

/**
 * `.draft-message-well` (Studio): one well holding the text box across the top and, on its lower
 * deck, the paperclip, the key hint and Send. The text box is 6rem tall on a phone (2.75rem while the
 * keyboard is up), 8rem on a desktop, growing to 30% of the screen. A hardware Enter sends and
 * Shift+Enter breaks the line; the soft keyboard's action key is Send (the web's Enter on a phone).
 */
@Composable
private fun MessageWell(draft: DraftComposerState, readiness: String, narrow: Boolean, keyboardOpen: Boolean, actions: DraftSheetActions, focusOnOpen: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // r2 (F3): dashboard.tsx starts the operator in the message box, not on Close.
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (focusOnOpen) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // The engine owns the text; the field keeps its own selection while the two agree.
    var field by remember { mutableStateOf(TextFieldValue(draft.text, TextRange(draft.text.length))) }
    if (field.text != draft.text) field = TextFieldValue(draft.text, TextRange(draft.text.length))
    val disabled = sendDisabled(draft, readiness)
    val submit = { if (!sendDisabled(draft, readiness)) actions.onSubmit() }
    val shape = RoundedCornerShape(t.radiusMd)
    val minInput: Dp = when {
        narrow && keyboardOpen -> 44.dp
        narrow -> 96.dp
        else -> 128.dp
    }
    val maxInput = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.3f).dp.coerceAtLeast(minInput)
    Column(
        Modifier
            .fillMaxWidth()
            .cssSurface(shape, t.mineralDeep, CssBorder(1.dp, if (focused) t.violetStrong else t.lineStrong), emptyList())
            .padding(t.css.spaceSm),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        val style = type.body.copy(fontSize = 16.sp, lineHeight = 25.6.sp)
        BasicTextField(
            value = field,
            onValueChange = { next ->
                field = next
                if (next.text != draft.text) actions.onText(next.text)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minInput, max = maxInput)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    val enter = event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                        !event.isShiftPressed && field.composition == null
                    if (enter) submit()
                    enter
                }
                .semantics { contentDescription = DRAFT_INPUT_LABEL }
                .testTag(DraftComposerTags.Input),
            textStyle = style.copy(color = t.ink),
            cursorBrush = SolidColor(t.violet),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            interactionSource = interaction,
            decorationBox = { inner ->
                Box(Modifier.padding(t.css.spaceSm)) {
                    if (field.text.isEmpty()) Text(DRAFT_PLACEHOLDER, style = style, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
            TetherKey(
                onClick = actions.onAttach,
                classes = KeyClasses.Attach,
                icon = TetherIcons.Paperclip,
                iconSize = 18.dp,
                contentDescription = "Attach a file or image",
                modifier = Modifier.size(44.dp).testTag(DraftComposerTags.Attach),
                contentPadding = 0.dp,
            )
            // `.draft-message-hint`: Enter send · Shift Enter new line (clears its own semantics: it is a hint).
            Text(
                "Enter send · Shift Enter new line",
                color = t.muted,
                style = type.body.copy(fontSize = if (narrow) 10.08.sp else 10.88.sp),
                modifier = Modifier.weight(1f).clearAndSetSemantics { },
            )
            TetherKey(
                onClick = submit,
                classes = KeyClasses.ChatSend,
                label = if (draft.creating) "Creating…" else "Send",
                icon = TetherIcons.Send,
                iconSize = 18.dp,
                enabled = !disabled,
                minHeight = 44.dp,
                contentDescription = if (draft.creating) "Creating session…" else "Start session and send",
                modifier = Modifier.widthIn(min = 44.dp).testTag(DraftComposerTags.Send),
            )
        }
    }
}

/**
 * issue #49 WorkspaceSelector: the folder chip (its compacted path, else "Folder") opening a
 * popover of the quick picks — the folders already set in Tether — with "Browse for another
 * folder…" as its last row (the interim folder picker, T8.2). A pick sets the draft's folder.
 */
@Composable
private fun WorkspaceSelector(cwd: String, quickPicks: List<WorkspaceQuickPick>, root: String, onSelect: (String) -> Unit, onBrowse: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val shown = compactPath(cwd, root).ifEmpty { "Folder" }
    Box {
        ControlPill(
            label = SafeText.line(shown),
            enabled = true,
            contentDescription = if (cwd.isEmpty()) "Session working folder, none chosen" else "Session working folder, ${SafeText.line(cwd)}",
            onClick = { open = !open },
            icon = TetherIcons.Folder,
            active = open,
            maxWidth = 208.dp,
            stateDescription = if (open) "Expanded" else "Collapsed",
            testTag = DraftComposerTags.WorkspaceChip,
        )
        if (open) {
            val density = LocalDensity.current
            Popup(
                offset = with(density) { IntOffset(0, 48.dp.roundToPx()) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                WorkspacePopover(
                    cwd = cwd,
                    quickPicks = quickPicks,
                    root = root,
                    onPick = { path ->
                        open = false
                        onSelect(path)
                    },
                    onBrowse = {
                        open = false
                        onBrowse()
                    },
                )
            }
        }
    }
}

/** `.workspace-popover`: the "Working folder" head, the quick picks (they scroll) and the Browse row. */
@Composable
internal fun WorkspacePopover(cwd: String, quickPicks: List<WorkspaceQuickPick>, root: String, onPick: (String) -> Unit, onBrowse: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusMd)
    Column(
        Modifier
            .widthIn(min = 240.dp, max = 448.dp)
            .heightIn(max = 416.dp)
            .cssSurface(shape, t.graphite, CssBorder(1.dp, t.lineStrong), t.css.shadowMenu)
            .clip(shape)
            .semantics { paneTitle = "Session working folder" }
            .testTag(DraftComposerTags.WorkspacePopover),
    ) {
        Box(Modifier.padding(start = t.css.spaceMd, end = t.css.spaceMd, top = t.css.spaceMd)) { SectionLabel("Working folder") }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            quickPicks.forEach { pick ->
                val selected = pick.path == cwd
                val label = "${pick.label}, ${SafeText.line(compactPath(pick.path, root))}"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .background(if (selected) t.violetWash else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onPick(pick.path) }
                        .clearAndSetSemantics {
                            role = Role.RadioButton
                            contentDescription = label
                            this.selected = selected
                            onClick("Use this folder") { onPick(pick.path); true }
                            testTagProperty = DraftComposerTags.quickPick(pick.path)
                        }
                        .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(pick.label, color = t.white, style = type.body.copy(fontSize = 12.8.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(600)))
                        Text(codeLabel(compactPath(pick.path, root)), color = t.muted, style = type.body.copy(fontSize = 11.2.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (selected) Icon(TetherIcons.Check, contentDescription = null, tint = t.violet, modifier = Modifier.size(14.dp))
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(t.line, Offset.Zero, Size(size.width, 1.dp.toPx())) }
                .heightIn(min = 44.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = "Browse for another folder", onClick = onBrowse)
                .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm)
                .testTag(DraftComposerTags.Browse),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Icon(TetherIcons.FolderOpen, contentDescription = null, tint = t.faint, modifier = Modifier.size(14.dp))
            Text("Browse for another folder…", color = t.muted, style = type.body.copy(fontSize = 12.8.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(600)))
        }
    }
}

/**
 * draft-composer.tsx DraftLaunching: the hand-off stage between Send and the live session. The
 * operator's own message is already in place as the first bubble (the chat's own user bubble), with
 * "Opening the <provider> session…" under it, in words (never colour alone). The shell draws it on
 * the normal stage while the draft's create is in flight; the session's `created` swaps it out.
 */
@Composable
fun DraftLaunching(text: String, attachments: List<com.tether.app.protocol.Attachment>, providerLabel: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(
        modifier
            .fillMaxSize()
            .background(t.graphite)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceLg)
            .testTag(DraftComposerTags.Launching),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceMd),
    ) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty() || attachments.isNotEmpty()) {
            UserBubble(
                com.tether.app.protocol.model.TurnBlock(
                    blockId = "draft-launching",
                    kind = "user",
                    text = trimmed.ifEmpty { null },
                    attachments = attachments.map { com.tether.app.protocol.model.AttachmentMeta(it.name, it.mediaType) }.ifEmpty { null },
                ),
            )
        }
        Row(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Icon(TetherIcons.Loader, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
            Text("Opening the $providerLabel session…", color = t.muted, style = type.body.copy(fontSize = 13.12.sp))
        }
    }
}
