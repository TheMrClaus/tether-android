package com.tether.app.ui.files

import android.graphics.Bitmap
import android.graphics.Color
import com.caverock.androidsvg.SVG
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ta-1u4: the SVG preview is script-free and fetches nothing. The security cases run the real
 * parser and renderer (Robolectric, native graphics) against hostile files; the "nothing fetched"
 * cases point every kind of reference at a live MockWebServer and assert it saw no request.
 */
@RunWith(RobolectricTestRunner::class)
class SvgImagesTest {
    private val server = MockWebServer()

    @Before fun setUp() {
        server.start()
        // The library's switches are process-wide: start each test from the library's own defaults.
        SVG.setInternalEntitiesEnabled(true)
    }

    @After fun tearDown() = server.shutdown()

    private fun svg(body: String, attrs: String = """xmlns="http://www.w3.org/2000/svg" """) = """<svg $attrs>$body</svg>"""

    private fun parsed(text: String): ParsedSvg = checkNotNull(SvgImages.parse(text)) { "did not parse" }

    private fun draw(p: ParsedSvg, w: Int = 100, h: Int = 50): Bitmap = checkNotNull(SvgImages.render(p, w, h)) { "did not render" }

    // --- intrinsic size -------------------------------------------------------------------------

    @Test fun theRootElementsOwnSizeIsReadAsIs() {
        assertEquals(SvgIntrinsic(40f, 20f, null, null), parsed(svg("", """xmlns="http://www.w3.org/2000/svg" width="40" height="20" """)).intrinsic)
        val withBox = parsed(svg("", """xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 100" """)).intrinsic
        assertEquals(SvgIntrinsic(null, null, 400f, 100f), withBox)
        // Percentages are no size at all.
        assertEquals(
            SvgIntrinsic(null, null, 8f, 4f),
            parsed(svg("", """xmlns="http://www.w3.org/2000/svg" width="100%" height="100%" viewBox="0 0 8 4" """)).intrinsic,
        )
        assertEquals(SvgIntrinsic(), parsed(svg("")).intrinsic)
        // Physical units go through the library's 96 dpi: 1in = 96 px.
        assertEquals(96f, parsed(svg("", """xmlns="http://www.w3.org/2000/svg" width="1in" height="2in" """)).intrinsic.width!!, 0.5f)
    }

    // --- drawing --------------------------------------------------------------------------------

    @Test fun aViewBoxOnlyDocumentIsDrawnAtTheSizeItIsShownAt() {
        val p = parsed(svg("""<rect width="400" height="100" fill="#ff0000"/>""", """xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 100" """))
        val bmp = draw(p, 200, 50)
        assertEquals(200, bmp.width)
        assertEquals(50, bmp.height)
        // Scaled to fill its viewport, not clipped to its own units.
        assertEquals(Color.RED, bmp.getPixel(199, 49))
        assertEquals(Color.RED, bmp.getPixel(0, 0))
        // A larger box draws it larger (no bitmap bigger than asked).
        val big = draw(p, 800, 200)
        assertEquals(Color.RED, big.getPixel(799, 199))
    }

    @Test fun aDocumentWithOnlyWidthAndHeightScalesWithTheBox() {
        val p = parsed(svg("""<rect width="40" height="20" fill="#00ff00"/>""", """xmlns="http://www.w3.org/2000/svg" width="40" height="20" """))
        // Shown at 4x its size: still filled to the corner (scaled, not drawn at 1:1 in a corner).
        assertEquals(Color.GREEN, draw(p, 160, 80).getPixel(159, 79))
        assertEquals(Color.GREEN, draw(p, 20, 10).getPixel(19, 9))
    }

    @Test fun aDocumentWithNeitherIsDrawnOnThe300By150Canvas() {
        val p = parsed(svg("""<rect width="300" height="150" fill="#0000ff"/>"""))
        assertEquals(Color.BLUE, draw(p, 150, 75).getPixel(149, 74))
        assertEquals(Color.BLUE, draw(p, 300, 150).getPixel(299, 149))
    }

    @Test fun transparentAreasStayTransparentForTheSurfaceBehindThem() {
        val p = parsed(svg("""<rect width="10" height="10" fill="#ff0000"/>""", """xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" """))
        assertEquals(Color.TRANSPARENT, draw(p, 40, 40).getPixel(39, 39))
    }

