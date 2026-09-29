package com.tether.app.ui.chat

import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * T6.4 fixtures, each folded by the real v128 reducer from the events the server journals:
 * sub-agent threads (`subagent_message`), v117 background tasks, v122 spawned runs, v54/v55
 * background commands, the v56 todo list and a Codex plan.
 */
object SubagentFixtures {
    const val T_SUB = 18_060_000L // 05:01 UTC, the web seeder's subagents session

    val session = AgentSession(id = "s1", provider = "claude", name = "Review with sub-agents", cwd = "/w", status = "ready", startedAt = 0, updatedAt = 0)

    private fun JsonObjectBuilder.item(key: String, kind: String, block: JsonObjectBuilder.() -> Unit = {}) {
        put("key", key); put("kind", kind); block()
    }

    /**
     * The web seeder's `subagents` script (parity-seed.mjs 236-261): two Agent runs, one done
     * (thinking, a Glob and its result, a message), one failed (a WebFetch that hit a 403).
     */
    val web: ChatFixtures.Folded by lazy {
        val t = T_SUB
        ChatFixtures.fold(
            ev("turn_started", "t1", ts = t) { put("idempotencyKey", "k-t1") },
            ev("user_message_accepted", "t1", ts = t) { put("text", "Review the change with two sub-agents.") },
            ev("message_started", "t1", ts = t) { put("blockId", "t1:m0") },
            ev("message_completed", "t1", ts = t) { put("blockId", "t1:m0"); put("text", "Splitting the review across two sub-agents.") },
            ev("tool_start", "t1", ts = t) {
                put("toolId", "toolu_a"); put("name", "Agent")
                putJsonObject("input") { put("description", "Survey the tests"); put("prompt", "List the test files and what they cover"); put("subagent_type", "general-purpose") }
            },
            ev("subagent_message", "t1", ts = t) {
                put("parentToolUseId", "toolu_a")
                putJsonArray("items") {
                    addJsonObject { item("sa-1", "thinking") { put("text", "Looking for test files.") } }
                    addJsonObject { item("sa-2", "tool") { put("name", "Glob"); putJsonObject("input") { put("pattern", "**/*.test.ts") } } }
                }
            },
            ev("subagent_message", "t1", ts = t) {
                put("parentToolUseId", "toolu_a")
                putJsonArray("items") {
                    addJsonObject { item("sa-2", "tool_result") { put("output", "src/index.test.ts\nsrc/config.test.ts"); put("isError", false) } }
                    addJsonObject { item("sa-3", "message") { put("text", "Two test files: greeting and config.") } }
                }
            },
            ev("tool_end", "t1", ts = t) { put("toolId", "toolu_a"); put("output", "Two test files: greeting and config."); put("isError", false) },
            ev("tool_start", "t1", ts = t) {
                put("toolId", "toolu_b"); put("name", "Agent")
                putJsonObject("input") { put("description", "Check the changelog"); put("prompt", "Fetch the changelog"); put("subagent_type", "general-purpose") }
            },
            ev("subagent_message", "t1", ts = t) {
                put("parentToolUseId", "toolu_b")
                putJsonArray("items") {
                    addJsonObject { item("sb-1", "tool") { put("name", "WebFetch"); putJsonObject("input") { put("url", "https://example.test/changelog") } } }
                    addJsonObject { item("sb-1", "tool_result") { put("output", "403 Forbidden"); put("isError", true) } }
                }
            },
            ev("tool_end", "t1", ts = t) { put("toolId", "toolu_b"); put("output", "Could not fetch the changelog (403)."); put("isError", true) },
            ev("message_started", "t1", ts = t) { put("blockId", "t1:m1") },
            ev("message_completed", "t1", ts = t) { put("blockId", "t1:m1"); put("text", "One sub-agent finished; the other hit a 403.") },
            ev("turn_end", "t1", ts = t) { put("outcome", "ok") },
        )
    }

