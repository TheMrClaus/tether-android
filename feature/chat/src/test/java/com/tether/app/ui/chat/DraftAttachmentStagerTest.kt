package com.tether.app.ui.chat

import com.tether.app.client.DraftComposerModel
import com.tether.app.ui.prefs.InMemoryDraftStore
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ta-abm: the new-session draft's attachments go through the T7.4 intake (the same caps and flashes)
 * into the draft's own staged set, and a pick still being read when that set is dropped (the draft
 * left with its server) is discarded, never added to the next draft.
 */
@RunWith(RobolectricTestRunner::class)
class DraftAttachmentStagerTest {
    private val job = Job()

    @After fun stop() = job.cancel()

    private fun model() = DraftComposerModel(
        client = ChatTestClient(),
        draftStore = InMemoryDraftStore(),
        scope = CoroutineScope(Dispatchers.Unconfined + job),
        currentWorkspace = { "/w" },
    ).also { it.onOrigin("https://a.example:443") }

    private class TextSource(override val displayName: String, private val bytes: ByteArray, private val onOpen: () -> Unit = {}) : AttachmentSource {
        override val reportedSize: Long = bytes.size.toLong()
        override val declaredType: String = "text/plain"
        override fun open(): InputStream {
            onOpen()
            return ByteArrayInputStream(bytes)
        }
    }

    @Test
    fun aPickIsStagedIntoTheDraftWithItsSize() = runBlocking {
        val model = model()
        val flashes = DraftAttachmentStager(model).stage(listOf(TextSource("notes.txt", "hello".toByteArray())))
        assertTrue(flashes.isEmpty())
        val staged = model.state.value.staged.single()
        assertEquals("notes.txt", staged.attachment.name)
        assertEquals(5L, staged.sizeBytes)
    }

    @Test
    fun theWebsCountCapFlashesAndStagesNoMore() = runBlocking {
        val model = model()
        val stager = DraftAttachmentStager(model)
        stager.stage((1..10).map { TextSource("f$it.txt", "x".toByteArray()) })
        val flashes = stager.stage(listOf(TextSource("eleven.txt", "x".toByteArray())))
        assertEquals(listOf(AttachmentCopy.COUNT), flashes)
        assertEquals(10, model.state.value.staged.size)
    }

    @Test
    fun aPickReadWhileTheDraftIsDroppedIsDiscarded() = runBlocking {
        val model = model()
        val source = TextSource("late.txt", "late".toByteArray(), onOpen = { model.onOrigin("https://b.example:443") })
        DraftAttachmentStager(model).stage(listOf(source))
        assertTrue(model.state.value.staged.isEmpty())
    }
}
