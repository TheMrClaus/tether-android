package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.fold.initialSessionState
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.theme.TetherSkin
import java.io.File
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-qm8b: the crash hunt. The transcript (activity rows, the file links, the find marks) is composed over every step of every
 * reducer corpus scenario and over random mutations of them (hostile strings, wrong types, dropped, duplicated and reordered
 * events); any exception that escapes composition or measure fails the test with its seed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
open class CrashHuntTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Scenario(val name: String, val provider: String, val cwd: String, val events: List<JsonObject>)

    private fun corpusDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val c = File(dir, "parity-corpus/reducer")
            if (c.isDirectory) return c
            dir = dir.parentFile
        }
        error("no parity-corpus/reducer above ${File("").absolutePath}")
    }

    private fun scenarios(): List<Scenario> = corpusDir().listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.map { f ->
        val o = Json.parseToJsonElement(f.readText()).jsonObject
        val init = o["initial"]!!.jsonObject
        Scenario(
            f.name,
            init["provider"]?.jsonPrimitive?.content ?: "claude",
            init["cwd"]?.jsonPrimitive?.content ?: "/w/p",
            o["inputEvents"]!!.jsonArray.map { it.jsonObject },
        )
    }

    private val hostile = listOf(
        "", " ", "\u0000", "\uD800", "\uDC00x", "a\uD83D", "😀".repeat(300), "👩‍👩‍👧‍👦 /a/b.kt", "‮evil/a/b.kt‬",
        "مرحبا /home/u/a.png ثم `a/b.kt`", "é".repeat(2000) + " /a/b.kt", "​/a/b.kt​", "  ", "%", "%zz", "[a](/%)", "[a](file:///%E0%A4%A)",
        "[x](/a/b.kt:99999999999)", "/a/b.kt:12:3:4", "/a/../../b.kt", "/../x.md", "`../../../../x.md`", "`~/a.png`", "`a b/c d.md`", "/a/b.kt#L1-L999999999999",
        "((((((((((((((/a/b.kt))))))))))))))", ")".repeat(5000), "(".repeat(5000), "/a/".repeat(2000) + "z.kt", "a".repeat(100_000), "x ".repeat(30_000),
        "```\n/a/b.kt\n```", "`".repeat(300), "<!-- -->", "| a | b |\n|-|-|\n| /a/b.kt | `c.md` |", "# " + "h".repeat(5000), "- ".repeat(3000),
        "/a/b.kt /a/b.kt /a/b.kt ".repeat(500), "\n".repeat(5000), "line\n".repeat(20_000), "http://x.test/a.png", "file:///a/b.kt", "/api/files?path=/a.png",
        "\t/a/b.kt\t", " /a/b.kt ", "ＡＢ/ａ/ｂ.kt", "/a/b.KT", "/a/Makefile:3", "/a/b.kt́", "9999999999999999999999", "-1", "1e999",
    )

    private val toolNames = listOf(
        "Bash", "BashOutput", "KillShell", "Read", "Edit", "MultiEdit", "NotebookEdit", "Write", "Grep", "Glob", "WebSearch", "WebFetch", "Task", "Agent", "TaskCreate",
        "TodoWrite", "ExitPlanMode", "mcp:srv:tool", "mcp:", "mcp::", "collaboration:spawn", "collaboration:", "command_execution", "file_change", "subagent_activity",
        "task", "web_search", "", "x".repeat(3000), "Tool/with/slash", "\uD800", "read_file", "edit_file", "apply_patch", "shell", "todo_write",
    )

    private fun randomValue(rnd: Random, depth: Int = 0): JsonElement = when (rnd.nextInt(if (depth > 2) 6 else 9)) {
        0 -> JsonNull
        1 -> JsonPrimitive(rnd.nextInt(-5, 100000))
        2 -> JsonPrimitive(rnd.nextDouble() * 1e12)
        3 -> JsonPrimitive(rnd.nextBoolean())
        4, 5 -> JsonPrimitive(hostile.random(rnd))
        6 -> JsonArray(List(rnd.nextInt(0, 4)) { randomValue(rnd, depth + 1) })
        else -> JsonObject(List(rnd.nextInt(0, 4)) { listOf("command", "file_path", "path", "pattern", "text", "url", "description", "output", "exitCode", "status", "changes", "server", "tool", "action", "type", "kind", "content", "old_string", "new_string", "edits", "todos").random(rnd) to randomValue(rnd, depth + 1) }.toMap())
    }

    private fun <T> List<T>.random(rnd: Random): T = this[rnd.nextInt(size)]

    /** Replaces one random leaf (or an object member) of [e] with something hostile. */
    private fun mutate(e: JsonElement, rnd: Random, budget: IntArray): JsonElement {
        if (budget[0] <= 0) return e
        return when (e) {
            is JsonObject -> {
                if (e.isEmpty()) return e
                val keys = e.keys.toList()
                val k = keys.random(rnd)
                if (rnd.nextInt(4) == 0) { budget[0]--; return JsonObject(e + (k to randomValue(rnd))) }
                if (rnd.nextInt(10) == 0) { budget[0]--; return JsonObject(e - k) }
                JsonObject(e + (k to mutate(e.getValue(k), rnd, budget)))
            }
            is JsonArray -> {
                if (e.isEmpty()) return e
                val i = rnd.nextInt(e.size)
                JsonArray(e.toMutableList().also { it[i] = mutate(it[i], rnd, budget) })
            }
            else -> { budget[0]--; randomValue(rnd) }
        }
    }

    private fun mutateEvents(events: List<JsonObject>, rnd: Random): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        for (e in events) {
            when (rnd.nextInt(14)) {
                0 -> Unit // dropped
                1 -> { out += e; out += e } // duplicated
                2, 3, 4 -> out += mutate(e, rnd, intArrayOf(2)).jsonObject
                5 -> if (out.isNotEmpty()) out.add(out.size - 1, e) else out += e // reordered with the previous
                6 -> out += JsonObject(e + ("turnId" to JsonPrimitive(listOf("t1", "t2", "a/b", "").random(rnd))))
                else -> out += e
            }
        }
        // A synthetic tool that never ends, one that ends twice, deltas for an unknown tool.
        if (rnd.nextInt(3) == 0) {
            val tn = toolNames.random(rnd)
            out += JsonObject(mapOf("type" to JsonPrimitive("tool_start"), "turnId" to JsonPrimitive("t1"), "toolId" to JsonPrimitive("z${rnd.nextInt(5)}"), "name" to JsonPrimitive(tn), "input" to randomValue(rnd)))
            out += JsonObject(mapOf("type" to JsonPrimitive("tool_output_delta"), "turnId" to JsonPrimitive("t1"), "toolId" to JsonPrimitive("z${rnd.nextInt(5)}"), "chunk" to JsonPrimitive(hostile.random(rnd))))
        }
        return out
    }

    private class Run(val projection: com.tether.app.protocol.model.SessionProjection, val tree: JsObj)

    /** Folds [events] one by one; a step the reducer itself refuses is skipped (counted in [folded]). */
    private fun steps(sc: Scenario, events: List<JsonObject>, stats: IntArray): List<Run> {
        var tree: JsObj = initialSessionState("s1", sc.provider, sc.cwd)
        val out = ArrayList<Run>()
        for (e in events) {
            val next = try { foldTree(tree, AgentEvent(e)) } catch (_: Throwable) { stats[0]++; continue }
            tree = next
            val p = try { LegacyProjectionAdapter.adaptOnce(tree) } catch (_: Throwable) { stats[1]++; null } ?: continue
            out += Run(p, tree)
        }
        return out
    }

    private var current by mutableStateOf<Run?>(null)
    private var richCodex by mutableStateOf(false)
    private var showThinking by mutableStateOf(false)
    private var toggles by mutableStateOf(GroupToggles())
    private var find by mutableStateOf<String?>(null)

    private fun show() {
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 3000.dp) {
                CompositionLocalProvider(
                    LocalToolMediaLoader provides ToolFixtures.FakeLoader(),
                    LocalWorkspaceFileOpener provides WorkspaceFileLinks("/w/p") { },
                ) {
                    val r = current ?: return@CompositionLocalProvider
                    val needle = find?.let { findNeedle(it) }
                    val results = needle?.let { findResults(r.projection, it, open = true) }
                    ChatTranscript(
                        projection = r.projection,
                        tree = r.tree,
                        showThinking = showThinking,
                        onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone,
                        groupToggles = toggles,
                        showTimeline = false,
                        richCodex = richCodex,
                        richOpencode = richCodex,
                        find = if (needle != null && results != null) TranscriptFind(results, needle, results.hits.firstOrNull()) else null,
                    )
                }
            }
        }
    }

    private fun render(run: Run, label: String) {
        try {
            rule.runOnIdle { current = run }
            rule.waitForIdle()
        } catch (t: Throwable) {
            throw AssertionError("transcript threw at $label", t)
        }
    }

    private fun openAll(run: Run) {
        val items = buildChatItems(run.projection, run.tree, showThinking = true, zone = ChatFixtures.zone, richCodex = richCodex)
        toggles = GroupToggles(items.filterIsInstance<ChatItem.ToolGroup>().associate { it.key to GroupToggle(default = it.defaultOpen, open = true) })
    }

    @Test fun everyCorpusScenarioRendersAtEveryStep() {
        show()
        val stats = IntArray(2)
        var renders = 0
        var rows = 0
        for (sc in scenarios()) {
            richCodex = sc.provider != "claude"
            showThinking = true
            rule.runOnIdle { toggles = GroupToggles() }
            val all = steps(sc, sc.events, stats)
            all.forEachIndexed { i, run ->
                if (i == all.lastIndex) rule.runOnIdle { openAll(run) }
                render(run, "${sc.name} step $i")
                renders++
            }
            all.lastOrNull()?.let { run ->
                rows += rule.onAllNodes(androidx.compose.ui.test.hasTestTag("activity-row")).fetchSemanticsNodes().size
                for (q in listOf("a", "/", "kt", "e", "rm")) {
                    rule.runOnIdle { find = q }
                    render(run, "${sc.name} find '$q'")
                }
                rule.runOnIdle { find = null }
            }
        }
        println("CRASHHUNT corpus: $renders renders ($rows activity rows on screen at the ends), reducer refused ${stats[0]}, adapter refused ${stats[1]}")
    }

    @Test fun mutatedCorpusScenariosNeverCrashTheTranscript() {
        show()
        val seeds = (System.getenv("CRASHHUNT_SEEDS") ?: "40").toInt()
        val base = (System.getenv("CRASHHUNT_BASE") ?: "1000").toLong()
        val all = scenarios()
        val stats = IntArray(2)
        var renders = 0
        for (s in 0 until seeds) {
            val seed = base + s
            val rnd = Random(seed)
            val sc = all.random(rnd)
            val events = mutateEvents(sc.events, rnd)
            richCodex = rnd.nextBoolean()
            showThinking = rnd.nextBoolean()
            rule.runOnIdle { toggles = GroupToggles() }
            val runs = steps(sc, events, stats)
            // Every step is composed on a few seeds, the last one with every group open and a find over it, on all.
            val every = rnd.nextInt(3) == 0
            runs.forEachIndexed { i, run ->
                if (i == runs.lastIndex) {
                    rule.runOnIdle { openAll(run) }
                    render(run, "seed=$seed ${sc.name} last step $i")
                    renders++
                    for (q in listOf("a", "/", "b.kt", "👩")) {
                        rule.runOnIdle { find = q }
                        render(run, "seed=$seed ${sc.name} find '$q'")
                    }
                    rule.runOnIdle { find = null }
                } else if (every) {
                    render(run, "seed=$seed ${sc.name} step $i")
                    renders++
                }
            }
        }
        println("CRASHHUNT fuzz: seeds $base..${base + seeds - 1}, $renders renders, reducer refused ${stats[0]}, adapter refused ${stats[1]}")
    }
}

/** The same hunt at the largest system font (2.0x). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 2.0f)
class CrashHuntLargeFontTest : CrashHuntTest()
