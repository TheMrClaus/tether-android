package com.tether.app.ui.scheduled

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.client.ScheduledAction
import com.tether.app.ui.components.CommandList
import com.tether.app.ui.components.ConsentPanel
import com.tether.app.ui.components.ConsentText
import com.tether.app.ui.components.HiddenCharactersWarning
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.StudioDialog
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherSelectMenu
import com.tether.app.ui.components.TetherSelectOption
import com.tether.app.ui.components.consentSentence
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Stable hooks for the editor's behaviour tests and goldens. */
object ScheduleEditorTags {
    const val Dialog = "schedule-dialog"
    const val Name = "schedule-name"
    const val Prompt = "schedule-prompt"
    const val Workspace = "schedule-workspace"
    const val WorkspaceSuggestions = "schedule-workspace-suggestions"
    const val Agent = "schedule-agent"
    const val Model = "schedule-model"
    const val Effort = "schedule-effort"
    const val Mode = "schedule-mode"
    const val Sandbox = "schedule-sandbox"
    const val Cadence = "schedule-cadence"
    const val TimeZone = "schedule-timezone"
    const val RunLimit = "schedule-run-limit"
    const val RunAt = "schedule-run-at"
    const val Cron = "schedule-cron"
    const val Worktree = "schedule-worktree"
    const val Error = "schedule-error"
    const val Cancel = "schedule-cancel"
    const val Submit = "schedule-submit"
    const val Close = "schedule-close"

    /** ta-m7ef (v143 r3): the setup check's status line, the approval panel and its three keys. */
    const val SetupChecking = "schedule-setup-checking"
    const val SetupConfirm = "schedule-setup-confirm"
    const val SetupBack = "schedule-setup-back"
    const val SetupWithout = "schedule-setup-without"
    const val SetupApprove = "schedule-setup-approve"
}

/** ta-m7ef: the approval panel's three keys (scheduled-actions-view.tsx 1bf4a465: Back, Save without setup, Approve setup and save). */
data class ScheduleSetupActions(
    val onBack: () -> Unit = {},
    val onSaveWithoutSetup: () -> Unit = {},
    val onApprove: () -> Unit = {},
)

/**
 * The open editor (scheduled-actions-view.tsx `editingId`, `form`, `cadenceMode`, `onceValue`,
 * `formError`). [editingId] null = a new schedule.
 */
