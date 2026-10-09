package com.tether.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.LayoutDirection
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-n1jk: the drawer enters from the START edge, so a closed panel waits off the start side: left in LTR,
 * right in RTL. Reverting the `rtl` argument of drawerOffsetFraction at the call site parks it on the wrong side.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SessionDrawerHostRtlTest {
    @get:Rule val rule = createComposeRule()

    private fun panel(open: Boolean, dir: LayoutDirection): Pair<androidx.compose.ui.unit.DpRect, androidx.compose.ui.unit.DpRect> {
        rule.setContent {
            TetherTheme {
                CompositionLocalProvider(LocalReducedMotion provides true, LocalLayoutDirection provides dir) {
                    Box(Modifier.fillMaxSize()) { SessionDrawerHost(open = open) { Box(Modifier.fillMaxSize()) } }
                }
            }
        }
        rule.waitForIdle()
        val root = rule.onRoot().getBoundsInRoot()
        val p = rule.onNodeWithTag(ShellTags.Drawer, useUnmergedTree = true).getBoundsInRoot()
        return androidx.compose.ui.unit.DpRect(root.left, root.top, root.right, root.bottom) to p
    }

    @Test fun inRtlTheClosedPanelWaitsOffTheRightEdge() {
        val (root, p) = panel(open = false, LayoutDirection.Rtl)
        assertTrue("the closed panel starts at or past the right edge: ${p.left} vs ${root.right}", p.left >= root.right)
    }

    @Test fun inLtrTheClosedPanelWaitsOffTheLeftEdge() {
        val (root, p) = panel(open = false, LayoutDirection.Ltr)
        assertTrue("the closed panel ends at or before the left edge: ${p.right} vs ${root.left}", p.right <= root.left)
    }

    @Test fun whenOpenThePanelCoversTheWindowInRtl() {
        val (root, p) = panel(open = true, LayoutDirection.Rtl)
        assertTrue("open panel covers: $p vs $root", p.left <= root.left && p.right >= root.right)
    }
}
