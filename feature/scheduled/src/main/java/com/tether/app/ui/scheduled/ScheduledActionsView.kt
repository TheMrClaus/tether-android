package com.tether.app.ui.scheduled

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.client.ScheduledAction
import com.tether.app.client.ScheduledActionsState
import com.tether.app.client.ScheduledContinuation
import com.tether.app.protocol.ScheduledActionInput
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.ProviderLogo
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.text.proseText
import com.tether.app.ui.theme.CssLineHeight
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.util.compactPath
import com.tether.app.ui.util.projectName
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.util.Locale

/** Stable hooks for behaviour tests and goldens. */
object ScheduledTags {
    const val Root = "scheduled-actions"
    const val New = "scheduled-new"
    const val Content = "scheduled-content"
    const val Empty = "scheduled-empty"
    const val EmptyCreate = "scheduled-empty-create"
    fun tab(tab: ScheduledTab) = "scheduled-tab-${tab.key}"
    fun row(id: String) = "schedule-row-$id"
    fun continuation(sessionId: String) = "continuation-row-$sessionId"
}

/** The view's three tabs (scheduled-actions-view.tsx:212). */
enum class ScheduledTab(val key: String, val label: String) {
    Schedules("schedules", "Schedules"),
    Completed("completed", "Completed"),
    Continuations("continuations", "Continuations"),
}

/**
 * The view's hosts (scheduled-actions-view.tsx props). Each send is fire-and-forget like the web's;
 * the server answers with a fresh list or an error toast.
 */
data class ScheduledActionsHandlers(
    val onCreate: (ScheduledActionInput) -> Unit = {},
    val onUpdate: (scheduleId: String, ScheduledActionInput) -> Unit = { _, _ -> },
    /** "pause" | "resume" | "run" | "delete". */
    val onControl: (scheduleId: String, action: String) -> Unit = { _, _ -> },
    val onCancelContinuation: (sessionId: String, resetsAt: Long) -> Unit = { _, _ -> },
    val onOpenSession: (sessionId: String) -> Unit = {},
)

/**
 * The view's own state, kept across a rotation and a process death: the tab and the open editor
 * (its fields, cadence, Run at value and error). The armed Delete is transient (it disarms on its
 * own after [ScheduledRules.DELETE_ARM_MS]), as on the web.
 */
@Stable
class ScheduledUiState(tab: ScheduledTab = ScheduledTab.Schedules, editor: ScheduleEditor? = null) {
    var tab by mutableStateOf(tab)
    var editor by mutableStateOf(editor)
    var armedDeleteId by mutableStateOf<String?>(null)

    companion object {
        val Saver: Saver<ScheduledUiState, Any> = androidx.compose.runtime.saveable.listSaver(
            save = { listOf(it.tab.key, it.editor?.let(ScheduleEditor::encode).orEmpty()) },
            restore = { saved ->
                ScheduledUiState(
                    tab = ScheduledTab.entries.firstOrNull { it.key == saved[0] } ?: ScheduledTab.Schedules,
                    editor = (saved[1] as String).takeIf { it.isNotEmpty() }?.let(ScheduleEditor::decode),
                )
            },
        )
    }
}

@Composable
fun rememberScheduledUiState(): ScheduledUiState = rememberSaveable(saver = ScheduledUiState.Saver) { ScheduledUiState() }

/** The web's breakpoints for this view (globals.css ≤ 47.9375rem, studio.css ≤ 900px and ≤ 640px). */
internal data class ScheduledLayout(val viewport: Int) {
    val phone = viewport <= 640
    val mobile = viewport < 768
    val tablet = viewport <= 900
}

internal fun css(family: FontFamily, sizeSp: Float, weight: Int, trackingEm: Float = 0f, lineHeight: Float? = null) = TextStyle(
    fontFamily = family,
    fontSize = sizeSp.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = if (trackingEm == 0f) androidx.compose.ui.unit.TextUnit.Unspecified else trackingEm.em,
    lineHeight = lineHeight?.em ?: androidx.compose.ui.unit.TextUnit.Unspecified,
    lineHeightStyle = CssLineHeight,
)

