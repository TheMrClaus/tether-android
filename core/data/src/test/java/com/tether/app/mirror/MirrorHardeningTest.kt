package com.tether.app.mirror

import com.tether.app.mirror.MirrorFixture.Companion.obj
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T13.1 round 2 (security review M1, M2, L3, L4; verifier F3, F5): a wipe that cannot be lost,
 * a writer that cannot die silently, bounded blobs and origins, the v2 AAD, and delete order.
 */
@RunWith(RobolectricTestRunner::class)
class MirrorHardeningTest {
    private val origin = "https://tether.example:443"
    private var fx = MirrorFixture()
    private val m get() = fx.mirror

    @After
    fun tearDown() = fx.close()

    private fun replace(fixture: MirrorFixture) {
        fx.close()
        fx = fixture
    }

    private fun state(marker: String = "base") = obj("""{"tetherSessionId":"s1","marker":"$marker","turnsById":{},"turnOrder":[]}""")

    private fun event(seq: Long, text: String = "e$seq") = """{"type":"message_delta","text":"$text","seq":$seq}"""

    // ---- M1: the keys go on the calling thread ----

    @Test
    fun wipeShredsTheKeysBeforeTheWriterRunsAndADeathCannotUndoIt() = runBlocking {
        m.bind(origin)
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.flush()
        // Hold the writer: the Wipe op cannot run. hydrateAsync enqueues at once, and the
        // writer is known to be inside the hold before wipe() is called.
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        m.beforeHydrateRead = {
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
        }
        val held = m.hydrateAsync(origin, "s1")
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        m.wipe()
        // Already gone, with the writer still held.
        assertFalse(fx.keyFile.exists())
        assertTrue(fx.kekKeys.destroyed >= 1)
        assertTrue("the DB file is still there (the writer never ran)", fx.dbFile(origin).isFile)
        // Process death before the writer deletes the files.
        Thread { Thread.sleep(200); gate.countDown() }.start()
        fx.restart()
        held.cancel()
        // The next process finds no key: nothing on disk is readable, and it is deleted.
        val index = m.bind(origin)!!
        assertTrue(index.sessions.isEmpty() && index.cursors.isEmpty())
        assertEquals(Hydration.None, m.hydrate(origin, "s1"))
    }

    @Test
    fun purgeDeletesEveryMirrorFileIncludingOrphanSiblingsAndBothKeys() = runBlocking {
        m.bind(origin)
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.flush()
        m.abandon()
        // An interrupted delete of another origin's DB left only its WAL.
        val orphan = File(fx.dbFile(origin).parentFile, "mirror-0123456789abcdef.db-wal").apply { writeText("pages") }
        assertTrue("an orphan WAL is listed", "mirror-0123456789abcdef.db" in fx.factory.existing())
        JournalMirror.purge(fx.factory, fx.keyStore())
        assertFalse(orphan.exists())
        assertFalse(fx.dbFile(origin).exists())
        assertTrue(fx.factory.existing().isEmpty())
        assertFalse(fx.keyFile.exists())
        assertTrue(fx.kekKeys.destroyed >= 1)
    }

    @Test
    fun siblingsAreListedAndDeletedBeforeTheMainFile() {
        assertEquals(
            listOf("mirror-0123456789abcdef.db", "mirror-fedcba9876543210.db"),
            MirrorFiles.dbNames(
                listOf(
                    "mirror-0123456789abcdef.db", "mirror-0123456789abcdef.db-wal",
                    "mirror-fedcba9876543210.db-shm", "other.db", "mirror-short.db-wal",
                ),
            ),
        )
        val dir = kotlin.io.path.createTempDirectory("mirror-del").toFile()
        val main = File(dir, "mirror-0123456789abcdef.db")
        listOf("", "-wal", "-shm", "-journal").forEach { File(main.path + it).writeText("x") }
        MirrorFiles.deleteWithSiblings(main)
        assertTrue(dir.listFiles()!!.isEmpty())
        dir.deleteRecursively()
    }

    // ---- M2: a writer that dies answers everyone and refuses the rest ----

    @Test
    fun anErrorInAReadStopsTheWriterAndEveryCallAnswersAtOnce() = runBlocking {
        m.bind(origin)
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.flush()
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        m.beforeHydrateRead = {
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            throw OutOfMemoryError("simulated")
        }
        val read = m.hydrateAsync(origin, "s1")
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        // Callers queued behind the failing op are answered too, not left hanging.
        val flush = async(kotlinx.coroutines.Dispatchers.Default) { m.flush() }
        val rebind = async(kotlinx.coroutines.Dispatchers.Default) { m.bind(origin) }
        Thread.sleep(200) // both are queued behind the held op
        gate.countDown()
        withTimeout(5_000) {
            assertEquals(Hydration.None, read.await())
            flush.await()
            assertNull(rebind.await())
        }
        assertTrue(m.dead)
        withTimeout(1_000) {
            assertNull("bind answers null at once", m.bind(origin))
            assertEquals(Hydration.None, m.hydrate(origin, "s1"))
            m.flush()
        }
        // Logout still destroys what is on disk.
        m.wipe()
        assertFalse(fx.keyFile.exists())
        assertFalse(fx.dbFile(origin).exists())
    }

    @Test
    fun anErrorInACommitStopsTheWriterToo() = runBlocking {
        replace(
            MirrorFixture(maxBlobPlaintextBytes = 64, onLog = { if ("size cap" in it) throw StackOverflowError("simulated") }),
        )
        m.bind(origin)
        m.recordState(origin, "s1", 10, null, obj("""{"x":1}"""), emptySet())
        m.recordEvent(origin, "s1", 11, "message_delta", 11, event(11, "x".repeat(200))) // refused -> log -> Error
        withTimeout(5_000) { m.flush() }
        assertTrue(m.dead)
        withTimeout(1_000) { assertNull(m.bind(origin)) }
    }

