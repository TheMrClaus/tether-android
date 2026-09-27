package com.tether.app.protocol.fold

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.arr
import com.tether.app.protocol.tree.js
import com.tether.app.protocol.tree.obj
import com.tether.app.protocol.tree.str

// T2.1 unit C (background): tasks 1986-2046 with 1538-1607; command_output_* 2192-2246;
// background commands 2247-2293 with 921-956, 1040; spawned runs 2294-2367 with 957-1039.
//
// H1 seeded normalizeCommandRunMeta (turn_started needs it for its acceptance cases).
internal fun foldBackground(state: JsObj, event: JsObj, type: String): JsObj = when (type) {
    "task_started" -> foldTaskStarted(state, event)
    "task_progress" -> foldTaskProgress(state, event)
    "task_completed" -> foldTaskCompleted(state, event)
    "background_tasks_changed" -> foldBackgroundTasksChanged(state, event)
    "command_output_started" -> foldCommandOutputStarted(state, event)
    "command_output_delta" -> foldCommandOutputDelta(state, event)
    "command_output_completed" -> foldCommandOutputCompleted(state, event)
    "background_command_updated" -> foldBackgroundCommandUpdated(state, event)
    "background_command_output" -> foldBackgroundCommandOutput(state, event)
    "spawned_run_updated" -> foldSpawnedRunUpdated(state, event)
    "spawned_run_output" -> foldSpawnedRunOutput(state, event)
    "spawned_run_media" -> foldSpawnedRunMedia(state, event)
    else -> state
}

/** `value ?? null` for a `string | null | undefined` result. */
private fun jsOrNull(value: String?): JsValue = value?.let { JsStr(it) } ?: JsNull

/** `Number.isInteger(v) ? v : null`. */
private fun integerOrNull(value: JsValue?): JsValue = if (isInteger(value)) value!! else JsNull

/** `typeof v === "string" ? (boundedDisplayText(v, labelChars) ?? null) : null`. */
private fun boundedSignal(value: JsValue?): JsValue =
    if (value is JsStr) jsOrNull(boundedDisplayText(value, Limits.PROVIDER_PROJECTION_LIMITS.labelChars)) else JsNull

// ---- background tasks ----

// events.mjs:1546
private fun normalizeTaskStatus(value: JsValue?): String? {
    val s = value.str ?: return null
    if (s in Limits.TASK_TERMINAL_STATUSES) return s
    if (s in Limits.TASK_PROGRESS_STATUSES) return "running"
    return null
}

// events.mjs:1552
private fun taskIdentifier(value: JsValue?): String? {
    if (value !is JsStr || value.value.isEmpty()) return null
    return boundedDisplayText(value, Limits.BACKGROUND_TASK_LIMITS.idChars)
}

private fun isTerminalTask(status: JsValue?): Boolean = status.str?.let { it in Limits.TASK_TERMINAL_STATUSES } == true

// events.mjs:1561 — `patch` never carries an undefined value (every optional key is spread
// conditionally), so the JS delete-undefined loop is a no-op here.
private fun foldBackgroundTask(state: JsObj, taskId: String, patch: JsObj): JsObj {
    val tasks = state["backgroundTasks"].arr!!
    val index = tasks.indexOfFirst { strictEquals(it.obj?.get("taskId"), JsStr(taskId)) }
    val existing = if (index == -1) null else tasks[index].obj
    val base = existing ?: JsObj.of(
        "taskId" to js(taskId),
        "toolUseId" to JsNull,
        "status" to js("running"),
        "live" to JsBool.FALSE,
    )
    var next = base.spread(patch).put("taskId", js(taskId))
    if (next["status"] == null) next = next.put("status", js("running"))
    if (existing != null && isTerminalTask(existing["status"])) next = next.put("status", existing["status"])
    if (index == -1) {
        return state.put("backgroundTasks", tasks.add(next).slice(-Limits.BACKGROUND_TASK_LIMITS.tasks))
    }
    // Identity rule: a patch that changes nothing folds to the same state (JS returns an equal copy).
    if (next == existing) return state
    return state.put("backgroundTasks", tasks.set(index, next))
}

