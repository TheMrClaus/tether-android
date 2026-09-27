package com.tether.app.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.CssShadow
import kotlin.math.abs
import kotlin.math.max

/**
 * The web's CSS box model for one molded part, drawn in CSS paint order (CSS Backgrounds 3 §7):
 * outer `box-shadow` layers (last layer lowest, each clipped out of the border box, so a
 * translucent face never shows its own shadow through itself) → background → inset layers
 * (clipped to the padding box) → border. Content draws on top.
 *
 * Every layer of a generated `List<CssShadow>` token is drawn, including zero-blur hard offsets
 * (key side-walls, bezels) and inset bevels/wells, which are geometrically exact: a hard layer is
 * the rounded rect grown by its spread and moved by its offset.
 *
 * Approximations (documented in core/designsystem/README.md):
 *  - Blur: CSS blur radius B is a Gaussian with sigma = B/2 (CSS Backgrounds 3 §7.1.1). Android's
 *    BlurMaskFilter takes a Skia "radius" r with sigma = 0.57735·r + 0.5, so r is solved from
 *    sigma; below sigma 0.5 px (a 1px blur at mdpi) the blur is the minimum Skia draws.
 *  - Spread on rounded corners: the corner radius grows/shrinks by the spread and clamps at 0.
 *    CSS additionally eases small radii (the "r < spread" cubic); the difference is < 1px on
 *    every radius the tokens use.
 *  - Only rectangle / rounded-rectangle outlines are exact (all Tether primitives use them);
 *    a generic outline falls back to its bounds.
 */
@Immutable
data class CssBorder(val width: Dp, val color: Color)

fun Modifier.cssSurface(
    shape: Shape,
    background: Color = Color.Transparent,
    border: CssBorder? = null,
    shadows: List<CssShadow> = emptyList(),
    backgroundBrush: Brush? = null,
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val box = RoundBox.of(outline, size)
    val borderPx = border?.width?.toPx() ?: 0f
    val padding = box.deflate(borderPx)
    val visible = shadows.filter { it.color.alpha > 0f }
    val outer = visible.filter { !it.inset }.asReversed()
    val inner = visible.filter { it.inset }.asReversed()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    onDrawBehind {
        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            if (outer.isNotEmpty()) {
                c.save()
                c.clipOutPath(box.path())
                for (layer in outer) {
                    val g = box.inflate(layer.spread.toPx()).offset(layer.offsetX.toPx(), layer.offsetY.toPx())
                    paint.shadow(layer, this)
                    c.drawPath(g.path(), paint)
                }
                c.restore()
            }
            paint.maskFilter = null
            if (background.alpha > 0f) {
                paint.color = background.toArgb()
                c.drawPath(box.path(), paint)
            }
        }
        if (backgroundBrush != null) {
            drawOutline(outline, backgroundBrush)
        }
        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            if (inner.isNotEmpty()) {
                c.save()
                c.clipPath(padding.path())
                for (layer in inner) {
                    val hole = padding.deflate(layer.spread.toPx()).offset(layer.offsetX.toPx(), layer.offsetY.toPx())
                    val reach = layer.blur.toPx() * 2 + abs(layer.offsetX.toPx()) + abs(layer.offsetY.toPx()) + abs(layer.spread.toPx()) + 2f
                    val ring = Path().apply {
                        fillType = Path.FillType.EVEN_ODD
                        addRect(-reach, -reach, size.width + reach, size.height + reach, Path.Direction.CW)
                        addPath(hole.path())
                    }
                    paint.shadow(layer, this)
                    c.drawPath(ring, paint)
                }
                c.restore()
            }
            paint.maskFilter = null
            if (border != null && borderPx > 0f && border.color.alpha > 0f) {
                val ring = Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    addPath(box.path())
                    addPath(padding.path())
                }
                paint.color = border.color.toArgb()
                c.drawPath(ring, paint)
            }
        }
    }
}

/** Only the drop (outer) layers of a shadow list, for parts whose face is drawn separately. */
fun List<CssShadow>.outerOnly(): List<CssShadow> = filter { !it.inset }

