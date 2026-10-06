package com.tether.app.ui.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tether.app.client.WorkspaceFileEntry
import com.tether.app.ui.components.Spinner
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.video.LocalVideoSurfaceEnabled
import com.tether.app.ui.video.VideoPhase
import com.tether.app.ui.video.VideoPlayer
import com.tether.app.ui.video.VideoSurface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
            if (phase !is VideoPhase.Failed && LocalVideoSurfaceEnabled.current) VideoSurface(player, box.videoWidth.dp, box.videoHeight.dp, Modifier.fillMaxSize())
            if (phase is VideoPhase.Opening) {
                Box(Modifier.align(Alignment.Center).semantics { contentDescription = "Loading video" }) { Spinner(22.dp, t.faint) }
            }
        }
    }
}
