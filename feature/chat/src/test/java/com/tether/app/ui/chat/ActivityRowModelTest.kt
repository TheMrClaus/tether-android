package com.tether.app.ui.chat

import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-a5jl A8: every name of the kind table maps to its verb, glyph and one-line argument; the state follows the block. */
class ActivityRowModelTest {
    private fun tool(name: String, input: String = "{}", extra: String = """"done":true"""): JsObj =
        JsCodec.parse("""{"blockId":"b","kind":"tool","name":"$name","input":$input,$extra}""") as JsObj

    private fun row(name: String, input: String = "{}", extra: String = """"done":true""") = activityRowModel(tool(name, input, extra))

    private fun check(model: ActivityRowModel, verb: String, glyph: ActivityGlyph, arg: String) {
        assertEquals(verb, model.verb)
        assertEquals(glyph, model.glyph)
        assertEquals(arg, model.arg)
    }

    @Test fun everyNameOfTheTableMapsToItsVerbGlyphAndArgument() {
        check(row("Bash", """{"command":"git status"}"""), "Shell", ActivityGlyph.SquareTerminal, "git status")
        check(row("command_execution", """{"command":"npm test"}"""), "Shell", ActivityGlyph.SquareTerminal, "npm test")
        check(row("BashOutput", """{"bash_id":"bg_1"}"""), "Shell", ActivityGlyph.SquareTerminal, "bg_1")
        check(row("KillShell", """{"shell_id":"sh_9"}"""), "Shell", ActivityGlyph.SquareTerminal, "sh_9")
        check(row("Read", """{"file_path":"/a/b.kt"}"""), "Read", ActivityGlyph.Eye, "/a/b.kt")
        check(row("Edit", """{"file_path":"/a/b.kt","old_string":"x","new_string":"y"}"""), "Edit", ActivityGlyph.Pencil, "/a/b.kt")
        check(row("MultiEdit", """{"file_path":"/a/c.kt"}"""), "Edit", ActivityGlyph.Pencil, "/a/c.kt")
        check(row("NotebookEdit", """{"notebook_path":"/n.ipynb"}"""), "Edit", ActivityGlyph.Pencil, "/n.ipynb")
        check(row("Write", """{"file_path":"/a/new.kt","content":"c"}"""), "Write", ActivityGlyph.FilePlus, "/a/new.kt")
        check(row("Grep", """{"pattern":"retries","path":"src"}"""), "Search", ActivityGlyph.Search, "retries  src")
        check(row("Glob", """{"pattern":"src/**/*.ts"}"""), "Find", ActivityGlyph.FolderOpen, "src/**/*.ts")
        check(row("WebSearch", """{"query":"compose flow row"}"""), "Search", ActivityGlyph.Search, "compose flow row")
        check(row("WebFetch", """{"url":"https://x.test/a"}"""), "Fetch", ActivityGlyph.Network, "https://x.test/a")
        check(row("Task", """{"description":"Survey tests","prompt":"long prompt"}"""), "Agent", ActivityGlyph.Bot, "Survey tests")
        check(row("Task", """{"prompt":"only a prompt"}"""), "Agent", ActivityGlyph.Bot, "only a prompt")
        check(row("Agent", """{"description":"d"}"""), "Agent", ActivityGlyph.Bot, "d")
        check(row("TaskCreate", """{"description":"t"}"""), "Agent", ActivityGlyph.Bot, "t")
        check(row("task", """{"description":"oc"}"""), "Agent", ActivityGlyph.Bot, "oc")
        check(row("subagent_activity", """{"kind":"started","agentPath":"reviewer"}""", """"done":false"""), "Agent", ActivityGlyph.Bot, "reviewer")
        check(row("TodoWrite", """{"todos":[]}"""), "Plan", ActivityGlyph.ListTodo, "")
        check(row("ExitPlanMode"), "Plan", ActivityGlyph.ListTodo, "")
        check(row("mcp:docs/search", """{"server":"docs","tool":"search"}"""), "MCP", ActivityGlyph.Server, "docs · search")
        check(row("mcp:docs/search"), "MCP", ActivityGlyph.Server, "docs · search")
        check(row("collaboration:spawn_agent"), "Agents", ActivityGlyph.Boxes, "spawn_agent")
    }

    @Test fun aCodexFileChangeNamesItsFirstPathAndHowManyMoreFollow() {
        val two = """{"changes":[{"path":"/a/one.kt","kind":"update","diff":"d"},{"path":"/a/two.kt","kind":"add","diff":"d"},{"path":"/a/three.kt","kind":"add","diff":"d"}]}"""
        check(row("file_change", two), "Edit", ActivityGlyph.FileDiff, "/a/one.kt +2")
        check(row("file_change", """{"changes":[{"path":"/a/one.kt","kind":"update","diff":"d"}]}"""), "Edit", ActivityGlyph.FileDiff, "/a/one.kt")
    }

    @Test fun anUnknownNameIsAToolWithItsNameAndCompactInput() {
        check(row("Frobnicate", """{"alpha":"one","beta":2}"""), "Tool", ActivityGlyph.Wrench, "Frobnicate  alpha: one  beta: 2")
        check(row("Frobnicate"), "Tool", ActivityGlyph.Wrench, "Frobnicate")
    }

    @Test fun globIsFindNotRead() {
        assertEquals("Find", row("Glob", """{"pattern":"*.kt"}""").verb)
        assertEquals("Read", row("Read", """{"file_path":"a"}""").verb)
    }

    @Test fun theArgumentIsOneLineTrimmedAndCapped() {
        val command = "  echo   one\n\n  two\t three  "
        assertEquals("echo one two three", row("Bash", """{"command":${JsCodec.stringify(com.tether.app.protocol.tree.JsStr(command))}}""").arg)
        val long = "x".repeat(500)
        val capped = row("Bash", """{"command":"$long"}""").arg
        assertEquals(ACTIVITY_ARG_MAX, capped.length)
        assertTrue(capped.all { it == 'x' })
    }

    @Test fun theStateAndItsWordsFollowTheBlock() {
        val running = row("Bash", """{"command":"sleep 9"}""", """"done":false,"elapsedSeconds":12""")
        assertEquals(ToolState.Running, running.state)
        assertEquals("running · 12s", running.words)
        assertEquals("Shell sleep 9, running, 12s", running.label)
        val error = row("Bash", """{"command":"false"}""", """"done":true,"isError":true""")
        assertEquals(ToolState.Error, error.state)
        assertEquals("error", error.words)
        assertEquals("Shell false, error", error.label)
        val stopped = row("Bash", """{"command":"x"}""", """"done":true,"interrupted":true""")
        assertEquals(ToolState.Interrupted, stopped.state)
        assertEquals("interrupted", stopped.words)
        val done = row("Bash", """{"command":"ls"}""")
        assertEquals(ToolState.Done, done.state)
        assertEquals("", done.words)
        assertEquals("Shell ls, done", done.label)
    }

    @Test fun aThinkingRowHasNoArgumentAndRunsWhileItStreams() {
        val streaming = thinkingRowModel(com.tether.app.protocol.model.TurnBlock("t", "thinking", text = "hm", done = false))
        assertEquals(ToolState.Running, streaming.state)
        val finished = thinkingRowModel(com.tether.app.protocol.model.TurnBlock("t", "thinking", text = "hm", done = true))
        check(finished, "Thinking", ActivityGlyph.Brain, "")
        assertEquals("Thinking, done", finished.label)
    }
}
