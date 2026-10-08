package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-8h5k: the band's edges, so an off-by-one in a gate is caught. The shell switches at 768 dp, the web's 48rem (Phone below, Expanded
 * from it; ta-09ca); the composer's sheet key becomes the options row at 1024 dp (the web's 64rem); the rail side flips at 1024 dp.
 *
 *  w767: Phone         - one row (the 44 dp key shares the footer's row), rail right, transcript pads 16 / 16 (the rail overlays the right 16 and the rows under it, ta-xxda).
 *  w768: Expanded<1024 - the 36 dp pill row above the footer, rail right, pads 48 left / 32 right (rail overlays).
 *  w1023: the same.
 *  w1024: Expanded wide - no sheet key (the options row above the footer), rail LEFT, pads 32 + the rail's 54.4 on the left, 32 right.
 *
 * No golden: the geometry is asserted in numbers, and the band's pictures are already recorded at w914 (mid-band/).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w914dp-h800dp-420dpi")
class MidBandEdgesTest {
    @get:Rule val rule = createComposeRule()
    private val d get() = rule.density.density
    private fun dp(px: Float) = px / d

    private val reply = "A reply that is long enough to wrap across the whole transcript column so that its right edge is the column's. ".repeat(6)

    private fun host() {
        val session = chatSession("s1", historyId = null)
        val client = ChatTestClient().also {
            it.show(session, ChatFixtures.fold(*ChatFixtures.turn("t1", "Summarize the README in one line.", reply, ChatFixtures.T_IDLE)))
            it.sessionControls.value = mapOf("s1" to SessionControlFixtures.claudeIdleControls.copy(sessionId = "s1"))
        }
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize()) {
                    ChatScreen(vm = vm, session = session, projection = projections[session.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
                }
            }
        }
        repeat(2) {
            rule.mainClock.advanceTimeBy(SETTLE_MS)
            rule.waitForIdle()
        }
    }

    private fun tagBounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun attach(): Rect = rule.onNodeWithContentDescription("Add attachment").fetchSemanticsNode().boundsInRoot
    private fun replyBounds(): Rect = rule.onNode(hasText("A reply that is long enough", substring = true)).fetchSemanticsNode().boundsInRoot

    private fun near(what: String, expected: Double, actual: Float, tolerance: Double) =
        assertEquals("$what (expected $expected, got $actual)", expected, actual.toDouble(), tolerance)

    private fun assertPhoneBand(width: Int) {
        host()
        val trigger = tagBounds("session-settings-trigger")
        near("the key is the 44 dp phone key", 44.0, dp(trigger.height), 0.5)
        near("and shares the footer row (its top is the attach key top)", dp(attach().top).toDouble(), dp(trigger.top), 1.0)
        val rail = tagBounds(TIMELINE_TAG)
        assertTrue("phone rail is on the right", dp(rail.right) > width - 2f && dp(rail.left) > width / 2f)
        val text = replyBounds()
        near("phone content left", 16.0, dp(text.left), 0.75)
        near("phone content right (16: the rail overlays it, ta-xxda)", (width - 16).toDouble(), dp(text.right), 0.75)
    }

    private fun assertMidBand(width: Int) {
        host()
        val trigger = tagBounds("session-settings-trigger")
        near("the key is the 36 dp pill", 36.0, dp(trigger.height), 0.5)
        near("its row is 44 above the footer's", 44.0, dp(attach().top - trigger.top), 0.5)
        val rail = tagBounds(TIMELINE_TAG)
        assertTrue("mid-band rail is on the right", dp(rail.right) > width - 2f && dp(rail.left) > width / 2f)
        val text = replyBounds()
        near("mid-band content left", 48.0, dp(text.left), 0.75)
        near("mid-band content right (32; the rail overlays it)", (width - 32).toDouble(), dp(text.right), 0.75)
        assertTrue("the rail overlays the right padding", dp(rail.left) < dp(text.right))
    }

    @Test @Config(qualifiers = "w767dp-h800dp-420dpi")
    fun at767TheComposerIsThePhonesOneRowAndTheRailAndInsetsAreThePhones() = assertPhoneBand(767)

    @Test @Config(qualifiers = "w768dp-h800dp-420dpi")
    fun at768TheComposerHasThePillRowAndTheRailIsOnTheRightWithTheBandsInsets() = assertMidBand(768)

    @Test @Config(qualifiers = "w1023dp-h800dp-420dpi")
    fun at1023TheBandIsStillTheMidBand() = assertMidBand(1023)

    @Test @Config(qualifiers = "w1024dp-h800dp-420dpi")
    fun at1024TheComposerHasTheWideOptionsRowAndTheRailIsOnTheLeft() {
        host()
        rule.onNodeWithTag("session-settings-trigger").assertDoesNotExist()
        val model = tagBounds("control-model")
        assertTrue("the options row is above the footer", model.bottom <= attach().top + 1f)
        val rail = tagBounds(TIMELINE_TAG)
        assertTrue("the wide rail is on the left: ${dp(rail.left)}", dp(rail.left) < 200f)
        val text = replyBounds()
        near("wide content left (32 + the rail's 54.4)", 86.4, dp(text.left), 0.75)
        near("wide content right", (1024 - 32).toDouble(), dp(text.right), 0.75)
    }
}
