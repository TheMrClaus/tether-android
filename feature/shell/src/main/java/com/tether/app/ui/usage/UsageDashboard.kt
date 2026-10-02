package com.tether.app.ui.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.tether.app.client.LabelText
import com.tether.app.client.UsageAnalytics
import com.tether.app.client.UsageDay
import com.tether.app.client.UsageSource
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.focusRing
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.shell.cssText
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.tabularNums
import kotlinx.coroutines.delay

/*
 * T9.2: the Usage page — components/usage-dashboard.tsx + usage-dashboard.module.css (the Studio
 * analytics rules at its end win over the structural ones by source order, as on the web). The page
 * is the console's `/usage` route; here it takes the workspace column under the shared top bar,
 * full width, without the rail. The media queries are the page's own width (the web's viewport):
 * ≤ 1100 and ≤ 700 (with the structural 64rem / 44rem ones folded in where Studio does not override).
 */

internal object UsageTags {
    const val Page = "usage-page"
    const val Range = "usage-range"
    fun range(r: UsageRange) = "usage-range-${r.key}"
    const val Refresh = "usage-refresh"
    const val SyncState = "usage-sync-state"
    const val Skeleton = "usage-skeleton"
    const val Error = "usage-error"
    const val TryAgain = "usage-try-again"
    const val Empty = "usage-empty"
    const val OpenConsole = "usage-open-console"
    const val Readings = "usage-readings"
    const val Tiles = "usage-tiles"
    const val Leaders = "usage-leaders"
    const val Chart = "usage-chart"
    const val Heatmap = "usage-heatmap"
    const val Table = "usage-top-sessions"
    const val Footnote = "usage-footnote"
    fun card(title: String) = "usage-card-$title"
    fun showMore(title: String) = "usage-show-more-$title"
}

/** The dataviz palette and ramps (usage-dashboard.module.css:11-58): light steps, dark under Studio dark. */
internal class VizPalette(val series: List<Color>, val seq: List<Color>, val grid: Color) {
    companion object {
        fun of(t: TetherTokens): VizPalette = if (t.skin.isDark) {
            VizPalette(
                listOf(0x3987e5, 0xd95926, 0x199e70, 0xc98500, 0xd55181, 0x008300, 0x9085e9, 0xe66767).map(::rgb),
                listOf(Color.White.copy(alpha = 0.04f)) + listOf(0x184f95, 0x256abf, 0x2a78d6, 0x5598e7, 0x86b6ef).map(::rgb),
                t.line,
            )
        } else {
            VizPalette(
                listOf(0x2a78d6, 0xeb6834, 0x1baf7a, 0xeda100, 0xe87ba4, 0x008300, 0x4a3aa7, 0xe34948).map(::rgb),
                listOf(Color.Black.copy(alpha = 0.04f)) + listOf(0x86b6ef, 0x5598e7, 0x3987e5, 0x256abf, 0x184f95).map(::rgb),
                Color.Black.copy(alpha = 0.08f),
            )
        }

        private fun rgb(hex: Int) = Color(0xFF000000 or hex.toLong())
    }

    /** `var(--sN)`, 1-based. */
    fun s(n: Int): Color = series[(n - 1) % series.size]
}

/** The page's own breakpoints (the web's media queries on the viewport). */
internal data class UsageLayout(val compact: Boolean, val medium: Boolean) {
    companion object {
        fun of(width: Dp) = UsageLayout(compact = width <= 700.dp, medium = width <= 1100.dp)
    }
}

/** A CSS px size as this file's text role. */
private fun px(family: androidx.compose.ui.text.font.FontFamily, size: Float, weight: Int, trackingEm: Float = 0f, lineHeight: Float? = null): TextStyle =
    cssText(family, size / 16f, weight, trackingEm, lineHeight)

/**
 * The Usage page wired to [source]: it fetches the selected range at once, then every
 * [UsageDashboardModel.POLL_MS] while the app is started (the web's visible tab), and again on every
 * return to the foreground (the web's `visibilitychange` / `focus`). [origin] is the server it is
 * drawn for; another server's answer is never shown.
 */
@Composable
fun UsagePage(
    source: UsageSource,
    origin: String?,
    onOpenConsole: () -> Unit,
    modifier: Modifier = Modifier,
    state: UsageDashboardState = remember(origin) { UsageDashboardState() },
) {
    val env = LocalUsageEnv.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val range = state.range
    LaunchedEffect(source, origin, range, state.refresh, started) {
        if (!started) return@LaunchedEffect
        while (true) {
            val since = UsageDashboardModel.sinceParam(range.days, env.now())
            state.onResult(range, source.analytics(origin, since.ifEmpty { null }))
            delay(UsageDashboardModel.POLL_MS)
        }
    }
    UsageDashboard(state = state, onOpenConsole = onOpenConsole, modifier = modifier)
}

