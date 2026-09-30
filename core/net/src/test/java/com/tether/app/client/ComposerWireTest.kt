package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.fold.initialSessionState
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.ui.TestViewModels
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.InMemoryDraftStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T7.1: the composer's wire frames against the vendored corpus (parity-corpus/wire).
 *
 * 1. `queue-add` / `queue-edit` / `queue-remove` re-encode byte for byte (key order included) as
 *    the validated client examples (client-examples.jsonl, `verdict.ok`).
 * 2. The recorded `queue` scenario replayed over a real socket through the composer's own path
 *    ([TetherViewModel.sendOrQueue] for Enter / Send / Queue, the client's queueEdit/queueRemove
 *    for a row's commit and its remove key): every frame the app puts on the wire equals the
 *    recorded one once the client-minted keys (a fresh UUID per message, as the web's
 *    newIdempotencyKey) are substituted, and the queue the composer renders follows the server's
 *    `queued_message_*` events, down to the flush into the next turn (whose idempotencyKey is the
 *    queueId).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerWireTest {
    private val wire = File(System.getProperty("parity.corpus") ?: "../../parity-corpus", "wire")
    private val h = ConnectionHarness()
    private val vms = TestViewModels()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() {
        // T7.4 r2: the view model first (see TestViewModels), then the client, then Main.
        vms.clear()
        h.close()
        Dispatchers.resetMain()
    }

    private fun jsonl(name: String): List<JsonObject> =
        File(wire, name).readLines().filter { it.isNotBlank() }.map { TetherJson.parseToJsonElement(it).jsonObject }

    private fun example(type: String): JsonObject {
        val line = jsonl("client-examples.jsonl").single { it["type"]!!.jsonPrimitive.content == type }
        assertEquals("true", line["verdict"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
        return line["frame"]!!.jsonObject
    }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    @Test fun queueFramesReEncodeAsTheCorpusExamples() {
        val add = example("queue-add")
        val edit = example("queue-edit")
        val remove = example("queue-remove")
        for ((recorded, encoded) in listOf(
            add to ClientMessage.QueueAdd(add.str("sessionId"), add.str("queueId"), add.str("text")),
            edit to ClientMessage.QueueEdit(edit.str("sessionId"), edit.str("queueId"), edit.str("text")),
            remove to ClientMessage.QueueRemove(remove.str("sessionId"), remove.str("queueId")),
        )) {
            assertEquals(recorded, encoded.toJsonObject())
            assertEquals(recorded.toString(), encoded.encode())
        }
    }

    @Test fun theRecordedQueueScenarioReplaysThroughTheComposerPath() {
        val sessionId = "sess-0001"
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        val vm = vms.track(TetherViewModel(client, InMemoryDraftStore(), monotonicClock = { 0 }))
        vm.selectSession(sessionId)
        h.expectFrame("attach")
        ws.send(snapshotFrame(sessionId, 58, EMPTY_STATE))
        h.await(client.projections) { it.containsKey(sessionId) }

        val lines = jsonl("queue.jsonl")
        val stop = lines.indexOfFirst { line ->
            line.str("dir") == "s2c" && line["frame"]!!.jsonObject["event"]?.jsonObject?.str("type") == "turn_started" &&
                lines.indexOf(line) > 1
        }
        assertTrue("the scenario flushes the queue into a second turn", stop > 0)
        val minted = HashMap<String, String>() // recorded key -> the key this client minted
        fun substitute(text: String): String = minted.entries.fold(text) { acc, (from, to) -> acc.replace("\"$from\"", "\"$to\"") }
        // The server-side barrier is a `created` reply, which the view model follows (T5.2) with an attach.
        fun barrier() {
            h.serverBarrier(ws)
            h.expectFrame("attach")
        }
        fun queued(): List<Pair<String, String>> = client.projections.value[sessionId]!!.queuedMessages.map { it.queueId to it.text }

        for (line in lines.take(stop + 1)) {
            val frame = line["frame"]!!.jsonObject
            if (line.str("dir") == "s2c") {
                ws.send(substitute(frame.toString()))
                continue
            }
            barrier() // every recorded server frame before this action has been applied
            when (frame.str("type")) {
                "send", "queue-add" -> {
                    val busy = client.projections.value[sessionId]!!.activeTurnId != null
                    assertEquals("send while idle, queue-add while a turn runs", frame.str("type") == "queue-add", busy)
                    assertTrue(vm.sendOrQueue(sessionId, frame.str("text")))
                    val sent = h.expectFrame(frame.str("type"))
                    val keyField = if (frame.str("type") == "send") "idempotencyKey" else "queueId"
                    minted[frame.str(keyField)] = sent.str(keyField)
                    assertTrue("a fresh UUID key", UUID.matches(sent.str(keyField)))
                    assertEquals(substitute(frame.toString()), sent.toString())
                }
                "queue-edit" -> {
                    vm.client.queueEdit(sessionId, minted.getValue(frame.str("queueId")), frame.str("text"))
                    assertEquals(substitute(frame.toString()), h.expectFrame("queue-edit").toString())
                }
                "queue-remove" -> {
                    vm.client.queueRemove(sessionId, minted.getValue(frame.str("queueId")))
                    assertEquals(substitute(frame.toString()), h.expectFrame("queue-remove").toString())
                }
                else -> Unit // approvals: not the composer's
            }
        }
        barrier()
        val first = minted.getValue("parity-queue-1")
        // queue.jsonl 26-35: added two, edited the first, removed the second; 45: the first flushed.
        assertEquals(emptyList<Pair<String, String>>(), queued())
        val flushedInto = client.projections.value[sessionId]!!.activeTurnId
        assertNotNull("the flush started the next turn", flushedInto)
        val tree = client.projectionTrees.value[sessionId]!!
        assertTrue("the next turn is keyed by the queueId", tree.toString().contains("\"idempotencyKey\":\"$first\""))
    }

    /** The composer's queue list at each step of the scenario (what QueuedMessages renders). */
    @Test fun theQueueTheComposerRendersFollowsTheEvents() {
        val sessionId = "sess-0001"
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        client.attach(sessionId)
        h.expectFrame("attach")
        ws.send(snapshotFrame(sessionId, 58, EMPTY_STATE))
        h.await(client.projections) { it.containsKey(sessionId) }
        val lines = jsonl("queue.jsonl")
        val expected = mapOf(
            26 to listOf("parity-queue-1" to "queued one"),
            29 to listOf("parity-queue-1" to "queued one", "parity-queue-2" to "queued two"),
            32 to listOf("parity-queue-1" to "queued one, edited", "parity-queue-2" to "queued two"),
            35 to listOf("parity-queue-1" to "queued one, edited"),
            45 to emptyList(),
        )
        for ((index, line) in lines.withIndex()) {
            if (index > 45) break
            if (line.str("dir") != "s2c") continue
            ws.send(line["frame"]!!.jsonObject.toString())
            expected[index]?.let { want ->
                h.serverBarrier(ws)
                val p = client.projections.value[sessionId]
                assertNotNull("line $index: the projection is gone (a fold failed and the client re-attached?)", p)
                val got = p!!.queuedMessages.map { it.queueId to it.text }
                assertEquals("after queue.jsonl line $index", want, got)
            }
        }
    }

    private companion object {
        /** The session as the reducer starts it (the recorded scenario's earlier turns are not in the file). */
        val EMPTY_STATE: String = JsCodec.stringify(initialSessionState("sess-0001", "claude", "/w"))
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}