// events.mjs:1585
private fun applyBackgroundTaskLevel(state: JsObj, taskIds: List<String>): JsObj {
    val live = taskIds.toHashSet()
    val seen = HashSet<String>()
    val next = ArrayList<JsValue>()
    var changed = false
    for (taskValue in state["backgroundTasks"].arr!!) {
        val task = taskValue.obj!!
        val id = jsToString(task["taskId"])
        seen.add(id)
        val isLive = task["taskId"] is JsStr && id in live && !isTerminalTask(task["status"])
        if (strictEquals(js(isLive), task["live"])) {
            next.add(task)
            continue
        }
        changed = true
        next.add(task.put("live", js(isLive)))
    }
    for (taskId in taskIds) {
        if (taskId in seen) continue
        changed = true
        next.add(JsObj.of("taskId" to js(taskId), "toolUseId" to JsNull, "status" to js("running"), "live" to JsBool.TRUE))
    }
    if (!changed) return state
    return state.put("backgroundTasks", JsArr.of(next).slice(-Limits.BACKGROUND_TASK_LIMITS.tasks))
}

// events.mjs:1989
private fun foldTaskStarted(state: JsObj, event: JsObj): JsObj {
    val taskId = taskIdentifier(event["taskId"]) ?: return state
    val labelChars = Limits.BACKGROUND_TASK_LIMITS.labelChars
    val toolUseId = boundedIdentifier(event["toolUseId"])
    val patch = JsObj.of(
        "status" to js("running"),
        "toolUseId" to toolUseId?.takeIf { it.isNotEmpty() }?.let { js(it) },
        "taskType" to jsOrUndefined(boundedDisplayText(event["taskType"], labelChars)),
        "subagentType" to jsOrUndefined(boundedDisplayText(event["subagentType"], labelChars)),
    )
    return foldBackgroundTask(state, taskId, patch)
}

// events.mjs:2000
private fun foldTaskProgress(state: JsObj, event: JsObj): JsObj {
    val taskId = taskIdentifier(event["taskId"]) ?: return state
    val toolUseId = boundedIdentifier(event["toolUseId"])
    val patch = JsObj.of(
        "status" to js(normalizeTaskStatus(event["status"]) ?: "running"),
        "toolUseId" to toolUseId?.takeIf { it.isNotEmpty() }?.let { js(it) },
        "lastToolName" to jsOrUndefined(boundedDisplayText(event["lastToolName"], Limits.BACKGROUND_TASK_LIMITS.labelChars)),
        "totalTokens" to nonNegativeFiniteNumber(event["totalTokens"]),
        "toolUses" to nonNegativeFiniteNumber(event["toolUses"]),
        "durationMs" to nonNegativeFiniteNumber(event["durationMs"]),
    )
    return foldBackgroundTask(state, taskId, patch)
}

// events.mjs:2018
private fun foldTaskCompleted(state: JsObj, event: JsObj): JsObj {
    val taskId = taskIdentifier(event["taskId"]) ?: return state
    val status = normalizeTaskStatus(event["status"])
    if (status == null || status !in Limits.TASK_TERMINAL_STATUSES) return state
    val toolUseId = boundedIdentifier(event["toolUseId"])
    val patch = JsObj.of(
        "status" to js(status),
        "live" to JsBool.FALSE,
        "toolUseId" to toolUseId?.takeIf { it.isNotEmpty() }?.let { js(it) },
        "totalTokens" to nonNegativeFiniteNumber(event["totalTokens"]),
        "toolUses" to nonNegativeFiniteNumber(event["toolUses"]),
        "durationMs" to nonNegativeFiniteNumber(event["durationMs"]),
    )
    return foldBackgroundTask(state, taskId, patch)
}

