package com.tether.app.protocol

import com.tether.app.protocol.model.Vocab
import com.tether.app.protocol.reduce.reduce
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {

    // A realistic snapshot frame, hand-built from the spec shapes (protocol-spec §4
    // + reducer-spec §2). Includes fields Android does not model to prove
    // ignoreUnknownKeys tolerance.
    private val snapshotJson = """
    {
      "type": "snapshot",
      "sessionId": "sess-1",
      "throughSeq": 42,
      "state": {
        "tetherSessionId": "sess-1",
        "provider": "claude",
        "cwd": "/home/user/project",
        "nativeSessionId": "native-abc",
        "cliCapabilities": ["cap.one"],
        "cliVersion": "2.1.218",
        "cliInventory": {
          "commands": [{"name": "compact", "description": "Compact"}],
          "tools": ["Bash", "Edit"],
          "mcpServers": [{"name": "linear", "status": "connected"}]
        },
        "mcpHealth": {"linear": {"name": "linear", "status": "ready"}},
        "rateLimit": {"status": "allowed", "limitType": null, "utilization": 0.4, "resetsAt": null},
        "rateLimitResume": null,
        "todo": {"items": [{"content": "a", "activeForm": "", "status": "pending"}], "activeForm": null, "completed": 0, "total": 1},
        "status": "active",
        "lastTurnOutcome": "ok",
        "lastError": null,
        "unattributedPermissionDenials": [{"toolId": "toolu_x", "name": "Bash", "reason": "safety_check", "reasonCode": "subcommandResults"}],
        "providerNotices": [{"noticeId": "n1", "level": "info", "message": "hello"}],
        "notices": [{"kind": "background_interrupted", "outstanding": 1, "seq": 5}],
        "turnOrder": ["turn-1", "turn-2"],
        "turnsById": {
          "turn-1": {
            "turnId": "turn-1",
            "idempotencyKey": "key-1",
            "continuation": false,
            "startedAt": 1000,
            "liveTokens": 99,
            "run": null,
            "runCount": 2,
            "activeMs": 6100,
            "status": "done",
            "outcome": "ok",
            "blocks": ["user:turn-1", "toolu_1", "m1:t0"],
            "blocksById": {
              "user:turn-1": {"blockId": "user:turn-1", "kind": "user_message", "text": "do it"},
              "toolu_1": {"blockId": "toolu_1", "kind": "tool", "name": "Bash", "input": {"command": "ls"}, "output": "ok", "isError": false, "done": true,
                "subagent": {"order": ["c1"], "entries": {"c1": {"key": "c1", "kind": "tool", "name": "Read", "done": true}}, "usage": {"outputTokens": 7}}},
              "m1:t0": {"blockId": "m1:t0", "kind": "message", "text": "done", "done": true}
            },
            "pendingApprovals": {},
            "pendingQuestions": {},
            "permissionDenials": [],
            "usage": {"model": "claude-sonnet-5", "perTurnTokens": 115, "modelUsages": [{"model": "claude-sonnet-5", "outputTokens": 88}]},
            "apiRetry": null,
            "plan": {"explanation": null, "steps": [{"step": "one", "status": "completed"}]},
            "diff": {"unifiedDiff": "--- a\n+++ b\n"},
            "modelReroutes": [],
            "reviews": [{"reviewId": "r1", "status": "completed", "result": "clean"}],
            "compactions": [{"itemId": "c1"}],
            "providerNotices": [],
            "warnings": [],
            "exit": {"code": 0, "signal": null},
            "someFutureField": {"nested": true}
          },
          "turn-2": {
            "turnId": "turn-2",
            "idempotencyKey": null,
            "continuation": true,
            "status": "running",
            "startedAt": 2000,
            "liveTokens": null,
            "run": {"index": 0, "startedAt": 2000, "tokensStart": 0},
            "runCount": 1,
            "activeMs": 0,
            "blocks": [],
            "blocksById": {},
            "pendingApprovals": {
              "req-1": {
                "requestId": "req-1",
                "toolId": "toolu_2",
                "name": "Bash",
                "input": {"command": "rm -rf /tmp/x"},
                "choices": [{"choiceId": "allow", "label": "Allow"}],
                "metadata": {"provider": "claude", "kind": "command", "command": "rm -rf /tmp/x"}
              }
            },
            "pendingQuestions": {},
            "permissionDenials": [],
            "usage": null,
            "apiRetry": {"attempt": 1, "maxRetries": 10, "delayMs": 400, "errorStatus": 529, "error": "overloaded"},
            "modelReroutes": [],
            "reviews": [],
            "compactions": [],
            "providerNotices": [],
            "warnings": []
          }
        },
        "activeTurnId": "turn-2",
        "queuedMessages": [{"queueId": "q-1", "text": "queued prompt"}]
      }
    }
    """.trimIndent()

    @Test
    fun snapshotDeserializesIntoSessionProjection() {
        val message = ServerMessage.parse(snapshotJson)
        assertTrue(message is ServerMessage.Snapshot)
        val snapshot = message as ServerMessage.Snapshot
        assertEquals("sess-1", snapshot.sessionId)
        assertEquals(42L, snapshot.throughSeq)
        assertFalse(snapshot.reset)

        val state = snapshot.state
        assertEquals("claude", state.provider)
        assertEquals("active", state.status)
        assertEquals("turn-2", state.activeTurnId)
        assertEquals(listOf("turn-1", "turn-2"), state.turnOrder)
        assertEquals("q-1", state.queuedMessages.single().queueId)
        assertEquals(1L, state.notices.single().outstanding)
        assertEquals("subcommandResults", state.unattributedPermissionDenials.single().reasonCode)

        val turn1 = state.turnsById.getValue("turn-1")
        assertEquals("done", turn1.status)
        assertEquals(99L, turn1.liveTokens)
        assertEquals(6_100L, turn1.activeMs)
        assertEquals(2, turn1.runCount)
        assertEquals("ok", turn1.outcome)
        val tool = turn1.blocksById.getValue("toolu_1")
        assertEquals("Bash", tool.name)
        assertEquals("ls", (tool.input as JsonObject)["command"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive("ok"), tool.output)
        assertEquals(7L, tool.subagent?.usage?.outputTokens)
        assertEquals("Read", tool.subagent?.entries?.get("c1")?.name)
        assertEquals(88L, turn1.usage?.modelUsages?.single()?.outputTokens)
        assertEquals("clean", turn1.reviews.single().result)
        assertEquals(0, turn1.exit?.code)

        val turn2 = state.turnsById.getValue("turn-2")
        assertTrue(turn2.continuation)
        assertEquals(0, turn2.run?.index)
        val approval = turn2.pendingApprovals.getValue("req-1")
        assertEquals("Allow", approval.choices?.single()?.label)
        assertEquals("command", approval.metadata?.kind)
        assertEquals(529, turn2.apiRetry?.errorStatus)

        // The snapshot is a valid reducer input: fold a live event on top.
        val folded = reduce(
            state,
            AgentEvent.parse(
                TetherJson.parseToJsonElement(
                    """{"type":"turn_end","turnId":"turn-2","outcome":"ok","seq":43,"ts":9000}""",
                ).jsonObject,
            ),
        )
        assertEquals(Vocab.SESSION_READY, folded.status)
        assertNull(folded.activeTurnId)
    }

    @Test
    fun eventEnvelopeParsesFlatSeqAndTs() {
        val frame = """
            {"type":"event","sessionId":"sess-1",
             "event":{"type":"message_delta","turnId":"t1","blockId":"m1:t0","text":"hi","seq":7,"ts":1234567890123}}
        """.trimIndent()
        val message = ServerMessage.parse(frame)
        assertTrue(message is ServerMessage.Event)
        val event = (message as ServerMessage.Event).event
        assertEquals("message_delta", event.type)
        assertEquals("t1", event.turnId)
        assertEquals(7L, event.seq)
        assertEquals(1_234_567_890_123L, event.ts)
        assertEquals("hi", event.raw.str("text"))
    }

    @Test
    fun unknownServerMessageTypesAreInert() {
        assertTrue(ServerMessage.parse("""{"type":"codex-controls","sessionId":"s"}""") is ServerMessage.Unknown)
        assertTrue(ServerMessage.parse("""{"type":"brand_new"}""") is ServerMessage.Unknown)
        assertTrue(ServerMessage.parse("not json at all") is ServerMessage.Unknown)
        assertTrue(ServerMessage.parse("""{"noType":true}""") is ServerMessage.Unknown)
    }

    @Test
    fun readyAndVersionMismatchParse() {
        val ready = ServerMessage.parse(
            """
            {"type":"ready","protocolVersion":40,
             "sessions":[{"id":"s1","provider":"claude","name":"one","cwd":"/w","status":"ready","startedAt":1,"updatedAt":2,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"}],
             "providers":[{"id":"claude","label":"Claude","glyph":"C","available":true,"capabilities":{"persistentSessions":true}}],
             "workspaceRoot":"/home/user"}
            """.trimIndent(),
        )
        assertTrue(ready is ServerMessage.Ready)
        val r = ready as ServerMessage.Ready
        assertEquals(40, r.protocolVersion)
        assertEquals("s1", r.sessions.single().id)
        assertTrue(r.providers.single().capabilities!!.persistentSessions)
        assertEquals("/home/user", r.workspaceRoot)

        val mismatch = ServerMessage.parse("""{"type":"version_mismatch","requiredVersion":41,"message":"reload"}""")
        assertEquals(41, (mismatch as ServerMessage.VersionMismatch).requiredVersion)
    }

    @Test
    fun clientMessagesSerializeExactFields() {
        val send = ClientMessage.Send("s1", "hello", "key-123").toJsonObject()
        assertEquals(setOf("type", "sessionId", "text", "idempotencyKey"), send.keys)
        assertEquals("send", send["type"]!!.jsonPrimitive.content)
        assertEquals("key-123", send["idempotencyKey"]!!.jsonPrimitive.content)

        // v15 attachments: exact server-validated field names (protocol-validate.mjs
        // validateAttachments), omitted entirely when there are none.
        val withFile = ClientMessage.Send(
            "s1",
            "look at this",
            "key-9",
            listOf(Attachment(name = "shot.png", mediaType = "image/png", data = "aGVsbG8=")),
        ).toJsonObject()
        assertEquals(setOf("type", "sessionId", "text", "idempotencyKey", "attachments"), withFile.keys)
        val att = withFile["attachments"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("name", "mediaType", "data"), att.keys)
        assertEquals("shot.png", att["name"]!!.jsonPrimitive.content)
        assertEquals("image/png", att["mediaType"]!!.jsonPrimitive.content)
        assertEquals("aGVsbG8=", att["data"]!!.jsonPrimitive.content)
        assertFalse(
            ClientMessage.Send("s1", "t", "k", emptyList()).toJsonObject().containsKey("attachments")
        )

        val attach = ClientMessage.Attach("s1", 42).toJsonObject()
        assertEquals(setOf("type", "sessionId", "afterSeq"), attach.keys)
        assertEquals(42L, attach["afterSeq"]!!.jsonPrimitive.content.toLong())
        // afterSeq omitted entirely (never null) when absent.
        assertEquals(setOf("type", "sessionId"), ClientMessage.Attach("s1").toJsonObject().keys)

        val hello = ClientMessage.Hello().toJsonObject()
        assertEquals(40, hello["protocolVersion"]!!.jsonPrimitive.content.toInt())

        val queueAdd = ClientMessage.QueueAdd("s1", "q-1", "text").toJsonObject()
        assertEquals(setOf("type", "sessionId", "queueId", "text"), queueAdd.keys)
        assertEquals("queue-add", queueAdd["type"]!!.jsonPrimitive.content)
        assertEquals("queue-edit", ClientMessage.QueueEdit("s1", "q", "t").toJsonObject()["type"]!!.jsonPrimitive.content)
        assertEquals("queue-remove", ClientMessage.QueueRemove("s1", "q").toJsonObject()["type"]!!.jsonPrimitive.content)

        val create = ClientMessage.Create(provider = "claude", cwd = "/w").toJsonObject()
        assertEquals(setOf("type", "provider", "cwd"), create.keys)

        assertEquals(
            setOf("type", "sessionId", "permissionMode"),
            ClientMessage.SetMode("s1", "plan").toJsonObject().keys,
        )
        assertEquals(setOf("type", "historyId", "cwd"), ClientMessage.Resume("h1", "/w").toJsonObject().keys)
        assertEquals(setOf("type", "cwd"), ClientMessage.Discover("/w").toJsonObject().keys)
        assertEquals(setOf("type"), ClientMessage.Browse().toJsonObject().keys)
        assertEquals(setOf("type", "sessionId"), ClientMessage.Interrupt("s1").toJsonObject().keys)
        assertEquals(setOf("type", "sessionId", "pinned"), ClientMessage.Pin("s1", true).toJsonObject().keys)
        assertEquals(setOf("type", "sessionId", "name"), ClientMessage.Rename("s1", "New").toJsonObject().keys)
        assertEquals(setOf("type", "sessionId"), ClientMessage.Archive("s1").toJsonObject().keys)
        assertEquals(setOf("type", "sessionId"), ClientMessage.Kill("s1").toJsonObject().keys)

        assertEquals(
            setOf("type", "sessionId", "model"),
            ClientMessage.SetModel("s1", "claude-opus-4-8").toJsonObject().keys,
        )
        assertEquals(
            "set-model",
            ClientMessage.SetModel("s1", "claude-opus-4-8").toJsonObject()["type"]!!.jsonPrimitive.content,
        )
        // The clear-to-default path is load-bearing: `model` must be present
        // even when empty (omitting it would silently keep the current model).
        val cleared = ClientMessage.SetModel("s1", "").toJsonObject()
        assertEquals("", cleared["model"]!!.jsonPrimitive.content)
        assertEquals(
            setOf("type", "sessionId"),
            ClientMessage.SessionControlsRequest("s1").toJsonObject().keys,
        )
        assertEquals(
            "session-controls",
            ClientMessage.SessionControlsRequest("s1").toJsonObject()["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun approvalIsChoiceIdXorDecision() {
        val byChoice = ClientMessage.Approval("s1", "r1", choiceId = "allow-once").toJsonObject()
        assertEquals(setOf("type", "sessionId", "requestId", "choiceId"), byChoice.keys)

        val byDecision = ClientMessage.Approval("s1", "r1", decision = "deny").toJsonObject()
        assertEquals(setOf("type", "sessionId", "requestId", "decision"), byDecision.keys)

        assertTrue(runCatching { ClientMessage.Approval("s1", "r1") }.isFailure)
        assertTrue(runCatching { ClientMessage.Approval("s1", "r1", "c", "allow") }.isFailure)
    }

    @Test
    fun questionAnswersNesting() {
        val question = ClientMessage.Question(
            sessionId = "s1",
            requestId = "req-1",
            answers = mapOf("Which color?" to "Blue, Green"),
            response = "extra context",
        ).toJsonObject()
        assertEquals(setOf("type", "sessionId", "requestId", "answers"), question.keys)
        val payload = question["answers"]!!.jsonObject
        assertEquals(setOf("answers", "response"), payload.keys)
        assertEquals(
            "Blue, Green",
            payload["answers"]!!.jsonObject["Which color?"]!!.jsonPrimitive.content,
        )
        // Without response, the key is omitted.
        val noResponse = ClientMessage.Question("s1", "r", mapOf("Q" to "A")).toJsonObject()
        assertEquals(setOf("answers"), noResponse["answers"]!!.jsonObject.keys)
    }

    @Test
    fun sessionControlsAndLogParse() {
        val controls = ServerMessage.parse(
            """
            {"type":"session-controls","sessionId":"s1",
             "models":[{"value":"default","displayName":"Default","current":true}],
             "commands":[{"name":"compact","supported":true},{"name":"vim","supported":false}],
             "model":"default"}
            """.trimIndent(),
        )
        assertTrue(controls is ServerMessage.SessionControls)
        assertEquals(2, (controls as ServerMessage.SessionControls).commands.size)

        val log = ServerMessage.parse(
            """{"type":"log","bootId":"boot-1","entries":[{"seq":1,"ts":2,"level":"info","event":"session_created"}]}""",
        )
        assertTrue(log is ServerMessage.Log)
        assertEquals("boot-1", (log as ServerMessage.Log).bootId)
    }
}