    /**
     * A running turn with a background Agent launch (acknowledged, its task still live), a
     * running sub-agent with a streaming step, a spawned Codex child (running, live output, the
     * picture it was handed) and a discovered run linked by its marker.
     */
    val running: ChatFixtures.Folded by lazy {
        val t = T_SUB
        ChatFixtures.fold(
            ev("turn_started", "t1", ts = t) { put("idempotencyKey", "k-t1") },
            ev("user_message_accepted", "t1", ts = t) { put("text", "Fix the flaky test and survey the rest.") },
            ev("tool_start", "t1", ts = t) {
                put("toolId", "toolu_bg"); put("name", "Agent")
                putJsonObject("input") { put("description", "Watch the CI"); put("prompt", "Tell me when CI is green"); put("run_in_background", true) }
            },
            ev("tool_end", "t1", ts = t) { put("toolId", "toolu_bg"); put("output", "Async agent launched"); put("isError", false) },
            evNullTurn("task_started", ts = t) { put("taskId", "task-1"); put("toolUseId", "toolu_bg"); put("taskType", "local_agent") },
            evNullTurn("background_tasks_changed", ts = t) { putJsonArray("taskIds") { add("task-1") } },
            ev("tool_start", "t1", ts = t) {
                put("toolId", "toolu_live"); put("name", "Agent")
                putJsonObject("input") { put("description", "Survey the tests"); put("prompt", "List the test files"); put("subagent_type", "explore") }
            },
            ev("subagent_message", "t1", ts = t) {
                put("parentToolUseId", "toolu_live")
                putJsonArray("items") {
                    addJsonObject { item("lv-1", "message") { put("text", "Scanning the **src** tree.") } }
                    addJsonObject { item("lv-2", "tool") { put("name", "Grep"); putJsonObject("input") { put("pattern", "describe\\("); put("path", "src") } } }
                }
            },
            evNullTurn("spawned_run_updated", ts = t) {
                put("runId", "run-1"); put("origin", "spawned"); put("provider", "codex"); put("title", "Fix the flaky test")
                put("prompt", "Fix the flaky retry test"); put("mode", "build"); put("model", "gpt-5"); put("cwd", "/w")
                put("logFile", "/w/.tether/runs/run-1.log"); put("parentTurnId", "t1"); put("toolId", "tool-9"); put("status", "running"); put("startedAt", t)
            },
            evNullTurn("spawned_run_output", ts = t) { put("runId", "run-1"); put("text", "compiling…\nrunning 12 tests\n") },
            evNullTurn("spawned_run_media", ts = t) {
                put("runId", "run-1")
                putJsonArray("media") {
                    addJsonObject {
                        put("type", "media_ref"); put("mediaKind", "image"); put("mediaType", "image/png"); put("url", ToolFixtures.CHART_URL)
                        put("bytes", 1024); put("source", "input"); put("label", "failing-test.png")
                    }
                }
            },
            evNullTurn("spawned_run_updated", ts = t) {
                put("runId", "run-2"); put("origin", "discovered"); put("provider", "claude"); put("title", "Linked review")
                put("nativeId", "session-7f3a"); put("status", "running"); put("startedAt", t)
            },
        )
    }

    /** A finished spawned child: stopped with exit 143, its capped output marked truncated. */
    val spawnedStopped: ChatFixtures.Folded by lazy {
        val t = T_SUB
        ChatFixtures.fold(
            *ChatFixtures.turn("t1", "Spawn a codex child for the lint.", "Spawned it.", t),
            evNullTurn("spawned_run_updated", ts = t) {
                put("runId", "run-3"); put("origin", "spawned"); put("provider", "codex"); put("title", "Lint the package")
                put("logFile", "/w/.tether/runs/run-3.log"); put("parentTurnId", "t1"); put("status", "running"); put("startedAt", t)
            },
            evNullTurn("spawned_run_output", ts = t) { put("runId", "run-3"); put("text", "src/a.ts: 3 problems\nsrc/b.ts: 1 problem\n") },
            evNullTurn("spawned_run_updated", ts = t + 42_000) {
                put("runId", "run-3"); put("origin", "spawned"); put("provider", "codex"); put("title", "Lint the package")
                put("logFile", "/w/.tether/runs/run-3.log"); put("parentTurnId", "t1"); put("status", "stopped"); put("exitCode", 143)
                put("startedAt", t); put("endedAt", t + 42_000)
            },
        )
    }

