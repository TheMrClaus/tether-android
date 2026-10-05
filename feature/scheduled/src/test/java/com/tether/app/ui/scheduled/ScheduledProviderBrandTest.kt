package com.tether.app.ui.scheduled

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ta-coik.45: `.schedule-provider-logo` holding a verified mark takes its brand tile (tether 90fbb9f globals.css 11204-11224). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ScheduledProviderBrandTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun scheduleLogosAreBrandTiles() {
        rule.setContent { ScheduledUnderTest(ScheduledStates.populated, ScheduledRecorder(), 412, skin = TetherSkin.StudioDark) }
        rule.waitForIdle()
        val px = rule.onRoot().captureToImage().toPixelMap()
        val out = HashMap<Color, Int>()
        for (x in 0 until px.width) for (y in 0 until px.height) out.merge(px[x, y], 1, Int::plus)
        // A 36dp tile at 420dpi is about 94x94px.
        assertTrue("claude schedules: terracotta tiles", (out[Color(0xFFD97757)] ?: 0) > 4_000)
        assertTrue("codex schedules: ink tiles", (out[Color(0xFF0D0D0D)] ?: 0) > 4_000)
    }
}
