package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.isNullish
import com.tether.app.protocol.fold.jsToString
import com.tether.app.protocol.fold.jsTrim
import com.tether.app.protocol.fold.strictEquals
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.js

/**
 * T2.2: faithful port of lib/draft-form.ts — the new-session draft composer's form reducer
 * (NEW_SESSION_COMPOSER_PLAN §B3): resolution precedence, the `userModified` guard, provider
 * switches, custom models (issue #45), and the v98 worktree request. Forms, preferences and
 * catalog entries are projection trees (JsObj), exactly the shapes the web stores.
 */
object DraftForm {

    // lib/draft-form.ts:38
    const val CLAUDE_DEFAULT_PERMISSION_MODE = "bypassPermissions"

    // lib/draft-form.ts:40
    fun defaultModeFor(provider: JsValue?): String = when {
        provider.isStr("claude") -> CLAUDE_DEFAULT_PERMISSION_MODE
        provider.isStr("codex") -> CodexModePresets.CODEX_DEFAULT_MODE
        else -> ""
    }

    // lib/draft-form.ts:61 — pinned → default → current → root, blanks skipped, first occurrence wins.
    fun buildWorkspaceQuickPicks(input: JsValue?): JsArr {
        val picks = ArrayList<JsValue>()
        val seen = HashSet<String>()
        fun push(kind: String, label: String, candidate: JsValue?) {
            val path = jsTrim((candidate as? JsStr)?.value ?: throw JsError("TypeError", "candidate.trim is not a function"))
            if (path.isEmpty() || !seen.add(path)) return
            picks.add(JsObj.of("kind" to JsStr(kind), "label" to JsStr(label), "path" to JsStr(path)))
        }
        val pinned = input["pinnedProjects"]
        if (!isNullish(pinned)) for (project in jsIterate(pinned)) push("pinned", "Pinned", project)
        push("default", "Default workspace", input["defaultWorkspace"])
        push("current", "Current workspace", input["currentWorkspace"])
        push("root", "Workspace root", input["workspaceRoot"])
        return JsArr.of(picks)
    }

    private fun str(v: JsValue?): String = (v as? JsStr)?.value ?: throw JsError("TypeError", "${jsToString(v)}.trim is not a function")

    // lib/draft-form.ts:151 — v98: the create frame's `worktree` block, or null for a local session.
    fun buildWorktreeCreateRequest(form: JsValue?): JsObj? {
        if (!truthy(form["useWorktree"])) return null
        var request = JsObj.of("mode" to form["worktreeMode"])
        val baseRef = jsTrim(str(form["worktreeBaseRef"]))
        val branch = jsTrim(str(form["worktreeBranch"]))
        val slug = jsTrim(str(form["worktreeSlug"]))
        if (slug.isNotEmpty()) request = request.put("slug", JsStr(slug))
        if (form["worktreeMode"].isStr("checkout-pr")) {
            val prNumber = jsParseInt10(jsTrim(str(form["worktreePr"])))
            if (!isIntegral(prNumber) || prNumber <= 0) return null
            request = request.put("prNumber", js(prNumber))
            if (branch.isNotEmpty()) request = request.put("branch", JsStr(branch))
            return request
        }
        if (branch.isNotEmpty()) request = request.put("branch", JsStr(branch))
        if (form["worktreeMode"].isStr("checkout-branch")) return if (truthy(request["branch"])) request else null
        if (baseRef.isNotEmpty()) request = request.put("baseRef", JsStr(baseRef))
        return request
    }

    // lib/draft-form.ts:176
    val INITIAL_DRAFT_FORM: JsObj = JsObj.of(
        "key" to JsStr(""),
        "model" to JsStr(""),
        "reasoningEffort" to JsStr(""),
        "mode" to JsStr(""),
        "autoMode" to JsBool.FALSE,
        "cwd" to JsStr(""),
        "useWorktree" to JsBool.FALSE,
        "worktreeMode" to JsStr("branch-off"),
        "worktreeBaseRef" to JsStr(""),
        "worktreeBranch" to JsStr(""),
        "worktreeSlug" to JsStr(""),
        "worktreePr" to JsStr(""),
    )

