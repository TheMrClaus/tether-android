package com.tether.app.client.sync

import com.tether.app.client.OutputIntakeCap
import com.tether.app.client.snapshotFrame
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.tether.app.mirror.SessionBaseEntity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T6.4 round 4 (B2): a command the client capped still reads `outputTruncated` after the process
 * restarts from a local checkpoint that holds exactly the capped output and a later `finished`
 * update (which carries the server's own flag, false). The flag is the server's OR the stored
 * output being at the cap, so it does not depend on any previous tree.
 */
@RunWith(RobolectricTestRunner::class)
class OutputCapRestartTest {
    private val h = MirrorHarness(checkpointEvery = 3)

    @After
    fun tearDown() = h.close()

    private val ready =
        """{"type":"ready","protocolVersion":143,"nativeProtocolFloor":129,"providers":[],"workspaceRoot":null,
            "sessions":[{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"ready",
            "startedAt":1,"updatedAt":1,"pinned":false,"runtimeArchived":false,"mode":"headless"}]}"""

    private fun event(seq: Long, type: String, fields: String) =
        """{"type":"event","sessionId":"s1","event":{"type":"$type","turnId":null,"seq":$seq,"ts":$seq$fields}}"""

    private fun command(tree: JsObj): JsObj = ((tree["backgroundCommands"] as JsArr).single() as JsObj)

    private fun keptChars(command: JsObj) = (command["segments"] as JsArr).sumOf { (((it as JsObj)["text"]) as JsStr).value.length }

    @Test fun anEmojiFloodKeepsItsFlagLiveAndAfterARestart() {
        // Round 5: 5 x 30,000 U+1F600 then "x": the cut steps past a surrogate pair (MAX_CHARS - 1 kept).
        h.startServer()
        h.boot(ready = ready)
        h.client.attach("s1")
        h.expectFrame("attach")
        val running = foldTree(freshTree(), evNullTurn("background_command_updated", ts = 1) {
            put("commandId", "c"); put("command", "yes"); put("cwd", "/w"); put("logFile", "/w/c.log"); put("status", "running"); put("startedAt", 1)
        })
        h.ws.send(snapshotFrame("s1", 5, JsCodec.stringify(running)))
        h.await(h.client.projectionTrees) { it.containsKey("s1") }
        var seq = 5L
        val emoji = "\\uD83D\\uDE00".repeat(30_000) // JSON escapes: the wire carries the pairs
        repeat(5) { h.ws.send(event(++seq, "background_command_output", ""","commandId":"c","stream":"stdout","text":"$emoji"""")) }
        h.ws.send(event(++seq, "background_command_output", ""","commandId":"c","stream":"stderr","text":"x""""))
        h.ws.send(event(++seq, "background_command_updated", ""","commandId":"c","command":"yes","cwd":"/w","logFile":"/w/c.log","status":"finished","exitCode":0,"startedAt":1,"endedAt":$seq"""))
        val live = h.await(h.client.projectionTrees) { trees -> trees["s1"]?.let { (command(it)["status"] as? JsStr)?.value } == "finished" }.getValue("s1")
        assertEquals(OutputIntakeCap.MAX_CHARS - 1, keptChars(command(live)))
        assertEquals("live", JsBool.TRUE, command(live)["outputTruncated"])
        h.kill(flushFirst = true)
        h.boot(ready = ready)
        h.client.attach("s1")
        val restored = h.await(h.client.projectionTrees) { it.containsKey("s1") }.getValue("s1")
        assertEquals("finished", (command(restored)["status"] as JsStr).value)
        assertEquals("after the restart", JsBool.TRUE, command(restored)["outputTruncated"])
    }

    @Test fun theCappedFlagSurvivesACheckpointAFinishedUpdateAndARestart() {
        h.startServer()
        h.boot(ready = ready)
        h.client.attach("s1")
        h.expectFrame("attach")
        val running = foldTree(freshTree(), evNullTurn("background_command_updated", ts = 1) {
            put("commandId", "c"); put("command", "yes"); put("cwd", "/w"); put("logFile", "/w/c.log"); put("status", "running"); put("startedAt", 1)
        })
        h.ws.send(snapshotFrame("s1", 5, JsCodec.stringify(running)))
        h.await(h.client.projectionTrees) { it.containsKey("s1") }
        var seq = 5L
        repeat(6) { i -> h.ws.send(event(++seq, "background_command_output", ""","commandId":"c","stream":"stdout","text":"#$i#${"z".repeat(60_000)}"""")) }
        // Small events after the cap: a checkpoint (every 3) lands holding exactly MAX_CHARS.
        repeat(3) { h.ws.send(event(++seq, "background_command_output", ""","commandId":"c","stream":"stderr","text":"tail $it\n"""")) }
        h.ws.send(event(++seq, "background_command_updated", ""","commandId":"c","command":"yes","cwd":"/w","logFile":"/w/c.log","status":"finished","exitCode":0,"startedAt":1,"endedAt":$seq"""))
        val live = h.await(h.client.projectionTrees) { trees -> trees["s1"]?.let { (command(it)["status"] as? JsStr)?.value } == "finished" }.getValue("s1")
        assertEquals(JsBool.TRUE, command(live)["outputTruncated"])
        assertEquals(OutputIntakeCap.MAX_CHARS, keptChars(command(live)))

        // Low-1: checkpoints keep landing while the cap acts (they compare the STORED tree), so the
        // mirrored tail stays short and the base is a local checkpoint past the flood.
        kotlinx.coroutines.runBlocking { h.mirror.flush() }
        val saved = checkNotNull(h.dbSession("s1"))
        assertEquals(SessionBaseEntity.ORIGIN_LOCAL, saved.origin)
        assertTrue("tail ${saved.tail.size} after base ${saved.throughSeq}", saved.tail.size < 3 && saved.throughSeq >= seq - 3)
        h.kill(flushFirst = true)
        // Restart; nobody answers the attach, so what the screens show is the mirrored copy.
        h.boot(ready = ready)
        h.client.attach("s1")
        val restored = h.await(h.client.projectionTrees) { it.containsKey("s1") }.getValue("s1")
        assertEquals("finished", (command(restored)["status"] as JsStr).value)
        assertEquals(OutputIntakeCap.MAX_CHARS, keptChars(command(restored)))
        assertEquals("still reads truncated after the restart", JsBool.TRUE, command(restored)["outputTruncated"])
    }
}