/** A 1dp rule along one edge (`border-top` / `border-bottom`). */
internal fun Modifier.edge(color: Color, top: Boolean): Modifier = drawBehind {
    val h = 1.dp.toPx()
    drawRect(color, Offset(0f, if (top) 0f else size.height - h), Size(size.width, h))
}

/** CSS `text-transform: capitalize`: every word's first letter upper-cased. */
internal fun capitalizeWords(text: String): String =
    text.split(' ').joinToString(" ") { w -> w.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }

/**
 * T9.3: components/scheduled-actions-view.tsx — the Scheduled destination. A header (title, blurb,
 * New schedule), three tabs (Schedules, Completed, Continuations, each with its count) over the
 * schedule rows (provider, name, status, prompt, cadence, workspace, zone, next run, last run, the
 * newest run's error; Open last session, Run now, Pause/Resume, Edit, the two-tap Delete) or the
 * waiting continuations (Open conversation, Cancel), and the schedule editor.
 *
 * Exactly as capable as the web's: every field, default and action, and no confirmation the web
 * does not ask for (Delete's own two-tap arm is the web's).
 */
@Composable
fun ScheduledActionsView(
    state: ScheduledActionsState,
    providerEntries: List<ProviderCatalogEntry>,
    currentWorkspace: String,
    workspaceRoot: String,
    pinnedProjects: List<String>,
    handlers: ScheduledActionsHandlers,
    modifier: Modifier = Modifier,
    ui: ScheduledUiState = rememberScheduledUiState(),
    now: Long = com.tether.app.ui.components.rememberTickingNow(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    viewportWidth: Int = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value.toInt() },
    /** The web's `Date.now()` at an editor's open and submit. */
    clock: () -> Long = System::currentTimeMillis,
    /** False only for goldens that shoot the editor inline ([ScheduleEditorFrame]). */
    editorInWindow: Boolean = true,
) {
    val t = LocalTetherTokens.current
    val layout = ScheduledLayout(viewportWidth)
    val entries = remember(providerEntries) { ScheduledRules.entries(providerEntries) }
    val pending = remember(state.schedules) { state.schedules.filter { it.status != "completed" } }
    val completed = remember(state.schedules) { state.schedules.filter { it.status == "completed" } }

    // scheduled-actions-view.tsx:321-331: a second tap within 4 s deletes; otherwise it disarms.
    ui.armedDeleteId?.let { armed ->
        LaunchedEffect(armed) {
            delay(ScheduledRules.DELETE_ARM_MS)
            if (ui.armedDeleteId == armed) ui.armedDeleteId = null
        }
    }
    val openCreate = {
        ui.editor = ScheduleEditor.create(entries.firstOrNull(), currentWorkspace, clock(), zone)
    }
    val openEdit = { schedule: ScheduledAction -> ui.editor = ScheduleEditor.edit(schedule, clock(), zone) }
    val armDelete = { id: String ->
        if (ui.armedDeleteId == id) {
            ui.armedDeleteId = null
            handlers.onControl(id, "delete")
        } else {
            ui.armedDeleteId = id
        }
    }

    Column(modifier.fillMaxSize().background(t.mineral).testTag(ScheduledTags.Root)) {
        Header(layout, canCreate = entries.isNotEmpty(), onCreate = openCreate)
        Tabs(layout, ui.tab, counts = mapOf(ScheduledTab.Schedules to pending.size, ScheduledTab.Completed to completed.size, ScheduledTab.Continuations to state.continuations.size)) { ui.tab = it }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = if (layout.phone) 16.dp else if (layout.tablet) 28.dp else 40.dp,
                    end = if (layout.phone) 16.dp else if (layout.tablet) 28.dp else 40.dp,
                    top = if (layout.phone) 20.dp else if (layout.tablet) 24.dp else 28.dp,
                    bottom = if (layout.phone) 32.dp else if (layout.tablet) 24.dp else 40.dp,
                )
                .testTag(ScheduledTags.Content),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (ui.tab) {
                ScheduledTab.Schedules, ScheduledTab.Completed -> {
                    val list = if (ui.tab == ScheduledTab.Schedules) pending else completed
                    if (list.isNotEmpty()) {
                        Column(Modifier.widthIn(max = 1120.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            for (schedule in list) {
                                androidx.compose.runtime.key(schedule.id) {
                                    ScheduleRow(
                                        schedule = schedule,
                                        layout = layout,
                                        workspaceRoot = workspaceRoot,
                                        armed = ui.armedDeleteId == schedule.id,
                                        now = now,
                                        zone = zone,
                                        locale = locale,
                                        onOpenSession = handlers.onOpenSession,
                                        onControl = handlers.onControl,
                                        onEdit = { openEdit(schedule) },
                                        onDelete = { armDelete(schedule.id) },
                                    )
                                }
                            }
                        }
                    } else if (ui.tab == ScheduledTab.Completed) {
                        EmptyState(layout, TetherIcons.CircleCheck, "No completed schedules", "Schedules that finish their run limit move here and drop off the main list.")
                    } else {
                        EmptyState(layout, TetherIcons.CalendarClock, "No recurring schedules", "Create a clean agent run for triage, maintenance, reports, or build checks.") {
                            TetherKey(
                                onClick = openCreate,
                                classes = KeyClasses.ButtonSecondary,
                                label = "Create schedule",
                                enabled = entries.isNotEmpty(),
                                modifier = Modifier.testTag(ScheduledTags.EmptyCreate),
                            )
                        }
                    }
                }
                ScheduledTab.Continuations -> if (state.continuations.isNotEmpty()) {
                    Column(Modifier.widthIn(max = 1120.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (continuation in state.continuations) {
                            androidx.compose.runtime.key(continuation.sessionId) {
                                ContinuationRow(continuation, layout, workspaceRoot, now, zone, locale, handlers)
                            }
                        }
                    }
                } else {
                    EmptyState(layout, TetherIcons.CircleCheck, "No scheduled continuations", "When a conversation is set to resume after a usage limit resets, it will appear here.")
                }
            }
        }
    }

    ui.editor?.let { editor ->
        val close = { ui.editor = null }
        val submit = {
            when (val result = ScheduledRules.submit(editor.form, editor.mode, editor.onceValue, clock(), zone)) {
                is SubmitResult.Error -> ui.editor = editor.copy(error = result.message)
                is SubmitResult.Ok -> {
                    val id = editor.editingId
                    if (id != null) handlers.onUpdate(id, result.input) else handlers.onCreate(result.input)
                    ui.editor = null
                }
            }
        }
        val workspaces = ScheduledRules.workspaceSuggestions(currentWorkspace, pinnedProjects)
        if (editorInWindow) {
            ScheduleEditorDialog(editor, entries, workspaces, viewportWidth, zone, clock, onChange = { ui.editor = it }, onClose = close, onSubmit = submit)
        }
    }
}

