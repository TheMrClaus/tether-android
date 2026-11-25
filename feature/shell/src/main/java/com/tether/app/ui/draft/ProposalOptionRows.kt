package com.tether.app.ui.draft

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tether.app.client.ConnectionState
import com.tether.app.client.DraftComposerModel
import com.tether.app.client.DraftSessionOptions
import com.tether.app.client.DraftSessionOptionsModel
import com.tether.app.client.LabelText
import com.tether.app.client.ProposalSeed
import com.tether.app.client.Proposals
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.FolderPickerDialog
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.chat.ApprovalsPill
import com.tether.app.ui.chat.ControlPill
import com.tether.app.ui.chat.ControlSelect
import com.tether.app.ui.chat.ProposalCopy
import com.tether.app.ui.chat.ProposalOptionsHost
import com.tether.app.ui.chat.ProposalOptionsRequest
import com.tether.app.ui.chat.ProposalRow
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.prefs.InMemoryDraftStore
import com.tether.app.ui.prefs.TetherPreferences
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.sidebar.SidebarController
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.util.compactPath

/**
 * v175 (#293): the proposal card's Options rows. The card (in :feature:chat) draws the panel; this host supplies the rows, built
 * from the new-session composer's own pickers (the folder popover, the isolation select, the model browser, Effort, Mode,
 * Approvals): no second picker. Each proposal keeps its own form for the life of the app, so closing and reopening the panel
 * keeps the operator's choices; its picks never become the operator's remembered new-session choices.
 */
class ShellProposalOptionsHost(private val vm: TetherViewModel, private val prefs: UiPrefs) : ProposalOptionsHost {
    private val models = HashMap<String, DraftComposerModel>()

    internal fun modelFor(proposalId: String): DraftComposerModel =
        models.getOrPut(proposalId) { DraftComposerModel(vm.client, InMemoryDraftStore(), vm.viewModelScope, { null }, persistPicks = false) }

    @Composable
    override fun Rows(request: ProposalOptionsRequest) = ProposalOptionRows(vm, prefs, modelFor(request.proposalId), request)
}

/** Test tags of the Options rows. */
object ProposalOptionTags {
    fun row(row: ProposalRow) = "proposal-row-${row.word}"
    const val Suggested = "proposal-suggested"
    const val Replaced = "proposal-replaced"
    const val RowFault = "proposal-row-fault"
}

/** What the rows draw: the card's form and its seed, with the pickers' inputs gathered by the host (goldens seed it). */
internal class ProposalRowsInputs(
    val draft: com.tether.app.client.DraftComposerState,
    val seedForm: com.tether.app.protocol.tree.JsObj,
    val suggested: Set<ProposalRow>,
    val replaced: List<com.tether.app.ui.chat.ProposalReplacedView>,
    val faultRow: ProposalRow?,
    val enabled: Boolean,
    val browser: ModelBrowserInputs,
    val browserOpen: Boolean,
    val quickPicks: List<WorkspaceQuickPick>,
    val workspaceRoot: String,
    val providerLabel: String,
    val accountLabel: String,
    val accountChoosable: Boolean,
)

internal class ProposalRowsActions(
    val onFolder: (String) -> Unit = {},
    val onBrowseFolder: () -> Unit = {},
    val onIsolation: (String) -> Unit = {},
    val onHarness: () -> Unit = {},
    val onAccount: () -> Unit = {},
    val onModelChip: () -> Unit = {},
    val onEffort: (String) -> Unit = {},
    val onMode: (String, String) -> Unit = { _, _ -> },
    val onApprovals: (String, String) -> Unit = { _, _ -> },
    val onAuto: (String) -> Unit = {},
)

private fun JsStrOf(form: com.tether.app.protocol.tree.JsObj, key: String): String = (form[key] as? JsStr)?.value.orEmpty()