    // ---- M2 / L3: per-blob and per-origin caps ----

    @Test
    fun anOversizedStateLeavesNoCopyOfItsSessionRatherThanAStaleOne() = runBlocking {
        replace(MirrorFixture(maxBlobPlaintextBytes = 400))
        m.bind(origin)
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.flush()
        assertTrue(m.hydrate(origin, "s1") is Hydration.Loaded)
        m.recordState(origin, "s1", 20, null, state("y".repeat(1_000)), emptySet())
        m.flush()
        assertEquals(Hydration.None, m.hydrate(origin, "s1"))
        assertTrue(m.bind(origin)!!.cursors.isEmpty())
    }

    @Test
    fun anOversizedEventStopsCoverageAndOversizedRowsAndDetailsAreNotKept() = runBlocking {
        replace(MirrorFixture(maxBlobPlaintextBytes = 400))
        m.bind(origin)
        m.recordSessions(origin, listOf(SessionRowInput("big", """{"id":"big","n":"${"z".repeat(1_000)}"}""", 1, null, false, false)), full = false)
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.recordEvent(origin, "s1", 11, "message_delta", 11, event(11, "x".repeat(1_000)))
        m.recordTurnDetails(origin, "s1", mapOf("t0" to (0 to obj("""{"turnId":"t0","text":"${"d".repeat(1_000)}"}"""))))
        m.flush()
        val h = (m.hydrate(origin, "s1") as Hydration.Loaded).session
        assertNull("not covered", h.cursor)
        assertTrue(h.tail.isEmpty())
        assertTrue(h.details.isEmpty())
        assertTrue(m.bind(origin)!!.sessions.none { it.sessionId == "big" })
    }

    @Test
    fun aSealedBlobOverTheRowCapIsRefused() = runBlocking {
        replace(MirrorFixture(maxStoredBlobBytes = 200))
        m.bind(origin)
        // Random text barely compresses: over 200 sealed bytes.
        val random = java.util.Random(7)
        val noise = (1..600).map { "abcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(36)] }.joinToString("")
        m.recordState(origin, "s1", 10, null, state(noise), emptySet())
        m.flush()
        assertEquals(Hydration.None, m.hydrate(origin, "s1"))
    }

    @Test
    fun anOriginHoldsAtMostMaxSessionsBases() = runBlocking {
        replace(MirrorFixture(maxSessions = 2))
        m.bind(origin)
        for (id in listOf("a", "b", "c")) m.recordState(origin, id, 1, null, state(id), emptySet())
        m.flush()
        assertEquals(setOf("a", "b"), m.bind(origin)!!.cursors.keys)
        // An existing one can still be replaced.
        m.recordState(origin, "a", 2, null, state("a2"), emptySet())
        m.flush()
        assertEquals(mapOf("a" to 2L, "b" to 1L), m.bind(origin)!!.cursors)
    }

    @Test
    fun anOriginHoldsAtMostMaxOriginBytes() = runBlocking {
        replace(MirrorFixture(maxOriginBytes = 400))
        m.bind(origin)
        m.recordState(origin, "a", 1, null, state("a"), emptySet())
        m.flush()
        repeat(20) { i -> m.recordEvent(origin, "a", 2L + i, "message_delta", null, event(2L + i)) }
        m.recordState(origin, "b", 1, null, state("b"), emptySet())
        m.flush()
        val index = m.bind(origin)!!
        assertFalse("the tail stopped being covered at the cap", "a" in index.cursors)
        assertFalse("no room for another base", "b" in index.cursors)
    }

    // ---- L4: the v2 AAD and blob version ----

    @Test
    fun theAadCannotBeForgedWithSeparatorsInServerIds() {
        // Under v1 (NUL-separated) these two collided.
        assertFalse(
            MirrorCipher.aad("o", "journal_event", "a\u0000b", "c")
                .contentEquals(MirrorCipher.aad("o", "journal_event", "a", "b\u0000c")),
        )
        val key = ByteArray(32) { it.toByte() }
        val c = MirrorCipher(key)
        val blob = c.seal("x".toByteArray(), MirrorCipher.aad("o", "t", "a\u0000b", "c"))
        try {
            c.open(blob, MirrorCipher.aad("o", "t", "a", "b\u0000c"))
            throw AssertionError("a forged split opened")
        } catch (_: MirrorBlobException) {
        }
        assertEquals(2, blob[0].toInt())
    }

    @Test
    fun aDbOfAnotherBlobVersionIsStartedOver() = runBlocking {
        m.bind(origin)
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.flush()
        m.abandon()
        val db = fx.factory.open(JournalMirror.dbName(JournalMirror.originKeyOf(origin)))
        db.openHelper.writableDatabase.execSQL("UPDATE meta SET value = '1' WHERE `key` = 'blob_version'")
        db.close()
        fx.restart()
        assertTrue(m.bind(origin)!!.cursors.isEmpty())
        assertEquals(Hydration.None, m.hydrate(origin, "s1"))
    }

    // ---- F3: a base that cannot even be read is Corrupt, not "no copy" ----

    @Test
    fun aBaseReadFailureIsCorruptAndDropsTheCursor() = runBlocking {
        m.bind(origin)
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.flush()
        m.beforeHydrateRead = { throw android.database.sqlite.SQLiteBlobTooBigException("row too big") }
        assertEquals(Hydration.Corrupt, m.hydrate(origin, "s1"))
        m.beforeHydrateRead = null
        assertTrue(m.bind(origin)!!.cursors.isEmpty())
        assertFalse(m.dead)
    }
}
