package com.tether.app.ui.chat

import com.tether.app.protocol.helpers.AttachmentDraft
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T11.2: a share's files are read when they arrive (the sender's grant ends with the receiving
 * activity) and handed later to the composer's own intake, which applies its caps and flashes then.
 */
@RunWith(RobolectricTestRunner::class)
class SharedAttachmentsTest {
    private val dir: File = Files.createTempDirectory("shared").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private class Source(
        override val displayName: String?,
        private val bytes: ByteArray?,
        override val reportedSize: Long? = bytes?.size?.toLong(),
        override val declaredType: String? = "text/plain",
    ) : AttachmentSource {
        var opened = 0
        override fun open(): InputStream? {
            opened++
            return bytes?.let(::ByteArrayInputStream)
        }
    }

    @Test
    fun readFilesAreCopiedAndStagedByTheIntakeLikeAPick() {
        val a = Source("notes.txt", "hello".toByteArray())
        val b = Source("data.csv", "1,2".toByteArray(), declaredType = "text/csv")
        val buffered = SharedAttachments.buffer(listOf(a, b), dir)!!
        assertEquals(2, buffered.size)
        assertTrue(buffered.all { it.file?.isFile == true })
        assertEquals(5L, buffered[0].reportedSize)

        var id = 0L
        val result = AttachmentIntake.intake(buffered, emptyList(), { ++id })
        assertEquals(listOf("notes.txt", "data.csv"), result.added.map { it.attachment.name })
        assertEquals(listOf("text/plain", "text/csv"), result.added.map { it.attachment.mediaType })
        assertEquals("hello", String(java.util.Base64.getDecoder().decode(result.added[0].attachment.data)))
        assertTrue(result.flashes.isEmpty())
    }

    @Test
    fun anUnreadableOrOversizedFileFlashesAsAPickWould() {
        val missing = Source("gone.bin", null)
        val huge = Source("big.bin", ByteArray(0), reportedSize = AttachmentDraft.MAX_ATTACHMENT_BYTES.toLong() + 5)
        val buffered = SharedAttachments.buffer(listOf(missing, huge), dir)!!
        assertNull(buffered[0].file)
        assertNull(buffered[1].file)
        assertEquals(0, huge.opened)

        var id = 0L
        val result = AttachmentIntake.intake(buffered, emptyList(), { ++id })
        assertTrue(result.added.isEmpty())
        assertEquals(listOf(AttachmentCopy.unreadable("gone.bin"), AttachmentCopy.tooLarge("big.bin")), result.flashes)
    }

    @Test
    fun onlyAsManyFilesAsTheCountCapAreReadAndTheIntakeFlashesTheCap() {
        val sources = (1..AttachmentDraft.MAX_ATTACHMENTS + 2).map { Source("f$it.txt", "x$it".toByteArray()) }
        val buffered = SharedAttachments.buffer(sources, dir)!!
        assertEquals(sources.size, buffered.size)
        assertEquals(0, sources.last().opened)

        var id = 0L
        val result = AttachmentIntake.intake(buffered, emptyList(), { ++id })
        assertEquals(AttachmentDraft.MAX_ATTACHMENTS, result.added.size)
        assertEquals(listOf(AttachmentCopy.COUNT), result.flashes)
    }

    @Test
    fun aReceiverThatWentAwayKeepsNothing() {
        val a = Source("a.txt", "a".toByteArray())
        assertNull(SharedAttachments.buffer(listOf(a), dir, active = { false }))
        assertFalse(dir.listFiles().orEmpty().any { it.isFile })
    }
}
