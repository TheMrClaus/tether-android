package com.tether.app.client

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T9.1 on the real client: the inspector's two reads (`worktree-scripts`, `change-request`) go out
 * as the web sends them (use-tether.ts:1496, 1508), and their replies fold like use-tether.ts
 * 918-921 / 940-941: a scripts snapshot keyed by its own sessionId, a change-request per session.
 */
class InspectorReadsClientTest {
    private val h = ConnectionHarness()

    @After fun tearDown() = h.close()

    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(TimeUnit.SECONDS.toMillis(20)) { flow.first(predicate) } }

    private fun session(id: String) =
        """{"id":"$id","provider":"claude","name":"n","cwd":"/w","status":"ready","startedAt":1,"updatedAt":1,"endedAt":null,""" +
            """"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"}"""

    /** The server lists [id] (a `created` frame), as a real session is before its replies arrive. */
    private fun created(ws: okhttp3.WebSocket, id: String) {
        ws.send("""{"type":"created","session":${session(id)}}""")
        await(h.client.sessions) { list -> list.any { it.id == id } }
    }

    private fun scripts(id: String) =
        """{"type":"worktree-scripts","snapshot":{"sessionId":"$id","worktreePath":"/w","branch":"b","scripts":[],"setupStatus":"ok","setupLog":[],"configWarnings":[]}}"""

    private fun changeRequest(id: String) = """{"type":"change-request","sessionId":"$id","changeRequest":null,"unknown":true}"""

    @Test fun theReadsGoOutAsTheWebSendsThem() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)

        assertEquals(true, h.client.requestWorktreeScripts("s1"))
        val scripts = h.expectFrame("worktree-scripts")
        assertEquals(setOf("type", "sessionId"), scripts.keys)
        assertEquals(true, h.client.requestChangeRequest("s1"))
        assertEquals(setOf("type", "sessionId"), h.expectFrame("change-request").keys)
        assertEquals(true, h.client.requestChangeRequest("s1", refresh = true))
        val refresh = h.expectFrame("change-request")
        assertEquals("true", refresh["refresh"]!!.jsonPrimitive.content)
    }

    @Test fun repliesFoldPerSession() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        created(ws, "s1")
        created(ws, "s2")

        ws.send("""{"type":"worktree-scripts","snapshot":{"sessionId":"s1","worktreePath":"/w","branch":"b","scripts":[],"setupStatus":"ok","setupLog":[],"configWarnings":[]}}""")
        ws.send("""{"type":"change-request","sessionId":"s1","changeRequest":{"number":7,"url":null,"state":"OPEN","isDraft":false},"unknown":false}""")
        ws.send("""{"type":"change-request","sessionId":"s2","changeRequest":null,"unknown":true}""")
        val scripts = await(h.client.worktreeScripts) { it["s1"] != null }
        assertEquals("/w", scripts["s1"]!!["worktreePath"]!!.jsonPrimitive.content)
        val crs = await(h.client.changeRequests) { it.size == 2 }
        assertEquals("7", crs["s1"]!!.changeRequest!!["number"]!!.jsonPrimitive.content)
        assertEquals(false, crs["s1"]!!.unknown)
        assertNull(crs["s2"]!!.changeRequest)
        assertEquals(true, crs["s2"]!!.unknown)

        // A snapshot with no sessionId has nowhere to go and is dropped.
        ws.send("""{"type":"worktree-scripts","snapshot":{"scripts":[]}}""")
        h.serverBarrier(ws)
        assertEquals(setOf("s1"), h.client.worktreeScripts.value.keys)
    }

    /**
     * ta-dl4: the replies are keyed by server-sent ids (and worktree-scripts also arrives
     * unsolicited), so only a known session's are kept: one the server listed or this client
     * subscribed to. A flood of unknown ids leaves both maps as they were.
     */
    @Test fun aFloodOfUnknownSessionIdsIsNotKept() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        created(ws, "s1")
        // An open session the list has not carried yet: subscribed, so its replies are kept.
        h.client.attach("open1")

        repeat(500) { i ->
            ws.send(scripts("ghost-$i"))
            ws.send(changeRequest("ghost-$i"))
        }
        ws.send(scripts("s1"))
        ws.send(changeRequest("s1"))
        ws.send(scripts("open1"))
        ws.send(changeRequest("open1"))
        await(h.client.changeRequests) { "open1" in it }
        h.serverBarrier(ws)
        assertEquals(setOf("s1", "open1"), h.client.worktreeScripts.value.keys)
        assertEquals(setOf("s1", "open1"), h.client.changeRequests.value.keys)
    }

    /** ta-dl4: a fresh `ready` drops the replies of a session it no longer lists; the open one stays. */
    @Test fun aReadyThatNoLongerListsASessionDropsItsReplies() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        created(ws, "gone")
        created(ws, "open1")
        h.client.attach("open1")
        h.expectFrame("attach")
        for (id in listOf("gone", "open1")) {
            ws.send(scripts(id))
            ws.send(changeRequest(id))
        }
        await(h.client.changeRequests) { it.size == 2 }
        await(h.client.worktreeScripts) { it.size == 2 }

        // The link drops; the next ready lists neither session, but open1 is still subscribed.
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        h.handshake(h.nextSocket())

        assertEquals(setOf("open1"), h.client.worktreeScripts.value.keys)
        assertEquals(setOf("open1"), h.client.changeRequests.value.keys)
    }

    private fun web(json: String) = Json.parseToJsonElement(json).jsonObject

    /**
     * ta-coik.14: the inspector's actions go out as the web's (use-tether.ts:1497-1508, at tether
     * 90fbb9f), key for key and value for value: Run/Stop/Restart, "Output of", and the refresh.
     */
    @Test fun theActionsGoOutAsTheWebSendsThem() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)

        for (action in listOf("start", "stop", "restart")) {
            assertEquals(true, h.client.controlWorktreeScript("s1", "dev", action))
            // send({ type: "worktree-script", sessionId, name, action })
            assertEquals(web("""{"type":"worktree-script","sessionId":"s1","name":"dev","action":"$action"}"""), h.expectFrame("worktree-script"))
        }
        assertEquals(true, h.client.requestWorktreeLogs("s1", "dev"))
        // send({ type: "worktree-logs", sessionId, name })
        assertEquals(web("""{"type":"worktree-logs","sessionId":"s1","name":"dev"}"""), h.expectFrame("worktree-logs"))
        assertEquals(true, h.client.requestChangeRequest("s1", refresh = true))
        // send({ type: "change-request", sessionId, ...(refresh ? { refresh: true } : {}) })
        assertEquals(web("""{"type":"change-request","sessionId":"s1","refresh":true}"""), h.expectFrame("change-request"))
    }

    /** ta-coik.14: use-tether.ts:922-926, the last `worktree-logs` reply per session, for a known session only. */
    @Test fun logsFoldPerSession() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        created(ws, "s1")

        ws.send("""{"type":"worktree-logs","sessionId":"ghost","name":"dev","lines":["x"],"dropped":0}""")
        ws.send("""{"type":"worktree-logs","sessionId":"s1","name":"dev","lines":["ready on :5173","GET /"],"dropped":3}""")
        val logs = await(h.client.worktreeLogs) { "s1" in it }
        assertEquals(WorktreeLogsReading("dev", listOf("ready on :5173", "GET /"), 3), logs["s1"])
        // Another script's reply replaces it (the web keeps one per session).
        ws.send("""{"type":"worktree-logs","sessionId":"s1","name":"test","lines":[],"dropped":0}""")
        await(h.client.worktreeLogs) { it["s1"]?.name == "test" }
        h.serverBarrier(ws)
        assertEquals(setOf("s1"), h.client.worktreeLogs.value.keys)
    }

    /** ta-coik.14: an action the link could not carry says so, in the web's words (use-tether.ts:337-341). */
    @Test fun anActionNotSentSaysSo() {
        h.newClient()
        val errors = CopyOnWriteArrayList<String>()
        val job = h.scope.launch(start = CoroutineStart.UNDISPATCHED) { h.client.errors.collect { errors += it } }
        try {
            assertEquals(false, h.client.controlWorktreeScript("s1", "dev", "start"))
            assertEquals(false, h.client.requestWorktreeLogs("s1", "dev"))
            assertEquals(false, h.client.requestChangeRequest("s1", refresh = true))
            runBlocking { withTimeout(20_000) { while (errors.size < 3) kotlinx.coroutines.delay(10) } }
            assertEquals(List(3) { "The secure link is reconnecting. Your input was not sent." }, errors.toList())
        } finally {
            job.cancel()
        }
    }
}