/** The row's value as the operator now has it, against the seed: equal = still the agent's suggestion. */
private fun unchanged(row: ProposalRow, now: com.tether.app.protocol.tree.JsObj, seed: com.tether.app.protocol.tree.JsObj): Boolean {
    val keys = when (row) {
        ProposalRow.Folder -> listOf("cwd")
        ProposalRow.Worktree -> listOf("useWorktree", "worktreeMode")
        ProposalRow.Harness -> listOf("key")
        ProposalRow.Account -> listOf("key")
        ProposalRow.Model -> listOf("model")
        ProposalRow.Effort -> listOf("reasoningEffort")
        ProposalRow.Mode -> listOf("mode")
        ProposalRow.Approvals -> listOf("toolApproval")
    }
    return keys.all { now[it] == seed[it] }
}

@Composable
private fun OptionRow(row: ProposalRow, label: String, inputs: ProposalRowsInputs, control: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    val fault = inputs.faultRow == row
    val tagged = row in inputs.suggested && unchanged(row, inputs.draft.form, inputs.seedForm)
    val replaced = inputs.replaced.firstOrNull { ProposalCopy.rowOfReplaced(it.field) == row }
    Column(Modifier.fillMaxWidth().testTag(ProposalOptionTags.row(row))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, color = t.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (tagged) {
                    Text(
                        "Suggested", color = t.faint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.border(1.dp, t.lineStrong, RoundedCornerShape(999.dp)).padding(horizontal = 6.dp, vertical = 1.dp).testTag(ProposalOptionTags.Suggested),
                    )
                }
            }
            Box(contentAlignment = Alignment.CenterEnd) { control() }
        }
        if (fault) {
            Text(
                ProposalCopy.refusedRowLine(row), color = t.danger, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(bottom = 6.dp).testTag(ProposalOptionTags.RowFault),
            )
        }
        if (replaced != null) {
            val using = when (row) {
                ProposalRow.Folder -> JsStrOf(inputs.draft.form, "cwd")
                ProposalRow.Harness -> inputs.providerLabel
                ProposalRow.Account -> inputs.accountLabel
                ProposalRow.Model -> JsStrOf(inputs.draft.form, "model")
                ProposalRow.Effort -> JsStrOf(inputs.draft.form, "reasoningEffort")
                ProposalRow.Mode -> JsStrOf(inputs.draft.form, "mode")
                ProposalRow.Approvals -> JsStrOf(inputs.draft.form, "toolApproval")
                ProposalRow.Worktree -> if (inputs.draft.form["useWorktree"] == JsBool.TRUE) "Worktree" else "Local"
            }.ifEmpty { "default" }
            Text(
                ProposalCopy.replacedLine(row, SafeText.line(replaced.suggested), SafeText.line(using)), color = t.warning, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(bottom = 6.dp).testTag(ProposalOptionTags.Replaced),
            )
        }
    }
}