// events.mjs:2037
private fun foldBackgroundTasksChanged(state: JsObj, event: JsObj): JsObj {
    val raw = event["taskIds"]
    val taskIds = if (raw is JsArr) raw.mapNotNull { taskIdentifier(it) } else emptyList()
    return applyBackgroundTaskLevel(state, taskIds)
}

// ---- foreground command output ----

/** The coalescing append shared by command_output_delta and background_command_output. */
private fun appendSegment(segments: JsArr, stream: String, text: String): JsArr {
    val last = segments.lastOrNull().obj
    if (last != null && strictEquals(last["stream"], JsStr(stream))) {
        return segments.set(
            segments.size - 1,
            JsObj.of("stream" to js(stream), "text" to js(jsToString(last["text"]) + text)),
        )
    }
    return segments.add(JsObj.of("stream" to js(stream), "text" to js(text)))
}

// events.mjs:2192
private fun foldCommandOutputStarted(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val limits = Limits.PROVIDER_PROJECTION_LIMITS
    val command = boundedDisplayText(event["command"], limits.commandChars)
    val logFile = boundedDisplayText(event["logFile"], limits.pathChars)
    return updateTurn(state) { turn ->
        upsertBlock(turn, event["blockId"]) { existing ->
            existing ?: JsObj.of(
                "blockId" to event["blockId"],
                "kind" to js("command_output"),
                "command" to jsOrUndefined(command),
                "logFile" to jsOrUndefined(logFile),
                "segments" to JsArr.EMPTY,
                "done" to JsBool.FALSE,
            )
        }
    }
}

// events.mjs:2210
private fun foldCommandOutputDelta(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    val text = event["text"].str
    if (text.isNullOrEmpty()) return state
    val stream = if (event["stream"].str == "stderr") "stderr" else "stdout"
    return updateTurn(state) { turn ->
        upsertBlock(turn, event["blockId"]) { existing ->
            val base = existing ?: JsObj.of(
                "blockId" to event["blockId"],
                "kind" to js("command_output"),
                "segments" to JsArr.EMPTY,
                "done" to JsBool.FALSE,
            )
            val segments = if (truthy(base["segments"])) base["segments"].arr!! else JsArr.EMPTY
            base.put("segments", appendSegment(segments, stream, text))
        }
    }
}

// events.mjs:2231
private fun foldCommandOutputCompleted(state: JsObj, event: JsObj): JsObj {
    if (!isOpenCurrentTurn(state, event["turnId"])) return state
    return updateTurn(state) { turn ->
        upsertBlock(turn, event["blockId"]) { existing ->
            (existing ?: JsObj.of("blockId" to event["blockId"], "kind" to js("command_output"), "segments" to JsArr.EMPTY)).with(
                "done" to JsBool.TRUE,
                "exitCode" to integerOrNull(event["exitCode"]),
                "signal" to (event["signal"] as? JsStr ?: JsNull),
                "timedOut" to js(event["timedOut"] === JsBool.TRUE),
                "killed" to js(event["killed"] === JsBool.TRUE),
                "outputTruncated" to js(event["outputTruncated"] === JsBool.TRUE),
                "durationMs" to (nonNegativeFiniteNumber(event["durationMs"]) ?: js(0)),
            )
        }
    }
}

// ---- background commands ----

// events.mjs:933
private fun normalizeBackgroundCommand(event: JsObj): JsObj? {
    val limits = Limits.PROVIDER_PROJECTION_LIMITS
    val commandId = boundedIdentifier(event["commandId"])
    val command = boundedDisplayText(event["command"], limits.commandChars)
    val cwd = boundedDisplayText(event["cwd"], limits.pathChars)
    val logFile = boundedDisplayText(event["logFile"], limits.pathChars)
    if (commandId.isNullOrEmpty() || command == null || cwd == null || logFile == null) return null
    val status = event["status"].str
    if (status == null || status !in Limits.BACKGROUND_COMMAND_STATUSES) return null
    return JsObj.of(
        "commandId" to js(commandId),
        "command" to js(command),
        "cwd" to js(cwd),
        "logFile" to js(logFile),
        "status" to js(status),
        "exitCode" to integerOrNull(event["exitCode"]),
        "signal" to boundedSignal(event["signal"]),
        "startedAt" to (nonNegativeFiniteNumber(event["startedAt"]) ?: js(0)),
        "endedAt" to (nonNegativeFiniteNumber(event["endedAt"]) ?: JsNull),
        "outputTruncated" to js(event["outputTruncated"] === JsBool.TRUE),
    )
}