    // lib/draft-form.ts:191
    val INITIAL_USER_MODIFIED: JsObj = JsObj.of(
        "key" to JsBool.FALSE,
        "model" to JsBool.FALSE,
        "reasoningEffort" to JsBool.FALSE,
        "mode" to JsBool.FALSE,
        "autoMode" to JsBool.FALSE,
        "cwd" to JsBool.FALSE,
        "useWorktree" to JsBool.FALSE,
        "worktree" to JsBool.FALSE,
    )

    // lib/draft-form.ts:202
    val INITIAL_DRAFT_FORM_STORE: JsObj = JsObj.of("form" to INITIAL_DRAFT_FORM, "userModified" to INITIAL_USER_MODIFIED)

    private fun obj(v: JsValue?): JsObj = v as? JsObj ?: JsObj.EMPTY

    // lib/draft-form.ts:209 — the pure per-provider preference merge ("" / false delete a field).
    fun mergeDraftPreferences(preferences: JsValue?, entryKey: JsValue?, updates: JsValue?): JsObj {
        val key = jsToString(entryKey)
        val existing = preferences["providerPreferences"][key] ?: JsObj.EMPTY
        var next = obj(existing)
        for (field in listOf("model", "reasoningEffort", "mode")) {
            val value = updates[field] ?: continue
            next = if (value.isStr("")) next.remove(field) else next.put(field, value)
        }
        val autoMode = updates["autoMode"]
        if (autoMode != null) next = if (truthy(autoMode)) next.put("autoMode", JsBool.TRUE) else next.remove("autoMode")
        return obj(preferences)
            .put("providerKey", entryKey)
            .put("providerPreferences", obj(preferences["providerPreferences"]).put(key, next))
    }

    // lib/draft-form.ts:239 — the operator's hand-typed ids for an entry.
    fun customModelIdsFor(preferences: JsValue?, entryKey: JsValue?): JsArr =
        preferences["customModels"][jsToString(entryKey)] as? JsArr ?: JsArr.EMPTY

    // lib/draft-form.ts:246 — blank or duplicate ids return the preferences unchanged.
    fun addCustomModelPref(preferences: JsValue?, entryKey: JsValue?, modelId: JsValue?): JsValue? {
        val id = normalizeId(modelId)
        if (id.isEmpty()) return preferences
        val existing = customModelIdsFor(preferences, entryKey)
        if (existing.any { it.isStr(id) }) return preferences
        return obj(preferences).put(
            "customModels",
            obj(preferences["customModels"]).put(jsToString(entryKey), existing.add(JsStr(id))),
        )
    }

    // lib/draft-form.ts:265 — drops the entry key entirely when its list empties.
    fun removeCustomModelPref(preferences: JsValue?, entryKey: JsValue?, modelId: JsValue?): JsValue? {
        val id = normalizeId(modelId)
        val existing = customModelIdsFor(preferences, entryKey)
        if (existing.none { it.isStr(id) }) return preferences
        val nextList = existing.filterKeep { !it.isStr(id) }
        var nextMap = obj(preferences["customModels"])
        nextMap = if (nextList.isEmpty()) nextMap.remove(jsToString(entryKey)) else nextMap.put(jsToString(entryKey), nextList)
        return obj(preferences).put("customModels", nextMap)
    }

    // lib/draft-form.ts:284 — append custom ids to the matching entry's models (untouched entries by identity).
    fun mergeCustomModelsIntoEntries(entries: JsValue?, customModels: JsValue?): JsArr {
        val list = entries.arrOrEmpty()
        if (!truthy(customModels)) return list
        return JsArr.of(
            list.map { entry ->
                val ids = customModels[jsToString(entry["key"])] as? JsArr ?: JsArr.EMPTY
                if (ids.isEmpty()) return@map entry
                val models = entry["models"].arrOrEmpty()
                val present = models.map { it["value"] }.toMutableList()
                val additions = ArrayList<JsValue>()
                for (rawId in ids) {
                    val id = normalizeId(rawId)
                    if (id.isEmpty() || present.any { strictEquals(it, JsStr(id)) }) continue
                    present.add(JsStr(id))
                    additions.add(JsObj.of("value" to JsStr(id), "displayName" to JsStr(id)))
                }
                if (additions.isEmpty()) entry else (entry as JsObj).put("models", JsArr.of(models + additions))
            },
        )
    }

    // lib/draft-form.ts:310
    private fun normalizeId(value: JsValue?): String = if (value is JsStr) jsTrim(value.value) else ""

