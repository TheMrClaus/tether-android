package com.tether.app.ui.statusline

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.model.SessionView
import com.tether.app.ui.theme.CssLineHeight
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTypography

/**
 * ENTRY POINT — the wrap-up badge (v127, issue #195: Claude's Wrap-Up Allowance): the account hit
 * its 5-hour limit while a response was in progress and the CLI is finishing on a small capped
 * allowance. Two web homes, both fed by [wrapUpReading]:
 *
 *  1. The statusline's rank-0 segment "Limit  Wrapping up" in `--warning`
 *     ([SessionStatusline] builds it; session-statusline.tsx:67-77).
 *  2. This notice, in the inspector / telemetry sheet ("Session details"), where it REPLACES the
 *     generic rate-limit notice while the allowance covers the turn in flight
 *     (inspector.tsx:112-130, 520-523).
 *
 * The word "Wrapping up" is the signal; the warning outline only reinforces it (no violet, no
 * glow, no fill — globals.css 11993-12007). Announced politely (`role="status"`). Renders nothing
 * when no allowance is running (the normal case), and re-renders itself when the window's reset
 * passes with no further event.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WrapUpNotice(
    state: SessionView?,
    modifier: Modifier = Modifier,
    /** Draw the `.telemetry-empty` top rule + padding (its margin-top is the host's). */
    divider: Boolean = true,
    env: () -> ReadingEnv = ReadingEnv::current,
) {
    val tick = rememberWrapUpExpiry(state, env)
    val reading = remember(state, tick) { wrapUpReading(state, env()) } ?: return
    WrapUpNotice(reading, modifier, divider)
}

/** [WrapUpNotice] for an already-derived reading. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WrapUpNotice(reading: WrapUpReading, modifier: Modifier = Modifier, divider: Boolean = true) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val text = TextStyle(
        fontFamily = type.ui,
        fontSize = (0.65f * TetherTypography.SP_PER_REM).sp,
        lineHeight = 1.5.em,
        lineHeightStyle = CssLineHeight,
    )
    val line = t.line
    FlowRow(
        modifier
            .fillMaxWidth()
            .then(
                if (divider) {
                    Modifier
                        .drawBehind { drawRect(line, Offset.Zero, size.copy(height = 1.dp.toPx())) }
                        .padding(top = t.css.spaceLg)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy((0.44f * TetherTypography.SP_PER_REM).dp),
        verticalArrangement = Arrangement.spacedBy((0.2f * TetherTypography.SP_PER_REM).dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        WrapUpPill(reading.label, text)
        Text(reading.detail, style = text, color = t.muted)
    }
}

/**
 * `.wrap-up-pill`: the printed state word in a `--warning` 1px capsule, weight 560, never wrapping
 * (globals.css 11997-12007).
 */
@Composable
fun WrapUpPill(label: String = WRAP_UP_LABEL, style: TextStyle? = null) {
    val t = LocalTetherTokens.current
    val base = style ?: TextStyle(
        fontFamily = LocalTetherTypography.current.ui,
        fontSize = (0.65f * TetherTypography.SP_PER_REM).sp,
        lineHeight = 1.5.em,
        lineHeightStyle = CssLineHeight,
    )
    Text(
        label,
        style = base.copy(fontWeight = FontWeight(560)),
        color = t.warning,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .border(1.dp, t.warning, RoundedCornerShape(percent = 50))
            .padding(horizontal = (0.44f * TetherTypography.SP_PER_REM).dp + 1.dp, vertical = (0.02f * TetherTypography.SP_PER_REM).dp + 1.dp),
    )
}
