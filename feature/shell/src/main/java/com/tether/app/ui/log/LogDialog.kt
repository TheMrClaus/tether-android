package com.tether.app.ui.log

import androidx.compose.foundation.background
import com.tether.app.client.LabelText
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.text.proseText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.tether.app.client.ServerStats
import com.tether.app.protocol.LogEntry
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.PerfDivider
import com.tether.app.ui.components.StudioDialog
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherSeam
import com.tether.app.ui.components.TetherSelect
import com.tether.app.ui.components.TetherSelectOption
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.components.hardShadow
import com.tether.app.ui.components.oklabMix
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.shell.ChromeIconKey
import com.tether.app.ui.shell.cssText
import com.tether.app.ui.shell.rememberDialogIn
import com.tether.app.ui.shell.rememberIconLook
import com.tether.app.ui.shell.studio
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.tabularNums
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max

/*
 * The Health & Event Log dialog: components/log-dialog.tsx (tether @ PARITY_BASE 7d65611), a
 * `<dialog className="settings-dialog recycle-dialog log-dialog">`. Chrome from the settings
 * dialog as the premium wash finishes it (globals.css 3308-3327, 11593-11606, 9255-9281), the
 * recycle body + stat tiles (4546-4617), the log meta grid, controls and rows (4619-4657, 4737),
 * the material-layer filter keys and select (8946-8957, 9144-9160), and Studio's secondary
 * surfaces (studio.css 504-547, 792-832, 954-966, 1017-1024). The ≤640px rules are the narrow
 * layout here, chosen by the window width in dp.
 */

/** `@media (max-width: 640px)`: the dialog's narrow layout (phones). */
private val NarrowMaxWidth = 640.dp

internal object LogDialogTags {
    const val Dialog = "log-dialog"
    const val List = "log-list"
    const val Row = "log-row"
    const val Empty = "log-empty"
    const val StatsError = "log-stats-error"
    const val Stats = "log-stats"
    const val Meta = "log-meta"
}

/**
 * The modal: the skin's scrim, then the case rising in (`dialog-in`). Like the web `<dialog>`,
 * a tap on the backdrop does nothing; Back (the web's Esc), Close and Done dismiss it.
 */