@Composable
private fun Header(layout: ScheduledLayout, canCreate: Boolean, onCreate: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val copy = @Composable {
        Text(
            "Scheduled actions",
            color = t.white,
            style = css(type.ui, if (layout.phone) 26f else 30f, 700, trackingEm = -0.03f),
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Start fresh agents on a cadence, or manage conversations waiting for their usage limit to reset.",
            color = t.muted,
            style = css(type.ui, if (layout.phone) 13f else 14f, 400, lineHeight = 1.65f),
            // `max-width: 58ch` (the 0 of 14px Manrope is ~8.3px).
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 480.dp),
        )
    }
    val newKey = @Composable {
        TetherKey(
            onClick = onCreate,
            classes = KeyClasses.ButtonPrimary,
            label = "New schedule",
            icon = TetherIcons.Plus,
            iconSize = 16.dp,
            enabled = canCreate,
            modifier = Modifier.testTag(ScheduledTags.New),
        )
    }
    val padding = when {
        layout.phone -> Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
        layout.tablet -> Modifier.padding(28.dp)
        else -> Modifier.padding(start = 40.dp, end = 40.dp, top = 36.dp, bottom = 28.dp)
    }
    if (layout.phone) {
        // `flex-wrap: wrap; gap: 20px`: the long blurb pushes the key onto its own line.
        Column(Modifier.fillMaxWidth().background(t.graphite).then(padding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column { copy() }
            newKey()
        }
    } else {
        Row(
            Modifier.fillMaxWidth().background(t.graphite).then(padding),
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceXl),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) { copy() }
            newKey()
        }
    }
}

