package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.FailedSend
import com.tether.app.client.FailedSendReason
import com.tether.app.client.PendingInput
import com.tether.app.client.PendingSendRow
import com.tether.app.client.SendStatus
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ta-coik.19: the send rows of the web's issue #135 states (90fbb9f). */
object SendFixtures {
    const val SESSION_ID = ComposerFixtures.SESSION_ID
    const val THREE_MB = 3.0 * 1024 * 1024
    const val KB_216 = 216.0 * 1024

    fun pending(
        key: String,
        text: String,
        status: SendStatus = SendStatus.Sending,
        attachments: Int = 0,
        images: Int = 0,
        bytes: Double = 0.0,
        sessionId: String = SESSION_ID,
    ) = PendingSendRow(key, sessionId, PendingInput.KIND_SEND, text, status, attachments, images, bytes)

    fun failed(
        key: String,
        text: String,
        attachments: Int = 0,
        images: Int = 0,
        bytes: Double = 0.0,
        reason: FailedSendReason = FailedSendReason.Expired,
        sessionId: String = SESSION_ID,
    ) = FailedSend(key, sessionId, PendingInput.KIND_SEND, text, attachments, images, bytes, reason)
}

/**
 * ta-coik.19 (web issue #135, chat-view.tsx 90fbb9f :1543-1606, :1983-1992, :3730-3768): what the
 * chat says of a send, in the web's words: `Sending…` / `Sending — <summary>` while on the wire,
 * `Waiting for link — …` while the link is down, `Not delivered (<summary>)` once given up, with
 * Dismiss as its one action; the composer counts what is still going out. Only this session's rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", shadows = [NoMagnifier::class])
class SendBubblesBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val session: AgentSession = ComposerFixtures.session

    private fun host(client: ChatTestClient) {
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalChatDerivationDispatcher provides kotlinx.coroutines.Dispatchers.Unconfined) { TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = session, projection = projections[session.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            } }
        }
        settle()
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private fun client(folded: ChatFixtures.Folded = ChatFixtures.idle) = ChatTestClient().also { it.show(session, folded) }

    // --- the words ----------------------------------------------------------------------------

    @Test
    fun theAttachmentSummaryIsTheWebs() {
        assertEquals("", attachmentSummary(0, 0, 0.0))
        assertEquals("1 image · 8 B", attachmentSummary(1, 1, 8.0))
        assertEquals("2 images · 3.0 MB", attachmentSummary(2, 2, SendFixtures.THREE_MB))
        assertEquals("1 file · 216 KB", attachmentSummary(1, 0, SendFixtures.KB_216))
        // Any non-image makes them all files.
        assertEquals("3 files · 3.0 MB", attachmentSummary(3, 2, SendFixtures.THREE_MB))
    }

    @Test
    fun eachStateSaysItsOwnLine() {
        assertEquals("Sending…", pendingSendLabel(SendFixtures.pending("a", "x")))
        assertEquals("Sending — 2 images · 3.0 MB", pendingSendLabel(SendFixtures.pending("a", "x", attachments = 2, images = 2, bytes = SendFixtures.THREE_MB)))
        // Waiting says the same whatever it carries.
        assertEquals(
            "Waiting for link — reconnecting, your message will be sent automatically",
            pendingSendLabel(SendFixtures.pending("a", "x", SendStatus.Waiting, attachments = 1, images = 1, bytes = 8.0)),
        )
        assertEquals("Not delivered", failedSendLabel(SendFixtures.failed("a", "x")))
        assertEquals("Not delivered (2 images · 3.0 MB)", failedSendLabel(SendFixtures.failed("a", "x", 2, 2, SendFixtures.THREE_MB)))
        assertEquals(
            "Photo could not be recovered — send again.",
            failedSendLabel(SendFixtures.failed("a", "x", reason = FailedSendReason.PhotoLost)),
        )
        assertEquals("Not sent — the link was down", failedSendLabel(SendFixtures.failed("a", "x", reason = FailedSendReason.Link)))
        assertEquals(
            "Not sent — the link was down (1 file · 216 KB)",
            failedSendLabel(SendFixtures.failed("a", "x", 1, 0, SendFixtures.KB_216, FailedSendReason.Link)),
        )
    }

    @Test
    fun theComposerRowCountsAndWaitingWins() {
        assertNull(sendRowLabel(emptyList()))
        assertEquals("Sending 1 message…", sendRowLabel(listOf(SendFixtures.pending("a", "x"))))
        assertEquals("Sending 2 messages…", sendRowLabel(listOf(SendFixtures.pending("a", "x"), SendFixtures.pending("b", "y"))))
        assertEquals(
            "Waiting for link — 1 message will send automatically when the connection returns.",
            sendRowLabel(listOf(SendFixtures.pending("a", "x"), SendFixtures.pending("b", "y", SendStatus.Waiting))),
        )
        assertEquals(
            "Waiting for link — 2 messages will send automatically when the connection returns.",
            sendRowLabel(listOf(SendFixtures.pending("a", "x", SendStatus.Waiting), SendFixtures.pending("b", "y", SendStatus.Waiting))),
        )
    }

    // --- in the chat --------------------------------------------------------------------------

    @Test
    fun aSendingRowIsAGhostBubbleAndTheComposerSaysSo() {
        val c = client()
        c.pendingRows.value = listOf(SendFixtures.pending("k1", "Ship it."))
        host(c)
        rule.onNodeWithTag(PENDING_SEND_TAG).assertIsDisplayed()
        rule.onNodeWithText("Ship it.").assertIsDisplayed()
        rule.onNodeWithText("Sending…").assertIsDisplayed()
        rule.onNodeWithTag(SEND_ROW_TAG).assertIsDisplayed()
        rule.onNodeWithText("Sending 1 message…").assertIsDisplayed()
    }

    @Test
    fun aWaitingRowSaysTheLinkIsDownInWords() {
        val c = client()
        c.pendingRows.value = listOf(SendFixtures.pending("k1", "Ship it.", SendStatus.Waiting))
        host(c)
        rule.onNodeWithText("Waiting for link — reconnecting, your message will be sent automatically").assertIsDisplayed()
        rule.onNodeWithText("Waiting for link — 1 message will send automatically when the connection returns.").assertIsDisplayed()
    }

    @Test
    fun theEchoTakesTheGhostBubbleAway() {
        val c = client()
        c.pendingRows.value = listOf(SendFixtures.pending("k1", "Ship it."))
        host(c)
        rule.onNodeWithTag(PENDING_SEND_TAG).assertIsDisplayed()
        // The client clears the record on the turn's ack; the real turn bubble is the projection's.
        c.pendingRows.value = emptyList()
        settle()
        rule.onAllNodesWithTag(PENDING_SEND_TAG).assertCountEquals(0)
        rule.onAllNodesWithTag(SEND_ROW_TAG).assertCountEquals(0)
    }

    @Test
    fun aFailedSendKeepsTheWordsAndItsSummaryUntilDismissed() {
        val c = client()
        c.failedRows.value = listOf(SendFixtures.failed("k9", "Please keep these words.", 2, 2, SendFixtures.THREE_MB))
        host(c)
        rule.onNodeWithTag(FAILED_SEND_TAG).assertIsDisplayed()
        rule.onNodeWithText("Please keep these words.").assertIsDisplayed()
        rule.onNodeWithText("Not delivered (2 images · 3.0 MB)").assertIsDisplayed()
        // A failed send is not in flight: the composer row does not count it.
        rule.onAllNodesWithTag(SEND_ROW_TAG).assertCountEquals(0)
        rule.onNodeWithContentDescription("Dismiss failed message").performClick()
        settle()
        assertEquals(listOf("k9"), c.dismissedSends.toList())
        rule.onAllNodesWithTag(FAILED_SEND_TAG).assertCountEquals(0)
        // Dismiss is local: nothing went out.
        assertTrue(c.outbox.isEmpty())
    }

    @Test
    fun onlyThisSessionsSendsShow() {
        val c = client()
        c.pendingRows.value = listOf(SendFixtures.pending("k1", "elsewhere", sessionId = "other"))
        c.failedRows.value = listOf(SendFixtures.failed("k2", "also elsewhere", sessionId = "other"))
        host(c)
        rule.onAllNodesWithTag(PENDING_SEND_TAG).assertCountEquals(0)
        rule.onAllNodesWithTag(FAILED_SEND_TAG).assertCountEquals(0)
        rule.onAllNodesWithTag(SEND_ROW_TAG).assertCountEquals(0)
    }

    @Test
    fun theFirstMessageOfAnEmptyConversationShowsToo() {
        val c = client(ChatFixtures.fold())
        c.pendingRows.value = listOf(SendFixtures.pending("k1", "Hello there."))
        c.failedRows.value = listOf(SendFixtures.failed("k0", "An earlier try."))
        host(c)
        rule.onNodeWithText("Send a message to start the conversation.").assertIsDisplayed()
        rule.onNodeWithText("Hello there.").assertIsDisplayed()
        rule.onNodeWithText("Not delivered").assertIsDisplayed()
        rule.onAllNodesWithText("Sending…").assertCountEquals(1)
    }

    /** The text toolbar, spied (as ActivitySelectionTest): the test presses "Select all" and "Copy" as the operator would. */
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    private class MenuSpy : androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider {
        @Volatile var shown: androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider? = null

        override suspend fun showTextContextMenu(dataProvider: androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider) {
            shown = dataProvider
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                if (shown === dataProvider) shown = null
            }
        }

        fun press(key: Any) {
            val menu = checkNotNull(shown) { "no text toolbar is open" }
            val item = menu.data().components.filterIsInstance<androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem>().first { it.key == key }
            item.onClick(object : androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession { override fun close() = Unit })
        }
    }

    private fun clip(): String? =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    private fun copiesTheFailedWords(reason: FailedSendReason) {
        val words = "Long words to keep: " + "alpha beta gamma delta ".repeat(12).trim()
        val menu = MenuSpy()
        val c = client()
        c.failedRows.value = listOf(SendFixtures.failed("k7", words, reason = reason))
        val vm = TetherViewModel(c)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider provides menu,
                LocalChatDerivationDispatcher provides kotlinx.coroutines.Dispatchers.Unconfined,
            ) {
                TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                    val projections by c.projections.collectAsStateWithLifecycle()
                    ChatScreen(vm = vm, session = session, projection = projections[session.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
                }
            }
        }
        settle()
        rule.onNodeWithText(words, substring = true).performTouchInput { longClick(center) }
        rule.waitUntil(20_000) { menu.shown != null }
        menu.press(androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys.SelectAllKey)
        rule.waitForIdle()
        menu.press(androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys.CopyKey)
        rule.waitForIdle()
        val copied = checkNotNull(clip()) { "nothing was copied" }
        assertEquals("the failed bubble's exact words", words, copied)
        assertFalse("the status line stays out of the selection: $copied", copied.contains("Photo could not") || copied.contains("Not delivered"))
    }

    @Test fun aPhotoLostBubblesWordsAreSelectableAndCopyExactly() = copiesTheFailedWords(FailedSendReason.PhotoLost)

    @Test fun anExpiredBubblesWordsAreSelectableAndCopyExactly() = copiesTheFailedWords(FailedSendReason.Expired)

    @Test fun aLinkBubblesWordsAreSelectableAndCopyExactly() = copiesTheFailedWords(FailedSendReason.Link)
}