// events.mjs:1040
private fun sameBackgroundCommand(a: JsObj, b: JsObj): Boolean =
    listOf("commandId", "status", "exitCode", "signal", "endedAt", "outputTruncated", "command", "logFile")
        .all { strictEquals(a[it], b[it]) }

// events.mjs:2247
private fun foldBackgroundCommandUpdated(state: JsObj, event: JsObj): JsObj {
    val next = normalizeBackgroundCommand(event) ?: return state
    val commands = state["backgroundCommands"].arr!!
    val index = commands.indexOfFirst { strictEquals(it.obj?.get("commandId"), next["commandId"]) }
    if (index == -1) {
        val base = if (commands.size >= Limits.MAX_BACKGROUND_COMMANDS) commands.slice(1) else commands
        return state.put("backgroundCommands", base.add(next.put("segments", JsArr.EMPTY)))
    }
    val existing = commands[index].obj!!
    if (sameBackgroundCommand(existing, next)) return state
    val merged = next.put("segments", coalesce(existing["segments"], JsArr.EMPTY))
    return state.put("backgroundCommands", commands.set(index, merged))
}

// events.mjs:2271
private fun foldBackgroundCommandOutput(state: JsObj, event: JsObj): JsObj {
    val commandId = boundedIdentifier(event["commandId"])
    val text = event["text"].str
    if (commandId.isNullOrEmpty() || text.isNullOrEmpty()) return state
    val commands = state["backgroundCommands"].arr!!
    val index = commands.indexOfFirst { strictEquals(it.obj?.get("commandId"), JsStr(commandId)) }
    if (index == -1) return state
    val stream = if (event["stream"].str == "stderr") "stderr" else "stdout"
    val existing = commands[index].obj!!
    val segments = if (truthy(existing["segments"])) existing["segments"].arr!! else JsArr.EMPTY
    return state.put("backgroundCommands", commands.set(index, existing.put("segments", appendSegment(segments, stream, text))))
}

// ---- spawned runs ----

// events.mjs:964
private fun normalizeSpawnedRun(event: JsObj): JsObj? {
    val limits = Limits.PROVIDER_PROJECTION_LIMITS
    val runId = boundedIdentifier(event["runId"])
    val provider = boundedIdentifier(event["provider"])
    if (runId.isNullOrEmpty() || provider.isNullOrEmpty()) return null
    val status = event["status"].str
    val origin = event["origin"].str
    if (status == null || status !in Limits.SPAWNED_RUN_STATUSES || origin == null || origin !in Limits.SPAWNED_RUN_ORIGINS) {
        return null
    }
    fun optionalText(value: JsValue?, max: Int): JsValue =
        if (value is JsStr && value.value.isNotEmpty()) jsOrNull(boundedDisplayText(value, max)) else JsNull
    fun optionalId(value: JsValue?): JsValue = if (value is JsStr) jsOrNull(boundedIdentifier(value)) else JsNull
    val mode = event["mode"].str
    return JsObj.of(
        "runId" to js(runId),
        "origin" to js(origin),
        "provider" to js(provider),
        "title" to optionalText(event["title"], limits.labelChars),
        "prompt" to optionalText(event["prompt"], limits.commandChars),
        "mode" to if (mode != null && mode in Limits.SPAWNED_RUN_MODES) js(mode) else JsNull,
        "model" to optionalText(event["model"], limits.labelChars),
        "cwd" to optionalText(event["cwd"], limits.pathChars),
        "logFile" to optionalText(event["logFile"], limits.pathChars),
        "nativeId" to optionalId(event["nativeId"]),
        "parentTurnId" to optionalId(event["parentTurnId"]),
        "toolId" to optionalId(event["toolId"]),
        "status" to js(status),
        "exitCode" to integerOrNull(event["exitCode"]),
        "signal" to boundedSignal(event["signal"]),
        "startedAt" to (nonNegativeFiniteNumber(event["startedAt"]) ?: js(0)),
        "endedAt" to (nonNegativeFiniteNumber(event["endedAt"]) ?: JsNull),
    )
}

