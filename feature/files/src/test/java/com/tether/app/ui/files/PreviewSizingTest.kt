package com.tether.app.ui.files

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ta-1u4: the preview sizing rules, with the figures the design ruling measured in Chromium
 * (playwright headless shell) against the web's own CSS at 29537e0: an 800x600 pane, padding 24,
 * so a 752x552 content box.
 */
class PreviewSizingTest {
    private val boxW = 752f
    private val boxH = 552f

    private fun svg(w: Float? = null, h: Float? = null, vbW: Float? = null, vbH: Float? = null) =
        PreviewSizing.svg(SvgIntrinsic(w, h, vbW, vbH), boxW, boxH)

    private fun assertSize(w: Float, h: Float, actual: SizeDp) {
        assertEquals("width", w, actual.width, 0.01f)
        assertEquals("height", h, actual.height, 0.01f)
    }

    @Test fun aViewBoxOnlyWideSvgFillsTheContentWidthAtItsRatio() = assertSize(752f, 188f, svg(vbW = 400f, vbH = 100f))

    @Test fun aViewBoxOnlyTallSvgFillsTheContentHeightAtItsRatio() = assertSize(276f, 552f, svg(vbW = 100f, vbH = 200f))

    @Test fun aViewBoxOnlySquareSvgIsTheLargestSquare() = assertSize(552f, 552f, svg(vbW = 24f, vbH = 24f))

    @Test fun aTinyViewBoxScalesUpToo() = assertSize(552f, 552f, svg(vbW = 1f, vbH = 1f))

    @Test fun neitherASizeNorAViewBoxIs300By150() = assertSize(300f, 150f, svg())

    @Test fun width40Height20IsThatSizeInDp() = assertSize(40f, 20f, svg(w = 40f, h = 20f))

    @Test fun absoluteAttributesWinOverTheViewBoxAndNeverScaleUp() = assertSize(120f, 60f, svg(w = 120f, h = 60f, vbW = 1000f, vbH = 1000f))

    @Test fun absoluteAttributesScaleDownToTheContentBox() {
        assertSize(752f, 376f, svg(w = 2000f, h = 1000f))
        assertSize(276f, 552f, svg(w = 1000f, h = 2000f))
    }

    @Test fun oneAttributeTakesTheOtherFromTheViewBoxOrTheDefault() {
        assertSize(200f, 100f, svg(w = 200f, vbW = 10f, vbH = 5f))
        assertSize(200f, 150f, svg(w = 200f))
        assertSize(100f, 50f, svg(h = 50f, vbW = 10f, vbH = 5f))
        assertSize(300f, 50f, svg(h = 50f))
    }

    @Test fun percentOrNonsenseAttributesCountAsMissing() {
        // The parser reports a percentage as no size: the viewBox decides.
        assertSize(752f, 188f, svg(w = null, h = null, vbW = 400f, vbH = 100f))
        assertSize(300f, 150f, svg(w = 0f, h = -3f, vbW = 0f, vbH = 0f))
        assertSize(300f, 150f, svg(w = Float.NaN, h = Float.POSITIVE_INFINITY))
    }

    @Test fun theDefaultSizeScalesDownInASmallBox() {
        assertSize(100f, 50f, PreviewSizing.svg(SvgIntrinsic(), 100f, 400f))
    }

    @Test fun aRasterAndAnSvgShareOneRule() {
        // A 100 px PNG draws 100 dp wide, not 100 device px; scaled down to the box, never up.
        assertSize(100f, 50f, PreviewSizing.raster(100, 50, boxW, boxH))
        assertSize(752f, 376f, PreviewSizing.raster(1504, 752, boxW, boxH))
        assertSize(276f, 552f, PreviewSizing.raster(1000, 2000, boxW, boxH))
        // The same natural size through the SVG path is the same drawn size.
        assertSize(100f, 50f, svg(w = 100f, h = 50f))
        assertSize(752f, 376f, svg(w = 1504f, h = 752f))
    }

    @Test fun anEmptyBoxDrawsNothing() {
        assertSize(0f, 0f, PreviewSizing.raster(100, 50, 0f, 100f))
        assertSize(0f, 0f, PreviewSizing.svg(SvgIntrinsic(viewBoxWidth = 1f, viewBoxHeight = 1f), 100f, 0f))
        assertEquals(VideoBox(0f, 0f, 0f), PreviewSizing.video(1920, 1080, 0f, 100f))
    }

    @Test fun aVideoIsFullWidthAndOneFiftyTallBeforeItsSizeIsKnown() {
        assertEquals(VideoBox(150f, 752f, 150f), PreviewSizing.video(null, null, boxW, boxH))
        assertEquals(VideoBox(150f, 752f, 150f), PreviewSizing.video(0, 0, boxW, boxH))
        // A pane shorter than 150 caps it.
        assertEquals(VideoBox(100f, 752f, 100f), PreviewSizing.video(null, null, boxW, 100f))
    }

    @Test fun aVideoIsContentWidthByWidthOverAspectCappedAtTheContentHeight() {
        val wide = PreviewSizing.video(1920, 1080, boxW, boxH)
        assertEquals(423f, wide.height, 0.01f)
        assertEquals(752f, wide.videoWidth, 0.01f)
        // A small clip still spans the width (`width: 100%`).
        assertEquals(PreviewSizing.video(160, 90, boxW, boxH).height, wide.height, 0.01f)
        // Portrait: capped by the height; the frame is contain-fit and the box stays full width (letterboxed).
        val portrait = PreviewSizing.video(1080, 1920, boxW, boxH)
        assertEquals(552f, portrait.height, 0.01f)
        assertEquals(310.5f, portrait.videoWidth, 0.01f)
        assertEquals(552f, portrait.videoHeight, 0.01f)
    }

    @Test fun thePhoneContentBoxIsTheSameRule() {
        // 412dp phone, padding 16: 380 wide. A 16:9 clip is ~214 tall.
        val box = PreviewSizing.video(1920, 1080, 380f, 600f)
        assertEquals(213.75f, box.height, 0.01f)
    }
}
