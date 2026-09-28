package com.tether.app.ui.chat

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import java.io.File
import kotlinx.serialization.json.put

/**
 * T6.2 transcript fixtures with tool cards, folded by the real v128 reducer. `tools` and
 * `codexTools` are the S0.4 seeded web scenarios verbatim (tether scripts/parity-seed.mjs:192-228,
 * sent at 03:01 / 04:01 UTC); the rest come from the vendored reducer corpus or are built to show
 * a state the seeder cannot freeze (a running tool with live output).
 */
object ToolFixtures {
    const val T_TOOLS = 10_860_000L // 03:01 UTC
    const val T_CODEX = 14_460_000L // 04:01 UTC
    const val T_RUNNING = 60_000L

    private fun json(text: String) = TetherJson.parseToJsonElement(text)

    private class Script(val turnId: String, val ts: Long) {
        val events = ArrayList<AgentEvent>()
        private var n = 0

        fun start(prompt: String) {
            events += ev("turn_started", turnId, ts = ts) { put("idempotencyKey", "k-$turnId") }
            events += ev("user_message_accepted", turnId, ts = ts) { put("text", prompt) }
        }

        fun tool(name: String, input: String, output: String? = null, isError: Boolean = false, done: Boolean = true, id: String = "$turnId:tool${n++}") {
            events += ev("tool_start", turnId, ts = ts) {
                put("toolId", id)
                put("name", name)
                put("input", json(input))
            }
            if (done) {
                events += ev("tool_end", turnId, ts = ts) {
                    put("toolId", id)
                    if (output != null) put("output", json(output))
                    put("isError", isError)
                }
            }
        }

        fun message(text: String) {
            val id = "$turnId:m${n++}"
            events += ev("message_started", turnId, ts = ts) { put("blockId", id) }
            events += ev("message_completed", turnId, ts = ts) { put("blockId", id); put("text", text) }
        }

        fun end() {
            events += ev("turn_end", turnId, ts = ts) { put("outcome", "ok") }
        }
    }

    private fun q(s: String) = TetherJson.encodeToString(kotlinx.serialization.json.JsonPrimitive(s))

    /** The seeder's `fixturePng()` as the server journals it: a content-addressed media_ref. */
    const val CHART_URL = "/api/tool-media/5c0e9a2b7d4f61e8a3b2c1d0e9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0.png"

    /** tool-cards: Read/Glob/Grep/Bash/Edit/MultiEdit/Write/WebFetch/TodoWrite, a failed Bash, an MCP tool with an image. */
    val tools: ChatFixtures.Folded by lazy {
        val s = Script("t1", T_TOOLS)
        s.start("Apply the config edits and verify.")
        s.tool("Read", """{"file_path":"src/config.ts"}""", q("export const config = { retries: 3 };\n"))
        s.tool("Glob", """{"pattern":"src/**/*.ts"}""", q("src/index.ts\nsrc/config.ts\n"))
        s.tool("Grep", """{"pattern":"retries","path":"src"}""", q("src/config.ts:1:export const config = { retries: 3 };\n"))
        s.tool("Bash", """{"command":"npm test","description":"Run the tests"}""", q("> parity-app@1.0.0 test\n> node --test\n\n# tests 4\n# pass 4\n# fail 0\n"))
        s.tool(
            "Edit",
            """{"file_path":"src/config.ts","old_string":"export const config = { retries: 3 };","new_string":"export const config = { retries: 5, backoffMs: 250 };"}""",
            q("The file src/config.ts has been updated."),
        )
        s.tool(
            "MultiEdit",
            """{"file_path":"src/index.ts","edits":[{"old_string":"export function greet(name: string) {","new_string":"export function greet(name: string, punctuation = \"!\") {"},{"old_string":"  return `Hello, ${'$'}{name}!`;","new_string":"  return `Hello, ${'$'}{name}${'$'}{punctuation}`;"}]}""",
            q("Applied 2 edits to src/index.ts"),
        )
        s.tool("Write", """{"file_path":"src/version.ts","content":"export const VERSION = \"1.1.0\";\nexport const CODENAME = \"parity\";\n"}""", q("File created successfully at: src/version.ts"))
        s.tool("WebFetch", """{"url":"https://example.test/changelog","prompt":"Summarize the latest entry"}""", q("The latest entry adds retry backoff."))
        s.tool(
            "TodoWrite",
            """{"todos":[{"content":"Inspect the project","status":"completed","activeForm":"Inspecting"},{"content":"Verify","status":"in_progress","activeForm":"Verifying"}]}""",
            q("Todos have been modified successfully."),
        )
        s.tool("Bash", """{"command":"npm run lint","description":"Lint"}""", q("error  'unused' is defined but never used  no-unused-vars\n1 problem"), isError = true)
        s.tool(
            "mcp__parity__render_chart",
            """{"series":[3,5,8]}""",
            """[{"type":"text","text":"Rendered the chart."},{"type":"media_ref","mediaKind":"image","mediaType":"image/png","url":"$CHART_URL","bytes":163}]""",
        )
        s.message("All edits are in; one lint error remains.")
        s.end()
        ChatFixtures.fold(*s.events.toTypedArray())
    }

