package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Dp
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.reduce.ev
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.put

/**
 * ta-a5jl fixtures and harness: a turn with one tool of each state (the rows board), a thinking block, a picture-bearing
 * Read, and helpers that enter the way a reader does: the transcript, a row, a tap, the sheet.
 */
object ActivityFixtures {
    const val T = 7_200_000L

    private fun json(text: String) = TetherJson.parseToJsonElement(text)

    private fun q(s: String) = TetherJson.encodeToString(kotlinx.serialization.json.JsonPrimitive(s))

    /** Builds the events of one turn "t1". */
    class Script(val turnId: String = "t1", val ts: Long = T) {
        val events = ArrayList<AgentEvent>()

        fun start(prompt: String) {
            events += ev("turn_started", turnId, ts = ts) { put("idempotencyKey", "k-$turnId") }
            events += ev("user_message_accepted", turnId, ts = ts) { put("text", prompt) }
        }

        fun tool(id: String, name: String, input: String, output: String? = null, isError: Boolean = false, interrupted: Boolean = false, done: Boolean = true) {
            events += ev("tool_start", turnId, ts = ts) { put("toolId", id); put("name", name); put("input", json(input)) }
            if (done) {
                events += ev("tool_end", turnId, ts = ts) {
                    put("toolId", id)
                    if (output != null) put("output", json(output))
                    put("isError", isError)
                    if (interrupted) put("interrupted", true)
                }
            }
        }

        fun progress(id: String, seconds: Int) {
            events += ev("tool_progress", turnId, ts = ts) { put("toolId", id); put("elapsedSeconds", seconds) }
        }

        fun delta(id: String, chunk: String) {
            events += ev("tool_output_delta", turnId, ts = ts) { put("toolId", id); put("chunk", chunk) }
        }

        fun thinking(id: String, text: String, done: Boolean = true) {
            events += ev("thinking_delta", turnId, ts = ts) { put("blockId", id); put("text", text) }
            if (done) events += ev("thinking_stop", turnId, ts = ts) { put("blockId", id) }
        }

        fun message(id: String, text: String) {
            events += ev("message_started", turnId, ts = ts) { put("blockId", id) }
            events += ev("message_completed", turnId, ts = ts) { put("blockId", id); put("text", text) }
        }

        fun fold(): ChatFixtures.Folded = ChatFixtures.fold(*events.toTypedArray())
    }

    fun text(s: String): String = q(s)

    const val LONG_COMMAND =
        "git -C /home/dev/projects/tether-android log --oneline --decorate --graph --all --since=2026-09-01 -- feature/chat core/designsystem"

    /**
     * The rows board: Shell done, Read done, Edit error, Search interrupted and Shell running (one open group, held open by
     * the running call), then Thinking, then a Read whose output carries a picture (a Single, its tile under the row).
     */
    val rows: ChatFixtures.Folded by lazy {
        val s = Script()
        s.start("Check the config and fix it.")
        s.tool("a1", "Bash", """{"command":"git status"}""", q("On branch main\nnothing to commit, working tree clean\n"))
        s.tool("a2", "Read", """{"file_path":"/w/p/src/config.ts"}""", q("export const config = { retries: 3 };\n"))
        s.tool(
            "a3", "Edit",
            """{"file_path":"/w/p/src/config.ts","old_string":"retries: 3","new_string":"retries: 5"}""",
            q("old_string was not found in the file"),
            isError = true,
        )
        s.tool("a4", "Grep", """{"pattern":"retries","path":"src"}""", q("[Request interrupted by user for tool use]"), interrupted = true)
        s.tool("a5", "Bash", """{"command":"npm test -- --watch=false"}""", done = false)
        s.delta("a5", "# Subtest: retry\nok 1 - backs off\n")
        s.progress("a5", 12)
        s.thinking("th1", "The retry loop never awaits the delay, so **all attempts run at once**.\n\n- attempt 1 fails\n- attempt 2 fires in the same tick")
        s.tool(
            "a6", "Read", """{"file_path":"/w/p/chart.png"}""",
            """[{"type":"media_ref","mediaKind":"image","mediaType":"image/png","url":"${ToolFixtures.CHART_URL}","bytes":163}]""",
        )
        s.fold()
    }

    /** A finished turn: one Bash (long command, output) in a closed group, and the thinking block. */
    val finishedShell: ChatFixtures.Folded by lazy {
        val s = Script()
        s.start("What changed?")
        s.tool("c1", "Bash", """{"command":${q(LONG_COMMAND)}}""", q("d4c3b2a (HEAD -> main) ta-a5jl: compact rows\ne97052c6 ta-d0qg: Review request lands on the card\n"))
        s.tool("c2", "Read", """{"file_path":"/w/p/README.md"}""", q("# README\n"))
        s.thinking("th1", "Two commits matter here.")
        s.message("m1", "Done.")
        s.fold()
    }

