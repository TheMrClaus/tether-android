package com.tether.app.protocol.fold

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.arr
import com.tether.app.protocol.tree.js
import com.tether.app.protocol.tree.obj
import com.tether.app.protocol.tree.str

// T2.1 unit C (session lists): CLI inventory 1633-1646 with 562-586, 1064-1183; MCP health
// 1946-1967; todo 1764-1847 with 750-920; plan, diff, reroute, fallback and review 1848-1945
// with 730-749, 1051.
//
// normalizeProjectedCliInventory / sameCliInventory are `internal` because unit A's
// native_session_id (events.mjs:1611) folds the init inventory through them.
internal fun foldSessionLists(state: JsObj, event: JsObj, type: String): JsObj = when (type) {
    "cli_inventory_reset" -> foldCliInventoryReset(state)
    "cli_commands_changed" -> foldCliCommandsChanged(state, event)
    "todo_updated" -> foldTodoUpdated(state, event)
    "todo_item_created" -> foldTodoItemCreated(state, event)
    "todo_item_id_assigned" -> foldTodoItemIdAssigned(state, event)
    "todo_item_updated" -> foldTodoItemUpdated(state, event)
    "plan_updated" -> foldPlanUpdated(state, event)
    "diff_updated" -> foldDiffUpdated(state, event)
    "model_rerouted" -> foldModelRerouted(state, event)
    "model_fallback" -> foldModelFallback(state, event)
    "review_started" -> foldReviewStarted(state, event)
    "review_completed" -> foldReviewCompleted(state, event)
    "mcp_health_updated" -> foldMcpHealthUpdated(state, event)
    else -> state
}

/** `value ?? null` for a `string | null | undefined` result. */
private fun jsOrNull(value: String?): JsValue = value?.let { JsStr(it) } ?: JsNull

/** A non-empty string or Kotlin null — the `x ? { x } : {}` spreads. */
private fun jsIfTruthy(value: String?): JsStr? = if (value.isNullOrEmpty()) null else JsStr(value)

// ---- CLI inventory ----

// events.mjs:1071
internal fun normalizeClaudeCommands(commands: JsValue?): JsArr {
    if (commands !is JsArr) return JsArr.EMPTY
    val limits = Limits.CLI_INVENTORY_LIMITS
    val normalized = ArrayList<JsValue>()
    val seen = HashSet<String>()
    for (raw in commands) {
        if (normalized.size >= limits.commands) break
        val source: JsObj? = if (raw is JsStr) JsObj.of("name" to raw) else raw.obj
        val name = identifier(source?.get("name"), stripLeadingSlash = true)
        if (name == null || name in seen) continue
        seen.add(name)
        var command = JsObj.of("name" to js(name))
        val description = boundedDisplayText(source?.get("description"), limits.descriptionChars)
        val argumentHint = boundedDisplayText(source?.get("argumentHint"), limits.argumentHintChars)
        if (description != null) command = command.put("description", js(description))
        if (argumentHint != null) command = command.put("argumentHint", js(argumentHint))
        val rawAliases = source?.get("aliases")
        if (rawAliases is JsArr) {
            val aliases = ArrayList<JsValue>()
            val aliasSeen = hashSetOf(name)
            for (rawAlias in rawAliases) {
                if (aliases.size >= limits.aliasesPerCommand) break
                val alias = identifier(rawAlias, stripLeadingSlash = true)
                if (alias == null || alias in aliasSeen) continue
                aliasSeen.add(alias)
                aliases.add(js(alias))
            }
            if (aliases.isNotEmpty()) command = command.put("aliases", JsArr.of(aliases))
        }
        normalized.add(command)
    }
    return JsArr.of(normalized)
}

// events.mjs:1106
internal fun normalizeClaudeTools(tools: JsValue?): JsArr {
    if (tools !is JsArr) return JsArr.EMPTY
    val normalized = ArrayList<JsValue>()
    val seen = HashSet<String>()
    for (raw in tools) {
        if (normalized.size >= Limits.CLI_INVENTORY_LIMITS.tools) break
        val name = identifier(raw)
        if (name == null || name in seen) continue
        seen.add(name)
        normalized.add(js(name))
    }
    return JsArr.of(normalized)
}

