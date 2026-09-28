package com.tether.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tether.app.client.ToolMediaResult
import com.tether.app.client.ToolMediaSource
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * T6.2: tool-result / attachment / spawned-run media (chat-tool-render.tsx `ToolMedia` +
 * `MediaLightbox`). The web hands the journaled URL to an <img>/<video>; natively the bytes come
 * over [ToolMediaSource] (the paired credential, the paired origin only, no redirects), images are
 * decoded under hard bounds, and a clip is downloaded (bounded) before it plays in a platform
 * VideoView. No WebView anywhere.
 */

/** What loading one picture came to. */
@Immutable
sealed interface MediaImage {
    data class Ok(val bitmap: ImageBitmap) : MediaImage
    data object TooLarge : MediaImage
    data object Failed : MediaImage
}

/** What loading one clip came to: a local, bounded copy to play. */
@Immutable
sealed interface MediaVideo {
    data class Ok(val file: File) : MediaVideo
    data object TooLarge : MediaVideo
    data object Failed : MediaVideo
}

/** The seam the transcript loads media through (a fake in tests and goldens). */
interface ToolMediaLoader {
    suspend fun image(item: ToolMediaItem): MediaImage
    suspend fun video(item: ToolMediaItem): MediaVideo
}

/** Null = no loader (previews): media tiles stay in their placeholder state. */
val LocalToolMediaLoader = staticCompositionLocalOf<ToolMediaLoader?> { null }

/**
 * Bounds for an in-transcript picture. Native-only: the web's <img> has no cap, but a phone must
 * never let one tool result exhaust memory. Same three-way bound as T11.1's BoundedImages, sized
 * for a transcript that can hold many pictures (the preview shows at most 320dp tall).
 */
object MediaLimits {
    /** Encoded bytes fetched for one picture (declared and streamed). */
    const val MAX_IMAGE_BYTES: Long = 32L * 1024L * 1024L

    /** Decoded pictures are sampled down to at most this many pixels a side… */
    const val MAX_IMAGE_SIDE: Int = 2048

    /** …and at most this many bytes. */
    const val MAX_DECODED_BYTES: Long = 16L * 1024L * 1024L

    /** A header claiming more pixels than this is refused outright (a decompression bomb). */
    const val MAX_IMAGE_PIXELS: Long = 100_000_000L

    /** A clip is downloaded before it plays: the server's own cap. */
    const val MAX_VIDEO_BYTES: Long = ToolMediaSource.MAX_MEDIA_BYTES

    /** Decoded pictures kept in memory across scrolling (content-addressed, so never stale). */
    const val CACHE_BYTES: Int = 24 * 1024 * 1024

    /** The image types a `data:` URI may carry (lib/tool-media-store.mjs IMAGE_MEDIA_TYPES). */
    val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
}

/** Bounded bitmap decoding from bytes (the T11.1 BoundedImages plan, applied to a byte array). */
object BoundedMediaDecoder {
    fun decode(
        bytes: ByteArray,
        decode: (ByteArray, BitmapFactory.Options) -> Bitmap? = { b, o -> BitmapFactory.decodeByteArray(b, 0, b.size, o) },
    ): MediaImage = try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        decode(bytes, options)
        val sample = plan(options.outWidth, options.outHeight, if (options.outConfig == Bitmap.Config.RGBA_F16) 8 else 4)
        when {
            options.outWidth <= 0 || options.outHeight <= 0 -> MediaImage.Failed
            sample == null -> MediaImage.TooLarge
            else -> {
                options.inJustDecodeBounds = false
                options.inSampleSize = sample
                val bitmap = decode(bytes, options)
                when {
                    bitmap == null -> MediaImage.Failed
                    bitmap.allocationByteCount > MediaLimits.MAX_DECODED_BYTES -> {
                        bitmap.recycle()
                        MediaImage.TooLarge
                    }
                    else -> MediaImage.Ok(bitmap.asImageBitmap())
                }
            }
        }
    } catch (_: OutOfMemoryError) {
        MediaImage.TooLarge
    } catch (_: RuntimeException) {
        MediaImage.Failed
    }

    /** The power-of-two sample that fits both caps, or null past [MediaLimits.MAX_IMAGE_PIXELS]. */
    fun plan(width: Int, height: Int, bytesPerPixel: Int): Int? {
        if (width <= 0 || height <= 0) return 1
        if (width.toLong() * height > MediaLimits.MAX_IMAGE_PIXELS) return null
        var sample = 1
        while (true) {
            val w = (width / sample).toLong()
            val h = (height / sample).toLong()
            if (w <= MediaLimits.MAX_IMAGE_SIDE && h <= MediaLimits.MAX_IMAGE_SIDE && w * h * bytesPerPixel <= MediaLimits.MAX_DECODED_BYTES) return sample
            sample *= 2
        }
    }
}

