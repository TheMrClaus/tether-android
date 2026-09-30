package com.tether.app.ui.statusline.screenshots

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reduced-motion pair must be able to fail: two frames after 0% → 80%, the reduced-motion
 * golden shows the fill already at 80% and the with-motion golden shows it mid-transition. The
 * fill is the only saturated colour on the board (Machine `--violet`; captions, page and track
 * floor are neutral greys), so its pixel count measures how far the fill has travelled.
 */
class UsageTrackMotionGoldensTest {
    private fun fillPixels(board: String): Int {
        val image = ImageIO.read(File(goldenPath(board, com.tether.app.ui.theme.TetherSkin.StudioDark, ScreenSize.Phone)))
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val p = image.getRGB(x, y)
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            if (maxOf(r, g, b) - minOf(r, g, b) > 60) count++
        }
        return count
    }

    @Test fun reducedMotionJumpsWhileMotionIsStillTravelling() {
        val moving = fillPixels("usage-track-motion")
        val reduced = fillPixels("usage-track-reduced-motion")
        assertTrue("the with-motion fill has started ($moving px)", moving > 0)
        assertTrue("the reduced-motion fill is already further ($reduced px vs $moving px)", reduced > moving * 1.1)
    }
}
