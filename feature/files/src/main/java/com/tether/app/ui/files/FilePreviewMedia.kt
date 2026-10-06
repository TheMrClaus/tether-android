package com.tether.app.ui.files

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tether.app.client.WorkspaceFileEntry
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.theme.LocalTetherTokens
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Whether a video draws its frame view and controller bar. Always on in the app; the screenshot
 * goldens switch it off, because a Robolectric window has no real surface and puts the controller's
 * own window at the bottom of the screen, which says nothing about the box the preview draws.
 */
internal val LocalVideoSurfaceEnabled = staticCompositionLocalOf { true }

/** Where an SVG is drawn: off the main thread in the app; the screenshot goldens draw in line, so no thread outlives its test. */
internal val LocalSvgDispatcher = staticCompositionLocalOf<CoroutineDispatcher> { Dispatchers.Default }

/**
 * The image preview, raster and SVG alike: `<img alt="Preview of {name}">` in a flex-centred pane
 * (globals.css 2878-2879: `max-width/height: 100%`, `object-fit: contain`, graphite, raised shadow).
 * Both sizes come from [PreviewSizing]: a picture's pixels are CSS px (= dp), scaled down to the
 * content box, never up; an SVG may fill the box by its viewBox's ratio.
 */
@Composable
internal fun ImagePreview(state: FileBrowserState, entry: WorkspaceFileEntry) {
    val image = state.image
    val svg = state.svg
    when {
        image != null -> BoxWithConstraints {
            val size = PreviewSizing.raster(image.width, image.height, maxWidth.value, maxHeight.value)
            PreviewPicture(image, entry.name, size)
        }
        svg != null -> SvgPreview(state, svg, entry)
        state.previewLoading -> StateBlock(null, "Opening image preview…", spinner = true)
    }
}

@Composable
private fun PreviewPicture(image: ImageBitmap, name: String, size: SizeDp) {
    val t = LocalTetherTokens.current
    Image(
        image,
        contentDescription = "Preview of ${SafeText.line(name)}",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .testTag(FileBrowserTags.Image)
            .size(size.width.dp, size.height.dp)
            .cssSurface(RoundedCornerShape(t.radiusMd), t.graphite, null, t.css.shadowRaised)
            .clip(RoundedCornerShape(t.radiusMd)),
    )
}

/**
 * An SVG drawn at the size it is shown at, into a bitmap no bigger than the content box (never at
 * the size the file claims). The box changing (fullscreen, rotation) draws it again; the picture
 * on screen stays until the new one lands.
 */
@Composable
private fun SvgPreview(state: FileBrowserState, svg: ParsedSvg, entry: WorkspaceFileEntry) {
    BoxWithConstraints {
        val size = PreviewSizing.svg(svg.intrinsic, maxWidth.value, maxHeight.value)
        val density = LocalDensity.current.density
        val widthPx = (size.width * density).roundToInt().coerceAtLeast(1)
        val heightPx = (size.height * density).roundToInt().coerceAtLeast(1)
        val dispatcher = LocalSvgDispatcher.current
        var drawn by remember(svg) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(svg, widthPx, heightPx) {
            val bitmap = withContext(dispatcher) { SvgImages.render(svg, widthPx, heightPx)?.asImageBitmap() }
            if (bitmap == null) state.svgDrawFailed(svg) else drawn = bitmap
        }
        val shown = drawn
        if (shown != null) {
            PreviewPicture(shown, entry.name, size)
        } else if (state.previewError.isEmpty()) {
            StateBlock(null, "Opening image preview…", spinner = true)
        }
    }
}

/**
 * The web's `<video controls preload="metadata">` (globals.css 2878-2880): the content width wide,
 * 150 dp tall until the frame size is known, then width / aspect capped at the content height, the
 * frame contain-fit and the rest letterboxed on graphite. Controls are the platform's
 * [MediaController] bar (as chat's clips); the header's fullscreen toggle is the video's fullscreen
 * button. A failed video keeps its empty box (the element stays on the web); the state says why.
 */
@Composable
internal fun VideoPreview(player: VideoPlayer, name: String) {
    val t = LocalTetherTokens.current
    val phase = player.phase
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val ready = phase as? VideoPhase.Ready
        val box = PreviewSizing.video(ready?.width, ready?.height, maxWidth.value, maxHeight.value)
        Box(
            Modifier
                .testTag(FileBrowserTags.Video)
                .fillMaxWidth()
                .height(box.height.dp)
                .cssSurface(RoundedCornerShape(t.radiusMd), t.graphite, null, t.css.shadowRaised)
                .clip(RoundedCornerShape(t.radiusMd))
                .semantics { contentDescription = "Preview of ${SafeText.line(name)}" },
            contentAlignment = Alignment.Center,
        ) {
            if (phase !is VideoPhase.Failed && LocalVideoSurfaceEnabled.current) VideoSurface(player, SizeDp(box.videoWidth, box.videoHeight), Modifier.fillMaxSize())
            if (phase is VideoPhase.Opening) {
                Box(Modifier.align(Alignment.Center).semantics { contentDescription = "Loading video" }) { Spinner(22.dp, t.faint) }
            }
        }
    }
}

/**
 * The controller draws in its own window, so the box's clip never reaches it and its dark backing would
 * square off the box's corners. The controller view clips itself to the BOX's rounded rectangle instead,
 * expressed in its own coordinates: it sits flush with the box's bottom, so the box's top edge is
 * `boxHeight - view.height` above the view's. A bar taller than most of a short (landscape phone) box thus
 * has its top corners cut by the box's top curves too, and a bar much shorter than the box only its bottom
 * ones (the web rounds all four corners with controls, globals.css 2878-2879). [boxHeight] is read at every
 * outline pass; call `invalidateOutline()` when it changes.
 */