/** `data:<type>;base64,<data>` → (type, base64 payload), or null. */
internal fun parseDataUri(src: String): Pair<String, String>? {
    if (!src.startsWith("data:")) return null
    val comma = src.indexOf(',')
    if (comma < 0) return null
    val meta = src.substring(5, comma)
    if (!meta.endsWith(";base64")) return null
    return meta.removeSuffix(";base64") to src.substring(comma + 1)
}

/**
 * The production [ToolMediaLoader]: `data:` pictures (legacy base64 tool results) decode locally,
 * `/api/tool-media/…` ones come over [source]; decoded pictures are cached by URL (the URL names
 * the content's sha256), clips under [cacheDir] by the same content address.
 */
class ToolMediaRepository(private val source: ToolMediaSource, private val cacheDir: File) : ToolMediaLoader {
    private val cache = object : LruCache<String, ImageBitmap>(MediaLimits.CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    override suspend fun image(item: ToolMediaItem): MediaImage {
        if (item.isVideo) return MediaImage.Failed
        cache.get(item.src)?.let { return MediaImage.Ok(it) }
        val result = withContext(Dispatchers.IO) { loadImage(item.src) }
        if (result is MediaImage.Ok) cache.put(item.src, result.bitmap)
        return result
    }

    private suspend fun loadImage(src: String): MediaImage {
        val data = parseDataUri(src)
        if (data != null) {
            if (data.first !in MediaLimits.IMAGE_TYPES) return MediaImage.Failed
            // Base64 is 4 characters per 3 bytes: refuse before decoding what could not fit.
            if (data.second.length.toLong() / 4 * 3 > MediaLimits.MAX_IMAGE_BYTES) return MediaImage.TooLarge
            val bytes = try {
                Base64.decode(data.second, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                return MediaImage.Failed
            }
            return BoundedMediaDecoder.decode(bytes)
        }
        val ext = ToolMediaSource.extensionOf(src) ?: return MediaImage.Failed
        if (ext == "mp4") return MediaImage.Failed
        val sink = ByteArrayOutputStream()
        return when (source.fetch(src, MediaLimits.MAX_IMAGE_BYTES, sink)) {
            is ToolMediaResult.Ok -> BoundedMediaDecoder.decode(sink.toByteArray())
            ToolMediaResult.TooLarge -> MediaImage.TooLarge
            else -> MediaImage.Failed
        }
    }

    override suspend fun video(item: ToolMediaItem): MediaVideo = withContext(Dispatchers.IO) {
        val ext = ToolMediaSource.extensionOf(item.src)
        if (ext != "mp4") return@withContext MediaVideo.Failed
        val dir = File(cacheDir, "tool-media").apply { mkdirs() }
        val name = item.src.substringAfterLast('/')
        val file = File(dir, name)
        if (file.isFile && file.length() > 0) return@withContext MediaVideo.Ok(file)
        val part = File(dir, "$name.part")
        val result = try {
            FileOutputStream(part).use { out -> source.fetch(item.src, MediaLimits.MAX_VIDEO_BYTES, out) }
        } catch (_: java.io.IOException) {
            ToolMediaResult.Failed()
        }
        when (result) {
            is ToolMediaResult.Ok -> if (part.renameTo(file)) MediaVideo.Ok(file) else MediaVideo.Failed.also { part.delete() }
            ToolMediaResult.TooLarge -> MediaVideo.TooLarge.also { part.delete() }
            else -> MediaVideo.Failed.also { part.delete() }
        }
    }
}

// --- The inline row ---------------------------------------------------------------------------

/**
 * `.chat-tool-media` (globals.css:5108-5131): a wrapping row, `space-sm` gaps, padded `space-sm
 * space-md` under a `--line` rule ([bare]: the bubble-attachment variant, no padding or rule).
 * Each picture shows at its own size up to the row width and 320dp tall, `--radius-md` corners;
 * a tap opens the viewer. A clip shows a play tile (the web's inline <video controls> has no
 * native twin without a player per row); a tap opens the viewer, which plays it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolMediaRow(items: List<ToolMediaItem>, modifier: Modifier = Modifier, bare: Boolean = false) {
    if (items.isEmpty()) return
    val t = LocalTetherTokens.current
    var openIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val line = t.line
    FlowRow(
        modifier
            .fillMaxWidth()
            .then(
                if (bare) {
                    Modifier
                } else {
                    Modifier
                        .drawBehind { drawRect(line, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
                        .padding(top = 1.dp)
                        .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm)
                },
            )
            .testTag("tool-media"),
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        items.forEachIndexed { index, item ->
            MediaTile(item, onOpen = { openIndex = index })
        }
    }
    openIndex?.let { index ->
        if (index in items.indices) {
            MediaLightbox(items, index, onIndexChange = { openIndex = it }, onClose = { openIndex = null })
        }
    }
}

@Composable
private fun MediaTile(item: ToolMediaItem, onOpen: () -> Unit) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    val label = if (item.isVideo) "View video full size" else "View image full size"
    val clickable = Modifier
        .clip(shape)
        .clickable(role = Role.Button, onClickLabel = label, onClick = onOpen)
        .semantics { contentDescription = label }
    if (item.isVideo) {
        Box(
            clickable.size(width = 240.dp, height = 135.dp).background(t.graphite),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(44.dp).background(t.scrim, CircleShape), contentAlignment = Alignment.Center) {
                Icon(TetherIcons.Play, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp))
            }
            Icon(
                TetherIcons.Maximize2,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(t.css.spaceXs).size(14.dp),
            )
        }
        return
    }
    val image = rememberMediaImage(item)
    when (val state = image) {
        is MediaImage.Ok -> {
            // CSS px = dp: a picture shows at its own size, capped at the row width and 320dp.
            val w = state.bitmap.width.dp
            val h = state.bitmap.height.dp
            Image(
                bitmap = state.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                // `max-width: 100%; max-height: 320px; object-fit: contain` at the picture's own ratio.
                modifier = clickable.widthIn(max = w).heightIn(max = 320.dp).aspectRatio(w.value / h.value),
            )
        }
        null -> Box(clickable.size(44.dp).background(t.tintXs), contentAlignment = Alignment.Center) {
            SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 14.dp)
        }
        else -> MediaUnavailable(if (state == MediaImage.TooLarge) "Image too large to show" else "Image unavailable", clickable = Modifier.clip(shape))
    }
}

@Composable
private fun MediaUnavailable(text: String, clickable: Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        clickable.background(t.tintXs).padding(horizontal = t.css.spaceSm, vertical = t.css.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Icon(TetherIcons.FileImage, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
        Text(text, style = TextStyle(fontFamily = type.ui, fontSize = 11.52.sp), color = t.muted)
    }
}

/** Null while loading; the loader's answer after. No loader (a preview) stays null. */
@Composable
private fun rememberMediaImage(item: ToolMediaItem): MediaImage? {
    val loader = LocalToolMediaLoader.current
    val state by produceState<MediaImage?>(initialValue = null, item.src, loader) {
        value = loader?.image(item)
    }
    return state
}

// --- The viewer --------------------------------------------------------------------------------

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private fun clampScale(value: Float) = min(MAX_SCALE, max(MIN_SCALE, value))

/** "100%" (`Math.round(scale * 100)%`). */
internal fun zoomLabel(scale: Float): String = "${(scale * 100).roundToInt()}%"

/**
 * `MediaLightbox` (chat-tool-render.tsx:179-368, globals.css:5160-5237): a full-screen `--graphite`
 * viewer. Toolbar (images): zoom out / % / zoom in / reset, then close; prev/next while there is
 * somewhere to go. Pinch zooms (1×–6×), a drag pans once zoomed, a double tap toggles 1× / 2×, a
 * tap on the empty stage or Back closes. Changing item resets the zoom.
 */
@Composable
internal fun MediaLightbox(items: List<ToolMediaItem>, index: Int, onIndexChange: (Int) -> Unit, onClose: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val item = items[index]
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    val canPrev = index > 0
    val canNext = index < items.size - 1
    fun zoomBy(factor: Float) {
        scale = clampScale(scale * factor)
        if (scale == MIN_SCALE) offset = Offset.Zero
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(t.graphite)
                .semantics { contentDescription = if (item.isVideo) "Video viewer" else "Image viewer" }
                .testTag("media-lightbox"),
        ) {
            val line = t.line
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { drawRect(line, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
                    .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!item.isVideo) {
                    ViewerKey(TetherIcons.ZoomOut, "Zoom out", enabled = scale > MIN_SCALE) { zoomBy(1 / 1.4f) }
                    Text(
                        zoomLabel(scale),
                        style = TextStyle(fontFamily = type.mono, fontSize = 11.52.sp, textAlign = TextAlign.Center),
                        color = t.muted,
                        modifier = Modifier.widthIn(min = 51.2.dp).padding(horizontal = t.css.spaceXs),
                    )
                    ViewerKey(TetherIcons.ZoomIn, "Zoom in", enabled = scale < MAX_SCALE) { zoomBy(1.4f) }
                    ViewerKey(TetherIcons.RotateCcw, "Reset zoom", enabled = scale > MIN_SCALE) {
                        scale = MIN_SCALE
                        offset = Offset.Zero
                    }
                    Spacer(Modifier.size(t.css.spaceMd - t.css.spaceXs))
                }
                ViewerKey(TetherIcons.X, "Close", enabled = true, onClick = onClose)
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }
                    .padding(t.css.spaceLg),
                contentAlignment = Alignment.Center,
            ) {
                if (item.isVideo) {
                    ViewerVideo(item)
                } else {
                    val image = rememberMediaImage(item)
                    when (image) {
                        is MediaImage.Ok -> Image(
                            bitmap = image.bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(index) {
                                    detectTapGestures(
                                        onDoubleTap = {
                                            scale = if (scale > MIN_SCALE) MIN_SCALE else 2f
                                            offset = Offset.Zero
                                        },
                                    )
                                }
                                .pointerInput(index) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val next = clampScale(scale * zoom)
                                        scale = next
                                        offset = if (next == MIN_SCALE) Offset.Zero else offset + pan
                                    }
                                }
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                        )
                        null -> SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 18.dp)
                        else -> MediaUnavailable(if (image == MediaImage.TooLarge) "Image too large to show" else "Image unavailable", Modifier)
                    }
                }
                if (canPrev) {
                    NavKey(TetherIcons.ChevronLeft, "Previous media", Modifier.align(Alignment.CenterStart)) { onIndexChange(index - 1) }
                }
                if (canNext) {
                    NavKey(TetherIcons.ChevronRight, "Next media", Modifier.align(Alignment.CenterEnd)) { onIndexChange(index + 1) }
                }
            }
        }
    }
}

@Composable
private fun ViewerKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(t.radiusSm))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) t.muted else t.faint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun NavKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(t.radiusSm))
            .background(t.scrim)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(22.dp))
    }
}

/** A downloaded, bounded clip in a platform VideoView with its media controls; plays on open. */
@Composable
private fun ViewerVideo(item: ToolMediaItem) {
    val t = LocalTetherTokens.current
    val loader = LocalToolMediaLoader.current
    val video by produceState<MediaVideo?>(initialValue = null, item.src, loader) { value = loader?.video(item) }
    when (val v = video) {
        is MediaVideo.Ok -> AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setMediaController(MediaController(context).also { it.setAnchorView(this) })
                    setVideoURI(Uri.fromFile(v.file))
                    setOnPreparedListener { start() }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        null -> SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 18.dp, contentDescription = "Loading video")
        else -> MediaUnavailable(if (v == MediaVideo.TooLarge) "Video too large to play" else "Video unavailable", Modifier)
    }
}