/** The page over [state] (stateless but for each breakdown's "Show all"). */
@Composable
fun UsageDashboard(state: UsageDashboardState, onOpenConsole: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val env = LocalUsageEnv.current
    BoxWithConstraints(modifier.fillMaxSize().cssSurface(androidx.compose.ui.graphics.RectangleShape, t.mineral).testTag(UsageTags.Page)) {
        val layout = UsageLayout.of(maxWidth)
        val padH = when {
            layout.compact -> 20.dp
            layout.medium -> 28.dp
            else -> (maxWidth * 0.05f).coerceIn(24.dp, 80.dp)
        }
        val padV = when {
            layout.compact -> 24.dp
            layout.medium -> 28.dp
            else -> 40.dp
        }
        val gap = if (layout.compact) 24.dp else 28.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = padH, vertical = padV),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 1560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                Header(state, layout)
                Text(
                    UsageDashboardModel.syncState(state, env),
                    color = t.muted,
                    style = px(LocalTetherTypography.current.ui, 12f, 400),
                    modifier = Modifier.heightIn(min = 18.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag(UsageTags.SyncState),
                )
                val data = state.data
                if (state.loading && data == null) Skeleton(layout)
                state.error?.let { ErrorBanner(it, data != null, layout, onTryAgain = state::refreshNow) }
                if (data != null && data.totals.sessions == 0.0) {
                    EmptyRange(layout, onOpenConsole)
                } else if (data != null) {
                    Column(
                        Modifier.fillMaxWidth().semantics { if (state.loading) stateDescription = "Updating" }.testTag(UsageTags.Readings),
                        verticalArrangement = Arrangement.spacedBy(if (layout.compact) 24.dp else 28.dp),
                    ) { Readings(data, layout, env) }
                }
            }
        }
    }
}

@Composable
private fun Header(state: UsageDashboardState, layout: UsageLayout) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    // `.header`: wraps; Studio aligns flex-start; ≤ 1100 the actions take a full row of their own.
    FlowRow(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(t.line, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
            .padding(bottom = 1.dp + if (layout.compact) 24.dp else 28.dp),
        horizontalArrangement = Arrangement.spacedBy(if (layout.compact) 20.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (layout.compact) 20.dp else 24.dp),
    ) {
        Column(Modifier.weight(1f, fill = layout.medium).widthIn(min = 0.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.4.dp)) {
                Icon(TetherIcons.ChartColumn, contentDescription = null, tint = t.violetStrong, modifier = Modifier.size(22.dp))
                Text(
                    "Usage analytics",
                    color = t.white,
                    style = px(type.ui, if (layout.compact) 28f else 32f, 700, trackingEm = -0.03f, lineHeight = 1.3f),
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                UsageDashboardModel.subtitle(state.data),
                color = t.muted,
                style = px(type.ui, if (layout.compact) 12f else 13f, 400, lineHeight = 1.7f).tabularNums(),
                modifier = Modifier.padding(top = 10.dp).widthIn(max = 520.dp),
            )
        }
        Row(
            Modifier.then(if (layout.medium) Modifier.fillMaxWidth() else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (layout.compact) 8.dp else 10.dp),
        ) {
            RangeGroup(state.range, layout, onSelect = state::selectRange)
            PageKey(
                onClick = state::refreshNow,
                icon = TetherIcons.RefreshCw,
                contentDescription = "Refresh usage",
                modifier = Modifier.testTag(UsageTags.Refresh),
            )
        }
    }
}

/** `.range` (role="group" "Time range"): the three keys on `--slate`, the active one raised and pressed. */
@Composable
private fun RangeGroup(current: UsageRange, layout: UsageLayout, onSelect: (UsageRange) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .cssSurface(RoundedCornerShape(9.dp), t.slate)
            .padding(3.dp)
            .semantics { paneTitle = "Time range" }
            .testTag(UsageTags.Range),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        UsageRange.entries.forEach { r ->
            val active = r == current
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            val pressed by interaction.collectIsPressedAsState()
            Box(
                Modifier
                    .widthIn(min = 44.dp)
                    .heightIn(min = if (layout.compact) 44.dp else 38.dp)
                    .cssSurface(RoundedCornerShape(6.dp), if (active) t.graphite else Color.Transparent)
                    .clickable(interaction, indication = null, role = Role.Button) { onSelect(r) }
                    .focusRing(focused, RoundedCornerShape(6.dp), t.violetStrong)
                    .clearAndSetSemantics {
                        contentDescription = r.description
                        selected = active
                        stateDescription = if (active) "Selected" else "Not selected"
                    }
                    .padding(horizontal = if (layout.compact) 10.dp else 14.dp)
                    .testTag(UsageTags.range(r)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    r.label,
                    color = when {
                        active -> t.violetStrong
                        pressed -> t.white
                        else -> t.muted
                    },
                    maxLines = 1,
                    style = px(type.ui, 12f, if (active) 700 else 400),
                )
            }
        }
    }
}