/** The rows in place (the goldens shoot this): where rows, a divider, then who rows. */
@Composable
internal fun ProposalOptionRowsFrame(inputs: ProposalRowsInputs, actions: ProposalRowsActions) {
    val t = LocalTetherTokens.current
    val draft = inputs.draft
    val options: DraftSessionOptions = DraftSessionOptionsModel.of(draft)
    val cwd = JsStrOf(draft.form, "cwd")
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "New session options" }, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        OptionRow(ProposalRow.Folder, "Folder", inputs) { WorkspaceSelector(cwd, inputs.quickPicks, inputs.workspaceRoot, actions.onFolder, actions.onBrowseFolder) }
        OptionRow(ProposalRow.Worktree, "Worktree", inputs) { WorktreeSelect(draft.form, actions.onIsolation) }
        HorizontalDivider(color = t.line, modifier = Modifier.padding(vertical = 2.dp))
        OptionRow(ProposalRow.Harness, "Harness", inputs) {
            ControlPill(
                label = SafeText.line(inputs.providerLabel).ifEmpty { "Select" }, enabled = inputs.enabled, contentDescription = "Harness, ${inputs.providerLabel}",
                onClick = actions.onHarness, glyphProvider = inputs.browser.entries.firstOrNull { it.key == inputs.browser.selectedKey }?.provider,
                glyphTile = true, active = inputs.browserOpen, testTag = "proposal-harness",
            )
        }
        OptionRow(ProposalRow.Account, "Account", inputs) {
            ControlPill(
                label = SafeText.line(inputs.accountLabel), enabled = inputs.enabled && inputs.accountChoosable, contentDescription = "Account, ${inputs.accountLabel}",
                onClick = actions.onAccount, chevron = inputs.accountChoosable, testTag = "proposal-account",
            )
        }
        OptionRow(ProposalRow.Model, "Model", inputs) { ModelSelectorChip(inputs.browser, inputs.browserOpen, actions.onModelChip) }
        options.effort?.let { effort ->
            OptionRow(ProposalRow.Effort, "Effort", inputs) {
                ControlSelect(control = effort, name = "Reasoning effort", enabled = inputs.enabled, lockCopy = null, onSelect = actions.onEffort, emptyLabel = EFFORT_DEFAULT, testTag = DraftOptionsTags.Effort)
            }
        } ?: options.effortNoneHint?.let { hint ->
            OptionRow(ProposalRow.Effort, "Effort", inputs) {
                ControlPill(label = hint, enabled = true, contentDescription = "Reasoning effort: $hint", onClick = {}, chevron = false, unavailable = true, testTag = DraftOptionsTags.Effort)
            }
        }
        options.mode?.let { mode ->
            OptionRow(ProposalRow.Mode, "Mode", inputs) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ControlSelect(
                        control = mode, name = options.modeAriaLabel, enabled = inputs.enabled, lockCopy = null,
                        onSelect = { actions.onMode(it, options.provider) }, danger = options.modeDanger, testTag = DraftOptionsTags.Mode,
                    )
                    options.auto?.let { auto -> DraftAutoChip(auto.on, { actions.onAuto(options.provider) }) }
                }
            }
        }
        options.approvals?.let { approvals ->
            OptionRow(ProposalRow.Approvals, "Approvals", inputs) {
                ApprovalsPill(approvals, enabled = inputs.enabled, onSelect = { actions.onApprovals(it, options.provider) })
            }
        }
    }
}

