package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val PANE_TAG = "footer-fit-pane"
private const val REFERENCE_TAG = "footer-fit-reference"

/** The device rail an Expanded window takes beside the chat (W23, 268.6 dp): the pane is the window less it. */
private const val RAIL_DP = 268.6f

/**
 * Chat with a busy turn (Queue + Interrupt) in a pane of [windowDp] less the rail, at [fontScale], next to a reference pane
 * wide enough for every key at its natural width (the web's keys never shrink: `.chat-composer-actions { flex: 0 0 auto }`).
 */
private fun ComposeContentTestRule.hostFooter(windowDp: Int, fontScale: Float, skin: TetherSkin = TetherSkin.Studio) {
    RuntimeEnvironment.setFontScale(fontScale)
    val session = chatSession("s1", historyId = null)
    val client = ChatTestClient().also {
        it.show(session, ComposerFixtures.busy)
        it.sessionControls.value = mapOf("s1" to SessionControlFixtures.claudeIdleControls.copy(sessionId = "s1"))
    }
    val vm = TetherViewModel(client)
    val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
    setContent {
        TetherTheme(choiceFor(skin)) {
            val projections by client.projections.collectAsStateWithLifecycle()
            Column {
                Box(Modifier.width((windowDp - RAIL_DP).dp).height(480.dp).testTag(PANE_TAG)) {
                    ChatScreen(vm = vm, session = session, projection = projections[session.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
                }
                Box(Modifier.requiredWidth(1100.dp).height(480.dp).testTag(REFERENCE_TAG)) {
                    ChatScreen(vm = vm, session = session, projection = projections[session.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
                }
            }
        }
    }
    mainClock.advanceTimeBy(SETTLE_MS)
    waitForIdle()
    mainClock.advanceTimeBy(SETTLE_MS)
    waitForIdle()
}

private fun ComposeContentTestRule.inPane(pane: String, matcher: androidx.compose.ui.test.SemanticsMatcher) =
    onNode(matcher and hasAnyAncestor(hasTestTag(pane)))

private fun ComposeContentTestRule.queue(pane: String) = inPane(pane, hasContentDescription("Queue message"))
private fun ComposeContentTestRule.interrupt(pane: String) = inPane(pane, hasTestTag(INTERRUPT_KEY_TAG))
private fun ComposeContentTestRule.totals(pane: String) = inPane(pane, hasTestTag(COMPOSER_TOTALS_TAG))
private fun ComposeContentTestRule.totalsCount(pane: String) =
    onAllNodes(hasTestTag(COMPOSER_TOTALS_TAG) and hasAnyAncestor(hasTestTag(pane))).fetchSemanticsNodes().size

private fun SemanticsNodeInteraction.rect(): Rect = fetchSemanticsNode().boundsInRoot

/**
 * ta-9mcp (W24): the composer footer at 800-900 dp panes, at font scale 1.0 / 1.3 / 2.0, against the web at 29537e0
 * (`b-{800,845}x1024-x1.0{,-footer}.json`, the M3 x2 probe). The totals readout belongs to `.chat-composer-toolbar`'s container
 * query (`max-width: 28rem` hides it: the toolbar's CONTENT, the well less its border and padding: 441.2 at 800, 486.2 at 845);
 * the keys keep their natural width and the totals strip takes the leftover and hard-clips its text (no ellipsis, no rank
 * ladder, no hidden key).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h1024dp-420dpi")
class ComposerFooterFitTest(private val windowDp: Int, private val fontScale: Float) {
    @get:Rule val rule = createComposeRule()
    private val d get() = rule.density.density

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp x{1}")
        fun params(): List<Array<Any>> = listOf(800, 802, 804, 845, 860, 900).flatMap { w -> listOf(1.0f, 1.3f, 2.0f).map { arrayOf<Any>(w, it) } }
    }

    @Test fun theKeysKeepTheirNaturalWidthAndTheTotalsReadoutFollowsTheContainerQuery() {
        rule.hostFooter(windowDp, fontScale)
        // (a) Queue and Interrupt are as wide as they are in a pane with room to spare.
        assertEquals("Queue width", rule.queue(REFERENCE_TAG).rect().width, rule.queue(PANE_TAG).rect().width, 0.5f)
        assertEquals("Interrupt width", rule.interrupt(REFERENCE_TAG).rect().width, rule.interrupt(PANE_TAG).rect().width, 0.5f)
        // (b) the container query: the toolbar's content is 441.2 at the 800 pane (hidden), 486.2 at 845 (shown).
        // The app's toolbar content is the window less 355.4 dp (444.6 at 800, 446.6 at 802 hidden, 448.6 at 804 shown, 450.7 at 806: the
        // web's 441.2 at 800 is 3.4 dp narrower, so the web's own edge is 806.6 dp; ta-n1jk/ta-fmdq receipt). 802 and 804 straddle 448
        // by more than the pixel rounding (0.1 dp); 807 is rounding-dependent in the web's numbers, so it is not a window.
        val shown = windowDp >= 804
        assertEquals("the totals readout at ${windowDp}dp", if (shown) 1 else 0, rule.totalsCount(PANE_TAG))
        if (!shown) return
        val totals = rule.totals(PANE_TAG).rect()
        val queue = rule.queue(PANE_TAG).rect()
        val attach = rule.inPane(PANE_TAG, hasContentDescription("Add attachment")).rect()
        // The strip is between the keys: clear of Queue by the 8 dp gap, at the left where the readout always began.
        assertTrue("the totals end ${totals.right / d} before Queue ${queue.left / d} less 8 dp", totals.right <= queue.left - 8 * d + 0.5f)
        assertTrue("the totals start after the keys: ${totals.left / d} vs ${attach.right / d}", totals.left > attach.right)
        // (c) when the leftover is less than the readout, it is clipped (the strip is narrower than the readout's natural width, the
        // one the reference pane gives), not squeezed or wrapped; where it fits, the strip is at least that wide.
        val ref = rule.totals(REFERENCE_TAG)
        val natural = ref.fetchSemanticsNode().children.fold(0f) { acc, n -> maxOf(acc, n.boundsInRoot.right) } - ref.rect().left
        assertTrue("the readout has a natural width: $natural", natural > 0f)
        if (windowDp == 845 && fontScale == 2.0f) {
            assertTrue("at 845 x2.0 the readout (${natural / d} dp) is wider than its strip (${totals.width / d} dp)", natural > totals.width + 0.5f)
        } else if (fontScale == 1.0f && windowDp >= 845) {
            assertTrue("at x1.0 the readout is whole: ${natural / d} dp in ${totals.width / d} dp", natural <= totals.width + 0.5f)
        }
    }

    // (d) at 845 x1.0 the readout starts where it always did: after the browser key and the 8 dp gap.
    @Test fun theTotalsStartAfterTheBrowserKeyAtTheNormalScale() {
        if (!(windowDp == 845 && fontScale == 1.0f)) return
        rule.hostFooter(windowDp, fontScale)
        val browser = rule.inPane(PANE_TAG, hasTestTag(BrowserPaneTags.Toggle)).rect()
        assertEquals("the readout's left", browser.right + 8 * d, rule.totals(PANE_TAG).rect().left, 0.5f)
        val interrupt = rule.interrupt(PANE_TAG).rect()
        val well = rule.inPane(PANE_TAG, hasTestTag(CHAT_COMPOSER_WELL_TAG)).rect()
        assertEquals("Interrupt is at the toolbar's right edge (the 0.65rem padding inside the well)", well.right - 10.4f * d, interrupt.right, 0.5f)
    }
}

/** The busy footer in the 800 and 845 panes at x2.0 (Studio light): the keys whole, the totals gone / clipped. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h1024dp-420dpi")
class ComposerFooterFitScreenshotTest(private val windowDp: Int) {
    @get:Rule val rule = createComposeRule()

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp")
        fun params(): List<Array<Any>> = listOf(800, 845).map { arrayOf<Any>(it) }
    }

    @Test fun footer() {
        rule.hostFooter(windowDp, 2.0f)
        rule.inPane(PANE_TAG, hasTestTag(CHAT_COMPOSER_TAG)).captureRoboImage(
            "src/test/screenshots/composer-footer-fit/studio-w$windowDp-x2.0.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
        )
    }
}