/**
 * `.back` / `.refresh` (Studio): 44px tall, `1px --line`, 8px corners on `--graphite`, 13px / 600
 * `--ink`; held (the web's hover) `--violet-wash` with `--violet-strong` ink.
 */
@Composable
internal fun PageKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    contentDescription: String? = null,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    val ink = if (pressed) t.violetStrong else t.ink
    Row(
        modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            .cssSurface(shape, if (pressed) t.violetWash else t.graphite, CssBorder(1.dp, t.line))
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .focusRing(focused, shape, t.violetStrong)
            .then(if (contentDescription != null) Modifier.clearAndSetSemantics { this.contentDescription = contentDescription; this.role = Role.Button } else Modifier)
            .padding(horizontal = if (label == null) 0.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.4.dp, Alignment.CenterHorizontally),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(15.dp)) }
        label?.let { Text(it, color = ink, maxLines = 1, style = px(type.ui, 13f, 600)) }
        trailingIcon?.let { Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp)) }
    }
}

/** `.skeleton`: four (two narrow) 9rem cells on `--tint-sm`, 1px apart. */
@Composable
private fun Skeleton(layout: UsageLayout) {
    val t = LocalTetherTokens.current
    val columns = if (layout.compact) 2 else 4
    Column(
        Modifier
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(12.dp), Color.Transparent, CssBorder(1.dp, t.line))
            .padding(1.dp)
            .clip(RoundedCornerShape(11.dp))
            .clearAndSetSemantics { }
            .testTag(UsageTags.Skeleton),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        (0 until 4).chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                row.forEach { _ -> Box(Modifier.weight(1f).height(144.dp).cssSurface(androidx.compose.ui.graphics.RectangleShape, t.tintSm)) }
            }
        }
    }
}

/** `.error` (role="alert"): the danger wash, the glyph, the sentence and "Try again". */
@Composable
private fun ErrorBanner(error: String, hasData: Boolean, layout: UsageLayout, onTryAgain: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val text = UsageDashboardModel.errorBanner(error, hasData)
    FlowRow(
        Modifier
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(8.dp), t.dangerWash)
            .padding(18.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .testTag(UsageTags.Error),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f, fill = true).widthIn(min = 160.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.danger, modifier = Modifier.size(18.dp))
            // The error may carry a server's words (prose rule).
            Text(com.tether.app.ui.text.proseText(text), color = t.danger, style = px(type.ui, 13f, 400, lineHeight = 1.6f), modifier = Modifier.weight(1f))
        }
        PageKey(onClick = onTryAgain, label = "Try again", modifier = Modifier.testTag(UsageTags.TryAgain))
    }
}

/** `.state`: no sessions in the range. */
@Composable
private fun EmptyRange(layout: UsageLayout, onOpenConsole: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = if (layout.compact) 16.dp else 24.dp, vertical = if (layout.compact) 48.dp else 80.dp).testTag(UsageTags.Empty),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(TetherIcons.ChartColumn, contentDescription = null, tint = t.muted, modifier = Modifier.size(32.dp))
        Text("No usage in this range", color = t.white, textAlign = TextAlign.Center, style = px(type.ui, 26f, 700, trackingEm = -0.025f), modifier = Modifier.semantics { heading() })
        Text(
            "Choose a wider range, or start a session to see its activity here.",
            color = t.muted,
            textAlign = TextAlign.Center,
            style = px(type.ui, 14f, 400, lineHeight = 1.75f),
            modifier = Modifier.widthIn(max = 360.dp),
        )
        PageKey(onClick = onOpenConsole, label = "Open console", trailingIcon = TetherIcons.ArrowLeft, modifier = Modifier.testTag(UsageTags.OpenConsole))
    }
}