// events.mjs:1119
internal fun normalizeClaudeMcpServers(servers: JsValue?): JsArr {
    if (servers !is JsArr) return JsArr.EMPTY
    val normalized = ArrayList<JsValue>()
    val seen = HashSet<String>()
    for (raw in servers) {
        if (normalized.size >= Limits.CLI_INVENTORY_LIMITS.mcpServers) break
        val name = identifier(raw.obj?.get("name"))
        if (name == null || name in seen) continue
        seen.add(name)
        val status = raw.obj?.get("status").str
        normalized.add(
            JsObj.of(
                "name" to js(name),
                "status" to js(if (status != null && status in Limits.MCP_SERVER_STATUSES) status else "unknown"),
            ),
        )
    }
    return JsArr.of(normalized)
}

// events.mjs:1136
private fun sameCliCommand(a: JsValue?, b: JsValue?): Boolean {
    val ao = a.obj
    val bo = b.obj
    return strictEquals(ao?.get("name"), bo?.get("name")) &&
        strictEquals(ao?.get("description"), bo?.get("description")) &&
        strictEquals(ao?.get("argumentHint"), bo?.get("argumentHint")) &&
        sameStringList(coalesce(ao?.get("aliases"), JsArr.EMPTY), coalesce(bo?.get("aliases"), JsArr.EMPTY).arr ?: JsArr.EMPTY)
}

// events.mjs:1145 — `a`/`b` are a projected inventory object or JS null.
internal fun sameCliInventory(a: JsValue?, b: JsValue?): Boolean {
    if (a === JsNull || b === JsNull) return strictEquals(a, b)
    val ao = a.obj!!
    val bo = b.obj!!
    val aCommands = ao["commands"].arr!!
    val bCommands = bo["commands"].arr!!
    val aTools = ao["tools"].arr!!
    val bTools = bo["tools"].arr!!
    val aServers = ao["mcpServers"].arr!!
    val bServers = bo["mcpServers"].arr!!
    if (aCommands.size != bCommands.size || aTools.size != bTools.size || aServers.size != bServers.size) return false
    return aCommands.indices.all { sameCliCommand(aCommands[it], bCommands.getOrNull(it)) } &&
        sameStringList(aTools, bTools) &&
        aServers.indices.all { i ->
            val server = aServers[i].obj
            val other = bServers.getOrNull(i).obj
            strictEquals(server?.get("name"), other?.get("name")) && strictEquals(server?.get("status"), other?.get("status"))
        }
}

// events.mjs:1162 — JS null (Kotlin null here) for a non-array.
private fun normalizeProjectedCliCommands(commands: JsValue?): JsArr? =
    if (commands is JsArr) normalizeClaudeCommands(commands) else null

// events.mjs:1166 — JS null (Kotlin null here) unless `inventory` is a plain object.
internal fun normalizeProjectedCliInventory(inventory: JsValue?, previous: JsValue?): JsObj? {
    if (inventory !is JsObj) return null
    val commands = normalizeClaudeCommands(inventory["commands"])
    val previousCommands = HashMap<String, JsValue>()
    for (command in coalesce(previous.obj?.get("commands"), JsArr.EMPTY).arr ?: JsArr.EMPTY) {
        previousCommands[jsToString(command.obj?.get("name"))] = command
    }
    val merged = commands.map { command ->
        val c = command.obj!!
        val prior = previousCommands[c["name"].str!!]
        if (prior != null && c["description"] == null && c["argumentHint"] == null && c["aliases"] == null) prior else command
    }
    return JsObj.of(
        "commands" to JsArr.of(merged),
        "tools" to normalizeClaudeTools(inventory["tools"]),
        "mcpServers" to normalizeClaudeMcpServers(inventory["mcpServers"]),
    )
}

// events.mjs:1633
private fun foldCliInventoryReset(state: JsObj): JsObj =
    if (state["cliInventory"] === JsNull) state else state.put("cliInventory", JsNull)

