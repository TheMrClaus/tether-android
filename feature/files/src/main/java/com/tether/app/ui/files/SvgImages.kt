package com.tether.app.ui.files

import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * An SVG parsed once, drawn as often as the preview box changes. Only the root element's own size
 * is read ([intrinsic]); the document itself is then set to fill whatever viewport it is drawn into.
 */
class ParsedSvg internal constructor(
    internal val svg: SVG,
    val intrinsic: SvgIntrinsic,
    /** The viewBox a document without one is drawn with (its natural size), so it scales instead of clipping. */
    internal val fallbackViewBox: SizeDp?,
)

/**
 * The SVG preview, the web's `<img src=*.svg>` as a script-free rasteriser (AndroidSVG, no web view).
 *
 * What an SVG file is allowed to do here, all enforced in this one place:
 * - nothing executes: AndroidSVG has no script engine, animation or event handling;
 * - nothing is fetched: no `SVGExternalFileResolver` is ever registered (and a registration is
 *   removed before every parse), so an external `<image href>`, font or `<use>` resolves to
 *   nothing; `data:` images embedded in the file still draw, as in a browser;
 * - no entity is expanded: internal entities are switched off (the library otherwise re-parses a
 *   DOCTYPE with `<!ENTITY>` through a SAX parser, which is how a billion-laughs file blows up), and
 *   its XML parser never processes a DTD, so an external entity resolves to nothing too;
 * - a gzip body is refused (the library would inflate it; a browser does not for a plain `.svg`);
 * - it is drawn at the size it is shown at, into a bitmap bounded by the preview box, never at
 *   the size the file claims.
 * A parse failure, an out-of-memory or a stack overflow in a hostile file is "could not be displayed".
 */
object SvgImages {
    /** Parsed, or null when the file is not an SVG this can draw. */
    fun parse(file: File): ParsedSvg? = try {
        file.inputStream().use(::parse)
    } catch (_: IOException) {
        null
    }

    fun parse(text: String): ParsedSvg? = parse(text.byteInputStream(Charsets.UTF_8))

    fun parse(input: InputStream): ParsedSvg? = try {
        val stream = if (input.markSupported()) input else BufferedInputStream(input)
        stream.mark(2)
        val first = stream.read()
        val second = stream.read()
        stream.reset()
        if (first == 0x1f && second == 0x8b) {
            null
        } else {
            // Process-wide library switches, set before every parse so no other code can have changed them.
            SVG.setInternalEntitiesEnabled(false)
            SVG.deregisterExternalFileResolver()
            val svg = SVG.getFromInputStream(stream)
            intrinsicOf(svg)
        }
    } catch (_: Exception) {
        // SVGParseException, a runtime failure of the parser, an unreadable stream.
        null
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: StackOverflowError) {
        null
    }

    private fun intrinsicOf(svg: SVG): ParsedSvg {
        val width = svg.documentWidth.takeIf { it > 0f }
        val height = svg.documentHeight.takeIf { it > 0f }
        val box = svg.documentViewBox
        val intrinsic = SvgIntrinsic(width, height, box?.width(), box?.height())
        // The document is drawn into the viewport it is given: its own width / height must not win.
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        val natural = PreviewSizing.svgNatural(intrinsic) ?: SizeDp(PreviewSizing.DEFAULT_WIDTH, PreviewSizing.DEFAULT_HEIGHT)
        return ParsedSvg(svg, intrinsic, if (intrinsic.hasViewBox) null else natural)
    }

    /** [parsed] drawn into a [widthPx] x [heightPx] bitmap, or null when it cannot be (memory, a hostile file). */
    fun render(parsed: ParsedSvg, widthPx: Int, heightPx: Int): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        return try {
            val bitmap = Bitmap.createBitmap(widthPx.coerceAtMost(MAX_SIDE_PX), heightPx.coerceAtMost(MAX_SIDE_PX), Bitmap.Config.ARGB_8888)
            val options = RenderOptions.create().viewPort(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            parsed.fallbackViewBox?.let { options.viewBox(0f, 0f, it.width, it.height) }
            // One render at a time per document: the library's tree is not built for concurrent drawing.
            synchronized(parsed) { parsed.svg.renderToCanvas(Canvas(bitmap), options) }
            bitmap
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    /** The preview box is the screen at most; this only keeps a stray huge request from allocating. */
    private const val MAX_SIDE_PX = 8192
}
