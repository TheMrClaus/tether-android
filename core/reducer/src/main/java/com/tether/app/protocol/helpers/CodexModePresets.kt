package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.isNullish
import com.tether.app.protocol.fold.jsToString
import com.tether.app.protocol.fold.strictEquals
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue

/** T2.2: faithful port of lib/codex-mode-presets.mjs — Codex's unified "Mode" picker (v100). */
object CodexModePresets {

    // lib/codex-mode-presets.mjs:35
    val CODEX_MODE_OPTIONS: List<JsObj> = listOf(
        JsObj.of(
            "value" to JsStr("default"),
            "label" to JsStr("Default permissions"),
            "hint" to JsStr("Edit files and run commands with Codex's default approval flow"),
        ),
        JsObj.of(
            "value" to JsStr("auto-review"),
            "label" to JsStr("Auto-review"),
            "hint" to JsStr(
                "Same workspace-write permissions as Default, but eligible on-request approvals are routed through " +
                    "the auto-reviewer subagent instead of you",
            ),
        ),
        JsObj.of(
            "value" to JsStr("full-access"),
            "label" to JsStr("Full access"),
            "hint" to JsStr("Edit files, run commands, and access the network without additional prompts"),
            "danger" to JsBool.TRUE,
        ),
    )

    private fun preset(sandboxPolicy: String, approvalPolicy: String?, approvalsReviewer: String) = JsObj.of(
        "sandboxPolicy" to JsStr(sandboxPolicy),
        "approvalPolicy" to (approvalPolicy?.let { JsStr(it) } ?: JsNull),
        "approvalsReviewer" to JsStr(approvalsReviewer),
    )

    // lib/codex-mode-presets.mjs:55
    private val CODEX_MODE_PRESETS: Map<String, JsObj> = linkedMapOf(
        "default" to preset("workspace-write", null, "user"),
        "auto-review" to preset("workspace-write", null, "auto_review"),
        "full-access" to preset("off", "never", "user"),
    )

    // lib/codex-mode-presets.mjs:61
    const val CODEX_DEFAULT_MODE = "default"

    // lib/codex-mode-presets.mjs:69 — unknown/absent values fall back to the "default" preset.
    fun codexModePreset(value: JsValue?): JsObj = CODEX_MODE_PRESETS[jsToString(value)] ?: CODEX_MODE_PRESETS.getValue(CODEX_DEFAULT_MODE)

    // lib/codex-mode-presets.mjs:80 — the picker value for a stored triple; "default" for anything else.
    fun codexModeForSession(session: JsValue?): String {
        if (isNullish(session)) {
            val shown = if (session == null) "undefined" else "object null"
            throw JsError("TypeError", "Cannot destructure property 'sandboxPolicy' of '$shown' as it is ${jsToString(session)}.")
        }
        val normalizedPolicy: JsValue = if (session["approvalPolicy"].isStr("never")) JsStr("never") else JsNull
        val normalizedReviewer = JsStr(if (session["approvalsReviewer"].isStr("auto_review")) "auto_review" else "user")
        for ((value, preset) in CODEX_MODE_PRESETS) {
            if (strictEquals(preset["sandboxPolicy"], session["sandboxPolicy"]) &&
                strictEquals(preset["approvalPolicy"], normalizedPolicy) &&
                strictEquals(preset["approvalsReviewer"], normalizedReviewer)
            ) {
                return value
            }
        }
        return CODEX_DEFAULT_MODE
    }
}
