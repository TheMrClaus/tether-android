package com.tether.app.ui.components

import android.os.VibrationEffect
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.isReducedMotion
import com.tether.app.ui.theme.tokensFor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MotionAndHapticsTest {
    @Test fun reducedMotionIsTheRemoveAnimationsSetting() {
        assertTrue(isReducedMotion(0f))
        assertFalse(isReducedMotion(1f))
        assertFalse(isReducedMotion(0.5f))
        assertFalse(isReducedMotion(10f))
        // An unreadable setting animates (the web's default is no-preference).
        assertFalse(isReducedMotion(null))
    }

    @Test fun hapticMomentMap() {
        val scrub = hapticSpec(HapticMoment.ScrubStep)
        assertEquals(VibrationEffect.Composition.PRIMITIVE_TICK, scrub.primitive)
        assertArrayEquals(longArrayOf(0, 20, 15, 20), scrub.fallback)
        assertEquals(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, hapticSpec(HapticMoment.KeyDown).primitive)
        assertEquals(VibrationEffect.Composition.PRIMITIVE_THUD, hapticSpec(HapticMoment.KeyUp).primitive)
        for (moment in HapticMoment.entries) {
            val spec = hapticSpec(moment)
            assertEquals(moment.name, 1.0f, spec.scale)
            assertEquals(moment.name, 0L, spec.fallback.first())
            assertTrue(moment.name, spec.fallback.drop(1).all { it > 0 })
        }
        // Every moment is distinguishable by feel.
        assertEquals(HapticMoment.entries.size, HapticMoment.entries.map { hapticSpec(it).primitive }.toSet().size)
    }

    @Test fun pingIsTheWebsTwoSecondRadar() {
        assertEquals(2000, PingCycleMs)
        assertEquals(7.2.dp, PingSpread)
    }

    @Test fun statusTones() {
        assertEquals(StatusTone.Active, statusToneOf("active"))
        assertEquals(StatusTone.Waiting, statusToneOf("waiting"))
        assertEquals(StatusTone.Ready, statusToneOf("ready"))
        assertEquals(StatusTone.Exited, statusToneOf("exited"))
        assertEquals(StatusTone.History, statusToneOf("something-new"))
    }

    @Test fun perfDotsParseOrNone() {
        assertEquals(null, perfDotColor("none"))
        val machine = tokensFor(TetherSkin.Machine).css.perfDots
        val precision = tokensFor(TetherSkin.Precision).css.perfDots
        assertTrue(machine, perfDotColor(machine) != null || machine == "none")
        assertTrue(precision, perfDotColor(precision) != null || precision == "none")
        assertEquals(null, perfDotColor(tokensFor(TetherSkin.Studio).css.perfDots))
    }

    @Test fun rockerGeometryMatchesTheCss() {
        val g = rockerGeometry(tokensFor(TetherSkin.Tactile))
        assertEquals(65.6f, g.width.value, 0.01f)
        assertEquals(28.8f, g.height.value, 0.01f)
        // left: 50% of the 63.6px padding box; width calc(50% - 1px); ON: translateX(-100% + 1px).
        assertEquals(31.8f, g.capLeftOff.value, 0.01f)
        assertEquals(30.8f, g.capWidth.value, 0.01f)
        assertEquals(2.0f, g.capLeftOn.value, 0.01f)
        assertEquals(22.8f, g.capHeight.value, 0.01f)
        val s = rockerGeometry(tokensFor(TetherSkin.Studio))
        assertEquals(40f, s.width.value, 0f)
        assertEquals(3f, s.capLeftOff.value, 0f)
        assertEquals(19f, s.capLeftOn.value, 0f)
    }

    @Test fun expandToggleWords() {
        assertEquals("Show less", expandToggleLabel(open = true, hidden = 12, locale = Locale.US))
        assertEquals("Show 1 more line", expandToggleLabel(false, 1, Locale.US))
        assertEquals("Show 4,000 more lines", expandToggleLabel(false, 4000, Locale.US))
        assertEquals("Show more", expandToggleLabel(false, 0, Locale.US))
        assertEquals("Show more", expandToggleLabel(false, null, Locale.US))
    }

    @Test fun hiddenRowsCountRowsBelowTheCut() {
        val tops = List(10) { it * 20f } // rows at 0,20,…,180
        // Cut at 100: the row at 99+ counts (a row straddling the cut does not).
        assertEquals(5, hiddenRowCount(tops, 20f, cut = 100f, hiddenPx = 100f))
        assertEquals(4, hiddenRowCount(tops, 20f, cut = 110f, hiddenPx = 90f))
        // Rows sharing a top edge (gutter + text) count once.
        assertEquals(1, hiddenRowCount(listOf(0f, 120f, 120.2f), 20f, 100f, 40f))
        assertEquals(0, hiddenRowCount(emptyList(), 20f, 100f, 40f))
    }

    @Test fun hugePayloadsEstimateFromTheFirstRow() {
        val tops = List(ExpandExactCountLimit + 1) { it * 10f }
        assertEquals(1500, hiddenRowCount(tops, 10f, cut = 100f, hiddenPx = 15000f))
        assertEquals(1, hiddenRowCount(tops, 10f, cut = 100f, hiddenPx = 2f))
        assertEquals(0, hiddenRowCount(tops, 0f, cut = 100f, hiddenPx = 2000f))
    }

    @Test fun overflowNeedsMoreThanTheSlop() {
        assertFalse(expandOverflows(contentPx = 108, clampPx = 100, slopPx = 8f))
        assertTrue(expandOverflows(contentPx = 109, clampPx = 100, slopPx = 8f))
        assertFalse(expandOverflows(contentPx = 50, clampPx = 100, slopPx = 8f))
    }
}