    /** A Codex child known only from its lifecycle notifications (issue #172). */
    val codexThread: ChatFixtures.Folded by lazy {
        val t = T_SUB
        ChatFixtures.fold(
            ev("turn_started", "t1", ts = t) { put("idempotencyKey", "k-t1") },
            ev("user_message_accepted", "t1", ts = t) { put("text", "Ask the reviewer.") },
            ev("tool_start", "t1", ts = t) {
                put("toolId", "act-1"); put("name", "subagent_activity")
                putJsonObject("input") { put("kind", "started"); put("agentThreadId", "thread-rev"); put("agentPath", "/root/reviewer") }
            },
            ev("tool_end", "t1", ts = t) {
                put("toolId", "act-1"); put("isError", false)
                putJsonObject("output") { put("kind", "completed"); put("agentThreadId", "thread-rev"); put("agentPath", "/root/reviewer") }
            },
            ev("turn_end", "t1", ts = t) { put("outcome", "ok") },
        ).let { folded ->
            // The session is a Codex one: the thread vocabulary is provider-scoped.
            val tree = folded.tree.put("provider", com.tether.app.protocol.tree.JsStr("codex"))
            ChatFixtures.Folded(checkNotNull(com.tether.app.protocol.model.LegacyProjectionAdapter.adaptOnce(tree)), tree)
        }
    }

    /**
     * The composer deck's session activity: a todo list mid-way, a running `!` command streaming
     * stdout and stderr, and two finished ones (exit 0, and one killed by a signal) that anchor in
     * the transcript by launch time.
     */
    val activity: ChatFixtures.Folded by lazy {
        val t = T_SUB
        ChatFixtures.fold(
            *ChatFixtures.turn("t1", "Build it, then run the slow suite in the background.", "Building.", t),
            evNullTurn("background_command_updated", ts = t + 1) {
                put("commandId", "bg-1"); put("command", "npm run build"); put("cwd", "/w"); put("logFile", "/w/.tether/bg-1.log")
                put("status", "running"); put("startedAt", t + 1)
            },
            evNullTurn("background_command_output", ts = t + 2) { put("commandId", "bg-1"); put("stream", "stdout"); put("text", "> build\ncompiled 214 modules\n") },
            evNullTurn("background_command_updated", ts = t + 3) {
                put("commandId", "bg-1"); put("command", "npm run build"); put("cwd", "/w"); put("logFile", "/w/.tether/bg-1.log")
                put("status", "finished"); put("exitCode", 0); put("startedAt", t + 1); put("endedAt", t + 3)
            },
            evNullTurn("background_command_updated", ts = t + 4) {
                put("commandId", "bg-2"); put("command", "sleep 600 && ./flaky.sh"); put("cwd", "/w"); put("logFile", "/w/.tether/bg-2.log")
                put("status", "error"); put("signal", "SIGKILL"); put("startedAt", t + 4); put("endedAt", t + 5)
            },
            *ChatFixtures.turn("t2", "Now run the full suite.", "Started it in the background.", t + 60_000),
            evNullTurn("background_command_updated", ts = t + 61_000) {
                put("commandId", "bg-3"); put("command", "npm test -- --runInBand"); put("cwd", "/w"); put("logFile", "/w/.tether/bg-3.log")
                put("status", "running"); put("startedAt", t + 61_000)
            },
            evNullTurn("background_command_output", ts = t + 61_500) { put("commandId", "bg-3"); put("stream", "stdout"); put("text", "PASS src/config.test.ts\n") },
            evNullTurn("background_command_output", ts = t + 61_600) { put("commandId", "bg-3"); put("stream", "stderr"); put("text", "warn: slow test (4.2s)\n") },
            evNullTurn("todo_updated", ts = t + 62_000) {
                putJsonArray("items") {
                    addJsonObject { put("content", "Build the package"); put("activeForm", "Building the package"); put("status", "completed") }
                    addJsonObject { put("content", "Run the full suite"); put("activeForm", "Running the full suite"); put("status", "in_progress") }
                    addJsonObject { put("content", "Fix the flaky test"); put("activeForm", "Fixing the flaky test"); put("status", "pending") }
                }
            },
        )
    }
}