    /** codex-tool-cards: command_execution, file_change (diff), mcp:, collaboration:, subagent_activity, a failed command. */
    val codexTools: ChatFixtures.Folded by lazy {
        val s = Script("t1", T_CODEX)
        s.start("Show one of each tool card.")
        s.tool("command_execution", """{"command":"npm test","cwd":"~/parity-app"}""", """{"text":"# tests 4\n# pass 4\n","exitCode":0,"status":"completed"}""")
        val change = """{"path":"src/config.ts","kind":"update","diff":"@@ -1 +1 @@\n-export const config = { retries: 3 };\n+export const config = { retries: 5 };\n"}"""
        s.tool("file_change", """{"changes":[$change]}""", """{"status":"completed","changes":[$change]}""")
        s.tool("mcp:docs/search", """{"server":"docs","tool":"search","arguments":{"query":"retry backoff"}}""", """{"status":"completed","result":{"content":[{"type":"text","text":"3 results: retry.md, backoff.md, faq.md"}]}}""")
        s.tool(
            "collaboration:spawn_agent",
            """{"prompt":"Review the retry change","model":"gpt-5","reasoningEffort":"medium","receiverThreadIds":["thread-reviewer"]}""",
            """{"status":"completed","agentsStates":{"thread-reviewer":"completed"}}""",
        )
        s.tool("subagent_activity", """{"kind":"started","agentPath":"reviewer"}""", """{"kind":"completed","agentPath":"reviewer"}""")
        s.tool("command_execution", """{"command":"npm run lint"}""", """{"text":"1 problem","exitCode":1,"status":"failed"}""", isError = true)
        s.message("Codex-style tool cards, one of each kind.")
        s.end()
        ChatFixtures.fold(*s.events.toTypedArray())
    }

    /** Live: a Codex command streaming output, then a Claude Bash still running at 12s. */
    val running: ChatFixtures.Folded by lazy {
        val s = Script("t1", T_RUNNING)
        s.start("Run the suites.")
        s.tool("command_execution", """{"command":"npm test -- --watch=false","cwd":"~/parity-app"}""", done = false, id = "cmd-live")
        s.events += ev("tool_output_delta", "t1", ts = T_RUNNING) { put("toolId", "cmd-live"); put("chunk", "# Subtest: retry\nok 1 - backs off\n") }
        s.tool("Bash", """{"command":"npm run e2e","description":"End-to-end"}""", done = false, id = "bash-live")
        s.events += ev("tool_progress", "t1", ts = T_RUNNING) { put("toolId", "bash-live"); put("elapsedSeconds", 12) }
        ChatFixtures.fold(*s.events.toTypedArray())
    }

