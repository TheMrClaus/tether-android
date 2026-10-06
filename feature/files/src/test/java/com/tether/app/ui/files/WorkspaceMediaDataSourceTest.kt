package com.tether.app.ui.files

import com.tether.app.client.FilesResult
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ta-1u4: the player's bytes. Range reads only, one server for the whole playback, no size cap,
 * and a close that frees a reader stuck on the network.
 */
@RunWith(RobolectricTestRunner::class)
class WorkspaceMediaDataSourceTest {
    private val files = FakeFiles()
    private val path = "/w/clip.mp4"

    private fun source(size: Long, block: Int = 1024): WorkspaceMediaDataSource {
        files.virtualFiles[path] = size
        return WorkspaceMediaDataSource(files, path, blockSize = block)
    }

    private fun WorkspaceMediaDataSource.read(position: Long, size: Int): Pair<Int, ByteArray> {
        val buffer = ByteArray(size)
        return readAt(position, buffer, 0, size) to buffer
    }

    private fun reads() = files.calls.filter { it.startsWith("readRange") }

    @Test fun openLearnsTheLengthAndPinsTheServer() = runBlocking {
        val s = source(10_000)
        assertTrue(s.open())
        assertEquals(10_000L, s.getSize())
        assertEquals("https://server-a:443", s.origin)
        assertEquals(listOf("readRange $path 0 1024"), reads())
    }

    @Test fun theStartOfTheFileIsServedFromTheFirstReadWithoutAnotherRequest() = runBlocking {
        val s = source(10_000)
        s.open()
        val (n, bytes) = s.read(10, 100)
        assertEquals(100, n)
        assertEquals(10, bytes[0].toInt())
        assertEquals(109, bytes[99].toInt())
        assertEquals(1, reads().size)
    }

    @Test fun aSeekIsOneRangeRequestForThatBlockNotADownload() = runBlocking {
        val s = source(1_000_000)
        s.open()
        val (n, bytes) = s.read(500_000, 100)
        assertEquals(100, n)
        assertEquals((500_000 % 251).toByte(), bytes[0])
        assertEquals(listOf("readRange $path 0 1024", "readRange $path 500000 1024"), reads())
        // Sequential reads inside that block cost nothing more.
        s.read(500_100, 100)
        assertEquals(2, reads().size)
        assertTrue("no whole-file download", files.calls.none { it.startsWith("download") })
    }

    @Test fun aReadAcrossABlockEndReturnsWhatThatBlockHolds() = runBlocking {
        val s = source(10_000)
        s.open()
        val (n, _) = s.read(1000, 100)
        assertEquals("the rest of the first block", 24, n)
        val (m, bytes) = s.read(1024, 100)
        assertEquals(100, m)
        assertEquals((1024 % 251).toByte(), bytes[0])
    }

    @Test fun theEndOfTheFileIsMinusOneAndTheLastBlockIsShort() = runBlocking {
        val s = source(1_500)
        s.open()
        assertEquals(-1, s.read(1_500, 10).first)
        assertEquals(-1, s.read(99_999, 10).first)
        val (n, _) = s.read(1_400, 1_000)
        assertEquals(100, n)
        assertEquals("the last block asks for what is left, not a whole block", "readRange $path 1400 100", reads().last())
    }

    @Test fun thereIsNoSizeCap() = runBlocking {
        val tenGiB = 10L * 1024 * 1024 * 1024
        val s = source(tenGiB)
        assertTrue(s.open())
        assertEquals(tenGiB, s.getSize())
        val (n, bytes) = s.read(9L * 1024 * 1024 * 1024, 64)
        assertEquals(64, n)
        assertEquals(((9L * 1024 * 1024 * 1024) % 251).toByte(), bytes[0])
        assertTrue(files.calls.none { it.startsWith("download") || it.startsWith("head") })
    }

    @Test fun aReadForAnotherServerFailsThePlaybackAndNeverGoesThere() = runBlocking {
        val s = source(10_000)
        s.open()
        // The client signed in to another server mid-playback: the pinned read is refused.
        files.rangeOrigin = "https://server-b:443"
        try {
            s.read(5_000, 10)
            fail("a read for another server must not succeed")
        } catch (_: IOException) {
        }
        // Not served from a cache either for what was never read: the first block still is, it came from A.
        assertEquals(10, s.read(0, 10).first)
    }

    @Test fun aSignedOutSessionFailsTheNextReadAndNotTheCachedOnes() = runBlocking {
        val s = source(10_000)
        s.open()
        files.failures["readRange"] = FilesResult.Failed("Sign in to browse workspace files.")
        try {
            s.read(5_000, 10)
            fail("signed out")
        } catch (_: IOException) {
        }
    }

    @Test fun aFileThatChangedSizeUnderThePlayerFailsIt() = runBlocking {
        val s = source(10_000)
        s.open()
        files.virtualFiles[path] = 20_000
        try {
            s.read(5_000, 10)
            fail("size changed")
        } catch (_: IOException) {
        }
    }

    @Test fun anUnreadableOrEmptyFileDoesNotOpen() = runBlocking {
        assertFalse(WorkspaceMediaDataSource(files, "/w/missing.mp4").open())
        assertFalse(source(0).open())
    }

    @Test fun closingStopsReadsAndFreesAReaderBlockedOnTheNetwork() {
        val s = source(1_000_000)
        runBlocking { s.open() }
        val gate = CompletableDeferred<Unit>()
        files.gates["readRange"] = gate
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        Thread {
            try {
                s.read(500_000, 10)
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                done.countDown()
            }
        }.start()
        // The reader is parked in the (never-answered) request.
        val deadline = System.currentTimeMillis() + 5_000
        while (reads().size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(2, reads().size)
        s.close()
        assertTrue("the blocked reader came back", done.await(5, TimeUnit.SECONDS))
        assertTrue(failure.get() is IOException)
        try {
            s.read(0, 1)
            fail("closed")
        } catch (_: IOException) {
        }
    }

    @Test fun aBigAskIsAnsweredOneBlockAtATime() = runBlocking {
        val s = source(10_000_000)
        s.open()
        val (n, _) = s.read(2_000_000, 4096)
        assertEquals("one block's worth; the player asks again for the rest", 1024, n)
        assertEquals("readRange $path 2000000 1024", reads().last())
    }
}
