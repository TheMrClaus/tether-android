package com.tether.app.protocol.fold

import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.arr
import com.tether.app.protocol.tree.obj
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * tth-fqjc A-stop-core: the v156 folds (tether engines/events.ts background_terminals_changed + turn_interrupted.unstoppedBackground)
 * and the Stop panel's COUNT BASIS (`stoppableBackgroundTaskCount`, the server's `_liveBackgroundTaskList` set).
 */
class StopBackgroundV156Test {
    private fun t(type: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): AgentEvent = ev(type, "t1", build = build)

    private val open = arrayOf(
        t("turn_started") { put("idempotencyKey", "k") },
        t("user_message_accepted") { put("text", "go") },
    )

    private fun terminals(count: kotlinx.serialization.json.JsonElement) =
        evNullTurn("background_terminals_changed") { put("count", count) }

    private fun n(value: Number) = kotlinx.serialization.json.JsonPrimitive(value)

    // ---- count basis: the LB2-refuter scenario ---------------------------------------------------------

    /** A foreground tool still running (`fg`), a background Bash (`bash`, tool ended, task level) and a background agent (`agent`). */
    private val lb2 = arrayOf(
        *open,
        t("tool_start") { put("toolId", "fg"); put("name", "Read"); put("input", buildJsonObject {}) },
        t("tool_start") { put("toolId", "bash"); put("name", "Bash"); put("input", buildJsonObject {}) },
        t("tool_end") { put("toolId", "bash"); put("output", "started") },
        t("tool_start") { put("toolId", "agent"); put("name", "Agent"); put("input", buildJsonObject {}) },
        t("tool_end") { put("toolId", "agent"); put("output", "started") },
        evNullTurn("task_started") { put("taskId", "x-bash"); put("toolUseId", "bash") },
        evNullTurn("task_started") { put("taskId", "x-agent"); put("toolUseId", "agent") },
    )

