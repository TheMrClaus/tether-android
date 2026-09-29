package com.tether.app.client.sync

import com.tether.app.client.ConnectionHarness
import com.tether.app.client.ConnectionState
import com.tether.app.client.Freshness
import com.tether.app.client.SessionSync
import com.tether.app.client.snapshotFrame
import com.tether.app.client.turnStartedEvent
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T13.2 (SYNC_DESIGN §4.1, §11 FreshnessTest): the freshness state machine over connection,
 * attach and verify. First as the pure rule (every combination of inputs), then driven through a
 * real client over a socket: attach -> CatchingUp -> snapshot -> Live -> gap -> CatchingUp ->
 * snapshot -> Live -> link lost -> Saved (age from the last frame the link delivered).
 */
class FreshnessTest {

    private val tree = JsCodec.parse("""{"tetherSessionId":"s1","turnOrder":[],"turnsById":{}}""") as JsObj

    private fun derive(inputs: FreshnessRules.Inputs, id: String = "s1"): SessionSync? = FreshnessRules.derive(inputs).get(id)

    // ------------------------------------------------------------------
    // The pure rule
    // ------------------------------------------------------------------

    @Test
    fun liveNeedsAConnectionAConfirmationOnItAndACopy() {
        val live = FreshnessRules.Inputs(connected = true, trees = mapOf("s1" to tree), attached = setOf("s1"), live = setOf("s1"))
        assertEquals(Freshness.Live, derive(live)!!.freshness)
        // A confirmation from a socket that is gone never counts (the live set outlived the link).
        assertEquals(Freshness.Saved, derive(live.copy(connected = false))!!.freshness)
        // A stateless reply confirmed the copy, but it is still being read from the mirror.
        assertEquals(Freshness.CatchingUp, derive(live.copy(trees = emptyMap()))!!.freshness)
    }

    @Test
    fun attachedButNotConfirmedIsCatchingUp() {
        val inputs = FreshnessRules.Inputs(connected = true, trees = mapOf("s1" to tree), attached = setOf("s1"))
        assertEquals(Freshness.CatchingUp, derive(inputs)!!.freshness)
        // Nothing to show yet, but the attach is out: catching up, not "not downloaded".
        assertEquals(Freshness.CatchingUp, derive(inputs.copy(trees = emptyMap()))!!.freshness)
    }

    @Test
    fun aCopyThatIsNotConfirmedOnThisConnectionIsSaved() {
        // Offline, a copy in memory.
        assertEquals(Freshness.Saved, derive(FreshnessRules.Inputs(connected = false, trees = mapOf("s1" to tree)))!!.freshness)
        // Offline, a copy in the mirror only (not read yet).
        assertEquals(Freshness.Saved, derive(FreshnessRules.Inputs(connected = false, saved = mapOf("s1" to 5L)))!!.freshness)
        // Connected, but not re-attached (the capped ready re-attach, §3.1 rule 5).
        assertEquals(Freshness.Saved, derive(FreshnessRules.Inputs(connected = true, saved = mapOf("s1" to 5L), listed = listOf("s1")))!!.freshness)
    }

    @Test
    fun aListedSessionWithNoCopyIsNotDownloaded() {
        assertEquals(Freshness.NotDownloaded, derive(FreshnessRules.Inputs(connected = false, listed = listOf("s1")))!!.freshness)
        assertEquals(Freshness.NotDownloaded, derive(FreshnessRules.Inputs(connected = true, listed = listOf("s1")))!!.freshness)
        // Attached while offline (only subscribed): still nothing on the device.
        assertEquals(Freshness.NotDownloaded, derive(FreshnessRules.Inputs(connected = false, listed = listOf("s1"), attached = setOf("s1")))!!.freshness)
    }

    @Test
    fun onlyKnownSessionsHaveAnEntry() {
        val out = FreshnessRules.derive(FreshnessRules.Inputs(connected = false, attached = setOf("gone"), live = setOf("gone")))
        assertTrue(out.isEmpty())
        val connected = FreshnessRules.derive(FreshnessRules.Inputs(connected = true, attached = setOf("s9")))
        assertEquals(Freshness.CatchingUp, connected.getValue("s9").freshness)
    }

    @Test
    fun thisProcessVerificationWinsOverThePersistedOne() {
        val inputs = FreshnessRules.Inputs(connected = false, saved = mapOf("s1" to 100L), verifiedAt = mapOf("s1" to 900L))
        assertEquals(900L, derive(inputs)!!.lastVerifiedAt)
        assertEquals(100L, derive(inputs.copy(verifiedAt = emptyMap()))!!.lastVerifiedAt)
        assertNull(derive(FreshnessRules.Inputs(connected = false, saved = mapOf("s1" to null)))!!.lastVerifiedAt)
    }

