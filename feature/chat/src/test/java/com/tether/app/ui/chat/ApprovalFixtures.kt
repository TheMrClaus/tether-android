package com.tether.app.ui.chat

import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * T6.3 attention-card states, folded by the real v128 reducer. [write] mirrors the S0.4 web
 * scenario `approval-pending` (parity-seed.mjs: "Add a version file." → a Write approval); the
 * rest are states the fake engine cannot seed (provider choices, permission grants, questions,
 * answers, denials), built from the event shapes the reducer corpus pins.
 */
object ApprovalFixtures {
    /** The web shot's clock: 07:01. */
    const val T = 7 * 3_600_000L + 60_000L

    private fun prompt(turnId: String, text: String, reply: String?): List<AgentEvent> = buildList {
        add(ev("turn_started", turnId, ts = T) { put("idempotencyKey", "k-$turnId") })
        add(ev("user_message_accepted", turnId, ts = T) { put("text", text) })
        if (reply != null) {
            add(ev("message_started", turnId, ts = T) { put("blockId", "$turnId:m0") })
            add(ev("message_completed", turnId, ts = T) { put("blockId", "$turnId:m0"); put("text", reply) })
        }
    }

    private fun approval(turnId: String, requestId: String, toolId: String, name: String, body: JsonObjectBuilder.() -> Unit): AgentEvent =
        ev("approval_request", turnId, ts = T) {
            put("requestId", requestId); put("toolId", toolId); put("name", name)
            body()
        }

    private const val VERSION_FILE = "export const VERSION = \"1.1.0\";\n"