/** A zero-blur hard layer, e.g. a key's `0 2px 0 var(--key-side)` side-wall. */
fun hardShadow(y: Dp, color: Color, x: Dp = 0.dp, inset: Boolean = false): CssShadow =
    CssShadow(inset = inset, offsetX = x, offsetY = y, blur = 0.dp, spread = 0.dp, color = color)

/** A blurred layer written inline in a CSS rule (not a token). */
fun softShadow(y: Dp, blur: Dp, color: Color, spread: Dp = 0.dp, x: Dp = 0.dp, inset: Boolean = false): CssShadow =
    CssShadow(inset = inset, offsetX = x, offsetY = y, blur = blur, spread = spread, color = color)

private fun Paint.shadow(layer: CssShadow, density: DrawScope) {
    color = layer.color.toArgb()
    val blurPx = with(density) { layer.blur.toPx() }
    maskFilter = if (blurPx > 0f) BlurMaskFilter(blurRadiusForCss(blurPx), BlurMaskFilter.Blur.NORMAL) else null
}

/**
 * CSS blur B (px) → the Skia radius Android's BlurMaskFilter expects. CSS: sigma = B/2.
 * Skia: sigma = 0.57735·r + 0.5 (SkBlurMask::ConvertRadiusToSigma).
 */
fun blurRadiusForCss(blurPx: Float): Float = max((blurPx / 2f - 0.5f) / 0.57735f, 0.1f)

/** A rounded rectangle with per-corner radii (px), the geometry every CSS box-shadow layer needs. */
internal data class RoundBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val tl: Float,
    val tr: Float,
    val br: Float,
    val bl: Float,
) {
    fun inflate(by: Float): RoundBox = RoundBox(
        left - by, top - by, right + by, bottom + by,
        max(0f, tl + by), max(0f, tr + by), max(0f, br + by), max(0f, bl + by),
    ).normalized()

    fun deflate(by: Float): RoundBox = inflate(-by)

    fun offset(dx: Float, dy: Float): RoundBox = copy(left = left + dx, right = right + dx, top = top + dy, bottom = bottom + dy)

    private fun normalized(): RoundBox = if (right < left || bottom < top) {
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        RoundBox(cx, cy, cx, cy, 0f, 0f, 0f, 0f)
    } else {
        this
    }

    fun path(): Path = Path().apply {
        addRoundRect(
            RectF(left, top, right, bottom),
            floatArrayOf(tl, tl, tr, tr, br, br, bl, bl),
            Path.Direction.CW,
        )
    }

    companion object {
        fun of(outline: Outline, size: Size): RoundBox = when (outline) {
            is Outline.Rectangle -> RoundBox(0f, 0f, size.width, size.height, 0f, 0f, 0f, 0f)
            is Outline.Rounded -> {
                val r = outline.roundRect
                RoundBox(
                    r.left, r.top, r.right, r.bottom,
                    r.topLeftCornerRadius.x, r.topRightCornerRadius.x,
                    r.bottomRightCornerRadius.x, r.bottomLeftCornerRadius.x,
                )
            }
            is Outline.Generic -> RoundBox(0f, 0f, size.width, size.height, 0f, 0f, 0f, 0f)
        }
    }
}

/**
 * `:focus-visible { outline: 2px solid var(--violet); outline-offset: 2px }` (globals.css 632-640):
 * a ring drawn independently of colour and elevation, following the part's corner radius grown by
 * the offset (Chromium rounds outlines with the border radius). Violet here means FOCUS.
 */
fun Modifier.focusRing(focused: Boolean, shape: Shape, color: Color, width: Dp = 2.dp, offset: Dp = 2.dp): Modifier =
    if (!focused) this else drawWithCache {
        val box = RoundBox.of(shape.createOutline(size, layoutDirection, this), size)
        val outer = box.inflate(offset.toPx() + width.toPx())
        val inner = box.inflate(offset.toPx())
        val ring = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addPath(outer.path())
            addPath(inner.path())
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        onDrawBehind {
            paint.color = color.toArgb()
            drawIntoCanvas { it.nativeCanvas.drawPath(ring, paint) }
        }
    }