// ── The readings ─────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.Readings(data: UsageAnalytics, layout: UsageLayout, env: UsageEnv) {
    val t = LocalTetherTokens.current
    val palette = VizPalette.of(t)
    val tt = data.totals
    val mu = data.mostUsed
    val fmt = UsageFormat::compact
    val int = UsageFormat::whole

    TileBank(
        layout,
        listOf(
            TileSpec(TetherIcons.Coins, "Total tokens", fmt(tt.tokens), sub = { SplitSub(data) }),
            TileSpec(TetherIcons.Percent, "Cache efficiency", "${UsageFormat.js(tt.cacheEfficiencyPct)}%", subText = "cache reads ÷ all input"),
            TileSpec(TetherIcons.Coins, "Est. cost", UsageFormat.money(tt.costUSD), subText = "known models only", badge = if (tt.costPartial) "partial" else null),
            TileSpec(TetherIcons.Layers, "Avg / session", fmt(tt.avgTokensPerSession), subText = "${fmt(tt.subagentTokens)} via subagents"),
        ),
    )
    Leaders(
        layout,
        listOf(
            TileSpec(TetherIcons.Server, "Top harness", mu.harness?.key ?: "—", subText = mu.harness?.let { "${UsageFormat.js(it.sharePct)}% of tokens" }),
            TileSpec(TetherIcons.Cpu, "Top provider", mu.provider?.key ?: "—", subText = mu.provider?.let { "${UsageFormat.js(it.sharePct)}% of tokens" }),
            TileSpec(TetherIcons.Bot, "Top agent", mu.agent?.key ?: "—", subText = mu.agent?.let { "${int(it.value)} runs · ${UsageFormat.js(it.sharePct)}%" }),
            TileSpec(TetherIcons.Boxes, "Top model", mu.model?.key ?: "—", subText = mu.model?.let { "${UsageFormat.js(it.sharePct)}% of tokens" }),
        ),
    )

    Card("Tokens per day", "${data.byDay.size} days · stacked by component", layout, icon = TetherIcons.Activity) { AreaChart(data.byDay, palette) }

    CardGrid(layout) {
        listOf<@Composable () -> Unit>(
            { Card("Tokens by harness", null, layout) { BarList("Tokens by harness", data.byHarness.map { it.key to it.tokens }, palette, byIndex = true, fmt = fmt) } },
            { Card("Sessions by harness", null, layout) { BarList("Sessions by harness", data.byHarness.map { it.key to it.sessions }, palette, byIndex = true, fmt = int) } },
            { Card("Turns by harness", null, layout) { BarList("Turns by harness", data.byHarness.map { it.key to it.turns }, palette, byIndex = true, fmt = int) } },
            { Card("Tokens by provider", null, layout) { BarList("Tokens by provider", data.byProvider.map { it.key to it.tokens }, palette, byIndex = true, fmt = fmt) } },
            { Card("Tokens by model", null, layout) { BarList("Tokens by model", data.byModel.map { it.key to it.tokens }, palette, byIndex = true, fmt = fmt, mono = true) } },
            { Card("Most-used agents", "subagent runs", layout) { BarList("Most-used agents", data.byAgent.take(8).map { it.key to it.count }, palette, color = palette.s(7), fmt = int) } },
        )
    }

    Card("Token composition", "what the tokens were", layout) { Composition(data, palette) }

    Card(
        "Activity by hour (UTC)",
        null,
        layout,
        aside = { HeatLegend(palette) },
    ) { Heatmap(data.heat, palette) }

    CardGrid(layout) {
        listOf<@Composable () -> Unit>(
            { Card("Most-used tools", "tool calls", layout) { BarList("Most-used tools", data.byTool.take(8).map { it.key to it.count }, palette, color = palette.s(3), fmt = int, mono = true) } },
            { Card("Top projects", "by tokens", layout) { BarList("Top projects", data.byProject.take(8).map { UsageFormat.shortPath(it.key) to it.tokens }, palette, color = palette.s(1), fmt = fmt, mono = true) } },
            {
                Card("Effort mix", "assistant turns", layout) {
                    BarList("Effort mix", data.effort.sortedByDescending { it.second }, palette, color = palette.s(4), fmt = int)
                }
            },
        )
    }

    Card("Top sessions", "by tokens", layout) { TopSessionsTable(data, layout) }

    Text(
        "Generated ${UsageFormat.dateTime(data.generatedAt, env)} · Cost estimates cover known models. ${if (tt.costPartial) "Some models are unpriced." else ""}",
        color = t.muted,
        style = px(LocalTetherTypography.current.ui, 12f, 400, lineHeight = 1.7f),
        modifier = Modifier.testTag(UsageTags.Footnote),
    )
}

private data class TileSpec(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val subText: String? = null,
    val badge: String? = null,
    val sub: (@Composable () -> Unit)? = null,
)

/** The raw total never stands alone: the split right under it (usage-dashboard.tsx:247-253). */
@Composable
private fun SplitSub(data: UsageAnalytics) {
    val t = LocalTetherTokens.current
    val b = SpanStyle(color = t.ink, fontWeight = FontWeight(650))
    val tt = data.totals
    Text(
        buildAnnotatedString {
            append("output ")
            withStyle(b) { append(UsageFormat.compact(tt.output)) }
            append(" · cache read ")
            withStyle(b) { append(UsageFormat.compact(tt.cacheRead)) }
            append(" · cache write ")
            withStyle(b) { append(UsageFormat.compact(tt.cacheCreation)) }
        },
        color = t.muted,
        style = tileSubStyle(),
    )
}

@Composable
private fun tileSubStyle(): TextStyle {
    val compact = LocalUsageCompact.current
    return px(LocalTetherTypography.current.ui, if (compact) 11f else 12f, 400, lineHeight = 1.6f).tabularNums()
}

private val LocalUsageCompact = androidx.compose.runtime.compositionLocalOf { false }

