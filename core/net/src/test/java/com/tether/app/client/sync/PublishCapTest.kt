package com.tether.app.client.sync

import com.tether.app.client.OutputIntakeCap
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.reduce.tree
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.js
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6.4 round 4, the output cap as SessionStore.publish applies it. B1: a publish costs only what
 * changed (100 commands × 65,536 one-character alternating segments stored, one output event per
 * publish: well under 1 ms). 5(a): the capped flag stays set across `finished` through publish.
 */
class PublishCapTest {
    private fun started(id: String) = evNullTurn("background_command_updated", ts = 1) {
        put("commandId", id); put("command", "yes"); put("cwd", "/w"); put("logFile", "/w/$id.log"); put("status", "running"); put("startedAt", 1)
    }.tree()

    private fun out(id: String, text: String, stream: String) = evNullTurn("background_command_output", ts = 2) {
        put("commandId", id); put("stream", stream); put("text", text)
    }.tree()

    private fun command(tree: JsObj, id: String) =
        (tree["backgroundCommands"] as JsArr).map { it as JsObj }.first { (it["commandId"] as JsStr).value == id }

    @Test fun theFlagStaysSetAcrossFinishedThroughPublish() {
        val store = SessionStore()
        var tree = reduce(freshTree(), started("c"))
        tree = store.publish("s1", tree, null)
        repeat(6) { i -> tree = store.publish("s1", reduce(tree, out("c", "#$i#" + "z".repeat(60_000), "stdout")), null) }
        assertEquals(JsBool.TRUE, command(store.tree("s1")!!, "c")["outputTruncated"])
        val finished = evNullTurn("background_command_updated", ts = 9) {
            put("commandId", "c"); put("command", "yes"); put("cwd", "/w"); put("logFile", "/w/c.log"); put("status", "finished")
            put("exitCode", 0); put("startedAt", 1); put("endedAt", 9)
        }.tree()
        val next = reduce(tree, finished)
        assertEquals("the server's update carries its own flag", JsBool.FALSE, command(next, "c")["outputTruncated"])
        val stored = store.publish("s1", next, null)
        assertSame(stored, store.tree("s1"))
        assertEquals("finished", (command(stored, "c")["status"] as JsStr).value)
        assertEquals(JsBool.TRUE, command(stored, "c")["outputTruncated"])
    }

    @Test fun aPublishCostsWhatChangedNotWhatIsStored() {
        // 100 commands, each 65,536 one-character segments alternating stdout/stderr: the heaviest
        // legal stream a conforming 64 KiB server can produce, times 100.
        val segments = JsArr.of((0 until 65_536).map { i -> JsObj.of("stream" to js(if (i % 2 == 0) "stdout" else "stderr"), "text" to js("x")) })
        var tree = freshTree()
        for (n in 0 until 100) tree = reduce(tree, started("c$n"))
        val commands = tree["backgroundCommands"] as JsArr
        tree = tree.put("backgroundCommands", JsArr.of(commands.map { (it as JsObj).put("segments", segments) }))
        val store = SessionStore()
        tree = store.publish("s1", tree, null) // the first publish caps everything once
        val first = command(tree, "c0")
        assertEquals(OutputIntakeCap.MAX_SEGMENTS, (first["segments"] as JsArr).size)
        assertEquals(JsBool.TRUE, first["outputTruncated"])
        // Steady state: one output event, one publish.
        val times = LongArray(400)
        for (k in times.indices) {
            val next = reduce(tree, out("c${k % 100}", "y", if (k % 2 == 0) "stdout" else "stderr"))
            val start = System.nanoTime()
            tree = store.publish("s1", next, null)
            times[k] = System.nanoTime() - start
        }
        val measured = times.drop(100).sorted()
        val medianMs = measured[measured.size / 2] / 1e6
        println("PUBLISH_MEDIAN_MS $medianMs p90=${measured[measured.size * 9 / 10] / 1e6}")
        assertTrue("median publish $medianMs ms", medianMs < 1.0)
        assertTrue((command(tree, "c7")["segments"] as JsArr).size <= OutputIntakeCap.MAX_SEGMENTS)
    }

    @Test fun emptyAndMalformedSegmentsAreDropped() {
        val tree = reduce(freshTree(), started("c"))
        val bad = tree.put(
            "backgroundCommands",
            JsArr.of(command(tree, "c").put("segments", JsArr.of(JsObj.of("stream" to js("stdout"), "text" to js("")), js(7), JsObj.of("stream" to js("stdout"), "text" to js("ok"))))),
        )
        val stored = SessionStore().publish("s1", bad, null)
        val kept = command(stored, "c")["segments"] as JsArr
        assertEquals(1, kept.size)
        assertEquals("ok", ((kept[0] as JsObj)["text"] as JsStr).value)
        assertEquals("nothing was cut, so not truncated", JsBool.FALSE, command(stored, "c")["outputTruncated"])
    }
}