    /** Every combination of the six inputs: Live exactly when connected, confirmed and held; never otherwise. */
    @Test
    fun liveIsNeverClaimedWithoutAllThreeConditions() {
        for (bits in 0 until (1 shl 6)) {
            val connected = bits and 1 != 0
            val live = bits and 2 != 0
            val attached = bits and 4 != 0
            val hasTree = bits and 8 != 0
            val saved = bits and 16 != 0
            val listed = bits and 32 != 0
            val inputs = FreshnessRules.Inputs(
                connected = connected,
                listed = if (listed) listOf("s1") else emptyList(),
                trees = if (hasTree) mapOf("s1" to tree) else emptyMap(),
                saved = if (saved) mapOf("s1" to 1L) else emptyMap(),
                attached = if (attached) setOf("s1") else emptySet(),
                live = if (live) setOf("s1") else emptySet(),
            )
            val got = derive(inputs)?.freshness
            val known = listed || hasTree || saved || (connected && attached)
            val expected = when {
                !known -> null
                connected && live && hasTree -> Freshness.Live
                connected && (attached || live) -> Freshness.CatchingUp
                hasTree || saved -> Freshness.Saved
                listed -> Freshness.NotDownloaded
                else -> null
            }
            assertEquals("bits=$bits", expected, got)
            if (got == Freshness.Live) assertTrue("bits=$bits", connected && live && hasTree)
        }
    }

    @Test
    fun partialMeansTheLeadingTurnsAreStillStubs() {
        val stub = JsCodec.parse(
            """{"turnOrder":["t0","t1"],"turnsById":{"t0":{"blocks":[],"blocksById":{}},"t1":{"blocks":["b"],"blocksById":{"b":{}}}}}""",
        ) as JsObj
        val filled = JsCodec.parse(
            """{"turnOrder":["t0","t1"],"turnsById":{"t0":{"blocks":["a"],"blocksById":{"a":{}}},"t1":{"blocks":["b"],"blocksById":{"b":{}}}}}""",
        ) as JsObj
        assertTrue(FreshnessRules.isPartial(stub, 1))
        assertFalse("turns-detail filled them in", FreshnessRules.isPartial(filled, 1))
        assertFalse("not a bounded snapshot", FreshnessRules.isPartial(stub, null))
        assertFalse(FreshnessRules.isPartial(stub, 0))
        assertTrue(derive(FreshnessRules.Inputs(connected = false, trees = mapOf("s1" to stub), trimmedBefore = mapOf("s1" to 1)))!!.partial)
    }

    // ------------------------------------------------------------------
    // Driven through the client over a socket
    // ------------------------------------------------------------------

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    private fun sync(id: String, predicate: (SessionSync?) -> Boolean): SessionSync? = h.await(h.client.syncStates) { predicate(it[id]) }[id]

    private fun ready(vararg ids: String) =
        """{"type":"ready","protocolVersion":132,"nativeProtocolFloor":129,"providers":[],"workspaceRoot":null,"sessions":[""" +
            ids.joinToString(",") {
                """{"id":"$it","provider":"claude","name":"n-$it","cwd":"/w","status":"active","startedAt":1,"updatedAt":1,"pinned":false,"runtimeArchived":false,"mode":"headless"}"""
            } + "]}"

    private val state = """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","turnOrder":[],"turnsById":{},"activeTurnId":null,"queuedMessages":[]}"""

    @Test
    fun theClientWalksTheStateMachine() {
        h.enqueueConnect()
        h.newClient().start()
        val ws = h.nextSocket()
        h.handshake(ws, ready("s1", "s2"))

        // Listed, nothing on the device, not attached.
        assertEquals(Freshness.NotDownloaded, sync("s2") { it?.freshness == Freshness.NotDownloaded }!!.freshness)

        h.client.attach("s1")
        h.expectFrame("attach")
        assertEquals(Freshness.CatchingUp, sync("s1") { it?.freshness == Freshness.CatchingUp }!!.freshness)

        h.now.set(2_000_000)
        ws.send(snapshotFrame("s1", 3, state))
        val live = sync("s1") { it?.freshness == Freshness.Live }!!
        assertEquals("the confirming snapshot's time", 2_000_000L, live.lastVerifiedAt)
        assertEquals(setOf("s1"), h.client.liveSessions.value)

        // A gap: events are missing until the resync snapshot lands.
        ws.send(turnStartedEvent("s1", "t9", seq = 9))
        assertEquals("the gap resync asks from the cursor", 3L, h.expectFrame("attach")["afterSeq"]!!.toString().toLong())
        assertEquals(Freshness.CatchingUp, sync("s1") { it?.freshness == Freshness.CatchingUp }!!.freshness)
        ws.send(snapshotFrame("s1", 9, state))
        sync("s1") { it?.freshness == Freshness.Live }

        // The last frame the link delivers, then the link drops much later.
        h.now.set(3_000_000)
        ws.send(turnStartedEvent("s1", "t10", seq = 10))
        h.serverBarrier(ws)
        h.now.set(9_000_000)
        ws.close(1000, null)
        h.await(h.client.connection) { it != ConnectionState.Connected }
        val saved = sync("s1") { it?.freshness == Freshness.Saved }!!
        assertTrue(
            "current as of the last delivered frame, never the drop time: ${saved.lastVerifiedAt}",
            saved.lastVerifiedAt!! in 3_000_000L until 9_000_000L,
        )
        assertEquals(Freshness.NotDownloaded, h.client.syncStates.value.getValue("s2").freshness)
        assertTrue(h.client.liveSessions.value.isEmpty())
    }
}