internal fun roundBoxCorners(view: View, boxHeight: () -> Int, radiusPx: Float) {
    view.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val above = (boxHeight() - view.height).coerceAtLeast(0)
            outline.setRoundRect(0, -above, view.width, view.height, radiusPx)
        }
    }
    view.clipToOutline = true
}

/** The frame view, the controller bar and the app-stop pause, for one [player]. */
@Composable
private fun VideoSurface(player: VideoPlayer, clip: SizeDp, modifier: Modifier) {
    val context = LocalContext.current
    val radiusPx = with(LocalDensity.current) { LocalTetherTokens.current.radiusMd.toPx() }
    val host = remember(player) { VideoHost(context, player, radiusPx) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = context.findActivity()
    DisposableEffect(host, lifecycle) {
        // Out of sight (the app stopped): stop playing, keep the place. A rotation / resize stops the
        // activity too, but the video stays where it is and keeps playing into the new one.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) player.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            host.dispose()
        }
    }
    val ready = player.phase is VideoPhase.Ready
    val playing = player.playing
    val density = LocalDensity.current.density
    val clipWidthPx = (clip.width * density).roundToInt()
    val clipHeightPx = (clip.height * density).roundToInt()
    // The host fills the WHOLE video box (the controller anchors to it, so its bar spans the box and
    // meets the rounded corners); the picture is sized and centred inside it.
    AndroidView(factory = { host.frame }, modifier = modifier, update = { host.sync(ready, playing, clipWidthPx, clipHeightPx) })
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The video box's view: a frame that fills the whole box and is the [MediaController]'s anchor, with the
 * TextureView (not a SurfaceView: it follows the Compose clip, so the corners stay round) sized and
 * centred inside it for the clip's aspect. The controller is shown and held while the video is ready and
 * not playing (no autoplay), hides on the platform timeout while it plays, and a tap on the frame toggles
 * it. The screen stays on while it plays. After the anchor moves or resizes (a rotation, a resize) a
 * showing controller is hidden and shown again, so it takes the anchor's new place.
 */
internal class VideoHost(
    context: Context,
    private val player: VideoPlayer,
    radiusPx: Float,
    makeController: (Context) -> MediaController = { MediaController(it) },
) {
    val frame = FrameLayout(context)
    internal val texture = TextureView(context)
    private val controller = makeController(context)
    private var surface: Surface? = null
    private var ready = false
    private var playing = false
    private var shownAt: List<Int>? = null
    private val relayout = ViewTreeObserver.OnGlobalLayoutListener { frame.post { repositionIfMoved() } }

    init {
        frame.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        frame.viewTreeObserver.addOnGlobalLayoutListener(relayout)
        // The box's size is part of the controller's clip: a rotation or resize recomputes it.
        frame.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) controller.invalidateOutline()
        }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                val mine = Surface(texture)
                surface = mine
                player.attachSurface(mine)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                releaseSurface()
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
        controller.setMediaPlayer(player.control)
        roundBoxCorners(controller, { frame.height }, radiusPx)
        controller.setAnchorView(frame)
        controller.isEnabled = false
        frame.isClickable = true
        frame.setOnClickListener {
            if (!ready) return@setOnClickListener
            if (controller.isShowing) controller.hide() else showFor(playing)
        }
    }

    /** Called with the player's state and the clip's size (px) on every change. */
    fun sync(ready: Boolean, playing: Boolean, clipWidthPx: Int, clipHeightPx: Int) {
        this.ready = ready
        this.playing = playing
        sizeClip(clipWidthPx, clipHeightPx)
        frame.keepScreenOn = playing
        controller.isEnabled = ready
        if (!ready) {
            controller.hide()
            shownAt = null
            return
        }
        // Held while paused / ended; the platform's own timeout while playing.
        frame.post { if (this.ready && frame.isAttachedToWindow) showFor(this.playing) }
    }

    private fun sizeClip(widthPx: Int, heightPx: Int) {
        val params = texture.layoutParams as FrameLayout.LayoutParams
        val width = if (widthPx > 0) widthPx else ViewGroup.LayoutParams.MATCH_PARENT
        val height = if (heightPx > 0) heightPx else ViewGroup.LayoutParams.MATCH_PARENT
        if (params.width == width && params.height == height) return
        params.width = width
        params.height = height
        texture.layoutParams = params
    }

    private fun showFor(playing: Boolean) {
        if (!frame.isAttachedToWindow) return
        if (playing) controller.show() else controller.show(0)
        controller.invalidateOutline()
        shownAt = anchorBounds()
    }

    /** Where the anchor is on screen and how big: what the controller was positioned from. */
    private fun anchorBounds(): List<Int> {
        val at = IntArray(2)
        if (frame.isAttachedToWindow) frame.getLocationOnScreen(at)
        return listOf(at[0], at[1], frame.width, frame.height)
    }

    /** The anchor is laid out somewhere else than the controller was shown for: hide it and show it again there. */
    internal fun repositionIfMoved() {
        if (!ready || !frame.isAttachedToWindow || !controller.isShowing) return
        if (anchorBounds() == shownAt) return
        controller.hide()
        showFor(playing)
    }

    private fun releaseSurface() {
        surface?.let {
            player.detachSurface(it)
            it.release()
        }
        surface = null
    }

    fun dispose() {
        ready = false
        controller.hide()
        if (frame.viewTreeObserver.isAlive) frame.viewTreeObserver.removeOnGlobalLayoutListener(relayout)
        texture.surfaceTextureListener = null
        releaseSurface()
        frame.keepScreenOn = false
    }
}
