package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasProgressBarRangeInfo
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
        percent: Int? = null,
        keptOnDevice: Boolean = true,
    ) = PendingSendRow(key, sessionId, PendingInput.KIND_SEND, text, status, attachments, images, bytes, percent, keptOnDevice)

    fun failed(
        key: String,
        text: String,
        attachments: Int = 0,
        images: Int = 0,
        bytes: Double = 0.0,
        reason: FailedSendReason = FailedSendReason.Expired,
        sessionId: String = SESSION_ID,
        canSendAgain: Boolean = false,
    ) = FailedSend(key, sessionId, PendingInput.KIND_SEND, text, attachments, images, bytes, reason, canSendAgain = canSendAgain)
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
        // AW.4: one attachment lost, then several; the web's C2 words.
        assertEquals(
            "Not sent — the upload failed. An attachment is no longer on this device; attach it again and resend. · 1 image · 10 B",
            failedSendLabel(SendFixtures.failed("a", "x", 1, 1, 10.0, FailedSendReason.PhotoLost)),
        )
        assertEquals(
            "Not sent — the upload failed. 2 attachments are no longer on this device; attach them again and resend. · 2 images · 3.0 MB",
            failedSendLabel(SendFixtures.failed("a", "x", 2, 2, SendFixtures.THREE_MB, FailedSendReason.PhotoLost)),
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
            "Waiting for link — kept on this device, sends when the connection returns",
            sendRowLabel(listOf(SendFixtures.pending("a", "x"), SendFixtures.pending("b", "y", SendStatus.Waiting))),
        )
        assertEquals(
            "Waiting for link — kept on this device, sends when the connection returns",
            sendRowLabel(listOf(SendFixtures.pending("a", "x", SendStatus.Waiting), SendFixtures.pending("b", "y", SendStatus.Waiting))),
        )
    }

    // --- ta-delivery V1-V3 in the strip row and the bubble -------------------------------------

    @Test
    fun theUploadingStripRowSaysV1WithAPrintedPercentAndAProgressRole() {
        val c = client()
        c.pendingRows.value = listOf(SendFixtures.pending("k1", "Ship it.", attachments = 2, images = 2, bytes = SendFixtures.THREE_MB, percent = 42))
        host(c)
        val words = "2 images · 3.0 MB · Uploading — kept on this device until it reaches the server"
        // The same words in the strip row and in the transcript bubble.
        rule.onAllNodesWithText(words).assertCountEquals(2)
        // §4.1d B item 4: the printed % is not exposed to accessibility (the bar's range info is the one place).
        rule.onAllNodesWithText("42%").assertCountEquals(0)
        rule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.42f, 0f..1f))).assertCountEquals(2)
    }

    @Test
    fun offlineWithAnUploadPercentTheLinkStateWinsAndThePercentStaysOut() {
        val c = client()
        c.pendingRows.value = listOf(SendFixtures.pending("k1", "Ship it.", SendStatus.Waiting, 1, 1, 8.0, percent = 0))
        host(c)
        rule.onNodeWithText("Waiting for link — kept on this device, sends when the connection returns").assertIsDisplayed()
        rule.onNodeWithText("Waiting for link — reconnecting, your message will be sent automatically").assertIsDisplayed()
        rule.onAllNodesWithText("0%").assertCountEquals(0)
        rule.onAllNodesWithTag(UPLOAD_PROGRESS_TAG).assertCountEquals(0)
    }

    @Test
    fun theStripsLiveRegionHoldsOnlyTheGlyphAndWordsNeverTheProgress() {
        val c = client()
        c.pendingRows.value = listOf(SendFixtures.pending("k1", "Ship it.", attachments = 2, images = 2, bytes = SendFixtures.THREE_MB, percent = 42))
        host(c)
        val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
        // The words are the live region; its subtree carries no range info and no percent.
        val regions = rule.onAllNodes(live).fetchSemanticsNodes()
        assertTrue(regions.isNotEmpty())
        for (node in regions) {
            val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
            assertFalse("no % inside a live region: $text", text.contains('%'))
            assertFalse(node.config.contains(SemanticsProperties.ProgressBarRangeInfo))
            assertTrue(node.children.none { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
        }
        // The progress node is the one that exposes the value, and it is not inside a live region.
        rule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.42f, 0f..1f))).assertCountEquals(2)
        val stripWords = rule.onAllNodes(live).fetchSemanticsNodes().filter { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.contains("Uploading") }
        }
        assertEquals(1, stripWords.size)
    }

    @Test
    fun thePendingBubbleIsNotALiveRegionAndTheFailedBubbleIs() {
        val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
        val c = client()
        c.pendingRows.value = listOf(SendFixtures.pending("k1", "Ship it.", SendStatus.Waiting, keptOnDevice = false))
        host(c)
        rule.onNode(hasAnyAncestor(hasTestTag(PENDING_SEND_TAG)).and(live), useUnmergedTree = true).assertDoesNotExist()
        rule.onNode(hasText("Waiting for link — reconnecting; may be lost if the app closes first"), useUnmergedTree = true).assertExists()
        rule.onNode(hasTestTag(SEND_ROW_TAG).and(live), useUnmergedTree = true).assertExists()
    }

    @Test
    fun aFailedBubblesStatusLineIsALiveRegion() {
        val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
        val c = client()
        c.failedRows.value = listOf(SendFixtures.failed("k9", "Ship it."))
        host(c)
        rule.onNode(hasAnyAncestor(hasTestTag(FAILED_SEND_TAG)).and(live), useUnmergedTree = true).assertExists()
    }

    @Test
    fun anUploadingRowPlusASendingRowShowsOneV1RowThenCountsOnlyTheNonUploadingRows() {
        val c = client()
        c.pendingRows.value = listOf(
            SendFixtures.pending("k1", "Ship it.", attachments = 1, images = 1, bytes = 8.0, percent = 42),
            SendFixtures.pending("k2", "Next."),
        )
        host(c)
        // One V1 row with its bar in the strip (the bubble repeats the words), then the V2 count of the one other row.
        rule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.42f, 0f..1f)), useUnmergedTree = true).assertCountEquals(2)
        rule.onNodeWithText("Sending 1 message…").assertIsDisplayed()
        rule.onAllNodesWithText("Sending 2 messages…").assertCountEquals(0)
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
        rule.onNodeWithText("Waiting for link — kept on this device, sends when the connection returns").assertIsDisplayed()
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

    // ---- A9: Send again (spec 5.4) -----------------------------------------------------------------------------

    private fun sendAgainTapOnce(reason: FailedSendReason) {
        val c = client()
        c.failedRows.value = listOf(SendFixtures.failed("k-orig", "Say this again.", reason = reason, canSendAgain = true))
        host(c)
        rule.onNodeWithTag(SEND_AGAIN_TAG).assertIsDisplayed().assertHasClickAction().performClick()
        settle()
        assertEquals(listOf("k-orig"), c.sentAgain.toList())
        rule.onAllNodesWithTag(FAILED_SEND_TAG).assertCountEquals(0)
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Send again")).assertCountEquals(0)
    }

    @Test fun aLinkTextOnlyBubbleOffersSendAgainAndATapSendsOnceUnderTheSameKey() = sendAgainTapOnce(FailedSendReason.Link)

    @Test fun anExpiredTextOnlyBubbleOffersSendAgainAndATapSendsOnceUnderTheSameKey() = sendAgainTapOnce(FailedSendReason.Expired)

    @Test
    fun theSendAgainKeyIs44dpHasItsWordsAndLeavesDismissAtTheEnd() {
        val c = client()
        c.failedRows.value = listOf(SendFixtures.failed("k1", "Words.", reason = FailedSendReason.Link, canSendAgain = true))
        host(c)
        val density = rule.density
        val key = rule.onNodeWithTag(SEND_AGAIN_TAG).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        with(density) {
            assertTrue("44dp tall (${key.height.toDp()})", key.height.toDp() >= androidx.compose.ui.unit.Dp(43.9f))
            assertTrue("44dp wide (${key.width.toDp()})", key.width.toDp() >= androidx.compose.ui.unit.Dp(43.9f))
        }
        rule.onNodeWithText("Send again").assertIsDisplayed()
        val dismiss = rule.onNodeWithContentDescription("Dismiss failed message").fetchSemanticsNode().boundsInRoot
        assertTrue("Dismiss sits above Send again", dismiss.bottom <= key.top + 1f)
        rule.onNodeWithContentDescription("Dismiss failed message").performClick()
        settle()
        assertEquals(listOf("k1"), c.dismissedSends.toList())
        assertTrue("Dismiss sends nothing", c.sentAgain.isEmpty() && c.outbox.isEmpty())
    }

    @Test
    fun aBubbleTheClientCannotResendShowsNoSendAgain() {
        val c = client()
        c.failedRows.value = listOf(
            SendFixtures.failed("lost", "Photo words.", 1, 1, 10.0, FailedSendReason.PhotoLost),
            SendFixtures.failed("up", "Up words.", 1, 1, 10.0, FailedSendReason.Upload).copy(detail = "The server could not take the upload right now.", retryable = true),
            SendFixtures.failed("exp", "Expired with files.", 2, 2, 20.0),
        )
        host(c)
        rule.onAllNodesWithTag(FAILED_SEND_TAG).assertCountEquals(3)
        rule.onAllNodesWithTag(SEND_AGAIN_TAG).assertCountEquals(0)
    }

    @Test
    fun theFailedUploadWordsAreThePrintedFormat() {
        fun label(detail: String?, attachments: Int = 2, images: Int = 2, bytes: Double = 3.1 * 1024 * 1024) =
            failedSendLabel(SendFixtures.failed("k", "x", attachments, images, bytes, FailedSendReason.Upload).copy(detail = detail))
        assertEquals("Not sent — the upload failed. The link was down. · 2 images · 3.1 MB", label("The link was down."))
        assertEquals("Not sent — the upload failed. · 2 images · 3.1 MB", label(null))
        assertEquals("Not sent — the upload failed. The link was down.", label("The link was down.", 0, 0, 0.0))
        assertEquals("Not sent — the upload failed.", label(null, 0, 0, 0.0))
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
        assertFalse("the status line stays out of the selection: $copied", copied.contains("no longer on this device") || copied.contains("Not delivered"))
    }

    @Test fun aPhotoLostBubblesWordsAreSelectableAndCopyExactly() = copiesTheFailedWords(FailedSendReason.PhotoLost)

    @Test fun anExpiredBubblesWordsAreSelectableAndCopyExactly() = copiesTheFailedWords(FailedSendReason.Expired)

    @Test fun aLinkBubblesWordsAreSelectableAndCopyExactly() = copiesTheFailedWords(FailedSendReason.Link)

    /** AW.4: "attachment", never "photo": no reason or bubble string a failed row can print contains the word. */
    @Test
    fun noFailedBubbleReasonOrLabelSaysPhoto() {
        val labels = FailedSendReason.entries.flatMap { reason ->
            listOf(0, 1, 2).map { n -> failedSendLabel(SendFixtures.failed("a", "x", n, n, 10.0 * n, reason)) }
        }
        labels.forEach { assertFalse("no photo word in: $it", it.contains("photo", ignoreCase = true)) }
    }

    /** AW.5 R4-f: the released forms, m = 1 and m = 2; a row that was never resendable keeps today's label. */
    @Test
    fun aReleasedRowSaysWhyAndOnlyAReleasedRowDoes() {
        fun released(reason: FailedSendReason, n: Int) =
            failedSendLabel(SendFixtures.failed("a", "x", n, n, 6.0 * 1024 * 1024, reason).copy(released = true))
        assertEquals(
            "Not sent — the link was down (1 image · 6.0 MB). An attachment is no longer on this device; attach it again and resend.",
            released(FailedSendReason.Link, 1),
        )
        assertEquals(
            "Not sent — the link was down (2 images · 6.0 MB). 2 attachments are no longer on this device; attach them again and resend.",
            released(FailedSendReason.Link, 2),
        )
        assertEquals(
            "Not delivered (1 image · 6.0 MB). An attachment is no longer on this device; attach it again and resend.",
            released(FailedSendReason.Expired, 1),
        )
        assertEquals(
            "Not delivered (2 images · 6.0 MB). 2 attachments are no longer on this device; attach them again and resend.",
            released(FailedSendReason.Expired, 2),
        )
        assertEquals(
            "Not sent — the upload failed. An attachment is no longer on this device; attach it again and resend. · 1 image · 6.0 MB",
            released(FailedSendReason.Upload, 1),
        )
        assertEquals(
            "Not sent — the upload failed. 2 attachments are no longer on this device; attach them again and resend. · 2 images · 6.0 MB",
            released(FailedSendReason.Upload, 2),
        )
        // Never resendable (canSendAgain false, NOT released): today's labels, no sentence.
        val refusal = SendFixtures.failed("r", "x", 2, 2, 6.0 * 1024 * 1024, FailedSendReason.Upload).copy(detail = "An attachment is limited to 9 MiB.")
        assertEquals("Not sent — the upload failed. An attachment is limited to 9 MiB. · 2 images · 6.0 MB", failedSendLabel(refusal))
        assertEquals("Not sent — the link was down (2 images · 6.0 MB)", failedSendLabel(SendFixtures.failed("m", "x", 2, 2, 6.0 * 1024 * 1024, FailedSendReason.Link)))
    }
}
