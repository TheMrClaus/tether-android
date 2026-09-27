package com.tether.app.ui.statusline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.model.SessionView
import com.tether.app.ui.theme.CssLineHeight
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.TetherTypography
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * ENTRY POINT — the workspace statusline (components/session-statusline.tsx): the readings the
 * inspector column would show, for layouts where that column does not exist.
 *
 * Where the web places it (components/workspace-header.tsx:93, globals.css 11861-11879, 4081-4090,
 * 4167): inside the header's **"Session links" popover** (the `…` key, `.workspace-links-popover`),
 * as the last item of `.workspace-meta-row`, below the path / resume-command / Tether-id rows. The
 * popover lays the strip out wrapping (`flex-wrap: wrap`), right-aligned on phones
 * ([Arrangement.End]) and left-aligned from 48rem ([Arrangement.Start]). At ≥ 100rem (the
 * inspector column exists) the web hides it. Host with:
 *
 * ```
 * SessionStatusline(TelemetryMetrics.from(session.metrics), projectionTrees[session.id]?.let(::SessionView))
 * ```
 *
 * Renders nothing when there is no reading at all (session-statusline.tsx:151-154). Segments drop
 * from the low-priority TAIL as the container narrows ([statuslineFit]); the thresholds are rem, so
 * they scale with the font scale like the text they were measured from. The strip re-renders when
 * a wrap-up's window resets with no further event (session-statusline.tsx:141-147).
 */
@Composable
fun SessionStatusline(
    metrics: TelemetryMetrics?,
    state: SessionView?,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.End,
    wrap: Boolean = true,
    env: () -> ReadingEnv = ReadingEnv::current,
) {
    val tick = rememberWrapUpExpiry(state, env)
    val segments = remember(metrics, state, tick) { buildStatusSegments(metrics, state, env()) }
    StatuslineSegments(segments, modifier, horizontalArrangement, wrap)
}

/**
 * Re-reads at the instant a live wrap-up expires on its own clock (the CLI may not push a
 * clearing rate_limit event for an idle reset); the returned counter changes at that instant.
 */
@Composable
internal fun rememberWrapUpExpiry(state: SessionView?, env: () -> ReadingEnv): Int {
    var tick by remember { mutableIntStateOf(0) }
    val expiresAt = wrapUpExpiresAt(state)
    LaunchedEffect(expiresAt) {
        if (expiresAt == null) return@LaunchedEffect
        delay(max(0.0, expiresAt - env().nowMs).toLong())
        tick++
    }
    return tick
}

/** The strip for already-built [segments] (a preview, the landing demo, a test board). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatuslineSegments(
    segments: List<StatusSegment>,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.End,
    wrap: Boolean = true,
) {
    if (segments.isEmpty()) return
    val t = LocalTetherTokens.current
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(
        modifier
            // `.statusline { min-width: 4.5rem }`
            .widthIn(min = (4.5f * TetherTypography.SP_PER_REM).dp)
            .semantics { isTraversalGroup = true; contentDescription = "Session telemetry" },
    ) {
        // Container queries read the strip's own width in rem (1rem = 16sp: scales with the font).
        val widthRem = if (constraints.hasBoundedWidth) maxWidth.value / (TetherTypography.SP_PER_REM * fontScale) else Float.MAX_VALUE
        val fit = statuslineFit(widthRem)
        val shown = segments.take(fit.visibleRanks)
        val gap = Arrangement.spacedBy(t.css.spaceMd, horizontalArrangementAlignment(horizontalArrangement))
        if (wrap) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = gap,
                verticalArrangement = Arrangement.spacedBy(t.css.spaceMd),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) { shown.forEach { Segment(it, fit.showTrack, t, elasticWeight = false) } }
        } else {
            Row(Modifier.fillMaxWidth().clipToBounds(), horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
                shown.forEach { Segment(it, fit.showTrack, t, elasticWeight = true) }
            }
        }
    }
}

private fun horizontalArrangementAlignment(arrangement: Arrangement.Horizontal): Alignment.Horizontal = when (arrangement) {
    Arrangement.Start -> Alignment.Start
    Arrangement.Center -> Alignment.CenterHorizontally
    else -> Alignment.End
}

/** `.statusline`: "JetBrains Mono Variable" 0.66rem, line-height 1.2 (globals.css 1800-1816). */
private val StatuslineText = TextStyle(
    fontFamily = JetBrainsMono,
    fontSize = (0.66f * TetherTypography.SP_PER_REM).sp,
    lineHeight = 1.2.em,
    lineHeightStyle = CssLineHeight,
)

/**
 * One `.statusline-seg`: key (`--faint`), the fill bar for a segment with a level, and the value
 * (`--ink`, tabular; `--warning` / `--danger` when toned). TalkBack reads the segment's full
 * unabbreviated reading (its web `title`), plus the bar's level when it has one.
 */
@Composable
private fun RowScope.Segment(segment: StatusSegment, showTrack: Boolean, t: TetherTokens, elasticWeight: Boolean) {
    val valueColor = when (segment.effectiveTone) {
        UsageTone.None -> t.ink
        UsageTone.High -> t.warning
        UsageTone.Critical -> t.danger
    }
    Row(
        modifier = (if (segment.elastic && elasticWeight) Modifier.weight(1f, fill = false) else Modifier)
            .clearAndSetSemantics {
                contentDescription = segment.title
                segment.percent?.let { progressBarRangeInfo = ProgressBarRangeInfo(it.toFloat(), 0f..100f) }
            },
        horizontalArrangement = Arrangement.spacedBy(StatuslineSegmentGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(segment.key, style = StatuslineText, color = t.faint, maxLines = 1, softWrap = false)
        if (segment.percent != null && showTrack) UsageTrack(segment.percent, segment.label)
        Text(
            segment.value,
            style = StatuslineText.copy(fontFeatureSettings = TetherTypography.TABULAR_NUMS),
            color = valueColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** `.statusline-seg { gap: 0.3rem }`. */
private val StatuslineSegmentGap = (0.3f * TetherTypography.SP_PER_REM).dp