/** `.tiles`: one bordered bank, four across (two from ≤ 700, the third starting a new row). */
@Composable
private fun TileBank(layout: UsageLayout, tiles: List<TileSpec>) {
    val t = LocalTetherTokens.current
    val columns = if (layout.compact) 2 else 4
    val pad = when {
        layout.compact -> Pair(16.dp, 20.dp)
        layout.medium -> Pair(22.dp, 22.dp)
        else -> Pair(26.dp, 26.dp)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalUsageCompact provides layout.compact) {
        Column(
            Modifier
                .fillMaxWidth()
                .cssSurface(RoundedCornerShape(12.dp), t.graphite, CssBorder(1.dp, t.line))
                .padding(1.dp)
                .testTag(UsageTags.Tiles),
        ) {
            tiles.chunked(columns).forEachIndexed { r, row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .then(if (r > 0) Modifier.drawBehind { drawRect(t.line, Offset.Zero, Size(size.width, 1.dp.toPx())) }.padding(top = 1.dp) else Modifier),
                ) {
                    row.forEachIndexed { c, tile ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(if (c > 0) Modifier.drawBehind { drawRect(t.line, Offset.Zero, Size(1.dp.toPx(), size.height)) }.padding(start = 1.dp) else Modifier),
                        ) {
                            Tile(tile, Modifier.padding(horizontal = pad.first, vertical = pad.second), big = true, layout = layout)
                        }
                    }
                }
            }
        }
    }
}

/** `.leaders`: the quieter strip, no frame but its bottom rule, 16px (14px narrow) values. */
@Composable
private fun Leaders(layout: UsageLayout, tiles: List<TileSpec>) {
    val t = LocalTetherTokens.current
    val columns = if (layout.compact) 2 else 4
    val inset = if (layout.compact) 16.dp else 26.dp
    androidx.compose.runtime.CompositionLocalProvider(LocalUsageCompact provides layout.compact) {
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(t.line, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
                .padding(top = 2.dp, bottom = 26.dp + 1.dp)
                .testTag(UsageTags.Leaders),
            verticalArrangement = Arrangement.spacedBy(if (layout.compact) 20.dp else 0.dp),
        ) {
            tiles.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    row.forEachIndexed { c, tile ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(if (c > 0) Modifier.drawBehind { drawRect(t.line, Offset.Zero, Size(1.dp.toPx(), size.height)) }.padding(start = 1.dp) else Modifier),
                        ) {
                            Tile(tile, Modifier.padding(start = if (c == 0) 0.dp else inset, end = inset), big = false, layout = layout)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(tile: TileSpec, modifier: Modifier, big: Boolean, layout: UsageLayout) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(if (big) 12.dp else 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.4.dp)) {
            Icon(tile.icon, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
            Text(tile.label, color = t.muted, style = px(type.ui, if (layout.compact) 11f else 12f, 600))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            val valueStyle = if (big) {
                px(type.ui, if (layout.medium) 28f else 34f, 650, trackingEm = -0.035f, lineHeight = 1.25f)
            } else {
                px(type.ui, if (layout.compact) 14f else 16f, 650, trackingEm = -0.01f, lineHeight = 1.5f)
            }
            // A leader's value is server text (a harness, provider, agent or model name): the label rule.
            Text(if (big) tile.value else LabelText.label(tile.value).ifEmpty { tile.value }, color = t.white, style = valueStyle.tabularNums())
            tile.badge?.let { Badge(it) }
        }
        tile.sub?.invoke() ?: tile.subText?.let { Text(it, color = t.muted, style = tileSubStyle()) }
    }
}

/** `.badge` (Studio): the amber wash, `--warning` ink, 10px / 650. */
@Composable
private fun Badge(text: String) {
    val t = LocalTetherTokens.current
    Text(
        text,
        color = t.warning,
        style = px(LocalTetherTypography.current.ui, 10f, 650),
        modifier = Modifier.cssSurface(RoundedCornerShape(4.dp), t.amberWash).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** `.grid2`: three across, two from ≤ 1100, one from ≤ 700; rows align at the top. */
@Composable
private fun CardGrid(layout: UsageLayout, cards: () -> List<@Composable () -> Unit>) {
    val columns = when {
        layout.compact -> 1
        layout.medium -> 2
        else -> 3
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        cards().chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                row.forEach { card -> Box(Modifier.weight(1f)) { card() } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** `.card`: `--graphite`, `1px --line`, 12px; its head (title, note) then the body. */
@Composable
private fun Card(
    title: String,
    note: String?,
    layout: UsageLayout,
    icon: ImageVector? = null,
    aside: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(
        Modifier
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(12.dp), t.graphite, CssBorder(1.dp, t.line))
            .padding(1.dp)
            .padding(horizontal = if (layout.compact) 18.dp else 26.dp, vertical = if (layout.compact) 22.dp else 26.dp)
            .testTag(UsageTags.card(title)),
        verticalArrangement = Arrangement.spacedBy(if (layout.compact) 20.dp else 24.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.Start), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f, fill = true), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                icon?.let { Icon(it, contentDescription = null, tint = t.white, modifier = Modifier.size(14.dp)) }
                Text(title, color = t.white, style = px(type.ui, if (layout.compact) 15f else 16f, 700, trackingEm = -0.015f), modifier = Modifier.semantics { heading() })
            }
            note?.let { Text(it, color = t.muted, style = px(type.ui, if (layout.compact) 11f else 12f, 400, lineHeight = 1.6f)) }
            aside?.invoke()
        }
        content()
    }
}

/**
 * `BarList`: label and value on one line, the meter under them (zero is zero: no minimum fill), at
 * most eight rows until "Show all N". [byIndex] colours each row by its rank (`colorByIndex`).
 */
@Composable
private fun BarList(
    id: String,
    rows: List<Pair<String, Double>>,
    palette: VizPalette,
    fmt: (Double) -> String,
    byIndex: Boolean = false,
    color: Color = palette.s(1),
    mono: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    if (rows.isEmpty()) {
        Text("No data", color = t.muted, style = px(type.ui, 12f, 400, lineHeight = 1.6f))
        return
    }
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val max = maxOf(1.0, rows.maxOf { it.second })
    val visible = if (expanded) rows else rows.take(8)
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        visible.forEachIndexed { i, (key, value) ->
            val fill = if (byIndex) palette.series[i % palette.series.size] else color
            Column(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$key: ${fmt(value)}" },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Every key is server text (a harness, model, tool, path…): the code rule for ids, the label rule otherwise.
                    Text(
                        if (mono) com.tether.app.ui.text.codeLabel(key) else AnnotatedString(LabelText.label(key).ifEmpty { key }),
                        color = t.ink,
                        style = if (mono) px(type.mono, 11.84f, 400, lineHeight = 1.6f) else px(type.ui, 13f, 400, lineHeight = 1.6f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(fmt(value), color = t.muted, maxLines = 1, style = px(type.mono, 12f, 400).tabularNums())
                }
                Box(Modifier.fillMaxWidth().height(6.dp).cssSurface(RoundedCornerShape(4.dp), t.mineral)) {
                    val fraction = (value / max).toFloat().coerceIn(0f, 1f)
                    if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().cssSurface(RoundedCornerShape(4.dp), fill))
                }
            }
        }
        if (rows.size > 8) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .cssSurface(RoundedCornerShape(7.dp), t.mineral)
                    .clickable(interaction, indication = null, role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .testTag(UsageTags.showMore(id)),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (expanded) "Show fewer" else "Show all ${rows.size}", color = t.ink, style = px(type.ui, 12f, 400))
            }
        }
    }
}