    // lib/draft-form.ts:327 — B1/B2: only a strictly newer reply routes the draft.
    fun replyIsFresh(snapshotSeq: JsValue?, replySeq: JsValue?): Boolean =
        jsToNumber(replySeq) > jsToNumber(if (isNullish(snapshotSeq)) JsNum(0.0) else snapshotSeq)

    // lib/draft-form.ts:343 — v76: the reply's requestId must match the in-flight submit's token.
    fun replyMatchesRequest(inFlightRequestId: JsValue?, replyRequestId: JsValue?): Boolean {
        if (inFlightRequestId === JsNull) return false
        return strictEquals(replyRequestId, inFlightRequestId)
    }

    // lib/draft-form.ts:348
    private fun isDefaultRow(model: JsValue?) = model["value"].isStr("") || model["value"].isStr("default")

    // lib/draft-form.ts:357 — the model to PRE-SELECT for display while nothing is pinned ("" = engine default).
    fun resolveDefaultModelId(models: JsValue?, defaultModel: JsValue? = null): String {
        val rows = models.arrOrEmpty()
        if (rows.isEmpty()) return ""
        if (rows.any { isDefaultRow(it) }) return ""
        val normalized = normalizeId(defaultModel)
        return if (normalized.isNotEmpty()) resolveCanonicalModelId(rows, JsStr(normalized)) else ""
    }

    // lib/draft-form.ts:368 — the id itself when listed, else the row whose resolvedModel names it, else "".
    fun resolveCanonicalModelId(models: JsValue?, modelId: JsValue?): String {
        val normalized = normalizeId(modelId)
        if (normalized.isEmpty()) return ""
        val rows = models as? JsArr ?: return normalized
        if (rows.any { it["value"].isStr(normalized) }) return normalized
        val viaResolved = rows.firstOrNull { it["resolvedModel"].isStr(normalized) }
        return viaResolved?.get("value")?.let { if (isNullish(it)) null else jsToString(it) } ?: ""
    }

    // lib/draft-form.ts:381
    private fun modelDefinition(models: JsValue?, modelId: JsValue?): JsValue? {
        val rows = models.arrOrEmpty()
        rows.firstOrNull { strictEquals(it["value"], modelId) }?.let { return it }
        if (!truthy(modelId)) return rows.firstOrNull { isDefaultRow(it) } ?: rows.firstOrNull()
        return rows.firstOrNull { strictEquals(it["resolvedModel"], modelId) }
    }

    // lib/draft-form.ts:392 — the requested effort when the model offers it, else "" (engine default).
    fun resolveReasoningEffortForModel(models: JsValue?, modelId: JsValue?, requested: JsValue?): String {
        val model = modelDefinition(models, modelId)
        val variants = model["variants"].arrOrEmpty()
        if (variants.isEmpty()) return ""
        val normalized = normalizeId(requested)
        if (normalized.isNotEmpty() && variants.any { it["value"].isStr(normalized) }) return normalized
        return ""
    }

    // lib/draft-form.ts:417
    private fun selectableKeys(entries: JsArr): List<JsValue?> = entries.filter { !it["status"].isStr("unavailable") }.map { it["key"] }

    // lib/draft-form.ts:421
    private fun entryFor(entries: JsArr, key: JsValue?): JsValue? = entries.firstOrNull { strictEquals(it["key"], key) }

    // lib/draft-form.ts:425 — never auto-select a provider; keep only one picked in THIS draft.
    private fun resolveKey(entries: JsArr, userModified: JsValue?, current: JsValue?): JsValue {
        val keys = selectableKeys(entries)
        if (truthy(userModified)) return if (keys.any { strictEquals(it, current) }) current!! else JsStr("")
        return JsStr("")
    }

    private fun entryModels(entry: JsValue?): JsValue = entry["models"].let { if (isNullish(it)) JsArr.EMPTY else it!! }

    // lib/draft-form.ts:440
    private fun resolveModel(entry: JsValue?, userModified: JsValue?, current: JsValue?, initial: JsValue?, preferred: JsValue?): JsValue? {
        if (truthy(userModified)) return current
        if (entry == null) return JsStr("")
        val models = entryModels(entry)
        val initialModel = normalizeId(initial)
        val preferredModel = normalizeId(preferred)
        val fallback = resolveDefaultModelId(models, entry["defaultModel"])
        if (initialModel.isNotEmpty()) return JsStr(resolveCanonicalModelId(models, JsStr(initialModel)).ifEmpty { fallback })
        if (preferredModel.isNotEmpty()) return JsStr(resolveCanonicalModelId(models, JsStr(preferredModel)).ifEmpty { fallback })
        return JsStr(fallback)
    }