    /**
     * A Codex turn's rich details (codex-rich-renderers.tsx `CodexRichTurnDetails`): the first plan,
     * the aggregate turn diff (no inline file_change diffs, e.g. a `git apply`), a completed and a
     * failed review — the corpus's plan-diff-reroute-review-compaction events around a real prompt.
     */
    val codexDetails: ChatFixtures.Folded by lazy {
        val s = Script("t1", T_CODEX)
        s.start("Apply the patch and review it.")
        s.events += ev("plan_updated", "t1", ts = T_CODEX) {
            put("explanation", "Initial plan")
            put("steps", json("""[{"status":"completed","step":"Read code"},{"status":"in_progress","step":"Apply the patch"},{"status":"pending","step":"Review"}]"""))
        }
        s.tool("command_execution", """{"command":"git apply fix.patch","cwd":"~/parity-app"}""", """{"text":"","exitCode":0,"status":"completed","durationMs":180}""")
        s.events += ev("diff_updated", "t1", ts = T_CODEX) {
            put("unifiedDiff", "diff --git a/src/config.ts b/src/config.ts\n--- a/src/config.ts\n+++ b/src/config.ts\n@@ -1,2 +1,2 @@\n-export const config = { retries: 3 };\n+export const config = { retries: 5 };\n export default config;\n")
        }
        s.events += ev("review_started", "t1", ts = T_CODEX) { put("reviewId", "rev-1"); put("target", "uncommitted changes") }
        s.events += ev("review_completed", "t1", ts = T_CODEX) { put("reviewId", "rev-1"); put("status", "completed"); put("result", "LGTM: the retry bump is covered by the tests.") }
        s.events += ev("review_started", "t1", ts = T_CODEX) { put("reviewId", "rev-2"); put("target", "base branch") }
        s.events += ev("review_completed", "t1", ts = T_CODEX) { put("reviewId", "rev-2"); put("status", "failed") }
        s.message("Patched and reviewed.")
        s.end()
        ChatFixtures.fold(*s.events.toTypedArray())
    }

    /** opencode: the `task` card with its `<task_result>` envelope unwrapped, and a running one. */
    val opencodeTask: ChatFixtures.Folded by lazy {
        val s = Script("t1", T_RUNNING)
        s.start("Delegate the survey.")
        s.tool(
            "task",
            """{"description":"Survey the tests","prompt":"List the test files and what they cover","subagent_type":"general"}""",
            q("<task id=\"ses_1\" state=\"completed\">\n<task_result>\nTwo test files: greeting and config.\n</task_result>\n</task>"),
        )
        s.tool("task", """{"description":"Check the changelog","prompt":"Fetch the changelog","subagent_type":"explore"}""", done = false, id = "task-live")
        ChatFixtures.fold(*s.events.toTypedArray())
    }

    private fun corpusDir(): File {
        val prop = System.getProperty("parity.corpus")
        return File(prop ?: "../../parity-corpus").resolve("reducer")
    }

    /** The final projection of a vendored reducer-corpus case, as the client would hold it. */
    fun corpusFinal(name: String): ChatFixtures.Folded {
        val doc = JsCodec.parse(corpusDir().resolve("$name.json").readText()) as JsObj
        val steps = doc["expectedProjectionAfterEachStep"] as com.tether.app.protocol.tree.JsArr
        val tree = steps.last() as JsObj
        return ChatFixtures.Folded(checkNotNull(LegacyProjectionAdapter.adaptOnce(tree)), tree)
    }

    /** A deterministic 48×32 checker (the seeder's fixturePng tiles) for any picture. */
    fun checker(width: Int = 48, height: Int = 32): androidx.compose.ui.graphics.ImageBitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) {
            val tile = ((x / 8) + (y / 8)) % 2
            bitmap.setPixel(x, y, if (tile == 1) 0xFF5C6EE6.toInt() else 0xFFECEEF4.toInt())
        }
        return bitmap.asImageBitmap()
    }

    /** Resolves every picture to [checker]; clips never load. Counts loads per URL. */
    class FakeLoader(private val image: MediaImage = MediaImage.Ok(checker())) : ToolMediaLoader {
        val loads = mutableListOf<String>()
        override suspend fun image(item: ToolMediaItem): MediaImage {
            loads += item.src
            return image
        }

        override suspend fun video(item: ToolMediaItem): MediaVideo = MediaVideo.Failed
    }
}
