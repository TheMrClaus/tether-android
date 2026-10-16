package com.tether.app.client

import com.tether.app.protocol.fold.jsTrim
import com.tether.app.protocol.helpers.CodexModePresets
import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ta-74gu (tether v151, tth-wtl.3): the pure form rules of the handover composer, ported key for key from the web's
 * lib/draft-form.ts at 62ceca46 (seedFromSource :822, codexCurrentModeOption :886, buildHandoverSuccessor :903, and
 * createPermissionModeFor :45). No I/O, no Compose.
 *
 * The server resolves the `successor` with `own(frame, key)` (lib/fresh-create.mjs:145-182): an ABSENT key inherits
 * the source's value, an explicit `null` is a choice. [buildSuccessor] therefore keeps absent and null distinct.
 * `toolApproval` is not built: Android has no Reasonix Approvals control (the draft form has no such field), and an
 * omitted `toolApproval` inherits the source's while the provider is unchanged (fresh-create.mjs:182).
 */
object HandoverDraftForm {
    /** The Codex Mode value that means "keep the source's raw sandbox / approvals / reviewer" (df:870). */
    const val CODEX_CURRENT_MODE = "current"

    /**
     * df:174 DraftCodexCurrent: the source's RAW triple, sent unchanged while Mode is [CODEX_CURRENT_MODE] and the
     * provider is unchanged. [approvalPolicy] is "never" or null; [approvalsReviewer] "auto_review" | "guardian_subagent"
     * | "user" | null.
     */
    data class CodexCurrent(
        val sandboxPolicy: String?,
        val approvalPolicy: String?,
        val approvalsReviewer: String?,
        val sourceName: String,
    )

    /** df:793 DraftSeed. [extraModel] is a source model the live catalog does not list (kept as-is, never a pin). */
    data class Seed(
        val key: String,
        val model: String,
        val reasoningEffort: String,
        val mode: String,
        val cwd: String,
        val codexCurrent: CodexCurrent?,
        val extraModel: String?,
    )

    private fun normalizeId(value: String?): String = if (value == null) "" else jsTrim(value)

    /**
     * df:822 seedFromSource: every field of the new lead's form, from the source. The model is the source's own id: one
     * the catalog does not list is kept as-is (and reported in [Seed.extraModel]), never replaced by a fallback; the
     * Mode follows each provider's own mapping; the cwd is the source's own folder.
     */
    fun seedFromSource(entry: ProviderCatalogEntry, source: AgentSession): Seed {
        val models: JsArr = entry.toDraftEntry()["models"] as JsArr
        val sourceModel = normalizeId(source.model)
        val canonical = if (sourceModel.isNotEmpty()) DraftForm.resolveCanonicalModelId(models, JsStr(sourceModel)) else ""
        val model = if (sourceModel.isNotEmpty()) canonical.ifEmpty { sourceModel } else ""
        val extraModel = if (sourceModel.isNotEmpty() && canonical.isEmpty()) sourceModel else null
        val known: JsArr = if (canonical.isNotEmpty()) models else JsArr.of(listOf(JsObj.of("value" to JsStr(model), "displayName" to JsStr(model))) + models.toList())
        val effort = DraftForm.resolveReasoningEffortForModel(known, JsStr(model), JsStr(normalizeId(source.reasoningEffort)))
        var mode = ""
        var current: CodexCurrent? = null
        when (entry.provider) {
            "claude" -> mode = normalizeId(source.permissionMode).ifEmpty { DraftForm.defaultModeFor(JsStr("claude")) }
            "codex" -> {
                val sandbox = source.sandboxPolicy
                val policy = if (source.approvalPolicy == "never") "never" else null
                val reviewer = if (source.approvalsReviewerCode == "auto_review") "auto_review" else null
                val preset = CodexModePresets.codexModeForSession(
                    JsObj.of(
                        "sandboxPolicy" to (sandbox?.let { JsStr(it) } ?: com.tether.app.protocol.tree.JsNull),
                        "approvalPolicy" to (policy?.let { JsStr(it) } ?: com.tether.app.protocol.tree.JsNull),
                        "approvalsReviewer" to (reviewer?.let { JsStr(it) } ?: com.tether.app.protocol.tree.JsNull),
                    ),
                )
                val triple = CodexModePresets.codexModePreset(JsStr(preset))
                val exact = (triple["sandboxPolicy"] as? JsStr)?.value == sandbox &&
                    (triple["approvalPolicy"] as? JsStr)?.value == policy &&
                    (triple["approvalsReviewer"] as? JsStr)?.value == (reviewer ?: "user")
                if (exact) {
                    mode = preset
                } else {
                    mode = CODEX_CURRENT_MODE
                    current = CodexCurrent(sandbox, policy, reviewer, source.name)
                }
            }
            "opencode" -> mode = if (source.approvalPolicy == "never") "bypassPermissions" else normalizeId(source.permissionMode)
            "reasonix" -> mode = normalizeId(source.permissionMode)
        }
        return Seed(entry.key, model, effort, mode, source.cwd, current, extraModel)
    }

