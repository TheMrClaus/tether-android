package com.tether.app.client

import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.reduce.tree
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6.4 (L4): the client caps a background command's folded output at intake, AFTER the fold (the
 * reducer stays line-for-line with the web's, which does not cap). A conforming server (64 KiB per
 * command) never reaches the cap; past it, the newest [OutputIntakeCap.MAX_CHARS] are kept and the
 * command reads `outputTruncated`.
 */
class OutputIntakeCapTest {
    private val started = evNullTurn("background_command_updated", ts = 1) {
        put("commandId", "c"); put("command", "yes"); put("cwd", "/w"); put("logFile", "/w/c.log"); put("status", "running"); put("startedAt", 1)
    }.tree()

    private fun out(text: String, stream: String = "stdout") = evNullTurn("background_command_output", ts = 2) {
        put("commandId", "c"); put("stream", stream); put("text", text)
    }.tree()

    private fun fold(tree: JsObj, event: JsObj, max: Int) = OutputIntakeCap.apply(reduce(tree, event), event, max)

    private fun command(tree: JsObj) = (tree["backgroundCommands"] as JsArr)[0] as JsObj

    private fun text(tree: JsObj) = (command(tree)["segments"] as JsArr).joinToString("") { ((it as JsObj)["text"] as JsStr).value }

    @Test fun underTheCapTheFoldIsUntouched() {
        var tree = reduce(freshTree(), started)
        val event = out("x".repeat(100))
        val folded = reduce(tree, event)
        assertSame(folded, OutputIntakeCap.apply(folded, event, max = 1_000))
        tree = folded
        assertEquals(false, (command(tree)["outputTruncated"] as JsBool).value)
    }

    @Test fun pastTheCapTheNewestOutputIsKeptAndMarkedTruncated() {
        var tree = reduce(freshTree(), started)
        for (i in 0 until 50) tree = fold(tree, out("line $i\n", if (i % 2 == 0) "stdout" else "stderr"), max = 100)
        assertTrue(text(tree).length <= 100)
        assertTrue(text(tree).endsWith("line 49\n"))
        assertEquals(true, (command(tree)["outputTruncated"] as JsBool).value)
    }

    @Test fun theCutNeverSplitsASurrogatePair() {
        var tree = reduce(freshTree(), started)
        tree = fold(tree, out("😀".repeat(40)), max = 11)
        val kept = text(tree)
        assertTrue(!Character.isLowSurrogate(kept[0]))
        assertEquals(10, kept.length)
    }

    @Test fun otherEventsAndOtherCommandsAreUntouched() {
        val tree = reduce(freshTree(), started)
        assertSame(tree, OutputIntakeCap.apply(tree, started, max = 0))
        val stray = evNullTurn("background_command_output", ts = 3) { put("commandId", "nope"); put("text", "x") }.tree()
        assertSame(tree, OutputIntakeCap.apply(tree, stray, max = 0))
    }
}
