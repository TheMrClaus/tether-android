package com.tether.app.protocol

import com.tether.app.protocol.model.QueuedOrigin
import com.tether.app.protocol.model.SessionView
import com.tether.app.protocol.model.WorktreeScriptView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-ylh: every wire addition of tether v133-v135 (lib/protocol.ts at protocol 135), decoded with
 * the T1.1 tolerance rules: new fields optional, absent = default, a malformed value degrades to
 * null / the documented fallback and never drops a session row, a queue item or the ready frame.
 * Frames are shaped after the web's own tests (tests/queued-message.test.mjs,
 * tests/worktree-services*.test.mjs, tests/integration/session-list-creator.test.mjs) and
 * server.mjs's ready. The vendored wire corpus is still 79c3d37 (v132): re-sync is T15.8.
 */
class ProtocolV133To135WireTest {

    private fun session(extra: String, id: String = "sess-1") =
        """{"id":"$id","provider":"claude","name":"n","cwd":"/w","status":"idle","startedAt":1,"updatedAt":2$extra}"""

    private fun ready(extra: String, vararg sessions: String) = ServerMessage.parse(
        """{"type":"ready","protocolVersion":135,"nativeProtocolFloor":129,"sessions":[${sessions.joinToString(",")}],
           $extra"providers":[],"workspaceRoot":"/w"}""",
    ) as ServerMessage.Ready

    // ---- v135: AgentSession.createdVia --------------------------------------------------------