data class ScheduleEditor(
    val editingId: String?,
    val form: ScheduleForm,
    val mode: CadenceMode,
    val onceValue: String,
    val error: String = "",
) {
    companion object {
        /** scheduled-actions-view.tsx:230-237 `openCreate`. */
        fun create(entry: ProviderCatalogEntry?, cwd: String, now: Long, zone: ZoneId) = ScheduleEditor(
            editingId = null,
            form = ScheduledRules.emptyForm(entry, cwd, zone),
            mode = CadenceMode.Preset,
            onceValue = ScheduledRules.defaultOnceInputValue(now, zone),
        )

        /** scheduled-actions-view.tsx:239-263 `openEdit`. */
        fun edit(schedule: ScheduledAction, now: Long, zone: ZoneId): ScheduleEditor {
            val mode = ScheduledRules.cadenceModeFor(schedule.cron, schedule.maxRuns)
            return ScheduleEditor(
                editingId = schedule.id,
                form = ScheduledRules.formFor(schedule),
                mode = mode,
                onceValue = if (mode == CadenceMode.Once) ScheduledRules.onceInputValueFromSchedule(schedule, now, zone) else ScheduledRules.defaultOnceInputValue(now, zone),
            )
        }

        private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        /** One string for the saved-state bundle (a rotation or a process death restores the open editor). */
        fun encode(editor: ScheduleEditor): String = buildJsonObject {
            val f = editor.form
            editor.editingId?.let { put("editingId", it) }
            put("mode", editor.mode.key)
            put("onceValue", editor.onceValue)
            put("error", editor.error)
            put("name", f.name)
            put("prompt", f.prompt)
            put("cwd", f.cwd)
            put("providerKey", f.providerKey)
            put("provider", f.provider)
            put("profileId", f.profileId)
            put("model", f.model)
            put("reasoningEffort", f.reasoningEffort)
            put("permissionMode", f.permissionMode)
            put("sandboxPolicy", f.sandboxPolicy)
            put("useWorktree", f.useWorktree)
            put("cron", f.cron)
            put("timeZone", f.timeZone)
            put("maxRuns", f.maxRuns)
            put("extra", f.extra)
        }.toString()

        fun decode(saved: String): ScheduleEditor? = runCatching {
            val o = Json.parseToJsonElement(saved).jsonObject
            ScheduleEditor(
                editingId = o.s("editingId"),
                form = ScheduleForm(
                    name = o.s("name").orEmpty(),
                    prompt = o.s("prompt").orEmpty(),
                    cwd = o.s("cwd").orEmpty(),
                    providerKey = o.s("providerKey").orEmpty(),
                    provider = o.s("provider") ?: "claude",
                    profileId = o.s("profileId"),
                    model = o.s("model"),
                    reasoningEffort = o.s("reasoningEffort"),
                    permissionMode = o.s("permissionMode"),
                    sandboxPolicy = o.s("sandboxPolicy"),
                    useWorktree = (o["useWorktree"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    cron = o.s("cron") ?: ScheduledRules.DEFAULT_CRON,
                    timeZone = o.s("timeZone") ?: "UTC",
                    maxRuns = (o["maxRuns"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull,
                    extra = o["extra"] as? JsonObject ?: JsonObject(emptyMap()),
                ),
                mode = CadenceMode.entries.firstOrNull { it.key == o.s("mode") } ?: CadenceMode.Preset,
                onceValue = o.s("onceValue").orEmpty(),
                error = o.s("error").orEmpty(),
            )
        }.getOrNull()
    }
}

/** The Run limit input's longest value (a `type="number"`'s digits; the server bounds it at 10000). */
private const val RUN_LIMIT_DIGITS = 9

/**
 * `<dialog className="schedule-dialog">` as a window: the skin's scrim and the case rising in.
 * Like the web's `showModal()` dialog, a tap on the backdrop does nothing; Back (the web's Esc),
 * the X and Cancel close it without sending.
 */
@Composable
fun ScheduleEditorDialog(
    editor: ScheduleEditor,
    entries: List<ProviderCatalogEntry>,
    workspaces: List<String>,
    viewportWidth: Int,
    zone: ZoneId,
    clock: () -> Long,
    onChange: (ScheduleEditor) -> Unit,
    onClose: () -> Unit,
    onSubmit: () -> Unit,
    setup: ScheduleSetupCheck? = null,
    setupActions: ScheduleSetupActions = ScheduleSetupActions(),
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        val t = LocalTetherTokens.current
        val reduced = LocalReducedMotion.current
        val progress = remember { androidx.compose.animation.core.Animatable(if (reduced) 1f else 0f) }
        LaunchedEffect(reduced) { if (!reduced) progress.animateTo(1f, androidx.compose.animation.core.tween(t.css.duration, easing = t.css.easeOut.toEasing())) }
        ScheduleEditorFrame(
            editor = editor,
            entries = entries,
            workspaces = workspaces,
            viewportWidth = viewportWidth,
            zone = zone,
            clock = clock,
            onChange = onChange,
            onClose = onClose,
            onSubmit = onSubmit,
            setup = setup,
            setupActions = setupActions,
            autoFocus = true,
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            surfaceModifier = Modifier.graphicsLayer {
                val p = progress.value
                alpha = p
                translationY = (1f - p) * 8.dp.toPx()
                scaleX = 0.99f + 0.01f * p
                scaleY = 0.99f + 0.01f * p
            },
        )
    }
}

/**
 * The editor inline, centred over the scrim (what the goldens shoot): `.schedule-dialog` (globals.css
 * 9923-9994, studio.css 511-549, 761-773 and its ≤ 640px rules): header (title, blurb, close),
 * the scrolling form, footer (Cancel, Create schedule / Save changes).
 */
@Composable
fun ScheduleEditorFrame(
    editor: ScheduleEditor,
    entries: List<ProviderCatalogEntry>,
    workspaces: List<String>,
    viewportWidth: Int,
    zone: ZoneId,
    onChange: (ScheduleEditor) -> Unit,
    onClose: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    surfaceModifier: Modifier = Modifier,
    clock: () -> Long = System::currentTimeMillis,
    autoFocus: Boolean = false,
    setup: ScheduleSetupCheck? = null,
    setupActions: ScheduleSetupActions = ScheduleSetupActions(),
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val layout = ScheduledLayout(viewportWidth)
    BoxWithConstraints(modifier.fillMaxSize().background(dialogScrim(t)), contentAlignment = Alignment.Center) {
        val width = if (layout.phone) maxWidth - 24.dp else minOf(800.dp, maxWidth - 48.dp)
        val shape = RoundedCornerShape(if (layout.phone) 14.dp else StudioDialog.radius)
        Column(
            surfaceModifier
                .width(width)
                .heightIn(max = maxHeight - if (layout.phone) 32.dp else 48.dp)
                .cssSurface(shape, t.graphite, null, StudioDialog.shadows)
                .clip(shape)
                .testTag(ScheduleEditorTags.Dialog),
        ) {
            // header
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (layout.phone) 76.dp else 88.dp)
                    .edge(t.line, top = false)
                    .padding(horizontal = if (layout.phone) 20.dp else 28.dp, vertical = if (layout.phone) 18.dp else 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceLg),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (editor.editingId != null) "Edit schedule" else "New schedule",
                        color = t.white,
                        style = css(type.ui, if (layout.phone) 20f else 22f, 700, trackingEm = -0.025f, lineHeight = 1.3f),
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        "Each run starts a fresh agent with this prompt and workspace.",
                        color = t.muted,
                        style = css(type.ui, 13f, 400, lineHeight = 1.6f),
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(t.radiusSm))
                        .clickable(role = Role.Button, onClick = onClose)
                        .semantics { contentDescription = "Close schedule editor" }
                        .testTag(ScheduleEditorTags.Close),
                    contentAlignment = Alignment.Center,
                ) { Icon(TetherIcons.X, contentDescription = null, tint = t.ink, modifier = Modifier.size(18.dp)) }
            }
            // body
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (layout.phone) 20.dp else 28.dp, vertical = if (layout.phone) 24.dp else 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                EditorFields(editor, entries, workspaces, layout, zone, clock, onChange, autoFocus, setup, setupActions)
            }
            // footer
            Row(
                Modifier
                    .fillMaxWidth()
                    .edge(t.line, top = true)
                    .padding(horizontal = if (layout.phone) 20.dp else 28.dp, vertical = if (layout.phone) 16.dp else 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TetherKey(onClick = onClose, classes = KeyClasses.ButtonSecondary, label = "Cancel", modifier = Modifier.testTag(ScheduleEditorTags.Cancel))
                TetherKey(
                    onClick = onSubmit,
                    classes = KeyClasses.ButtonPrimary,
                    label = if (editor.editingId != null) "Save changes" else "Create schedule",
                    // v143 r3: not while the setup check is running or waiting for approval.
                    enabled = setup == null,
                    modifier = Modifier.testTag(ScheduleEditorTags.Submit),
                )
            }
        }
    }
}

