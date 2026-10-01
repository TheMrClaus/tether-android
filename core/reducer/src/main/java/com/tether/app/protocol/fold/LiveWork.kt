package com.tether.app.protocol.fold

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.arr
import com.tether.app.protocol.tree.obj

// ta-ceo: the live work a turn-wide Stop would destroy, read off the folded projection — the pure
// helpers engines/events.mjs exports at tether 887c222 (issue #183, moved there by #229 so the
// browser shares them with the server). The chat composer reads them for the deferred-message wait
// line and Stop's cost (chat-view.tsx:1974-1977). The web is defensive only where the projection
// may legitimately lack a field; here a malformed field reads as empty instead of throwing, so a
// hostile tree can never crash the composer.

// events.mjs:425 — the tool calls of the in-flight turn that are genuinely RUNNING: started, not
// ended, and not parked on an approval or a question (a gated call has not run yet, so
// interrupting it destroys nothing). [excludeToolId] drops the call whose own tool_start is being
// evaluated. Insertion order is the turn's block order, as the web's Set.
fun runningToolIds(state: JsObj?, excludeToolId: JsValue? = null): Set<JsValue> {
    val running = LinkedHashSet<JsValue>()
    val turn = state?.let(::currentTurn) ?: return running
    val parked = HashSet<JsValue>()
    for (key in listOf("pendingApprovals", "pendingQuestions")) {
        turn[key].obj?.values?.forEach { request ->
            val toolId = request.obj?.get("toolId")
            if (truthy(toolId)) parked.add(toolId!!)
        }
    }
    val byId = turn["blocksById"].obj
    for (blockId in turn["blocks"].arr ?: JsArr.EMPTY) {
        if ((excludeToolId != null && strictEquals(blockId, excludeToolId)) || blockId in parked) continue
        val block = byId?.prop(blockId).obj ?: continue
        if ((block["kind"] as? JsStr)?.value == "tool" && block["done"] != JsBool.TRUE) running.add(blockId)
    }
    return running
}

// events.mjs:440
fun openToolCount(state: JsObj?, excludeToolId: JsValue? = null): Int = runningToolIds(state, excludeToolId).size

// events.mjs:452 — background tasks that are provably still running. [levelTaskIds] (the CLI's
// authoritative background_tasks_changed level, when this process has seen one) wins outright;
// otherwise a task whose task_started has no terminal task_completed. A task launched by a call in
// [excludeToolIds] is not counted (the server passes its running tools; the browser passes none).
fun liveBackgroundTaskCount(
    state: JsObj?,
    levelTaskIds: Collection<JsValue>? = null,
    excludeToolIds: Set<JsValue> = emptySet(),
): Int {
    val tasks = state?.get("backgroundTasks").arr ?: JsArr.EMPTY
    fun excluded(task: JsObj?): Boolean {
        val toolUseId = task?.get("toolUseId")
        return truthy(toolUseId) && toolUseId in excludeToolIds
    }
    if (levelTaskIds != null) {
        var count = 0
        for (taskId in levelTaskIds) {
            val task = tasks.firstOrNull { strictEquals(it.obj?.get("taskId"), taskId) }.obj
            if (!excluded(task)) count += 1
        }
        return count
    }
    return tasks.count { value ->
        val task = value.obj
        task != null && (task["status"] as? JsStr)?.value == "running" && !excluded(task)
    }
}
