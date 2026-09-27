package com.tether.app.protocol.fold

import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.js

// T2.1 unit C (background): tasks 1986-2046 with 1538-1607; command_output_* 2192-2246;
// background commands 2247-2293 with 921-956, 1040; spawned runs 2294-2367 with 957-1039.
//
// H1 seeded normalizeCommandRunMeta (turn_started needs it for its acceptance cases).
internal fun foldBackground(state: JsObj, event: JsObj, type: String): JsObj = when (type) {
    else -> state // not yet ported (unit C)
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
