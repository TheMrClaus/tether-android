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
 * T6.4 (L4): the client caps background commands' folded output in every tree it publishes, AFTER
 * the fold (the reducer stays line-for-line with the web's, which does not cap). A conforming
 * server (64 KiB per command) never reaches the cap; past it, the newest [OutputIntakeCap.MAX_CHARS]
 * are kept and the command reads `outputTruncated`, which then never goes back to false.
 */
class OutputIntakeCapTest {
    private fun started(id: String = "c") = evNullTurn("background_command_updated", ts = 1) {
        put("commandId", id); put("command", "yes"); put("cwd", "/w"); put("logFile", "/w/c.log"); put("status", "running"); put("startedAt", 1)
    }.tree()

    private fun finished(id: String = "c") = evNullTurn("background_command_updated", ts = 9) {
        put("commandId", id); put("command", "yes"); put("cwd", "/w"); put("logFile", "/w/c.log"); put("status", "finished")
        put("exitCode", 0); put("startedAt", 1); put("endedAt", 9)
    }.tree()

    private fun out(text: String, stream: String = "stdout", id: String = "c") = evNullTurn("background_command_output", ts = 2) {
        put("commandId", id); put("stream", stream); put("text", text)
    }.tree()

    /** One publish: the fold, then the cap against the tree it replaces (SessionStore.publish). */
    private fun fold(tree: JsObj, event: JsObj, max: Int) = OutputIntakeCap.capTree(tree, reduce(tree, event), max)

    private fun command(tree: JsObj) = (tree["backgroundCommands"] as JsArr)[0] as JsObj

    private fun text(tree: JsObj) = (command(tree)["segments"] as JsArr).joinToString("") { ((it as JsObj)["text"] as JsStr).value }

    @Test fun underTheCapTheTreeIsUntouched() {
        val tree = reduce(freshTree(), started())
        val folded = reduce(tree, out("x".repeat(100)))
        assertSame(folded, OutputIntakeCap.capTree(tree, folded, max = 1_000))
        assertEquals(JsBool.FALSE, command(folded)["outputTruncated"])
    }

    @Test fun pastTheCapTheNewestOutputIsKeptAndMarkedTruncated() {
        var tree = reduce(freshTree(), started())
        for (i in 0 until 50) tree = fold(tree, out("line $i\n", if (i % 2 == 0) "stdout" else "stderr"), max = 100)
        assertTrue(text(tree).length <= 100)
        assertTrue(text(tree).endsWith("line 49\n"))
        assertEquals(JsBool.TRUE, command(tree)["outputTruncated"])
    }

    @Test fun theCutNeverSplitsASurrogatePair() {
        var tree = reduce(freshTree(), started())
        tree = fold(tree, out("😀".repeat(40)), max = 11)
        val kept = text(tree)
        assertTrue(!Character.isLowSurrogate(kept[0]))
        assertEquals(10, kept.length)
    }

    @Test fun anIdLongerThanTheFoldsBoundIsCappedAsTheFoldStoredIt() {
        // L-1: the fold bounds ids (boundedIdentifier); the cap scans the stored commands, so a
        // 201-character id cannot slip past it.
        val id = "i".repeat(201)
        var tree = reduce(freshTree(), started(id))
        for (i in 0 until 20) tree = fold(tree, out("chunk $i ".repeat(10), id = id), max = 200)
        assertTrue(text(tree).length <= 200)
        assertEquals(JsBool.TRUE, command(tree)["outputTruncated"])
    }

    @Test fun theClientsTruncatedFlagSurvivesTheNextStatusUpdate() {
        // L-2: `background_command_updated` merges only the segments and carries the server's
        // own flag (false); the client's trimmed output still reads truncated.
        var tree = reduce(freshTree(), started())
        for (i in 0 until 20) tree = fold(tree, out("x".repeat(50)), max = 100)
        assertEquals(JsBool.TRUE, command(tree)["outputTruncated"])
        tree = fold(tree, finished(), max = 100)
        assertEquals("finished", (command(tree)["status"] as JsStr).value)
        assertEquals(JsBool.TRUE, command(tree)["outputTruncated"])
    }

    @Test fun treesWithoutCommandsAreUntouched() {
        val tree = freshTree()
        assertSame(tree, OutputIntakeCap.capTree(null, tree, max = 0))
    }
}
