package com.tether.app.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import kotlinx.coroutines.delay

/**
 * Play / pause, the place in the clip and a seek bar, drawn in the caller's own window. The platform
 * [android.widget.MediaController] is a window of its own, and while it is held (a paused or ended clip)
 * it sat over a full-screen viewer and the viewer's Close could not be pressed on a device; nothing
 * here is a window, so everything around it stays reachable. Reads and drives [VideoPlayer.control].
 */
@Composable
fun VideoControlBar(player: VideoPlayer, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val control = player.control
    var position by remember(player) { mutableIntStateOf(control.currentPosition) }
    var duration by remember(player) { mutableIntStateOf(control.duration) }
    var dragging by remember(player) { mutableFloatStateOf(-1f) }
    val playing = player.playing
    // The place only moves while it plays: one read when it stops, a few a second while it goes.
    LaunchedEffect(player, playing) {
        position = control.currentPosition
        duration = control.duration
        while (playing) {
            delay(250)
            position = control.currentPosition
            duration = control.duration
        }
    }
    val shown = if (dragging >= 0f) (dragging * duration).toInt() else position
    Row(
        modifier
            .fillMaxWidth()
            .background(t.scrim.copy(alpha = 0.92f))
            .padding(horizontal = t.css.spaceSm),
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(t.radiusSm))
                .clickable(role = Role.Button) { if (playing) control.pause() else control.start() }
                .semantics { contentDescription = if (playing) "Pause" else "Play" }
                .testTag("video-play-pause"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (playing) TetherIcons.Pause else TetherIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Text(clock(shown), style = TextStyle(fontFamily = type.mono, fontSize = 11.52.sp), color = Color.White)
        SeekBar(
            fraction = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            buffered = (control.bufferPercentage / 100f).coerceIn(0f, 1f),
            onSeek = { f, done ->
                dragging = if (done) -1f else f
                if (done && duration > 0) {
                    control.seekTo((f * duration).toInt())
                    position = (f * duration).toInt()
                }
            },
            modifier = Modifier.weight(1f),
        )
        Text(clock(duration), style = TextStyle(fontFamily = type.mono, fontSize = 11.52.sp), color = Color.White)
    }
}

@Composable
private fun SeekBar(fraction: Float, buffered: Float, onSeek: (Float, Boolean) -> Unit, modifier: Modifier) {
    val t = LocalTetherTokens.current
    var width by remember { mutableIntStateOf(1) }
    val inset = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    // The track runs between two insets so the knob never spills over the time labels beside it.
    fun at(x: Float): Float = ((x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
    Box(
        modifier
            .height(44.dp)
            .semantics { contentDescription = "Seek" }
            .testTag("video-seek")
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { p -> onSeek(at(p.x), true) }
            }
            .pointerInput(Unit) {
                var last = 0f
                detectDragGestures(
                    onDragStart = { p: Offset -> last = at(p.x); onSeek(last, false) },
                    onDragEnd = { onSeek(last, true) },
                    onDragCancel = { onSeek(last, true) },
                ) { change, _ ->
                    last = at(change.position.x)
                    onSeek(last, false)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            val y = size.height / 2f
            val h = 3.dp.toPx()
            val r = androidx.compose.ui.geometry.CornerRadius(h / 2f)
            val track = (size.width - 2 * inset).coerceAtLeast(1f)
            drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(inset, y - h / 2f), androidx.compose.ui.geometry.Size(track, h), r)
            drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(inset, y - h / 2f), androidx.compose.ui.geometry.Size(track * buffered, h), r)
            drawRoundRect(t.accent, Offset(inset, y - h / 2f), androidx.compose.ui.geometry.Size(track * fraction, h), r)
            drawCircle(t.accent, 6.dp.toPx(), Offset(inset + track * fraction, y))
        }
    }
}

/** `m:ss` (`h:mm:ss` from an hour). */
internal fun clock(ms: Int): String {
    val total = (ms.coerceAtLeast(0) + 500) / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