    @Test
    fun createdViaIsDecodedOnReadyCreatedAndSessionRows() {
        val r = ready(
            "",
            session(""","createdVia":"console""""),
            session(""","createdVia":null""", id = "sess-2"),
            session("", id = "sess-3"),
            session(""","createdVia":"api-requested"""", id = "sess-4"),
        )
        assertEquals(listOf("console", null, null, "api-requested"), r.sessions.map { it.createdViaStamp })

        val created = ServerMessage.parse("""{"type":"created","session":${session(""","createdVia":"api-agent"""")}}""") as ServerMessage.Created
        assertEquals("api-agent", created.session.createdViaStamp)
        val update = ServerMessage.parse("""{"type":"session","session":${session(""","createdVia":"delegate","parentSessionId":"p"""")}}""")
        assertEquals("delegate", (update as ServerMessage.SessionUpdate).session.createdViaStamp)
    }

    @Test
    fun aMalformedOrUnknownCreatedViaNeverDropsTheRow() {
        val huge = "x".repeat(200_000)
        val r = ready(
            "",
            session(""","createdVia":42"""),
            session(""","createdVia":{"by":"agent"}""", id = "sess-2"),
            session(""","createdVia":["console"]""", id = "sess-3"),
            session(""","createdVia":"a-future-stamp"""", id = "sess-4"),
            session(""","createdVia":"$huge"""", id = "sess-5"),
        )
        assertEquals(listOf("sess-1", "sess-2", "sess-3", "sess-4", "sess-5"), r.sessions.map { it.id })
        assertEquals(listOf(null, null, null, "a-future-stamp", huge), r.sessions.map { it.createdViaStamp })
    }

    @Test
    fun createdViaSurvivesTheMirrorRowRoundTrip() {
        val row = (ServerMessage.parse("""{"type":"created","session":${session(""","createdVia":"handoff"""")}}""") as ServerMessage.Created).session
        val json = TetherJson.encodeToString(com.tether.app.protocol.model.AgentSession.serializer(), row)
        assertEquals("handoff", TetherJson.decodeFromString(com.tether.app.protocol.model.AgentSession.serializer(), json).createdViaStamp)
        val bare = TetherJson.encodeToString(com.tether.app.protocol.model.AgentSession.serializer(), row.copy(createdVia = null))
        assertFalse("an absent stamp stays absent on encode", bare.contains("createdVia"))
    }

    // ---- v135: ready.hiddenAgentSessionCount ---------------------------------------------------

    @Test
    fun hiddenAgentSessionCountIsDecodedAndAbsentIsNull() {
        assertEquals(3, ready(""""hiddenAgentSessionCount":3,""", session("")).hiddenAgentSessionCount)
        assertEquals(0, ready(""""hiddenAgentSessionCount":0,""").hiddenAgentSessionCount)
        val old = ready("", session(""))
        assertNull("a pre-v135 server sends no count", old.hiddenAgentSessionCount)
        assertEquals(1, old.sessions.size)
    }

    @Test
    fun aMalformedHiddenCountNeverDropsTheReadyFrame() {
        for (bad in listOf("\"3\"", "-1", "null", "true", "{}", "[1]")) {
            val r = ready(""""hiddenAgentSessionCount":$bad,""", session(""))
            assertNull("count $bad", r.hiddenAgentSessionCount)
            assertEquals("count $bad", listOf("sess-1"), r.sessions.map { it.id })
            assertEquals(135, r.protocolVersion)
        }
        assertEquals(2, ready(""""hiddenAgentSessionCount":2.7,""").hiddenAgentSessionCount)
        assertEquals(Int.MAX_VALUE, ready(""""hiddenAgentSessionCount":1e300,""").hiddenAgentSessionCount)
    }

    // ---- v133: QueuedMessage.origin / noticeKind ------------------------------------------------

    private fun snapshotWithQueue(queue: String) = ServerMessage.parse(
        """{"type":"snapshot","sessionId":"sess-1","throughSeq":9,"state":{
             "tetherSessionId":"sess-1","provider":"claude","cwd":"/w","status":"running","activeTurnId":null,
             "turnOrder":[],"turnsById":{},"queuedMessages":$queue,"removedQueueIds":[]}}""",
    ) as ServerMessage.Snapshot

    /** tests/queued-message.test.mjs: the folded queue after a legacy, a user and a system add. */
    @Test
    fun queueOriginAndNoticeKindAreReadFromTheWebShapedQueue() {
        val frame = snapshotWithQueue(
            """[{"queueId":"legacy","text":"old draft"},
                {"queueId":"user","text":"new draft","origin":"user"},
                {"queueId":"run","text":"automated","origin":"system","noticeKind":"spawn"}]""",
        )
        val views = SessionView(frame.state!!).queuedMessages
        assertEquals(listOf("user", "user", "system"), views.map { it.origin })
        assertEquals(listOf(null, null, "spawn"), views.map { it.noticeKind })
        assertEquals(listOf("legacy", "user"), views.filter { it.isOperatorMessage }.map { it.queueId })

        val typed = frame.projection!!.queuedMessages
        assertEquals(listOf("legacy", "user", "run"), typed.map { it.queueId })
        assertEquals(listOf("user", "user", "system"), typed.map { it.originKind })
        assertEquals(listOf(null, null, "spawn"), typed.map { it.noticeKindValue })
        assertEquals(listOf(true, true, false), typed.map { it.isOperatorMessage })
    }

    @Test
    fun aMalformedQueueOriginNeverDropsTheQueueItem() {
        val frame = snapshotWithQueue(
            """[{"queueId":"a","text":"t","origin":"agent","noticeKind":"a-future-kind"},
                {"queueId":"b","text":"t","origin":7,"noticeKind":9},
                {"queueId":"c","text":"t","origin":{"x":1},"noticeKind":["spawn"]},
                {"queueId":"d","text":"t","origin":"system","noticeKind":"command"}]""",
        )
        val views = SessionView(frame.state!!).queuedMessages
        assertEquals(listOf("a", "b", "c", "d"), views.map { it.queueId })
        // lib/queued-message.mjs queuedMessageOrigin: an unknown origin is null — not the operator's.
        assertEquals(listOf(null, null, null, QueuedOrigin.SYSTEM), views.map { it.origin })
        assertEquals(listOf("a-future-kind", null, null, "command"), views.map { it.noticeKind })
        assertTrue(views.none { it.isOperatorMessage })

        val typed = frame.projection!!.queuedMessages
        assertEquals("a wrongly typed origin must not empty the typed queue", listOf("a", "b", "c", "d"), typed.map { it.queueId })
        assertEquals(listOf(null, null, null, "system"), typed.map { it.originKind })
        assertEquals(listOf("a-future-kind", null, null, "command"), typed.map { it.noticeKindValue })
    }

    /**
     * T15.6 (ta-ylh leftover): lib/queued-message.mjs queuedMessageOrigin(null) is null — only
     * `undefined` replays as the operator's. The typed path must tell an explicit `"origin":null`
     * from an absent key the way the view (and the web) does, on every decode path and a re-encode.
     */
    @Test
    fun anExplicitNullOriginIsNotTheOperatorsButAnAbsentOneIs() {
        val queue = """[{"queueId":"absent","text":"t"},{"queueId":"nulled","text":"t","origin":null,"noticeKind":null}]"""
        val frame = snapshotWithQueue(queue)
        val views = SessionView(frame.state!!).queuedMessages
        assertEquals(listOf(QueuedOrigin.USER, null), views.map { it.origin })
        assertEquals(listOf(true, false), views.map { it.isOperatorMessage })

        val typed = frame.projection!!.queuedMessages
        assertEquals(listOf("absent", "nulled"), typed.map { it.queueId })
        assertEquals(listOf(QueuedOrigin.USER, null), typed.map { it.originKind })
        assertEquals(listOf(true, false), typed.map { it.isOperatorMessage })
        assertEquals(listOf(null, null), typed.map { it.noticeKindValue })

        val serializer = kotlinx.serialization.builtins.ListSerializer(com.tether.app.protocol.model.QueuedMessage.serializer())
        val fromTree = TetherJson.decodeFromJsonElement(serializer, TetherJson.parseToJsonElement(queue))
        assertEquals(listOf(true, false), fromTree.map { it.isOperatorMessage })
        val again = TetherJson.decodeFromString(serializer, TetherJson.encodeToString(serializer, typed))
        assertEquals("the null/absent split survives a re-encode", listOf(true, false), again.map { it.isOperatorMessage })
        assertFalse(
            "an absent origin stays absent on encode",
            TetherJson.encodeToString(com.tether.app.protocol.model.QueuedMessage.serializer(), typed[0]).contains("origin"),
        )
    }

    // ---- v134: WorktreeScript.proxyUnavailable + nullable proxy links ---------------------------

    private fun scripts(vararg entries: String) = ServerMessage.parse(
        """{"type":"worktree-scripts","snapshot":{"sessionId":"s1","worktreePath":"/w","branch":"b",
             "scripts":[${entries.joinToString(",")}],"setupStatus":"ok","setupLog":[],"configWarnings":[]}}""",
    ) as ServerMessage.WorktreeScripts

    private fun service(extra: String) =
        """{"name":"web","type":"service","command":"npm run dev","status":"running","port":4100,"exitCode":null,
            "startedAt":1,"endedAt":null,"error":null$extra}"""

    @Test
    fun proxyFieldsAreDecodedFromWebShapedScripts() {
        val frame = scripts(
            // tests/worktree-services.test.mjs: a loopback console gets its own-origin link and the path.
            service(""","proxyHost":"web--tidy-fox--src.localhost","proxyUrl":"http://web--tidy-fox--src.localhost:4173",
                "proxyPath":"/services/s1/web/","proxyAuthUrl":"/api/worktree/open?session=s1&script=web","proxyUnavailable":null"""),
            // tests/worktree-services-card.test.mjs: no usable origin for this viewer.
            service(""","proxyHost":null,"proxyUrl":null,"proxyPath":null,"proxyAuthUrl":null,"proxyUnavailable":"not-configured""""),
            service(""","proxyHost":null,"proxyUrl":null,"proxyPath":null,"proxyAuthUrl":null,"proxyUnavailable":"label-too-long""""),
            service(""","proxyHost":null,"proxyUrl":null,"proxyPath":null,"proxyAuthUrl":null,"proxyUnavailable":"label-invalid""""),
        )
        val views = WorktreeScriptView.of(frame.snapshot)
        assertEquals(4, views.size)
        val linked = views[0]
        assertEquals("web", linked.name)
        assertEquals("service", linked.type)
        assertEquals("running", linked.status)
        assertEquals("http://web--tidy-fox--src.localhost:4173", linked.proxyUrl)
        assertEquals("/api/worktree/open?session=s1&script=web", linked.proxyAuthUrl)
        assertEquals("/services/s1/web/", linked.proxyPath)
        assertNull(linked.proxyUnavailable)
        assertEquals(listOf(null, "not-configured", "label-too-long", "label-invalid"), views.map { it.proxyUnavailable })
        assertEquals(listOf(null, null, null), views.drop(1).map { it.proxyUrl })
    }

    @Test
    fun anOlderServerOrAMalformedReasonNeverDropsTheScript() {
        val frame = scripts(
            // pre-v134: no proxyUnavailable, proxyAuthUrl absent.
            service(""","proxyHost":null,"proxyUrl":null,"proxyPath":null"""),
            // unknown reason, wrong types: lib/protocol.ts — treat like "not-configured".
            service(""","proxyUrl":null,"proxyAuthUrl":null,"proxyUnavailable":"future-reason""""),
            service(""","proxyUrl":7,"proxyAuthUrl":{"x":1},"proxyHost":[],"proxyUnavailable":3"""),
            """"not-an-object"""",
            """{"name":"lint","type":"script","command":"x","status":"idle"}""",
        )
        val views = WorktreeScriptView.of(frame.snapshot)
        assertEquals(4, views.size)
        assertEquals(listOf(null, "not-configured", "not-configured", null), views.map { it.proxyUnavailable })
        assertNull(views[2].proxyUrl)
        assertNull(views[2].proxyAuthUrl)
        assertNull(views[2].proxyHost)
        assertEquals("lint", views[3].name)
        assertEquals(emptyList<WorktreeScriptView>(), WorktreeScriptView.of(kotlinx.serialization.json.JsonObject(emptyMap())))
    }
}