// events.mjs:996
private fun sameSpawnedRun(a: JsObj, b: JsObj): Boolean =
    Limits.SPAWNED_RUN_COMPARED_FIELDS.all { strictEquals(a[it], b[it]) }

// events.mjs:1007
private fun normalizeSpawnedRunMedia(item: JsValue?): JsObj? {
    if (item !is JsObj || !strictEquals(item["type"], JsStr("media_ref"))) return null
    val url = item["url"].str
    // `matches` (whole input): JS `$` has no before-final-newline match, Java's `find` would.
    if (url == null || !Limits.SPAWNED_RUN_MEDIA_URL.matches(url)) return null
    val mediaKind = when (item["mediaKind"].str) {
        "video" -> "video"
        "image" -> "image"
        else -> null
    }
    val mediaType = item["mediaType"].str
    if (mediaKind == null || mediaType == null || !mediaType.startsWith("$mediaKind/")) return null
    val source = item["source"].str
    val label = item["label"]
    return JsObj.of(
        "type" to js("media_ref"),
        "mediaKind" to js(mediaKind),
        "mediaType" to js(if (mediaType.length > 40) mediaType.substring(0, 40) else mediaType),
        "url" to js(url),
        "bytes" to (nonNegativeFiniteNumber(item["bytes"]) ?: js(0)),
        "source" to js(if (source != null && source in Limits.SPAWNED_RUN_MEDIA_SOURCES) source else "viewed"),
        "label" to if (label is JsStr && label.value.isNotEmpty()) {
            jsOrNull(boundedDisplayText(label, Limits.PROVIDER_PROJECTION_LIMITS.labelChars))
        } else {
            JsNull
        },
    )
}

// events.mjs:1027 — the SAME list when nothing is new.
private fun withSpawnedRunKeys(keys: JsValue?, run: JsObj): JsArr {
    val list = keys.arr ?: JsArr.EMPTY
    val fresh = listOf(run["runId"], run["nativeId"]).filter { key ->
        key is JsStr && key.value.isNotEmpty() && list.none { strictEquals(it, key) }
    }
    if (fresh.isEmpty()) return list
    var next = list
    for (key in fresh) next = next.add(key!!)
    return next.slice(-Limits.MAX_SPAWNED_RUN_KEYS)
}

// events.mjs:1037
private fun evictSpawnedRun(runs: JsArr): JsArr {
    val index = runs.indexOfFirst { !strictEquals(it.obj?.get("status"), JsStr("running")) }
    val drop = if (index == -1) 0 else index
    return JsArr.of(runs.filterIndexed { i, _ -> i != drop })
}

