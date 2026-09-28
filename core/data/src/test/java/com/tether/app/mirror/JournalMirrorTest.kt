package com.tether.app.mirror

import com.tether.app.mirror.MirrorFixture.Companion.obj
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * T13.1: the mirror's write path (SYNC_DESIGN §2.3), hydration reads (§2.4), key checks (§8.2),
 * wipe (§8.3) and rotation (§8.1), against Robolectric's real SQLite in temp files.
 */
// Robolectric's default Conscrypt mode installs Conscrypt as a JVM-GLOBAL provider, which then
// changes how the plain-JVM CredentialCipherTest in this same test JVM fails (order-dependent).
// The core:net mirror tests keep Conscrypt on, so the mirror cipher runs under both providers.
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class JournalMirrorTest {
    private val origin = "https://tether.example:443"
    private var fx = MirrorFixture()
    private val m get() = fx.mirror

    @After
    fun tearDown() = fx.close()

    private fun state(marker: String = "base") = obj("""{"tetherSessionId":"s1","marker":"$marker","turnsById":{},"turnOrder":[]}""")

    private fun event(seq: Long, text: String = "e$seq") = """{"type":"message_delta","turnId":"t1","text":"$text","seq":$seq,"ts":$seq}"""

    private fun loaded(sessionId: String = "s1"): HydratedSession = runBlocking {
        val h = m.hydrate(origin, sessionId)
        assertTrue("expected a hydrated session, got $h", h is Hydration.Loaded)
        (h as Hydration.Loaded).session
    }

    private fun tailSeqs(h: HydratedSession) = h.tail.map { JsCodec.canonical(it["seq"]!!) }

    private fun bind(): MirrorIndex = runBlocking { m.bind(origin)!! }

    @Test
    fun stateThenContiguousEventsHydrateVerbatim() = runBlocking {
        bind()
        m.recordState(origin, "s1", 10, trimmedBefore = 3, state = state(), keepTurnIds = emptySet())
        m.recordEvent(origin, "s1", 11, "message_delta", 11, event(11))
        m.recordEvent(origin, "s1", 12, "message_delta", 12, event(12))
        m.flush()
        val h = loaded()
        assertEquals(state(), h.base)
        assertEquals(10L, h.throughSeq)
        assertEquals(3, h.trimmedBefore)
        assertEquals(SessionBaseEntity.ORIGIN_SERVER, h.origin)
        assertEquals(listOf("11", "12"), tailSeqs(h))
        assertEquals(obj(event(12)), h.tail[1])
        assertEquals(12L, h.cursor)
        assertEquals(mapOf("s1" to 12L), runBlocking { m.bind(origin)!! }.cursors)
    }

    @Test
    fun theTailStaysContiguous() = runBlocking {
        bind()
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.recordEvent(origin, "s1", 11, "message_delta", 11, event(11))
        m.recordEvent(origin, "s1", 11, "message_delta", 11, event(11, "dup")) // duplicate: ignored
        m.recordEvent(origin, "s1", 10, "message_delta", 10, event(10)) // behind: ignored
        m.flush()
        assertEquals(listOf("11"), tailSeqs(loaded()))
        assertEquals(obj(event(11)), loaded().tail[0])
        // A gap cannot be covered: not persisted, and the cursor stops claiming coverage.
        m.recordEvent(origin, "s1", 13, "message_delta", 13, event(13))
        m.recordEvent(origin, "s1", 14, "message_delta", 14, event(14))
        m.flush()
        val h = loaded()
        assertEquals(listOf("11"), tailSeqs(h))
        assertNull(h.cursor)
        assertTrue(bind().cursors.isEmpty())
    }

    @Test
    fun aSeqlessEventClearsTheCursorAndIsNotPersisted() = runBlocking {
        bind()
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.recordEvent(origin, "s1", 11, "message_delta", 11, event(11))
        m.recordSeqless(origin, "s1")
        m.recordEvent(origin, "s1", 12, "message_delta", 12, event(12)) // after the taint: not covered
        m.flush()
        val h = loaded()
        assertEquals(listOf("11"), tailSeqs(h))
        assertNull(h.cursor)
        // The next state re-bases and restores the cursor.
        m.recordState(origin, "s1", 20, null, state("after"), emptySet())
        m.flush()
        assertEquals(20L, loaded().cursor)
        assertTrue(loaded().tail.isEmpty())
    }

    @Test
    fun eventsWithoutABaseAreNotPersisted() = runBlocking {
        bind()
        m.recordEvent(origin, "s1", 1, "message_delta", 1, event(1))
        m.flush()
        assertEquals(Hydration.None, m.hydrate(origin, "s1"))
        assertTrue(bind().cursors.isEmpty())
    }

    @Test
    fun aStatelessReplyVerifiesOnlyTheCursorItConfirms() = runBlocking {
        bind()
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.flush()
        val first = loaded().lastVerifiedAt
        fx.now.addAndGet(5_000)
        m.recordVerified(origin, "s1", 9) // not our cursor: proves nothing
        m.flush()
        assertEquals(first, loaded().lastVerifiedAt)
        m.recordVerified(origin, "s1", 10)
        m.flush()
        assertEquals(fx.now.get(), loaded().lastVerifiedAt)
        assertEquals(10L, loaded().cursor)
    }

    @Test
    fun aResetMovesTheCursorDownAndReplacesEverything() = runBlocking {
        bind()
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.recordEvent(origin, "s1", 11, "message_delta", 11, event(11))
        m.recordState(origin, "s1", 4, null, state("reset"), emptySet())
        m.flush()
        val h = loaded()
        assertEquals(4L, h.cursor)
        assertEquals(state("reset"), h.base)
        assertTrue(h.tail.isEmpty())
    }

    @Test
    fun turnDetailsAreSplicedMaterialAndANewBaseSupersedesThem() = runBlocking {
        bind()
        m.recordTurnDetails(origin, "s1", mapOf("t0" to (0 to obj("""{"turnId":"t0"}""")))) // no base yet: dropped
        m.recordState(origin, "s1", 10, 2, state(), emptySet())
        m.recordTurnDetails(
            origin,
            "s1",
            mapOf("t0" to (0 to obj("""{"turnId":"t0","blocks":["a"]}""")), "t1" to (1 to obj("""{"turnId":"t1","blocks":["b"]}"""))),
        )
        m.flush()
        assertEquals(setOf("t0", "t1"), loaded().details.keys)
        // A new base that keeps t0 trimmed (the caller says which details stay useful).
        m.recordState(origin, "s1", 12, 1, state("next"), keepTurnIds = setOf("t0"))
        m.flush()
        assertEquals(setOf("t0"), loaded().details.keys)
        assertEquals(obj("""{"turnId":"t0","blocks":["a"]}"""), loaded().details["t0"])
    }

    @Test
    fun aFullListMarksMissingRowsGoneAndKeepsLastOpened() = runBlocking {
        bind()
        fun row(id: String, pinned: Boolean = false) = SessionRowInput(id, """{"id":"$id"}""", 1, null, pinned, false)
        m.recordSessions(origin, listOf(row("a"), row("b", pinned = true)), full = true)
        m.recordOpened(origin, "a")
        m.recordSessions(origin, listOf(row("b", pinned = true), row("c")), full = true)
        m.flush()
        val byId = bind().sessions.associateBy { it.sessionId }
        assertEquals(setOf("a", "b", "c"), byId.keys)
        assertTrue(byId.getValue("a").goneFromServer)
        assertEquals(fx.now.get(), byId.getValue("a").lastOpenedAt)
        assertFalse(byId.getValue("b").goneFromServer)
        assertTrue(byId.getValue("b").pinned)
        assertEquals("""{"id":"c"}""", byId.getValue("c").json)
        // An upsert (created / session-update) never marks anything gone, and keeps last-opened.
        m.recordOpened(origin, "b")
        m.recordSessions(origin, listOf(row("b")), full = false)
        m.flush()
        val again = bind().sessions.associateBy { it.sessionId }
        assertFalse(again.getValue("c").goneFromServer)
        assertEquals(fx.now.get(), again.getValue("b").lastOpenedAt)
        assertFalse(again.getValue("b").pinned)
    }

    @Test
    fun aLocalCheckpointReplacesTheBaseOnlyWhenItCoversExactlyTheCursor() = runBlocking {
        bind()
        m.recordState(origin, "s1", 10, 5, state(), emptySet())
        m.recordEvent(origin, "s1", 11, "message_delta", 11, event(11))
        m.recordEvent(origin, "s1", 12, "message_delta", 12, event(12))
        m.checkpoint(origin, "s1", 11, state("stale")) // cursor is 12: rejected
        m.flush()
        assertEquals(state(), loaded().base)
        m.checkpoint(origin, "s1", 12, state("folded"))
        m.recordEvent(origin, "s1", 13, "message_delta", 13, event(13))
        m.flush()
        val h = loaded()
        assertEquals(state("folded"), h.base)
        assertEquals(SessionBaseEntity.ORIGIN_LOCAL, h.origin)
        assertEquals(12L, h.throughSeq)
        assertEquals(5, h.trimmedBefore)
        assertEquals(listOf("13"), tailSeqs(h))
        assertEquals(13L, h.cursor)
    }

    @Test
    fun dropForgetsBaseTailDetailsAndCursorButKeepsTheRow() = runBlocking {
        bind()
        m.recordSessions(origin, listOf(SessionRowInput("s1", """{"id":"s1"}""", 1, null, false, false)), full = false)
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.recordEvent(origin, "s1", 11, "message_delta", 11, event(11))
        m.dropSession(origin, "s1")
        m.flush()
        assertEquals(Hydration.None, m.hydrate(origin, "s1"))
        val index = bind()
        assertTrue(index.cursors.isEmpty())
        assertEquals(listOf("s1"), index.sessions.map { it.sessionId })
    }

    @Test
    fun writesForAnotherOriginNeverLand() = runBlocking {
        bind()
        m.recordState("https://other.example:443", "s1", 10, null, state(), emptySet())
        m.flush()
        assertEquals(Hydration.None, m.hydrate(origin, "s1"))
        assertEquals(Hydration.None, m.hydrate("https://other.example:443", "s1"))
    }

    @Test
    fun bindingAnotherOriginDeletesTheFirstOnesDb() = runBlocking {
        bind()
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.flush()
        assertTrue(fx.dbFile(origin).isFile)
        val other = "https://other.example:443"
        assertTrue(m.bind(other)!!.sessions.isEmpty())
        assertFalse(fx.dbFile(origin).exists())
        assertTrue(fx.dbFile(other).isFile)
        // The file name never names the server.
        assertFalse(fx.dbFile(other).name.contains("example"))
    }

    @Test
    fun theCommittedCursorNeverClaimsMoreThanTheDbHolds() = runBlocking {
        // Process death with a batch still in its window: whatever survives, cursor == coverage.
        fx.close()
        fx = MirrorFixture(batchWindowMs = 60_000)
        bind()
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.recordEvent(origin, "s1", 11, "message_delta", 11, event(11))
        m.flush()
        m.recordEvent(origin, "s1", 12, "message_delta", 12, event(12))
        m.recordEvent(origin, "s1", 13, "message_delta", 13, event(13))
        fx.restart() // 12 and 13 were never committed
        m.bind(origin)
        val h = loaded()
        assertEquals(11L, h.cursor)
        assertEquals(listOf("11"), tailSeqs(h))
    }

    @Test
    fun aReducerUpgradeClearsOnlyLocalBaseCursors() = runBlocking {
        bind()
        m.recordState(origin, "server", 10, null, state(), emptySet())
        m.recordState(origin, "local", 10, null, state(), emptySet())
        m.recordEvent(origin, "local", 11, "message_delta", 11, event(11))
        m.checkpoint(origin, "local", 11, state("folded"))
        m.flush()
        fx.restart(version = "next-reducer")
        val index = m.bind(origin)!!
        assertEquals(mapOf("server" to 10L), index.cursors)
        // Still displayable offline.
        assertEquals(state("folded"), loaded("local").base)
        assertNull(loaded("local").cursor)
    }

    @Test
    fun rotationAfterTheWriteBudgetStartsOverUnderANewKey() = runBlocking {
        fx.close()
        fx = MirrorFixture(rotateAfterWrites = 3)
        bind()
        val keyBefore = (fx.keyStore().load() as MirrorKeyStore.Loaded.Present).key.id
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.recordEvent(origin, "s1", 11, "message_delta", 11, event(11))
        m.flush()
        assertEquals(11L, loaded().cursor)
        m.recordEvent(origin, "s1", 12, "message_delta", 12, event(12)) // the 3rd blob under this key
        m.flush()
        val keyAfter = (fx.keyStore().load() as MirrorKeyStore.Loaded.Present).key.id
        assertNotEquals(keyBefore, keyAfter)
        assertEquals(Hydration.None, m.hydrate(origin, "s1"))
        // Still bound: the mirror rebuilds from the next server state.
        m.recordState(origin, "s1", 20, null, state("fresh"), emptySet())
        m.flush()
        assertEquals(state("fresh"), loaded().base)
        assertEquals(0, fx.kekKeys.destroyed) // rotation keeps the KEK
    }

    @Test
    fun clearAndRotateEmptiesTheMirrorUnderANewKey() = runBlocking {
        bind()
        val keyBefore = (fx.keyStore().load() as MirrorKeyStore.Loaded.Present).key.id
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.flush()
        m.clearAndRotate()
        assertEquals(Hydration.None, m.hydrate(origin, "s1"))
        assertNotEquals(keyBefore, (fx.keyStore().load() as MirrorKeyStore.Loaded.Present).key.id)
    }

    @Test
    fun wipeDeletesEveryMirrorFileAndDestroysBothKeys() = runBlocking {
        bind()
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.flush()
        m.wipe().await()
        assertFalse(fx.dbFile(origin).exists())
        assertFalse(fx.keyFile.exists())
        // Destroyed on the calling thread, and again by the writer (a bind in flight may have
        // minted a key after the first).
        assertTrue(fx.kekKeys.destroyed >= 1)
        assertTrue(fx.factory.existing().isEmpty())
        // Writes after a wipe land nowhere.
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.flush()
        assertFalse(fx.dbFile(origin).exists())
    }

    @Test
    fun keystoreUnavailableMeansNoMirrorThisProcessAndNothingDeleted() = runBlocking {
        bind()
        m.recordState(origin, "s1", 10, null, state(), emptySet())
        m.flush()
        fx.restart()
        fx.kekKeys.failure = java.security.KeyStoreException("busy")
        assertNull(m.bind(origin))
        assertTrue(fx.dbFile(origin).isFile)
        fx.kekKeys.failure = null
        m.bind(origin)
        assertEquals(state(), loaded().base)
    }

    @Test
    fun logsNeverCarryContent() = runBlocking {
        bind()
        m.recordState(origin, "s1", 10, null, state("SECRET-MARKER"), emptySet())
        m.flush()
        fx.keyFile.delete() // key loss on the next bind
        fx.restart()
        m.bind(origin)
        assertEquals(Hydration.None, m.hydrate(origin, "s1"))
        for (line in fx.logs) {
            assertFalse(line, line.contains("SECRET") || line.contains("s1") || line.contains("example"))
        }
    }

    @Suppress("unused")
    private fun JsObj.text() = JsCodec.canonical(this)
}
