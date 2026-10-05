package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.OrNull
import com.tether.app.protocol.WorktreeCreateRequest
import com.tether.app.protocol.helpers.CodexModePresets
import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr

/**
 * ta-8cv (T8.1 slice 1): the new-session `create` frame, built exactly as the web's draft composer
 * builds it (hooks/use-draft-composer.ts submit, 887c222 ~313-347), from the draft form and its
 * `userModified` guard (both the lib/draft-form.ts shapes, [DraftForm]) and the catalog row the
 * session starts on.
 *
 * Every key the web sends, with the web's rule:
 * - `provider` = the row's engine; `cwd` = the form's folder; `requestId` = the submit's token (v76).
 * - `permissionMode`: Claude and opencode send the form's mode (empty: `bypassPermissions`, the web's
 *   cold-start default); every other engine sends `bypassPermissions` (no engine meaning there; the
 *   web keeps it for wire-shape symmetry).
 * - `sandboxPolicy`: Codex sends its Mode preset's tier ([CodexModePresets]); every other engine,
 *   Claude included, sends none (ta-93qs: tether 6e38663's composer names no tier for Claude, so the
 *   server's one rule decides, as it does for the web).
 * - `approvalPolicy`: Codex sends `"never"` when its preset says so (Full access); opencode sends
 *   `"never"` when its mode is `bypassPermissions` (Build + Auto, issue #44); otherwise absent.
 * - `approvalsReviewer`: `"auto_review"` for Codex's Auto-review preset only.
 * - `useWorktree` always; `worktree` only when [DraftForm.buildWorktreeCreateRequest] makes one
 *   ([WorktreeDraft.request]: the web's builder as it is; [NewSessionGuard.resolve] then refuses an
 *   isolated create with no block at all, which the server would take for a default new branch).
 * - `profileId` only for a profile row.
 * - `model` / `reasoningEffort` only when the operator picked them in THIS draft (userModified) and
 *   they are non-empty: a display pre-selection is never pinned.
 *
 * ta-coik.4 (owner rule: the app is as capable as the web): the form's mode, model and effort ride
 * as they are, exactly as use-draft-composer.ts:311-353 sends them. A mode the provider does not
 * offer (a retired `dontAsk`, an older build's value) and an effort the model does not list go out
 * unchanged; the server validates.
 *
 * Pure: no I/O, no clock, no randomness (the caller mints [requestId]).
 */
object CreateFrame {

    /** use-draft-composer.ts: the cold-start / non-mode providers' permissionMode. */
    const val DEFAULT_PERMISSION_MODE = DraftForm.CLAUDE_DEFAULT_PERMISSION_MODE

    fun build(form: JsObj, entry: ProviderCatalogEntry, modified: JsObj, requestId: String): ClientMessage.Create {
        val provider = entry.provider
        val isClaude = provider == "claude"
        val isCodex = provider == "codex"
        val isOpencode = provider == "opencode"
        // use-draft-composer.ts:305-311: the form's mode as it is.
        val mode = form.s("mode")
        // codexModePreset degrades any non-codex mode value to its "default" preset, harmlessly.
        val codexPreset = CodexModePresets.codexModePreset(JsStr(mode))
        val autoMode = !isCodex && mode == DEFAULT_PERMISSION_MODE
        // use-draft-composer.ts:312 buildWorktreeCreateRequest.
        val worktree = WorktreeDraft.request(form)?.let(::worktreeRequest)
        val approvalPolicy: String? = when {
            isCodex -> (codexPreset["approvalPolicy"] as? JsStr)?.value
            autoMode && isOpencode -> "never"
            else -> null
        }
        val approvalsReviewer = if (isCodex && (codexPreset["approvalsReviewer"] as? JsStr)?.value == "auto_review") "auto_review" else null
        val model = form.s("model")
        val effort = form.s("reasoningEffort")
        return ClientMessage.Create(
            provider = provider,
            cwd = form.s("cwd"),
            requestId = requestId,
            permissionMode = if (isClaude || isOpencode) mode.ifEmpty { DEFAULT_PERMISSION_MODE } else DEFAULT_PERMISSION_MODE,
            sandboxPolicy = if (isCodex) (codexPreset["sandboxPolicy"] as JsStr).value else null,
            approvalPolicy = approvalPolicy?.let { OrNull(it) },
            approvalsReviewer = approvalsReviewer?.let { OrNull(it) },
            useWorktree = form["useWorktree"] == JsBool.TRUE,
            worktree = worktree,
            profileId = entry.profileId?.takeIf { it.isNotEmpty() },
            model = model.takeIf { modified.flag("model") && it.isNotEmpty() },
            reasoningEffort = effort.takeIf { modified.flag("reasoningEffort") && it.isNotEmpty() },
        )
    }

    /** The v98 `worktree` block, from [DraftForm.buildWorktreeCreateRequest]'s object. */
    internal fun worktreeRequest(o: JsObj): WorktreeCreateRequest = WorktreeCreateRequest(
        mode = (o["mode"] as? JsStr)?.value ?: "branch-off",
        baseRef = (o["baseRef"] as? JsStr)?.value,
        branch = (o["branch"] as? JsStr)?.value,
        slug = (o["slug"] as? JsStr)?.value,
        prNumber = (o["prNumber"] as? JsNum)?.value?.toLong(),
    )

    private fun JsObj.s(key: String): String = (this[key] as? JsStr)?.value ?: ""

    private fun JsObj.flag(key: String): Boolean = this[key] == JsBool.TRUE
}

/**
 * ta-8cv: a catalog row as the lib/draft-form.ts reducer reads one (a `ProviderCatalogEntry` tree:
 * key, provider, status, models with their variants, defaultModel, label, profileId).
 */
fun ProviderCatalogEntry.toDraftEntry(): JsObj = JsObj.of(
    "key" to JsStr(key),
    "provider" to JsStr(provider),
    "status" to JsStr(status),
    "models" to JsArr.of(
        models.map { m ->
            JsObj.of(
                "value" to JsStr(m.value),
                "displayName" to JsStr(m.displayName),
                "resolvedModel" to m.resolvedModel?.let(::JsStr),
                "variants" to m.variants?.let { vs -> JsArr.of(vs.map { v -> JsObj.of("value" to JsStr(v.value), "label" to JsStr(v.label)) }) },
            )
        },
    ),
    "defaultModel" to defaultModel?.let(::JsStr),
    "label" to label?.let(::JsStr),
    "profileId" to profileId?.let(::JsStr),
)
