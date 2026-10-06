package com.tether.app.ui.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tether.app.ui.components.Spinner
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.video.LocalVideoSurfaceEnabled
import com.tether.app.ui.video.VideoSurface

/*
 * ta-coik.68: tool-media video as the web draws it (chat-tool-render.tsx:122-141 inline,
 * :290-366 the lightbox). Natively nothing is fetched or decoded until the user taps play (the
 * decoder is made on that first tap, never on render); the inline box and the lightbox each have
 * their own MediaPlayer over the one download (ToolClips.kt), so both can play at once, as the
 * web's two <video> elements do.
 */

/** The inline box's size (`.chat-tool-media-item`, globals.css:4501-4507) and the lightbox's fit. */
internal object VideoSizing {
    /** A `<video>` with no metadata yet is the browser's 300 x 150 box. */
    const val DEFAULT_WIDTH = 300f
    const val DEFAULT_HEIGHT = 150f

    /** `.chat-tool-media-item { max-height: 320px }` */
    const val MAX_HEIGHT = 320f

    data class Box(val width: Float, val height: Float)

    /**
     * Before the size is known: [DEFAULT_WIDTH] x [DEFAULT_HEIGHT], or the row's width at 2:1 when the
     * row is narrower. Once known: the clip's own pixels (CSS px = dp), scaled down to [rowWidth]
     * and [MAX_HEIGHT] with the ratio kept, never up.
     */
    fun inline(known: Pair<Int, Int>?, rowWidth: Float): Box {
        if (known == null || known.first <= 0 || known.second <= 0) {
            return if (rowWidth.isFinite() && rowWidth < DEFAULT_WIDTH) Box(rowWidth, rowWidth / 2f) else Box(DEFAULT_WIDTH, DEFAULT_HEIGHT)
        }
        return fit(known, rowWidth, MAX_HEIGHT)
    }

    /** The lightbox stage: `max-width/height: 100%`, contain, never scaled up. */
    fun fit(known: Pair<Int, Int>, boxWidth: Float, boxHeight: Float): Box {
        val (w, h) = known
        if (w <= 0 || h <= 0 || boxWidth <= 0f || boxHeight <= 0f) return Box(0f, 0f)
        val scale = minOf(1f, boxWidth / w, boxHeight / h)
        return Box(w * scale, h * scale)
    }
}

/**
 * The inline clip: `.chat-tool-media-frame` with `<video controls>` and its expand key. Idle it is a
 * box with a play disc (no network, no decoder); a tap plays it here; the key opens the viewer.
 */
