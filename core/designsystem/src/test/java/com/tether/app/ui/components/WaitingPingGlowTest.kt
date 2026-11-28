package com.tether.app.ui.components

import androidx.compose.ui.graphics.Color
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.tokensFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W7-D: the waiting ping halo is the wait ramp's border step, and the waiting ink is amber (parity with web W7-A). */
class WaitingPingGlowTest {
    @Test fun haloIsTheWaitBorderTokenNotTheFocusGlow() {
        for (skin in TetherSkin.entries) {
            val t = tokensFor(skin)
            assertEquals(t.css.waitBorder, waitingPingGlow(t))
            assertNotEquals(t.focusGlow, waitingPingGlow(t))
        }
    }

    @Test fun haloValuesMatchTheWebWaitBorder() {
        assertEquals(Color(0xFFFBBF24), waitingPingGlow(tokensFor(TetherSkin.Studio)))
        assertEquals(Color(0xFF664C16), waitingPingGlow(tokensFor(TetherSkin.StudioDark)))
    }

    @Test fun waitingInkIsAmberAttentionInkAndReadable() {
        assertEquals(Color(0xFF9A4D06), tokensFor(TetherSkin.Studio).amber)
        assertEquals(Color(0xFFFBBF24), tokensFor(TetherSkin.StudioDark).amber)
        for (skin in listOf(TetherSkin.Studio, TetherSkin.StudioDark)) {
            val t = tokensFor(skin)
            assertEquals(t.attentionInk, t.amber)
            assertTrue(contrast(t.amber, t.amberWash) >= 4.5)
        }
    }

    private fun lum(c: Color): Double {
        fun f(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * f(c.red) + 0.7152 * f(c.green) + 0.0722 * f(c.blue)
    }
    private fun contrast(a: Color, b: Color): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }
}