/** `.legend`: wrapped swatches with their words. */
@Composable
private fun Legend(items: List<Pair<String, Color>>, values: List<String>? = null) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { i, (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.4.dp)) {
                Box(Modifier.size(8.dp).cssSurface(RoundedCornerShape(2.dp), color))
                Text(
                    buildAnnotatedString {
                        append(label)
                        values?.getOrNull(i)?.let { v ->
                            append(" · ")
                            withStyle(SpanStyle(color = t.ink, fontWeight = FontWeight.Bold)) { append(v) }
                        }
                    },
                    color = t.muted,
                    style = px(type.ui, 12f, 400).tabularNums(),
                )
            }
        }
    }
}

/** `Composition`: one 12px stacked bar (only the non-zero parts), then the legend with each total. */
@Composable
private fun Composition(data: UsageAnalytics, palette: VizPalette) {
    val t = LocalTetherTokens.current
    val c = data.tokenComposition
    val parts = listOf(
        Triple("Cache read", c.cacheRead, palette.s(1)),
        Triple("Cache write", c.cacheCreation, palette.s(2)),
        Triple("Output", c.output, palette.s(3)),
        Triple("Input", c.input, palette.s(4)),
    )
    val total = maxOf(1.0, parts.sumOf { it.second })
    Column(verticalArrangement = Arrangement.spacedBy(11.2.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .cssSurface(RoundedCornerShape(5.dp), t.slate)
                .clearAndSetSemantics {
                    contentDescription = parts.joinToString(", ") { (k, v, _) -> "$k ${UsageFormat.compact(v)} (${String.format(java.util.Locale.US, "%.1f", v / total * 100)}%)" }
                },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            parts.filter { it.second > 0 }.forEach { (_, v, color) ->
                Box(Modifier.weight((v / total).toFloat().coerceAtLeast(0.0001f)).widthIn(min = 1.dp).fillMaxHeight().cssSurface(androidx.compose.ui.graphics.RectangleShape, color))
            }
            val rest = 1f - parts.filter { it.second > 0 }.sumOf { it.second / total }.toFloat()
            if (rest > 0.0001f) Spacer(Modifier.weight(rest))
        }
        Legend(parts.map { it.first to it.third }, parts.map { UsageFormat.compact(it.second) })
    }
}

