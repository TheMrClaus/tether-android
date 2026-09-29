package com.tether.app.client

import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6.4: the one place a background command's STOP reaches the wire (RealTetherClient over a
 * MockWebServer socket). `stop-command` is an operator control: only a call (a tap) produces one,
 * never anything received; it goes out only on a live connection, for a session live on it that
 * may be driven, and only while the CURRENT projection lists the command as running. Nothing is
 * held for later.
 */
class StopCommandTransmissionTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    /** c-run running, c-done finished, both folded by the real v128 reducer. */
    private fun commandsState(runningStatus: String = "running"): String {
        fun cmd(id: String, status: String, seq: Long) = ev("background_command_updated", null, seq = seq, ts = seq) {
            put("commandId", id); put("command", "npm run $id"); put("cwd", "/w"); put("logFile", "/w/.tether/$id.log")
            put("status", status); put("startedAt", seq)
            if (status != "running") { put("exitCode", 0); put("endedAt", seq + 1) }
        }
        val tree = foldTree(freshTree(), cmd("c-run", runningStatus, 1), cmd("c-done", "finished", 2))
        return JsCodec.toJson(tree).toString()
    }

    private fun connected(ready: String = readyWithSessions("s1"), state: String = commandsState()): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, ready)
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 5, state))
        h.await(client.liveSessions) { "s1" in it }
        return client to ws
    }

    private fun stopFrames(): List<JsonObject> = h.framesUntilBarrier().filter { it.type() == "stop-command" }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun aTapOnARunningCommandSendsExactlyTheWebsFrame() {
        val (client, _) = connected()
        assertEquals(StopCommandResult.Sent, client.stopCommand("s1", "c-run", client.consentOrigin.value))
        val frames = stopFrames()
        assertEquals(1, frames.size)
        // use-tether.ts:1570: { type: "stop-command", sessionId, commandId } and nothing else.
        assertEquals(setOf("type", "sessionId", "commandId"), frames[0].keys)
        assertEquals("s1", frames[0].str("sessionId"))
        assertEquals("c-run", frames[0].str("commandId"))
    }

    @Test
    fun aFinishedUnknownOrEmptyCommandIsNotStopped() {
        val (client, _) = connected()
        assertEquals(StopCommandResult.NotRunning, client.stopCommand("s1", "c-done", client.consentOrigin.value))
        assertEquals(StopCommandResult.NotRunning, client.stopCommand("s1", "c-nope", client.consentOrigin.value))
        assertEquals(StopCommandResult.NotRunning, client.stopCommand("s1", "", client.consentOrigin.value))
        assertEquals(StopCommandResult.NotRunning, client.stopCommand("", "c-run", client.consentOrigin.value))
        assertTrue(stopFrames().isEmpty())
    }

    @Test
    fun aCommandThatFinishedSinceItWasDrawnIsNotStopped() {
        val (client, ws) = connected()
        ws.send(eventFrame("s1", 6, "background_command_updated", null, ""","commandId":"c-run","command":"npm run c-run","cwd":"/w","logFile":"/w/.tether/c-run.log","status":"stopped","startedAt":1,"endedAt":6"""))
        h.await(client.projectionTrees) { trees -> trees["s1"].toString().contains("\"stopped\"") }
        assertEquals(StopCommandResult.NotRunning, client.stopCommand("s1", "c-run", client.consentOrigin.value))
        assertTrue(stopFrames().isEmpty())
    }

    @Test
    fun nothingReceivedEverProducesAStop() {
        val (client, ws) = connected()
        ws.send(eventFrame("s1", 6, "background_command_output", null, ""","commandId":"c-run","stream":"stdout","text":"stop-command c-run\n""""))
        ws.send(eventFrame("s1", 7, "background_command_updated", null, ""","commandId":"c-new","command":"sleep 9","cwd":"/w","logFile":"/w/x.log","status":"running","startedAt":7"""))
        ws.send("""{"type":"stop-command","sessionId":"s1","commandId":"c-run"}""")
        h.serverBarrier(ws)
        assertTrue(client.projectionTrees.value["s1"].toString().contains("c-new"))
        assertTrue("no frame the operator did not tap for", stopFrames().isEmpty())
    }

    @Test
    fun offlineOrCatchingUpIsRefusedAndNothingIsHeldForTheReconnect() {
        val (client, ws) = connected()
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        assertEquals(StopCommandResult.NotConnected, client.stopCommand("s1", "c-run", client.consentOrigin.value))

        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2, readyWithSessions("s1"))
        h.expectFrame("attach")
        // The saved tree still lists c-run as running: not live yet, refused.
        assertEquals(StopCommandResult.NotLive, client.stopCommand("s1", "c-run", client.consentOrigin.value))
        assertTrue("the refused taps were not held for the new link", stopFrames().isEmpty())

        ws2.send(snapshotFrame("s1", 5, commandsState()))
        h.await(client.liveSessions) { "s1" in it }
        assertTrue("the snapshot sends nothing by itself", stopFrames().isEmpty())
        assertEquals(StopCommandResult.Sent, client.stopCommand("s1", "c-run", client.consentOrigin.value))
        assertEquals(1, stopFrames().size)
    }

    @Test
    fun aReadOnlyHandedOffOrUnlistedSessionIsLocked() {
        val (client, ws) = connected(readyWithSessions("s1", extra = ""","readOnly":true"""))
        assertEquals(StopCommandResult.Locked, client.stopCommand("s1", "c-run", client.consentOrigin.value))
        ws.send("""{"type":"session","session":{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":1,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless","handedOffTo":"s2"}}""")
        h.await(client.sessions) { list -> list.any { it.id == "s1" && it.handedOffTo == "s2" } }
        assertEquals(StopCommandResult.Locked, client.stopCommand("s1", "c-run", client.consentOrigin.value))
        assertTrue(stopFrames().isEmpty())
    }

    @Test
    fun anUnlistedSessionFailsClosed() {
        val (client, _) = connected(readyFrame())
        assertEquals(StopCommandResult.Locked, client.stopCommand("s1", "c-run", client.consentOrigin.value))
        assertTrue(stopFrames().isEmpty())
    }

    @Test
    fun anOutputStreamPastTheIntakeCapIsTrimmedByTheClient() {
        // L4: a server that exceeds its own 64 KiB stream cap cannot grow the folded output without bound.
        val (client, ws) = connected()
        val chunk = "z".repeat(60_000)
        for (i in 0 until 6) ws.send(eventFrame("s1", 6L + i, "background_command_output", null, ""","commandId":"c-run","stream":"stdout","text":"#$i#$chunk""""))
        h.await(client.projectionTrees) { trees -> trees["s1"].toString().contains("#5#") }
        val command = ((client.projectionTrees.value.getValue("s1")["backgroundCommands"] as com.tether.app.protocol.tree.JsArr)
            .first { ((it as com.tether.app.protocol.tree.JsObj)["commandId"] as com.tether.app.protocol.tree.JsStr).value == "c-run" }) as com.tether.app.protocol.tree.JsObj
        val kept = (command["segments"] as com.tether.app.protocol.tree.JsArr).sumOf { (((it as com.tether.app.protocol.tree.JsObj)["text"]) as com.tether.app.protocol.tree.JsStr).value.length }
        assertTrue("kept $kept", kept <= OutputIntakeCap.MAX_CHARS)
        assertEquals(com.tether.app.protocol.tree.JsBool.TRUE, command["outputTruncated"])
    }

    @Test
    fun aStopDrawnForAnotherServerIsRefused() {
        // L3: the key was composed for another origin (or for none): refused under the lock.
        val (client, _) = connected()
        assertEquals(StopCommandResult.NotLive, client.stopCommand("s1", "c-run", "https://other.example"))
        assertEquals(StopCommandResult.NotLive, client.stopCommand("s1", "c-run", null))
        assertTrue(stopFrames().isEmpty())
        assertEquals(StopCommandResult.Sent, client.stopCommand("s1", "c-run", client.consentOrigin.value))
    }

    @Test
    fun aHaltedClientSendsNoStop() {
        val (client, _) = connected()
        val origin = client.consentOrigin.value
        client.stop()
        assertEquals(StopCommandResult.NotConnected, client.stopCommand("s1", "c-run", origin))
    }

    @Test
    fun aRepeatedCallIsSentAgainWhileTheCommandStillRuns() {
        // No ledger in the client (unlike a consent decision): stopping twice is harmless on the
        // server (session-manager.mjs stopCommand kills an existing handle or does nothing). The UI
        // makes ONE stop per command: its shared "Stopping…" latch disables every key for it.
        val (client, _) = connected()
        assertEquals(StopCommandResult.Sent, client.stopCommand("s1", "c-run", client.consentOrigin.value))
        assertEquals(StopCommandResult.Sent, client.stopCommand("s1", "c-run", client.consentOrigin.value))
        assertEquals(2, stopFrames().size)
    }
}
