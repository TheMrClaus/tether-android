package com.tether.app.ui.files

import android.content.Context
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.FilesCopy
import com.tether.app.client.FilesResult
import com.tether.app.ui.files.FilesFixtures.ROOT
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T11.1 round 2: the local side's failure modes — bounded decodes (M1), saving through a scratch
 * copy (L1), nothing thrown escaping (L2), shared copies that never outlive their rule (L3), and
 * a pending save target that survives a configuration change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FileLifecycleTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cache = FileCache(context.cacheDir)
    private val files = FakeFiles()
    private val docs = FakeDocs()
    private val target: Uri = Uri.parse("content://docs.example/document/7")
    // FileProvider caches its paths per authority for the process; Robolectric gives each test
    // class its own cache dir, so the grant URI is stubbed here (FileBrowserRecreationTest runs the real one).
    private val platform = AndroidBrowserPlatform(context, docs, cache, background = { it() }, uriFor = { Uri.fromFile(it) })
    private val entry = FilesFixtures.file("report.pdf", 5)

    @After fun tearDown() {
        cache.sweepAll()
    }

    private class FakeDocs : DocumentTarget {
        var size: Long? = 0
        var sizeThrows: RuntimeException? = null
        var openThrows: RuntimeException? = null
        val written = ByteArrayOutputStream()
        var truncated: Boolean? = null
        var deleted = false

        override fun sizeOf(uri: Uri): Long? {
            sizeThrows?.let { throw it }
            return size
        }

        override fun openForWrite(uri: Uri, truncate: Boolean): OutputStream {
            openThrows?.let { throw it }
            truncated = truncate
            return written
        }

        override fun delete(uri: Uri) {
            deleted = true
        }
    }

    private fun scratchLeft() = cache.root.listFiles().orEmpty().filter { it.isFile }

    // --- L1: Save goes through a scratch copy ---

    @Test fun aFailedDownloadNeverTouchesAnExistingDocument() = runBlocking {
        docs.size = 120 // the user chose to overwrite a file with content
        val result = platform.saveTo(files, entry, target)
        assertEquals(FilesResult.Failed("This file could not be opened.", 404), result)
        assertNull("never opened, so never truncated", docs.truncated)
        assertFalse("never deleted", docs.deleted)
        assertTrue(scratchLeft().isEmpty())
    }

    @Test fun aFailedDownloadRemovesOnlyTheEmptyDocumentThePickerCreated() = runBlocking {
        docs.size = 0
        platform.saveTo(files, entry, target)
        assertTrue(docs.deleted)
        assertNull(docs.truncated)
    }

    @Test fun theDocumentIsWrittenOnlyWithTheWholeFile() = runBlocking {
        val body = ByteArray(70_000) { (it % 13).toByte() }
        files.downloads[entry.path] = body
        docs.size = 0
        assertEquals(FilesResult.Ok(70_000L), platform.saveTo(files, entry, target))
        assertArrayEquals(body, docs.written.toByteArray())
        assertEquals("an empty document needs no truncating mode", false, docs.truncated)
        docs.written.reset()
        docs.size = 9
        platform.saveTo(files, entry, target)
        assertEquals("an overwritten one is truncated, only now", true, docs.truncated)
        assertTrue("no scratch copy left", scratchLeft().isEmpty())
    }

    @Test fun aProviderThatThrowsIsAFailedSaveNotACrash() = runBlocking {
        files.downloads[entry.path] = byteArrayOf(1, 2, 3)
        docs.size = 50
        docs.openThrows = UnsupportedOperationException("mode wt")
        assertEquals(FilesResult.Failed(AndroidBrowserPlatform.SAVE_FAILED), platform.saveTo(files, entry, target))
        assertFalse("an existing document is never deleted", docs.deleted)
        docs.sizeThrows = SecurityException("grant revoked")
        docs.openThrows = SecurityException("grant revoked")
        assertEquals(FilesResult.Failed(AndroidBrowserPlatform.SAVE_FAILED), platform.saveTo(files, entry, target))
        assertFalse("an unknown size counts as not ours to delete", docs.deleted)
        assertTrue(scratchLeft().isEmpty())
    }

    // --- L3: shared copies ---

    private fun shareDirs() = File(cache.root, FileCache.SHARE_DIR).listFiles().orEmpty().toList()

    @Test fun aCopyNeverClaimedIsDiscardedAndAClaimedOneLivesOutItsWindow() = runBlocking {
        files.downloads[entry.path] = byteArrayOf(1)
        val first = (platform.shareCopy(files, entry) as FilesResult.Ok).value
        val second = (platform.shareCopy(files, entry) as FilesResult.Ok).value
        assertEquals(2, shareDirs().size)
        platform.claimShare(first)
        platform.discardUnclaimedShares()
        assertEquals("only the claimed copy stays", listOf(File(first.id)), shareDirs())
        assertFalse(File(second.id).exists())
        // Its window: an expiry sweep keeps it now, removes it once the window has passed.
        cache.sweepExpired()
        assertTrue(File(first.id).exists())
        cache.sweepExpired(now = System.currentTimeMillis() + FileCache.SHARE_GRACE_MS + 1)
        assertFalse(File(first.id).exists())
    }

    @Test fun aShareCancelledMidDownloadLeavesNothing() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        files.gates["download"] = gate
        files.downloads[entry.path] = byteArrayOf(1)
        val job = async(Dispatchers.Default) { platform.shareCopy(files, entry) }
        withTimeout(5_000) { while (shareDirs().isEmpty()) kotlinx.coroutines.delay(5) }
        job.cancel()
        runCatching { job.await() }
        assertTrue(shareDirs().isEmpty())
    }

    @Test fun aFailedShareDownloadLeavesNothing() = runBlocking {
        assertTrue(platform.shareCopy(files, entry) is FilesResult.Failed)
        assertTrue(shareDirs().isEmpty())
    }

    // --- state: L2 (nothing escapes) and L3 (share after close) ---

    private val escaped = CopyOnWriteArrayList<Throwable>()
    private val logged = CopyOnWriteArrayList<String>()

    private fun TestScope.browser(p: BrowserPlatform, f: FakeFiles = files): FileBrowserState {
        // The parent's handler would see anything the browser let escape.
        val parent = CoroutineScope(StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> escaped += e })
        return FileBrowserState(f, p, parent, log = { logged += it }).apply {
            cwd = ROOT
            f.listings[ROOT] = FilesResult.Ok(FilesFixtures.listing())
        }
    }

    @Test fun aThrowingSaveOrUploadLandsAsAnErrorNeverAsACrash() = runTest {
        val fake = FakePlatform().apply { saveFailure = IllegalStateException("provider exploded") }
        val s = browser(fake)
        s.open()
        advanceUntilIdle()
        s.saveTo(entry, target)
        advanceUntilIdle()
        assertEquals(FilesCopy.ACTION_FALLBACK, s.mutationError)
        files.uploadThrows = SecurityException("grant revoked")
        s.upload(listOf(PickedUpload("a.txt", bytesSource(byteArrayOf(1)))))
        advanceUntilIdle()
        assertEquals(FilesCopy.ACTION_FALLBACK, s.mutationError)
        assertNull("the upload line is cleared", s.uploading)
        assertTrue("nothing reached the parent: $escaped", escaped.isEmpty())
        // Logged by class name only: a message can carry a path or file content.
        assertEquals(
            listOf("file browser job failed: java.lang.IllegalStateException", "file browser job failed: java.lang.SecurityException"),
            logged.toList(),
        )
        assertTrue(logged.none { "exploded" in it || "revoked" in it })
        // Still usable afterwards.
        s.loadDirectory(ROOT)
        advanceUntilIdle()
        assertEquals(4, s.listing!!.entries.size)
    }

    @Test fun aShareFinishingAfterCloseIsDiscardedNotShown() = runTest {
        val ready = ShareReady(target, "application/pdf", "report.pdf", "share-1")
        val fake = FakePlatform().apply {
            share = FilesResult.Ok(ready)
            shareGate = CompletableDeferred()
        }
        val s = browser(fake)
        s.open()
        advanceUntilIdle()
        s.share(entry)
        advanceUntilIdle()
        s.close()
        fake.shareGate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(s.pendingShare)
        assertTrue(fake.calls.contains("discard share-1"))
    }

    @Test fun closeDropsAPendingShareAndTheSheetClaimsOrDiscards() = runTest {
        val ready = ShareReady(target, "application/pdf", "report.pdf", "share-2")
        val fake = FakePlatform().apply { share = FilesResult.Ok(ready) }
        val s = browser(fake)
        s.open()
        advanceUntilIdle()
        s.share(entry)
        advanceUntilIdle()
        assertEquals(ready, s.pendingShare)
        s.close()
        assertNull(s.pendingShare)
        assertEquals(listOf("discard share-2", "discardUnclaimed", "sweep Expired"), fake.calls.takeLast(3))

        s.open()
        advanceUntilIdle()
        s.share(entry)
        advanceUntilIdle()
        s.shareHandled(started = true)
        assertEquals("claim share-2", fake.calls.last())
        s.share(entry)
        advanceUntilIdle()
        s.shareHandled(started = false)
        assertEquals("discard share-2", fake.calls.last())
    }

    @Test fun aShareSheetThePlatformRefusesDiscardsTheCopyWithoutACrash() {
        val ready = ShareReady(target, "application/pdf", "report.pdf", "share-3")
        val fake = FakePlatform().apply { share = FilesResult.Ok(ready) }
        files.listings[ROOT] = FilesResult.Ok(FilesFixtures.listing())
        val s = FileBrowserState(files, fake, CoroutineScope(Dispatchers.Main)).apply { cwd = ROOT }
        rule.setContent {
            val base = androidx.compose.ui.platform.LocalContext.current
            val refusing = object : android.content.ContextWrapper(base) {
                override fun startActivity(intent: android.content.Intent?) = throw SecurityException("chooser refused")
            }
            com.tether.app.ui.theme.TetherTheme(com.tether.app.ui.theme.ThemeMode.Dark) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides refusing) { WorkspaceFileBrowser(s) }
            }
        }
        rule.runOnIdle { s.open() }
        rule.waitUntil(20_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); s.listing != null }
        rule.runOnIdle { s.share(entry) }
        rule.waitUntil(20_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); fake.calls.contains("discard share-3") }
        assertNull(s.pendingShare)
        assertFalse("never claimed", fake.calls.contains("claim share-3"))
    }

    // --- process start sweep (L3, H3) ---

    @Test fun theProviderSweepsWhatAPreviousProcessLeftOffTheMainThread() {
        val now = System.currentTimeMillis()
        val leftover = cache.newScratch().apply { writeText("x") }
        val expired = cache.newShareFile("old.txt").apply { writeText("x") }.also { f -> f.parentFile!!.walkTopDown().forEach { it.setLastModified(now - FileCache.SHARE_GRACE_MS - 1) } }
        val fresh = cache.newShareFile("fresh.txt").apply { writeText("x") }
        val info = ProviderInfo().apply {
            authority = FileCache.authority(context)
            exported = false
            grantUriPermissions = true
        }
        WorkspaceFileProvider().attachInfo(context, info)
        runBlocking { withTimeout(5_000) { while (leftover.exists() || expired.exists()) kotlinx.coroutines.delay(5) } }
        assertTrue("a copy inside its window stays for the app reading it", fresh.exists())
    }

    // --- M1: bounded decode ---

    /** A PNG that is a header claiming [w] × [h] and one tiny IDAT (a few bytes on disk). */
    private fun pngHeader(w: Int, h: Int): ByteArray {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val crc = CRC32().apply { update(type.toByteArray()); update(data) }.value.toInt()
            return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(type.toByteArray()).put(data).putInt(crc).array()
        }
        val ihdr = ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(2).put(0).put(0).put(0).array()
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + chunk("IHDR", ihdr) + chunk("IDAT", deflate(ByteArray(64))) + chunk("IEND", ByteArray(0))
    }

    private fun deflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    @Test fun aPixelBombHeaderIsRefusedBeforeDecodingAndAnImageStillDecodes() {
        val bomb = cache.newScratch().apply { writeBytes(pngHeader(30_000, 30_000)) }
        assertEquals(Decoded.TooLarge, BoundedImages.decode(bomb))
        val real = cache.newScratch()
        Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(AColor.BLUE) }
            .compress(Bitmap.CompressFormat.PNG, 100, real.outputStream())
        val decoded = BoundedImages.decode(real) as Decoded.Ok
        assertEquals(48, decoded.bitmap.width)
        assertEquals(Decoded.Failed, BoundedImages.decode(cache.newScratch().apply { writeText("not an image") }))
    }

    @Test fun aDecoderThatThrowsIsTooLargeOrFailedNeverACrash() {
        val file = cache.newScratch().apply { writeBytes(pngHeader(64, 64)) }
        var calls = 0
        fun boundsThenThrow(error: Throwable): (String, android.graphics.BitmapFactory.Options) -> Bitmap? = { path, options ->
            calls++
            if (options.inJustDecodeBounds) android.graphics.BitmapFactory.decodeFile(path, options) else throw error
        }
        assertEquals(Decoded.TooLarge, BoundedImages.decode(file, boundsThenThrow(OutOfMemoryError("bitmap"))))
        assertEquals(Decoded.Failed, BoundedImages.decode(file, boundsThenThrow(IllegalArgumentException("bitmap too large"))))
        assertEquals(4, calls)
    }

    @Test fun sweepsRunOffTheMainThread() {
        val thread = CompletableDeferred<Thread>()
        FileCache.sweepInBackground { thread.complete(Thread.currentThread()) }
        val ran = runBlocking { withTimeout(5_000) { thread.await() } }
        assertTrue("swept on ${ran.name}", ran !== android.os.Looper.getMainLooper().thread && ran !== Thread.currentThread())
    }

    @Test fun claimingStartsTheWindowAtTheHandOff() = runBlocking {
        files.downloads[entry.path] = byteArrayOf(1)
        val share = (platform.shareCopy(files, entry) as FilesResult.Ok).value
        // A slow download: the copy was written long before the sheet took it.
        File(share.id).walkTopDown().forEach { it.setLastModified(System.currentTimeMillis() - FileCache.SHARE_GRACE_MS - 5_000) }
        platform.claimShare(share)
        cache.sweepExpired()
        assertTrue("the receiving app gets its full window", File(share.id).exists())
    }

    @Test fun f16CountsEightBytesAPixel() {
        assertEquals(8, BoundedImages.bytesPerPixel(Bitmap.Config.RGBA_F16))
        assertEquals(4, BoundedImages.bytesPerPixel(Bitmap.Config.ARGB_8888))
        assertEquals(4, BoundedImages.bytesPerPixel(null))
    }

    // --- verifier low: the pending save target survives a configuration change ---

    @Test fun thePendingSaveTargetSurvivesRecreation() {
        val restoration = StateRestorationTester(rule)
        var pending: androidx.compose.runtime.MutableState<com.tether.app.client.WorkspaceFileEntry?>? = null
        restoration.setContent { pending = rememberPendingSave() }
        rule.runOnIdle { pending!!.value = entry }
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { assertEquals(entry, pending!!.value) }
    }
}