@Composable
private fun EditorFields(
    editor: ScheduleEditor,
    entries: List<ProviderCatalogEntry>,
    workspaces: List<String>,
    layout: ScheduledLayout,
    zone: ZoneId,
    clock: () -> Long,
    onChange: (ScheduleEditor) -> Unit,
    autoFocus: Boolean,
    setup: ScheduleSetupCheck? = null,
    setupActions: ScheduleSetupActions = ScheduleSetupActions(),
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val form = editor.form
    val set = { next: ScheduleForm -> onChange(editor.copy(form = next)) }
    val selectedEntry = entries.firstOrNull { it.key == form.providerKey } ?: entries.firstOrNull()
    val selectedModel = selectedEntry?.models?.firstOrNull { it.value == form.model }
    val once = editor.mode == CadenceMode.Once

    val nameFocus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }

    Field("Name") {
        FieldInput(form.name, { set(form.copy(name = it)) }, "Morning issue triage", ScheduleEditorTags.Name, "Name", layout, Modifier.focusRequester(nameFocus))
    }
    Field("Prompt") {
        FieldInput(
            form.prompt, { set(form.copy(prompt = it)) },
            "Review new issues and pull requests, then summarize what needs attention.",
            ScheduleEditorTags.Prompt, "Prompt", layout, singleLine = false, minHeight = 120.dp,
        )
    }
    Field("Workspace") {
        FieldInput(
            form.cwd, { set(form.copy(cwd = it)) }, "/path/to/repository", ScheduleEditorTags.Workspace, "Workspace", layout,
            trailing = if (workspaces.isEmpty()) null else {
                {
                    FieldMenuAnchor(
                        options = workspaces.map { TetherSelectOption(it, SafeText.line(it)) },
                        selectedValue = form.cwd,
                        onSelect = { set(form.copy(cwd = it.value)) },
                        contentDescription = "Workspace suggestions",
                        tag = ScheduleEditorTags.WorkspaceSuggestions,
                    )
                }
            },
        )
    }

    // `.schedule-form-grid`: three columns, one below 48rem.
    val agentFields = buildList<@Composable () -> Unit> {
        add {
            Field("Agent") {
                FieldSelect(
                    options = entries.map { TetherSelectOption(it.key, SafeText.line(it.label ?: it.provider)) },
                    selectedValue = form.providerKey,
                    onSelect = { set(ScheduledRules.selectProvider(form, entries, it.value)) },
                    tag = ScheduleEditorTags.Agent, label = "Agent", layout = layout,
                    // A key the list does not hold shows its first option, as a native select does.
                    fallbackLabel = entries.firstOrNull()?.let { SafeText.line(it.label ?: it.provider) }.orEmpty(),
                )
            }
        }
        add {
            Field("Model") {
                FieldSelect(
                    options = listOf(TetherSelectOption("", "Provider default")) +
                        selectedEntry?.models.orEmpty().filter { it.legacy != true }.map { TetherSelectOption(it.value, SafeText.line(it.displayName)) },
                    selectedValue = form.model.orEmpty(),
                    onSelect = { set(form.copy(model = it.value.ifEmpty { null }, reasoningEffort = null)) },
                    tag = ScheduleEditorTags.Model, label = "Model", layout = layout, fallbackLabel = "Provider default",
                )
            }
        }
        val variants = selectedModel?.variants.orEmpty()
        if (variants.isNotEmpty()) add {
            Field("Effort") {
                FieldSelect(
                    options = listOf(TetherSelectOption("", "Provider default")) + variants.map { TetherSelectOption(it.value, SafeText.line(it.label)) },
                    selectedValue = form.reasoningEffort.orEmpty(),
                    onSelect = { set(form.copy(reasoningEffort = it.value.ifEmpty { null })) },
                    tag = ScheduleEditorTags.Effort, label = "Effort", layout = layout, fallbackLabel = "Provider default",
                )
            }
        }
        add {
            Field("Mode") {
                FieldSelect(
                    options = listOf(
                        TetherSelectOption("", "Server default"),
                        TetherSelectOption("default", "Manual approvals"),
                        TetherSelectOption("acceptEdits", "Accept edits"),
                        TetherSelectOption("plan", "Plan only"),
                        TetherSelectOption("bypassPermissions", "Auto approve"),
                    ),
                    selectedValue = form.permissionMode.orEmpty(),
                    onSelect = { set(form.copy(permissionMode = it.value.ifEmpty { null })) },
                    tag = ScheduleEditorTags.Mode, label = "Mode", layout = layout, fallbackLabel = "Server default",
                )
            }
        }
        add {
            // ta-m73: "Server default" is the server's one rule — the server-wide tier, else
            // workspace-write for Claude (never a silent no-sandbox); Codex keeps its own default.
            val hint = if (form.sandboxPolicy != null) null else if (form.provider == "claude") "Server tier, else workspace write" else "Server tier, else the provider's own"
            Field("Sandbox", hint) {
                FieldSelect(
                    options = listOf(
                        TetherSelectOption("", "Server default"),
                        TetherSelectOption("read-only", "Read-only"),
                        TetherSelectOption("workspace-write", "Workspace write"),
                        TetherSelectOption("off", "Full access"),
                    ),
                    selectedValue = form.sandboxPolicy.orEmpty(),
                    onSelect = { set(form.copy(sandboxPolicy = it.value.ifEmpty { null })) },
                    tag = ScheduleEditorTags.Sandbox, label = "Sandbox", layout = layout, fallbackLabel = "Server default",
                )
            }
        }
    }
    FormGrid(layout, agentFields)

    val cadenceFields = listOf<@Composable () -> Unit>(
        {
            Field("Cadence") {
                FieldSelect(
                    options = ScheduledRules.CADENCE_PRESETS.map { TetherSelectOption(it.value, it.label) } +
                        TetherSelectOption(CadenceMode.Once.key, "Run once…") + TetherSelectOption(CadenceMode.Custom.key, "Custom cron…"),
                    selectedValue = if (editor.mode == CadenceMode.Preset) form.cron else editor.mode.key,
                    onSelect = {
                        val (next, mode, onceValue) = ScheduledRules.selectCadence(form, editor.onceValue, it.value, clock(), zone)
                        onChange(editor.copy(form = next, mode = mode, onceValue = onceValue))
                    },
                    tag = ScheduleEditorTags.Cadence, label = "Cadence", layout = layout,
                    fallbackLabel = ScheduledRules.CADENCE_PRESETS.first().label,
                )
            }
        },
        {
            Field("Time zone", if (once) "Uses your device's zone" else null) {
                FieldInput(form.timeZone, { set(form.copy(timeZone = it)) }, "Europe/Madrid", ScheduleEditorTags.TimeZone, "Time zone", layout, enabled = !once)
            }
        },
        {
            Field("Run limit", if (once) "Locked — runs once" else "Optional") {
                FieldInput(
                    value = if (once) "1" else form.maxRuns?.toString().orEmpty(),
                    onValueChange = { text ->
                        val digits = text.filter { it in '0'..'9' }.take(RUN_LIMIT_DIGITS)
                        set(form.copy(maxRuns = digits.toIntOrNull()))
                    },
                    placeholder = "Unlimited",
                    tag = ScheduleEditorTags.RunLimit,
                    label = "Run limit",
                    layout = layout,
                    enabled = !once,
                    keyboardType = KeyboardType.Number,
                )
            }
        },
    )
    FormGrid(layout, cadenceFields)

    if (once) {
        Field("Run at", "Runs once, then the schedule completes") {
            RunAtInput(editor.onceValue, layout) { value -> onChange(editor.copy(onceValue = value, form = ScheduledRules.pickOnce(form, value, zone))) }
        }
    }
    if (editor.mode == CadenceMode.Custom) {
        Field("Cron expression", "minute hour day month weekday") {
            FieldInput(form.cron, { set(form.copy(cron = it)) }, "0 9 * * 1-5", ScheduleEditorTags.Cron, "Cron expression", layout, mono = true)
        }
    }
    WorktreeCheckbox(form.useWorktree) { set(form.copy(useWorktree = !form.useWorktree)) }
    // v143 r3 (ta-6t1, scheduled-actions-view.tsx 1bf4a465): an isolated schedule's save approves its setup first.
    if (setup != null && !setup.confirming) {
        ConsentText(ScheduleSetup.CHECKING, tag = ScheduleEditorTags.SetupChecking)
    }
    setup?.approval?.takeIf { setup.confirming }?.let { approval ->
        ConsentPanel(
            title = ScheduleSetup.TITLE,
            modifier = Modifier.testTag(ScheduleEditorTags.SetupConfirm).semantics { contentDescription = ScheduleSetup.CONFIRM_LABEL },
            actions = {
                TetherKey(onClick = setupActions.onBack, classes = KeyClasses.ButtonSecondary, label = ScheduleSetup.BACK, modifier = Modifier.testTag(ScheduleEditorTags.SetupBack))
                TetherKey(onClick = setupActions.onSaveWithoutSetup, classes = KeyClasses.ButtonSecondary, label = ScheduleSetup.SAVE_WITHOUT, modifier = Modifier.testTag(ScheduleEditorTags.SetupWithout))
                TetherKey(onClick = setupActions.onApprove, classes = KeyClasses.ButtonPrimary, label = ScheduleSetup.APPROVE, modifier = Modifier.testTag(ScheduleEditorTags.SetupApprove))
            },
        ) {
            ConsentText(
                consentSentence(
                    "Its committed ", "tether.json", " on ", approval.baseRef,
                    " declares setup that runs outside the agent's sandbox. Approving saves these exact commands with the schedule; a run whose setup " +
                        "differs runs none. The files they run come from the branch as it is at each run. Teardown is not approved here: it is asked " +
                        "for when a run's session is ended.",
                ),
            )
            if (approval.hiddenCharacters) HiddenCharactersWarning()
            if (approval.commands.isNotEmpty()) CommandList(ScheduleSetup.SETUP_LABEL, approval.commands, tagPrefix = "schedule-setup-command")
            approval.portScript?.takeIf { it.isNotEmpty() }?.let { CommandList(ScheduleSetup.PORT_SCRIPT_LABEL, listOf(it), tagPrefix = "schedule-port-script") }
            val sha = approval.portScriptSha256
            if (!approval.portScript.isNullOrEmpty() && !sha.isNullOrEmpty()) {
                ConsentText(consentSentence("Port script contents (SHA-256): ", sha))
            }
        }
    }
    if (editor.error.isNotEmpty()) {
        Text(editor.error, color = t.danger, style = css(type.ui, 13f, 400), modifier = Modifier.alertRegion().testTag(ScheduleEditorTags.Error))
    }
}