    // lib/draft-form.ts:459
    private fun resolveEffort(
        entry: JsValue?,
        modelId: JsValue?,
        userModified: JsValue?,
        current: JsValue?,
        initial: JsValue?,
        preferred: JsValue?,
    ): String {
        if (entry == null) return ""
        val requested: JsValue? = if (truthy(userModified)) current else JsStr(normalizeId(initial).ifEmpty { normalizeId(preferred) })
        return resolveReasoningEffortForModel(entryModels(entry), modelId, requested)
    }

    // lib/draft-form.ts:474
    private fun resolveMode(entry: JsValue?, userModified: JsValue?, current: JsValue?, initial: JsValue?, preferred: JsValue?): JsValue? {
        if (entry == null) return JsStr("")
        if (truthy(userModified)) return current
        normalizeId(initial).takeIf { it.isNotEmpty() }?.let { return JsStr(it) }
        normalizeId(preferred).takeIf { it.isNotEmpty() }?.let { return JsStr(it) }
        return JsStr(defaultModeFor(entry["provider"]))
    }

    // lib/draft-form.ts:494 — Auto is off unless the stored preference (or an in-draft toggle) says so.
    private fun resolveAutoMode(entry: JsValue?, userModified: JsValue?, current: JsValue?, preferred: JsValue?): Boolean {
        if (entry == null) return false
        if (truthy(userModified)) return truthy(current)
        return (preferred as? JsBool)?.value ?: false
    }

    private val WORKTREE_FIELDS = listOf("worktreeMode", "worktreeBaseRef", "worktreeBranch", "worktreeSlug", "worktreePr")

    // lib/draft-form.ts:505 — one resolution pass over `{ entries, preferences, initial? }`.
    fun resolveDraftForm(input: JsValue?, userModified: JsValue?, current: JsValue?): JsObj {
        val entries = input["entries"].arrOrEmpty()
        val initial = input["initial"]
        var result = obj(current)

        val key = resolveKey(entries, userModified["key"], current["key"])
        result = result.put("key", key)
        val entry = entryFor(entries, key)
        val prefs = if (truthy(key)) input["preferences"]["providerPreferences"][jsToString(key)] else null

        val model = resolveModel(entry, userModified["model"], current["model"], initial["model"], prefs["model"])
        result = result.put("model", model)
        result = result.put(
            "reasoningEffort",
            JsStr(resolveEffort(entry, model, userModified["reasoningEffort"], current["reasoningEffort"], initial["reasoningEffort"], prefs["reasoningEffort"])),
        )
        result = result.put("mode", resolveMode(entry, userModified["mode"], current["mode"], initial["mode"], prefs["mode"]))
        result = result.put("autoMode", js(resolveAutoMode(entry, userModified["autoMode"], current["autoMode"], prefs["autoMode"])))

        if (!truthy(userModified["cwd"]) && initial["cwd"] != null) result = result.put("cwd", initial["cwd"])
        if (!truthy(userModified["useWorktree"]) && initial["useWorktree"] != null) result = result.put("useWorktree", initial["useWorktree"])
        if (!truthy(userModified["worktree"])) {
            for (field in WORKTREE_FIELDS) if (initial[field] != null) result = result.put(field, initial[field])
        }
        return result
    }

    // lib/draft-form.ts:571
    private fun resolveEntryState(entry: JsValue?, preferences: JsValue?): JsObj {
        val prefs = preferences["providerPreferences"][jsToString(entry["key"])]
        val model = resolveModel(entry, JsBool.FALSE, JsStr(""), null, prefs["model"])
        val reasoningEffort = resolveEffort(entry, model, JsBool.FALSE, JsStr(""), null, prefs["reasoningEffort"])
        val mode = resolveMode(entry, JsBool.FALSE, JsStr(""), null, prefs["mode"])
        val autoMode = (prefs["autoMode"] as? JsBool)?.value ?: false
        return JsObj.of("model" to model, "reasoningEffort" to JsStr(reasoningEffort), "mode" to mode, "autoMode" to js(autoMode))
    }