// events.mjs:2294
private fun foldSpawnedRunUpdated(state: JsObj, event: JsObj): JsObj {
    val next = normalizeSpawnedRun(event) ?: return state
    val runs = state["spawnedRuns"].arr ?: JsArr.EMPTY
    val index = runs.indexOfFirst { strictEquals(it.obj?.get("runId"), next["runId"]) }
    val spawnedRunKeys = withSpawnedRunKeys(state["spawnedRunKeys"], next)
    if (index == -1) {
        val base = if (runs.size >= Limits.MAX_SPAWNED_RUNS) evictSpawnedRun(runs) else runs
        return state.with(
            "spawnedRunKeys" to spawnedRunKeys,
            "spawnedRuns" to base.add(next.with("output" to js(""), "outputTruncated" to JsBool.FALSE)),
        )
    }
    val existing = runs[index].obj!!
    if (sameSpawnedRun(existing, next)) {
        return if (spawnedRunKeys === state["spawnedRunKeys"]) state else state.put("spawnedRunKeys", spawnedRunKeys)
    }
    val merged = next.with(
        "output" to coalesce(existing["output"], js("")),
        "outputTruncated" to js(existing["outputTruncated"] === JsBool.TRUE),
        "media" to existing["media"].arr,
    )
    return state.with("spawnedRunKeys" to spawnedRunKeys, "spawnedRuns" to runs.set(index, merged))
}

// events.mjs:2318
private fun foldSpawnedRunOutput(state: JsObj, event: JsObj): JsObj {
    val runId = boundedIdentifier(event["runId"])
    val text = event["text"].str
    if (runId.isNullOrEmpty() || text.isNullOrEmpty()) return state
    val runs = state["spawnedRuns"].arr ?: JsArr.EMPTY
    val index = runs.indexOfFirst { strictEquals(it.obj?.get("runId"), JsStr(runId)) }
    if (index == -1) return state
    val existing = runs[index].obj!!
    val current = jsToString(coalesce(existing["output"], js("")))
    val cap = Limits.SPAWNED_RUN_OUTPUT_CAP_CHARS
    // UTF-16 lengths and slicing on purpose (JS `.length` / `.slice`).
    if (current.length >= cap) {
        if (truthy(existing["outputTruncated"])) return state
        return state.put("spawnedRuns", runs.set(index, existing.put("outputTruncated", JsBool.TRUE)))
    }
    val room = cap - current.length
    val slice = if (text.length > room) text.substring(0, room) else text
    val updated = existing.with(
        "output" to js(current + slice),
        "outputTruncated" to js(existing["outputTruncated"] === JsBool.TRUE || slice.length < text.length),
    )
    return state.put("spawnedRuns", runs.set(index, updated))
}

// events.mjs:2344
private fun foldSpawnedRunMedia(state: JsObj, event: JsObj): JsObj {
    val runId = boundedIdentifier(event["runId"])
    val media = event["media"]
    if (runId.isNullOrEmpty() || media !is JsArr || media.isEmpty()) return state
    val runs = state["spawnedRuns"].arr ?: JsArr.EMPTY
    val index = runs.indexOfFirst { strictEquals(it.obj?.get("runId"), JsStr(runId)) }
    if (index == -1) return state
    val existing = runs[index].obj!!
    val current = existing["media"].arr ?: JsArr.EMPTY
    val seen = current.map { jsToString(it.obj?.get("url")) }.toHashSet()
    var next = current
    var fresh = 0
    for (raw in media.slice(0, Limits.MAX_SPAWNED_RUN_MEDIA)) {
        val item = normalizeSpawnedRunMedia(raw) ?: continue
        val url = item["url"].str!!
        if (url in seen) continue
        seen.add(url)
        next = next.add(item)
        fresh++
    }
    if (fresh == 0) return state
    return state.put("spawnedRuns", runs.set(index, existing.put("media", next.slice(-Limits.MAX_SPAWNED_RUN_MEDIA))))
}

// events.mjs:924 — `{ command, cwd, logFile }` or null.
internal fun normalizeCommandRunMeta(meta: JsValue?): JsValue {
    if (meta !is JsObj) return JsNull
    val limits = Limits.PROVIDER_PROJECTION_LIMITS
    val command = boundedDisplayText(meta["command"], limits.commandChars)
    val cwd = boundedDisplayText(meta["cwd"], limits.pathChars)
    val logFile = boundedDisplayText(meta["logFile"], limits.pathChars)
    if (command == null || cwd == null || logFile == null) return JsNull
    return JsObj.of("command" to js(command), "cwd" to js(cwd), "logFile" to js(logFile))
}