// events.mjs:1636
private fun foldCliCommandsChanged(state: JsObj, event: JsObj): JsObj {
    val commands = normalizeProjectedCliCommands(event["commands"]) ?: return state
    val current = state["cliInventory"].obj
    val inventory = JsObj.of(
        "commands" to commands,
        "tools" to coalesce(current?.get("tools"), JsArr.EMPTY),
        "mcpServers" to coalesce(current?.get("mcpServers"), JsArr.EMPTY),
    )
    return if (sameCliInventory(state["cliInventory"], inventory)) state else state.put("cliInventory", inventory)
}

// ---- todo ----

// events.mjs:772
private fun normalizeTodoItems(items: JsValue?): JsArr {
    if (items !is JsArr) return JsArr.EMPTY
    val limits = Limits.TODO_LIMITS
    val normalized = ArrayList<JsValue>()
    for (item in items) {
        if (normalized.size >= limits.items) break
        val o = item.obj
        val content = boundedDisplayText(o?.get("content"), limits.textChars)
        val status = o?.get("status").str
        if (isBlank(content) || status == null || status !in Limits.TODO_STATUSES) continue
        val activeForm = boundedDisplayText(o?.get("activeForm"), limits.textChars)
        normalized.add(
            JsObj.of(
                "content" to js(content!!),
                "activeForm" to js(if (isBlank(activeForm)) "" else activeForm!!),
                "status" to js(status),
            ),
        )
    }
    return JsArr.of(normalized)
}

// events.mjs:797
private fun todoProjection(items: JsArr): JsObj {
    val active = items.firstOrNull { strictEquals(it.obj?.get("status"), JsStr("in_progress")) }?.obj
    return JsObj.of(
        "items" to items,
        "activeForm" to if (active != null) jsOr(active["activeForm"], active["content"]) else JsNull,
        "completed" to js(items.count { strictEquals(it.obj?.get("status"), JsStr("completed")) }),
        "total" to js(items.size),
    )
}

// events.mjs:807 — `a`/`b` are a TodoProjection or JS null.
private fun sameTodo(a: JsValue?, b: JsValue?): Boolean {
    if (a === JsNull || b === JsNull) return strictEquals(a, b)
    val ao = a.obj!!
    val bo = b.obj!!
    if (!strictEquals(ao["total"], bo["total"]) ||
        !strictEquals(ao["completed"], bo["completed"]) ||
        !strictEquals(ao["activeForm"], bo["activeForm"])
    ) {
        return false
    }
    val aItems = ao["items"].arr!!
    val bItems = bo["items"].arr!!
    return aItems.indices.all { i ->
        val item = aItems[i].obj
        val other = bItems.getOrNull(i).obj
        strictEquals(item?.get("content"), other?.get("content")) &&
            strictEquals(item?.get("activeForm"), other?.get("activeForm")) &&
            strictEquals(item?.get("status"), other?.get("status"))
    }
}

// events.mjs:910
private fun foldTodoTasks(state: JsObj, todoTasks: JsArr): JsObj {
    val items = normalizeTodoItems(
        JsArr.of(
            todoTasks.map { task ->
                val t = task.obj
                JsObj.of("content" to t?.get("subject"), "activeForm" to t?.get("activeForm"), "status" to t?.get("status"))
            },
        ),
    )
    val todo: JsValue = if (items.isNotEmpty()) todoProjection(items) else JsNull
    val nextTodo = if (sameTodo(state["todo"], todo)) state["todo"] else todo
    // JS compares todoTasks by reference; a structurally equal list is the same projection, and
    // returning `state` keeps the identity rule (an unchanged fold is the same object).
    val current = state["todoTasks"]
    if ((current === todoTasks || current == todoTasks) && state["todo"] === nextTodo) return state
    return state.with("todoTasks" to todoTasks, "todo" to nextTodo)
}

// events.mjs:1764
private fun foldTodoUpdated(state: JsObj, event: JsObj): JsObj {
    val items = normalizeTodoItems(event["items"])
    if (items.isEmpty()) return state
    val todo = todoProjection(items)
    return if (sameTodo(state["todo"], todo)) state else state.put("todo", todo)
}

