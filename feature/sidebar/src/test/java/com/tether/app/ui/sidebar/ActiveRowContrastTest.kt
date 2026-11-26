package com.tether.app.ui.sidebar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P8A-FIX2: the current-session row paints the skin's --bg-panel (web `.session-item-row.is-current`), so its status
 * line (`muted`) and its name (`ink-strong`) keep 4.5:1 against the face the row really drew, in Light and Dark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ActiveRowContrastTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    private fun lum(c: Color): Double {
        fun ch(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }
    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = lum(a).let { x -> lum(b).let { y -> if (x > y) x to y else y to x } }
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun check(skin: TetherSkin) {
        var t: TetherTokens? = null
        rule.setContent {
            com.tether.app.ui.theme.TetherTheme(choiceFor(skin)) { t = com.tether.app.ui.theme.LocalTetherTokens.current }
            SidebarUnderTest(skin, F.state(listOf(F.live("c1", "Current one", ago = 0)), activeId = "c1"), TetherLayoutClass.Phone)
        }
        rule.waitForIdle()
        val bmp = rule.onNodeWithTag(SidebarTags.row("live:c1")).captureToImage().toPixelMap()
        val face = bmp[bmp.width / 2, 1]
        val tk = t!!
        assertEquals("active row face is --bg-panel ($skin)", tk.css.bgPanel.toArgb(), face.toArgb())
        val status = ratio(tk.muted, face)
        val name = ratio(tk.css.inkStrong, face)
        assertTrue("status line vs row face $status ($skin)", status >= 4.5)
        assertTrue("name vs row face $name ($skin)", name >= 4.5)
    }

    @Test fun lightActiveRowKeepsContrast() = check(TetherSkin.Studio)
    @Test fun darkActiveRowKeepsContrast() = check(TetherSkin.StudioDark)
}