/** Stacking order, bottom → top (usage-dashboard.tsx:322-327): cache read at the base. */
private val COMP_SERIES = listOf("Cache read", "Cache write", "Output", "Input")

private fun UsageDay.component(i: Int): Double = when (i) {
    0 -> cacheRead
    1 -> cacheCreation
    2 -> output
    else -> input
}

/**
 * `AreaChart`: the web's 900 × 210 SVG (40 padding) drawn at the card's width (never under 720, so a
 * phone scrolls it sideways, `.chartViewport`): three gridlines with their values, the four stacked
 * layers at 0.85, a dot on each day's total, and day labels (every ⌈n/8⌉-th and the last).
 */
@Composable
private fun AreaChart(points: List<UsageDay>, palette: VizPalette) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    if (points.isEmpty()) {
        Text("No data", color = t.muted, style = px(type.ui, 12f, 400, lineHeight = 1.6f))
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = maxOf(maxWidth, 720.dp)
            val height = width * (210f / 900f)
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Canvas(
                    Modifier
                        .width(width)
                        .height(height)
                        .semantics {
                            contentDescription = "Tokens per day by component"
                            stateDescription = "${points.size} days, peak ${UsageFormat.compact(points.maxOf { it.tokens })} tokens"
                        }
                        .testTag(UsageTags.Chart),
                ) {
                    val w = 900f
                    val h = 210f
                    val pad = 40f
                    val s = size.width / w
                    val max = maxOf(1.0, points.maxOf { it.tokens })
                    val n = points.size
                    fun x(i: Int) = (pad + if (n == 1) (w - 2 * pad) / 2 else (i.toFloat() / (n - 1)) * (w - 2 * pad)) * s
                    fun y(v: Double) = (h - pad - (v / max).toFloat() * (h - 2 * pad)) * s
                    val axis = TextStyle(fontFamily = type.ui, fontSize = with(density) { (12f * s).toSp() }, color = t.muted).tabularNums()
                    for (f in listOf(0f, 0.5f, 1f)) {
                        val ty = (h - pad - f * (h - 2 * pad)) * s
                        drawLine(palette.grid, Offset(pad * s, ty), Offset((w - pad) * s, ty), strokeWidth = 1f * s)
                        val label = measurer.measure(UsageFormat.compact(max * f), axis)
                        drawText(label, topLeft = Offset(4f * s, ty + 3f * s - label.firstBaseline))
                    }
                    val stacks = points.map { p -> var sum = 0.0; List(4) { i -> sum += p.component(i); sum } }
                    for (si in 0 until 4) {
                        val path = Path()
                        points.indices.forEach { i -> if (i == 0) path.moveTo(x(i), y(stacks[i][si])) else path.lineTo(x(i), y(stacks[i][si])) }
                        points.indices.reversed().forEach { i -> path.lineTo(x(i), y(if (si == 0) 0.0 else stacks[i][si - 1])) }
                        path.close()
                        drawPath(path, palette.s(si + 1).copy(alpha = 0.85f))
                    }
                    points.forEachIndexed { i, p -> drawCircle(t.white.copy(alpha = 0.9f), radius = 2.5f * s, center = Offset(x(i), y(p.tokens))) }
                    val every = kotlin.math.ceil(n / 8.0).toInt().coerceAtLeast(1)
                    points.forEachIndexed { i, p ->
                        if (i % every == 0 || i == n - 1) {
                            val label = measurer.measure(p.day.drop(5), axis)
                            drawText(label, topLeft = Offset(x(i) - label.size.width / 2f, (h - 8f) * s - label.firstBaseline))
                        }
                    }
                }
            }
        }
        Legend(COMP_SERIES.mapIndexed { i, label -> label to palette.s(i + 1) })
    }
}

private val DOW = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/** `.heatLegend`: Less ▢▢▢ More. */
@Composable
private fun HeatLegend(palette: VizPalette) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.clearAndSetSemantics { }) {
        Text("Less", color = t.muted, style = px(type.ui, 11f, 400))
        listOf(0, 2, 5).forEach { Box(Modifier.size(10.4.dp).cssSurface(RoundedCornerShape(2.dp), palette.seq[it])) }
        Text("More", color = t.muted, style = px(type.ui, 11f, 400))
    }
}