private fun todoStatusOr(status: JsValue?, fallback: String): JsStr {
    val s = status.str
    return js(if (s != null && s in Limits.TODO_STATUSES) s else fallback)
}

// events.mjs:1782
private fun foldTodoItemCreated(state: JsObj, event: JsObj): JsObj {
    val todoTasks = state["todoTasks"].arr!!
    if (todoTasks.size >= Limits.TODO_LIMITS.items) return state
    val subject = boundedDisplayText(event["subject"], Limits.TODO_LIMITS.textChars)
    val toolId = event["toolId"].str
    if (isBlank(subject) || toolId.isNullOrEmpty()) return state
    val activeForm = boundedDisplayText(event["activeForm"], Limits.TODO_LIMITS.textChars)
    val entry = JsObj.of(
        "id" to js(toolId),
        "subject" to js(subject!!),
        "activeForm" to js(if (isBlank(activeForm)) "" else activeForm!!),
        "status" to todoStatusOr(event["status"], "pending"),
    )
    return foldTodoTasks(state, todoTasks.add(entry))
}

// events.mjs:1799
private fun foldTodoItemIdAssigned(state: JsObj, event: JsObj): JsObj {
    val taskId = js(boundedIdentifier(event["taskId"]) ?: return state)
    var changed = false
    val next = state["todoTasks"].arr!!.map { task ->
        val id = task.obj?.get("id")
        if (!strictEquals(id, event["toolId"]) || strictEquals(id, taskId)) {
            task
        } else {
            changed = true
            task.obj!!.put("id", taskId)
        }
    }
    return if (changed) foldTodoTasks(state, JsArr.of(next)) else state
}

// events.mjs:1815
private fun foldTodoItemUpdated(state: JsObj, event: JsObj): JsObj {
    val taskId = js(boundedIdentifier(event["taskId"]) ?: return state)
    val todoTasks = state["todoTasks"].arr!!
    val deleted = strictEquals(event["status"], JsStr("deleted"))
    val status = event["status"].str
    var found = false
    val next = ArrayList<JsValue>()
    for (task in todoTasks) {
        if (!strictEquals(task.obj?.get("id"), taskId)) {
            next.add(task)
            continue
        }
        found = true
        if (deleted) continue
        val subject = boundedDisplayText(event["subject"], Limits.TODO_LIMITS.textChars)
        val activeForm = boundedDisplayText(event["activeForm"], Limits.TODO_LIMITS.textChars)
        var patched = task.obj!!
        if (status != null && status in Limits.TODO_STATUSES) patched = patched.put("status", js(status))
        if (!isBlank(subject)) patched = patched.put("subject", js(subject!!))
        if (!isBlank(activeForm)) patched = patched.put("activeForm", js(activeForm!!))
        next.add(patched)
    }
    if (found) return foldTodoTasks(state, JsArr.of(next))
    val subject = boundedDisplayText(event["subject"], Limits.TODO_LIMITS.textChars)
    if (deleted || isBlank(subject) || todoTasks.size >= Limits.TODO_LIMITS.items) return state
    val activeForm = boundedDisplayText(event["activeForm"], Limits.TODO_LIMITS.textChars)
    return foldTodoTasks(
        state,
        todoTasks.add(
            JsObj.of(
                "id" to taskId,
                "subject" to js(subject!!),
                "activeForm" to js(if (isBlank(activeForm)) "" else activeForm!!),
                "status" to todoStatusOr(event["status"], "pending"),
            ),
        ),
    )
}

// ---- plan, diff, reroute, fallback, review ----

// events.mjs:730
private fun normalizePlanSteps(steps: JsValue?): JsArr {
    if (steps !is JsArr) return JsArr.EMPTY
    val limits = Limits.PROVIDER_PROJECTION_LIMITS
    val normalized = ArrayList<JsValue>()
    for (item in steps) {
        if (normalized.size >= limits.planSteps) break
        val step = boundedDisplayText(item.obj?.get("step"), limits.proseChars)
        val status = item.obj?.get("status").str
        if (step.isNullOrEmpty() || status == null || status !in Limits.PLAN_STEP_STATUSES) continue
        normalized.add(JsObj.of("step" to js(step), "status" to js(status)))
    }
    return JsArr.of(normalized)
}

