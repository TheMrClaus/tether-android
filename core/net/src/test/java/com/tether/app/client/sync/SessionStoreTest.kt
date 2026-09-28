package com.tether.app.client.sync

import com.tether.app.mirror.HydratedSession
import com.tether.app.mirror.SessionBaseEntity
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** T13.1 (c): the extracted projection owner's own rules, without a socket. */
class SessionStoreTest {
    private val store = SessionStore()

    private fun obj(json: String) = JsCodec.parse(json) as JsObj

    private val base = obj(
        """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","activeTurnId":null,"queuedMessages":[],
           "turnOrder":["t0","t1"],"turnsById":{"t0":{"turnId":"t0","blocks":[]},"t1":{"turnId":"t1","blocks":[]}}}""",
    )
    private val detail = obj("""{"turnId":"t0","blocks":["x"]}""")

    private fun hydrated(tail: List<JsObj> = emptyList(), details: Map<String, JsObj> = emptyMap()) =
        HydratedSession("s1", base, 3, 1, SessionBaseEntity.ORIGIN_SERVER, details, tail, 3L + tail.size, null)

    @Test
    fun publishAndDropKeepTreeAndTypedViewTogether() {
        store.publish("s1", base, store.adapt("s1", base))
        assertSame(base, store.tree("s1"))
        assertTrue(store.projections.value.containsKey("s1"))
        store.drop("s1")
        assertNull(store.tree("s1"))
        assertFalse(store.projections.value.containsKey("s1"))
    }

    @Test
    fun aHydrationFoldsTheEventsBufferedWhileItRead() {
        assertTrue(store.beginHydration("s1"))
        assertFalse("at most once per binding", store.beginHydration("s1"))
        val event = obj("""{"type":"turn_started","turnId":"t2","seq":4,"ts":4}""")
        assertTrue(store.bufferIfHydrating("s1", event))
        assertFalse(store.bufferIfHydrating("s2", event))
        val rebuilt = MirrorLink.rebuild(hydrated())
        val outcome = store.completeHydration("s1", hydrated(), rebuilt) as SessionStore.HydrationOutcome.Ready
        assertEquals(com.tether.app.protocol.tree.JsStr("t2"), outcome.tree["activeTurnId"])
        // Nothing is buffered any more: the next event folds live.
        assertFalse(store.bufferIfHydrating("s1", event))
    }

    @Test
    fun aStateWinsOverAReadInFlight() {
        store.beginHydration("s1")
        store.rebase("s1", base, null)
        assertEquals(SessionStore.HydrationOutcome.Cancelled, store.completeHydration("s1", hydrated(), base))
    }

    @Test
    fun anUnfoldableCopyFails() {
        store.beginHydration("s1")
        assertEquals(SessionStore.HydrationOutcome.Failed, store.completeHydration("s1", null, null))
    }

    @Test
    fun aRebaseKeepsOnlyTheDetailsOfTurnsTheStateStillTrims() {
        store.addDetails("s1", obj("""{"t0":{"turnId":"t0","blocks":["x"]},"t1":{"turnId":"t1","blocks":["y"]}}"""))
        // trimmedBefore 1: t0 is still trimmed (its detail stays), t1 is held in full.
        val shown = store.rebase("s1", base, 1)
        assertEquals(detail, (shown["turnsById"] as JsObj)["t0"])
        assertEquals(obj("""{"turnId":"t1","blocks":[]}"""), (shown["turnsById"] as JsObj)["t1"])
        // Untrimmed: every detail is superseded, the state is shown as sent.
        assertSame(base, store.rebase("s1", base, null))
        assertSame(base, store.rebase("s1", base, 1))
    }

    @Test
    fun checkpointsAreDueByTailLengthOrAtATurnEnd() {
        store.publish("s1", base, null)
        store.rebase("s1", base, null)
        repeat(3) { store.countTail("s1") }
        assertFalse(store.checkpointDue("s1", "message_delta", base, every = 5, atTurnEnd = 4))
        assertFalse("only the published tree", store.checkpointDue("s1", "turn_end", obj("{}"), every = 5, atTurnEnd = 3))
        assertTrue(store.checkpointDue("s1", "turn_end", base, every = 5, atTurnEnd = 3))
        assertFalse("the count restarts", store.checkpointDue("s1", "turn_end", base, every = 5, atTurnEnd = 3))
        repeat(5) { store.countTail("s1") }
        assertTrue(store.checkpointDue("s1", "message_delta", base, every = 5, atTurnEnd = 3))
    }

    @Test
    fun clearingForgetsViewsAndBookkeeping() {
        store.publish("s1", base, null)
        store.setTrimmedBefore("s1", 1)
        store.beginHydration("s2")
        store.clearViews()
        store.clearMirrorState()
        assertTrue(store.trees.value.isEmpty())
        assertTrue(store.trimmedBefore.value.isEmpty())
        assertTrue("a new binding may read again", store.beginHydration("s2"))
    }
}
