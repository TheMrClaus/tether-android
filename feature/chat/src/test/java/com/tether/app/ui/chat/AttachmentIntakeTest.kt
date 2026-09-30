package com.tether.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tether.app.client.AttachmentFrame
import com.tether.app.client.StagedAttachment
import com.tether.app.client.StagedAttachments
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.helpers.AttachmentDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * T7.4: staging, against tether components/chat-view.tsx addFiles (:2531-2566) and
 * lib/attachment-draft.ts. The web's limits and copy exactly (10 files, 9 MB each, 18 MB in total,
 * "You can attach up to 10 files at once." and the rest), plus the native rules for content another
 * app hands over: a bounded read that never trusts the claimed size, the type decided from the bytes
 * for the formats the model takes natively, a cleaned wire name, and the frame bound.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentIntakeTest {

    /** A source of [size] bytes (a PNG header when [png]); counts what was read and whether it was opened. */
    private class FakeSource(
        override val displayName: String?,
        private val size: Long,
        override val reportedSize: Long? = size,
        override val declaredType: String? = "text/plain",
        private val head: ByteArray = ByteArray(0),
        private val fail: Boolean = false,
    ) : AttachmentSource {
        val read = AtomicLong()
        var opened = false
        override fun open(): InputStream? {
            opened = true
            if (fail) return null
            return object : InputStream() {
                var pos = 0L
                override fun read(): Int {
                    if (pos >= size) return -1
                    val b = if (pos < head.size) head[pos.toInt()].toInt() and 0xFF else 'x'.code
                    pos++
                    read.incrementAndGet()
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (pos >= size) return -1
                    val n = minOf(len.toLong(), size - pos).toInt()
                    for (i in 0 until n) b[off + i] = if (pos + i < head.size) head[(pos + i).toInt()] else 'x'.code.toByte()
                    pos += n
                    read.addAndGet(n.toLong())
                    return n
                }
            }
        }
    }

    private val ids = AtomicLong(1)
    private fun intake(sources: List<AttachmentSource>, existing: List<StagedAttachment> = emptyList(), prepare: (ByteArray, String) -> ImageShrink.Prepared = { b, t -> ImageShrink.Prepared(b, t) }) =
        AttachmentIntake.intake(sources, existing, { ids.getAndIncrement() }, prepare)

    private fun staged(size: Long, name: String = "f") = StagedAttachment(ids.getAndIncrement(), Attachment(name, "text/plain", "eA=="), size)

    private val mb = 1024L * 1024

    // --- the web's limits and copy ---------------------------------------------------------------

    @Test
    fun theWebsCapsAndCopy() {
        assertEquals(10, AttachmentDraft.MAX_ATTACHMENTS)
        assertEquals(9 * mb, AttachmentDraft.MAX_ATTACHMENT_BYTES.toLong())
        assertEquals(18 * mb, AttachmentDraft.MAX_ATTACHMENTS_TOTAL_BYTES.toLong())
        assertEquals("You can attach up to 10 files at once.", AttachmentCopy.COUNT)
        assertEquals("\"a.bin\" is too large (max 9.0 MB).", AttachmentCopy.tooLarge("a.bin"))
        assertEquals("Attachments exceed the total size limit (18.0 MB).", AttachmentCopy.TOTAL)
        assertEquals("Could not read \"a.bin\".", AttachmentCopy.unreadable("a.bin"))
        assertEquals("No image found on the clipboard.", AttachmentCopy.NO_CLIPBOARD_IMAGE)
        assertEquals("Couldn't read the clipboard — grant clipboard permission and try again.", AttachmentCopy.CLIPBOARD_UNREADABLE)
    }

    @Test
    fun theCountCapStopsAtTenAndSaysSo() {
        val existing = (1..8).map { staged(10) }
        val r = intake((1..4).map { FakeSource("f$it.txt", 10) }, existing)
        assertEquals(2, r.added.size)
        assertEquals(listOf(AttachmentCopy.COUNT), r.flashes)
    }

    @Test
    fun anOversizedFileIsSkippedWithoutBeingOpenedAndTheRestStillStage() {
        val big = FakeSource("big.iso", 9 * mb + 1)
        val ok = FakeSource("ok.txt", 100)
        val r = intake(listOf(big, ok))
        assertFalse("an over-cap file was opened", big.opened)
        assertEquals(listOf("ok.txt"), r.added.map { it.attachment.name })
        assertEquals(listOf(AttachmentCopy.tooLarge("big.iso")), r.flashes)
    }

    @Test
    fun exactlyNineMegabytesIsAccepted() {
        val r = intake(listOf(FakeSource("nine.bin", 9 * mb)), prepare = { b, t -> ImageShrink.Prepared(b, t) })
        // 9 MB of raw bytes is 12 MB of base64: within the web's cap, and within the frame bound.
        assertEquals(1, r.added.size)
        assertEquals(9 * mb, r.added.single().sizeBytes)
    }

    @Test
    fun theTotalCapStopsTheBatchAndSaysSo() {
        val existing = listOf(staged(9 * mb), staged(8 * mb))
        val r = intake(listOf(FakeSource("a.txt", 2 * mb), FakeSource("b.txt", 10)), existing)
        assertTrue(r.added.isEmpty())
        assertEquals(listOf(AttachmentCopy.TOTAL), r.flashes)
    }

    @Test
    fun anUnreadableFileSaysSoAndTheBatchGoesOn() {
        val r = intake(listOf(FakeSource("gone.txt", 10, fail = true), FakeSource("ok.txt", 10)))
        assertEquals(listOf("ok.txt"), r.added.map { it.attachment.name })
        assertEquals(listOf(AttachmentCopy.unreadable("gone.txt")), r.flashes)
        // An empty file cannot be sent either (protocol-validate: data must be non-empty).
        val empty = intake(listOf(FakeSource("empty.txt", 0)))
        assertTrue(empty.added.isEmpty())
        assertEquals(listOf(AttachmentCopy.unreadable("empty.txt")), empty.flashes)
    }

    @Test
    fun theLastFlashIsTheOneShownAsOnTheWeb() {
        val r = intake(listOf(FakeSource("big.iso", 10 * mb), FakeSource("gone", 1, fail = true), FakeSource("ok", 1)))
        assertEquals(AttachmentCopy.unreadable("gone"), r.flashes.last())
        assertEquals(1, r.added.size)
    }

    // --- untrusted sizes: bounded reads ------------------------------------------------------------

    @Test
    fun anUnknownSizeIsReadInBoundedChunksAndAbortedAtTheCap() {
        // A provider that reports no size and streams 100 MB: the read stops just past 9 MB.
        val endless = FakeSource("stream.bin", 100 * mb, reportedSize = null)
        val r = intake(listOf(endless))
        assertTrue(r.added.isEmpty())
        assertEquals(listOf(AttachmentCopy.tooLarge("stream.bin")), r.flashes)
        assertTrue("read ${endless.read.get()} bytes", endless.read.get() <= 9 * mb + 64 * 1024)
    }

    @Test
    fun aProviderThatUnderstatesItsSizeIsStillCutAtTheCap() {
        val liar = FakeSource("liar.bin", 50 * mb, reportedSize = 10)
        val r = intake(listOf(liar))
        assertTrue(r.added.isEmpty())
        assertEquals(listOf(AttachmentCopy.tooLarge("liar.bin")), r.flashes)
        assertTrue(liar.read.get() <= 9 * mb + 64 * 1024)
    }

    @Test
    fun anUnknownSizeCountsTowardTheTotalOnceRead() {
        val existing = listOf(staged(9 * mb), staged(8 * mb))
        val r = intake(listOf(FakeSource("u.txt", 2 * mb, reportedSize = null)), existing)
        assertTrue(r.added.isEmpty())
        assertEquals(listOf(AttachmentCopy.TOTAL), r.flashes)
    }

    @Test
    fun aCancelledReadStopsAtTheNextChunkAndStagesNothing() {
        val endless = FakeSource("stream.bin", 100 * mb, reportedSize = null)
        var chunks = 0
        val r = AttachmentIntake.intake(listOf(endless, FakeSource("next", 1)), emptyList(), { 1L }, active = { chunks++ < 3 })
        assertTrue(r.added.isEmpty())
        assertTrue(r.flashes.isEmpty())
        assertTrue("read ${endless.read.get()}", endless.read.get() <= 3 * 64 * 1024)
    }

    // --- the frame bound (native) ----------------------------------------------------------------

    @Test
    fun theFrameBoundStopsABatchTheWebWouldTakeAndSaysSo() {
        // Two 8 MB files: 16 MB raw is within the web's 18 MB, but about 21 MB of base64 is not
        // within what one socket frame may carry.
        val r = intake(listOf(FakeSource("a.bin", 8 * mb), FakeSource("b.bin", 8 * mb)))
        assertEquals(listOf("a.bin"), r.added.map { it.attachment.name })
        assertEquals(listOf(AttachmentCopy.FRAME), r.flashes)
        assertTrue(AttachmentFrame.wireBytes(r.added.map { it.attachment }) <= AttachmentFrame.STAGING_BUDGET_BYTES)
    }

    // --- types ----------------------------------------------------------------------------------

    private val pngHead = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun theBytesDecideTheNativeFormatsNotTheProvidersClaim() {
        assertEquals("image/png", AttachmentTypes.resolve(pngHead, "text/plain"))
        assertEquals("image/jpeg", AttachmentTypes.resolve(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0), null))
        assertEquals("image/gif", AttachmentTypes.resolve("GIF89a".toByteArray(), "application/octet-stream"))
        assertEquals("image/webp", AttachmentTypes.resolve("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1), null))
        assertEquals("application/pdf", AttachmentTypes.resolve("%PDF-1.7".toByteArray(), "text/plain"))
        // A claim of a native format the bytes do not back goes as a plain file.
        assertEquals(AttachmentTypes.OCTET, AttachmentTypes.resolve("not a picture".toByteArray(), "image/png"))
        assertEquals(AttachmentTypes.OCTET, AttachmentTypes.resolve("not a pdf".toByteArray(), "application/pdf"))
        // Any other well-formed claim is kept, parameters dropped, lower-cased.
        assertEquals("text/html", AttachmentTypes.resolve("<p>".toByteArray(), "Text/HTML; charset=utf-8"))
        assertEquals("image/heic", AttachmentTypes.resolve("....ftypheic".toByteArray(), "image/heic"))
        // Malformed or hostile claims do not reach the wire.
        for (bad in listOf("", "text", "text/", "/x", "text/plain\r\nX-Evil: 1", "a/b c", "x".repeat(200) + "/y", "täxt/plain")) {
            assertEquals("claim ${bad.take(20)}", AttachmentTypes.OCTET, AttachmentTypes.resolve("abc".toByteArray(), bad))
        }
        assertEquals(AttachmentTypes.OCTET, AttachmentTypes.resolve("abc".toByteArray(), null))
    }

    @Test
    fun aStagedPictureGoesOutUnderItsSniffedType() {
        val r = intake(listOf(FakeSource("shot", 64, declaredType = "application/octet-stream", head = pngHead)))
        assertEquals("image/png", r.added.single().attachment.mediaType)
    }

    // --- hostile display names -------------------------------------------------------------------

    @Test
    fun hostileDisplayNamesAreCleanedForTheWireAndNeverAPath() {
        assertEquals("passwd", AttachmentNames.wire("../../etc/passwd"))
        assertEquals("evil.exe", AttachmentNames.wire("C:\\Users\\x\\..\\evil.exe"))
        assertEquals("invoice.pdf", AttachmentNames.wire("invoice\u202E.pdf"))
        assertEquals("gnp.exe", AttachmentNames.wire("\u202Egnp.exe"))
        assertEquals("ab", AttachmentNames.wire("a\u0000b"))
        assertEquals("a[31mb", AttachmentNames.wire("a\u001B[31mb")) // the ESC goes, the printable rest stays
        assertEquals("ab", AttachmentNames.wire("a\u200Db")) // FORMAT characters go (ZWJ, ZWSP, BOM)
        assertEquals("ab", AttachmentNames.wire("\uFEFFa\u200Bb"))
        assertEquals("ab", AttachmentNames.wire("a\u2028b"))
        assertEquals("ab", AttachmentNames.wire("a\uD800b"))
        assertEquals("ab", AttachmentNames.wire("a\uDB40\uDC41b")) // a tag character
        assertEquals("line", AttachmentNames.wire("line\r\n"))
        for (empty in listOf(null, "", "   ", ".", "..", "...", "/", "a/", "\u202E", "\u0000\u0001")) {
            assertEquals("for ${empty?.map { it.code }}", AttachmentNames.FALLBACK, AttachmentNames.wire(empty))
        }
        // Long names are cut on a code point boundary, keeping a short extension, within the budget.
        val long = AttachmentNames.wire("é".repeat(1000) + ".txt")
        assertTrue(long.endsWith(".txt"))
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= AttachmentNames.MAX_BYTES)
        val emoji = AttachmentNames.wire("😀".repeat(200))
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= AttachmentNames.MAX_BYTES)
        assertEquals(0, emoji.length % 2) // no half surrogate pair
        // RTL letters are text, not controls: kept.
        assertEquals("שלום.txt", AttachmentNames.wire("שלום.txt"))
    }

    @Test
    fun theNameThatStagesIsTheCleanedOne() {
        val r = intake(listOf(FakeSource("../../\u202Ettt.sh", 10)))
        assertEquals("ttt.sh", r.added.single().attachment.name)
        val flash = intake(listOf(FakeSource("x/\u202Ebig", 10 * mb)))
        assertEquals(listOf("\"big\" is too large (max 9.0 MB)."), flash.flashes)
    }

    @Test
    fun clipboardPicturesAreNamedTheWebsWay() {
        assertEquals("pasted-image.png", ClipboardImages.pastedName("image/png"))
        assertEquals("pasted-image.jpeg", ClipboardImages.pastedName("image/jpeg"))
        assertEquals("pasted-image.svg", ClipboardImages.pastedName("image/svg+xml"))
        assertEquals("pasted-image.png", ClipboardImages.pastedName("image/"))
        // A hostile type cannot turn the name into a path: the wire name is the last segment.
        assertEquals("x", AttachmentNames.wire(ClipboardImages.pastedName("image/../../x")))
    }

    // --- lib/attachment-draft.ts prepareAttachmentData -------------------------------------------

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height step 7) for (x in 0 until width step 5) bitmap.setPixel(x, y, (0xFF000000 or ((x * 31 + y * 17).toLong() and 0xFFFFFF)).toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun dims(bytes: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth to o.outHeight
    }

    @Test
    fun aLargePictureIsDownscaledToTheWebsLongEdgeAsJpeg() {
        val bytes = png(3000, 2000)
        val prepared = ImageShrink.prepare(bytes, "image/png")
        assertEquals("image/jpeg", prepared.mediaType)
        // scale = 1568 / 3000; round(2000 × scale) = 1045 (attachment-draft.ts Math.round).
        assertEquals(1568 to 1045, dims(prepared.bytes))
        assertTrue(prepared.bytes.size < bytes.size)
    }

    @Test
    fun aPortraitPictureScalesItsLongEdge() {
        val prepared = ImageShrink.prepare(png(1000, 4000), "image/png")
        assertEquals(392 to 1568, dims(prepared.bytes))
    }

    @Test
    fun whatTheWebPassesThroughStaysByteForByte() {
        val small = png(1568, 900)
        assertSame(small, ImageShrink.prepare(small, "image/png").bytes)
        assertEquals("image/png", ImageShrink.prepare(small, "image/png").mediaType)
        val gif = "GIF89a....".toByteArray()
        assertSame(gif, ImageShrink.prepare(gif, "image/gif").bytes)
        val garbage = pngHead + ByteArray(64)
        assertSame(garbage, ImageShrink.prepare(garbage, "image/png").bytes)
        val text = "hello".toByteArray()
        assertSame(text, ImageShrink.prepare(text, "text/plain").bytes)
    }

    @Test
    fun theStagedSizeIsWhatGoesOut() {
        val bytes = png(3000, 2000)
        val source = object : AttachmentSource {
            override val displayName = "big.png"
            override val reportedSize: Long = bytes.size.toLong()
            override val declaredType = "image/png"
            override fun open(): InputStream = bytes.inputStream()
        }
        val item = AttachmentIntake.intake(listOf(source), emptyList(), { 1L }).added.single()
        assertEquals("image/jpeg", item.attachment.mediaType)
        val decoded = java.util.Base64.getDecoder().decode(item.attachment.data)
        assertEquals(decoded.size.toLong(), item.sizeBytes)
        assertArrayEquals(decoded, ImageShrink.prepare(bytes, "image/png").bytes)
    }

    // --- the stager -------------------------------------------------------------------------------

    @Test
    fun aPickStillBeingReadWhenTheSetIsDroppedIsDiscarded() = runBlocking {
        val store = StagedAttachments()
        val gate = CompletableDeferred<Unit>()
        val slow = object : AttachmentSource {
            override val displayName = "slow.txt"
            override val reportedSize: Long? = null
            override val declaredType = "text/plain"
            override fun open(): InputStream {
                runBlocking { gate.await() }
                return "hi".byteInputStream()
            }
        }
        val stager = AttachmentStager(store, { "http://a.example:80" }, Dispatchers.IO)
        val pending = async(Dispatchers.Default) { stager.stage("s1", listOf(slow)) }
        Thread.sleep(100)
        store.clear() // a server switch, a lock, a sign-out
        gate.complete(Unit)
        withTimeout(5_000) { pending.await() }
        assertNull(store.current.value)
    }

    @Test
    fun aPickReadWhileTheServerChangedIsDiscarded() = runBlocking {
        val store = StagedAttachments()
        var origin = "http://a.example:80"
        val source = object : AttachmentSource {
            override val displayName = "f.txt"
            override val reportedSize: Long = 2
            override val declaredType = "text/plain"
            override fun open(): InputStream {
                origin = "http://b.example:80"
                return "hi".byteInputStream()
            }
        }
        AttachmentStager(store, { origin }, Dispatchers.IO).stage("s1", listOf(source))
        assertNull(store.current.value)
    }

    @Test
    fun picksStageOneBatchAtATimeUnderTheirServerAndSession() = runBlocking {
        val store = StagedAttachments()
        val stager = AttachmentStager(store, { "http://a.example:80" }, Dispatchers.IO)
        stager.stage("s1", listOf(FakeSource("a.txt", 2)))
        stager.stage("s1", listOf(FakeSource("b.txt", 2)))
        assertEquals(listOf("a.txt", "b.txt"), store.items("http://a.example:80", "s1").map { it.attachment.name })
        assertTrue(store.items("http://b.example:80", "s1").isEmpty())
        assertTrue(store.items("http://a.example:80", "s2").isEmpty())
    }
}
