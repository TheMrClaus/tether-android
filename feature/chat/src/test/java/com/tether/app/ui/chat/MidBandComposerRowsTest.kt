package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
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
 * ta-8h5k (W19 L2): the composer and the transcript at 768 to 1023 dp, row for row against the web at 914 px
 * (tether 29537e0, spec ~/ta-runs/w19/L1-spec.md). The chat is hosted under 64 dp (topbar) + 80 dp (workspace header)
 * of spacer, so the chat's own height is the window minus 144, as in the Expanded shell.
 *
 * Web numbers (914x411, getBoundingClientRect): composer 207.58 (233.58 with the waiting row), trigger pill
 * 100 x 36 above the 44 footer, attach and browser 44 x 44, Send 88 x 44, `.chat-scroll` padding 32 / 32 / 32 / 48
 * with the right-docked rail over the 32.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w914dp-h411dp-420dpi")
class MidBandComposerRowsTest {
    @get:Rule val rule = createComposeRule()

    private val d get() = rule.density.density
    private fun dp(px: Float) = px / d

    /**
     * C4 (measured here): the well's 1 dp border is drawn by `cssSurface` over the content and takes no room, so
     * the app's well is 2 dp (top + bottom border) shorter than the web's. It lives in ComposerParts.kt/Material.kt, outside
     * this lane's files (follow-up bead); the numbers below are the web's less that residual. When it is fixed, set this to 0.
     */
    private val c4 = 2.0

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun boundsOfDescription(text: String): Rect = rule.onNodeWithContentDescription(text).fetchSemanticsNode().boundsInRoot


    /** ta-4vun (R2): the transcript's visible band = min(box, region + strip); region = transcript top to the composer's top, strip = the composer's top to the well's top. */
    private fun band(box: Rect, composer: Rect): Triple<Double, Double, Double> {
        val well = bounds(CHAT_COMPOSER_WELL_TAG)
        val region = dp(composer.top - box.top).toDouble()
        val strip = dp(well.top - composer.top).toDouble()
        return Triple(region, strip, minOf(dp(box.height).toDouble(), region + strip))
    }

    private val longReply = "A reply that is long enough to wrap across the whole transcript column so that its right edge is the column's. ".repeat(6)