    /** approval-pending: the Write call running, its approval waiting (no provider choices: Approve / Deny). */
    val write: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *prompt("t1", "Add a version file.", "I'll add the version file.").toTypedArray(),
            ev("tool_start", "t1", ts = T) {
                put("toolId", "toolu_w"); put("name", "Write")
                putJsonObject("input") { put("file_path", "src/version.ts"); put("content", VERSION_FILE) }
            },
            approval("t1", "req-w", "toolu_w", "Write") {
                putJsonObject("input") { put("file_path", "src/version.ts"); put("content", VERSION_FILE) }
            },
        )
    }

    /** A Codex command outside its sandbox: provider choices, reason, working directory, network. */
    val choices: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *prompt("t1", "Publish the release.", "Version bumped. The publish script needs the network.").toTypedArray(),
            approval("t1", "req-c", "cmd-1", "command_execution") {
                putJsonObject("input") { put("command", "./scripts/publish.sh --tag v1.4.0") }
                putJsonArray("choices") {
                    addJsonObject { put("choiceId", "accept"); put("label", "Allow once") }
                    addJsonObject { put("choiceId", "acceptForSession"); put("label", "Allow for this session"); put("description", "Until the session ends") }
                    addJsonObject { put("choiceId", "decline"); put("label", "Decline") }
                }
                putJsonObject("metadata") {
                    put("provider", "codex"); put("kind", "command")
                    put("reason", "The command reaches outside the workspace sandbox.")
                    put("cwd", "/w/pipeline")
                    putJsonObject("network") { put("host", "registry.example.test"); put("protocol", "https") }
                }
            },
        )
    }

    /** A permission expansion (T6.3 "permission paths"): exact and subset grants over read/write paths + network. */
    val grants: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *prompt("t1", "Run the migration against the shared fixtures.", null).toTypedArray(),
            approval("t1", "req-g", "perm-1", "permissions") {
                putJsonArray("choices") {
                    addJsonObject { put("choiceId", "all"); put("label", "Allow all"); put("permissionGrant", "exact") }
                    addJsonObject { put("choiceId", "some"); put("label", "Allow selected"); put("permissionGrant", "subset") }
                    addJsonObject { put("choiceId", "deny"); put("label", "Deny") }
                }
                putJsonObject("metadata") {
                    put("provider", "codex"); put("kind", "permissions")
                    put("reason", "The migration reads the shared fixtures and writes its report.")
                    putJsonObject("requestedPermissions") {
                        putJsonObject("fileSystem") {
                            putJsonArray("read") { add("/srv/fixtures"); add("/srv/schema.sql") }
                            putJsonArray("write") { add("/w/report") }
                        }
                        putJsonObject("network") { put("enabled", true) }
                    }
                }
            },
        )
    }

    const val Q_DB = "Which database should the service use?"
    const val Q_ENV = "Which environments should the migration run in?"

    private fun askEvents(): List<AgentEvent> = listOf(
        ev("tool_start", "t1", ts = T) { put("toolId", "ask-1"); put("name", "AskUserQuestion"); putJsonObject("input") {} },
        ev("question_request", "t1", ts = T) {
            put("requestId", "q-1"); put("toolId", "ask-1")
            putJsonArray("questions") {
                addJsonObject {
                    put("question", Q_DB); put("header", "Database"); put("multiSelect", false)
                    putJsonArray("options") {
                        addJsonObject { put("label", "Postgres"); put("description", "Relational, the team's default") }
                        addJsonObject { put("label", "SQLite"); put("description", "Embedded, one file") }
                    }
                }
                addJsonObject {
                    put("question", Q_ENV); put("header", "Environments"); put("multiSelect", true)
                    putJsonArray("options") {
                        addJsonObject { put("label", "staging"); put("description", "") }
                        addJsonObject { put("label", "production"); put("description", "After a day on staging") }
                    }
                }
            }
        },
    )

    /** Two questions pending (paged: "Question 1 of 2"). */
    val question: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(*prompt("t1", "Set up the new service.", "Two choices before I scaffold it.").toTypedArray(), *askEvents().toTypedArray())
    }

    /** The question answered and resolved: the record in the AskUserQuestion slot; a second, block-less record trails. */
    val answered: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *prompt("t1", "Set up the new service.", "Two choices before I scaffold it.").toTypedArray(),
            *askEvents().toTypedArray(),
            ev("question_answered", "t1", ts = T) {
                put("requestId", "q-1"); put("toolId", "ask-1"); put("response", "Keep it boring.")
                putJsonArray("items") {
                    addJsonObject { put("header", "Database"); put("question", Q_DB); put("answer", "Postgres, Keep it boring.") }
                    addJsonObject { put("header", "Environments"); put("question", Q_ENV); put("answer", "") }
                }
            },
            ev("question_resolved", "t1", ts = T) { put("requestId", "q-1") },
            // v104: a record whose AskUserQuestion block is not in the projection trails the turn.
            ev("question_answered", "t1", ts = T) {
                put("requestId", "q-2"); put("toolId", "ask-missing")
                putJsonArray("items") { addJsonObject { put("header", ""); put("question", "Add a health check?"); put("answer", "Yes") } }
            },
            ev("tool_end", "t1", ts = T) { put("toolId", "ask-1"); put("output", "answered"); put("isError", false) },
            ev("message_started", "t1", ts = T) { put("blockId", "t1:m1") },
            ev("message_completed", "t1", ts = T) { put("blockId", "t1:m1"); put("text", "Scaffolding on Postgres.") },
            ev("turn_end", "t1", ts = T) { put("outcome", "ok") },
        )
    }

    /**
     * Denials: a main-agent Bash refusal right after its call (the classifier), a sub-agent's Read
     * refused inside an Agent run (a link to the run), an abort's own words, and a homeless one.
     */
    val denials: ChatFixtures.Folded by lazy {
        ChatFixtures.fold(
            *prompt("t1", "Clean the build and check the fixtures.", "Cleaning first.").toTypedArray(),
            ev("tool_start", "t1", ts = T) { put("toolId", "toolu_b"); put("name", "Bash"); putJsonObject("input") { put("command", "rm -rf build/ && git clean -fdx") } },
            ev("permission_denied", "t1", ts = T) { put("toolId", "toolu_b"); put("name", "Bash"); put("reason", "classifier"); put("reasonCode", "classifier") },
            ev("tool_end", "t1", ts = T) { put("toolId", "toolu_b"); put("output", "denied"); put("isError", true) },
            ev("message_started", "t1", ts = T) { put("blockId", "t1:m1") },
            ev("message_completed", "t1", ts = T) { put("blockId", "t1:m1"); put("text", "That was refused. A sub-agent will check the fixtures.") },
            ev("tool_start", "t1", ts = T) { put("toolId", "task-1"); put("name", "Agent"); putJsonObject("input") { put("description", "Check fixtures"); put("subagent_type", "general-purpose") } },
            ev("subagent_message", "t1", ts = T) {
                put("parentToolUseId", "task-1")
                putJsonArray("items") { addJsonObject { put("key", "child-read"); put("kind", "tool"); put("name", "Read"); putJsonObject("input") { put("file_path", "/srv/fixtures/secret.env") } } }
            },
            ev("permission_denied", "t1", ts = T) { put("toolId", "child-read"); put("name", "Read"); put("reason", "working_dir"); put("subagent", true) },
            ev("tool_end", "t1", ts = T) { put("toolId", "task-1"); put("output", "done"); put("isError", false) },
            ev("permission_denied", "t1", ts = T) {
                put("toolId", "toolu_gone"); put("name", "Edit"); put("reason", "unknown")
                put("error", "Tool permission request failed: AbortError: Stream closed")
            },
            ev("turn_end", "t1", ts = T) { put("outcome", "ok") },
            evNullTurn("permission_denied", ts = T) { put("toolId", "toolu_late"); put("name", "WebFetch"); put("reason", "sandbox") },
        )
    }
}