@Composable
private fun FormGrid(layout: ScheduledLayout, cells: List<@Composable () -> Unit>) {
    if (layout.mobile) {
        Column(verticalArrangement = Arrangement.spacedBy(if (layout.phone) 20.dp else 16.dp)) { cells.forEach { it() } }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (row in cells.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (cell in row) Box(Modifier.weight(1f)) { cell() }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** `.schedule-field`: the label (`> span`, with its `small` hint) over the control. */
@Composable
private fun Field(label: String, hint: String? = null, control: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, color = t.ink, style = css(type.ui, 13f, 600))
            if (hint != null) Text(hint, color = t.faint, style = css(type.ui, 10.8f, 550), modifier = Modifier.padding(start = 4.dp))
        }
        control()
    }
}

/** `.schedule-field :is(input, textarea, select)` (studio.css 767-768): the well every control sits in. */
@Composable
private fun Modifier.fieldWell(focused: Boolean, enabled: Boolean = true): Modifier {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(8.dp)
    return this
        .then(if (focused) Modifier.border(2.dp, t.focusGlow, RoundedCornerShape(10.dp)).padding(2.dp) else Modifier.padding(2.dp))
        .background(t.graphite, shape)
        .border(1.dp, if (focused) t.violet else t.lineStrong, shape)
        .alpha(if (enabled) 1f else 0.6f)
}

