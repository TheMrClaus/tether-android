package com.tether.app.ui.statusline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.focusRing
import com.tether.app.ui.theme.CssLineHeight
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.TetherTypography
import com.tether.app.ui.theme.ThemeFamily

/**
 * ENTRY POINT — the live context gauge (components/context-gauge.tsx): a 270° speedometer arc
 * opening at the bottom; the faint track is the whole window, the filled arc (the needle) is
 * `contextPercent`, in `--violet`, `--warning` from 75%, `--danger` from 90%. The reading always
 * travels as text: the button's TalkBack label is `contextReading().detail`
 * ("Context 45% full · 123K of 1M tokens"), else [title], else "Session telemetry".
 *
 * Where the web places it (workspace-header.tsx:122-128): in `.workspace-actions`, right after the
 * dial, before the `…` Session-links key. It is the **telemetry drawer's handle** on narrow
 * layouts: tap opens the telemetry sheet ("Session details"), tap again collapses it; [pressed]
 * mirrors the sheet's open state (`aria-pressed`). Its label "Telemetry" is hidden below 48rem
 * (globals.css 1966-1970) and printed from 48rem (4117-4121) — pass [showLabel]. On wide layouts
 * with an inspector column the same key is a quiet indicator.
 *
 * Material (`.telemetry-button.context-gauge`, globals.css 1930-1986, 8975-8990, 11194-11195): 44dp
 * minimum, `--radius-key`, transparent at rest in `--muted`; held down it seats as a pressed key
 * (`--key-face-deep`, `--bevel-pressed`, `--shadow-key-pressed`, `--press-travel`); open, it takes
 * the `--violet-wash` plate and `--violet` legend (open = selected, so violet is right). Studio
 * flattens every header-rail control (studio.css 362-366, specificity (0,3,1) beats both), so
 * there the only visible open cue is the violet label. `:hover` is not modelled (touch).
 */
@Composable
fun ContextGauge(
    metrics: TelemetryMetrics?,
    modifier: Modifier = Modifier,
    label: String = "Telemetry",
    showLabel: Boolean = false,
    /** The drawer's open state; null where the gauge is not a drawer handle (tooltip-only). */
    pressed: Boolean? = null,
    onClick: (() -> Unit)? = null,
    title: String? = null,
    interactionSource: MutableInteractionSource? = null,
    env: () -> ReadingEnv = ReadingEnv::current,
    /**
     * T13.2 r2 (SYNC_DESIGN §4.2): the words qualifying a reading from a copy that is not live
     * ("Saved copy · updated 12 min ago"); null while live. The needle turns neutral and TalkBack
     * hears the qualifier after the reading, so a saved copy's gauge never reads as now.
     */
    stale: String? = null,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val reading = remember(metrics, title) { gaugeReading(metrics, env(), title) }
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val held by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val open = pressed == true
    val studio = t.skin.family == ThemeFamily.Studio
    val shape = RoundedCornerShape(t.radiusKey)
    val active = held && onClick != null

    val face = when {
        studio -> Color.Transparent
        active -> t.keyFaceDeep
        open -> t.violetWash
        else -> Color.Transparent
    }
    val shadows = if (active && !studio) t.css.bevelPressed + t.css.shadowKeyPressed else emptyList()
    val legend = if (open) t.violet else t.muted

    val interactive = when {
        onClick == null -> Modifier
        pressed != null -> Modifier.toggleable(value = pressed, interactionSource = interaction, indication = null, role = Role.Button) { onClick() }
        else -> Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
    }

    Row(
        modifier
            .offset(y = if (active) t.pressTravel else 0.dp)
            .focusRing(focused, shape, t.violet)
            .cssSurface(shape, face, shadows = shadows)
            .then(interactive)
            .semantics {
                contentDescription = if (stale == null) reading.text else "${reading.text}, $stale"
                if (pressed != null) stateDescription = if (pressed) "Pressed" else "Not pressed"
            }
            .defaultMinSize(minWidth = TetherDimens.touchTargetDp, minHeight = TetherDimens.touchTargetDp)
            // UA `button` padding (1px 6px): the web's reset sets font/colour only.
            .padding(horizontal = 6.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GaugeDial(reading, t, Modifier.size(GaugeGlyphSize), stale = stale != null)
        if (showLabel) {
            Text(
                label,
                style = TextStyle(
                    fontFamily = type.ui,
                    fontSize = (0.74f * TetherTypography.SP_PER_REM).sp,
                    fontWeight = FontWeight(400),
                    lineHeightStyle = CssLineHeight,
                ),
                color = legend,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * The 24-unit SVG at 18dp (context-gauge.tsx:17-24, 56-82): r = 9, stroke 2.75 with round caps,
 * the dash starting at 135° (bottom-left) and sweeping clockwise through the top. The track is
 * `--slate`; the needle is drawn only for a reading above 0.
 */
@Composable
private fun GaugeDial(reading: GaugeReading, t: TetherTokens, modifier: Modifier, stale: Boolean = false) {
    val needle = when {
        // T13.2 r2: a saved copy's reading is neutral ink (stale is neither focus nor an alarm).
        stale -> t.faint
        else -> when (reading.tone) {
        UsageTone.None -> t.violet
        UsageTone.High -> t.warning
        UsageTone.Critical -> t.danger
        }
    }
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        val radius = 9f * unit
        val stroke = Stroke(width = 2.75f * unit, cap = StrokeCap.Round)
        val topLeft = Offset(12f * unit - radius, 12f * unit - radius)
        val box = Size(radius * 2, radius * 2)
        drawArc(t.slate, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = topLeft, size = box, style = stroke)
        if (reading.showsNeedle) {
            drawArc(needle, startAngle = 135f, sweepAngle = 270f * reading.fillFraction, useCenter = false, topLeft = topLeft, size = box, style = stroke)
        }
    }
}

private val GaugeGlyphSize = 18.dp