@Composable
internal fun InlineVideo(item: ToolMediaItem, onOpen: () -> Unit) {
    val t = LocalTetherTokens.current
    val registry = LocalToolClips.current
    val clip = remember(registry, item.src) { registry?.clip(item.src) }
    val view = clip?.inline
    val state = view?.state ?: ClipState.Idle
    val ready = state as? ClipState.Ready
    LaunchedEffect(clip, ready) { if (clip != null && ready != null) clip.noteSize(ready.width, ready.height) }
    BoxWithConstraints {
        val box = VideoSizing.inline(clip?.knownSize, maxWidth.value)
        val shape = RoundedCornerShape(t.radiusMd)
        val error = state is ClipState.Error
        val opaqueScrim = t.scrim.copy(alpha = 1f)
        Box(
            Modifier
                .testTag("tool-media-video")
                .width(box.width.dp)
                .then(if (error) Modifier.heightIn(min = box.height.dp) else Modifier.height(box.height.dp))
                .background(t.graphite, shape)
                // No frame is drawn (idle, loading, error): graphite is white in the light skin, so an edge.
                .then(if (ready == null) Modifier.border(1.dp, t.line, shape) else Modifier)
                .clip(shape),
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .then(
                        if (state is ClipState.Idle && view != null) {
                            Modifier
                                .clickable(role = Role.Button, onClickLabel = "Play video") { view.play() }
                                .semantics { contentDescription = "Play video" }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val player = view?.player
                if (player != null && state !is ClipState.Error && LocalVideoSurfaceEnabled.current) {
                    VideoSurface(player, box.width.dp, box.height.dp, Modifier.fillMaxSize())
                }
                when (state) {
                    ClipState.Idle -> Box(Modifier.size(44.dp).background(opaqueScrim, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(TetherIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    ClipState.Loading -> Box(Modifier.semantics { contentDescription = "Loading video" }) { Spinner(22.dp, t.faint) }
                    is ClipState.Ready -> if (player?.buffering == true) Spinner(22.dp, t.faint)
                    is ClipState.Error -> VideoUnavailable(state.kind, Modifier, plain = true)
                }
            }
            // The expand key is always drawn (touch has no hover); 44dp to hit, 28dp to see.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(44.dp)
                    .clickable(role = Role.Button, onClick = onOpen)
                    .semantics { contentDescription = "View video full size" }
                    .testTag("tool-media-expand"),
                contentAlignment = Alignment.TopEnd,
            ) {
                Box(
                    Modifier
                        .padding(t.css.spaceXs)
                        .size(28.dp)
                        .background(opaqueScrim, RoundedCornerShape(t.radiusSm))
                        .border(1.dp, t.line, RoundedCornerShape(t.radiusSm)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(TetherIcons.Maximize2, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** The failure copy for a clip ([MediaVideo.Blocked] gets its two lines). */
@Composable
internal fun VideoUnavailable(kind: MediaVideo, clickable: Modifier, plain: Boolean) = when (kind) {
    MediaVideo.Blocked -> MediaUnavailable(MediaCopy.BLOCKED, clickable, detail = MediaCopy.BLOCKED_DETAIL, icon = TetherIcons.FileVideo2, plain = plain)
    MediaVideo.TooLarge -> MediaUnavailable(MediaCopy.VIDEO_TOO_LARGE, clickable, icon = TetherIcons.FileVideo2, plain = plain)
    else -> MediaUnavailable(MediaCopy.VIDEO_UNAVAILABLE, clickable, icon = TetherIcons.FileVideo2, plain = plain)
}

/**
 * The lightbox's clip (`<video controls autoPlay>`, chat-tool-render.tsx:346): its own player over
 * the same download as the inline box, autoplaying, contain-fit to the stage with no radius and no
 * fill. It outlives a rotation (the registry owns it) and is released when the viewer goes away
 * for good: closed, or another item.
 */
@Composable
internal fun ViewerVideo(item: ToolMediaItem) {
    val t = LocalTetherTokens.current
    val registry = LocalToolClips.current
    val clip = remember(registry, item.src) { registry?.clip(item.src) }
    if (clip == null) {
        MediaUnavailable(MediaCopy.VIDEO_UNAVAILABLE, Modifier)
        return
    }
    val view = clip.viewer
    val activity = LocalContext.current.findHostActivity()
    LaunchedEffect(clip) { view.play() }
    DisposableEffect(clip) {
        onDispose { if (activity?.isChangingConfigurations != true) view.release() }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val state = view.state
        if (state is ClipState.Error) {
            VideoUnavailable(state.kind, Modifier, plain = false)
            return@BoxWithConstraints
        }
        val ready = state as? ClipState.Ready
        val fit = ready?.takeIf { it.width > 0 && it.height > 0 }?.let { VideoSizing.fit(it.width to it.height, maxWidth.value, maxHeight.value) }
        val player = view.player
        if (player != null && LocalVideoSurfaceEnabled.current) {
            VideoSurface(
                player,
                fit?.width?.dp ?: 0.dp,
                fit?.height?.dp ?: 0.dp,
                if (fit != null) Modifier.size(fit.width.dp, fit.height.dp) else Modifier.fillMaxSize(),
                cornerRadius = 0.dp,
            )
        }
        if (ready == null) SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 18.dp, contentDescription = "Loading video")
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