    @Test
    fun theLb2ScenarioCountsTwoStoppableTasksAndOneRunningTool() {
        val state = foldTree(freshTree(), *lb2)
        assertEquals(1, openToolCount(state))
        assertEquals(2, stoppableBackgroundTaskCount(state))
        // the CLI level (live rows) names the same two: still 2
        val level = foldTree(state, evNullTurn("background_tasks_changed") { put("taskIds", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("x-bash")); add(kotlinx.serialization.json.JsonPrimitive("x-agent")) }) })
        assertEquals(2, stoppableBackgroundTaskCount(level))
    }

    @Test
    fun aTaskLaunchedByAToolCallThatIsStillRunningIsNotCounted() {
        val state = foldTree(
            freshTree(), *open,
            t("tool_start") { put("toolId", "bash"); put("name", "Bash"); put("input", buildJsonObject {}) },
            evNullTurn("task_started") { put("taskId", "x-bash"); put("toolUseId", "bash") },
        )
        assertEquals("the launching call is a running tool, not a background task yet", 0, stoppableBackgroundTaskCount(state))
    }

    @Test
    fun aLevelIdWhoseRowIsTerminalIsNotCountedButTheOldCountStillIs() {
        val state = foldTree(
            freshTree(), *open,
            evNullTurn("task_started") { put("taskId", "x1") },
            evNullTurn("task_started") { put("taskId", "x2") },
            evNullTurn("background_tasks_changed") { put("taskIds", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("x1")); add(kotlinx.serialization.json.JsonPrimitive("x2")) }) },
            evNullTurn("task_completed") { put("taskId", "x2"); put("status", "completed") },
        )
        // #278: x2's own terminal record ends it whatever the level set says.
        assertEquals(1, stoppableBackgroundTaskCount(state))
        assertEquals(1, liveBackgroundTaskCount(state, levelTaskIds = listOf(JsStr("x1"), JsStr("x2")), terminalEnds = true))
        assertEquals("the unflagged ta-ceo count is unchanged", 2, liveBackgroundTaskCount(state, levelTaskIds = listOf(JsStr("x1"), JsStr("x2"))))
    }

    @Test
    fun snapshotRowLiveButTerminalIsNotCounted() {
        // #278 terminal-row rule at the stoppable site: a snapshot row can be live:true AND already terminal (no fold event cleared it).
        fun row(id: String, status: String) = JsObj.of("taskId" to JsStr(id), "live" to JsBool.TRUE, "status" to JsStr(status))
        val state = freshTree().with(
            "backgroundTasks" to JsArr.EMPTY.add(row("x1", "running")).add(row("x2", "completed")),
        )
        assertEquals(1, stoppableBackgroundTaskCount(state))
    }

    @Test
    fun foregroundToolPlusSubAgentInnerTasksCountsTheLevelNotEveryRow() {
        fun ids(vararg v: String) = buildJsonArray { v.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        val state = foldTree(
            freshTree(), *open,
            t("tool_start") { put("toolId", "fg"); put("name", "Read"); put("input", buildJsonObject {}) },
            evNullTurn("task_started") { put("taskId", "x-a") },
            evNullTurn("task_started") { put("taskId", "x-b") },
            evNullTurn("task_started") { put("taskId", "inner-1") },
            evNullTurn("task_started") { put("taskId", "inner-2") },
            evNullTurn("background_tasks_changed") { put("taskIds", ids("x-a", "x-b")) },
        )
        assertEquals(1, openToolCount(state))
        assertEquals("the CLI level (2), not the 4 running rows", 2, stoppableBackgroundTaskCount(state))
    }

    @Test
    fun noStateIsZero() {
        assertEquals(0, stoppableBackgroundTaskCount(null))
        assertEquals(0, stoppableBackgroundTaskCount(freshTree()))
    }

    // ---- background_terminals_changed -------------------------------------------------------------------

    @Test
    fun terminalsFoldTheSessionLevelCountAndTolerateGarbage() {
        val fresh = freshTree()
        assertEquals(0, backgroundTerminalCount(fresh))
        val three = foldTree(fresh, terminals(n(3)))
        assertEquals(3, backgroundTerminalCount(three))
        assertEquals(JsNum(3.0), three["backgroundTerminals"])
        assertEquals("a fractional count floors", 2, backgroundTerminalCount(foldTree(fresh, terminals(n(2.9)))))
        assertEquals("negative -> 0", 0, backgroundTerminalCount(foldTree(three, terminals(n(-4)))))
        assertEquals("a string count is not a finite number -> 0", 0, backgroundTerminalCount(foldTree(three, terminals(kotlinx.serialization.json.JsonPrimitive("5")))))
        assertEquals("the same count folds to the same state", three, foldTree(three, terminals(n(3))))
        assertEquals("an unknown sibling event is still a no-op", fresh, foldTree(fresh, evNullTurn("background_terminals_unknown") { put("count", 9) }))
    }

    @Test
    fun theTerminalsFoldNeedsNoOpenTurn() {
        assertEquals(4, backgroundTerminalCount(foldTree(foldTree(freshTree(), *open), terminals(n(4)))))
    }

    // ---- turn_interrupted.unstoppedBackground -----------------------------------------------------------

    private fun interrupted(extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        ev("turn_interrupted", "t1", seq = 9, ts = 99) {
            put("by", "operator-stop"); put("interruptedTurnId", "t1"); put("stoppedTools", 1); put("stoppedBackground", 2)
            extra()
        }

    private fun notice(state: JsObj): JsObj = state["notices"].arr!!.single { it.obj?.get("kind") == JsStr("turn_interrupted") }.obj!!

    @Test
    fun unstoppedBackgroundIsKeptOnlyWhenAtLeastOne() {
        val withUnstopped = notice(foldTree(freshTree(), *open, interrupted { put("unstoppedBackground", 2) }))
        assertEquals(JsNum(2.0), withUnstopped["unstoppedBackground"])
        assertEquals(JsNum(2.0), withUnstopped["stoppedBackground"])
        for (bad in listOf(n(0), n(-1), n(1.5), kotlinx.serialization.json.JsonPrimitive("3"))) {
            assertNull("tolerant: $bad is dropped", notice(foldTree(freshTree(), *open, interrupted { put("unstoppedBackground", bad) }))["unstoppedBackground"])
        }
        assertNull("absent from an older server", notice(foldTree(freshTree(), *open, interrupted {}))["unstoppedBackground"])
    }
}
