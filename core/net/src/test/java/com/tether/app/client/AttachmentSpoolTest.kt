package com.tether.app.client

import com.tether.app.protocol.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/** ta-delivery A1: the spool's own rules, and the persist / restore of a pending record through it. */
class AttachmentSpoolTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir() = File(tmp.root, "spool")
    private fun b64(n: Int, fill: Int = 7) = Base64.getEncoder().encodeToString(ByteArray(n) { (it + fill).toByte() })
    private val photo = Attachment("photo.jpg", "image/jpeg", b64(1000, 1))
    private val notes = Attachment("notes.txt", "text/plain", b64(50, 2))

    private fun storeWith(key: String, vararg atts: Attachment, text: String = "hi") =
        PendingInput.addRecord(PendingInput.emptyStore(), key, PendingInput.KIND_SEND, "s1", text, 1_000L, atts.toList()).store

    @Test
    fun putThenLoadRoundTripsExactlyAndSurvivesANewSpoolInstance() {
        val refs = AttachmentSpool(dir()).put("k1", listOf(photo, notes))!!
        assertEquals(listOf(1000L, 50L), refs.map { it.bytes })
        // "process death": a fresh instance over the same directory.
        assertEquals(listOf(photo, notes), AttachmentSpool(dir()).load("k1", refs))
        // the files hold decoded bytes, not base64 text
        assertEquals(1000L, File(dir(), "k1/0.bin").length())
    }

    @Test
    fun aRecordWithTwoAttachmentsComesBackFromAPersistedPayloadAfterProcessDeath() {
        val spool = AttachmentSpool(dir())
        val raw = PendingInput.toPersistable(storeWith("k1", photo, notes, text = "two files"), listOf("gone"), spool)
        assertFalse("bytes in the slot: $raw", raw.contains(photo.data))
        assertTrue(raw.contains("attachmentFiles"))
        val restored = PendingInput.fromPersisted(raw, AttachmentSpool(dir())).records.single()
        assertEquals("two files", restored.text)
        assertEquals(listOf(photo, notes), restored.attachments)
        assertEquals(setOf("gone"), PendingInput.clearedFromPersisted(raw))
    }

    @Test
    fun withoutASpoolARecordWithAttachmentsIsStillLeftOut() {
        val raw = PendingInput.toPersistable(storeWith("k1", photo))
        assertTrue(PendingInput.fromPersisted(raw).records.isEmpty())
    }

    @Test
    fun aRecordWhoseFileWentMissingOrChangedIsDroppedNeverSentWithoutItsAttachments() {
        val spool = AttachmentSpool(dir())
        val raw = PendingInput.toPersistable(storeWith("k1", photo, notes), spool = spool)
        File(dir(), "k1/1.bin").writeBytes(byteArrayOf(1, 2, 3)) // size no longer the recorded one
        assertTrue(PendingInput.fromPersisted(raw, AttachmentSpool(dir())).records.isEmpty())
        File(dir(), "k1/1.bin").delete()
        assertTrue(PendingInput.fromPersisted(raw, AttachmentSpool(dir())).records.isEmpty())
    }

    @Test
    fun aTextOnlyRecordBesideASpooledOneIsKeptAsItWas() {
        val spool = AttachmentSpool(dir())
        var store = storeWith("k1", photo)
        store = PendingInput.addRecord(store, "k2", PendingInput.KIND_QUEUE, "s1", "plain", 2_000L).store
        val restored = PendingInput.fromPersisted(PendingInput.toPersistable(store, spool = spool), AttachmentSpool(dir()))
        assertEquals(listOf("k1", "k2"), restored.records.map { it.key })
        assertNull(restored.records[1].attachments)
    }

    @Test
    fun capsFollowTheServer_overACapNothingIsSpooledAndNothingIsLeftOnDisk() {
        val small = AttachmentSpool(dir(), maxFileBytes = 100, maxRecordFiles = 2, maxRecordBytes = 150, maxTotalBytes = 200)
        assertNull("file over the per-file cap", small.put("a", listOf(Attachment("x", "t", b64(101)))))
        assertNull("too many files", small.put("b", List(3) { Attachment("x", "t", b64(10)) }))
        assertNull("over the per-message total", small.put("c", listOf(Attachment("x", "t", b64(90)), Attachment("y", "t", b64(90)))))
        assertNotNull(small.put("d", listOf(Attachment("x", "t", b64(100)))))
        assertNull("over the whole spool", small.put("e", listOf(Attachment("x", "t", b64(100)), Attachment("y", "t", b64(10)))))
        assertEquals(listOf("d"), dir().list()!!.toList())
        // the defaults are the server's: 10 files, 9 MB each, 18 MB a message
        assertNull(AttachmentSpool(dir()).put("f", List(11) { Attachment("x", "t", b64(1)) }))
        assertNull(AttachmentSpool(dir()).put("g", listOf(Attachment("x", "t", b64(9 * 1024 * 1024 + 1)))))
    }

    @Test
    fun aSendThatDoesNotFitStaysMemoryOnlyAsBefore() {
        val tiny = AttachmentSpool(dir(), maxFileBytes = 10)
        val raw = PendingInput.toPersistable(storeWith("k1", photo), spool = tiny)
        assertTrue(PendingInput.fromPersisted(raw, tiny).records.isEmpty())
        assertTrue(dir().list().isNullOrEmpty())
    }

    @Test
    fun nonCanonicalBase64AndUnsafeKeysAreNotSpooled() {
        val spool = AttachmentSpool(dir())
        assertNull(spool.put("k", listOf(Attachment("x", "t", "aGVsbG8"))))   // no padding: would not round-trip
        assertNull(spool.put("../escape", listOf(photo)))
        assertNull(spool.put("a/b", listOf(photo)))
        assertNull(spool.load("..", listOf(SpoolRef("x", "t", "0.bin", 1))))
        assertNull(spool.load("k", listOf(SpoolRef("x", "t", "../../etc", 1))))
    }

    @Test
    fun deleteRemovesARecordsFiles() {
        val spool = AttachmentSpool(dir())
        spool.put("k1", listOf(photo))
        spool.put("k2", listOf(notes))
        spool.delete("k1")
        assertEquals(listOf("k2"), dir().list()!!.toList())
    }

    @Test
    fun sweepRemovesEveryDirectoryNoRecordReferencesAndLeftoverTempFiles() {
        val spool = AttachmentSpool(dir())
        spool.put("live", listOf(photo))
        spool.put("orphan", listOf(notes))
        File(dir(), "stray.txt").writeText("x")
        File(dir(), "live/9.bin.tmp").writeText("half")
        val removed = spool.sweep(setOf("live"))
        assertEquals(3, removed)
        assertEquals(listOf("live"), dir().list()!!.toList())
        assertEquals(listOf("0.bin"), File(dir(), "live").list()!!.toList())
        // the sweep forgot what it deleted: the same key can be written again
        assertNotNull(spool.put("orphan", listOf(notes)))
    }

    @Test
    fun persistedKeysNamesEveryRecordOfAPayload() {
        val spool = AttachmentSpool(dir())
        val raw = PendingInput.toPersistable(storeWith("k1", photo), spool = spool)
        assertEquals(setOf("k1"), PendingInput.persistedKeys(raw))
        assertEquals(emptySet<String>(), PendingInput.persistedKeys("not json"))
        assertEquals(emptySet<String>(), PendingInput.persistedKeys(null))
    }
}