    // lib/draft-form.ts:580 — the action reducer; an unknown action returns the state unchanged.
    fun reduceDraftForm(state: JsValue?, action: JsValue?): JsValue? {
        val form = obj(state["form"])
        val userModified = obj(state["userModified"])
        fun withForm(nextForm: JsObj, nextModified: JsObj) = obj(state).put("form", nextForm).put("userModified", nextModified)
        val entry = action["entry"]
        val preferences = action["preferences"]
        return when ((action["type"] as? JsStr)?.value) {
            "RESOLVE" -> obj(state).put(
                "form",
                resolveDraftForm(
                    JsObj.of("entries" to action["entries"], "preferences" to action["preferences"], "initial" to action["initial"]),
                    userModified,
                    form,
                ),
            )

            "SET_PROVIDER_FROM_USER" -> {
                val next = resolveEntryState(entry, preferences)
                val prefs = preferences["providerPreferences"][jsToString(entry["key"])]
                val prefsModel = normalizeId(prefs["model"])
                val modelFromPrefs = if (prefsModel.isNotEmpty()) resolveCanonicalModelId(entryModels(entry), JsStr(prefsModel)) != "" else false
                val effortFromPrefs = truthy(next["reasoningEffort"])
                withForm(
                    form.put("key", entry["key"]).spread(next),
                    userModified.with(
                        "key" to JsBool.TRUE,
                        "model" to js(modelFromPrefs),
                        "reasoningEffort" to js(effortFromPrefs),
                        "autoMode" to next["autoMode"],
                    ),
                )
            }

            "SET_PROVIDER_AND_MODEL_FROM_USER" -> {
                val models = entryModels(entry)
                val model = resolveCanonicalModelId(models, action["modelId"]).ifEmpty { resolveDefaultModelId(models, entry["defaultModel"]) }
                val prefs = preferences["providerPreferences"][jsToString(entry["key"])]
                val reasoningEffort = resolveReasoningEffortForModel(models, JsStr(model), JsStr(normalizeId(prefs["reasoningEffort"])))
                val mode = normalizeId(prefs["mode"]).ifEmpty { defaultModeFor(entry["provider"]) }
                val autoMode = (prefs["autoMode"] as? JsBool)?.value ?: false
                withForm(
                    form.with(
                        "key" to entry["key"],
                        "model" to JsStr(model),
                        "reasoningEffort" to JsStr(reasoningEffort),
                        "mode" to JsStr(mode),
                        "autoMode" to js(autoMode),
                    ),
                    userModified.with(
                        "key" to JsBool.TRUE,
                        "model" to JsBool.TRUE,
                        "reasoningEffort" to js(reasoningEffort.isNotEmpty()),
                        "autoMode" to js(autoMode),
                    ),
                )
            }

            "SET_MODEL_FROM_USER" -> {
                val models = entryModels(entry)
                val model = resolveCanonicalModelId(models, action["modelId"]).ifEmpty { resolveDefaultModelId(models, entry["defaultModel"]) }
                val reasoningEffort = resolveReasoningEffortForModel(models, JsStr(model), form["reasoningEffort"])
                withForm(
                    form.with("model" to JsStr(model), "reasoningEffort" to JsStr(reasoningEffort)),
                    userModified.put("model", JsBool.TRUE),
                )
            }

            "SET_REASONING_EFFORT_FROM_USER" ->
                withForm(form.put("reasoningEffort", action["effort"]), userModified.put("reasoningEffort", JsBool.TRUE))

            "SET_MODE_FROM_USER" -> withForm(form.put("mode", action["mode"]), userModified.put("mode", JsBool.TRUE))

            "SET_AUTO_MODE_FROM_USER" -> withForm(form.put("autoMode", action["autoMode"]), userModified.put("autoMode", JsBool.TRUE))

            "SET_CWD_FROM_USER" -> withForm(form.put("cwd", action["cwd"]), userModified.put("cwd", JsBool.TRUE))

            "SET_WORKTREE_FROM_USER" ->
                withForm(form.put("useWorktree", action["useWorktree"]), userModified.put("useWorktree", JsBool.TRUE))

            "SET_WORKTREE_OPTIONS_FROM_USER" ->
                withForm(form.spread(obj(action["options"])), userModified.put("worktree", JsBool.TRUE))

            "RESET" -> JsObj.of(
                "form" to INITIAL_DRAFT_FORM.put("cwd", action["cwd"].let { if (isNullish(it)) JsStr("") else it }),
                "userModified" to INITIAL_USER_MODIFIED,
            )

            else -> state
        }
    }
}