// events.mjs:1848
private fun foldPlanUpdated(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val explanation: JsValue = if (isNullish(event["explanation"])) {
        JsNull
    } else {
        jsOrNull(boundedDisplayText(event["explanation"], Limits.PROVIDER_PROJECTION_LIMITS.proseChars))
    }
    val plan = JsObj.of("explanation" to explanation, "steps" to normalizePlanSteps(event["steps"]))
    return updateTurn(state) { turn ->
        turn.with("initialPlan" to coalesce(turn["initialPlan"], plan), "plan" to plan)
    }
}

// events.mjs:1861
private fun foldDiffUpdated(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"]) || event["unifiedDiff"] !is JsStr) return state
    val unifiedDiff = boundedDisplayText(event["unifiedDiff"], Limits.PROVIDER_PROJECTION_LIMITS.diffChars)!!
    return updateTurn(state) { turn -> turn.put("diff", JsObj.of("unifiedDiff" to js(unifiedDiff))) }
}

// events.mjs:1867
private fun foldModelRerouted(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val fromModel = boundedIdentifier(event["fromModel"])
    val toModel = boundedIdentifier(event["toModel"])
    val reason = boundedDisplayText(event["reason"], Limits.PROVIDER_PROJECTION_LIMITS.proseChars)
    if (fromModel.isNullOrEmpty() || toModel.isNullOrEmpty() || reason.isNullOrEmpty()) return state
    return updateTurn(state) { turn ->
        val reroutes = turn["modelReroutes"].arr!!
        val last = reroutes.lastOrNull().obj
        if (last?.get("fromModel").str == fromModel && last?.get("toModel").str == toModel && last?.get("reason").str == reason) {
            turn
        } else {
            val reroute = JsObj.of("fromModel" to js(fromModel), "toModel" to js(toModel), "reason" to js(reason))
            turn.put("modelReroutes", reroutes.add(reroute).slice(-Limits.PROVIDER_PROJECTION_LIMITS.modelReroutes))
        }
    }
}

// events.mjs:1059 — private copy of unit B's appendProviderNotice (B owns 1059-1063).
private fun appendProviderNoticeC(notices: JsArr, notice: JsObj): JsArr {
    if (notices.any { strictEquals(it.obj?.get("noticeId"), notice["noticeId"]) }) return notices
    return notices.add(notice).slice(-Limits.PROVIDER_PROJECTION_LIMITS.providerNotices)
}

// events.mjs:1884
private fun foldModelFallback(state: JsObj, event: JsObj): JsObj {
    val limits = Limits.PROVIDER_PROJECTION_LIMITS
    val message = boundedDisplayText(event["message"], limits.proseChars)
    if (message.isNullOrEmpty()) return state
    val fromModel = boundedIdentifier(event["fromModel"])
    val toModel = boundedIdentifier(event["toModel"])
    val trigger = boundedDisplayText(event["trigger"], limits.labelChars)
    val fallbackId = boundedIdentifier(event["fallbackId"])
    val fallback = JsObj.of(
        "message" to js(message),
        "fallbackId" to jsIfTruthy(fallbackId),
        "fromModel" to jsIfTruthy(fromModel),
        "toModel" to jsIfTruthy(toModel),
        "trigger" to jsIfTruthy(trigger),
    )
    val turnId = event["turnId"]
    val turnScoped = !isNullish(turnId)
    if (turnScoped && !isOpenCurrentTurn(state, turnId)) return state
    val existing = if (turnScoped) {
        coalesce(state["turnsById"].obj!!.prop(turnId).obj?.get("modelFallbacks"), JsArr.EMPTY).arr ?: JsArr.EMPTY
    } else {
        JsArr.EMPTY
    }
    if (!fallbackId.isNullOrEmpty() && existing.any { it.obj?.get("fallbackId").str == fallbackId }) return state
    val noticeId = "model_fallback:" + (fallbackId ?: if (turnScoped) numberToString(existing.size.toDouble()) else "session")
    val dismissKey = providerNoticeDismissKey(turnId, js(noticeId))
    val notice = JsObj.of(
        "noticeId" to js(noticeId),
        "level" to js("warning"),
        "code" to js(modelFallbackLeadIn(jsOrUndefined(trigger))),
        "message" to js(message),
        "dismissKey" to js(dismissKey),
    )
    val dismissed = isNoticeDismissed(state, dismissKey)
    val next = state.put("lastModelFallback", JsObj.of("turnId" to turnId.orJsNull()).spread(fallback))
    if (!turnScoped) {
        if (dismissed) return next
        return next.put("providerNotices", appendProviderNoticeC(state["providerNotices"].arr!!, notice))
    }
    return updateTurn(next) { turn ->
        val fallbacks = coalesce(turn["modelFallbacks"], JsArr.EMPTY).arr ?: JsArr.EMPTY
        val turnNotices = turn["providerNotices"]
        turn.with(
            "modelFallbacks" to fallbacks.add(fallback).slice(-limits.modelFallbacks),
            "providerNotices" to if (dismissed) turnNotices else appendProviderNoticeC(turnNotices.arr!!, notice),
        )
    }
}

