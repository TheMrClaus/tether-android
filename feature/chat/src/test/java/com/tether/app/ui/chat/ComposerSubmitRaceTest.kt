package com.tether.app.ui.chat

import android.net.Uri
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.core.app.ActivityOptionsCompat
import com.tether.app.protocol.Attachment
import com.tether.app.ui.theme.TetherSkin
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T7.1 verifier finding: a keystroke and a submit can land in ONE UI-thread step, before the
 * recomposition that refreshes the composition-time draft. The composer must send what is in the
 * field at that moment (here "hello wor" was typed and settled, then "ld" and the submit arrive
 * together through the real InputConnection), for the IME Send action, a hardware Enter, the Send
 * key and, while a turn runs, the queue path. And the web's busy-with-attachments refusal
 * (chat-view.tsx:3171-3175): the files and the text stay, nothing is sent, the operator is told
 * to wait.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ComposerSubmitRaceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    private val sends = mutableListOf<Pair<String, List<Attachment>>>()
    private var picks: List<Uri> = emptyList()

    /** T7.4: the real staged set and stager behind the composer; the guarded send is recorded. */
    private val store = com.tether.app.client.StagedAttachments()
    private val stager = AttachmentStager(store, { ORIGIN })
    private val attachmentSends = mutableListOf<Pair<String, List<Attachment>>>()
    private var staged by androidx.compose.runtime.mutableStateOf(emptyList<com.tether.app.client.StagedAttachment>())

    /** The document picker answers at once with [picks] (Robolectric has no picker activity). */
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, picks)
            }
        }
    }

    private fun show(fixture: ChatFixtures.Folded) {
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                ComposerHost(TetherSkin.Machine) {
                    Composer(
                        session = ComposerFixtures.session,
                        projection = fixture.projection,
                        controls = null,
                        serverNow = { ComposerFixtures.BUSY_NOW },
                        onSend = { text, attachments ->
                            sends += text to attachments
                            true
                        },
                        onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                        onQueueEdit = { _, _ -> },
                        onQueueRemove = {},
                        onRequestControls = {},
                        liveness = ComposerLiveness.Live,
                        attachments = ComposerAttachments(
                            staged = staged,
                            stage = { sources -> stager.stage(ComposerFixtures.SESSION_ID, sources).also { staged = store.items(ORIGIN, ComposerFixtures.SESSION_ID) } },
                            onRemove = { id -> store.remove(ORIGIN, ComposerFixtures.SESSION_ID, id); staged = store.items(ORIGIN, ComposerFixtures.SESSION_ID) },
                            send = { text, _ ->
                                attachmentSends += text to staged.map { it.attachment }
                                store.clear()
                                staged = emptyList()
                                com.tether.app.client.AttachmentSendResult.Sent
                            },
                        ),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** The paperclip, then one of the sheet's rows (the registry answers with [picks] at once). */
    private fun pick(row: String) {
        rule.onNodeWithContentDescription("Add attachment").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription(row).performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithRemove().isNotEmpty() }
    }

    private fun input() = rule.onNodeWithContentDescription("Message the agent")

    private fun inputText(): String =
        input().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /** Type [settled] and let it settle; then run [sameStep] with the IME's live connection in one UI-thread step. */
    private fun typeThen(settled: String, sameStep: (InputConnection, View) -> Unit) {
        input().requestFocus()
        input().performTextInput(settled)
        rule.waitForIdle()
        rule.runOnUiThread {
            val view = rule.activity.window.decorView.findFocus()
            assertNotNull("the composer's view holds focus", view)
            val ic = view!!.onCreateInputConnection(EditorInfo())
            assertNotNull("an input connection", ic)
            sameStep(ic!!, view)
        }
        rule.waitForIdle()
    }

    @Test
    fun theImeSendActionSendsTheLastKeystrokeToo() {
        show(ComposerFixtures.idle)
        typeThen("hello wor") { ic, _ ->
            ic.commitText("ld", 1)
            ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
        }
        assertEquals(listOf("hello world"), sends.map { it.first })
        assertEquals("", inputText())
    }

    @Test
    fun aHardwareEnterSendsTheLastKeystrokeToo() {
        show(ComposerFixtures.idle)
        typeThen("hello wor") { ic, view ->
            ic.commitText("ld", 1)
            view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
            view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        }
        assertEquals(listOf("hello world"), sends.map { it.first })
        assertEquals("", inputText())
    }

    @Test
    fun theSendKeySendsTheLastKeystrokeToo() {
        show(ComposerFixtures.idle)
        val send = rule.onNodeWithContentDescription("Send message")
        typeThen("hello wor") { ic, _ ->
            ic.commitText("ld", 1)
            send.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!.invoke()
        }
        assertEquals(listOf("hello world"), sends.map { it.first })
    }

    @Test
    fun whileBusyTheQueuedTextIncludesTheLastKeystroke() {
        show(ComposerFixtures.busy)
        typeThen("after this tur") { ic, _ ->
            ic.commitText("n", 1)
            ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
        }
        assertEquals(listOf("after this turn"), sends.map { it.first })
        assertEquals("", inputText())
        // And through the Queue key.
        val queue = rule.onNodeWithContentDescription("Queue message")
        typeThen("and the next on") { ic, _ ->
            ic.commitText("e", 1)
            queue.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!.invoke()
        }
        assertEquals(listOf("after this turn", "and the next one"), sends.map { it.first })
    }

    @Test
    fun attachmentsWhileATurnRunsAreRefusedAndKept() {
        val file = File(tmp.root, "notes.txt").apply { writeText("hello") }
        picks = listOf(Uri.fromFile(file))
        show(ComposerFixtures.busy)
        pick(ATTACH_ROW_FILES)
        input().performTextInput("with the file")
        rule.onNodeWithContentDescription("Queue message").performClick()
        rule.waitForIdle()
        assertEquals("nothing sent, nothing queued", emptyList<Pair<String, List<Attachment>>>(), sends)
        assertEquals(emptyList<Pair<String, List<Attachment>>>(), attachmentSends)
        rule.onNodeWithText("Wait for the current turn to finish before sending attachments.").assertExists()
        assertEquals("the text stays", "with the file", inputText())
        assertEquals("the file stays", 1, rule.onAllNodesWithRemove().size)
    }

    @Test
    fun aPickedFileIsStagedByTheRealIntakeAndGoesOnlyWithTheSendTap() {
        val file = File(tmp.root, "notes.txt").apply { writeText("hello") }
        picks = listOf(Uri.fromFile(file))
        show(ComposerFixtures.idle)
        pick(ATTACH_ROW_FILES)
        assertEquals(emptyList<Pair<String, List<Attachment>>>(), attachmentSends)
        input().performTextInput("see the file")
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.waitForIdle()
        val (text, attachments) = attachmentSends.single()
        assertEquals("see the file", text)
        assertEquals("aGVsbG8=", attachments.single().data)
        assertEquals("the text path carried nothing", emptyList<Pair<String, List<Attachment>>>(), sends)
        assertEquals(0, rule.onAllNodesWithRemove().size)
    }

    @Test
    fun theImageRowStagesThePhotoPickersPicturesUnderTheirSniffedType() {
        val bitmap = android.graphics.Bitmap.createBitmap(20, 10, android.graphics.Bitmap.Config.ARGB_8888)
        val file = File(tmp.root, "photo").apply { outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        picks = listOf(Uri.fromFile(file))
        show(ComposerFixtures.idle)
        pick(ATTACH_ROW_IMAGES)
        assertEquals("image/png", store.items(ORIGIN, ComposerFixtures.SESSION_ID).single().attachment.mediaType)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithRemove() =
        onAllNodes(androidx.compose.ui.test.hasContentDescription("Remove ", substring = true)).fetchSemanticsNodes()

    private companion object {
        const val ORIGIN = "https://tether.test:443"
    }
}
