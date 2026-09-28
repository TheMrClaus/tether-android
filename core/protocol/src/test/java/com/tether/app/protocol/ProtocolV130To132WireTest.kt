package com.tether.app.protocol

import com.tether.app.protocol.model.OverviewFilters
import com.tether.app.protocol.model.SessionView
import com.tether.app.protocol.model.TurnView
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-koy: every wire change of tether v130-v132 (lib/protocol.ts @ 79c3d37), decoded with the
 * T1.1 tolerance rules — new fields optional, unknown fields ignored.
 */
class ProtocolV130To132WireTest {

    private fun session(extra: String, id: String = "sess-1") =
        """{"id":"$id","provider":"claude","name":"n","cwd":"/w","status":"idle","startedAt":1,"updatedAt":2$extra}"""

    private fun ready(vararg sessions: String) = ServerMessage.parse(
        """{"type":"ready","protocolVersion":132,"nativeProtocolFloor":129,"sessions":[${sessions.joinToString(",")}],
           "providers":[],"workspaceRoot":null}""",
    ) as ServerMessage.Ready

    // ---- v130 A: AgentSession.lastSeq ------------------------------------------------------

    @Test
    fun lastSeqIsDecodedOnReadyCreatedAndSessionRows() {
        val r = ready(session(""","lastSeq":424"""), session("", id = "sess-2"))
        assertEquals(132, r.protocolVersion)
        assertEquals(129, r.nativeProtocolFloor)
        assertEquals(listOf(424L, null), r.sessions.map { it.lastSeq })

        val created = ServerMessage.parse("""{"type":"created","session":${session(""","lastSeq":0""")}}""") as ServerMessage.Created
        assertEquals(0L, created.session.lastSeq)
        val update = ServerMessage.parse("""{"type":"session","session":${session(""","lastSeq":7,"futureField":{"x":1}""")}}""")
        assertEquals(7L, (update as ServerMessage.SessionUpdate).session.lastSeq)
    }

    // ---- v130 C + v131: projection fields, through the JsValue views --------------------------

    @Test
    fun snapshotStateCarriesRemovedQueueIdsAndPendingCreatedAt() {
        val frame = ServerMessage.parse(
            """{"type":"snapshot","sessionId":"sess-1","throughSeq":9,"state":{
                 "tetherSessionId":"sess-1","provider":"claude","cwd":"/w","status":"waiting","activeTurnId":"t1",
                 "turnOrder":["t1"],"queuedMessages":[],"removedQueueIds":["q-2","q-1",5],
                 "turnsById":{"t1":{"turnId":"t1","status":"running","blocks":[],"blocksById":{},
                   "pendingApprovals":{"r1":{"requestId":"r1","toolId":"u1","name":"Bash","input":{},"createdAt":1790000005000}},
                   "pendingQuestions":{"r2":{"requestId":"r2","toolId":"u2","questions":[]}}}}}}""",
        ) as ServerMessage.Snapshot
        val view = SessionView(frame.state!!)
        assertEquals(listOf("q-2", "q-1"), view.removedQueueIds)
        val turn = view.activeTurn!!
        assertEquals(1_790_000_005_000.0, turn.pendingApprovals.single().createdAt)
        assertNull("a pre-v131 / unstamped question has no createdAt", turn.pendingQuestions.single().createdAt)
        // The legacy typed adapter still fits the v132 state (it ignores the new keys).
        assertTrue(frame.projection != null)
    }

    @Test
    fun aPreV130StateHasNoRemovedQueueIds() {
        val state = JsCodec.fromJson(TetherJson.parseToJsonElement("""{"queuedMessages":[]}""").jsonObject) as JsObj
        assertNull(SessionView(state).removedQueueIds)
        val odd = JsCodec.fromJson(TetherJson.parseToJsonElement("""{"removedQueueIds":"q-1"}""").jsonObject) as JsObj
        assertNull(SessionView(odd).removedQueueIds)
        assertEquals(emptyList<Any>(), TurnView(JsObj.EMPTY).pendingApprovals)
    }