@Composable
fun LogDialog(
    entries: List<LogEntry>,
    sessions: List<AgentSession>,
    state: LogDialogState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        val progress = rememberDialogIn()
        LogDialogFrame(
            entries = entries,
            sessions = sessions,
            state = state,
            onRefresh = onRefresh,
            onClose = onDismiss,
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

/** The scrim-filled window with the dialog centred in it (the inline form the goldens shoot). */
@Composable
fun LogDialogFrame(
    entries: List<LogEntry>,
    sessions: List<AgentSession>,
    state: LogDialogState,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    surfaceModifier: Modifier = Modifier,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val t = LocalTetherTokens.current
    BoxWithConstraints(modifier.fillMaxSize().background(dialogScrim(t)), contentAlignment = Alignment.Center) {
        val narrow = maxWidth <= NarrowMaxWidth
        val studio = t.studio
        val width = when {
            studio && narrow -> maxWidth - 24.dp
            studio -> minOf(980.dp, maxWidth - 48.dp)
            // The UA sheet's `dialog { max-width: calc(100% - 6px - 2em) }` still caps the instrument case.
            else -> minOf(928.dp, maxWidth - 24.dp, maxWidth - 38.dp)
        }
        val maxHeight = maxHeight - when {
            studio && narrow -> 32.dp
            studio -> 48.dp
            else -> 24.dp
        }
        val bodyMin = minOf(if (studio) 460.dp else 400.dp, this.maxHeight * 0.5f)
        val shape = RoundedCornerShape(if (studio) (if (narrow) 14.dp else StudioDialog.radius) else t.radiusLg)
        Column(
            surfaceModifier
                .testTag(LogDialogTags.Dialog)
                .width(width)
                .heightIn(max = maxHeight)
                .cssSurface(
                    shape, t.graphite,
                    if (studio) null else CssBorder(1.dp, t.keySide),
                    if (studio) StudioDialog.shadows else listOf(hardShadow(1.dp, t.litStrong, inset = true)) + t.css.shadowModal,
                )
                .clip(shape),
        ) {
            LogHeader(narrow, onRefresh, onClose)
            LogBody(
                entries = entries,
                sessions = sessions,
                state = state,
                narrow = narrow,
                locale = locale,
                zone = zone,
                modifier = Modifier.weight(1f, fill = false).heightIn(min = bodyMin),
            )
            LogFooter(narrow, onClose)
        }
    }
}

@Composable
private fun LogHeader(narrow: Boolean, onRefresh: () -> Unit, onClose: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val studio = t.studio
    Box {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (studio) Modifier.heightIn(min = if (narrow) 76.dp else 88.dp) else Modifier)
                    .padding(
                        horizontal = if (studio) (if (narrow) 20.dp else 28.dp) else t.css.spaceXl,
                        vertical = if (studio) (if (narrow) 18.dp else 22.dp) else t.css.spaceLg,
                    ),
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceLg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    if (!studio) {
                        Text(
                            type.sectionLabel.format("Tether · health & events"),
                            color = t.faint,
                            style = type.sectionLabel.style,
                            modifier = Modifier.padding(bottom = t.css.spaceSm),
                        )
                    }
                    Text(
                        "Health & Event Log",
                        color = t.white,
                        style = if (studio) {
                            cssText(type.ui, if (narrow) 1.25f else 1.375f, 700, trackingEm = -0.025f, lineHeight = 1.3f)
                        } else {
                            cssText(type.ui, 1.3f, 700, trackingEm = -0.025f)
                        },
                        modifier = Modifier.semantics { heading() },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm), verticalAlignment = Alignment.CenterVertically) {
                    val look = rememberIconLook(t.ink, t.radiusSm)
                    ChromeIconKey(onRefresh, TetherIcons.RefreshCw, "Refresh stats", look, 44.dp, 44.dp, 16.dp)
                    ChromeIconKey(onClose, TetherIcons.X, "Close", look, 44.dp, 44.dp, 19.dp)
                }
            }
            TetherSeam()
        }
        // `.log-dialog > header::after`: the perforation over the header's bottom edge.
        PerfDivider(Modifier.align(Alignment.BottomStart).offset(y = (-1).dp))
    }
}