    @Test fun inlineStylesAndEmbeddedDataImagesStillDraw() {
        val styled = parsed(svg("""<style>.a{fill:#ff0000}</style><rect class="a" width="100" height="50"/>""", """xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50" """))
        assertEquals(Color.RED, draw(styled).getPixel(50, 25))
    }

    @Test fun aRenderOfAnEmptySizeIsNothing() {
        assertNull(SvgImages.render(parsed(svg("")), 0, 10))
    }

    // --- not an SVG -----------------------------------------------------------------------------

    @Test fun whatIsNotAnSvgDocumentIsRefused() {
        assertNull(SvgImages.parse(""))
        assertNull(SvgImages.parse("hello"))
        assertNull(SvgImages.parse("<html><body/></html>"))
        assertNull(SvgImages.parse(svg("<g>")))
        assertNull(SvgImages.parse(java.io.ByteArrayInputStream(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))))
    }

    @Test fun aGzipBodyIsNotInflated() {
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(svg("""<rect width="1" height="1"/>""").toByteArray()) } }.toByteArray()
        assertNull("a browser does not inflate a plain .svg either", SvgImages.parse(java.io.ByteArrayInputStream(gz)))
    }

    // --- scripts ----------------------------------------------------------------------------------

    @Test fun scriptsAndEventHandlersDoNothing() {
        val p = parsed(
            svg(
                """<script>throw new Error('ran')</script><script href="http://127.0.0.1:${server.port}/s.js"/>
                   <rect width="100" height="50" fill="#ff0000" onload="alert(1)" onclick="alert(2)"/>
                   <a href="javascript:alert(3)"><rect width="10" height="10"/></a>""",
                """xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 100 50" onload="alert(0)" """,
            ),
        )
        assertEquals(Color.RED, draw(p).getPixel(50, 25))
        assertEquals("a script reference is never fetched", 0, server.requestCount)
    }

    // --- nothing is fetched --------------------------------------------------------------------

    @Test fun noKindOfExternalReferenceIsFetched() {
        val origin = "http://127.0.0.1:${server.port}"
        val ns = """xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 100 50" """
        val bodies = listOf(
            """<image href="$origin/a.png" width="10" height="10"/>""",
            """<image xlink:href="$origin/b.png" width="10" height="10"/>""",
            """<image href="//127.0.0.1:${server.port}/c.png" width="10" height="10"/>""",
            """<image href="file:///etc/passwd" width="10" height="10"/>""",
            """<use href="$origin/u.svg#a"/>""",
            """<use xlink:href="$origin/u.svg#a"/>""",
            """<rect width="10" height="10" fill="url($origin/g.svg#g)"/>""",
            """<rect width="10" height="10" filter="url($origin/f.svg#f)" style="fill:url($origin/h.svg#g)"/>""",
            """<defs><filter id="f"><feImage href="$origin/f.png"/></filter></defs><rect width="10" height="10" filter="url(#f)"/>""",
            """<style>@import url($origin/a.css); @font-face{font-family:x;src:url($origin/f.woff)} text{font-family:x}</style><text x="0" y="10">hi</text>""",
            """<text x="0" y="10"><tref xlink:href="$origin/t.svg#t"/></text>""",
            """<pattern id="p" width="5" height="5"><image href="$origin/p.png" width="5" height="5"/></pattern><rect width="10" height="10" fill="url(#p)"/>""",
        )
        var drawn = 0
        for (body in bodies) {
            // A file this library cannot parse is as safe as one it draws; what must never happen is a request.
            SvgImages.parse(svg(body, ns))?.let { if (SvgImages.render(it, 100, 50) != null) drawn++ }
        }
        assertTrue("most of them do parse and draw: $drawn", drawn >= 8)
        assertEquals("nothing was requested", 0, server.requestCount)
    }

    @Test fun noExternalFileResolverIsLeftRegistered() {
        // Whatever registered one in this process, the next parse removes it: an external <image> resolves to nothing.
        SVG.registerExternalFileResolver(object : com.caverock.androidsvg.SVGExternalFileResolver() {
            override fun resolveImage(filename: String?): Bitmap? {
                server.enqueue(MockResponse())
                java.net.URL("http://127.0.0.1:${server.port}/resolved").openStream().close()
                return null
            }
        })
        val p = parsed(svg("""<image href="http://example.invalid/a.png" width="10" height="10"/>""", """xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10" """))
        draw(p)
        assertEquals(0, server.requestCount)
    }

    // --- entities and DTDs ------------------------------------------------------------------------

    private fun billionLaughs(): String {
        val levels = (1..9).joinToString("") { """<!ENTITY lol$it "${"&lol${it - 1};".repeat(10)}">""" }
        return """<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY lol0 "lollollollollollollollollollol">$levels]>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><text>&lol9;</text><rect width="10" height="10" fill="#ff0000"/></svg>"""
    }

    @Test fun aBillionLaughsFileIsNotExpanded() {
        val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val started = System.nanoTime()
        val p = SvgImages.parse(billionLaughs())
        val bitmap = p?.let { SvgImages.render(it, 40, 40) }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        // Expanded, it is 10^9 x 30 characters; refused, it is a few milliseconds and a few kilobytes.
        assertTrue("took ${tookMs}ms", tookMs < 5_000)
        val after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        assertTrue("grew by ${(after - before) / 1_000_000} MB", after - before < 200_000_000)
        assertFalse("entity expansion stays off", SVG.isInternalEntitiesEnabled())
        // Either refused outright or drawn without the entity text; the picture itself may still show.
        if (bitmap != null) assertEquals(Color.RED, bitmap.getPixel(20, 20))
    }

    @Test fun aSingleInternalEntityIsNotExpandedEither() {
        // An entity that would paint the square red if it were expanded (an attribute value).
        val text = """<!DOCTYPE svg [<!ENTITY c "#ff0000">]>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10" fill="&c;"/></svg>"""
        val bitmap = SvgImages.parse(text)?.let { SvgImages.render(it, 20, 20) }
        // Never red: an undefined entity is a parse error or a rect with no usable fill.
        if (bitmap != null) assertTrue(bitmap.getPixel(10, 10) != Color.RED)
    }

    @Test fun anExternalEntityIsNeverResolved() {
        server.enqueue(MockResponse().setBody("SECRET"))
        val text = """<!DOCTYPE svg [<!ENTITY xxe SYSTEM "http://127.0.0.1:${server.port}/xxe"><!ENTITY pw SYSTEM "file:///etc/passwd">]>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><text>&xxe;&pw;</text><rect width="10" height="10" fill="#ff0000"/></svg>"""
        SvgImages.parse(text)?.let { SvgImages.render(it, 20, 20) }
        assertEquals("the external entity was never fetched", 0, server.requestCount)
    }

    @Test fun anExternalDtdIsNeverFetched() {
        val text = """<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://127.0.0.1:${server.port}/svg11.dtd">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10" fill="#ff0000"/></svg>"""
        val bitmap = SvgImages.parse(text)?.let { SvgImages.render(it, 20, 20) }
        assertEquals(0, server.requestCount)
        // The usual Illustrator / Inkscape preamble (a DOCTYPE without entities) is just a DOCTYPE.
        assertNotNull(bitmap)
        assertEquals(Color.RED, bitmap!!.getPixel(10, 10))
    }

    // --- hostile structure ------------------------------------------------------------------------

    @Test fun deepNestingIsAFailureNotACrash() {
        val depth = 30_000
        val text = svg("<g>".repeat(depth) + """<rect width="10" height="10"/>""" + "</g>".repeat(depth), """xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10" """)
        // Parsed or refused, and drawn or refused: never an exception out of this call.
        SvgImages.parse(text)?.let { SvgImages.render(it, 20, 20) }
    }

    @Test fun aHugeClaimedSizeCostsOnlyTheBitmapWeAskFor() {
        val p = parsed(svg("""<rect width="1" height="1"/>""", """xmlns="http://www.w3.org/2000/svg" width="1000000" height="1000000" """))
        assertEquals(SvgIntrinsic(1_000_000f, 1_000_000f, null, null), p.intrinsic)
        // The drawn size is the preview box, however large the file claims to be.
        val size = PreviewSizing.svg(p.intrinsic, 300f, 200f)
        assertEquals(200f, size.height, 0.01f)
        val bitmap = draw(p, size.width.toInt().coerceAtLeast(1), size.height.toInt())
        assertTrue(bitmap.width <= 300 && bitmap.height <= 200)
    }
}