@Composable
internal fun ProposalOptionRows(vm: TetherViewModel, prefs: UiPrefs, model: DraftComposerModel, request: ProposalOptionsRequest) {
    val client = vm.client
    val draft by model.state.collectAsStateWithLifecycle()
    val connection by client.connection.collectAsStateWithLifecycle()
    val catalog by client.providerCatalog.collectAsStateWithLifecycle()
    val live by client.providerCatalogLive.collectAsStateWithLifecycle()
    val providers by client.providers.collectAsStateWithLifecycle()
    val sessions by client.sessions.collectAsStateWithLifecycle()
    val root by client.workspaceRoot.collectAsStateWithLifecycle()
    val directories by client.directories.collectAsStateWithLifecycle()
    val picked by vm.currentWorkspace.collectAsStateWithLifecycle()
    val preferences by remember(prefs, client) { prefs.preferencesFor(client.serverUrl) }.collectAsStateWithLifecycle(initialValue = TetherPreferences.Default)
    val linkEpoch by client.linkEpoch.collectAsStateWithLifecycle()
    val connected = connection == ConnectionState.Connected
    val source = sessions.firstOrNull { it.id == request.sessionId }
    val browser = remember(request.proposalId) { ModelBrowserState() }
    var browsing by remember { mutableStateOf(false) }
    var seed by remember(request.proposalId) { mutableStateOf<ProposalSeed.Result?>(null) }
    val collator = remember { com.tether.app.ui.chat.IcuJsCollator.forLocale() }

    LaunchedEffect(connected) { if (connected) client.requestProviderCatalog() }
    LaunchedEffect(catalog, live, providers) { model.refresh() }
    // Seed once: the entries are in (the live catalog, else the base rows). A picked harness or account that is not here seeds nothing.
    LaunchedEffect(request.proposalId, draft.entries.isNotEmpty(), source?.id) {
        if (seed != null || draft.entries.isEmpty() || source == null) return@LaunchedEffect
        val fresh = ProposalSeed.of(request.suggestions, draft.entries, source, vm.draftComposer.preferences().also(model::usePreferences))
        if (fresh != null) {
            model.seedProposal(fresh)
            seed = fresh
        } else {
            model.setCwd(request.suggestions.let { (it["cwd"] as? JsStr)?.value } ?: source.cwd)
        }
    }

    val key = JsStrOf(draft.form, "key")
    val entry = draft.entries.firstOrNull { it.key == key }
    LaunchedEffect(draft.form, draft.modified, draft.proposalCodexCurrent, entry) {
        if (entry != null && seed != null) request.onSettings(Proposals.startSettings(draft.form, entry, draft.modified, draft.proposalCodexCurrent))
    }
    val rows = com.tether.app.client.NewSessionGuard.rows(if (live) catalog else null, providers)
    val browserInputs = draftBrowserInputs(draft, if (live) catalog else null, providers, collator, System.currentTimeMillis())
    val providerLabel = draftProviderLabel(rows.firstOrNull { it.choice.key == key }, providers)
    val sameHarness = draft.entries.filter { it.provider == entry?.provider }
    val inputs = ProposalRowsInputs(
        draft = draft,
        seedForm = seed?.form ?: draft.form,
        suggested = ProposalCopy.suggestedRows(request.suggestions),
        replaced = request.replaced,
        faultRow = ProposalCopy.faultRow(request.faultRow),
        enabled = request.enabled,
        browser = browserInputs,
        browserOpen = browser.open,
        quickPicks = workspaceQuickPicks(preferences.pinnedProjects, preferences.defaultWorkspace, SidebarController.resolveCurrentWorkspace(picked, preferences, root), root),
        workspaceRoot = root.orEmpty(),
        providerLabel = providerLabel,
        accountLabel = entry?.profileId?.takeIf { it.isNotEmpty() }?.let { LabelText.label(entry.label ?: it) } ?: "Default",
        accountChoosable = sameHarness.size > 1,
    )
    val cwd = JsStrOf(draft.form, "cwd")
    val actions = ProposalRowsActions(
        onFolder = model::setCwd,
        onBrowseFolder = {
            client.browse(cwd.ifEmpty { root.orEmpty() }.ifEmpty { null })
            browsing = true
        },
        onIsolation = { model.selectIsolation(it) },
        onHarness = {
            browser.openOn(draft.entries, key)
            browser.back()
        },
        onAccount = { browser.openOn(draft.entries, key) },
        onModelChip = { browser.openOn(draft.entries, key) },
        onEffort = { model.selectEffort(it) },
        onMode = { mode, drawnFor -> model.selectMode(mode, drawnFor) },
        onApprovals = { value, drawnFor -> model.selectToolApproval(value, drawnFor) },
        onAuto = { drawnFor -> model.toggleAuto(drawnFor) },
    )
    ProposalOptionRowsFrame(inputs, actions)

    if (browser.open) {
        val pick: (String, String) -> Unit = { entryKey, modelId ->
            if (entryKey != key) model.selectProviderAndModel(entryKey, modelId) else model.selectModel(modelId)
            browser.close()
        }
        val browserActions = ModelBrowserActions(
            onSelect = pick,
            onRetry = { k -> client.refreshProviders(k, linkEpoch) },
            onAddModel = model::addCustomModel,
            onRemoveModel = model::removeCustomModel,
            onClose = browser::close,
        )
        Dialog(onDismissRequest = browser::close, properties = DraftDialogProperties) {
            ModelBrowserFrame(browserInputs, browser, browserActions, currentLayoutClass())
        }
    }
    if (browsing) {
        FolderPickerDialog(
            directories = directories,
            current = cwd.ifEmpty { root },
            onDismiss = { browsing = false },
            onBrowse = { client.browse(it) },
            onChoose = { chosen ->
                browsing = false
                model.setCwd(chosen)
            },
            title = "Choose a working folder",
            onCreateFolder = { parent, name -> client.createFolder(parent, name) },
        )
    }
}
