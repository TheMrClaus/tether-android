package com.tether.app.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ta-hkk7.6 item 3 (W46 WC6) probe: a 50% stroke on black; a pixel painted twice would reach ~75%. Green at base: the joint paints nothing twice, no code change. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ConnectorOverlapProbeTest {
    @get:Rule val rule = createComposeRule()

    private fun maxChannel(content: @androidx.compose.runtime.Composable () -> Unit): Int {
        rule.setContent { content() }
        rule.waitForIdle()
        val bmp = rule.onNodeWithTag("probe").captureToImage().asAndroidBitmap()
        var max = 0
        for (yy in 0 until bmp.height) for (xx in 0 until bmp.width) max = maxOf(max, bmp.getPixel(xx, yy) and 0xFF)
        return max
    }

    private val half = Color.White.copy(alpha = 0.5f)

    @Test fun theElbowAndTheSpineBelowItNeverPaintAPixelTwice() {
        val max = maxChannel {
            Box(Modifier.size(100.dp, 120.dp).background(Color.Black).testTag("probe")) {
                Box(Modifier.size(100.dp, 120.dp).nestedConnector(false, half))
            }
        }
        // a single 50% stroke tops out at 128 (+ rounding); two over each other would be ~192
        assertTrue("max channel $max", max <= 132)
    }

    private fun headSpineProbe(open: Boolean) {
        val max = maxChannel {
            Box(Modifier.size(100.dp, 120.dp).background(Color.Black).testTag("probe")) {
                Box(Modifier.size(100.dp, 120.dp).headSpine(open, half))
            }
        }
        assertTrue("open=$open max channel $max", max <= 132)
    }

    @Test fun theOpenHeadSpineNeverPaintsAPixelTwice() = headSpineProbe(true)
    @Test fun theClosedHeadSpineNeverPaintsAPixelTwice() = headSpineProbe(false)
}