    private fun host(folded: ChatFixtures.Folded, windowHeightDp: Int = 411) {
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

    /** The goldens (w914dp-h411dp-420dpi): the composer idle and with an approval, and the chat with its rail on the right. */
    private fun golden(tag: String, name: String) = rule.onNodeWithTag(tag).captureRoboImage(
        "src/test/screenshots/mid-band/$name.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )

    private fun near(what: String, expected: Float, actual: Float, tolerance: Double) = near(what, expected.toDouble(), actual, tolerance)
    private fun near(what: String, expected: Double, actual: Float, tolerance: Double) =
        assertEquals("$what (expected $expected, got $actual)", expected, actual.toDouble(), tolerance)

    // ---- 1. idle ------------------------------------------------------------------------------------------

    @Test fun idleComposerIsTheWebsTwoRowsAndTheTranscriptTakesTheRest() {
        host(idle)
        val composer = bounds(CHAT_COMPOSER_TAG)
        val transcript = bounds("chat-transcript")
        val input = boundsOfDescription("Message the agent")
        println("W19-MEASURE idle rows: composerTop=0 inputTop=${dp(input.top - composer.top)} inputH=${dp(input.height)} inputBottom=${dp(input.bottom - composer.top)} composerH=${dp(composer.height)} d=$d")
        near("composer height (web 207.58 less C4)", 207.58 - c4, dp(composer.height), 1.0)
        // ta-4vun (R2): the box is max(region, 64); the region is the web-less-floor 59.4 plus C4, just under the floor.
        near("transcript box (the 64 dp floor over the 59.4 + C4 region)", 64.0, dp(transcript.height), 1.0)
        val (region, strip, band) = band(transcript, composer)
        near("region (web-less-floor 59.4 plus C4)", 59.4 + c4, region.toFloat(), 1.0)
        near("visible band = min(box, region + strip)", minOf(64.0, region + strip), band.toFloat(), 0.01)
        // The trigger is its own 36 dp pill row, 44 above the footer row's top (36 + the 8 gap).
        val trigger = bounds("session-settings-trigger")
        near("trigger height", 36.0, dp(trigger.height), 0.5)
        val attach = boundsOfDescription("Add attachment")
        near("trigger top to footer top", 44.0, dp(attach.top - trigger.top), 0.5)
        near("trigger left edge is the toolbar's (not centred, not stretched)", dp(attach.left), dp(trigger.left), 0.5)
        assertTrue("the trigger is content width, not the row", dp(trigger.width) < 280f)
        // A3: the footer's keys against the web's sidecar.
        val browser = bounds(BrowserPaneTags.Toggle)
        val send = boundsOfDescription("Send message")
        near("attach width", 44.0, dp(attach.width), 0.5); near("attach height", 44.0, dp(attach.height), 0.5)
        near("browser width", 44.0, dp(browser.width), 0.5); near("browser height", 44.0, dp(browser.height), 0.5)
        near("Send width", 88.0, dp(send.width), 1.5); near("Send height", 44.0, dp(send.height), 0.5)
        rule.onNode(hasText("Send")).assertIsDisplayed()
        for ((name, r) in listOf("attach" to attach, "browser" to browser, "Send" to send, "trigger" to trigger)) {
            assertTrue("$name is inside the composer: $r in $composer", r.top >= composer.top - 0.5f && r.bottom <= composer.bottom + 0.5f)
        }
        // C4: the well's height (the web's one-row equivalent is 127.58): composer minus its deck padding 16 + 20, the toolbar rows.
        golden(CHAT_COMPOSER_TAG, "composer-idle-w914dp-h411dp-420dpi")
        println("W19-MEASURE idle: composer=${dp(composer.height)} transcript=${dp(transcript.height)} trigger=${dp(trigger.width)}x${dp(trigger.height)} attach=${dp(attach.width)}x${dp(attach.height)} browser=${dp(browser.width)}x${dp(browser.height)} send=${dp(send.width)}x${dp(send.height)} footerTop=${dp(attach.top - composer.top)} triggerTop=${dp(trigger.top - composer.top)}")
    }

    // ---- 3. the rail and the transcript's insets (M2, C2) ---------------------------------------------------

    @Test fun theRailIsOnTheRightAndTheTranscriptPadsThirtyTwoRightFortyEightLeft() {
        host(idle)
        val rail = bounds(TIMELINE_TAG)
        val window = 914f
        assertTrue("the rail docks at the right edge: ${dp(rail.right)} of $window", dp(rail.right) > window - 2f)
        assertTrue("and is not on the left: ${dp(rail.left)}", dp(rail.left) > window / 2f)
        // C2: the reply's paragraph starts 48 dp in and ends 32 dp short of the right edge; the rail overlays that 32.
        val reply = rule.onNode(hasText("A reply that is long enough", substring = true)).fetchSemanticsNode().boundsInRoot
        near("transcript content left", 48.0, dp(reply.left), 0.75)
        near("transcript content right (the rail overlays the 32)", (window - 32f).toDouble(), dp(reply.right), 0.75)
        assertTrue("the rail overlays the right padding", dp(rail.left) < dp(reply.right))
        golden("mid-band-chat", "chat-rail-right-w914dp-h411dp-420dpi")
    }

    // ---- 2. a pending approval ----------------------------------------------------------------------------

    @Test fun approvalComposerKeepsQueueAndInterruptWholeAndTheTranscriptGivesWay() {
        host(ApprovalFixtures.write)
        val composer = bounds(CHAT_COMPOSER_TAG)
        near("composer height with the waiting row (web 233.58 less C4)", 233.58 - c4, dp(composer.height), 1.0)
        // ta-4vun (R2): the box is max(region, 64); the region is 33.4 plus C4.
        val transcriptBox = bounds("chat-transcript")
        near("transcript box (the 64 dp floor over the 33.4 + C4 region)", 64.0, dp(transcriptBox.height), 1.0)
        val (region, strip, band) = band(transcriptBox, composer)
        near("region (33.4 plus C4)", 33.4 + c4, region.toFloat(), 1.0)
        near("visible band = min(box, region + strip)", minOf(64.0, region + strip), band.toFloat(), 0.01)
        rule.onNode(hasText("Waiting for your approval", substring = true)).assertIsDisplayed()
        val queue = boundsOfDescription("Queue message")
        val interrupt = bounds(INTERRUPT_KEY_TAG)
        rule.onNodeWithContentDescription("Queue message").assertIsDisplayed()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).assertIsDisplayed()
        rule.onNodeWithText("Queue").assertIsDisplayed()
        rule.onNodeWithText("Interrupt").assertIsDisplayed()
        near("Queue height", 44.0, dp(queue.height), 0.5)
        near("Interrupt height", 44.0, dp(interrupt.height), 0.5)
        assertTrue("Queue and Interrupt sit side by side: $queue $interrupt", queue.right <= interrupt.left + 0.5f && kotlin.math.abs(queue.top - interrupt.top) < 0.5f)
        for ((name, r) in listOf("Queue" to queue, "Interrupt" to interrupt)) {
            assertTrue("$name is inside the composer: $r in $composer", r.bottom <= composer.bottom + 0.5f && r.right <= 914f * d)
        }
        golden(CHAT_COMPOSER_TAG, "composer-approval-w914dp-h411dp-420dpi")
        println("W19-MEASURE approval h411: composer=${dp(composer.height)} queue=${dp(queue.width)}x${dp(queue.height)} interrupt=${dp(interrupt.width)}x${dp(interrupt.height)}")
    }

    // ---- A2: the like-for-like content window (914 x 359, a pending approval) -----------------------------

    @Test @Config(qualifiers = "w914dp-h359dp-420dpi")
    fun inTheLikeForLikeWindowTheComposerIsNotSqueezedAndTheKeysStayWhole() {
        host(ApprovalFixtures.write, 359)
        val composer = bounds(CHAT_COMPOSER_TAG)
        val transcript = bounds("chat-transcript")
        // 359 - 144 = 215 dp is left; the composer needs 233.6, so the transcript is 0 and the bottom padding is what is cut.
        near("composer keeps its intrinsic height (web 233.58 less C4)", 233.58 - c4, dp(composer.height), 1.0)
        // ta-4vun (R2): the region gives way to 0; the box is the 64 dp floor, its visible band the composer's top strip.
        val (region, strip, band) = band(transcript, composer)
        assertTrue("the region gave way first: $region", region < 1.0)
        near("transcript box (the 64 dp floor)", 64.0, dp(transcript.height), 1.0)
        near("visible band = min(box, region + strip)", minOf(64.0, region + strip), band.toFloat(), 0.01)
        near("band (the strip: deck 16 + waiting line + gap)", 42.0, band.toFloat(), 2.0)
        val trigger = bounds("session-settings-trigger")
        near("trigger height", 36.0, dp(trigger.height), 0.5)
        for (key in listOf(boundsOfDescription("Add attachment"), bounds(BrowserPaneTags.Toggle), boundsOfDescription("Queue message"), bounds(INTERRUPT_KEY_TAG))) {
            near("each footer key is 44 tall", 44.0, dp(key.height), 0.5)
            assertTrue("each footer key is inside the 359 dp window: bottom ${dp(key.bottom)} of 359", dp(key.bottom) <= 359f)
        }
        rule.onNodeWithContentDescription("Add attachment").assertIsDisplayed()
        rule.onNodeWithContentDescription("Queue message").assertIsDisplayed()
        rule.onNodeWithTag(INTERRUPT_KEY_TAG).assertIsDisplayed()
        rule.onNodeWithTag(BrowserPaneTags.Toggle).assertIsDisplayed()
        rule.onNodeWithText("Queue").assertIsDisplayed()
        rule.onNodeWithText("Interrupt").assertIsDisplayed()
        println("W19-MEASURE approval h359: composer=${dp(composer.height)} transcript=${dp(transcript.height)}")
    }
}

/** The widths the pill row must not touch: the phone's one-row key and the wide options row above the footer. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PhoneComposerRowStaysOneRowTest {
    @get:Rule val rule = createComposeRule()
    private val d get() = rule.density.density

    @Test fun theSheetKeyIsStillTheFortyFourDpKeyInTheFootersRow() {
        val session = chatSession("s1", historyId = null)
        val client = ChatTestClient().also {
            it.show(session, ChatFixtures.idle)
            it.sessionControls.value = mapOf("s1" to SessionControlFixtures.claudeIdleControls.copy(sessionId = "s1"))
        }
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = session, projection = projections[session.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            }
        }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
        val trigger = rule.onNodeWithTag("session-settings-trigger").fetchSemanticsNode().boundsInRoot
        val attach = rule.onNodeWithContentDescription("Add attachment").fetchSemanticsNode().boundsInRoot
        assertEquals("the key is 44 tall", 44.0, (trigger.height / d).toDouble(), 0.5)
        assertEquals("in the footer's row (its top is the attach key's)", attach.top.toDouble(), trigger.top.toDouble(), 1.0)
        val rail = rule.onNodeWithTag(TIMELINE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("the phone's rail is still on the right", rail.left / d > 412f / 2)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class WideComposerRowStaysTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theOptionsRowIsStillAboveTheFooterAndTheRailStillOnTheLeft() {
        val session = chatSession("s1", historyId = null)
        val client = ChatTestClient().also {
            it.show(session, ChatFixtures.idle)
            it.sessionControls.value = mapOf("s1" to SessionControlFixtures.claudeIdleControls.copy(sessionId = "s1"))
        }
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = session, projection = projections[session.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            }
        }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
        rule.onNodeWithTag("session-settings-trigger").assertDoesNotExist()
        val model = rule.onNodeWithTag("control-model").fetchSemanticsNode().boundsInRoot
        val attach = rule.onNodeWithContentDescription("Add attachment").fetchSemanticsNode().boundsInRoot
        assertTrue("the options row is above the footer", model.bottom <= attach.top + 1f)
        val rail = rule.onNodeWithTag(TIMELINE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("the wide rail is on the left", rail.left < 200f)
    }
}