    /** A finished Edit of a few lines: its sheet draws the diff. */
    val edit: ChatFixtures.Folded by lazy {
        val s = Script()
        s.start("Back off between retries.")
        val old = "export async function retry(fn) {\n  for (let i = 0; i < 3; i++) {\n    try { return await fn(); } catch {}\n  }\n}\n"
        val new = "export async function retry(fn, delay = 250) {\n  for (let i = 0; i < 3; i++) {\n    try { return await fn(); } catch {}\n    await sleep(delay * 2 ** i);\n  }\n}\n"
        s.tool("e1", "Edit", """{"file_path":"/w/p/src/retry.ts","old_string":${q(old)},"new_string":${q(new)}}""", q("The file /w/p/src/retry.ts has been updated."))
        s.tool("e2", "Read", """{"file_path":"/w/p/src/retry.ts"}""", q("ok"))
        s.message("m1", "Added the backoff.")
        s.fold()
    }

    /** A running Codex command: streaming output, then (via [finishCommand]) its end. */
    val runningCommand: ChatFixtures.Folded by lazy {
        val s = Script()
        s.start("Run the suite.")
        s.tool("cmd-live", "command_execution", """{"command":"npm test","cwd":"/w/p"}""", done = false)
        s.delta("cmd-live", "# Subtest: retry\nok 1 - backs off\n")
        s.fold()
    }

    /** [runningCommand] with more output and its end ("done"). */
    fun finishCommand(from: ChatFixtures.Folded, more: String = "ok 2 - gives up\n"): ChatFixtures.Folded {
        val tree = com.tether.app.protocol.reduce.foldTree(
            from.tree,
            ev("tool_output_delta", "t1", ts = T) { put("toolId", "cmd-live"); put("chunk", more) },
            ev("tool_end", "t1", ts = T) { put("toolId", "cmd-live"); put("output", json("""{"text":"# Subtest: retry\nok 1 - backs off\n$more","exitCode":0,"status":"completed"}""")) },
        )
        return ChatFixtures.Folded(checkNotNull(com.tether.app.protocol.model.LegacyProjectionAdapter.adaptOnce(tree)), tree)
    }
}

/** The matcher of one activity row by the start of its accessible name. */
fun rowLabel(prefix: String): SemanticsMatcher = hasTestTag("activity-row") and hasContentDescription(prefix, substring = true)

/** Composes [fixture] in a transcript the way the chat screen does (no timeline rail), on a fake media loader. */
fun ComposeContentTestRule.showTranscript(
    fixture: ChatFixtures.Folded,
    skin: TetherSkin = TetherSkin.StudioDark,
    showThinking: Boolean = false,
    richCodex: Boolean = false,
    richOpencode: Boolean = false,
    wellHeight: Dp = WellHeightPhone,
    wellWidth: Dp? = null,
    listState: LazyListState = LazyListState(),
    loader: ToolMediaLoader = ToolFixtures.FakeLoader(),
    groupsOpen: Boolean = false,
) {
    val toggles = if (groupsOpen) allGroupsOpen(fixture, richCodex) else GroupToggles()
    setContent {
        ChatHost(skin, wellHeight, wellWidth) {
            CompositionLocalProvider(LocalToolMediaLoader provides loader) {
                ChatTranscript(
                    projection = fixture.projection,
                    tree = fixture.tree,
                    showThinking = showThinking,
                    onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone,
                    listState = listState,
                    groupToggles = toggles,
                    showTimeline = false,
                    richCodex = richCodex,
                    richOpencode = richOpencode,
                )
            }
        }
    }
    waitForIdle()
}

/** Toggles that hold every activity group of [fixture] open (each against the default it overrides). */
internal fun allGroupsOpen(fixture: ChatFixtures.Folded, richCodex: Boolean = false): GroupToggles {
    val items = buildChatItems(fixture.projection, fixture.tree, showThinking = true, zone = ChatFixtures.zone, richCodex = richCodex)
    return GroupToggles(items.filterIsInstance<ChatItem.ToolGroup>().associate { it.key to GroupToggle(default = it.defaultOpen, open = true) })
}

/** Scrolls the transcript to the row whose label starts [prefix] and taps it: the reader's way into the sheet. */
fun ComposeContentTestRule.openRow(prefix: String) {
    onNodeWithTag("chat-transcript").performScrollToNode(rowLabel(prefix))
    onNode(rowLabel(prefix)).performClick()
    waitForIdle()
}