    private val SANDBOX_WORDS = mapOf("workspace-write" to "workspace-write sandbox", "off" to "no sandbox")
    private val APPROVAL_WORDS = mapOf(
        "never" to "never asks",
        "on-request" to "asks on request",
        "on-failure" to "asks on failure",
        "untrusted" to "asks for untrusted commands",
    )
    private val REVIEWER_WORDS = mapOf(
        "user" to "you review",
        "auto_review" to "the auto-reviewer reviews",
        "guardian_subagent" to "the guardian subagent reviews",
    )

    /** df:886 codexCurrentModeOption: the "Current (custom)" Mode row, first in the list; danger only when unsandboxed. */
    data class CodexCurrentOption(val value: String, val label: String, val hint: String, val danger: Boolean)

    fun codexCurrentOption(current: CodexCurrent): CodexCurrentOption {
        val sandbox = SANDBOX_WORDS[current.sandboxPolicy.orEmpty()]
            ?: current.sandboxPolicy?.takeIf { it.isNotEmpty() }?.let { "$it sandbox" }
            ?: "its sandbox"
        val approvals = APPROVAL_WORDS[current.approvalPolicy.orEmpty()] ?: "Codex's default approvals"
        val reviewer = REVIEWER_WORDS[current.approvalsReviewer ?: "user"] ?: REVIEWER_WORDS.getValue("user")
        return CodexCurrentOption(
            value = CODEX_CURRENT_MODE,
            label = "Current (custom)",
            hint = "Kept from ${current.sourceName}: $sandbox, $approvals, $reviewer. Pick a mode to replace it.",
            danger = current.sandboxPolicy == "off",
        )
    }

    /** df:45 createPermissionModeFor (the successor builder's rule; the ordinary Create's is [CreateFrame]). */
    fun permissionModeFor(provider: String, formMode: String): String = when (provider) {
        "claude", "opencode" -> formMode.ifEmpty { "bypassPermissions" }
        "reasonix" -> "default"
        else -> "bypassPermissions"
    }

    /**
     * df:903 buildHandoverSuccessor. Every field the form shows is sent EXPLICITLY except where it still equals the seed
     * AND is empty (then the server inherits the source's value, "as it is"). The name rides only when the operator
     * edited it to something non-empty. Never a worktree, an attachment or text. [form] is the draft form tree.
     */
    fun buildSuccessor(
        form: JsObj,
        entry: ProviderCatalogEntry,
        seedModel: String,
        seedEffort: String,
        codexCurrent: CodexCurrent?,
        name: String,
        nameEdited: Boolean,
    ): JsonObject {
        fun f(key: String) = (form[key] as? JsStr)?.value ?: ""
        val provider = entry.provider
        val out = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        out["provider"] = JsonPrimitive(provider)
        out["cwd"] = JsonPrimitive(f("cwd"))
        entry.profileId?.takeIf { it.isNotEmpty() }?.let { out["profileId"] = JsonPrimitive(it) }
        val model = f("model")
        if (model != seedModel) out["model"] = JsonPrimitive(model.ifEmpty { "default" })
        else if (model.isNotEmpty()) out["model"] = JsonPrimitive(model)
        val effort = f("reasoningEffort")
        if (effort != seedEffort) out["reasoningEffort"] = JsonPrimitive(effort.ifEmpty { "default" })
        else if (effort.isNotEmpty()) out["reasoningEffort"] = JsonPrimitive(effort)
        val mode = f("mode")
        out["permissionMode"] = JsonPrimitive(permissionModeFor(provider, mode))
        if (provider == "codex") {
            if (mode == CODEX_CURRENT_MODE && codexCurrent != null) {
                codexCurrent.sandboxPolicy?.takeIf { it.isNotEmpty() }?.let { out["sandboxPolicy"] = JsonPrimitive(it) }
                out["approvalPolicy"] = codexCurrent.approvalPolicy?.let { JsonPrimitive(it) } ?: JsonNull
                // The wire carries only "auto_review" | null; a guardian reviewer is left to inherit (the server keeps it).
                if (codexCurrent.approvalsReviewer == "auto_review") out["approvalsReviewer"] = JsonPrimitive("auto_review")
                else if (codexCurrent.approvalsReviewer != "guardian_subagent") out["approvalsReviewer"] = JsonNull
            } else {
                val preset = CodexModePresets.codexModePreset(JsStr(mode))
                out["sandboxPolicy"] = JsonPrimitive((preset["sandboxPolicy"] as JsStr).value)
                out["approvalPolicy"] = (preset["approvalPolicy"] as? JsStr)?.value?.let { JsonPrimitive(it) } ?: JsonNull
                out["approvalsReviewer"] = if ((preset["approvalsReviewer"] as? JsStr)?.value == "auto_review") JsonPrimitive("auto_review") else JsonNull
            }
        } else if (provider == "opencode") {
            out["approvalPolicy"] = if (mode == "bypassPermissions") JsonPrimitive("never") else JsonNull
        }
        val trimmed = jsTrim(name)
        if (nameEdited && trimmed.isNotEmpty()) out["name"] = JsonPrimitive(trimmed)
        return JsonObject(out)
    }
}
