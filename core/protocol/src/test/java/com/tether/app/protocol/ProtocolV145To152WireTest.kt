package com.tether.app.protocol

import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.model.OverviewConsoleItem
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * W48 (ta-d3tj.1): the additive wire paths of tether v145-v152 (web 19c56e25), decode and encode only. The reducer folds
 * (sessionErrors, endedAt, errorAt, held, withdrawn, summary, outputFile) are R1's; the UI is .3-.6. Server refs are the tether lib protocol-schema files at 19c56e25.
 */
class ProtocolV145To152WireTest {
    private val wire = File(System.getProperty("tether.parityCorpusWire") ?: "../../parity-corpus/wire")

    private fun adapt(state: String): Pair<LegacyProjectionAdapter, com.tether.app.protocol.model.SessionProjection?> {
        val adapter = LegacyProjectionAdapter()
        return adapter to adapter.adapt(JsCodec.parse(state) as JsObj)
    }

    private fun state(extra: String) =
        """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","status":"ready","lastTurnOutcome":null,"lastError":"boom","turnOrder":[],"turnsById":{},"queuedMessages":[]$extra}"""

    // ---- sessionErrors (protocol-schema-w4.ts:751-760 sessionErrorProjectionSchema, :809) -----------------------------------

    @Test
    fun sessionErrorsDecodeWithTheCauseRawAndAnUnknownCauseKeepsTheRow() {
        val (adapter, p) = adapt(
            state(""","sessionErrors":[{"seq":4,"ts":1700,"message":"auth","cause":"auth_expired"},{"seq":5,"ts":1800,"message":"x","cause":"a_future_cause"},{"seq":null,"ts":null,"message":"never journaled"},{"seq":6,"ts":1900.5,"message":"fractional"}]"""),
        )
        assertNotNull(p)
        assertEquals(0, adapter.misfits)
        assertEquals(listOf("auth", "x", "never journaled", "fractional"), p!!.sessionErrors.map { it.message })
        assertEquals(listOf("auth_expired", "a_future_cause", null, null), p.sessionErrors.map { it.cause })
        assertNull("a never-journaled event has null seq/ts", p.sessionErrors[2].seq)
        assertNull(p.sessionErrors[2].ts)
        assertEquals(1900L, p.sessionErrors[3].ts)
        assertEquals("lastError is kept", "boom", p.lastError)
    }

    @Test
    fun aMalformedSessionErrorRowIsDroppedAndTheSnapshotAndTheOtherRowsStayIntact() {
        val (adapter, p) = adapt(
            state(""","sessionErrors":[{"seq":1,"ts":10,"message":"first"},{"seq":2,"ts":20,"message":7},"junk",{"seq":"two","ts":30,"message":"bad seq"},{"seq":3,"ts":40,"message":"last"}]"""),
        )
        assertNotNull("a bad row must not blank the session", p)
        assertEquals("no misfit: the bad rows are dropped, not the key", 0, adapter.misfits)
        assertEquals(listOf("first", "last"), p!!.sessionErrors.map { it.message })
        assertEquals("boom", p.lastError)
        val (a2, p2) = adapt(state(""","sessionErrors":{"not":"an array"}"""))
        assertNotNull(p2)
        assertEquals(0, a2.misfits)
        assertEquals(emptyList<Any>(), p2!!.sessionErrors)
    }

    @Test
    fun everyCorpusSnapshotAdaptsWithNoMisfit() {
        var checked = 0
        for (file in wire.listFiles { f -> f.name.endsWith(".jsonl") }!!.sortedBy { it.name }) {
            for (line in file.readLines().filter { it.isNotBlank() }) {
                val frame = TetherJson.parseToJsonElement(line).jsonObject["frame"]?.jsonObject ?: continue
                if (frame["type"]?.jsonPrimitive?.content != "snapshot") continue
                val st = frame["state"] as? JsonObject ?: continue
                val adapter = LegacyProjectionAdapter()
                assertNotNull("${file.name}: the typed projection", adapter.adapt(JsCodec.fromJson(st) as JsObj))
                assertEquals("${file.name} misfits", 0, adapter.misfits)
                checked++
            }
        }
        assertTrue("the corpus has snapshot frames ($checked)", checked > 0)
    }

