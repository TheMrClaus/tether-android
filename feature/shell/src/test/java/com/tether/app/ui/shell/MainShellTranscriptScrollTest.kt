package com.tether.app.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.protocol.tree.JsObj
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-jyj0: a phone turned sideways swaps PhoneShell for ExpandedShell (and back), each composing its own
 * ChatScreen. The browser keeps the reader's place across a resize, and a reader following the bottom stays
 * there; a chat opened after another never inherits that other chat's place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1000dp-mdpi")
class MainShellTranscriptScrollTest {
    @get:Rule val rule = createComposeRule()

    private var width by mutableIntStateOf(PORTRAIT)

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(width, 1000)
    }

    private fun session(id: String) =
        AgentSession(id = id, provider = "claude", name = id, cwd = "/w", status = "active", startedAt = 1, updatedAt = 1, historyId = "h-$id")

    private fun conversation(prefix: String, turns: Int): JsObj {
        val events = (1..turns).flatMap { n ->
            val turn = "$prefix$n"
            listOf(
                ev("turn_started", turn, ts = n * 1_000L) { put("idempotencyKey", "k-$turn") },
                ev("user_message_accepted", turn, ts = n * 1_000L) { put("text", "$prefix prompt $n") },
                ev("message_started", turn, ts = n * 1_000L) { put("blockId", "$turn:m0") },
                ev("message_delta", turn, ts = n * 1_000L) { put("blockId", "$turn:m0"); put("text", "$prefix reply $n") },
                ev("message_completed", turn, ts = n * 1_000L) { put("blockId", "$turn:m0"); put("text", "$prefix reply $n") },
                ev("turn_end", turn, ts = n * 1_000L) { put("outcome", "ok") },
            )
        }
        return foldTree(freshTree(), *events.toTypedArray())
    }

    private lateinit var vm: TetherViewModel

    private fun host() {
        val client = ShellConsentClient()
        client.show(session("a"), conversation("a", 60))
        client.show(session("b"), conversation("b", 60))
        vm = TetherViewModel(client)
        vm.selectSession("a")
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent { TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } } }
        settle()
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
    }

    private fun shown(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** The lowest-numbered reply of [prefix] on screen: the reader's place. */
    private fun firstShownReply(prefix: String): Int? = (1..60).firstOrNull { shown("$prefix reply $it") }

    private fun resize(to: Int) {
        width = to
        settle()
    }

    @Test fun aReaderScrolledUpKeepsTheirPlaceAcrossTheShellSwitchBothWays() {
        host()
        assertTrue("opens at the latest message", shown("a reply 60"))
        repeat(3) { rule.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() } }
        settle()
        assertFalse(shown("a reply 60"))
        val place = checkNotNull(firstShownReply("a"))
        rule.onNodeWithContentDescription("Jump to latest").assertExists()

        resize(LANDSCAPE)
        assertEquals("landscape (ExpandedShell) keeps the place", place, firstShownReply("a"))
        assertFalse(shown("a reply 60"))
        rule.onNodeWithContentDescription("Jump to latest").assertExists()

        resize(PORTRAIT)
        assertEquals("portrait (PhoneShell) keeps it too", place, firstShownReply("a"))
        assertFalse(shown("a reply 60"))
    }

    @Test fun aReaderAtTheBottomStaysAtTheBottomAcrossTheShellSwitch() {
        host()
        assertTrue(shown("a reply 60"))
        resize(LANDSCAPE)
        assertTrue("still at the latest message", shown("a reply 60"))
        rule.onNodeWithContentDescription("Jump to latest").assertDoesNotExist()
        resize(PORTRAIT)
        assertTrue(shown("a reply 60"))
        rule.onNodeWithContentDescription("Jump to latest").assertDoesNotExist()
    }

    @Test fun anotherSessionNeverInheritsThePlaceOfTheOneBeforeIt() {
        host()
        repeat(3) { rule.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() } }
        settle()
        assertFalse(shown("a reply 60"))
        resize(LANDSCAPE)
        assertFalse(shown("a reply 60"))

        rule.runOnIdle { vm.selectSession("b") }
        settle()
        assertTrue("B opens at its latest message", shown("b reply 60"))
        rule.onNodeWithContentDescription("Jump to latest").assertDoesNotExist()

        // And a shell switch on B still keeps B at the bottom (nothing of A's place was carried).
        resize(PORTRAIT)
        assertTrue(shown("b reply 60"))
    }

    private companion object {
        const val PORTRAIT = 412
        const val LANDSCAPE = 1200
    }
}