@Composable
private fun Tabs(layout: ScheduledLayout, current: ScheduledTab, counts: Map<ScheduledTab, Int>, onSelect: (ScheduledTab) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val inline = if (layout.phone) 20.dp else if (layout.tablet) 28.dp else 40.dp
    Row(
        Modifier
            .fillMaxWidth()
            .background(t.graphite)
            .edge(t.line, top = false)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = inline)
            .semantics { contentDescription = "Scheduled action types" },
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        for (tab in ScheduledTab.entries) {
            val active = tab == current
            val color = if (active) t.violetStrong else t.muted
            Row(
                Modifier
                    .heightIn(min = 52.dp)
                    .then(if (active) Modifier.drawBehind { drawRect(t.violetStrong, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx())) } else Modifier)
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .semantics { selected = active }
                    .padding(horizontal = 2.dp)
                    .testTag(ScheduledTags.tab(tab)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            ) {
                Text(tab.label, color = color, style = css(type.ui, 13f, 650))
                Box(Modifier.background(t.mineral, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
                    Text("${counts[tab] ?: 0}", color = t.faint, style = css(type.ui, 11f, 650, lineHeight = 1f))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(layout: ScheduledLayout, icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(
        Modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .heightIn(min = if (layout.phone) 320.dp else 360.dp)
            .then(if (layout.phone) Modifier.padding(horizontal = 12.dp, vertical = 24.dp) else Modifier)
            .testTag(ScheduledTags.Empty),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = t.violetStrong, modifier = Modifier.size(30.dp))
        Text(
            title,
            color = t.white,
            style = css(type.ui, 20f, 700, trackingEm = -0.02f),
            modifier = Modifier.padding(top = 20.dp).semantics { heading() },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            body,
            color = t.muted,
            style = css(type.ui, 14f, 400, lineHeight = 1.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 416.dp).padding(top = 10.dp, bottom = 24.dp),
        )
        action?.invoke()
    }
}

/** `.schedule-provider-logo` (studio.css 737-738): 36dp, 9dp corners, the mineral well. */
@Composable
private fun ProviderBadge(provider: String) {
    val t = LocalTetherTokens.current
    Box(Modifier.size(36.dp).background(t.mineral, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
        ProviderLogo(provider, color = t.ink, markSize = 20.dp)
    }
}

/** `.schedule-status` (globals.css 9793-9812, studio.css 741-744): the word is the status, never the colour alone. */
@Composable
internal fun StatusPill(kind: String, word: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val (fg, bg) = when (kind) {
        "running", "active" -> t.running to t.running.copy(alpha = 0.10f)
        "paused" -> t.muted to t.mineral
        "completed" -> t.css.active to t.mineral
        "waiting" -> t.violetDeep to t.violetWash
        else -> t.faint to t.mineral
    }
    Box(
        Modifier.heightIn(min = 24.dp).background(bg, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(capitalizeWords(word), color = fg, style = css(type.ui, 11f, 650))
    }
}

/** `.schedule-action` (globals.css 9877-9895, studio.css 753): the row's flat keys. */
@Composable
internal fun RowAction(
    label: String,
    onClick: () -> Unit,
    layout: ScheduledLayout,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    armed: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(7.dp)
    val bg = when {
        armed -> t.brickWash
        pressed && enabled -> t.graphiteRaised
        else -> Color.Transparent
    }
    val fg = when {
        armed -> t.danger
        pressed && enabled -> t.white
        else -> t.muted
    }
    Row(
        modifier
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .background(bg, shape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (layout.phone) 10.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Text(label, color = fg, style = css(type.ui, 12f, 650), maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleRow(
    schedule: ScheduledAction,
    layout: ScheduledLayout,
    workspaceRoot: String,
    armed: Boolean,
    now: Long,
    zone: ZoneId,
    locale: Locale,
    onOpenSession: (String) -> Unit,
    onControl: (String, String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val latestRun = schedule.runs.lastOrNull()
    val isRunning = latestRun?.status == "running"
    val isCompleted = schedule.status == "completed"
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(t.graphite, shape)
            .border(1.dp, t.line, shape)
            .testTag(ScheduledTags.row(schedule.id)),
    ) {
        val next = @Composable { stacked: Boolean ->
            Column(
                Modifier.then(if (stacked) Modifier.padding(top = 12.dp) else Modifier.padding(start = 16.dp).widthIn(min = 144.dp)),
                horizontalAlignment = if (stacked) Alignment.Start else Alignment.End,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(ScheduledRules.nextCaption(schedule), color = t.faint, style = css(type.ui, 11f, 700))
                Text(
                    ScheduledRules.nextValue(schedule, now, zone, locale),
                    color = t.white,
                    style = css(type.ui, 13f, 680).copy(fontFeatureSettings = "tnum"),
                )
                Text(
                    ScheduledRules.runSummary(schedule, now, zone, locale),
                    color = if (latestRun?.status == "failed") t.danger else t.faint,
                    style = css(type.ui, 11f, 400),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (layout.phone) 16.dp else 24.dp, vertical = if (layout.phone) 20.dp else 24.dp),
            horizontalArrangement = Arrangement.spacedBy(if (layout.phone) 12.dp else 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ProviderBadge(schedule.provider)
            Column(Modifier.weight(1f)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(
                        SafeText.line(schedule.name),
                        color = t.white,
                        style = css(type.ui, if (layout.phone) 15f else 16f, 680, trackingEm = -0.01f, lineHeight = 1.4f),
                        modifier = Modifier.semantics { heading() },
                    )
                    StatusPill(if (isRunning) "running" else schedule.status, ScheduledRules.statusWord(schedule))
                }
                Text(
                    proseText(schedule.prompt),
                    color = t.muted,
                    style = css(type.ui, 13f, 400, lineHeight = 1.65f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val meta = css(type.ui, if (layout.phone) 11f else 12f, 550, lineHeight = 1.4f)
                FlowRow(
                    Modifier.padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.8.dp)) {
                        Icon(TetherIcons.Clock3, contentDescription = null, tint = t.faint, modifier = Modifier.size(13.dp))
                        Text(ScheduledRules.cadenceLabel(schedule, zone, locale), color = t.faint, style = meta)
                    }
                    Text(codeLabel(compactPath(schedule.cwd, workspaceRoot)), color = t.faint, style = meta)
                    Text(SafeText.line(schedule.timeZone), color = t.faint, style = meta)
                    // Issue #193: a DeepSeek-API run that will fire in a peak window.
                    if (ScheduledRules.deepSeekPeakHint(schedule)) {
                        Text(ScheduledRules.DEEPSEEK_HINT, color = t.warning, style = meta)
                    }
                }
                if (layout.tablet) next(true)
            }
            if (!layout.tablet) next(false)
        }
        latestRun?.error?.let { error ->
            Text(
                proseText(error),
                color = t.danger,
                style = css(type.mono, 12f, 400, lineHeight = 1.45f),
                modifier = Modifier
                    .padding(start = 24.dp, end = 24.dp, bottom = 20.dp)
                    .fillMaxWidth()
                    .background(t.brickWash, RoundedCornerShape(8.dp))
                    .padding(14.dp),
            )
        }
        FlowRow(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .edge(t.line, top = true)
                .padding(horizontal = if (layout.phone) 8.dp else 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, if (layout.mobile) Alignment.Start else Alignment.End),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ≤ 47.9375rem: `.schedule-action { flex: 1 1 auto }`.
            val grow = if (layout.mobile) Modifier.weight(1f, fill = true) else Modifier
            latestRun?.sessionId?.let { sessionId ->
                RowAction("Open last session", { onOpenSession(sessionId) }, layout, grow)
            }
            if (!isCompleted) {
                RowAction("Run now", { onControl(schedule.id, "run") }, layout, grow, icon = TetherIcons.Play, enabled = !isRunning)
                val paused = schedule.status == "paused"
                RowAction(
                    if (paused) "Resume" else "Pause",
                    { onControl(schedule.id, if (paused) "resume" else "pause") },
                    layout,
                    grow,
                    icon = if (paused) TetherIcons.RotateCcw else TetherIcons.Pause,
                )
            }
            RowAction("Edit", onEdit, layout, grow, icon = TetherIcons.Pencil)
            RowAction(if (armed) "Confirm delete" else "Delete", onDelete, layout, grow, icon = TetherIcons.Trash2, armed = armed)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContinuationRow(
    continuation: ScheduledContinuation,
    layout: ScheduledLayout,
    workspaceRoot: String,
    now: Long,
    zone: ZoneId,
    locale: Locale,
    handlers: ScheduledActionsHandlers,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(12.dp)
    val actions = @Composable {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalArrangement = Arrangement.spacedBy(0.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            RowAction("Open conversation", { handlers.onOpenSession(continuation.sessionId) }, layout)
            RowAction("Cancel", { handlers.onCancelContinuation(continuation.sessionId, continuation.resetsAt) }, layout, icon = TetherIcons.X)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(t.graphite, shape)
            .border(1.dp, t.line, shape)
            .padding(horizontal = if (layout.phone) 16.dp else 24.dp, vertical = if (layout.phone) 20.dp else 24.dp)
            .testTag(ScheduledTags.continuation(continuation.sessionId)),
        horizontalArrangement = Arrangement.spacedBy(if (layout.phone) 12.dp else 16.dp),
        verticalAlignment = if (layout.tablet) Alignment.Top else Alignment.CenterVertically,
    ) {
        ProviderBadge(continuation.provider)
        Column(Modifier.weight(1f)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(
                    SafeText.line(continuation.sessionName),
                    color = t.white,
                    style = css(type.ui, if (layout.phone) 15f else 16f, 680, trackingEm = -0.01f, lineHeight = 1.4f),
                    modifier = Modifier.semantics { heading() },
                )
                StatusPill("waiting", "Waiting for limit reset")
            }
            Text(
                "Continues this conversation automatically ${ScheduledRules.timeLabel(continuation.resumeAt, now, zone, locale)}.",
                color = t.muted,
                style = css(type.ui, 13f, 400, lineHeight = 1.6f),
                modifier = Modifier.padding(top = 6.72.dp),
            )
            val meta = css(type.ui, if (layout.phone) 11f else 12f, 550, lineHeight = 1.4f)
            FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(codeLabel(projectName(continuation.cwd).ifEmpty { compactPath(continuation.cwd, workspaceRoot) }), color = t.faint, style = meta)
                Text("Limit resets ${ScheduledRules.timeLabel(continuation.resetsAt, now, zone, locale)}", color = t.faint, style = meta)
            }
            if (layout.tablet) Box(Modifier.padding(top = 12.dp)) { actions() }
        }
        if (!layout.tablet) actions()
    }
}

/** A form error is read out at once (`role="alert"`). */
internal fun Modifier.alertRegion(): Modifier = semantics { liveRegion = LiveRegionMode.Assertive }
