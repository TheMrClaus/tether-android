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
import kotlinx.coroutines.launch
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
        // r2: the read watchdog asks too, from its own thread: the caller "goes away" after 3 chunks read.
        val r = AttachmentIntake.intake(listOf(endless, FakeSource("next", 1)), emptyList(), { 1L }, active = { endless.read.get() < 3 * 64 * 1024 })
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
        // r2 (verifier L1): the server's bound is 128 characters (a longer type fails the whole send).
        val at = "a".repeat(63) + "/" + "b".repeat(64)
        assertEquals(at, AttachmentTypes.resolve("abc".toByteArray(), at))
        assertEquals(AttachmentTypes.OCTET, AttachmentTypes.resolve("abc".toByteArray(), "a".repeat(64) + "/" + "b".repeat(64)))
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

    // --- r2 M1: only another app's content:// provider is ever read -------------------------------

    private val context: android.content.Context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    /** A hostile app's provider: serves [payload] for every URI, and says every one is a PNG. */
    class PayloadProvider : android.content.ContentProvider() {
        companion object {
            @Volatile var payload: ByteArray = ByteArray(0)
        }

        override fun onCreate() = true
        override fun getType(uri: android.net.Uri) = "image/png"
        override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor {
            val f = java.io.File.createTempFile("payload", ".bin").apply {
                writeBytes(payload)
                deleteOnExit()
            }
            return android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): android.database.Cursor? = null
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
        override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private val evil = "com.evil.provider"
    private val own = "com.tether.test.own"

    /** Installs [evil] (another package) and [own] (this app's) the way the package manager answers them. */
    private fun policy(): AttachmentUriPolicy {
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        pm.installPackage(android.content.pm.PackageInfo().apply {
            packageName = "com.evil"
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "com.evil" }
        })
        pm.addOrUpdateProvider(android.content.pm.ProviderInfo().apply {
            authority = evil
            packageName = "com.evil"
            name = PayloadProvider::class.java.name
        })
        pm.addOrUpdateProvider(android.content.pm.ProviderInfo().apply {
            authority = own
            packageName = context.packageName
            name = "com.tether.test.OwnProvider"
        })
        org.robolectric.Robolectric.setupContentProvider(PayloadProvider::class.java, evil)
        return AttachmentUriPolicy.of(context)
    }

    private fun clip(uri: android.net.Uri, vararg types: String) =
        android.content.ClipData(android.content.ClipDescription("x", arrayOf(*types)), android.content.ClipData.Item(uri))

    private val jpegHead = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)

    @Test
    fun onlyAnotherAppsResolvableContentProviderIsAllowed() {
        val p = policy()
        assertTrue(p.allows(android.net.Uri.parse("content://$evil/x.png")))
        assertFalse("file://", p.allows(android.net.Uri.parse("file:///data/data/${context.packageName}/files/datastore/drafts.preferences_pb")))
        assertFalse("own provider", p.allows(android.net.Uri.parse("content://$own/drafts")))
        assertFalse("own provider under a user prefix", p.allows(android.net.Uri.parse("content://10@$own/drafts")))
        assertFalse("unresolved", p.allows(android.net.Uri.parse("content://com.nobody.provider/x")))
        assertFalse("no authority", p.allows(android.net.Uri.parse("content:///x")))
        assertFalse("android.resource", p.allows(android.net.Uri.parse("android.resource://${context.packageName}/raw/x")))
        assertFalse("http", p.allows(android.net.Uri.parse("http://$evil/x")))
    }

    @Test
    fun aPickerFileUriIsNeverOpenedOrAsked() {
        val p = policy()
        val secret = java.io.File(context.filesDir, "drafts.preferences_pb").apply { writeText("private drafts for every server") }
        val source = ContentUriSource(context.contentResolver, android.net.Uri.fromFile(secret), p)
        assertNull(source.open())
        assertNull(source.displayName)
        assertNull(source.reportedSize)
        assertNull(source.declaredType)
        val r = intake(listOf(source))
        assertTrue(r.added.isEmpty())
        assertEquals(listOf(AttachmentCopy.unreadable(AttachmentNames.FALLBACK)), r.flashes)
    }

    @Test
    fun aPickerUriOfTheAppsOwnProviderIsNeverOpened() {
        val source = ContentUriSource(context.contentResolver, android.net.Uri.parse("content://$own/drafts"), policy())
        assertNull(source.open())
        assertTrue(intake(listOf(source)).added.isEmpty())
    }

    @Test
    fun aClipboardFileUriOwnProviderOrUnresolvedAuthorityIsNotAPicture() {
        val p = policy()
        val secret = java.io.File(context.filesDir, "secret.png").apply { writeBytes(pngHead + ByteArray(32)) }
        for (uri in listOf(
            android.net.Uri.fromFile(secret),
            android.net.Uri.parse("content://$own/drafts"),
            android.net.Uri.parse("content://com.nobody.provider/x"),
        )) {
            assertTrue("offered: $uri", ClipboardImages.sources(clip(uri, "image/png"), context.contentResolver, p).isEmpty())
        }
    }

    @Test
    fun aPastedItemIsAPictureOnlyIfItsBytesAreOne() {
        val p = policy()
        val uri = android.net.Uri.parse("content://$evil/x.png")
        // Text behind an image/png description (and an image/png provider type): no picture.
        PayloadProvider.payload = "not a picture at all".toByteArray()
        val lie = ClipboardImages.sources(clip(uri, "image/png"), context.contentResolver, p)
        assertEquals(1, lie.size)
        val r = intake(lie)
        assertTrue(r.added.isEmpty())
        assertEquals(listOf(AttachmentCopy.NO_CLIPBOARD_IMAGE), r.flashes)
        // A PNG is staged as one.
        PayloadProvider.payload = pngHead + ByteArray(32)
        val png = intake(ClipboardImages.sources(clip(uri, "image/png"), context.contentResolver, p)).added.single().attachment
        assertEquals("pasted-image.png" to "image/png", png.name to png.mediaType)
        // A JPEG claimed as a PNG goes as what its bytes are, and is named for them.
        PayloadProvider.payload = jpegHead + ByteArray(32)
        val jpeg = intake(ClipboardImages.sources(clip(uri, "image/png"), context.contentResolver, p)).added.single().attachment
        assertEquals("pasted-image.jpeg" to "image/jpeg", jpeg.name to jpeg.mediaType)
    }

    @Test
    fun aPastedPdfOrOtherNonPictureIsDropped() {
        val pdf = object : AttachmentSource {
            override val displayName = "pasted-image.png"
            override val reportedSize: Long? = null
            override val declaredType = "image/png"
            override val imagesOnly = true
            override fun open(): InputStream = "%PDF-1.7 ...".byteInputStream()
        }
        val r = intake(listOf(pdf, FakeSource("fine.txt", 4)))
        assertEquals(listOf("fine.txt"), r.added.map { it.attachment.name })
        assertEquals(listOf(AttachmentCopy.NO_CLIPBOARD_IMAGE), r.flashes)
    }

    // --- r2 L3: a stalled, overlong or abandoned read is ended by closing its stream -------------

    /**
     * Runs [block] on its own thread and fails the test if it is not done in [ms] (r3: a stall test
     * that regresses fails instead of hanging the fork).
     */
    private fun <T> within(ms: Long, block: () -> T): T =
        java.util.concurrent.CompletableFuture.supplyAsync(block).get(ms, java.util.concurrent.TimeUnit.MILLISECONDS)

    private fun await(latch: java.util.concurrent.CountDownLatch, what: String) =
        assertTrue("never $what", latch.await(10, java.util.concurrent.TimeUnit.SECONDS))

    /**
     * Delivers [first] bytes, then blocks in read() until closed (Android's close unblocks a read).
     * [stuck] opens once a read is blocked; [closes] counts the closes.
     */
    private class StallingSource(private val first: Int = 1) : AttachmentSource {
        val closed = java.util.concurrent.CountDownLatch(1)
        val stuck = java.util.concurrent.CountDownLatch(1)
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val readReturned = java.util.concurrent.CountDownLatch(1)
        override val displayName = "stall.bin"
        override val reportedSize: Long? = null
        override val declaredType = "application/octet-stream"
        override fun open(): InputStream = object : InputStream() {
            var given = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (given < first) {
                    given++
                    b[off] = 'x'.code.toByte()
                    return 1
                }
                stuck.countDown()
                try {
                    if (!closed.await(60, java.util.concurrent.TimeUnit.SECONDS)) error("never closed")
                    throw java.io.IOException("closed")
                } finally {
                    readReturned.countDown()
                }
            }
            override fun close() {
                closes.incrementAndGet()
                closed.countDown()
            }
        }
    }

    private companion object {
        /** Like a Binder call: an interrupt does not end the wait. */
        fun awaitUninterruptibly(latch: java.util.concurrent.CountDownLatch, seconds: Long): Boolean {
            val deadline = System.nanoTime() + seconds * 1_000_000_000
            var interrupted = false
            try {
                while (true) {
                    try {
                        return latch.await(deadline - System.nanoTime(), java.util.concurrent.TimeUnit.NANOSECONDS)
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    /** A provider whose open ignores the signal (and interrupts) and returns a stream only when [release] opens. */
    private class OpenStall : AttachmentSource {
        val opening = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val lateClosed = java.util.concurrent.CountDownLatch(1)
        override val displayName = "openstall.bin"
        override val reportedSize: Long? = null
        override val declaredType = "application/octet-stream"
        override fun open(): InputStream? = open(android.os.CancellationSignal())
        override fun open(signal: android.os.CancellationSignal): InputStream {
            opening.countDown()
            awaitUninterruptibly(release, 60)
            return object : java.io.ByteArrayInputStream("x".toByteArray()) {
                override fun close() = lateClosed.countDown()
            }
        }
    }

    /** A provider whose query (name, size, type) stalls until [release] opens. */
    private class QueryStall : AttachmentSource {
        val asking = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        override val displayName: String?
            get() {
                asking.countDown()
                release.await(60, java.util.concurrent.TimeUnit.SECONDS)
                return "q.txt"
            }
        override val reportedSize: Long? = null
        override val declaredType = "text/plain"
        override fun open(): InputStream = "hi".byteInputStream()
    }

    private val quick = ReadLimits(idleMs = 200, totalMs = 60_000, pollMs = 20)

    @Test
    fun aReadThatStallsIsEndedByClosingItsStream() = within(20_000) {
        val stall = StallingSource()
        val started = System.nanoTime()
        assertSame(BoundedRead.TimedOut, readBounded(stall, limits = quick))
        assertEquals("the stream was not closed", 0, stall.closed.count)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 5_000_000_000)
        // Through the intake: "Could not read", and the batch goes on.
        val r = AttachmentIntake.intake(listOf(StallingSource(), FakeSource("ok.txt", 2)), emptyList(), { 1L }, limits = quick)
        assertEquals(listOf("ok.txt"), r.added.map { it.attachment.name })
        assertEquals(listOf(AttachmentCopy.unreadable("stall.bin")), r.flashes)
    }

    @Test
    fun aReadThatTricklesPastTheTotalIsEndedToo() = within(20_000) {
        val trickle = object : AttachmentSource {
            override val displayName = "slow.bin"
            override val reportedSize: Long? = null
            override val declaredType = "application/octet-stream"
            override fun open(): InputStream = object : InputStream() {
                @Volatile var shut = false
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (shut) throw java.io.IOException("closed")
                    Thread.sleep(30)
                    b[off] = 'x'.code.toByte()
                    return 1
                }
                override fun close() { shut = true }
            }
        }
        assertSame(BoundedRead.TimedOut, readBounded(trickle, limits = ReadLimits(idleMs = 1_000, totalMs = 300, pollMs = 20)))
    }

    @Test
    fun aCancelledPickClosesAStalledStream() = within(20_000) {
        val stall = StallingSource()
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val result = java.util.concurrent.CompletableFuture.supplyAsync { readBounded(stall, active = { active.get() }, limits = ReadLimits(60_000, 60_000, 20)) }
        await(stall.stuck, "stuck in a read")
        active.set(false)
        assertSame(BoundedRead.Cancelled, result.get(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(0, stall.closed.count)
    }

    /** Security re-check (ta-wrx): the caller and the reader both reach the close; the stream is closed once. */
    @Test
    fun aWalkedAwayFromStreamIsClosedExactlyOnce() = within(20_000) {
        val stall = StallingSource()
        assertSame(BoundedRead.TimedOut, readBounded(stall, limits = quick))
        // The reader's IOException, its own close attempt, and the call's end have all happened.
        await(stall.readReturned, "returned from the read")
        Thread.sleep(50)
        assertEquals(1, stall.closes.get())
    }

    /** F2 (r3): the pick is cancelled only once its read is known to be stuck (no sleeps). */
    @Test
    fun aStalledPickNeverHoldsTheStager() = within(30_000) {
        runBlocking {
            val store = StagedAttachments()
            val stager = AttachmentStager(store, { "http://a.example:80" }, Dispatchers.IO, limits = ReadLimits(60_000, 60_000, 20))
            // Cancelled (the composer left): the stream is closed and the stager is free at once.
            val stall = StallingSource()
            val job = launch(Dispatchers.Default) { stager.stage("s1", listOf(stall)) }
            await(stall.stuck, "stuck in a read")
            job.cancel()
            withTimeout(5_000) { job.join() }
            await(stall.closed, "closed")
            withTimeout(5_000) { stager.stage("s1", listOf(FakeSource("next.txt", 2))) }
            assertEquals(listOf("next.txt"), store.items("http://a.example:80", "s1").map { it.attachment.name })
            // Timed out: the same, with the web's "Could not read".
            val timed = AttachmentStager(store, { "http://a.example:80" }, Dispatchers.IO, limits = quick)
            assertEquals(listOf(AttachmentCopy.unreadable("stall.bin")), withTimeout(5_000) { timed.stage("s1", listOf(StallingSource())) })
            withTimeout(5_000) { timed.stage("s1", listOf(FakeSource("after.txt", 2))) }
            assertEquals(listOf("next.txt", "after.txt"), store.items("http://a.example:80", "s1").map { it.attachment.name })
        }
    }

    // --- r3 F1: a provider that stalls in its open or its query never holds the stager ---------

    @Test
    fun anOpenThatIgnoresItsSignalIsWalkedAwayFromAndItsLateStreamClosed() = within(20_000) {
        val stall = OpenStall()
        val started = System.nanoTime()
        assertSame(BoundedRead.TimedOut, readBounded(stall, limits = ReadLimits(idleMs = 200, totalMs = 300, pollMs = 20)))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms against 200/300 ms limits", ms < 2_000)
        // The provider answers at last: its stream is closed, its bytes discarded.
        stall.release.countDown()
        await(stall.lateClosed, "closed the late stream")
    }

    @Test
    fun aCancelledPickStalledInItsOpenNeverHoldsTheStager() = within(30_000) {
        runBlocking {
            val store = StagedAttachments()
            val stager = AttachmentStager(store, { "http://a.example:80" }, Dispatchers.IO, limits = ReadLimits(60_000, 60_000, 20))
            val stall = OpenStall()
            try {
                val job = launch(Dispatchers.Default) { stager.stage("s1", listOf(stall)) }
                await(stall.opening, "opening")
                job.cancel()
                withTimeout(1_500) { stager.stage("s1", listOf(FakeSource("next.txt", 2))) }
                assertEquals(listOf("next.txt"), store.items("http://a.example:80", "s1").map { it.attachment.name })
                withTimeout(5_000) { job.join() }
            } finally {
                stall.release.countDown()
            }
            // The late open is discarded: nothing of it is ever staged.
            await(stall.lateClosed, "closed the late stream")
            assertEquals(listOf("next.txt"), store.items("http://a.example:80", "s1").map { it.attachment.name })
        }
    }

    @Test
    fun aQueryThatStallsIsWalkedAwayFromAndTheBatchGoesOn() = within(20_000) {
        val stall = QueryStall()
        try {
            val started = System.nanoTime()
            val r = AttachmentIntake.intake(listOf(stall, FakeSource("ok.txt", 2)), emptyList(), { 1L }, limits = ReadLimits(100, 200, 20))
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("took $ms ms against a 200 ms limit", ms < 2_000)
            assertEquals(listOf("ok.txt"), r.added.map { it.attachment.name })
            assertEquals(listOf(AttachmentCopy.unreadable(AttachmentNames.FALLBACK)), r.flashes)
        } finally {
            stall.release.countDown()
        }
    }

    /**
     * F1 (r3): a provider that never returns keeps its provider-call thread, and there are at most
     * [ProviderCalls.MAX_THREADS] of them: with every one stuck, a new call is refused at once (never
     * queued behind them, never a new thread), and the threads come back when the providers return.
     */
    @Test
    fun stuckProviderCallsAreBoundedAndANewCallIsRefusedAtOnce() = within(30_000) {
        val stalls = ArrayList<OpenStall>()
        try {
            var refused = false
            for (i in 0..ProviderCalls.MAX_THREADS) {
                val stall = OpenStall()
                val r = readBounded(stall, limits = ReadLimits(idleMs = 50, totalMs = 100, pollMs = 10))
                if (r === BoundedRead.Failed) {
                    refused = true
                    break
                }
                assertSame(BoundedRead.TimedOut, r)
                stalls += stall
            }
            assertTrue("more than ${ProviderCalls.MAX_THREADS} threads were stuck", refused)
            val started = System.nanoTime()
            assertSame(BoundedRead.Failed, readBounded(FakeSource("next.txt", 2), limits = ReadLimits(60_000, 60_000, 10)))
            assertTrue("a refusal waited", System.nanoTime() - started < 1_000_000_000)
        } finally {
            stalls.forEach { it.release.countDown() }
        }
        stalls.forEach { await(it.lateClosed, "closed a late stream") }
        // The threads are back: a read goes through again.
        val deadline = System.nanoTime() + 5_000_000_000
        var r: BoundedRead
        do {
            r = readBounded(FakeSource("again.txt", 2), limits = ReadLimits(60_000, 60_000, 10))
        } while (r !is BoundedRead.Bytes && System.nanoTime() < deadline)
        assertTrue("$r", r is BoundedRead.Bytes)
    }

    /** A provider whose query waits for its CancellationSignal (or 10 s), and says whether it saw it. */
    class SignalQueryProvider : android.content.ContentProvider() {
        companion object {
            val sawCancel = java.util.concurrent.CountDownLatch(1)
        }

        override fun onCreate() = true
        override fun getType(uri: android.net.Uri) = "text/plain"
        override fun query(uri: android.net.Uri, projection: Array<out String>?, queryArgs: android.os.Bundle?, signal: android.os.CancellationSignal?): android.database.Cursor? {
            val cancelled = java.util.concurrent.CountDownLatch(1)
            signal?.setOnCancelListener { cancelled.countDown() }
            if (awaitUninterruptibly(cancelled, 10)) sawCancel.countDown()
            return null
        }
        override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?, signal: android.os.CancellationSignal?): android.database.Cursor? =
            query(uri, p, null as android.os.Bundle?, signal)
        override fun query(uri: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): android.database.Cursor? = query(uri, p, null as android.os.Bundle?, null)
        override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor {
            val f = java.io.File.createTempFile("q", ".txt").apply { writeText("hi") }
            return android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
        override fun delete(uri: android.net.Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Test
    fun aContentUrisQueryIsGivenASignalThatIsCancelledWhenItIsWalkedAwayFrom() {
        val authority = "com.slow.provider"
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        pm.installPackage(android.content.pm.PackageInfo().apply {
            packageName = "com.slow"
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "com.slow" }
        })
        pm.addOrUpdateProvider(android.content.pm.ProviderInfo().apply {
            this.authority = authority
            packageName = "com.slow"
            name = SignalQueryProvider::class.java.name
        })
        org.robolectric.Robolectric.setupContentProvider(SignalQueryProvider::class.java, authority)
        val source = ContentUriSource(context.contentResolver, android.net.Uri.parse("content://$authority/x"), AttachmentUriPolicy.of(context))
        within(20_000) {
            val started = System.nanoTime()
            val r = AttachmentIntake.intake(listOf(source), emptyList(), { 1L }, limits = ReadLimits(100, 200, 20))
            assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 2_000_000_000)
            assertEquals(listOf(AttachmentCopy.unreadable(AttachmentNames.FALLBACK)), r.flashes)
            await(SignalQueryProvider.sawCancel, "cancelled the query's signal")
        }
    }

    @Test
    fun aPickWhoseSessionIsNoLongerAllowedWhenTheReadEndsIsDiscarded() = runBlocking {
        val store = StagedAttachments()
        var allowed = true
        val source = object : AttachmentSource {
            override val displayName = "f.txt"
            override val reportedSize: Long = 2
            override val declaredType = "text/plain"
            override fun open(): InputStream {
                allowed = false // deselected or locked while it was being read
                return "hi".byteInputStream()
            }
        }
        AttachmentStager(store, { "http://a.example:80" }, Dispatchers.IO, allowed = { allowed }).stage("s1", listOf(source))
        assertNull(store.current.value)
        // Not allowed at all: not even opened.
        val never = FakeSource("n.txt", 2)
        AttachmentStager(store, { "http://a.example:80" }, Dispatchers.IO, allowed = { false }).stage("s1", listOf(never))
        assertFalse(never.opened)
    }

    // --- r2 L1: the chip thumbnail is bounded on its OUTPUT -----------------------------------------

    @Test
    fun theChipThumbnailSampleBoundsBothSidesAndTheBitmap() {
        for ((w, h) in listOf(65535 to 191, 191 to 65535, 65535 to 1, 20000 to 20000, 4000 to 3000, 192 to 192, 48 to 32)) {
            val sample = chipThumbnailSample(w, h) ?: continue
            val dw = maxOf(1, w / sample).toLong()
            val dh = maxOf(1, h / sample).toLong()
            assertTrue("$w x $h -> $dw x $dh", dw <= CHIP_THUMB_SIDE * 2 && dh <= CHIP_THUMB_SIDE * 2)
            assertTrue("$w x $h -> ${dw * dh * 4} bytes", dw * dh * 4 <= CHIP_THUMB_MAX_BYTES)
        }
        // The goldens' 48 × 32 picture is drawn as it is.
        assertEquals(1, chipThumbnailSample(48, 32))
    }

    @Test
    fun aCraftedWidePictureDecodesSmall() {
        // 6000 × 8: a short side far under the chip, so the old short-side bound decoded it whole.
        val wide = java.util.Base64.getEncoder().encodeToString(png(6000, 8))
        val thumb = chipThumbnail(wide)
        assertTrue(thumb != null)
        assertTrue("decoded ${thumb!!.width} x ${thumb.height}", thumb.width <= CHIP_THUMB_SIDE * 2 && thumb.height <= CHIP_THUMB_SIDE * 2)
    }
}