@Composable
private fun FieldInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tag: String,
    label: String,
    layout: ScheduledLayout,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minHeight: Dp = 46.dp,
    enabled: Boolean = true,
    mono: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = css(if (mono) type.mono else type.ui, if (layout.phone) 16f else 14f, 400, lineHeight = 1.5f).copy(color = if (enabled) t.white else t.faint)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = style,
        cursorBrush = SolidColor(t.violet),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .testTag(tag),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().fieldWell(focused, enabled).heightIn(min = minHeight - 4.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            ) {
                Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = style.copy(color = t.faint), maxLines = if (singleLine) 1 else 3)
                    inner()
                }
                trailing?.invoke(this)
            }
        },
    )
}

/** A native `<select>` in `.schedule-field`: the well with its value and a chevron, the app's select menu. */
@Composable
private fun FieldSelect(
    options: List<TetherSelectOption>,
    selectedValue: String?,
    onSelect: (TetherSelectOption) -> Unit,
    tag: String,
    label: String,
    layout: ScheduledLayout,
    fallbackLabel: String,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.value == selectedValue }?.label ?: fallbackLabel
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .fieldWell(expanded)
                .heightIn(min = 42.dp)
                .clickable(role = Role.DropdownList) { expanded = !expanded }
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = current
                }
                .testTag(tag)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current, color = t.white, style = css(type.ui, if (layout.phone) 16f else 14f, 400, lineHeight = 1.5f), maxLines = 1, modifier = Modifier.weight(1f))
            Icon(TetherIcons.ChevronDown, contentDescription = null, tint = t.faint, modifier = Modifier.size(14.dp))
        }
        if (expanded) {
            SelectPopup(options, selectedValue, onDismiss = { expanded = false }) {
                expanded = false
                onSelect(it)
            }
        }
    }
}

