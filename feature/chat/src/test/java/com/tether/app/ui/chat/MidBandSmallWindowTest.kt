package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-09ca (W20 A2): the 768-1023 band's composer in a window as short as the web's 780x360 (the web's innerHeight, a
 * like-for-like window; no height guard, 780x360 is desktop because the web is). Hosted like MidBandComposerRowsTest
 * (64 + 80 dp of spacer above the chat, so the chat's own height is 360 - 144 = 216).
 *
 * Idle: the composer is its intrinsic height and the transcript takes the 8.4 dp that is left. Approval pending: the
 * composer (233.58) does not fit in 216, the transcript gives way to 0 FIRST, and the overflow (about 17.6) falls only
 * on the composer's bottom padding, so Attach, Browser, Queue and Interrupt stay a full 44 tall, whole and inside the
 * window. The numbers are the web's less C4 (the app's well is 2 dp shorter, MidBandComposerRowsTest.c4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w780dp-h360dp-420dpi")
class MidBandSmallWindowTest {
    @get:Rule val rule = createComposeRule()

    private val d get() = rule.density.density
    private fun dp(px: Float) = px / d
    private val c4 = 2.0

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun boundsOfDescription(text: String): Rect = rule.onNodeWithContentDescription(text).fetchSemanticsNode().boundsInRoot
    private fun near(what: String, expected: Double, actual: Float, tolerance: Double) =
        assertEquals("$what (expected $expected, got $actual)", expected, actual.toDouble(), tolerance)

    private val longReply = "A reply that is long enough to wrap across the whole transcript column so that its right edge is the column's. ".repeat(6)

    private fun host(folded: ChatFixtures.Folded) {
        val session = chatSession("s1", historyId = null)
        val client = ChatTestClient().also {
            it.show(session, folded)
            it.sessionControls.value = mapOf("s1" to SessionControlFixtures.claudeIdleControls.copy(sessionId = "s1"))
        }
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(144.dp))
                    Box(Modifier.fillMaxSize().testTag("mid-band-chat")) {
                        ChatScreen(vm = vm, session = session, projection = projections[session.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private val idle get() = ChatFixtures.fold(*ChatFixtures.turn("t1", "Summarize the README in one line.", longReply, ChatFixtures.T_IDLE))

    @Test fun idleAt780x360TheComposerIsTheWebsAndTheTranscriptTakesTheEightDpLeft() {
        host(idle)
        val composer = bounds(CHAT_COMPOSER_TAG)
        val transcript = bounds("chat-transcript")
        near("composer height (web 207.58 less C4)", 207.58 - c4, dp(composer.height), 1.0)
        near("transcript height (216 - 207.58 = 8.4, plus C4)", 8.4 + c4, dp(transcript.height), 1.0)
        near("trigger height (the 36 pill row)", 36.0, dp(bounds("session-settings-trigger").height), 0.5)
        rule.onNodeWithTag(CHAT_COMPOSER_TAG).captureRoboImage(
            "src/test/screenshots/mid-band/composer-idle-w780dp-h360dp-420dpi.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
        )
        println("W20-MEASURE idle w780h360: composer=${dp(composer.height)} transcript=${dp(transcript.height)} c4=$c4 d=$d")
    }

    @Test fun approvalAt780x360TheComposerIsNotShrunkAndTheKeysStayWholeInsideTheWindow() {
        host(ApprovalFixtures.write)
        val composer = bounds(CHAT_COMPOSER_TAG)
        val transcript = bounds("chat-transcript")
        near("composer keeps its intrinsic height (web 233.58 less C4)", 233.58 - c4, dp(composer.height), 1.0)
        assertTrue("the transcript gave way to 0 first: ${dp(transcript.height)}", dp(transcript.height) < 1f)
        near("trigger height", 36.0, dp(bounds("session-settings-trigger").height), 0.5)
        val footer = boundsOfDescription("Add attachment")
        near("footer row height", 44.0, dp(footer.height), 0.5)
        for ((name, r) in listOf(
            "Attach" to footer,
            "Browser" to bounds(BrowserPaneTags.Toggle),
            "Queue" to boundsOfDescription("Queue message"),
            "Interrupt" to bounds(INTERRUPT_KEY_TAG),
        )) {
            near("$name is a full 44 tall", 44.0, dp(r.height), 0.5)
            assertTrue("$name is inside the 360 dp window: bottom ${dp(r.bottom)}", dp(r.bottom) <= 360f && dp(r.right) <= 780f && r.top >= 0f)
        }
        rule.onNodeWithContentDescription("Add attachment").assertIsDisplayed()
        rule.onNodeWithContentDescription("Queue message").assertIsDisplayed()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).assertIsDisplayed()
        rule.onNodeWithTag(BrowserPaneTags.Toggle).assertIsDisplayed()
        rule.onNodeWithText("Queue").assertIsDisplayed()
        rule.onNodeWithText("Interrupt").assertIsDisplayed()
        println("W20-MEASURE approval w780h360: composer=${dp(composer.height)} transcript=${dp(transcript.height)} footer=${dp(footer.height)} bottomOfFooter=${dp(footer.bottom)}")
    }

    /** The floor's composer at tablet portrait: 768 x 1024 is the same two rows, 207.58 (L1 spec section 2). */
    @Test @Config(qualifiers = "w768dp-h1024dp-420dpi")
    fun idleAt768x1024TheComposerIsTheWebsTwoRows() {
        host(idle)
        val composer = bounds(CHAT_COMPOSER_TAG)
        near("composer height (web 207.58 less C4)", 207.58 - c4, dp(composer.height), 1.0)
        near("trigger height", 36.0, dp(bounds("session-settings-trigger").height), 0.5)
        val rail = bounds(TIMELINE_TAG)
        assertTrue("the timeline rail is on the right: ${dp(rail.left)}", dp(rail.right) > 768f - 2f && dp(rail.left) > 768f / 2f)
    }
}