    // ---- turn endedAt / errorAt (w4:592-596) and withdrawnQueueIds (w4:857) -----------------------------------------------

    @Test
    fun turnEndedAtErrorAtAndWithdrawnQueueIdsDecode() {
        val (_, p) = adapt(
            state(
                ""","withdrawnQueueIds":["q1","q2",5,null]""",
            ).replace(
                """"turnOrder":[],"turnsById":{}""",
                """"turnOrder":["t1","t2"],"turnsById":{"t1":{"turnId":"t1","status":"done","startedAt":100,"endedAt":900,"errorAt":800},"t2":{"turnId":"t2","status":"running","startedAt":1000}}""",
            ),
        )
        assertEquals(listOf("q1", "q2"), p!!.withdrawnQueueIds)
        assertEquals(900L, p.turnsById["t1"]!!.endedAt)
        assertEquals(800L, p.turnsById["t1"]!!.errorAt)
        assertNull("absent until the turn ended", p.turnsById["t2"]!!.endedAt)
        assertNull(p.turnsById["t2"]!!.errorAt)
        assertEquals("absent = empty", emptyList<String>(), adapt(state("")).second!!.withdrawnQueueIds)
    }

    // ---- QueuedMessage held / fromSession* (w4:748, :727-728) ; peer getter (queued-message.ts:22) -----------------------------

    @Test
    fun queuedHeldAndPeerFieldsDecode() {
        val (_, p) = adapt(
            state("").replace(
                """"queuedMessages":[]""",
                """"queuedMessages":[{"queueId":"a","text":"held one","held":true},{"queueId":"b","text":"normal","held":false},{"queueId":"c","text":"hi","origin":"system","noticeKind":"peer","fromSessionId":"s9","fromSessionName":"Reviewer"},{"queueId":"d","text":"plain"}]""",
            ),
        )
        val (a, b, c, d) = p!!.queuedMessages
        assertTrue(a.held)
        assertFalse("held:false reads false", b.held)
        assertFalse(d.held)
        assertTrue(c.isPeer)
        assertEquals("s9", c.fromSessionId)
        assertEquals("Reviewer", c.fromSessionName)
        assertFalse(a.isPeer)
        assertFalse("origin user with a peer kind is not a peer row", ParsedQueue.peer(origin = "user").isPeer)
        // isListedInComposer / operatorQueuedMessages are L4's and unchanged: a peer row is still not listed here.
        assertFalse(c.isListedInComposer)
    }

    private object ParsedQueue {
        fun peer(origin: String) = adaptQueue("""{"queueId":"x","text":"t","origin":"$origin","noticeKind":"peer"}""")
        private fun adaptQueue(row: String) = LegacyProjectionAdapter.adaptOnce(
            JsCodec.parse("""{"tetherSessionId":"s","provider":"claude","cwd":"/w","queuedMessages":[$row]}""") as JsObj,
        )!!.queuedMessages.single()
    }

    // ---- user_message block `from` (w4:464, v149) ----------------------------------------------------------------------