/** `Heatmap`: 7 rows × 24 hours (UTC), six buckets of the busiest hour; scrolls sideways under 480. */
@Composable
private fun Heatmap(heat: List<Double>, palette: VizPalette) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val max = maxOf(1.0, heat.maxOrNull() ?: 0.0)
    fun bucket(v: Double): Color {
        if (v <= 0) return palette.seq[0]
        val f = v / max
        return when {
            f < 0.2 -> palette.seq[1]
            f < 0.4 -> palette.seq[2]
            f < 0.6 -> palette.seq[3]
            f < 0.8 -> palette.seq[4]
            else -> palette.seq[5]
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxOf(maxWidth, 480.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .semantics { contentDescription = "Hourly token activity, Sunday through Saturday, UTC" }
                .testTag(UsageTags.Heatmap),
        ) {
            Column(Modifier.width(width).padding(bottom = 8.dp)) {
                DOW.forEachIndexed { row, day ->
                    Row(Modifier.padding(bottom = 4.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(day, color = t.muted, style = px(type.ui, 11f, 400), modifier = Modifier.width(32.dp))
                        repeat(24) { h -> Box(Modifier.weight(1f).height(24.dp).cssSurface(RoundedCornerShape(3.dp), bucket(heat.getOrElse(row * 24 + h) { 0.0 }))) }
                    }
                }
                Row(Modifier.padding(top = 7.2.dp - 4.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Spacer(Modifier.width(32.dp))
                    repeat(24) { h ->
                        Text(if (h % 6 == 0) "$h" else "", color = t.muted, textAlign = TextAlign.Center, style = px(type.mono, 10f, 400), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * The top-sessions table (`.tableScroll` / `.table`): nine columns sized to their widest cell
 * (`white-space: nowrap`; the three mono columns stop at 16rem with an ellipsis), stretched to the
 * card when narrower; it scrolls sideways, and down past 36rem under a header that stays put.
 */
@Composable
private fun TopSessionsTable(data: UsageAnalytics, layout: UsageLayout) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val headStyle = px(type.ui, 12f, 600)
    val cellStyle = px(type.ui, 13f, 400)
    val monoStyle = px(type.mono, 12f, 400)
    val numStyle = px(type.mono, 13f, 400).tabularNums()
    val heads = listOf("Session", "Model", "Project", "Turns", "Output", "Cache read", "Cache write", "Tokens", "Est. cost")
    val numeric = setOf(3, 4, 5, 6, 7, 8)
    val rows = data.topSessions.map { s ->
        listOf(
            s.sessionId.take(8),
            s.model ?: "—",
            UsageFormat.shortPath(s.cwd),
            UsageFormat.whole(s.turns),
            UsageFormat.compact(s.output),
            UsageFormat.compact(s.cacheRead),
            UsageFormat.compact(s.cacheCreation),
            UsageFormat.compact(s.tokens),
            (if (s.costUSD > 0) UsageFormat.money2(s.costUSD) else "—") + if (s.costPartial) "*" else "",
        )
    }
    val padH = if (layout.compact) 12.dp else 16.dp
    val padHead = 14.dp
    val padCell = if (layout.compact) 14.dp else 16.dp
    val maxMono = 256.dp
    val widths = remember(rows, layout, type) {
        heads.indices.map { c ->
            val head = measurer.measure(heads[c], headStyle).size.width
            val cells = rows.maxOfOrNull { r -> measurer.measure(r[c], if (c in numeric) numStyle else if (c < 3) monoStyle else cellStyle).size.width } ?: 0
            with(density) { (maxOf(head, cells).toDp() + padH * 2).let { if (c < 3) minOf(it, maxMono + padH * 2) else it } }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().semantics { contentDescription = "Top sessions by token usage" }.testTag(UsageTags.Table)) {
        val natural = widths.fold(0.dp) { a, b -> a + b }
        val stretch = if (natural < maxWidth) maxWidth / natural else 1f
        val cols = widths.map { it * stretch }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(cols.fold(0.dp) { a, b -> a + b })) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .cssSurface(androidx.compose.ui.graphics.RectangleShape, t.mineral)
                        .drawBehind { drawRect(t.line, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
                        .semantics(mergeDescendants = true) { heading() },
                ) {
                    heads.forEachIndexed { c, head ->
                        Text(
                            head,
                            color = t.muted,
                            maxLines = 1,
                            textAlign = if (c in numeric) TextAlign.End else TextAlign.Start,
                            style = headStyle,
                            modifier = Modifier.width(cols[c]).padding(horizontal = padH, vertical = padHead),
                        )
                    }
                }
                Column(Modifier.heightIn(max = 576.dp - 44.dp).verticalScroll(rememberScrollState())) {
                    rows.forEachIndexed { r, cells ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(if (r < rows.lastIndex) Modifier.drawBehind { drawRect(t.line, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) } else Modifier)
                                .semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            cells.forEachIndexed { c, cell ->
                                Text(
                                    if (c == 1 || c == 2 || c == 0) com.tether.app.ui.text.codeLabel(cell) else AnnotatedString(cell),
                                    color = t.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = if (c in numeric) TextAlign.End else TextAlign.Start,
                                    style = if (c in numeric) numStyle else if (c < 3) monoStyle else cellStyle,
                                    modifier = Modifier.width(cols[c]).padding(horizontal = padH, vertical = padCell),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
