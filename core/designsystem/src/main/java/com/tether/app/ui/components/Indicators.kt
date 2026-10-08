package com.tether.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/**
 * Sanctioned ambient motion (globals.css keyframes 3868-3877): 720ms ring spinners, the 1s Loader
 * rotation, the 2s waiting radar ping. Under reduced motion (the OS "remove animations" /
 * animator scale 0 → [LocalReducedMotion]) they render their static frame, as the web's global
 * `prefers-reduced-motion` rule (globals.css 4530-4539: 0.01ms, one iteration) leaves them.
 */

/** Status-dot diameter: `.status-line i` / `.status-badge i` are 0.4rem (globals.css:1573). */
val StatusDotSize: Dp = 6.4.dp

/**
 * `.activity-spinner`: a 1.5px `currentColor` ring whose top border is transparent, spinning
 * 720ms linear (globals.css 1602-1609 / 6504-6513). A transparent top border is the quadrant
 * between the two 45° corner joins, so the visible arc is 270° with butt ends on the diagonals.
 */
@Composable
fun SpinnerRing(color: Color, size: Dp = 10.4.dp, stroke: Dp = 1.5.dp, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val angle: Float = if (reduced) 0f else {
        val transition = rememberInfiniteTransition(label = "spinner")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(720, easing = LinearEasing)),
            label = "spinnerAngle",
        )
        value
    }
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val strokePx = stroke.toPx()
        val inset = strokePx / 2f
        drawArc(
            color = color,
            // Compose angles: 0° = 3 o'clock, clockwise. The top quadrant (225°..315°) is the gap.
            startAngle = 315f + angle,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(this.size.width - strokePx, this.size.height - strokePx),
            style = Stroke(width = strokePx),
        )
    }
}

/** The web's `.spin` LoaderCircle (900ms a turn); still under reduced motion. */
@Composable
fun Spinner(size: Dp, tint: Color) {
    val reduced = LocalReducedMotion.current
    val angle = if (reduced) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "spin")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spin").value
    }
    Icon(TetherIcons.LoaderCircle, contentDescription = null, tint = tint, modifier = Modifier.size(size).rotate(angle))
}

/** A lucide icon spun 1s/turn (`.chat-spin`, globals.css:6852). */
@Composable
fun SpinningIcon(icon: ImageVector, tint: Color, size: Dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val reduced = LocalReducedMotion.current
    val angle: Float = if (reduced) 0f else {
        val transition = rememberInfiniteTransition(label = "loader")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
            label = "loaderAngle",
        )
        value
    }
    Icon(
        icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = angle },
    )
}

/** Plain status dot (`.status-line i`: 0.4rem, currentColor). Never the only status signal. */
@Composable
fun StatusDot(color: Color, size: Dp = StatusDotSize, modifier: Modifier = Modifier) {
    // `.status-line i` / `.sidebar-footer i`: `border-radius: 50%` on a box that can flex-shrink (width only,
    // the height stays): an oval once it is laid out narrower than it is tall (ta-z4c1).
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        if (this.size.width < this.size.height) drawOval(color, size = this.size) else drawCircle(color)
    }
}

/**
 * The waiting dot and its quiet radar ping (globals.css 1588-1593 + 3874-3877):
 * `box-shadow: 0 0 0 0 var(--focus-glow)` → `0 0 0 0.45rem transparent` over 70% of a 2s cycle
 * with `--ease-out`, then held clear until the cycle restarts. The ring is a box-shadow, so it
 * never takes layout space: the composable is the dot's own size and the ring overflows it.
 * Reduced motion: the dot alone (the animation's end state is a clear ring).
 */
@Composable
fun WaitingPingDot(color: Color, dotSize: Dp = StatusDotSize, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val glow = t.focusGlow
    val ease = t.css.easeOut.toEasing()
    val progress: Float = if (reduced) 1f else {
        val transition = rememberInfiniteTransition(label = "ping")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = PingCycleMs
                    0f at 0 using ease
                    1f at (PingCycleMs * 0.7f).toInt()
                    1f at PingCycleMs
                },
                RepeatMode.Restart,
            ),
            label = "pingProgress",
        )
        value
    }
    Box(
        modifier
            .size(dotSize)
            .clearAndSetSemantics { }
            .drawBehind {
                if (progress < 1f) {
                    val spread = PingSpread.toPx() * progress
                    drawCircle(
                        color = glow.copy(alpha = glow.alpha * (1f - progress)),
                        radius = size.minDimension / 2f + spread,
                    )
                }
                // Flex-shrunk narrower than tall: the box's `border-radius: 50%` is an oval (ta-z4c1).
                if (this.size.width < this.size.height) drawOval(color, size = this.size) else drawCircle(color)
            },
    )
}

/** `animation: waiting-ping 2s` (globals.css:1592). */
const val PingCycleMs: Int = 2000

/** The ping ring's final spread, `0.45rem` (globals.css:3876). */
val PingSpread: Dp = 7.2.dp

/** A session status as the web names it (`status-${session.status}`, globals.css 1580-1599). */
enum class StatusTone { Active, Waiting, Ready, Exited, History }

/** A wire `session.status` string → its [StatusTone] (unknown statuses print faint, like the web). */
fun statusToneOf(status: String): StatusTone = when (status) {
    "active" -> StatusTone.Active
    "waiting" -> StatusTone.Waiting
    "ready" -> StatusTone.Ready
    "exited" -> StatusTone.Exited
    else -> StatusTone.History
}

/**
 * Status as an etched pill: dot + printed word, never the dot alone (globals.css 11161-11175;
 * Studio: studio.css:354). Active carries the spinner, Waiting the pinging violet dot (violet
 * = waiting for the operator). The accessible name is [label] as written.
 */
@Composable
fun TetherStatusPill(label: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val ink = statusColor(tone)
    val waiting = tone == StatusTone.Waiting
    val shape = RoundedCornerShape(percent = 50)
    val role = type.statusLabel
    val surface = when {
        // `.status-badge.status-waiting` (0,3,0) out-ranks Studio's flat badge for the fill; the
        // border stays 0-width in Studio.
        waiting -> Modifier.cssSurface(
            shape, t.violetWash,
            null,
            emptyList(),
        )
        else -> Modifier.cssSurface(shape, t.graphiteRaised)
    }
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) { contentDescription = label }
            .then(Modifier)
            .then(surface)
            .then(
                Modifier.padding(horizontal = 8.8.dp, vertical = 4.8.dp),
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.6.dp),
    ) {
        when (tone) {
            StatusTone.Active -> SpinnerRing(ink, size = 10.4.dp)
            StatusTone.Waiting -> WaitingPingDot(ink)
            else -> StatusDot(ink)
        }
        Text(role.format(label), style = role.style, color = ink, maxLines = 1, modifier = Modifier.clearAndSetSemantics { })
    }
}

/** `.status-active` running, `.status-waiting` violet, the rest faint. */
@Composable
fun statusColor(tone: StatusTone): Color {
    val t = LocalTetherTokens.current
    return when (tone) {
        StatusTone.Active -> t.running
        StatusTone.Waiting -> t.violet
        else -> t.faint
    }
}
