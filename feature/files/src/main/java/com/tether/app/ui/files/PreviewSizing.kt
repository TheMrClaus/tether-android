package com.tether.app.ui.files

/** A size in dp (a CSS px is a dp on a phone, as chat's tool media already treats it). */
data class SizeDp(val width: Float, val height: Float)

/**
 * What an SVG says about its own size (the root element's attributes). [width] / [height] are
 * absolute lengths in CSS px (null when missing, a percentage, or not a positive number);
 * [viewBoxWidth] / [viewBoxHeight] are the viewBox's, null without one.
 */
data class SvgIntrinsic(
    val width: Float? = null,
    val height: Float? = null,
    val viewBoxWidth: Float? = null,
    val viewBoxHeight: Float? = null,
) {
    private fun Float?.positive(): Float? = this?.takeIf { it.isFinite() && it > 0f }

    val attrWidth: Float? get() = width.positive()
    val attrHeight: Float? get() = height.positive()

    /** The viewBox's width / height, null without a usable one. */
    val ratio: Float?
        get() {
            val w = viewBoxWidth.positive() ?: return null
            val h = viewBoxHeight.positive() ?: return null
            return w / h
        }

    /** Whether the document has a viewBox the renderer can scale from. */
    val hasViewBox: Boolean get() = ratio != null
}

/** The video box: its [height] (full content width wide), and the frame's contain-fit size inside it. */
data class VideoBox(val height: Float, val videoWidth: Float, val videoHeight: Float)

/**
 * The preview pane's sizing rules, one place for every kind, in dp. They are the CSS the web puts
 * on its `<img>` / `<video>` (globals.css 2878-2880: `display: block; max-width: 100%; max-height:
 * 100%; object-fit: contain`, a video also `width: 100%`) in a flex-centred pane, measured in
 * Chromium against 29537e0 (the L1 design ruling): the content box is the pane minus its padding.
 *
 * An image has no say in its own size beyond its pixels, so a raster and an SVG share
 * [fitDown]: the natural size scaled down to the box, never up.
 */
object PreviewSizing {
    /** The browser's default object size (an SVG with neither a size nor a viewBox). */
    const val DEFAULT_WIDTH = 300f
    const val DEFAULT_HEIGHT = 150f

    /** [natural] scaled down (never up) to fit the box, keeping its ratio. */
    fun fitDown(natural: SizeDp, boxWidth: Float, boxHeight: Float): SizeDp {
        if (boxWidth <= 0f || boxHeight <= 0f || natural.width <= 0f || natural.height <= 0f) return SizeDp(0f, 0f)
        val scale = minOf(1f, boxWidth / natural.width, boxHeight / natural.height)
        return SizeDp(natural.width * scale, natural.height * scale)
    }

    /** A bitmap's pixels are its CSS size: 100 px wide draws 100 dp wide (not 100 device px), scaled down to the box. */
    fun raster(widthPx: Int, heightPx: Int, boxWidth: Float, boxHeight: Float): SizeDp =
        fitDown(SizeDp(widthPx.toFloat(), heightPx.toFloat()), boxWidth, boxHeight)

    /** The largest box of [ratio] (width / height) that fits the content box: it scales up as well as down. */
    fun fillRatio(ratio: Float, boxWidth: Float, boxHeight: Float): SizeDp {
        if (boxWidth <= 0f || boxHeight <= 0f || !ratio.isFinite() || ratio <= 0f) return SizeDp(0f, 0f)
        val heightAtFullWidth = boxWidth / ratio
        return if (heightAtFullWidth <= boxHeight) SizeDp(boxWidth, heightAtFullWidth) else SizeDp(boxHeight * ratio, boxHeight)
    }

    /**
     * An SVG's natural CSS size when it states one: both lengths, or one of them with the other
     * taken from the viewBox's ratio (the browser's rule), else the default object size for the
     * missing one. Null when it states neither (a viewBox-only document fills the box instead).
     */
    fun svgNatural(i: SvgIntrinsic): SizeDp? {
        val w = i.attrWidth
        val h = i.attrHeight
        val ratio = i.ratio
        return when {
            w != null && h != null -> SizeDp(w, h)
            w != null -> SizeDp(w, ratio?.let { w / it } ?: DEFAULT_HEIGHT)
            h != null -> SizeDp(ratio?.let { h * it } ?: DEFAULT_WIDTH, h)
            else -> null
        }
    }

    /**
     * The size an SVG is drawn at (the L1 ruling's table): absolute width and height attributes
     * are that size in dp, scaled down to the box and never up; a viewBox alone (or percentages)
     * is the largest box of its ratio that fits the box, scaling up; neither is 300x150 dp, scaled
     * down.
     */
    fun svg(i: SvgIntrinsic, boxWidth: Float, boxHeight: Float): SizeDp {
        svgNatural(i)?.let { return fitDown(it, boxWidth, boxHeight) }
        i.ratio?.let { return fillRatio(it, boxWidth, boxHeight) }
        return fitDown(SizeDp(DEFAULT_WIDTH, DEFAULT_HEIGHT), boxWidth, boxHeight)
    }

    /**
     * The video element: always the content width (`width: 100%`), [DEFAULT_HEIGHT] tall until its
     * size is known (a `<video>` before metadata), then width / aspect capped at the content
     * height, the frame contain-fit inside and the rest letterboxed on graphite.
     */
    fun video(videoWidth: Int?, videoHeight: Int?, boxWidth: Float, boxHeight: Float): VideoBox {
        if (boxWidth <= 0f || boxHeight <= 0f) return VideoBox(0f, 0f, 0f)
        if (videoWidth == null || videoHeight == null || videoWidth <= 0 || videoHeight <= 0) {
            val height = minOf(DEFAULT_HEIGHT, boxHeight)
            return VideoBox(height, boxWidth, height)
        }
        val fitted = fillRatio(videoWidth.toFloat() / videoHeight.toFloat(), boxWidth, boxHeight)
        return VideoBox(fitted.height, fitted.width, fitted.height)
    }
}
