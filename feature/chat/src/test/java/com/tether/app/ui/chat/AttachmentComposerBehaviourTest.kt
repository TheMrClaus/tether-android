package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.tether.app.client.AttachmentSendResult
import com.tether.app.client.StagedAttachment
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.DelegateMention
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T7.4: the composer's attachments (chat-view.tsx submit :3104-3188, the attach sheet). The paperclip
 * opens the web's touch sheet; picks are staged, never sent; ONLY an explicit Send (the key, Enter)
 * transmits them, through the one guarded path, and a refusal keeps the draft and the chips and says
 * why (offline: "Not connected — the message and its attachments were not sent."). While a turn runs
 * attachments wait (the web's copy), and command mode refuses them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class AttachmentComposerBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val textSends = mutableListOf<String>()
    private val attachmentSends = mutableListOf<Pair<String, DelegateMention?>>()
    private val removed = mutableListOf<Long>()
    private var staged by mutableStateOf(listOf<StagedAttachment>())
    private var answer = AttachmentSendResult.Sent

    private val pic = StagedAttachment(1, Attachment("pic.png", "image/png", ComposerAttachmentFixtures.PNG_BASE64), 67)
    private val notes = StagedAttachment(2, Attachment("notes.txt", "text/plain", "aGVsbG8="), 5)

    private fun show(fixture: ChatFixtures.Folded = ComposerFixtures.idle, commandMode: Boolean = false) {
        rule.setContent {
            ComposerHost(TetherSkin.Machine) {
                Composer(
                    session = ComposerFixtures.session,
                    projection = fixture.projection,
                    controls = null,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { text, atts ->
                        assertTrue("the text path got attachments", atts.isEmpty())
                        textSends += text
                        true
                    },
                    onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    liveness = ComposerLiveness.Live,
                    tree = fixture.tree,
                    runActions = if (commandMode) CommandFixtures.Recorder().actions(commandMode = true) else ComposerCommandActions.Unavailable,
                    attachments = ComposerAttachments(
                        staged = staged,
                        stage = { emptyList() },
                        onRemove = { id -> removed += id; staged = staged.filterNot { it.id == id } },
                        send = { text, mention ->
                            attachmentSends += text to mention
                            answer.also { if (it == AttachmentSendResult.Sent) staged = emptyList() }
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
    fun thePaperclipOpensTheWebsTouchSheet() {
        show()
        rule.onNodeWithContentDescription("Add attachment").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(ATTACH_SHEET_TITLE).assertExists()
        rule.onNodeWithContentDescription("Add image").assertIsEnabled()
        rule.onNodeWithContentDescription("Paste image").assertIsEnabled()
        rule.onNodeWithContentDescription("Upload file").assertIsEnabled()
        // Logged divergence: no GitHub work client yet (T8.4), so no "Add issue or PR" row.
        rule.onAllNodesWithContentDescription("Add issue or PR").assertCountEquals(0)
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText(ATTACH_SHEET_TITLE).assertCountEquals(0)
    }

    @Test
    fun pasteImageWithNoPictureOnTheClipboardSaysSoAndStagesNothing() {
        show()
        val clipboard = rule.activity.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("t", "just words"))
        rule.onNodeWithContentDescription("Add attachment").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Paste image").performClick()
        rule.waitForIdle()
        // r2 (L3): the clip's items are looked at off the main thread.
        rule.waitUntil(5_000) { rule.onAllNodesWithText(AttachmentCopy.NO_CLIPBOARD_IMAGE).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(AttachmentCopy.NO_CLIPBOARD_IMAGE).assertExists()
        // The sheet closed first (attach-sheet.tsx run()); nothing was staged or sent.
        rule.onAllNodesWithText(ATTACH_SHEET_TITLE).assertCountEquals(0)
        assertTrue(attachmentSends.isEmpty() && textSends.isEmpty())
    }

    @Test
    fun stagedAttachmentsAreNeverSentWithoutASendAndOneTapSendsThemOnce() {
        staged = listOf(pic, notes)
        show()
        rule.onAllNodesWithTag("staged-attachment").assertCountEquals(2)
        input().performTextInput("have a look")
        rule.mainClock.advanceTimeBy(5_000)
        rule.waitForIdle()
        assertTrue("sent without a tap", attachmentSends.isEmpty())
        rule.onNodeWithContentDescription("Send message").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("have a look" to null), attachmentSends)
        assertTrue(textSends.isEmpty())
        assertEquals("", inputText())
        rule.onAllNodesWithTag("staged-attachment").assertCountEquals(0)
    }

    @Test
    fun attachmentsAloneAreSendable() {
        staged = listOf(notes)
        show()
        rule.onNodeWithContentDescription("Send message").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("" to null), attachmentSends)
    }

    @Test
    fun offlineTheSendIsRefusedAndTheDraftAndChipsStay() {
        staged = listOf(pic)
        answer = AttachmentSendResult.NotConnected
        show()
        input().performTextInput("while offline")
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Not connected — the message and its attachments were not sent.").assertExists()
        assertEquals("while offline", inputText())
        rule.onAllNodesWithTag("staged-attachment").assertCountEquals(1)
        assertEquals(1, attachmentSends.size)
        // Nothing retries: time passes, the same single attempt.
        rule.mainClock.advanceTimeBy(30_000)
        rule.waitForIdle()
        assertEquals(1, attachmentSends.size)
    }

    @Test
    fun everyRefusalSaysWhyInWords() {
        for (result in AttachmentSendResult.entries) {
            val copy = attachmentRefusalCopy(result)
            if (result == AttachmentSendResult.Sent || result == AttachmentSendResult.Empty) assertEquals(null, copy) else assertTrue("$result", !copy.isNullOrBlank())
        }
    }

    @Test
    fun whileATurnRunsAttachmentsWaitWithTheWebsCopy() {
        staged = listOf(pic)
        show(ComposerFixtures.busy)
        input().performTextInput("later")
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        rule.onNodeWithText("Wait for the current turn to finish before sending attachments.").assertExists()
        assertTrue(attachmentSends.isEmpty() && textSends.isEmpty())
        assertEquals("later", inputText())
    }

    @Test
    fun commandModeRefusesAttachments() {
        staged = listOf(pic)
        show(commandMode = true)
        input().performTextInput("!ls")
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        rule.onNodeWithText("Command mode can’t include attachments — remove them to run a command.").assertExists()
        assertTrue(attachmentSends.isEmpty())
    }

    @Test
    fun theRemoveKeyUnstagesOne() {
        staged = listOf(pic, notes)
        show()
        rule.onNodeWithContentDescription("Remove notes.txt").performClick()
        rule.waitForIdle()
        assertEquals(listOf(2L), removed)
        rule.onAllNodesWithTag("staged-attachment").assertCountEquals(1)
    }

    @Test
    fun aHostileNameIsDrawnByTheCodeRuleOnOneLine() {
        // The wire name is already cleaned at staging; the chip still draws whatever it is given by
        // the code rule, so a control that got this far shows as a token instead of acting.
        staged = listOf(StagedAttachment(9, Attachment("invoice\u202Efdp.exe", "application/octet-stream", "eA=="), 1))
        show()
        rule.onNodeWithText("U+202E", substring = true, useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("invoice\u202Efdp.exe", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aNameWithALineFeedCarriageReturnOrTabIsOneLineInTheChipAndItsRemoveLabel() {
        staged = listOf(StagedAttachment(10, Attachment("notes\nfinal\r\tv2.txt", "text/plain", "eA=="), 1))
        show()
        for (token in listOf("U+000A", "U+000D", "U+0009")) {
            rule.onNodeWithText(token, substring = true, useUnmergedTree = true).assertExists()
        }
        // The remove key's spoken label names the file by the same rule: tokens, no raw break.
        val label = rule.onNodeWithContentDescription("Remove ", substring = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString("")
        assertTrue("label: $label", label.contains("U+000A") && label.contains("U+0009"))
        assertTrue("a raw break in the label: $label", label.none { it == '\n' || it == '\r' || it == '\t' })
    }
}

object ComposerAttachmentFixtures {
    /** A 48×32 checker PNG, base64 (the staged-picture chip decodes its thumbnail from it). */
    val PNG_BASE64: String by lazy {
        val bitmap = ToolFixtures.checker().asAndroidBitmap()
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }
}
