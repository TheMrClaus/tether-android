package com.tether.app.ui.statusline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.ui.components.tetherWell
import com.tether.app.ui.theme.CssLineHeight
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherTypography
import kotlinx.coroutines.delay

/**
 * ENTRY POINT — the session dial (components/session-dial.tsx): the header's elapsed clock,
 * "HH:MM:SS" since the session started, ticking once a second while the session is live and
 * frozen at its end time once it has ended.
 *
 * Where the web places it (workspace-header.tsx:116): first item of `.workspace-actions`, the
 * header's right-hand control rail, before the context gauge. **Hidden on phones** (globals.css
 * 3273-3275: `display: none` below 48rem — the composer's session-total line already reports
 * elapsed work); shown from 48rem (globals.css 4019). So the phone shell does not host it; the
 * expanded shell does, with `active = session.status != "exited"`, `stoppedAt = session.endedAt`.
 *
 * Look (the material layer's "readout plate", globals.css 11177-11190): a recessed well
 * (`--mineral-deep`, `--line-strong` edge, `--well` shadow) 1.75rem tall, mono 0.72rem / 620,
 * tabular; `--muted` at rest, `--ink` with a `--running` dot while live. Colour is never the only
 * signal: the ticking digits carry "live".
 *
 * Motion: the tick is a content update, not an animation, so it runs under reduced motion as the
 * web's does (`prefers-reduced-motion` only collapses CSS animations/transitions; the dial has
 * none). TalkBack reads "Session elapsed time HH:MM:SS" (not a live region: it would talk every
 * second).
 */
@Composable
fun SessionDial(
    startedAt: Long,
    stoppedAt: Long?,
    active: Boolean,
    modifier: Modifier = Modifier,
    clock: () -> Long = System::currentTimeMillis,
) {
    val t = LocalTetherTokens.current
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        now = clock()
        while (true) {
            // session-dial.tsx:10: a one-second interval while active.
            delay(1000)
            now = clock()
        }
    }
    val reading = dialReading(startedAt.toDouble(), stoppedAt?.toDouble(), active, now.toDouble())
    val ink = if (active) t.ink else t.muted
    Row(
        modifier
            .clearAndSetSemantics { contentDescription = reading.contentDescription }
            .tetherWell(t, RoundedCornerShape(t.radiusKey - 2.dp))
            .heightIn(min = DialHeight)
            .padding(horizontal = DialPaddingX),
        horizontalArrangement = Arrangement.spacedBy(DialGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `.session-elapsed i`: a 0.35rem dot in currentColor (`--running` while live).
        Box(Modifier.size(DialDot).background(if (active) t.running else ink, CircleShape))
        Text(reading.label, style = DialText, color = ink, maxLines = 1, softWrap = false)
    }
}

private val DialHeight = (1.75f * TetherTypography.SP_PER_REM).dp
private val DialPaddingX = (0.6f * TetherTypography.SP_PER_REM).dp
private val DialGap = (0.45f * TetherTypography.SP_PER_REM).dp
private val DialDot = (0.35f * TetherTypography.SP_PER_REM).dp

/** `.session-elapsed` mono, `:root .session-elapsed { font-size: 0.72rem; font-weight: 620 }`, 0.03em, tabular. */
private val DialText = TextStyle(
    fontFamily = JetBrainsMono,
    fontSize = (0.72f * TetherTypography.SP_PER_REM).sp,
    fontWeight = FontWeight(620),
    letterSpacing = 0.03.em,
    lineHeightStyle = CssLineHeight,
    fontFeatureSettings = TetherTypography.TABULAR_NUMS,
)
