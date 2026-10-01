package com.tether.app.protocol.fold

import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.js
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ta-ceo: engines/events.mjs runningToolIds / openToolCount / liveBackgroundTaskCount at tether
 * 887c222. Every expectation was produced by the real web module folding the same events with its
 * own reducer (Node 22); the events here are folded by this app's reducer.
 */
class LiveWorkTest {

    private fun t(type: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): AgentEvent = ev(type, "t1", build = build)

    private val base = arrayOf(
        t("turn_started") { put("idempotencyKey", "k") },
        t("user_message_accepted") { put("text", "go") },
    )
    private val tools = arrayOf(
        *base,
        t("tool_start") { put("toolId", "a"); put("name", "Bash"); put("input", buildJsonObject {}) },
        t("tool_start") { put("toolId", "b"); put("name", "Bash"); put("input", buildJsonObject {}) },
        t("tool_start") { put("toolId", "c"); put("name", "Bash"); put("input", buildJsonObject {}) },
        t("tool_end") { put("toolId", "b"); put("output", "ok") },
    )
    private val background = arrayOf(
        *tools,
        evNullTurn("task_started") { put("taskId", "x1"); put("toolUseId", "a") },
        evNullTurn("task_started") { put("taskId", "x2"); put("toolUseId", "z") },
        evNullTurn("task_started") { put("taskId", "x3") },
        evNullTurn("task_completed") { put("taskId", "x3"); put("status", "completed") },
    )

    private fun ids(state: JsObj?, exclude: JsValue? = null) = runningToolIds(state, exclude).map { (it as JsStr).value }

    @Test
    fun noTurnMeansNothingRuns() {
        assertEquals(emptyList<String>(), ids(freshTree()))
        assertEquals(0, openToolCount(freshTree()))
        assertEquals(0, liveBackgroundTaskCount(freshTree()))
        assertEquals(emptyList<String>(), ids(null))
        assertEquals(0, liveBackgroundTaskCount(null))
        assertEquals(emptyList<String>(), ids(foldTree(freshTree(), *base)))
    }

    @Test
    fun startedNotEndedToolsRunInBlockOrder() {
        val state = foldTree(freshTree(), *tools)
        assertEquals(listOf("a", "c"), ids(state))
        assertEquals(2, openToolCount(state))
        assertEquals(listOf("c"), ids(state, js("a")))
        assertEquals(1, openToolCount(state, js("a")))
    }

    @Test
    fun aCallParkedOnAnApprovalOrAQuestionIsNotRunning() {
        val approval = foldTree(freshTree(), *tools, t("approval_request") { put("requestId", "r1"); put("toolId", "a"); put("name", "Bash"); put("input", buildJsonObject {}) })
        assertEquals(listOf("c"), ids(approval))
        val question = foldTree(
            freshTree(), *tools,
            t("question_request") {
                put("requestId", "q1"); put("toolId", "c")
                put("questions", buildJsonArray { add(buildJsonObject { put("question", "?"); put("header", "h"); put("options", buildJsonArray {}) }) })
            },
        )
        assertEquals(listOf("a"), ids(question))
    }

    @Test
    fun theTurnEndingLeavesNoRunningToolButTheTasksStayLive() {
        val ended = foldTree(freshTree(), *background, t("turn_end") { put("outcome", "success") })
        assertEquals(emptyList<String>(), ids(ended))
        assertEquals(2, liveBackgroundTaskCount(ended))
    }

    @Test
    fun liveBackgroundTasksAreTheRunningOnesUnlessTheLevelIsKnown() {
        val state = foldTree(freshTree(), *background)
        assertEquals(listOf("a", "c"), ids(state))
        assertEquals(2, liveBackgroundTaskCount(state))
        assertEquals(1, liveBackgroundTaskCount(state, excludeToolIds = setOf(js("a"))))
        val level = listOf<JsValue>(js("x1"), js("x3"), js("unknown"))
        assertEquals(3, liveBackgroundTaskCount(state, levelTaskIds = level))
        assertEquals(2, liveBackgroundTaskCount(state, levelTaskIds = level, excludeToolIds = setOf(js("a"))))
    }

    @Test
    fun aMalformedTreeReadsAsNoWorkInsteadOfThrowing() {
        val hostile = JsObj.of(
            "activeTurnId" to js("t1"),
            "turnsById" to JsObj.of("t1" to JsObj.of("blocks" to js("nope"), "pendingApprovals" to JsArr.EMPTY)),
            "backgroundTasks" to JsArr.of(listOf(JsNum(1.0), JsObj.of("status" to js("running")))),
        )
        assertEquals(emptyList<String>(), ids(hostile))
        assertEquals(1, liveBackgroundTaskCount(hostile))
    }
}
