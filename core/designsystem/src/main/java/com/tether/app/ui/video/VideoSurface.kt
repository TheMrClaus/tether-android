package com.tether.app.ui.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.MediaController
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tether.app.ui.theme.LocalTetherTokens
import kotlin.math.roundToInt

/**
 * Whether a video draws its frame view and controller bar. Always on in the app; the screenshot
 * goldens switch it off, because a Robolectric window has no real surface and puts the controller's
 * own window at the bottom of the screen, which says nothing about the box the preview draws.
 */
val LocalVideoSurfaceEnabled = staticCompositionLocalOf { true }

/**
 * The controller draws in its own window, so the box's clip never reaches it and its dark backing would
 * square off the box's corners. The controller view clips itself to the BOX's rounded rectangle instead,
 * expressed in its own coordinates: it sits flush with the box's bottom, so the box's top edge is
 * `boxHeight - view.height` above the view's. A bar taller than most of a short (landscape phone) box thus
 * has its top corners cut by the box's top curves too, and a bar much shorter than the box only its bottom
 * ones (the web rounds all four corners with controls, globals.css 2878-2879). [boxHeight] is read at every
 * outline pass; call `invalidateOutline()` when it changes.
 */
fun roundBoxCorners(view: View, boxHeight: () -> Int, radiusPx: Float) {
    view.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val above = (boxHeight() - view.height).coerceAtLeast(0)
            outline.setRoundRect(0, -above, view.width, view.height, radiusPx)
        }
    }
    view.clipToOutline = true
}

/**
 * The frame view, the controller bar and the app-stop pause, for one [player]. [clipWidth] x
 * [clipHeight] is the picture's size inside the box [modifier] gives (0 = fill it); the controller
 * is clipped to the box's corners, [cornerRadius] (default the skin's radius-md) round, 0 for none.
 * The controller is its own window, which the box's clip never reaches: a caller whose box can be
 * partly scrolled away passes [controllerAllowed] false then, and the bar is hidden. [onFrame] is told
 * `true` when a frame is on the surface and `false` when the surface is new or gone and none is yet.
 */
