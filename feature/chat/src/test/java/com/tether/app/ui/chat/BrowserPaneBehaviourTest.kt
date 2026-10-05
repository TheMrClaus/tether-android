package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.tether.app.client.BrowserChannel
import com.tether.app.client.BrowserSocket
import com.tether.app.client.BrowserSocketListener
import com.tether.app.client.BrowserSocketOpen
import com.tether.app.client.BrowserSocketOpener
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A `/ws-browser` socket the test drives: what the pane sent, and the server's side. */
internal class PaneSocket : BrowserSocket {
    val sent = mutableListOf<String>()
    var closed = false
    lateinit var listener: BrowserSocketListener

    override fun send(text: String): Boolean {
        sent += text
        return true
    }

    override fun close() {
        closed = true
    }
}

internal class PaneOpener(private val opens: Boolean = true) : BrowserSocketOpener {
    val sockets = mutableListOf<PaneSocket>()

    override fun open(sessionId: String, listener: BrowserSocketListener): BrowserSocketOpen {
        val socket = PaneSocket().also { it.listener = listener }
        sockets += socket
        if (opens) listener.onOpen(socket)
        return BrowserSocketOpen.Opened(socket)
    }
}

/**
 * T8.6 behaviour of the in-console browser pane against browser-pane.tsx (90fbb9f): Go (and the
 * IME's Go) navigates to the trimmed URL with `https://` supplied (:140-144); a tap on the stage is
 * a left `mousePressed` + `mouseReleased` at the mapped page pixel (:70-90); Reload re-navigates
 * the current page; Close sends `close` and closes the pane (:174-183); the presets, Fit and the
 * custom size send `viewport` (:128-138); the readout shows the true emulated size; a refused
 * channel shows the dead state (:198-202). The composer's AppWindow key toggles the pane.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BrowserPaneBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val opener = PaneOpener()
    private var closes = 0

    private fun show(channel: BrowserChannel = BrowserChannel(opener, "s1")): BrowserChannel {
        channel.connect()
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = WellHeightPhone) {
                BrowserPane(channel, onClose = { closes++ }, modifier = Modifier.fillMaxSize())
            }
        }
        rule.waitForIdle()
        opener.sockets.lastOrNull()?.sent?.clear()
        return channel
    }

    private val socket get() = opener.sockets.last()

    private fun server(text: String) {
        rule.runOnIdle { socket.listener.onMessage(text) }
        rule.waitForIdle()
    }

    @Test
    fun goNavigatesToTheTrimmedUrlWithHttpsSupplied() {
        show()
        rule.onNodeWithTag(BrowserPaneTags.Url).performTextInput("  localhost:3000  ")
        rule.onNodeWithTag(BrowserPaneTags.Go).performClick()
        rule.onNodeWithTag(BrowserPaneTags.Url).performImeAction()
        assertEquals(
            listOf(
                """{"t":"navigate","url":"https://localhost:3000"}""",
                """{"t":"navigate","url":"https://localhost:3000"}""",
            ),
            socket.sent,
        )
        assertEquals("https://localhost:3000", browserTarget("https://localhost:3000"))
        assertEquals("HTTP://x.test", browserTarget(" HTTP://x.test "))
    }

    @Test
    fun anEmptyUrlSendsNothing() {
        show()
        rule.onNodeWithTag(BrowserPaneTags.Go).performClick()
        rule.waitForIdle()
        assertTrue(socket.sent.isEmpty())
    }

    @Test
    fun aTapIsALeftClickAtTheMappedPagePixel() {
        show()
        server("""{"t":"state","url":"https://a.test/","title":"A","viewport":{"width":390,"height":844,"mobile":true,"deviceScaleFactor":1},"pickMode":false,"loading":false}""")
        val stage = rule.onNodeWithTag(BrowserPaneTags.Stage)
        val size = stage.fetchSemanticsNode().size
        stage.performTouchInput {
            down(Offset(size.width / 2f, size.height / 4f))
            up()
        }
        rule.waitForIdle()
        assertEquals(
            listOf(
                """{"t":"input","event":{"type":"mousePressed","x":195,"y":211,"button":"left","buttons":1,"clickCount":1,"modifiers":0}}""",
                """{"t":"input","event":{"type":"mouseReleased","x":195,"y":211,"button":"left","buttons":0,"clickCount":1,"modifiers":0}}""",
            ),
            socket.sent.filter { it.contains("\"mouseP") || it.contains("\"mouseR") },
        )
    }

    @Test
    fun inPickModeATapReachesNoPage() {
        show()
        server("""{"t":"state","url":"https://a.test/","pickMode":true}""")
        rule.onNodeWithTag(BrowserPaneTags.Stage).performTouchInput {
            down(center)
            up()
        }
        rule.waitForIdle()
        assertTrue(socket.sent.none { it.contains("\"input\"") })
    }

    @Test
    fun reloadRenavigatesTheCurrentPageAndCloseClosesIt() {
        show()
        server("""{"t":"navigated","url":"https://b.test/","title":"B"}""")
        rule.onNodeWithContentDescription("Reload").performClick()
        rule.onNodeWithContentDescription("Close browser").performClick()
        rule.waitForIdle()
        assertEquals(listOf("""{"t":"navigate","url":"https://b.test/"}""", """{"t":"close"}"""), socket.sent)
        assertEquals(1, closes)
    }

    @Test
    fun presetsAndTheCustomSizeSetTheViewport() {
        show()
        rule.onNodeWithTag(BrowserPaneTags.preset("Mobile")).performClick()
        rule.onNodeWithTag(BrowserPaneTags.preset("Desktop")).performClick()
        rule.onNodeWithTag(BrowserPaneTags.CustomWidth).performTextInput("800px")
        rule.onNodeWithTag(BrowserPaneTags.CustomHeight).performTextInput("600")
        rule.onNodeWithTag(BrowserPaneTags.SetCustom).performClick()
        rule.waitForIdle()
        assertEquals(
            listOf(
                """{"t":"viewport","width":390,"height":844,"mobile":true,"deviceScaleFactor":1}""",
                """{"t":"viewport","width":1440,"height":900,"mobile":false,"deviceScaleFactor":1}""",
                // parseInt("800px") is 800.
                """{"t":"viewport","width":800,"height":600,"mobile":false,"deviceScaleFactor":1}""",
            ),
            socket.sent,
        )
        // parseInt as JS reads it: an unparsable size is NaN, and Set sends nothing.
        assertEquals(800, jsParseInt(" 800px"))
        assertEquals(-5, jsParseInt("-5"))
        assertEquals(null, jsParseInt("x800"))
    }

    @Test
    fun fitSendsTheStagesOwnSize() {
        show()
        rule.onNodeWithTag(BrowserPaneTags.Fit).performClick()
        rule.waitForIdle()
        // 412dp wide: the wrap's 8dp padding each side leaves a 396dp stage; 1024×768 → 297 tall.
        assertEquals(listOf("""{"t":"viewport","width":396,"height":297,"mobile":false,"deviceScaleFactor":1}"""), socket.sent)
    }

    @Test
    fun theReadoutAndActivePresetFollowTheServer() {
        show()
        rule.onNodeWithTag(BrowserPaneTags.Readout).assertTextEquals("1024×768")
        server("""{"t":"state","url":"https://a.test/","viewport":{"width":390,"height":844,"mobile":true,"deviceScaleFactor":1}}""")
        rule.onNodeWithTag(BrowserPaneTags.Readout).assertTextEquals("390×844 · mobile")
        rule.onNodeWithTag(BrowserPaneTags.preset("Mobile")).assertIsSelected()
        rule.onNodeWithText("Live").assertExists()
        server("""{"t":"state","url":"https://a.test/","loading":true}""")
        rule.onNodeWithText("Loading…").assertExists()
    }

    @Test
    fun aRefusedChannelShowsTheDeadState() {
        show(BrowserChannel(BrowserSocketOpener.Unavailable, "s1"))
        rule.onNodeWithText("The browser is not available.").assertExists()
        rule.onNodeWithText(BrowserSocketOpener.SIGNED_OUT).assertExists()
        rule.onNodeWithText("Connecting…").assertExists()
    }

    @Test
    fun theComposerKeyTogglesThePane() {
        var open by mutableStateOf(false)
        rule.setContent {
            ComposerHost(TetherSkin.StudioDark) {
                Box {
                    Composer(
                        session = ComposerFixtures.session,
                        projection = ComposerFixtures.idle.projection,
                        controls = null,
                        serverNow = { ComposerFixtures.BUSY_NOW },
                        onSend = { _, _ -> true },
                        onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                        onQueueEdit = { _, _ -> },
                        onQueueRemove = {},
                        onRequestControls = {},
                        liveness = ComposerLiveness.Live,
                        browser = ComposerBrowser(open) { open = !open },
                    )
                }
            }
        }
        val key = rule.onNodeWithContentDescription("Open the in-console browser")
        assertEquals(false, key.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true)
        key.performClick()
        rule.waitForIdle()
        assertTrue(open)
        key.assertIsSelected()
        key.performClick()
        rule.waitForIdle()
        assertEquals(false, open)
    }
}
