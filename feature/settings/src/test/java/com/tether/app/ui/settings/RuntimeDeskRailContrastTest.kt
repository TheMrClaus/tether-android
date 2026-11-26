package com.tether.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.ProvideTokenScope
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P8A-FIX3 B1: the Settings rail's selected row (the row the expanded rail really calls: `dark = false`) keeps 4.5:1 for
 * its title and supporting line against the face the row really drew, in Light and Dark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class RuntimeDeskRailContrastTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun lum(c: Color): Double {
        fun ch(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }
    private fun ratio(a: Color, b: Color): Double = lum(a).let { x -> lum(b).let { y -> (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) } }

    private fun check(skin: TetherSkin, dark: Boolean) {
        var t: TetherTokens? = null
        rule.setContent {
            TetherTheme(skin.mode) {
                ProvideTokenScope(RuntimeDeskRailScope) {
                    t = LocalTetherTokens.current
                    Box(Modifier.requiredWidth(192.dp).testTag("item")) {
                        RuntimeDeskItem(RuntimeDeskNavItem("p", "Preferences", "Choose startup, session behavior"), active = true, dark = dark, showChevron = false) {}
                    }
                }
            }
        }
        rule.waitForIdle()
        val tk = t!!
        val face = rule.onNodeWithTag("item").captureToImage().toPixelMap().let { it[2, it.height / 2] }
        val p = runtimeDeskItemPalette(tk, dark)
        assertEquals("selected face is the palette's active face ($skin)", p.activeBackground.toArgb(), face.toArgb())
        val title = ratio(p.activeText, face)
        val sub = ratio(p.supporting, face)
        assertTrue("selected title vs face $title ($skin)", title >= 4.5)
        assertTrue("selected supporting vs face $sub ($skin)", sub >= 4.5)
    }

    /** What the expanded rail passes (RuntimeDeskScaffold.kt: `RuntimeDeskList(..., dark = false, ...)`). */
    @Test fun lightSelectedRowKeepsContrast() = check(TetherSkin.Studio, dark = false)
    @Test fun darkSelectedRowKeepsContrast() = check(TetherSkin.StudioDark, dark = false)
}