/** The Workspace field's suggestions key (Chrome draws a `<datalist>` input with a dropdown arrow). */
@Composable
private fun FieldMenuAnchor(
    options: List<TetherSelectOption>,
    selectedValue: String?,
    onSelect: (TetherSelectOption) -> Unit,
    contentDescription: String,
    tag: String,
) {
    val t = LocalTetherTokens.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(42.dp)
                .clickable(role = Role.DropdownList) { expanded = !expanded }
                .semantics { this.contentDescription = contentDescription }
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) { Icon(TetherIcons.ChevronDown, contentDescription = null, tint = t.faint, modifier = Modifier.size(14.dp)) }
        if (expanded) {
            SelectPopup(options, selectedValue, onDismiss = { expanded = false }) {
                expanded = false
                onSelect(it)
            }
        }
    }
}

@Composable
private fun SelectPopup(options: List<TetherSelectOption>, selectedValue: String?, onDismiss: () -> Unit, onSelect: (TetherSelectOption) -> Unit) {
    val t = LocalTetherTokens.current
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { t.css.spaceXs.roundToPx() }
    var opensUp by remember { mutableStateOf(false) }
    val provider = remember(gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val below = windowSize.height - anchorBounds.bottom
                val up = below < popupContentSize.height + gap && anchorBounds.top > below
                opensUp = up
                val y = if (up) anchorBounds.top - gap - popupContentSize.height else anchorBounds.bottom + gap
                val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        TetherSelectMenu(options = options, selectedValue = selectedValue, onSelect = onSelect, opensUp = opensUp)
    }
}