    @Test
    fun aPeerUserMessageBlockCarriesItsSenderAndAMalformedFromKeepsTheBlock() {
        val turn = { from: String ->
            state("").replace(
                """"turnOrder":[],"turnsById":{}""",
                """"turnOrder":["t"],"turnsById":{"t":{"turnId":"t","status":"running","blocks":["b"],"blocksById":{"b":{"blockId":"b","kind":"user_message","text":"hey"$from}}}}""",
            )
        }
        val block = adapt(turn(""","from":{"sessionId":"s9","name":"Reviewer"}""")).second!!.turnsById["t"]!!.blocksById["b"]!!
        assertEquals(com.tether.app.protocol.model.MessageSource("s9", "Reviewer"), block.from)
        val (adapter, p) = adapt(turn(""","from":"nope""""))
        val kept = p!!.turnsById["t"]!!.blocksById["b"]!!
        assertEquals("hey", kept.text)
        assertNull(kept.from)
        assertEquals(1, adapter.misfits)
        assertNull(adapt(turn("")).second!!.turnsById["t"]!!.blocksById["b"]!!.from)
    }

    // ---- toolApproval on AgentSession (w3:33-38, :108), agentMode (w3:112), HistorySession (w3:252) -----------------------------

    private fun session(extra: String) =
        """{"id":"s1","provider":"reasonix","name":"n","cwd":"/w","status":"idle","startedAt":1,"updatedAt":2$extra}"""

    private fun ready(vararg sessions: String) = ServerMessage.parse(
        """{"type":"ready","protocolVersion":152,"nativeProtocolFloor":129,"sessions":[${sessions.joinToString(",")}],"providers":[],"workspaceRoot":"/w"}""",
    ) as ServerMessage.Ready

    @Test
    fun toolApprovalIsKeptRawAndNeverDefaultedToAsk() {
        val r = ready(
            session(""","toolApproval":{"value":"yolo","assumed":false,"options":["ask","auto","yolo"],"requested":"auto"},"agentMode":"plan""""),
            session(""","toolApproval":{"value":"turbo","assumed":true,"options":["ask","turbo"],"requested":null}""").replace("\"s1\"", "\"s2\""),
            session(""","toolApproval":{"value":null,"assumed":true,"options":[],"requested":null}""").replace("\"s1\"", "\"s3\""),
            session("").replace("\"s1\"", "\"s4\""),
            session(""","toolApproval":"garbage","agentMode":7""").replace("\"s1\"", "\"s5\""),
            session(""","toolApproval":{"value":5,"options":"x"}""").replace("\"s1\"", "\"s6\""),
        )
        assertEquals("a malformed state never drops a session row", 6, r.sessions.size)
        val s1 = r.sessions[0]; val s2 = r.sessions[1]; val s3 = r.sessions[2]; val s4 = r.sessions[3]; val s5 = r.sessions[4]; val s6 = r.sessions[5]
        assertEquals(com.tether.app.protocol.model.ToolApprovalState("yolo", false, listOf("ask", "auto", "yolo"), "auto"), s1.toolApprovalState)
        assertEquals("plan", s1.agentModeId)
        assertEquals("an unknown value is kept as sent", "turbo", s2.toolApprovalState!!.value)
        assertTrue(s2.toolApprovalState!!.assumed)
        assertNull("a null value stays null", s3.toolApprovalState!!.value)
        assertNull("an absent state is absent", s4.toolApprovalState)
        assertNull("absent is NOT 'ask'", s4.toolApproval)
        assertNull(s5.toolApprovalState)
        assertNull(s5.agentModeId)
        assertNull("a non-string value is null, never 'ask'", s6.toolApprovalState!!.value)
        assertEquals(emptyList<String>(), s6.toolApprovalState!!.options)
    }

    @Test
    fun historySessionToolApprovalIsRawToo() {
        val h = ServerMessage.parse(
            """{"type":"histories","cwd":"/w","sessions":[{"historyId":"h1","provider":"reasonix","name":"n","cwd":"/w","updatedAt":1,"toolApproval":"yolo"},{"historyId":"h2","provider":"reasonix","name":"n","cwd":"/w","updatedAt":1,"toolApproval":"turbo"},{"historyId":"h3","provider":"claude","name":"n","cwd":"/w","updatedAt":1},{"historyId":"h4","provider":"reasonix","name":"n","cwd":"/w","updatedAt":1,"toolApproval":{"x":1}}]}""",
        ) as ServerMessage.Histories
        assertEquals(listOf("yolo", "turbo", null, null), h.sessions.map { it.toolApprovalValue })
        assertEquals(4, h.sessions.size)
    }

    // ---- session-controls currentMode (w5:1207) ------------------------------------------------------------------------

    @Test
    fun sessionControlsCarriesTheCurrentMode() {
        val base = """{"type":"session-controls","sessionId":"s1","models":[],"commands":[]"""
        assertEquals("plan", (ServerMessage.parse("$base,\"currentMode\":\"plan\"}") as ServerMessage.SessionControls).currentMode)
        assertNull((ServerMessage.parse("$base}") as ServerMessage.SessionControls).currentMode)
    }

    // ---- client frames: set-tool-approval (lu:352), set-mode requestId (lu:345), create / schedule toolApproval ------------------

    @Test
    fun setToolApprovalFrameRoundTripsAndKeepsTheValueVerbatim() {
        val frame = ClientMessage.SetToolApproval("s1", "yolo", "r1").toJsonObject()
        assertEquals(listOf("type", "sessionId", "toolApproval", "requestId"), frame.keys.toList())
        assertEquals("set-tool-approval", frame["type"]!!.jsonPrimitive.content)
        assertEquals(ClientMessage.SetToolApproval("s1", "yolo", "r1"), ClientMessage.decode(frame).getOrThrow())
        assertEquals(listOf("type", "sessionId", "toolApproval"), ClientMessage.SetToolApproval("s1", "ask").toJsonObject().keys.toList())
        // Never normalised: an unknown value goes out as given (the server's validator is the judge).
        assertEquals("turbo", ClientMessage.SetToolApproval("s1", "turbo").toJsonObject()["toolApproval"]!!.jsonPrimitive.content)
        assertEquals("turbo", (ClientMessage.decode(ClientMessage.SetToolApproval("s1", "turbo").toJsonObject()).getOrThrow() as ClientMessage.SetToolApproval).toolApproval)
        assertTrue("set-tool-approval" in ClientMessage.TYPES)
        assertTrue(ClientMessage.decode(JsonObject(mapOf("type" to JsonPrimitive("set-tool-approval"), "sessionId" to JsonPrimitive("s1")))).isFailure)
    }

    @Test
    fun setModeRequestIdDefaultsToAbsent() {
        assertEquals(listOf("type", "sessionId", "permissionMode"), ClientMessage.SetMode("s1", "plan").toJsonObject().keys.toList())
        val withId = ClientMessage.SetMode("s1", "plan", "r1")
        assertEquals("r1", withId.toJsonObject()["requestId"]!!.jsonPrimitive.content)
        assertEquals(withId, ClientMessage.decode(withId.toJsonObject()).getOrThrow())
    }

    @Test
    fun createToolApprovalIsThreeState() {
        val none = ClientMessage.Create("reasonix").toJsonObject()
        assertFalse("never sent until the Approvals UI", none.containsKey("toolApproval"))
        val nulled = ClientMessage.Create("reasonix", toolApproval = OrNull(null)).toJsonObject()
        assertEquals(JsonNull, nulled["toolApproval"])
        val valued = ClientMessage.Create("reasonix", toolApproval = OrNull("auto"))
        assertEquals("auto", valued.toJsonObject()["toolApproval"]!!.jsonPrimitive.content)
        for (c in listOf(ClientMessage.Create("reasonix"), ClientMessage.Create("reasonix", toolApproval = OrNull(null)), valued)) {
            assertEquals(c, ClientMessage.decode(c.toJsonObject()).getOrThrow())
        }
    }

    @Test
    fun scheduledInputToolApprovalIsThreeStateInTheWebsKeyOrder() {
        fun input(approval: OrNull<String>?, extra: JsonObject = JsonObject(emptyMap())) = ScheduledActionInput(
            "n", "p", "/w", "reasonix", null, null, null, "default", null, false, "* * * * *", "UTC", null, toolApproval = approval, extra = extra,
        )
        assertFalse(input(null).toJsonObject().containsKey("toolApproval"))
        assertEquals(JsonNull, input(OrNull(null)).toJsonObject()["toolApproval"])
        val keys = input(OrNull("yolo")).toJsonObject().keys.toList()
        assertEquals(listOf("permissionMode", "toolApproval", "sandboxPolicy"), keys.subList(keys.indexOf("permissionMode"), keys.indexOf("permissionMode") + 3))
        // An unknown stored value survives (the app never rewrites it), and `extra` can never replace it.
        val extra = JsonObject(mapOf("toolApproval" to JsonPrimitive("ask")))
        assertEquals("yolo", input(OrNull("yolo"), extra).toJsonObject()["toolApproval"]!!.jsonPrimitive.content)
        for (a in listOf(null, OrNull(null), OrNull("turbo"))) {
            val frame = ClientMessage.ScheduleCreate(input(a)).toJsonObject()
            assertEquals(ClientMessage.ScheduleCreate(input(a)), ClientMessage.decode(frame).getOrThrow())
        }
    }

    // ---- Overview consoleItems (w2:434-459) ---------------------------------------------------------------------------------

    @Test
    fun consoleItemsKeepKnownKindsRawAndSkipUnknownOrMalformed() {
        val items = """[{"id":"c1","kind":"client_ip_ask","askKind":"confirm","suggestedSource":"cloudflare","label":"Cloudflare","summary":"How do visitors reach Tether?"},""" +
            """{"id":"c2","kind":"client_ip_warning","source":"auto","since":1790000000000,"summary":"Proxy not sending the address"},""" +
            """{"id":"c3","kind":"a_future_kind","summary":"later"},{"id":"c4","summary":"no kind"},"junk",{"id":"c5","kind":"client_ip_ask","summary":9}]"""
        val text = ServerFixtures.OVERVIEW_SNAPSHOT.replace(""""pending":{""", """"pending":{"consoleItems":$items,""")
        val snap = ServerMessage.parse(text) as ServerMessage.OverviewSnapshot
        val got = snap.pending.consoleItems
        assertEquals(listOf("c1", "c2"), got.map { it.id })
        assertEquals(listOf("client_ip_ask", "client_ip_warning"), got.map { it.kind })
        assertEquals(OverviewConsoleItem("c1", "client_ip_ask", "confirm", "cloudflare", "Cloudflare", null, null, "How do visitors reach Tether?"), got[0])
        assertEquals(1790000000000L, got[1].since)
        assertTrue("the panel itself still decodes (total / items)", snap.pending.total >= 0)
        assertEquals(emptyList<OverviewConsoleItem>(), (ServerMessage.parse(ServerFixtures.OVERVIEW_SNAPSHOT) as ServerMessage.OverviewSnapshot).pending.consoleItems)
    }

    // ---- server-settings clientIpStatus: raw passthrough (cl:65-85, :226) ---------------------------------------------------

    @Test
    fun clientIpStatusRidesTheRawSettingsMap() {
        val frame = File(wire, "settings.jsonl").readLines().filter { it.isNotBlank() }
            .map { TetherJson.parseToJsonElement(it).jsonObject }
            .filter { it["dir"]!!.jsonPrimitive.content == "s2c" }.map { it["frame"]!!.jsonObject }
            .first { it["type"]!!.jsonPrimitive.content == "server-settings" }
        val settings = (ServerMessage.parse(frame) as ServerMessage.ServerSettings).settings
        val status = settings["clientIpStatus"]!!.jsonObject
        assertEquals("auto", status["mode"]!!.jsonPrimitive.content)
        assertEquals("choose", status["askKind"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, status["suggestedSource"])
        assertEquals(frame["settings"]!!.jsonObject["clientIpStatus"], status)
        for (key in listOf("clientIpSource", "clientIpHeader", "clientIpSourceConfirmed")) assertTrue(key, settings.containsKey(key))
    }
}