@Composable
private fun LogFooter(narrow: Boolean, onClose: () -> Unit) {
    val t = LocalTetherTokens.current
    val studio = t.studio
    // Footer edge: `border-top` then the lit lip inside it (`inset 0 1px 0 var(--seam-lip)`).
    TetherSeam()
    Row(
        Modifier
            .fillMaxWidth()
            .background(t.graphite)
            .padding(
                horizontal = if (studio) (if (narrow) 20.dp else 28.dp) else t.css.spaceXl,
                vertical = if (studio) (if (narrow) 16.dp else 18.dp) else t.css.spaceMd,
            ),
        horizontalArrangement = Arrangement.spacedBy(if (studio) 10.dp else t.css.spaceLg, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TetherKey(onClick = onClose, classes = KeyClasses.ButtonSecondary, label = "Done")
    }
}

@Composable
private fun LogBody(
    entries: List<LogEntry>,
    sessions: List<AgentSession>,
    state: LogDialogState,
    narrow: Boolean,
    locale: Locale,
    zone: ZoneId,
    modifier: Modifier,
) {
    val t = LocalTetherTokens.current
    val studio = t.studio
    val names = remember(sessions) { LogReadings.namesById(sessions) }
    val logged = remember(entries, names) { LogReadings.loggedSessions(entries, names) }
    val rows = remember(entries, state.level, state.session) { LogReadings.filtered(entries, state.level, state.session) }
    val warnCount = remember(entries) { LogReadings.warnCount(entries) }
    val stats = state.stats
    val gap = when {
        studio && narrow -> 28.dp
        studio -> 28.dp
        narrow -> t.css.spaceLg
        else -> t.css.spaceXl
    }
    val padding = when {
        studio && narrow -> PaddingValues(horizontal = 20.dp, vertical = 24.dp)
        studio -> PaddingValues(28.dp)
        narrow -> PaddingValues(t.css.spaceLg)
        else -> PaddingValues(t.css.spaceXl)
    }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = padding) {
        var first = true
        fun Modifier.sectionGap(): Modifier = if (first) { first = false; this } else padding(top = gap)
        if (state.statsError.isNotEmpty()) {
            val m = Modifier.sectionGap()
            item(key = "stats-error") { EmptyNote(state.statsError, m.testTag(LogDialogTags.StatsError)) }
        }
        if (stats != null) {
            val tiles = Modifier.sectionGap()
            val meta = Modifier.sectionGap()
            item(key = "stats") { StatTiles(stats, warnCount, narrow, tiles.testTag(LogDialogTags.Stats)) }
            item(key = "meta") { MetaGrid(stats, narrow, meta.testTag(LogDialogTags.Meta)) }
        }
        val controls = Modifier.sectionGap()
        item(key = "controls") { LogControls(state, logged, controls) }
        if (rows.isEmpty()) {
            item(key = "empty") { EmptyNote(LogReadings.EmptyText, Modifier.padding(top = gap).testTag(LogDialogTags.Empty)) }
        } else {
            items(rows.size, key = { rows[it].seq }) { index ->
                LogRow(
                    entry = rows[index],
                    names = names,
                    first = index == 0,
                    last = index == rows.lastIndex,
                    narrow = narrow,
                    locale = locale,
                    zone = zone,
                    modifier = if (index == 0) Modifier.padding(top = gap) else Modifier,
                )
            }
        }
    }
}

/** `.recycle-empty`: a centred faint note (the empty log, a stats error). */
@Composable
private fun EmptyNote(text: String, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        // T9.1: a stats error may carry the server's words (prose rule).
        proseText(text),
        color = t.faint,
        textAlign = TextAlign.Center,
        style = if (t.studio) cssText(type.ui, 0.8125f, 400, lineHeight = 1.65f) else cssText(type.ui, 0.74f, 400),
        modifier = modifier.fillMaxWidth().padding(if (t.studio) 28.dp else t.css.spaceLg),
    )
}

private enum class TileTone { Alive, Cooling, Stuck }

private data class Tile(val icon: ImageVector, val value: String, val caption: String, val tone: TileTone, val alarm: Boolean = false)

