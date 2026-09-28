package com.tether.app.mirror

import com.tether.app.mirror.MirrorFixture.Companion.obj
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T13.1 (SYNC_DESIGN §8, §11 `MirrorCipherTest` DB half): what an attacker holding the DB
 * files sees and can do. No transcript text on disk; a blob moved to another row is rejected;
 * losing the data key or its KEK wipes the mirror instead of leaving unreadable (or
 * mis-keyed) rows behind.
 */
@RunWith(RobolectricTestRunner::class)
class MirrorAtRestTest {
    private val origin = "https://tether.example:443"
    private val fx = MirrorFixture()
    private val m get() = fx.mirror
    private val marker = "PLAINTEXT-MARKER-7f3a9c"

    @After
    fun tearDown() = fx.close()

    private fun seed() = runBlocking {
        m.bind(origin)
        m.recordSessions(origin, listOf(SessionRowInput("s1", """{"id":"s1","name":"$marker-name"}""", 1, null, false, false)), full = true)
        m.recordState(origin, "s1", 10, null, obj("""{"tetherSessionId":"s1","text":"$marker-base"}"""), emptySet())
        m.recordEvent(origin, "s1", 11, "message_delta", 11, """{"type":"message_delta","text":"$marker-e11","seq":11}""")
        m.recordEvent(origin, "s1", 12, "message_delta", 12, """{"type":"message_delta","text":"$marker-e12","seq":12}""")
        m.recordTurnDetails(origin, "s1", mapOf("t0" to (0 to obj("""{"turnId":"t0","text":"$marker-detail"}"""))))
        m.flush()
    }

    @Test
    fun noPlaintextInTheDbFiles() {
        seed()
        val bytes = String(fx.dbBytes(origin), Charsets.ISO_8859_1)
        assertTrue("the DB was written", bytes.length > 4096)
        assertFalse("transcript text on disk", bytes.contains(marker))
        assertFalse("the key id is metadata, the key is not", bytes.contains("tether.mirror.datakey"))
        // Index columns stay in clear by design (§8.1: activity metadata only).
        assertTrue(bytes.contains("message_delta"))
    }

    @Test
    fun aBlobMovedToAnotherRowIsRejectedAndTheSessionDropped() = runBlocking {
        seed()
        // Swap the two event payloads in place, as an attacker with file access could.
        m.abandon()
        val db = fx.factory.open(JournalMirror.dbName(JournalMirror.originKeyOf(origin)))
        db.openHelper.writableDatabase.apply {
            execSQL("UPDATE journal_event SET seq = -1 WHERE session_id = 's1' AND seq = 11")
            execSQL("UPDATE journal_event SET seq = 11 WHERE session_id = 's1' AND seq = 12")
            execSQL("UPDATE journal_event SET seq = 12 WHERE session_id = 's1' AND seq = -1")
        }
        db.close()
        fx.restart()
        m.bind(origin)
        assertEquals(Hydration.Corrupt, m.hydrate(origin, "s1"))
        // Dropped: the next attach is a full one.
        assertEquals(Hydration.None, m.hydrate(origin, "s1"))
        assertTrue(m.bind(origin)!!.cursors.isEmpty())
    }

    @Test
    fun aSessionRowCopiedToAnotherIdIsDiscarded() = runBlocking {
        seed()
        m.abandon()
        val db = fx.factory.open(JournalMirror.dbName(JournalMirror.originKeyOf(origin)))
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO session_row SELECT 's2', blob, updated_at, last_message_at, pinned, runtime_archived, " +
                "server_last_seq, gone_from_server, last_opened_at FROM session_row WHERE session_id = 's1'",
        )
        db.close()
        fx.restart()
        assertEquals(listOf("s1"), m.bind(origin)!!.sessions.map { it.sessionId })
    }

    @Test
    fun losingTheDataKeyWipesTheMirror() = runBlocking {
        seed()
        fx.restart()
        fx.keyFile.delete()
        val index = m.bind(origin)!!
        assertTrue(index.sessions.isEmpty())
        assertTrue(index.cursors.isEmpty())
        assertEquals(Hydration.None, m.hydrate(origin, "s1"))
    }

    @Test
    fun losingTheKekWipesTheMirror() = runBlocking {
        seed()
        fx.restart()
        fx.kekKeys.key = null // Keystore reset / restore to a new device
        val index = m.bind(origin)!!
        assertTrue(index.sessions.isEmpty())
        assertEquals(Hydration.None, m.hydrate(origin, "s1"))
        // A fresh key was minted and works.
        m.recordState(origin, "s1", 3, null, obj("""{"x":1}"""), emptySet())
        m.flush()
        assertTrue(m.hydrate(origin, "s1") is Hydration.Loaded)
    }

    @Test
    fun aDbSealedUnderAnotherKeyIsStartedOver() = runBlocking {
        seed()
        fx.restart()
        // A valid key file, but not the one this DB was sealed under.
        fx.keyStore().create()
        assertTrue(m.bind(origin)!!.sessions.isEmpty())
        assertEquals(Hydration.None, m.hydrate(origin, "s1"))
    }
}
