package com.tether.app.ui.chat

import com.tether.app.client.BackgroundCommandResult
import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.client.RunCommandResult
import com.tether.app.protocol.DelegateMention
import com.tether.app.protocol.ModelVariantOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T7.3 fixtures, folded by the real v128 reducer: a FOREGROUND `!` command turn (turn_started with
 * `commandRun`, then command_output_started / _delta, stdout and stderr), the same command finished
 * with a failure, the CLI inventory events, and a provider catalog for the `@` Agents.
 */
object CommandFixtures {
    const val T = ComposerFixtures.T_START
    const val COMMAND = "npm test -- --runInBand"
    const val LOG = "/w/.tether/commands/c-1.log"

    private fun commandStart(turnId: String = "t1") = arrayOf(
        ev("turn_started", turnId, ts = T) {
            put("idempotencyKey", "k-$turnId")
            put("commandRun", buildJsonObject { put("command", COMMAND); put("cwd", "/w"); put("logFile", LOG) })
        },
        ev("command_output_started", turnId, ts = T) { put("blockId", "$turnId:cmd"); put("command", COMMAND); put("logFile", LOG) },
    )

    private fun delta(turnId: String, stream: String, text: String) =
        ev("command_output_delta", turnId, ts = T + 1_000) { put("blockId", "$turnId:cmd"); put("stream", stream); put("text", text) }

    /** A foreground command still running, with some output. */
    val running: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *commandStart(),
            delta("t1", "stdout", "> app@1.0.0 test\n> jest --runInBand\n\nPASS src/format.test.ts\n"),
            delta("t1", "stderr", "console.warn: slow test (1.8 s)\n"),
            delta("t1", "stdout", "RUNS src/sync.test.ts\n"),
        )
    }

    /** Output a hostile command could print: ANSI colour, a bidi override, a lone CR, a NUL. */
    const val HOSTILE_OUTPUT = "\u001B[32mok\u001B[0m\r\nApprove \u202Egnp.exe\u202C\u0000 done\n"

    /** The command finished: exit 1, stderr in the tail, the capture truncated by the server. */
    val failed: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *commandStart(),
            delta("t1", "stdout", "PASS src/format.test.ts\n"),
            delta("t1", "stderr", "FAIL src/sync.test.ts\n  ● outbox › drains in order\n"),
            ev("command_output_completed", "t1", ts = T + 4_000) {
                put("blockId", "t1:cmd"); put("exitCode", 1); put("timedOut", false); put("killed", false); put("outputTruncated", true); put("durationMs", 4_000)
            },
            ev("turn_end", "t1", ts = T + 5_000) { put("outcome", "ok") },
        )
    }

    /** Just started: no output yet. */
    val waiting: ChatFixtures.Folded by lazy { ChatFixtures.fold(*commandStart()) }

    val hostile: ChatFixtures.Folded by lazy { ChatFixtures.fold(*commandStart(), delta("t1", "stdout", HOSTILE_OUTPUT)) }

    private fun command(name: String, description: String?, hint: String? = null) = buildJsonObject {
        put("name", name)
        if (description != null) put("description", description)
        if (hint != null) put("argumentHint", hint)
    }

    /** native_session_id carrying the CLI's init inventory, then cli_commands_changed. */
    val inventory: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            ev("native_session_id", "t0", ts = T) {
                put("nativeSessionId", "native-1")
                put("cliInventory", buildJsonObject {
                    put("commands", JsonArray(listOf(command("compact", "Clear history but keep a summary", "<instructions>"), command("context", "Show context usage"), command("exit", "Exit the REPL"))))
                    put("tools", JsonArray(listOf(JsonPrimitive("Bash"))))
                    put("mcpServers", JsonArray(emptyList()))
                })
            },
        )
    }

    val inventoryChanged: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            ev("native_session_id", "t0", ts = T) {
                put("nativeSessionId", "native-1")
                put("cliInventory", buildJsonObject {
                    put("commands", JsonArray(listOf(command("compact", "Clear history but keep a summary"))))
                    put("tools", JsonArray(emptyList())); put("mcpServers", JsonArray(emptyList()))
                })
            },
            evNullTurn("cli_commands_changed", ts = T + 1) {
                put("commands", JsonArray(listOf(command("review", "Review a pull request"), command("security-review", "Complete a security review"))))
            },
        )
    }

    val inventoryReset: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            ev("native_session_id", "t0", ts = T) {
                put("nativeSessionId", "native-1")
                put("cliInventory", buildJsonObject {
                    put("commands", JsonArray(listOf(command("compact", "Clear history but keep a summary"))))
                    put("tools", JsonArray(emptyList())); put("mcpServers", JsonArray(emptyList()))
                })
            },
            evNullTurn("cli_inventory_reset", ts = T + 1),
        )
    }

    val catalog: List<ProviderCatalogEntry> = listOf(
        ProviderCatalogEntry(
            "claude", "claude", "ready",
            listOf(
                SessionModelOption("claude-opus-5", "Opus 5", variants = listOf(ModelVariantOption("high", "High"), ModelVariantOption("max", "Max"))),
                SessionModelOption("claude-haiku-5", "Haiku 5"),
            ),
            defaultModel = "claude-opus-5", label = "Claude",
        ),
        ProviderCatalogEntry("codex", "codex", "loading", emptyList(), label = "Codex"),
        ProviderCatalogEntry("opencode", "opencode", "ready", listOf(SessionModelOption("a", "A"), SessionModelOption("b", "B")), label = "OpenCode"),
    )

    /** Records every run, background and delegated send the composer makes. */
    class Recorder(
        var run: RunCommandResult = RunCommandResult.Sent,
        var background: BackgroundCommandResult = BackgroundCommandResult.Sent,
        var delegated: Boolean = true,
    ) {
        val runs = mutableListOf<Pair<String, Boolean>>()
        val backgrounds = mutableListOf<String>()
        val delegations = mutableListOf<Pair<String, DelegateMention>>()
        var agentReads = 0
        fun actions(commandMode: Boolean = true, agents: List<ProviderCatalogEntry> = emptyList(), origin: String = "https://tether.test") = ComposerCommandActions(
            commandMode = commandMode,
            onRun = { command, bg -> runs += command to bg; run },
            onBackground = { turnId -> backgrounds += turnId; background },
            origin = origin,
            agents = agents,
            onRequestAgents = { agentReads++ },
            onSendDelegated = { text, _, mention -> delegations += text to mention; delegated },
        )
    }
}
