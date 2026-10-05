package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ScheduledActionInput
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * T9.3: the client's half of the v87 scheduled actions (use-tether.ts:801, 885-887, 1915-1923;
 * dashboard.tsx:1598) against the fake-engine capture (parity-corpus/wire/scheduled-actions.jsonl)
 * and the recorded valid examples (client-examples.jsonl).
 */
class ScheduledActionsTest {

    private val wire = File(System.getProperty("parity.corpus") ?: "../../parity-corpus", "wire")

    private fun jsonl(name: String): List<JsonObject> =
        File(wire, name).readLines().filter { it.isNotBlank() }.map { TetherJson.parseToJsonElement(it).jsonObject }

    private val capture: List<JsonObject> by lazy { jsonl("scheduled-actions.jsonl") }

    private fun captured(dir: String): List<JsonObject> = capture.filter { it["dir"]!!.jsonPrimitive.content == dir }.map { it["frame"]!!.jsonObject }

    private fun example(type: String): JsonObject {
        val line = jsonl("client-examples.jsonl").single { it["type"]!!.jsonPrimitive.content == type }
        assertEquals("true", line["verdict"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
        return line["frame"]!!.jsonObject
    }

    private fun parse(frame: JsonObject) = ScheduledActionsParse.state(ServerMessage.parse(frame) as ServerMessage.ScheduledActions)

    // ---- decoding ---------------------------------------------------------------------------

    @Test fun everyCapturedSnapshotDecodesWithEveryField() {
        val states = captured("s2c").map(::parse)
        assertEquals(listOf(0, 1, 1, 1, 0), states.map { it.schedules.size })
        val created = states[1].schedules.single()
        assertEquals("sched-0001", created.id)
        assertEquals("Parity weekly", created.name)
        assertEquals("Summarize the week", created.prompt)
        assertEquals("claude", created.provider)
        assertEquals(listOf(null, null, null, null, null), listOf(created.profileId, created.model, created.reasoningEffort, created.permissionMode, created.sandboxPolicy))
        assertFalse(created.useWorktree)
        assertEquals("0 9 * * 1", created.cron)
        assertEquals("UTC", created.timeZone)
        assertNull(created.maxRuns)
        assertEquals("active", created.status)
        assertEquals(1767225600000L, created.nextRunAt)
        assertNull(created.lastRunAt)
        assertEquals(emptyList<ScheduledActionRun>(), created.runs)
        assertEquals(JsonObject(emptyMap()), created.unknown)
        assertEquals(2, states[2].schedules.single().maxRuns)
        assertEquals("paused", states[3].schedules.single().status)
        assertNull(states[3].schedules.single().nextRunAt)
        assertTrue(states.all { it.loaded && it.continuations.isEmpty() })
    }

    @Test fun runsAndContinuationsDecode() {
        val frame = TetherJson.parseToJsonElement(
            """{"type":"scheduled-actions","schedules":[{"id":"s","name":"n","prompt":"p","cwd":"/w","provider":"codex","profileId":"work",""" +
                """"model":"gpt-5","reasoningEffort":"high","permissionMode":"plan","sandboxPolicy":"read-only","useWorktree":true,"cron":"0 9 * * *",""" +
                """"timeZone":"Europe/Rome","maxRuns":3,"status":"active","createdAt":1,"updatedAt":2,"nextRunAt":3,"lastRunAt":4,"runs":[""" +
                """{"id":"r1","status":"failed","startedAt":10,"endedAt":11,"sessionId":null,"error":"boom","manual":true},""" +
                """{"id":"r2","status":"running","startedAt":12,"endedAt":null,"sessionId":"sess","error":null,"manual":false}]}],""" +
                """"continuations":[{"sessionId":"c1","sessionName":"Fix it","provider":"claude","cwd":"/w/p","resetsAt":100,"resumeAt":220}]}""",
        ).jsonObject
        val state = parse(frame)
        val s = state.schedules.single()
        assertEquals(listOf("work", "gpt-5", "high", "plan", "read-only"), listOf(s.profileId, s.model, s.reasoningEffort, s.permissionMode, s.sandboxPolicy))
        assertTrue(s.useWorktree)
        assertEquals(3, s.maxRuns)
        assertEquals(
            listOf(
                ScheduledActionRun("r1", "failed", 10, 11, null, "boom", true),
                ScheduledActionRun("r2", "running", 12, null, "sess", null, false),
            ),
            s.runs,
        )
        assertEquals(listOf(ScheduledContinuation("c1", "Fix it", "claude", "/w/p", 100, 220)), state.continuations)
    }

    @Test fun aMalformedEntryIsDroppedNeverFatal() {
        val frame = TetherJson.parseToJsonElement(
            """{"type":"scheduled-actions","schedules":[{"id":"s"},{"id":"ok","name":"n","prompt":"p","cwd":"/w","provider":"claude","cron":"* * * * *",""" +
                """"timeZone":"UTC","status":"active","maxRuns":"many","runs":[{"id":"bad"},{"id":"r","status":"succeeded","startedAt":1}]}],""" +
                """"continuations":[{"sessionId":"x"}]}""",
        ).jsonObject
        val state = parse(frame)
        val s = state.schedules.single()
        assertEquals("ok", s.id)
        assertNull("a wrongly typed nullable field reads null", s.maxRuns)
        assertEquals(listOf("r"), s.runs.map { it.id })
        assertEquals(emptyList<ScheduledContinuation>(), state.continuations)
    }

    /** tether #241 adds `setupConsent` (v143, now modelled): an edit starting from the stored schedule sends it back unchanged. */
    @Test fun anUnknownFieldRoundTripsThroughAnEdit() {
        val frame = TetherJson.parseToJsonElement(
            captured("s2c")[1].toString().replace("\"runs\":[]", "\"runs\":[],\"setupConsent\":\"sha256:${"a".repeat(64)}\""),
        ).jsonObject
        val schedule = parse(frame).schedules.single()
        assertEquals(com.tether.app.protocol.OrNull("sha256:${"a".repeat(64)}"), schedule.setupConsent)
        assertNull("it is modelled now, so it is no unknown field", schedule.unknown["setupConsent"])
        val encoded = ClientMessage.ScheduleUpdate(schedule.id, schedule.toInput()).toJsonObject()
        val input = encoded["schedule"]!!.jsonObject
        assertEquals(ScheduledActionInput.KEYS.toList(), input.keys.toList())
        assertEquals(JsonPrimitive("sha256:${"a".repeat(64)}"), input["setupConsent"])
    }

    @Test fun aStoredNullApprovalStaysAnExplicitNullAndAnAbsentOneStaysAbsent() {
        val base = captured("s2c")[1].toString()
        val nulled = parse(TetherJson.parseToJsonElement(base.replace("\"runs\":[]", "\"runs\":[],\"setupConsent\":null")).jsonObject).schedules.single()
        assertEquals(com.tether.app.protocol.OrNull<String>(null), nulled.setupConsent)
        assertEquals("null", ClientMessage.ScheduleUpdate(nulled.id, nulled.toInput()).toJsonObject()["schedule"]!!.jsonObject["setupConsent"].toString())
        val absent = parse(TetherJson.parseToJsonElement(base).jsonObject).schedules.single()
        assertNull(absent.setupConsent)
        assertFalse(ClientMessage.ScheduleUpdate(absent.id, absent.toInput()).toJsonObject()["schedule"]!!.jsonObject.containsKey("setupConsent"))
    }

    @Test fun anExtraFieldCanNeverReplaceAModelledOne() {
        val extra = TetherJson.parseToJsonElement("""{"provider":"gemini","maxRuns":99,"later":true}""").jsonObject
        val input = ScheduledActionInput("n", "p", "/w", "claude", null, null, null, null, null, false, "* * * * *", "UTC", null, extra = extra)
        val encoded = input.toJsonObject()
        assertEquals("claude", encoded["provider"]!!.jsonPrimitive.content)
        assertEquals("null", encoded["maxRuns"].toString())
        assertEquals("true", encoded["later"].toString())
    }

    // ---- the client over a socket ----------------------------------------------------------

    private val h = ConnectionHarness()

    @After fun tearDown() = h.close()

    private fun connected(): okhttp3.WebSocket {
        h.newClient()
        h.enqueueConnect()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return ws
    }

    @Test fun everyReadyAsksForTheSnapshotAndTheFrameReplacesBothLists() {
        val ws = connected()
        assertEquals("""{"type":"scheduled-actions"}""", h.scheduledRequests.poll(20, TimeUnit.SECONDS))
        assertFalse(h.client.scheduledActions.value.loaded)
        for (frame in captured("s2c").drop(1)) {
            ws.send(frame.toString())
            h.serverBarrier(ws)
            assertEquals(parse(frame), h.client.scheduledActions.value)
        }
    }

    @Test fun theFourSendsAreTheCapturedFramesByteForByte() {
        connected()
        val c2s = captured("c2s")
        val created = parse(captured("s2c")[1]).schedules.single()
        // The create, built as the web's submit builds it.
        assertTrue(h.client.createSchedule(created.toInput()))
        assertEquals(c2s[1].toString(), h.expectFrame("schedule-create").toString())
        assertTrue(h.client.updateSchedule("sched-0001", created.toInput().copy(name = "Parity weekly (edited)", maxRuns = 2)))
        assertEquals(c2s[2].toString(), h.expectFrame("schedule-update").toString())
        assertTrue(h.client.controlSchedule("sched-0001", "pause"))
        assertEquals(c2s[3].toString(), h.expectFrame("schedule-control").toString())
        assertTrue(h.client.controlSchedule("sched-0001", "delete"))
        assertEquals(c2s[4].toString(), h.expectFrame("schedule-control").toString())
        // The bare request is the same frame the ready sends (the harness keeps both apart).
        assertEquals(c2s[0].toString(), h.scheduledRequests.poll(20, TimeUnit.SECONDS))
        assertTrue(h.client.requestScheduledActions())
        assertEquals(c2s[0].toString(), h.scheduledRequests.poll(20, TimeUnit.SECONDS))
    }

    @Test fun theRecordedExamplesReEncode() {
        connected()
        for (type in listOf("schedule-create", "schedule-update")) {
            val recorded = example(type)
            val decoded = ClientMessage.decode(recorded).getOrThrow()
            assertEquals(recorded.toString(), decoded.encode())
        }
        val update = ClientMessage.decode(example("schedule-update")).getOrThrow() as ClientMessage.ScheduleUpdate
        assertTrue(h.client.updateSchedule(update.scheduleId, update.schedule))
        assertEquals(example("schedule-update").toString(), h.expectFrame("schedule-update").toString())
        assertTrue(h.client.controlSchedule("schedule-0001", "pause"))
        assertEquals(example("schedule-control").toString(), h.expectFrame("schedule-control").toString())
    }

    @Test fun cancellingAContinuationDismissesItsLimitResume() {
        connected()
        assertTrue(h.client.cancelScheduledContinuation("session-0001", 1767225600000))
        assertEquals(
            """{"type":"rate-limit-resume","sessionId":"session-0001","resetsAt":1767225600000,"action":"dismiss"}""",
            h.expectFrame("rate-limit-resume").toString(),
        )
    }

    @Test fun anUnsentSendSaysSoInTheWebsWords() {
        h.newClient(configured = false)
        runBlocking {
            val toast = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { withTimeout(20_000) { h.client.errors.first() } }
            assertFalse(h.client.controlSchedule("s", "run"))
            assertEquals(NodeRegistryRules.NOT_SENT_MESSAGE, toast.await())
        }
    }

    @Test fun clearingForAnotherServerEmptiesTheLists() {
        val sync = ScheduledActionsSync { true }
        assertTrue(sync.onFrame(ServerMessage.parse(captured("s2c")[1])))
        assertNotNull(sync.state.value.schedules.singleOrNull())
        sync.clear()
        assertEquals(ScheduledActionsState(), sync.state.value)
        assertFalse(sync.onFrame(ServerMessage.parse(TetherJson.parseToJsonElement("""{"type":"pong"}""").jsonObject)))
    }
}