    // ---- v131/v132: opt-in Overview feed ------------------------------------------------------

    @Test
    fun overviewSnapshotDecodesEveryFieldIncludingTheV132Workspace() {
        val s = ServerMessage.parse(ServerFixtures.OVERVIEW_SNAPSHOT) as ServerMessage.OverviewSnapshot
        assertEquals("feed-1", s.feedId)
        assertEquals(7L, s.cursor)
        assertEquals(1_790_000_000_000L, s.activitySince)
        assertEquals(listOf("running", "waiting"), s.filters.statuses)
        assertEquals(24, s.pageSize)
        assertEquals(1, s.counts.waiting)
        assertEquals("project", s.facets.workspaces.single().label)
        val card = s.cards.single()
        assertEquals("waiting", card.status)
        assertEquals("project", card.workspace.label)
        assertEquals(2, card.progress!!.done)
        assertEquals("approval", card.attention!!.kind)
        assertEquals(1_790_000_005_000L, card.pending.single().createdAt)
        assertEquals(1, card.spawnedRunsActive)
        assertEquals(1, s.pending.total)
        // v132: `workspace` on a Recent activity row; absent on a pre-v132 row.
        assertEquals(listOf("project", null), s.activity.map { it.workspace })
        assertEquals("node-1:sess-1:42:request", s.activity.first().id)
    }

    @Test
    fun overviewDeltaDecodesTheCursorStep() {
        val d = ServerMessage.parse(ServerFixtures.OVERVIEW_DELTA) as ServerMessage.OverviewDelta
        assertEquals(8L, d.cursor)
        assertEquals(7L, d.prevCursor)
        assertEquals(listOf("sess-9"), d.removals)
        assertEquals("running", d.upserts.single().status)
        assertEquals("project", d.activity.single().workspace)
    }

    @Test
    fun overviewFramesAreTolerant() {
        // The step identity is required: without it the frame is inert, never a crash.
        val noFeed = ServerMessage.parse("""{"type":"overview-delta","cursor":2,"prevCursor":1}""")
        assertTrue(noFeed is ServerMessage.Unknown && noFeed.reason!!.contains("feedId"))
        val noPrev = ServerMessage.parse("""{"type":"overview-delta","feedId":"f","cursor":2}""")
        assertTrue(noPrev is ServerMessage.Unknown && noPrev.reason!!.contains("prevCursor"))
        // Everything else defaults; a malformed card or activity row is dropped, unknown keys ignored.
        val s = ServerMessage.parse(
            """{"type":"overview-snapshot","feedId":"f","cursor":1,"future":true,"counts":"bad",
               "cards":[{"sessionId":"a","status":"ready","newThing":[1]},{"title":"no id"},7],
               "activity":[{"id":"x","workspace":"w"},{"id":5}]}""",
        ) as ServerMessage.OverviewSnapshot
        assertEquals(listOf("a"), s.cards.map { it.sessionId })
        assertEquals(listOf("x"), s.activity.map { it.id })
        assertEquals(0, s.counts.total)
        assertEquals(emptyList<String>(), s.filters.workspaces)
    }

    @Test
    fun overviewSubscribeEncodesOnlyWhatIsSet() {
        assertEquals("""{"type":"overview-unsubscribe"}""", ClientMessage.OverviewUnsubscribe.encode())
        assertEquals("""{"type":"overview-subscribe"}""", ClientMessage.OverviewSubscribe().encode())
        assertEquals(
            """{"type":"overview-subscribe","filters":{"statuses":["waiting"]},"page":0,"pageSize":24}""",
            ClientMessage.OverviewSubscribe(OverviewFilters(statuses = listOf("waiting")), page = 0, pageSize = 24).encode(),
        )
        val back = ClientMessage.decode(TetherJson.parseToJsonElement("""{"type":"overview-subscribe","filters":{}}""").jsonObject)
        assertEquals(ClientMessage.OverviewSubscribe(OverviewFilters()), back.getOrThrow())
    }
}