/** Chrome's display of a `datetime-local` value ("10/04/2026, 09:00 AM" in en-US). */
internal fun runAtDisplay(value: String): String {
    val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})$").matchEntire(value) ?: return value
    val (y, mo, d, h, mi) = m.destructured
    return runCatching {
        LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt()).format(DateTimeFormatter.ofPattern("MM/dd/yyyy, hh:mm a", Locale.US))
    }.getOrDefault(value)
}

/**
 * `<input type="datetime-local">`: Chrome on Android opens the platform's date picker and then its
 * time picker; the value is local time, `YYYY-MM-DDTHH:mm`.
 */
@Composable
private fun RunAtInput(value: String, layout: ScheduledLayout, onPick: (String) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val context = LocalContext.current
    val open = {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})$").matchEntire(value)
        val start = m?.destructured?.let { (y, mo, d, h, mi) -> listOf(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt()) }
            ?: LocalDateTime.now().let { listOf(it.year, it.monthValue, it.dayOfMonth, it.hour, it.minute) }
        DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, hour, minute ->
                onPick(ScheduledRules.formatOnceInputValue(LocalDateTime.of(year, month + 1, day, hour, minute)))
            }, start[3], start[4], DateFormat.is24HourFormat(context)).show()
        }, start[0], start[1] - 1, start[2]).show()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .fieldWell(false)
            .heightIn(min = 42.dp)
            .clickable(role = Role.Button, onClick = open)
            .semantics(mergeDescendants = true) {
                contentDescription = "Run at"
                stateDescription = runAtDisplay(value)
            }
            .testTag(ScheduleEditorTags.RunAt)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(runAtDisplay(value), color = t.white, style = css(type.ui, if (layout.phone) 16f else 14f, 400, lineHeight = 1.5f), modifier = Modifier.weight(1f))
        Icon(TetherIcons.CalendarClock, contentDescription = null, tint = t.faint, modifier = Modifier.size(16.dp))
    }
}

/** `.schedule-checkbox` (studio.css 770-772): Use an isolated worktree. */
@Composable
private fun WorktreeCheckbox(checked: Boolean, onToggle: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .fillMaxWidth()
            .edge(t.line, top = true)
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                role = Role.Checkbox
                toggleableState = ToggleableState(checked)
            }
            .testTag(ScheduleEditorTags.Worktree)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val box = RoundedCornerShape(3.dp)
        Box(
            Modifier
                .size(16.dp)
                .background(if (checked) t.violet else t.graphite, box)
                .border(1.dp, if (checked) t.violet else t.lineStrong, box),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(TetherIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp)) }
        Column(verticalArrangement = Arrangement.spacedBy(1.92.dp)) {
            Text("Use an isolated worktree", color = t.ink, style = css(type.ui, 13f, 700))
            Text("Run without changing the selected repository checkout.", color = t.faint, style = css(type.ui, 12f, 400, lineHeight = 1.5f))
        }
    }
}