/** `.recycle-stats`: four tiles, two per row when narrow. */
@Composable
private fun StatTiles(stats: ServerStats, warnCount: Int, narrow: Boolean, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val studio = t.studio
    val tiles = listOf(
        Tile(TetherIcons.Clock, LogReadings.uptime(stats.uptimeMs), "Uptime · pid ${stats.pid}", TileTone.Alive),
        Tile(TetherIcons.Activity, "${stats.runtime?.activeTurns ?: 0}", LogReadings.activeTurnsCaption(stats), TileTone.Cooling),
        Tile(TetherIcons.ServerCog, "${stats.runtime?.warm ?: 0}", "Warm sessions", TileTone.Alive),
        Tile(TetherIcons.TriangleAlert, "$warnCount", "Warnings logged", TileTone.Stuck, alarm = warnCount > 0),
    )
    val perRow = if (narrow) 2 else 4
    val hGap = if (studio) 0.dp else t.css.spaceMd
    val vGap = if (studio) 24.dp else t.css.spaceMd
    Column(
        modifier
            .fillMaxWidth()
            .then(if (studio) Modifier.drawBottomRule(t.line).padding(bottom = 24.dp + 1.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(vGap),
    ) {
        tiles.chunked(perRow).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(hGap)) {
                row.forEachIndexed { column, tile ->
                    val index = rowIndex * perRow + column
                    StatTile(
                        tile,
                        firstInRow = column == 0,
                        lastInRow = column == row.lastIndex || index == tiles.lastIndex,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(tile: Tile, firstInRow: Boolean, lastInRow: Boolean, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val studio = t.studio
    val iconTint = when {
        tile.alarm -> t.danger
        tile.tone == TileTone.Alive -> t.running
        tile.tone == TileTone.Cooling -> t.violet
        else -> t.muted
    }
    val shape = RoundedCornerShape(t.radiusMd)
    val surface = when {
        studio -> Modifier
            .then(if (lastInRow) Modifier else Modifier.drawEndRule(t.line))
            .padding(start = if (firstInRow) 0.dp else 20.dp, end = 20.dp + if (lastInRow) 0.dp else 1.dp)
        else -> Modifier
            .cssSurface(
                shape,
                if (tile.alarm) oklabMix(t.danger, t.graphiteRaised, 0.12f) else t.graphiteRaised,
                CssBorder(1.dp, if (tile.alarm) oklabMix(t.danger, t.line, 0.45f) else t.line),
                emptyList(),
            )
            .padding(t.css.spaceLg)
    }
    Column(
        modifier
            .then(surface)
            .clearAndSetSemantics { contentDescription = "${tile.caption}: ${tile.value}" },
        verticalArrangement = Arrangement.spacedBy(if (studio) 8.dp else 2.4.dp),
    ) {
        if (studio) {
            Icon(tile.icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        } else {
            Box(
                Modifier.padding(bottom = 5.6.dp).size(32.dp).background(t.slate, RoundedCornerShape(t.radiusSm)),
                contentAlignment = Alignment.Center,
            ) { Icon(tile.icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp)) }
        }
        Text(
            tile.value,
            color = if (tile.alarm) t.danger else t.white,
            style = if (studio) cssText(type.ui, 1.75f, 680, trackingEm = -0.02f) else cssText(type.ui, 1.6f, 680, trackingEm = -0.02f),
            // `line-height: 1` (Studio 1.25) is tighter than the face's natural line, which Compose never shrinks to.
            modifier = Modifier.cssLineBox(if (studio) (28 * 1.25).sp else 25.6.sp),
        )
        Text(
            if (studio) tile.caption else tile.caption.uppercase(Locale.ROOT),
            color = if (studio) t.muted else t.faint,
            style = if (studio) cssText(type.ui, 0.75f, 400) else cssText(type.ui, 0.66f, 400, trackingEm = 0.05f),
        )
    }
}

/** `.log-meta`: key/value lines, two columns (one when narrow). */
@Composable
private fun MetaGrid(stats: ServerStats, narrow: Boolean, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val studio = t.studio
    val lines = listOf(
        Triple("Engine", null, LogReadings.engine(stats)),
        Triple("Sessions", null, LogReadings.sessions(stats)),
        Triple("Memory", TetherIcons.Cpu, LogReadings.memory(stats)),
        Triple("Connected clients", null, "${stats.clients}"),
        Triple("Protocol", null, "v${stats.protocolVersion}"),
    )
    val columns = if (narrow) 1 else 2
    val rowGap = if (studio) (if (narrow) 0.dp else 12.dp) else t.css.spaceSm
    val columnGap = if (studio) 28.dp else t.css.spaceLg
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(rowGap)) {
        lines.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(columnGap)) {
                row.forEach { (label, icon, value) -> MetaLine(label, icon, value, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MetaLine(label: String, icon: ImageVector?, value: String, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val studio = t.studio
    val labelStyle = if (studio) cssText(type.ui, 0.75f, 400) else cssText(type.ui, 0.66f, 400, trackingEm = 0.05f)
    Row(
        modifier
            .drawBottomRule(t.line)
            .padding(top = if (studio) 10.dp else 5.6.dp, bottom = (if (studio) 10.dp else 5.6.dp) + 1.dp)
            .clearAndSetSemantics { contentDescription = "$label: $value" },
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd),
    ) {
        Row(Modifier.alignByBaseline(), horizontalArrangement = Arrangement.spacedBy(4.8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription = null, tint = t.faint, modifier = Modifier.size(13.dp))
            Text(if (studio) label else label.uppercase(Locale.ROOT), color = t.faint, style = labelStyle)
        }
        Text(
            value,
            color = t.white,
            textAlign = TextAlign.End,
            style = if (studio) cssText(type.ui, 0.8125f, 600) else cssText(type.ui, 0.78f, 600),
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
    }
}

/** `.log-controls`: the level filter keys and, when the log names sessions, the session select. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogControls(state: LogDialogState, logged: List<LoggedSession>, modifier: Modifier) {
    val t = LocalTetherTokens.current
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(t.css.spaceMd),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        LevelFilterGroup(state.level) { state.level = it }
        if (logged.isNotEmpty()) {
            val options = remember(logged) {
                listOf(TetherSelectOption(AllSessions, "All sessions")) + logged.map { TetherSelectOption(it.id, it.name) }
            }
            TetherSelect(
                options = options,
                selectedValue = state.session,
                onSelect = { state.session = it.value },
                placeholder = "All sessions",
                contentDescription = "Filter by session",
                modifier = Modifier.padding(start = t.css.spaceMd).widthIn(max = 224.dp),
            )
        }
    }
}

/**
 * `.log-filter-group`: two keys in one recessed strip. Instrument: a `--key-face-deep` well in a
 * `--line-strong` edge, raised `--key-face` keys, the active one sunk (`--bevel-pressed`, white).
 * Studio: a `--mineral` tray with 38px keys, the active one a `--graphite` pill in violet. The keys
 * draw at the web's size; Compose widens their hit area to the 48dp minimum touch target.
 */
@Composable
private fun LevelFilterGroup(level: LogLevelFilter, onChange: (LogLevelFilter) -> Unit) {
    val t = LocalTetherTokens.current
    val studio = t.studio
    val shape = RoundedCornerShape(if (studio) 8.dp else t.radiusSm)
    Row(
        Modifier
            .semantics { contentDescription = "Level filter" }
            .then(
                if (studio) {
                    Modifier.background(t.mineral, shape).padding(3.dp)
                } else {
                    Modifier.cssSurface(shape, t.keyFaceDeep, CssBorder(1.dp, t.lineStrong), t.css.well)
                },
            )
            .clip(shape),
        horizontalArrangement = Arrangement.spacedBy(if (studio) 3.dp else 0.dp),
    ) {
        FilterKey("All", level == LogLevelFilter.All) { onChange(LogLevelFilter.All) }
        FilterKey("Warnings", level == LogLevelFilter.Warnings) { onChange(LogLevelFilter.Warnings) }
    }
}

@Composable
private fun FilterKey(label: String, active: Boolean, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val studio = t.studio
    val surface = if (studio) {
        val shape = RoundedCornerShape(6.dp)
        Modifier
            .heightIn(min = 38.dp)
            .then(if (active) Modifier.background(t.graphite, shape) else Modifier)
            .padding(horizontal = 12.dp)
    } else {
        Modifier
            .cssSurface(
                RoundedCornerShape(0.dp),
                if (active) t.keyFaceDeep else t.keyFace,
                null,
                if (active) t.css.bevelPressed else listOf(hardShadow(1.dp, t.litStrong, inset = true)),
            )
            .padding(horizontal = 11.2.dp, vertical = 4.8.dp)
    }
    Box(
        Modifier
            .semantics {
                role = Role.Button
                selected = active
            }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .then(surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                active && studio -> t.violetStrong
                active -> t.white
                else -> t.muted
            },
            style = if (studio) cssText(type.ui, 0.75f, 600) else cssText(type.ui, 0.7f, 600),
        )
    }
}

/** `.log-row`: time, level, then the label with its session, turn and detail. */
@Composable
private fun LogRow(
    entry: LogEntry,
    names: Map<String, String>,
    first: Boolean,
    last: Boolean,
    narrow: Boolean,
    locale: Locale,
    zone: ZoneId,
    modifier: Modifier,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val studio = t.studio
    val level = entry.level ?: ""
    val time = LogReadings.clockTime(entry.ts, locale, zone)
    val label = LogReadings.label(entry)
    val session = entry.sid?.takeIf { it.isNotEmpty() }?.let { LogReadings.sessionName(it, names) }
    val turn = entry.turnId?.takeIf { it.isNotEmpty() }?.let { "turn ${LogReadings.shortId(it)}" }
    val detail = LogReadings.detail(entry)
    val tint = when (level) {
        "warn" -> t.warning.copy(alpha = t.warning.alpha * 0.08f)
        "error" -> t.danger.copy(alpha = t.danger.alpha * 0.10f)
        else -> Color.Transparent
    }
    val levelInk = when (level) {
        "info" -> t.muted
        "warn" -> t.warning
        "error" -> t.danger
        else -> t.ink
    }
    val small = if (studio) 0.75f else 0.7f
    val timeView: @Composable (Modifier) -> Unit = { m ->
        Text(time, color = t.faint, style = cssText(type.ui, if (studio) 0.75f else 0.68f, 400).tabularNums(), modifier = m)
    }
    val levelView: @Composable (Modifier) -> Unit = { m ->
        Text(
            LabelText.label(level).uppercase(Locale.ROOT),
            color = levelInk,
            style = cssText(type.ui, if (studio) 0.625f else 0.6f, 700, trackingEm = if (studio) 0f else 0.05f),
            maxLines = 1,
            // `width: 3rem` scales with the font size; Studio's 48px does not.
            modifier = m.width(if (studio) 48.dp else with(LocalDensity.current) { 48.sp.toDp() }),
        )
    }
    val body: @Composable (Modifier) -> Unit = { m ->
        BaselineFlow(hGap = 8.dp, vGap = 8.dp, modifier = m) {
            Text(label, color = t.white, style = cssText(type.ui, if (studio) 0.8125f else 0.74f, 600))
            if (session != null) Text(session, color = t.muted, style = cssText(type.ui, small, 400))
            // T9.1: the turn id by the one-line rule, the server's outcome / reason / message by the prose rule.
            if (turn != null) Text(codeLabel(turn), color = t.faint, style = cssText(type.ui, small, 400).tabularNums())
            if (detail.isNotEmpty()) Text(proseText(detail), color = t.muted, style = cssText(type.ui, small, 400))
        }
    }
    val rowPadding = if (studio) PaddingValues(vertical = 12.dp) else PaddingValues(horizontal = t.css.spaceMd, vertical = 6.4.dp)
    val radius = if (studio) 0.dp else t.radiusMd
    Box(
        modifier
            .fillMaxWidth()
            .logRowEdges(t, first, last, radius, framed = !studio, tint)
            .padding(rowPadding)
            .padding(bottom = if (last) 0.dp else 1.dp)
            .clearAndSetSemantics {
                testTag = LogDialogTags.Row
                contentDescription = listOfNotNull(time, level, label, session, turn, detail.takeIf { it.isNotEmpty() }).joinToString(", ")
            },
    ) {
        if (studio && narrow) {
            // studio.css 1023-1024: the row wraps, the body on its own line.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    timeView(Modifier.alignByBaseline())
                    levelView(Modifier.alignByBaseline())
                }
                body(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd)) {
                timeView(Modifier.alignByBaseline())
                levelView(Modifier.alignByBaseline())
                body(Modifier.weight(1f).alignByBaseline())
            }
        }
    }
}

/**
 * One row's share of the list frame. Instrument: the list is a `1px var(--line)` box with
 * `--radius-md` corners that clips the row tints, and every row but the last has a `--line`
 * bottom rule. Studio: no frame, only the rules.
 */
private fun Modifier.logRowEdges(t: TetherTokens, first: Boolean, last: Boolean, radius: Dp, framed: Boolean, tint: Color): Modifier =
    drawBehind {
        val px = 1.dp.toPx()
        val r = radius.toPx()
        // The frame as one rounded rect, extended past the edges this row does not own, then clipped to the row.
        val top = if (first || !framed) 0f else -(r + px)
        val bottom = if (last || !framed) size.height else size.height + r + px
        clipRect {
            if (tint.alpha > 0f) {
                drawRoundRect(tint, Offset(0f, top), Size(size.width, bottom - top), CornerRadius(r))
            }
            if (framed) {
                drawRoundRect(t.line, Offset(px / 2, top + px / 2), Size(size.width - px, bottom - top - px), CornerRadius(max(0f, r - px / 2)), style = Stroke(px))
            }
            if (!last) drawRect(t.line, Offset(0f, size.height - px), Size(size.width, px))
        }
    }

/**
 * A CSS line box of [height] around one line of text: the line is centred in it (half-leading,
 * negative when [height] is below the face's natural line) and the box takes exactly [height].
 * Compose's own `lineHeight` never goes below the natural line, so a `line-height: 1` needs this.
 */
private fun Modifier.cssLineBox(height: TextUnit): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val box = height.roundToPx()
    layout(placeable.width, box) { placeable.place(0, (box - placeable.height) / 2) }
}

/** A 1px `--line` rule along the bottom edge (a `border-bottom`). */
private fun Modifier.drawBottomRule(color: Color): Modifier = drawBehind {
    val px = 1.dp.toPx()
    drawRect(color, Offset(0f, size.height - px), Size(size.width, px))
}

/** A 1px `--line` rule along the end edge (a `border-right`). */
private fun Modifier.drawEndRule(color: Color): Modifier = drawBehind {
    val px = 1.dp.toPx()
    drawRect(color, Offset(size.width - px, 0f), Size(px, size.height))
}

/**
 * `.log-body`: `display: flex; flex-wrap: wrap; align-items: baseline; gap`. Items flow onto
 * lines, each line baseline-aligned, and the whole reports its first line's baseline so the row
 * can align time and level to it.
 */
@Composable
private fun BaselineFlow(hGap: Dp, vGap: Dp, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val h = hGap.roundToPx()
        val v = vGap.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        data class Line(val items: List<Int>, val baseline: Int, val height: Int)
        val lines = mutableListOf<Line>()
        var current = mutableListOf<Int>()
        var width = 0
        fun close() {
            if (current.isEmpty()) return
            val above = current.maxOf { i -> placeables[i].baselineOr() }
            val below = current.maxOf { i -> placeables[i].height - placeables[i].baselineOr() }
            lines += Line(current, above, above + below)
            current = mutableListOf()
            width = 0
        }
        placeables.forEachIndexed { i, p ->
            val needed = if (current.isEmpty()) p.width else width + h + p.width
            if (current.isNotEmpty() && needed > constraints.maxWidth) close()
            width = if (current.isEmpty()) p.width else width + h + p.width
            current += i
        }
        close()
        val totalHeight = lines.sumOf { it.height } + v * max(0, lines.size - 1)
        val layoutWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else lines.maxOfOrNull { line -> line.items.sumOf { placeables[it].width } + h * (line.items.size - 1) } ?: 0
        layout(layoutWidth, totalHeight, mapOf(FirstBaseline to (lines.firstOrNull()?.baseline ?: 0))) {
            var y = 0
            for (line in lines) {
                var x = 0
                for (i in line.items) {
                    val p = placeables[i]
                    p.placeRelative(x, y + line.baseline - p.baselineOr())
                    x += p.width + h
                }
                y += line.height + v
            }
        }
    }
}

private fun androidx.compose.ui.layout.Placeable.baselineOr(): Int =
    this[FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: height