@Composable
fun VideoSurface(
    player: VideoPlayer,
    clipWidth: Dp,
    clipHeight: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp? = null,
    controllerAllowed: Boolean = true,
    onFrame: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val radiusPx = with(LocalDensity.current) { (cornerRadius ?: LocalTetherTokens.current.radiusMd).toPx() }
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
    var framed by remember(player) { mutableStateOf(false) }
    val still = player.still
    val clipWidthPx = (clipWidth.value * density).roundToInt()
    val clipHeightPx = (clipHeight.value * density).roundToInt()
    // The host fills the WHOLE video box (the controller anchors to it, so its bar spans the box and
    // meets the rounded corners); the picture is sized and centred inside it. The last picture the previous
    // surface showed is drawn over it until the new surface draws its own.
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { host.frame },
            modifier = Modifier.fillMaxSize(),
            update = {
                host.onFrame = { drawn ->
                    framed = drawn
                    onFrame?.invoke(drawn)
                }
                host.sync(ready, playing, clipWidthPx, clipHeightPx, controllerAllowed)
            },
        )
        // Above the surface, gone at its first frame (a paused or ended player may paint none onto a new one).
        if (still != null && !framed) {
            val image = remember(still) { still.asImageBitmap() }
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = if (clipWidthPx > 0 && clipHeightPx > 0) Modifier.size(clipWidth, clipHeight) else Modifier.fillMaxSize(),
            )
        }
    }
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
class VideoHost(
    context: Context,
    private val player: VideoPlayer,
    radiusPx: Float,
    /** The picture on a texture, taken while it can still be read (a seam for tests). */
    private val grab: (TextureView) -> Bitmap? = { if (it.isAvailable) it.bitmap else null },
    makeController: (Context) -> MediaController = { MediaController(it) },
) {
    val frame = FrameLayout(context)
    val texture = TextureView(context)
    private val controller = makeController(context)
    private var surface: Surface? = null

    /**
     * The texture the surface was made from. The view is told not to release it ([TextureView.SurfaceTextureListener]
     * answers false): the player may still be drawing into it, and it is released once the player let go ([releaseSurface]).
     */
    private var ownedTexture: SurfaceTexture? = null
    private var disposed = false
    private var framed = false

    /** The still of the surface now (or last) in use has been taken: a later capture would read a dead layer. */
    private var stillTaken = false

    /** Told whether a frame is drawn on the current surface (see [VideoSurface]). */
    var onFrame: ((Boolean) -> Unit)? = null
    private var allowed = true
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
        // The picture can only be read while the texture still has its hardware layer: detaching destroys the
        // layer BEFORE the surface listener's onSurfaceTextureDestroyed runs (a bitmap taken there is blank).
        // This listener is called before the view's own detach work.
        texture.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = captureStill()
        })
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                ownedTexture = texture
                if (disposed) return
                val mine = Surface(texture)
                surface = mine
                stillTaken = false
                setFramed(false)
                player.attachSurface(mine)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                captureStill()
                releaseSurface()
                // The texture is ours to release, later: the player lets go of it first, which can wait on its source.
                return false
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = setFramed(true)
        }
        controller.setMediaPlayer(player.control)
        roundBoxCorners(controller, { frame.height }, radiusPx)
        controller.setAnchorView(frame)
        controller.isEnabled = false
        frame.isClickable = true
        frame.setOnClickListener {
            if (!ready || !allowed) return@setOnClickListener
            if (controller.isShowing) controller.hide() else showFor(playing)
        }
    }

    /** Called with the player's state and the clip's size (px) on every change. */
    fun sync(ready: Boolean, playing: Boolean, clipWidthPx: Int, clipHeightPx: Int, controllerAllowed: Boolean = true) {
        this.ready = ready
        this.allowed = controllerAllowed
        this.playing = playing
        sizeClip(clipWidthPx, clipHeightPx)
        frame.keepScreenOn = playing
        controller.isEnabled = ready
        if (!ready || !controllerAllowed) {
            controller.hide()
            shownAt = null
            return
        }
        // Held while paused / ended; the platform's own timeout while playing.
        frame.post { if (this.ready && this.allowed && frame.isAttachedToWindow) showFor(this.playing) }
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
    fun repositionIfMoved() {
        if (!ready || !allowed || !frame.isAttachedToWindow || !controller.isShowing) return
        if (anchorBounds() == shownAt) return
        controller.hide()
        showFor(playing)
    }

    /** The picture on screen, kept with the player before its surface goes (see [VideoPlayer.still]). */
    private fun captureStill() {
        if (!framed || stillTaken) return
        stillTaken = true
        try {
            // A texture whose layer is already gone yields a transparent bitmap: not a picture, not kept.
            grab(texture)?.takeIf { !isBlank(it) }?.let(player::keepStill)
        } catch (_: RuntimeException) {
        }
    }

    private fun setFramed(value: Boolean) {
        if (framed == value) return
        framed = value
        if (value) {
            stillTaken = false
            player.keepStill(null)
        }
        onFrame?.invoke(value)
    }

    private fun releaseSurface() {
        setFramed(false)
        val mine = surface
        val texture = ownedTexture
        surface = null
        ownedTexture = null
        if (mine == null) {
            texture?.release()
            return
        }
        // The player lets go of the surface when it can (it may be waiting on its source: the main thread does not),
        // and only then are the surface and its texture released.
        player.detachSurface(mine) {
            mine.release()
            texture?.release()
        }
    }

    fun dispose() {
        captureStill()
        onFrame = null
        ready = false
        controller.hide()
        if (frame.viewTreeObserver.isAlive) frame.viewTreeObserver.removeOnGlobalLayoutListener(relayout)
        // The listener stays: a texture that is destroyed later is released through it (a disposed host attaches nothing).
        disposed = true
        releaseSurface()
        frame.keepScreenOn = false
    }
}

/** True when a handful of sampled pixels are all fully transparent (nothing was drawn into it). */
internal fun isBlank(bitmap: Bitmap): Boolean {
    if (bitmap.width <= 0 || bitmap.height <= 0) return true
    for (iy in 0..4) {
        for (ix in 0..4) {
            val x = (bitmap.width - 1) * ix / 4
            val y = (bitmap.height - 1) * iy / 4
            if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) != 0) return false
        }
    }
    return true
}
