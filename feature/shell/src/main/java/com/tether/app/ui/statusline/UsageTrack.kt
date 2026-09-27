package com.tether.app.ui.statusline

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.ThemeFamily

/** Where a usage track sits: its size and the material rules that apply there. */
enum class UsageTrackPlacement {
    /** `.statusline .usage-track`: 1.6rem × 0.3rem, no border, no shadow (globals.css 1853-1858, 11588). */
    Statusline,

    /** A full-width meter (`:root .usage-track`, globals.css 11579-11587): 0.4rem with a 1px edge. */
    Meter,
}

/**
 * The one progressbar idiom (telemetry-readings.tsx:40-52 `UsageTrack`): a track plus a fill whose
 * tone shifts at 75 / 90 — the printed percentage always travels beside it. An absent reading
 * renders an empty track that announces nothing (never "0%"); a real one announces as a progress
 * bar named "<label> usage".
 *
 * Material, per skin: the track floor is `--key-face-deep` (instrument) or `--line` with 6px
 * corners (Studio, studio.css:850-851); the fill is `--violet`, `--amber` when high
 * (`:root .usage-track.is-high i`, globals.css:9140 beats the base `--warning`), `--danger` when
 * critical, with an `inset 0 1px 0 var(--lit-soft)` highlight. The fill's width/colour
 * transition (`var(--duration) var(--ease-out)`, globals.css 4490-4497) becomes a jump under
 * reduced motion.
 */
@Composable
fun UsageTrack(
    percent: Int?,
    label: String,
    modifier: Modifier = Modifier,
    placement: UsageTrackPlacement = UsageTrackPlacement.Statusline,
) {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val studio = t.skin.family == ThemeFamily.Studio
    val tone = usageTone(percent)
    val shape: Shape = if (studio) RoundedCornerShape(6.dp) else RoundedCornerShape(percent = 50)
    val floor = if (studio) t.line else t.keyFaceDeep
    val meter = placement == UsageTrackPlacement.Meter
    val border = if (meter) CssBorder(1.dp, t.lineStrong) else null
    val shadows = if (meter && !studio) listOf(CssShadow(true, 0.dp, 1.dp, 2.dp, 0.dp, t.contact.copy(alpha = 0.2f))) else emptyList()
    val duration = t.css.duration
    val easing = t.css.easeOut.toEasing()
    val width by animateFloatAsState(
        targetValue = (percent ?: 0) / 100f,
        animationSpec = if (reduced) snap() else tween(duration, easing = easing),
        label = "usage-track-width",
    )
    val fillColor by animateColorAsState(
        targetValue = fillColor(t, tone),
        animationSpec = if (reduced) snap() else tween(duration),
        label = "usage-track-tone",
    )
    val size: Modifier = when (placement) {
        UsageTrackPlacement.Statusline -> Modifier.size(width = StatuslineTrackWidth, height = StatuslineTrackHeight)
        UsageTrackPlacement.Meter -> Modifier.fillMaxWidth().height(MeterTrackHeight)
    }
    Box(
        modifier
            .then(size)
            .semantics {
                if (percent != null) {
                    contentDescription = "$label usage"
                    progressBarRangeInfo = ProgressBarRangeInfo(percent.toFloat(), 0f..100f)
                }
            }
            .cssSurface(shape, floor, border, shadows),
    ) {
        if (width > 0f) {
            Box(
                Modifier
                    .padding(border?.width ?: 0.dp)
                    .fillMaxHeight()
                    .fillMaxWidth(width)
                    .cssSurface(shape, fillColor, shadows = listOf(CssShadow(true, 0.dp, 1.dp, 0.dp, 0.dp, t.litSoft))),
            )
        }
    }
}

private fun fillColor(t: TetherTokens, tone: UsageTone): Color = when (tone) {
    UsageTone.None -> t.violet
    UsageTone.High -> t.amber
    UsageTone.Critical -> t.danger
}

/** `.statusline .usage-track { width: 1.6rem }` and `:root .statusline .usage-track { height: 0.3rem }`. */
val StatuslineTrackWidth: Dp = 25.6.dp
val StatuslineTrackHeight: Dp = 4.8.dp

/** `:root .usage-track { height: 0.4rem }` (border-box). */
val MeterTrackHeight: Dp = 6.4.dp
