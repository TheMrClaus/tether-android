package com.tether.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tether.app.ui.sidebar.PhoneDrawer
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-8znp and ta-1jj7 (owner-directed design): the open drawer pads each side by the larger of its design
 * minimum (8 / 8 / 8 / 12 dp), the system bars and the display cutout. The foot clears the navigation bar
 * (the web's `max(space-md, safe-area-inset-bottom)`), the top clears the status bar, and a landscape side
 * clears a cutout or a side navigation bar. The insets are injected as the DraftDockInsetBase does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SessionDrawerHostInsetsTest {
    @get:Rule val rule = createComposeRule()

    private data class Edges(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun px(dp: Dp, view: android.view.View) = (dp.value * view.resources.displayMetrics.density).toInt()

    /** The content box's gaps to the window's four edges, in dp, once [inject] has dispatched its insets. */
    private fun edges(inject: (view: android.view.View, builder: WindowInsetsCompat.Builder) -> Unit): Edges {
        var view: android.view.View? = null
        rule.setContent {
            view = LocalView.current
            TetherTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.fillMaxSize().testTag("window")) {
                        SessionDrawerHost(open = true) { Box(Modifier.fillMaxSize().testTag("content")) }
                    }
                }
            }
        }
        rule.waitForIdle()
        // After the first composition: Compose installs its insets listener on the view then.
        val builder = WindowInsetsCompat.Builder()
        inject(view!!, builder)
        rule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view!!, builder.build()) }
        rule.waitForIdle()
        val root = rule.onRoot().getBoundsInRoot()
        val c = rule.onNodeWithTag("content").getBoundsInRoot()
        return Edges(c.left.value - root.left.value, c.top.value - root.top.value, root.right.value - c.right.value, root.bottom.value - c.bottom.value)
    }

    private fun WindowInsetsCompat.Builder.bar(type: Int, l: Int, t: Int, r: Int, b: Int) {
        val i = Insets.of(l, t, r, b)
        setInsets(type, i).setInsetsIgnoringVisibility(type, i).setVisible(type, true)
    }

    private fun assertEdges(e: Edges, left: Float, top: Float, right: Float, bottom: Float) {
        assertEquals("left", left, e.left, 0.6f)
        assertEquals("top", top, e.top, 0.6f)
        assertEquals("right", right, e.right, 0.6f)
        assertEquals("bottom", bottom, e.bottom, 0.6f)
    }

    @Test fun withNoInsetsTheContentSitsAtTheDesignMinimum() {
        val e = edges { _, _ -> }
        assertEdges(e, PhoneDrawer.Edge.value, PhoneDrawer.Edge.value, PhoneDrawer.Edge.value, PhoneDrawer.Bottom.value)
        assertEquals(8f, e.left, 0.6f)
        assertEquals(12f, e.bottom, 0.6f)
    }

    @Test fun theFootClearsTheNavigationBar() {
        val e = edges { v, b -> b.bar(WindowInsetsCompat.Type.navigationBars(), 0, 0, 0, px(48.dp, v)) }
        assertEdges(e, 8f, 8f, 8f, 48f)
    }

    @Test fun aShortNavigationBarLeavesTheDesignFoot() {
        // A gesture bar of 20 dp is above the 12 dp minimum: the larger wins.
        val e = edges { v, b -> b.bar(WindowInsetsCompat.Type.navigationBars(), 0, 0, 0, px(20.dp, v)) }
        assertEdges(e, 8f, 8f, 8f, 20f)
    }

    @Test fun theTopClearsTheStatusBar() {
        val e = edges { v, b -> b.bar(WindowInsetsCompat.Type.statusBars(), 0, px(24.dp, v), 0, 0) }
        assertEdges(e, 8f, 24f, 8f, 12f)
    }

    @Test fun aDisplayCutoutOnTheStartSideIsCleared() {
        val e = edges { v, b -> b.bar(WindowInsetsCompat.Type.displayCutout(), px(32.dp, v), 0, 0, 0) }
        assertEdges(e, 32f, 8f, 8f, 12f)
    }

    @Test fun aSideNavigationBarIsClearedOnEitherSide() {
        val left = edges { v, b -> b.bar(WindowInsetsCompat.Type.navigationBars(), px(48.dp, v), 0, 0, 0) }
        assertEdges(left, 48f, 8f, 8f, 12f)
    }

    @Test fun aSideNavigationBarOnTheEndIsCleared() {
        val right = edges { v, b -> b.bar(WindowInsetsCompat.Type.navigationBars(), 0, 0, px(48.dp, v), 0) }
        assertEdges(right, 8f, 8f, 48f, 12f)
    }
}
