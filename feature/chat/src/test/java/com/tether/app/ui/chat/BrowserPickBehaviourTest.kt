package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import com.tether.app.client.AttachmentSendResult
import com.tether.app.client.BrowserChannel
import com.tether.app.client.BrowserPick
import com.tether.app.client.BrowserPicks
import com.tether.app.client.ElementDescriptor
import com.tether.app.protocol.Attachment
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val STATE_PICKING = """{"t":"state","url":"https://a.test/app","title":"A","viewport":{"width":390,"height":844,"mobile":true,"deviceScaleFactor":1},"pickMode":true}"""
private const val DESCRIPTOR = """{"selector":"#go","tag":"button","id":"go","classes":["btn"],"attributes":{"type":"submit"},"box":{"x":10,"y":20,"width":100,"height":40},"computedStyles":{"display":"block"},"role":"button","name":"Go","textContent":"Go now"}"""

/**
 * T8.6 part 2, the pane's side, against browser-pane.tsx (tether 90fbb9f): Select elements toggles
 * pick mode (:232-243), "Screenshot on pick" starts on (:53, :244-247), a drag in pick mode sends
 * `hover` and the reply is drawn (:250-254), a tap sends `pick {x,y,screenshot}` (:108-112), a
 * `picked` reply goes to `onPicked` with the page URL (:44-47), and `pick-empty` is no feedback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BrowserPickBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val opener = PaneOpener()
    private val picked = mutableListOf<Pair<BrowserPick, String>>()

    private fun show() {
        val channel = BrowserChannel(opener, "s1")
        channel.connect()
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = WellHeightPhone) {
                BrowserPane(channel, onClose = {}, modifier = Modifier.fillMaxSize(), onPicked = { p, url -> picked += p to url })
            }
        }
        rule.waitForIdle()
        opener.sockets.last().sent.clear()
    }

    private val socket get() = opener.sockets.last()

    private fun server(text: String) {
        rule.runOnIdle { socket.listener.onMessage(text) }
        rule.waitForIdle()
    }

    private fun picks() = socket.sent.filter { it.contains("\"pick\"") }

    @Test
    fun selectElementsTogglesPickMode() {
        show()
        rule.onNodeWithTag(BrowserPaneTags.SelectElements).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(BrowserPaneTags.SelectElements).assertIsOn()
        rule.onNodeWithTag(BrowserPaneTags.SelectElements).performClick()
        rule.waitForIdle()
        assertEquals(listOf("""{"t":"pick-mode","on":true}""", """{"t":"pick-mode","on":false}"""), socket.sent)
    }

    @Test
    fun aTapInPickModeSendsPickWithTheScreenshotFlagOnByDefault() {
        show()
        server(STATE_PICKING)
        val stage = rule.onNodeWithTag(BrowserPaneTags.Stage)
        val size = stage.fetchSemanticsNode().size
        stage.performTouchInput {
            down(Offset(size.width / 2f, size.height / 4f))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("""{"t":"pick","x":195,"y":211,"screenshot":true}"""), picks())
        // Nothing reaches the page.
        assertTrue(socket.sent.none { it.contains("\"input\"") })
    }

    @Test
    fun unticking_ScreenshotOnPick_sendsFalse() {
        show()
        server(STATE_PICKING)
        rule.onNodeWithTag(BrowserPaneTags.ShotOnPick).performClick()
        rule.onNodeWithTag(BrowserPaneTags.Stage).performTouchInput {
            down(center)
            up()
        }
        rule.waitForIdle()
        assertEquals(1, picks().size)
        assertTrue(picks().single().endsWith(""""screenshot":false}"""))
    }

    @Test
    fun aDragThatLeavesTheTapSlopIsNotAPick() {
        show()
        server(STATE_PICKING)
        rule.onNodeWithTag(BrowserPaneTags.Stage).performTouchInput {
            down(center)
            moveBy(Offset(120f, 120f))
            up()
        }
        rule.waitForIdle()
        assertTrue(picks().isEmpty())
    }

    @Test
    fun aDragInPickModeSendsHoverAndTheReplyIsDrawnUntilPickModeEnds() {
        show()
        server(STATE_PICKING)
        rule.onNodeWithTag(BrowserPaneTags.Stage).performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
            up()
        }
        rule.waitForIdle()
        val hovers = socket.sent.filter { it.startsWith("""{"t":"hover"""") }
        assertTrue("a hover per throttled move: $hovers", hovers.isNotEmpty())
        rule.onAllNodesWithTag(BrowserPaneTags.Highlight).assertCountEquals(0)
        server("""{"t":"hover","box":{"x":10,"y":20,"width":100,"height":40},"label":"button#go.btn"}""")
        rule.onNodeWithTag(BrowserPaneTags.Highlight).assertExists()
        rule.onNodeWithText("button#go.btn").assertExists()
        // Select elements off: the box goes (use-browser.ts :225), and the server's state says so too.
        rule.onNodeWithTag(BrowserPaneTags.SelectElements).performClick()
        server("""{"t":"state","url":"https://a.test/app","pickMode":false}""")
        rule.onAllNodesWithTag(BrowserPaneTags.Highlight).assertCountEquals(0)
    }

    @Test
    fun aPickedReplyGoesToTheComposerWithThePageUrl() {
        show()
        server(STATE_PICKING)
        server("""{"t":"picked","descriptor":$DESCRIPTOR,"screenshot":{"mediaType":"image/jpeg","data":"QUJD"}}""")
        rule.waitForIdle()
        val (pick, url) = picked.single()
        assertEquals("https://a.test/app", url)
        assertEquals("pick-1", pick.id)
        assertEquals("button", pick.descriptor.tag)
        assertEquals(Attachment("button-1.jpg", "image/jpeg", "QUJD"), pick.screenshot)
    }

    @Test
    fun aPickEmptyReplyIsNoFeedback() {
        show()
        server(STATE_PICKING)
        server("""{"t":"pick-empty"}""")
        rule.waitForIdle()
        assertTrue(picked.isEmpty())
        // The pane says nothing new: still picking, no error line.
        rule.onNodeWithTag(BrowserPaneTags.SelectElements).assertIsOn()
        rule.onNodeWithText("Live").assertExists()
    }
}

/**
 * T8.6 part 2, the composer's side, against chat-view.tsx (90fbb9f) :3173-3210 `submit` and
 * :4097-4120 (the chips): the picked elements show as chips (name, tag, " · shot"), go out with the
 * next message as the descriptor block ahead of the text with their screenshots as attachments, idle
 * only, and are cleared by a send that went (kept by one that did not).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BrowserPickComposerBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val textSends = mutableListOf<String>()
    private val pickSends = mutableListOf<Pair<String, List<Attachment>>>()
    private var cleared = 0
    private var answer = AttachmentSendResult.Sent

    private val shot = Attachment("button-1.jpg", "image/jpeg", "QUJD")
    private val withShot = BrowserPick("pick-1", ElementDescriptor(selector = "#go", tag = "button", name = "Go", box = ElementDescriptor.Box(10.0, 20.0, 100.0, 40.0)), shot)
    private val plain = BrowserPick("pick-2", ElementDescriptor(selector = "nav > a", tag = "a"), null)
    private var items by mutableStateOf(listOf<BrowserPick>())

    private fun show(fixture: ChatFixtures.Folded = ComposerFixtures.idle) {
        rule.setContent {
            ComposerHost(TetherSkin.StudioDark) {
                Composer(
                    session = ComposerFixtures.session,
                    projection = fixture.projection,
                    controls = null,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { text, _ -> textSends += text; true },
                    onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    liveness = ComposerLiveness.Live,
                    tree = fixture.tree,
                    browserPicks = ComposerPicks(
                        items = items,
                        pageUrl = "https://a.test/app",
                        onRemove = { pick -> items = items.filterNot { it === pick } },
                        onClear = { cleared++; items = emptyList() },
                        send = { text, shots ->
                            pickSends += text to shots
                            answer
                        },
                    ),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun input() = rule.onNodeWithContentDescription("Message the agent")
    private fun inputText(): String = input().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun pickedElementsShowAsChipsWithTheirNameTagAndShot() {
        items = listOf(withShot, plain)
        show()
        rule.onAllNodesWithTag(BrowserPickTags.Chip).assertCountEquals(2)
        rule.onNodeWithText("Go").assertExists()
        rule.onNodeWithText("button · shot").assertExists()
        // No a11y name: the selector; no screenshot: just the tag.
        rule.onNodeWithText("nav > a").assertExists()
        rule.onNodeWithText("a").assertExists()
        rule.onNodeWithContentDescription("Remove selected button").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(BrowserPickTags.Chip).assertCountEquals(1)
    }

    @Test
    fun theNextSendCarriesTheBlockAheadOfTheTextAndTheShotsAndClears() {
        items = listOf(withShot, plain)
        show()
        input().performTextInput("Make these bigger")
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        val block = BrowserPicks.formatDescriptorBlock(listOf(withShot.descriptor, plain.descriptor), "https://a.test/app")
        // chat-view.tsx :3197-3204: [block, text] joined by a blank line; the shots as attachments.
        assertEquals(listOf(block + "\n\nMake these bigger" to listOf(shot)), pickSends)
        assertTrue(textSends.isEmpty())
        assertEquals(1, cleared)
        assertEquals("", inputText())
        rule.onAllNodesWithTag(BrowserPickTags.Chip).assertCountEquals(0)
    }

    @Test
    fun picksWithoutShotsGoAsAPlainTextSend() {
        items = listOf(plain)
        show()
        input().performTextInput("Look")
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf(BrowserPicks.formatDescriptorBlock(listOf(plain.descriptor), "https://a.test/app") + "\n\nLook"), textSends)
        assertTrue(pickSends.isEmpty())
        assertEquals(1, cleared)
    }

    @Test
    fun picksAloneSendTheBlockWithNoText() {
        items = listOf(plain)
        show()
        input().performClick()
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf(BrowserPicks.formatDescriptorBlock(listOf(plain.descriptor), "https://a.test/app")), textSends)
        assertEquals(1, cleared)
    }

    @Test
    fun whileATurnRunsSelectedElementsWaitWithTheWebsCopy() {
        items = listOf(withShot)
        show(ComposerFixtures.busy)
        input().performTextInput("later")
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        rule.onNodeWithText("Wait for the current turn to finish before sending selected elements.").assertExists()
        assertTrue(textSends.isEmpty() && pickSends.isEmpty())
        assertEquals("later", inputText())
        assertEquals(0, cleared)
        rule.onAllNodesWithTag(BrowserPickTags.Chip).assertCountEquals(1)
    }

    @Test
    fun aSendThatDidNotGoKeepsThePicksAndTheDraft() {
        items = listOf(withShot)
        answer = AttachmentSendResult.NotConnected
        show()
        input().performTextInput("keep")
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(1, pickSends.size)
        assertEquals(0, cleared)
        assertEquals("keep", inputText())
        assertFalse(rule.onAllNodesWithTag(BrowserPickTags.Chip).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Not connected — the message and its attachments were not sent.").assertExists()
    }
}
