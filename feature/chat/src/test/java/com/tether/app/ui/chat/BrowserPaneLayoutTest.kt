package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tether.app.client.BrowserChannel
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every target in the pane's toolbar (design/pane-spec.md §7: 48dp on Android). */
private val ToolbarTargets = listOf(
    BrowserPaneTags.Reload,
    BrowserPaneTags.Url,
    BrowserPaneTags.Go,
    BrowserPaneTags.Close,
    BrowserPaneTags.preset("Mobile"),
    BrowserPaneTags.preset("Tablet"),
    BrowserPaneTags.preset("Desktop"),
    BrowserPaneTags.Fit,
    BrowserPaneTags.CustomWidth,
    BrowserPaneTags.CustomHeight,
    BrowserPaneTags.SetCustom,
    BrowserPaneTags.SelectElements,
    BrowserPaneTags.ShotOnPick,
)

private val Segments = listOf(BrowserPaneTags.preset("Mobile"), BrowserPaneTags.preset("Tablet"), BrowserPaneTags.preset("Desktop"), BrowserPaneTags.Fit)

private fun viewportState(width: Int, height: Int, mobile: Boolean) =
    BrowserChannel.State(url = "https://a.test/", viewport = BrowserChannel.Viewport(width, height, mobile, deviceScaleFactor = 1.0))

/** The pane alone (phone sheet) or in the 520dp side panel, the Studio skin, reduced motion. */
private class PaneHarness(private val rule: ComposeContentTestRule) {
    var ui by mutableStateOf(BrowserChannel.Ui(connected = true))

    fun show(width: Dp, height: Dp, panel: Boolean) {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.size(width, height)) {
                        val pane: @androidx.compose.runtime.Composable () -> Unit = {
                            BrowserPaneContent(ui = ui, frame = null, actions = BrowserPaneActions({}, { _, _, _ -> }, {}, {}, {}), modifier = Modifier.fillMaxSize())
                        }
                        if (panel) BrowserSidePanel(Modifier.align(Alignment.TopEnd)) { pane() } else pane()
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    fun set(next: BrowserChannel.Ui) {
        rule.runOnIdle { ui = next }
        rule.waitForIdle()
    }

    fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    fun dp(px: Float): Float = px / rule.density.density
}

private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }

/**
 * The pane's layout as design/pane-spec.md §1-§7 draws it on Android (browser-pane.tsx and
 * browser-pane.module.css 0bd07fdd): 48dp toolbar targets; Reload → address (Go inside) → Close
 * with no decorative icon; the presets and Fit as one segmented control where only the active
 * preset is selected and Fit never is; the size readout, the pick hint and the live state in the
 * status line; the 2dp loading bar; the error row above the status line; the empty-page invitation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BrowserPaneLayoutTest {
    @get:Rule val rule = createComposeRule()

    private val pane = PaneHarness(rule)

    @Test
    fun everyToolbarTargetIs48dpTallOnThePhoneSheet() {
        pane.show(412.dp, 780.dp, panel = false)
        ToolbarTargets.forEach { rule.onNodeWithTag(it).assertHeightIsEqualTo(48.dp) }
        rule.onNodeWithTag(BrowserPaneTags.Status).assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun thePresetsAndFitAreOneSegmentedControl_onlyTheActivePresetIsSelected_FitNever() {
        pane.show(412.dp, 780.dp, panel = false)
        val children = rule.onNodeWithTag(BrowserPaneTags.Segmented).onChildren().fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.TestTag) }
        assertEquals("the four segments, in order, in one control", Segments, children)
        fun selection() = Segments.map { rule.onNodeWithTag(it).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) }

        // 1024×768 matches no preset: every preset says not selected; Fit carries no selection at all.
        assertEquals(listOf(false, false, false, null), selection())
        pane.set(BrowserChannel.Ui(connected = true, state = viewportState(390, 844, mobile = true)))
        assertEquals(listOf(true, false, false, null), selection())
        pane.set(BrowserChannel.Ui(connected = true, state = viewportState(1440, 900, mobile = false)))
        assertEquals(listOf(false, false, true, null), selection())
        // What Fit sends (the stage's own size) is never shown as a selected Fit.
        pane.set(BrowserChannel.Ui(connected = true, state = viewportState(396, 297, mobile = false)))
        assertEquals(listOf(false, false, false, null), selection())
    }

    @Test
    fun onThePhoneTheFourSegmentsShareTheWidthAndTheSizeFieldsTakeTheirOwnRow() {
        pane.show(412.dp, 780.dp, panel = false)
        val widths = Segments.map { pane.dp(pane.bounds(it).width) }
        widths.forEach { assertEquals("equal segments: $widths", widths.first(), it, 1f) }
        assertTrue("W x H below the segments", pane.bounds(BrowserPaneTags.CustomWidth).top >= pane.bounds(BrowserPaneTags.Segmented).bottom)
        assertEquals(pane.bounds(BrowserPaneTags.CustomWidth).width, pane.bounds(BrowserPaneTags.CustomHeight).width, 1f)
    }

    @Test
    fun theAddressRowIsReloadThenTheFieldWithGoThenClose_andNothingElse() {
        pane.show(412.dp, 780.dp, panel = false)
        val paneBox = pane.bounds(BrowserPaneTags.Pane)
        val reload = pane.bounds(BrowserPaneTags.Reload)
        val url = pane.bounds(BrowserPaneTags.Url)
        val go = pane.bounds(BrowserPaneTags.Go)
        val close = pane.bounds(BrowserPaneTags.Close)
        assertEquals("Reload sits on the pane's edge", paneBox.left, reload.left, 1f)
        assertEquals("Close sits on the pane's edge", paneBox.right, close.right, 1f)
        assertTrue("Go is inside the address field, after the text", go.left >= url.right - 1f && go.right <= close.left)
        // The address field (the globe, then the text) starts right after Reload: no icon box between them.
        val fieldStart = pane.dp(url.left - reload.right)
        assertTrue("the field's text starts 6dp + 10dp + 14dp + 6dp after Reload, not after another box: $fieldStart", fieldStart in 34f..38f)
        val between = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode().all().filter { n ->
            val b = n.boundsInRoot
            b.width > 0f && b.top >= reload.top - 1f && b.bottom <= reload.bottom + 1f && b.left >= reload.right - 1f && b.right <= url.left + 1f
        }
        assertEquals("no node between Reload and the address", emptyList<String>(), between.map { it.config.toString() })
    }

    @Test
    fun theLoadingBarIsTwoDpAndOnlyThereWhileAPageLoads() {
        pane.show(412.dp, 780.dp, panel = false)
        rule.onNodeWithTag(BrowserPaneTags.LoadingBar).assertDoesNotExist()
        pane.set(BrowserChannel.Ui(connected = true, state = BrowserChannel.State(url = "https://a.test/", loading = true)))
        rule.onNodeWithTag(BrowserPaneTags.LoadingBar).assertHeightIsEqualTo(2.dp)
        assertTrue("the bar sits on the toolbar's bottom edge", pane.bounds(BrowserPaneTags.LoadingBar).bottom <= pane.bounds(BrowserPaneTags.Stage).top)
        pane.set(BrowserChannel.Ui(connected = true, state = BrowserChannel.State(url = "https://a.test/")))
        rule.onNodeWithTag(BrowserPaneTags.LoadingBar).assertDoesNotExist()
    }

    @Test
    fun theSizeReadoutAndThePickHintAreInTheStatusLine() {
        pane.show(412.dp, 780.dp, panel = false)
        pane.set(BrowserChannel.Ui(connected = true, state = viewportState(390, 844, mobile = true).copy(pickMode = true)))
        val status = pane.bounds(BrowserPaneTags.Status)
        rule.onNodeWithTag(BrowserPaneTags.Readout).assertTextEquals("390×844 · mobile")
        rule.onNodeWithTag(BrowserPaneTags.PickHint).assertTextEquals(BROWSER_PICK_HINT)
        listOf(BrowserPaneTags.Readout, BrowserPaneTags.PickHint, BrowserPaneTags.StatusStart, BrowserPaneTags.StatusUrl).forEach {
            val b = pane.bounds(it)
            assertTrue("$it inside the status line", b.top >= status.top - 1f && b.bottom <= status.bottom + 1f)
        }
        assertTrue("the readout is right of the live state", pane.bounds(BrowserPaneTags.Readout).left > pane.bounds(BrowserPaneTags.StatusStart).right)
    }

    @Test
    fun aServerErrorWhileConnectedIsAFullWidthRowAboveTheStatusLine() {
        pane.show(412.dp, 780.dp, panel = false)
        pane.set(BrowserChannel.Ui(connected = true, error = "navigation failed"))
        val row = pane.bounds(BrowserPaneTags.ErrorRow)
        assertEquals(pane.bounds(BrowserPaneTags.Pane).width, row.width, 1f)
        assertTrue("above the status line", row.bottom <= pane.bounds(BrowserPaneTags.Status).top)
        assertTrue("below the page", row.top >= pane.bounds(BrowserPaneTags.Stage).bottom)
        rule.onNodeWithTag(BrowserPaneTags.StatusError).assertTextEquals("navigation failed")
        rule.onNodeWithTag(BrowserPaneTags.Blank).assertDoesNotExist()
    }

    @Test
    fun theEmptyPageInvitationShowsOnlyOnABlankConnectedPage() {
        pane.show(412.dp, 780.dp, panel = false)
        rule.onNodeWithTag(BrowserPaneTags.Blank).assertExists()
        pane.set(BrowserChannel.Ui(connected = true, state = BrowserChannel.State(loading = true)))
        rule.onNodeWithTag(BrowserPaneTags.Blank).assertDoesNotExist()
        pane.set(BrowserChannel.Ui(connected = false))
        rule.onNodeWithTag(BrowserPaneTags.Blank).assertDoesNotExist()
        rule.onNodeWithTag(BrowserPaneTags.Stage).assertExists()
        pane.set(BrowserChannel.Ui(connected = true, state = BrowserChannel.State(url = "https://a.test/")))
        rule.onNodeWithTag(BrowserPaneTags.Blank).assertDoesNotExist()
        pane.set(BrowserChannel.Ui(connected = false, error = "refused"))
        rule.onNodeWithTag(BrowserPaneTags.Dead).assertExists()
        rule.onNodeWithTag(BrowserPaneTags.Stage).assertDoesNotExist()
    }

    @Test
    fun theTabIndicatorSaysHowManyTabsWhenNoneIsActive() {
        assertEquals("3 tabs", browserTabLabel(List(3) { BrowserChannel.Tab(it + 1, active = false, url = "u", title = "") }))
        assertEquals("Tab 2 of 2 · Docs", browserTabLabel(listOf(BrowserChannel.Tab(1, false, "a", ""), BrowserChannel.Tab(2, true, "b", "Docs"))))
    }
}

/** The 520dp side panel (a pane of 30rem and wider): the three-column tools. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class BrowserPaneWideLayoutTest {
    @get:Rule val rule = createComposeRule()

    private val pane = PaneHarness(rule)

    @Test
    fun everyToolbarTargetIs48dpTallInTheSidePanel() {
        pane.show(1280.dp, 800.dp, panel = true)
        ToolbarTargets.forEach { rule.onNodeWithTag(it).assertHeightIsEqualTo(48.dp) }
    }

    @Test
    fun theSegmentsAndTheSizeShareARow_SelectElementsAndScreenshotTheNext() {
        pane.show(1280.dp, 800.dp, panel = true)
        val seg = pane.bounds(BrowserPaneTags.Segmented)
        val w = pane.bounds(BrowserPaneTags.CustomWidth)
        assertEquals("one row", seg.top, w.top, 1f)
        assertTrue("the size group on the right", w.left > seg.right)
        assertEquals("W is 56dp", 56f, pane.dp(w.width), 1f)
        val pick = pane.bounds(BrowserPaneTags.SelectElements)
        val shot = pane.bounds(BrowserPaneTags.ShotOnPick)
        assertEquals(pick.top, shot.top, 1f)
        assertTrue(pick.top >= seg.bottom && shot.left > pick.right)
    }
}