// events.mjs:1051
private fun upsertReview(reviews: JsArr, review: JsObj): JsArr {
    val existingIndex = reviews.indexOfFirst { strictEquals(it.obj?.get("reviewId"), review["reviewId"]) }
    val next = if (existingIndex == -1) {
        reviews.add(review)
    } else {
        reviews.set(existingIndex, reviews[existingIndex].obj!!.spread(review))
    }
    return next.slice(-Limits.PROVIDER_PROJECTION_LIMITS.reviews)
}

// events.mjs:1928
private fun foldReviewStarted(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val reviewId = boundedIdentifier(event["reviewId"])
    if (reviewId.isNullOrEmpty()) return state
    val target = boundedDisplayText(event["target"], Limits.PROVIDER_PROJECTION_LIMITS.proseChars)
    val review = JsObj.of("reviewId" to js(reviewId), "status" to js("started"), "target" to jsOrUndefined(target))
    return updateTurn(state) { turn -> turn.put("reviews", upsertReview(turn["reviews"].arr!!, review)) }
}

// events.mjs:1937
private fun foldReviewCompleted(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val reviewId = boundedIdentifier(event["reviewId"])
    val status = event["status"].str
    if (reviewId.isNullOrEmpty() || status == null || status !in Limits.REVIEW_COMPLETION_STATUSES) return state
    val result = boundedDisplayText(event["result"], Limits.PROVIDER_PROJECTION_LIMITS.proseChars)
    val review = JsObj.of("reviewId" to js(reviewId), "status" to js(status), "result" to jsOrUndefined(result))
    return updateTurn(state) { turn -> turn.put("reviews", upsertReview(turn["reviews"].arr!!, review)) }
}

// ---- MCP health ----

// events.mjs:1946
private fun foldMcpHealthUpdated(state: JsObj, event: JsObj): JsObj {
    val limits = Limits.PROVIDER_PROJECTION_LIMITS
    val name = boundedIdentifier(event["name"])
    if (name.isNullOrEmpty()) return state
    val rawStatus = event["status"].str
    val status = if (rawStatus != null && rawStatus in Limits.MCP_HEALTH_STATUSES) rawStatus else "unknown"
    val error = boundedDisplayText(event["error"], limits.proseChars)
    val failureReason = boundedDisplayText(event["failureReason"], limits.labelChars)
    val health = JsObj.of(
        "name" to js(name),
        "status" to js(status),
        "error" to jsOrUndefined(error),
        "failureReason" to jsOrUndefined(failureReason),
    )
    val mcpHealth = state["mcpHealth"].obj!!
    val previous = mcpHealth[name].obj
    if (strictEquals(previous?.get("status"), health["status"]) &&
        strictEquals(previous?.get("error"), health["error"]) &&
        strictEquals(previous?.get("failureReason"), health["failureReason"])
    ) {
        return state
    }
    if (previous == null && mcpHealth.size >= limits.mcpServers) return state
    return state.put("mcpHealth", mcpHealth.put(name, health))
}
