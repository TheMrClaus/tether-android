package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.isNullish
import com.tether.app.protocol.fold.jsToString
import com.tether.app.protocol.fold.jsTrim
import com.tether.app.protocol.fold.strictEquals
import com.tether.app.protocol.fold.truthy
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.js

/**
 * T2.2: faithful port of lib/model-picker.mjs — the composer's Model / Effort picker shaping
 * (v43+), the [CLAUDE_MODEL_CATALOG] legacy tier, and the Inspector's model reading (issue #179).
 * Rows are the wire's ModelOption objects as projection trees. Strictly pure.
 */
object ModelPicker {

    // lib/model-picker.mjs:27 — string-aware JSONC stripping (comments, trailing commas).
    fun stripJsonc(text: JsValue?): String {
        val out = StringBuilder()
        var inString = false
        var escaped = false
        val source = if (isNullish(text)) "" else jsToString(text)
        var index = 0
        while (index < source.length) {
            val char = source[index]
            if (inString) {
                out.append(char)
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
                index += 1
                continue
            }
            if (char == '"') {
                inString = true
                out.append(char)
                index += 1
                continue
            }
            if (char == '/' && source.getOrNull(index + 1) == '/') {
                while (index < source.length && source[index] != '\n') index += 1
                out.append('\n') // keep line structure so error offsets stay meaningful
                index += 1
                continue
            }
            if (char == '/' && source.getOrNull(index + 1) == '*') {
                index += 2
                while (index < source.length && !(source[index] == '*' && source.getOrNull(index + 1) == '/')) index += 1
                index += 1 // the loop's own increment consumes the closing slash
                index += 1
                continue
            }
            out.append(char)
            index += 1
        }
        return TRAILING_COMMA.replace(out.toString(), "$1")
    }

    private val TRAILING_COMMA = Regex(",($JS_WS*[}\\]])")

    // lib/model-picker.mjs:79 — `{}` or `{ variants }`, filtered by the engine's allowlist.
    fun effortVariants(levels: JsValue?, allowed: JsValue?): JsObj {
        val list = levels.arrOrEmpty()
        val allowlist = allowed.arrOrEmpty()
        val variants = list
            .filter { level -> allowlist.any { strictEquals(it, level) } }
            .map { level -> JsObj.of("value" to level, "label" to level) }
        return if (variants.isNotEmpty()) JsObj.of("variants" to JsArr.of(variants)) else JsObj.EMPTY
    }

    // lib/model-picker.mjs:95
    private val CLAUDE_EFFORT_LABELS = mapOf(
        "off" to "Off",
        "low" to "Low",
        "medium" to "Medium",
        "high" to "High",
        "xhigh" to "Extra high",
        "max" to "Max",
        "ultracode" to "Ultra code",
    )

    // lib/model-picker.mjs:123 — effortVariants + the "off" / "ultracode" rows and friendly labels.
    fun claudeEffortVariants(levels: JsValue?, claudeEffortLevels: JsValue?): JsObj {
        val variants = effortVariants(levels, claudeEffortLevels)["variants"] as? JsArr
        if (variants == null || variants.isEmpty()) return JsObj.EMPTY
        val rawLevels = levels.arrOrEmpty()
        val labeled = variants.map { variant ->
            (variant as JsObj).put("label", CLAUDE_EFFORT_LABELS[jsToString(variant["value"])]?.let { JsStr(it) } ?: variant["label"])
        }
        val rows = ArrayList<JsValue>()
        rows.add(JsObj.of("value" to JsStr("off"), "label" to JsStr(CLAUDE_EFFORT_LABELS.getValue("off"))))
        rows.addAll(labeled)
        if (rawLevels.any { it.isStr("xhigh") }) {
            rows.add(JsObj.of("value" to JsStr("ultracode"), "label" to JsStr(CLAUDE_EFFORT_LABELS.getValue("ultracode"))))
        }
        return JsObj.of("variants" to JsArr.of(rows))
    }

    private val CONTEXT_SUFFIX = Regex("\\[([12])m]\\z")
    private val BUILD_SUFFIX = Regex("-v(\\d+(?::\\d+)?)\\z")
    private val DATE_SUFFIX = Regex("[-@](\\d{8})\\z")
    private val SEGMENT_SPLIT = Regex("[-.]")
    private val ALL_DIGITS = Regex("^\\d+\\z")
    private val ALPHA = Regex("^[a-zA-Z]+\\z")

    // lib/model-picker.mjs:154 — "5", "4.5", "5 · 2025-09-01", "5 · v2 · 1M"; "" when unversioned.
    fun claudeVersionSuffix(value: JsValue?): String {
        if (value !is JsStr || !value.value.startsWith("claude-")) return ""
        var id = value.value
        var context = ""
        CONTEXT_SUFFIX.find(id)?.let { m ->
            context = "${m.groupValues[1]}M"
            id = id.substring(0, id.length - m.value.length)
        }
        var build = ""
        BUILD_SUFFIX.find(id)?.let { m ->
            build = "v${m.groupValues[1]}"
            id = id.substring(0, id.length - m.value.length)
        }
        var date = ""
        DATE_SUFFIX.find(id)?.let { m ->
            val raw = m.groupValues[1]
            date = "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}"
            id = id.substring(0, id.length - m.value.length)
        }
        val segments = id.split(SEGMENT_SPLIT).filter { it.isNotEmpty() }
        val isDigits = { segment: String -> ALL_DIGITS.containsMatchIn(segment) }
        var family = -1
        for (index in segments.indices.reversed()) {
            if (ALPHA.containsMatchIn(segments[index])) {
                family = index
                break
            }
        }
        if (family < 0) return ""
        var start = family + 1
        var end = start
        while (end < segments.size && isDigits(segments[end])) end += 1
        if (end > start) {
            return listOf(segments.subList(start, end).joinToString("."), date, build, context).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        start = family - 1
        end = start
        while (start >= 0 && isDigits(segments[start])) start -= 1
        if (end - start <= 0) return ""
        return listOf(segments.subList(start + 1, end + 1).joinToString("."), date, build, context).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    fun claudeVersionSuffix(value: String?): String = claudeVersionSuffix(value?.let { JsStr(it) })

    private val HAS_DIGIT = Regex("\\d")

    // lib/model-picker.mjs:226 — the canonical ("fancy") display name for a model row.
    fun modelDisplayName(model: JsValue?, models: JsValue? = null): String {
        val row: JsValue = if (truthy(model)) model!! else JsObj.EMPTY
        var name = ""
        val displayName = row["displayName"]
        if (displayName is JsStr && displayName.value.isNotEmpty()) {
            name = displayName.value
        } else {
            val list = models.arrOrEmpty()
            val lookup = { id: JsValue? ->
                if (id !is JsStr || id.value.isEmpty()) {
                    ""
                } else {
                    val found = list.firstOrNull { candidate ->
                        truthy(candidate) && (strictEquals(candidate["value"], id) || strictEquals(candidate["resolvedModel"], id))
                    }
                    found["displayName"].nonEmptyStr ?: ""
                }
            }
            name = lookup(row["resolvedModel"]).ifEmpty { lookup(row["value"]) }
        }
        if (name.isNotEmpty() && !isDefaultRow(row) && !HAS_DIGIT.containsMatchIn(name)) {
            val suffix = claudeVersionSuffix(row["value"]).ifEmpty { claudeVersionSuffix(row["resolvedModel"]) }
            if (suffix.isNotEmpty()) name = "$name $suffix"
        }
        return name.ifEmpty { row["value"].nonEmptyStr ?: "Model" }
    }

    // lib/model-picker.mjs:255
    private fun isDefaultRow(option: JsValue?): Boolean = option["value"].isStr("") || option["value"].isStr("default")

    // lib/model-picker.mjs:266 — the CLI-default row inherits the variants of the model it resolves to.
    fun inheritDefaultRowVariants(options: JsValue?): JsArr {
        val rows = options.arrOrEmpty()
        val defaultRow = rows.firstOrNull { isDefaultRow(it) }
        if (defaultRow == null || truthy(defaultRow["variants"]) || !truthy(defaultRow["resolvedModel"])) return rows
        val resolved = rows.firstOrNull { option ->
            !isDefaultRow(option) && truthy(option["value"]) && strictEquals(option["value"], defaultRow["resolvedModel"])
        }
        if (!truthy(resolved["variants"])) return rows
        return JsArr.of(rows.map { option -> if (option === defaultRow) (option as JsObj).put("variants", resolved["variants"]) else option })
    }

    // lib/model-picker.mjs:295 — `{ options, defaultModel }`: the default row replaced by its concrete model.
    fun collapseDefaultModelRow(options: JsValue?, appliedModel: JsValue?): JsObj {
        val rows = options.arrOrEmpty()
        val defaultRow = rows.firstOrNull { isDefaultRow(it) }
        val target = appliedModel.nonEmptyStr ?: (defaultRow["resolvedModel"] as? JsStr)?.value ?: ""
        if (target.isEmpty()) return JsObj.of("options" to rows, "defaultModel" to JsStr(""))
        val t = JsStr(target)
        val concrete = rows.filter { option -> !isDefaultRow(option) && truthy(option["value"]) }
        val match = concrete.firstOrNull { strictEquals(it["value"], t) }
            ?: concrete.firstOrNull { strictEquals(it["resolvedModel"], t) }
            ?: concrete.firstOrNull { !ModelId.modelsDiverge(it["value"], t) }
            ?: return JsObj.of("options" to rows, "defaultModel" to JsStr(""))
        val pinned = rows.any { truthy(it["current"]) }
        return JsObj.of(
            "options" to JsArr.of(
                rows.filter { !isDefaultRow(it) }
                    .map { option -> if (option === match && !pinned) (option as JsObj).put("current", JsBool.TRUE) else option },
            ),
            "defaultModel" to (match["value"] ?: JsNull),
        )
    }

    // lib/model-picker.mjs:331 — the applied effort, only when the model offers it; "" otherwise.
    fun resolveDefaultEffort(appliedEffort: JsValue?, variants: JsValue?): String {
        val effort = (appliedEffort as? JsStr)?.value ?: ""
        if (effort.isEmpty()) return ""
        return if (variants.arrOrEmpty().any { it["value"].isStr(effort) }) effort else ""
    }

    // lib/model-picker.mjs:349 — a "CLI Default" row followed by the CLI-discovered models.
    fun buildOpenCodeModelOptions(liveModels: JsValue?, selectedModel: JsValue?, defaultModelId: JsValue?): JsArr {
        val source = liveModels as? JsArr ?: JsArr.EMPTY
        val pinnedId = defaultModelId.nonEmptyStr ?: ""
        val pinned = if (pinnedId.isNotEmpty()) source.firstOrNull { it["value"].isStr(pinnedId) } else null
        val defaultLabel = if (pinnedId.isNotEmpty()) modelDisplayName(JsObj.of("value" to JsStr(pinnedId)), source) else ""
        val defaultVariants = (pinned["variants"] as? JsArr)?.takeIf { it.isNotEmpty() }
        val options = ArrayList<JsObj>()
        options.add(
            JsObj.of(
                "value" to JsStr(""),
                "displayName" to JsStr(if (defaultLabel.isNotEmpty()) "CLI Default ($defaultLabel)" else "CLI Default"),
                "description" to JsStr(
                    if (pinnedId.isNotEmpty()) {
                        "The model pinned in the opencode config"
                    } else {
                        "The CLI's own default model (pin `model` in opencode.jsonc to name it here)"
                    },
                ),
                "resolvedModel" to (if (pinnedId.isNotEmpty()) JsStr(pinnedId) else null),
                "variants" to defaultVariants,
                "current" to js(!truthy(selectedModel)),
            ),
        )
        for (model in source) {
            options.add(
                JsObj.of(
                    "value" to model["value"],
                    "displayName" to JsStr(modelDisplayName(model, source)),
                    "variants" to (model["variants"] as? JsArr)?.takeIf { it.isNotEmpty() },
                    "providerLabel" to (model["providerLabel"] as? JsStr)?.takeIf { it.value.isNotEmpty() },
                    "current" to js(truthy(selectedModel) && strictEquals(model["value"], selectedModel)),
                ),
            )
        }
        // A model selected but no longer offered: still show it, so the picker never misreports.
        if (truthy(selectedModel) && options.none { it["current"] == JsBool.TRUE }) {
            options.add(
                0,
                JsObj.of(
                    "value" to selectedModel,
                    "displayName" to JsStr(modelDisplayName(JsObj.of("value" to selectedModel), source)),
                    "description" to JsStr("Currently selected"),
                    "current" to JsBool.TRUE,
                ),
            )
        }
        return JsArr.of(options)
    }

    /** One CLAUDE_MODEL_CATALOG entry; [retiresOn] filters it out at request time (legacyModelRows). */
    data class CatalogModel(
        val value: String,
        val displayName: String,
        val description: String,
        val aliases: List<String>? = null,
        val reviewAfter: String? = null,
        val retiresOn: String? = null,
    )

    // lib/model-picker.mjs:419 — SOURCE OF TRUTH for every date: the model-deprecations page
    // (verified 2026-08-06 upstream). Retired entries are kept to document why an id is absent.
    val CLAUDE_MODEL_CATALOG: List<CatalogModel> = listOf(
        CatalogModel("claude-fable-5", "Fable 5", "Most capable · hardest, longest-running tasks · 1M context", reviewAfter = "2027-06-09"),
        CatalogModel("claude-mythos-5", "Mythos 5", "Fable-class · invitation-only (Project Glasswing)"),
        CatalogModel("claude-opus-5", "Opus 5", "Opus 5 · complex agentic coding · 1M context", aliases = listOf("opus", "best", "opusplan"), reviewAfter = "2027-07-24"),
        CatalogModel("claude-opus-4-8", "Opus 4.8", "Previous-generation Opus · 1M context", reviewAfter = "2027-05-28"),
        CatalogModel("claude-opus-4-7", "Opus 4.7", "Opus 4.7 · 1M context", reviewAfter = "2027-04-16"),
        CatalogModel("claude-opus-4-6", "Opus 4.6", "Opus 4.6 · 1M context", reviewAfter = "2027-02-05"),
        CatalogModel("claude-opus-4-5", "Opus 4.5", "Opus 4.5 · legacy", reviewAfter = "2026-11-24"),
        CatalogModel("claude-sonnet-5", "Sonnet 5", "Sonnet 5 · near-Opus coding at Sonnet cost · 1M context", aliases = listOf("sonnet"), reviewAfter = "2027-06-30"),
        CatalogModel("claude-sonnet-4-6", "Sonnet 4.6", "Previous-generation Sonnet · 1M context", reviewAfter = "2027-02-17"),
        CatalogModel("claude-sonnet-4-5", "Sonnet 4.5", "Sonnet 4.5 · legacy", reviewAfter = "2026-11-30"),
        CatalogModel("claude-haiku-4-5", "Haiku 4.5", "Fastest · quick answers · 200K context", aliases = listOf("haiku"), reviewAfter = "2026-10-15"),
        // --- Retired: filtered out by date, listed so the absence is explained. ---
        CatalogModel("claude-opus-4-1", "Opus 4.1", "Retired", retiresOn = "2026-08-05"),
        CatalogModel("claude-opus-4-0", "Opus 4", "Retired", retiresOn = "2026-06-15"),
        CatalogModel("claude-sonnet-4-0", "Sonnet 4", "Retired", retiresOn = "2026-06-15"),
        CatalogModel("claude-3-haiku-20240307", "Haiku 3", "Retired", retiresOn = "2026-04-20"),
        CatalogModel("claude-3-7-sonnet-20250219", "Sonnet 3.7", "Retired", retiresOn = "2026-02-19"),
        CatalogModel("claude-3-5-haiku-20241022", "Haiku 3.5", "Retired", retiresOn = "2026-02-19"),
        CatalogModel("claude-3-opus-20240229", "Opus 3", "Retired", retiresOn = "2026-01-05"),
        CatalogModel("claude-3-5-sonnet-20241022", "Sonnet 3.5", "Retired", retiresOn = "2025-10-28"),
    )

    private val BRACKET_SUFFIX = Regex("\\[[^\\]]*]\\z")

    // lib/model-picker.mjs:445 — the id without its context-window suffix, trimmed and lowercased.
    fun baseModelId(value: JsValue?): String =
        if (value is JsStr) baseModelId(value.value) else ""

    fun baseModelId(value: String): String = jsTrim(BRACKET_SUFFIX.replaceFirst(jsTrim(value), "")).lowercase()

    // lib/model-picker.mjs:456 — the catalog entries the live list doesn't already offer, minus retired ones.
    fun legacyModelRows(provider: JsValue?, liveRows: JsValue?, today: JsValue?): JsArr {
        if (!provider.isStr("claude")) return JsArr.EMPTY
        val day = today.nonEmptyStr ?: return JsArr.EMPTY
        if (liveRows !is JsArr) return JsArr.EMPTY
        val covered = HashSet<String>()
        for (row in liveRows) {
            covered.add(baseModelId(row["value"]))
            if (truthy(row["resolvedModel"])) covered.add(baseModelId(row["resolvedModel"]))
        }
        return JsArr.of(
            CLAUDE_MODEL_CATALOG
                .filter { model -> model.retiresOn.isNullOrEmpty() || model.retiresOn > day }
                .filter { model ->
                    if (baseModelId(model.value) in covered) return@filter false
                    !(model.aliases ?: emptyList()).any { baseModelId(it) in covered }
                }
                .map { model ->
                    JsObj.of(
                        "value" to JsStr(model.value),
                        "displayName" to JsStr(model.displayName),
                        "description" to JsStr(model.description),
                        "legacy" to JsBool.TRUE,
                    )
                },
        )
    }

    // lib/model-picker.mjs:539 — the group row's sentinel value, never a model id.
    const val LEGACY_GROUP_VALUE = "__legacy_models__"

    // lib/model-picker.mjs:503 — live rows, pinned legacy rows, then the "Legacy models" submenu.
    fun groupModelOptions(models: JsValue?, pinnedModelIds: JsValue?): JsArr {
        val rows = models.arrOrEmpty()
        val pinSet = pinnedModelIds.arrOrEmpty()
        fun pinned(model: JsValue) = pinSet.any { strictEquals(it, model["value"]) }
        fun toOption(model: JsValue, extra: Pair<String, JsValue>? = null): JsObj {
            val description = model["description"].let { d ->
                if (!isNullish(d)) d else if (model["value"].isStr("") || model["value"].isStr("default")) JsStr("The CLI's default model") else null
            }
            var option = JsObj.of(
                "value" to model["value"],
                "label" to model["displayName"],
                "description" to description,
                "tag" to (model["providerLabel"] as? JsStr)?.takeIf { it.value.isNotEmpty() },
            )
            if (extra != null) option = option.put(extra.first, extra.second)
            return option
        }
        val live = rows.filter { !truthy(it["legacy"]) }.map { toOption(it) }
        val allLegacy = rows.filter { truthy(it["legacy"]) }
        val pinnedRows = allLegacy.filter { pinned(it) }.map { toOption(it, "pinned" to JsBool.TRUE) }
        val unpinned = allLegacy.filter { !pinned(it) }.map { toOption(it, "pinnable" to JsBool.TRUE) }
        val result = ArrayList<JsValue>(live + pinnedRows)
        if (unpinned.isEmpty()) return JsArr.of(result)
        result.add(
            JsObj.of(
                "value" to JsStr(LEGACY_GROUP_VALUE),
                "label" to JsStr("Legacy models"),
                "description" to JsStr("${unpinned.size} older model${if (unpinned.size == 1) "" else "s"} the CLI still accepts"),
                "submenu" to JsArr.of(unpinned),
                "submenuNote" to JsStr(
                    "Not advertised by your CLI, so this list can be out of date and may include " +
                        "models your account cannot use — the CLI validates on the next turn.",
                ),
            ),
        )
        return JsArr.of(result)
    }

    // lib/model-picker.mjs:562 — `{ render, persist }`: a partial live read never shrinks the cache.
    fun reconcileModelCatalog(liveModels: JsValue?, cachedModels: JsValue?): JsObj {
        val live = liveModels.arrOrEmpty()
        val cached = cachedModels.arrOrEmpty()
        if (live.isEmpty() || cached.isEmpty()) return JsObj.of("render" to live, "persist" to live)
        val cachedValues = cached.map { it["value"] }
        val subsumed = live.all { row -> cachedValues.any { sameValueZero(it, row["value"]) } }
        if (subsumed && live.size < cached.size) return JsObj.of("render" to cached, "persist" to JsArr.EMPTY)
        return JsObj.of("render" to live, "persist" to live)
    }

    // lib/model-picker.mjs:600 — union live and cached by model id; live metadata wins.
    fun mergeModelCatalog(liveModels: JsValue?, cachedModels: JsValue?): JsArr {
        val live = liveModels.arrOrEmpty()
        val cached = cachedModels.arrOrEmpty()
        if (live.isEmpty()) return cached
        if (cached.isEmpty()) return live
        val liveIds = live.map { it["value"] }
        val extra = cached.filter { row -> liveIds.none { sameValueZero(it, row["value"]) } }
        return if (extra.isNotEmpty()) JsArr.of(live + extra) else live
    }

    /** Set/Map membership (SameValueZero): `undefined` equals `undefined`, primitives by value. */
    private fun sameValueZero(a: JsValue?, b: JsValue?): Boolean = strictEquals(a, b)

    // lib/model-picker.mjs:625
    private val CLAUDE_FAMILY_ALIASES = setOf("opus", "sonnet", "haiku", "fable", "mythos")
    private val CLAUDE_CONTEXT_ALIASES = mapOf("opusplan" to listOf("opus", "sonnet"))
    private val CLAUDE_OPAQUE_ALIASES = setOf("", "default", "best")

    private val DATED = Regex("[-@]\\d{8}\\z")

    // lib/model-picker.mjs:633
    private fun claudeBaseId(value: JsValue?): String = DATED.replaceFirst(baseModelId(value), "")

    private val SYNTHETIC = Regex("^<[^>]*>\\z")

    // lib/model-picker.mjs:640 — the CLI's placeholder model on a synthetic record ("<synthetic>").
    fun isSyntheticModel(value: JsValue?): Boolean = value is JsStr && SYNTHETIC.containsMatchIn(jsTrim(value.value))

    private fun isSyntheticModel(value: String): Boolean = SYNTHETIC.containsMatchIn(jsTrim(value))

    private val FAMILY = Regex("^claude-(?:\\d+-)*([a-z]+)")

    // lib/model-picker.mjs:644
    private fun claudeFamilyOf(id: String): String? = FAMILY.find(id)?.groupValues?.get(1)

    private val CONTEXT_SUFFIX_I = Regex("\\[([12])m]\\z", RegexOption.IGNORE_CASE)

    // lib/model-picker.mjs:655 — "Sonnet 5", "Opus 5.5 · 1M", "Opus · 1M", else the raw value.
    fun modelReadingLabel(value: JsValue?): String {
        if (value !is JsStr || jsTrim(value.value).isEmpty()) return ""
        val raw = jsTrim(value.value)
        val context = CONTEXT_SUFFIX_I.find(raw)
        val contextSuffix = if (context != null) " · ${context.groupValues[1]}M" else ""
        val base = baseModelId(raw)
        CLAUDE_MODEL_CATALOG.firstOrNull { it.value == base }?.let { return "${it.displayName}$contextSuffix" }
        if (base in CLAUDE_FAMILY_ALIASES) return "${base.substring(0, 1).uppercase()}${base.substring(1)}$contextSuffix"
        val family = claudeFamilyOf(base)
        val version = if (family != null) claudeVersionSuffix(raw) else ""
        if (family != null && version.isNotEmpty()) return "${family.substring(0, 1).uppercase()}${family.substring(1)} $version"
        return raw
    }

    // lib/model-picker.mjs:677 — does `served` plausibly BE `configured`? True whenever it can't prove otherwise.
    fun servedModelMatches(configured: JsValue?, served: JsValue?): Boolean {
        val configuredBase = claudeBaseId(configured)
        val servedBase = claudeBaseId(served)
        if (configuredBase.isEmpty() || servedBase.isEmpty() || configuredBase in CLAUDE_OPAQUE_ALIASES) return true
        if (isSyntheticModel(servedBase)) return true
        val servedFamily = claudeFamilyOf(servedBase) ?: servedBase
        if (configuredBase in CLAUDE_FAMILY_ALIASES) return servedFamily.contains(configuredBase)
        CLAUDE_CONTEXT_ALIASES[configuredBase]?.let { families -> return families.any { servedFamily.contains(it) } }
        if (configuredBase.startsWith("claude-") && servedBase.startsWith("claude-")) return configuredBase == servedBase
        return !ModelId.modelsDiverge(configuredBase, servedBase)
    }

    // lib/model-picker.mjs:709 — the Inspector's `{ label, lastServed, note }` (issue #179).
    fun modelReading(options: JsValue? = null): JsObj {
        if (options === JsNull) throw JsError("TypeError", "Cannot read properties of null (reading 'configured')")
        val configured = options["configured"] ?: JsNull
        val served = options["served"] ?: JsNull
        val fallback = options["fallback"] ?: JsNull
        val servedRaw = served.nonEmptyStr
        val configuredModel: JsValue = configured.takeIf { it is JsStr && it.value.isNotEmpty() }
            ?: fallback["fromModel"].takeIf { truthy(it) } ?: JsNull
        val servedModel: JsValue = servedRaw?.let { JsStr(it) } ?: fallback["toModel"].takeIf { truthy(it) } ?: JsNull
        val differs = truthy(configuredModel) && truthy(servedModel) && !servedModelMatches(configuredModel, servedModel)
        if (!differs) return JsObj.of("label" to JsStr(servedRaw ?: "—"), "lastServed" to JsNull, "note" to JsNull)
        val message = fallback["message"]
        return JsObj.of(
            "label" to JsStr(modelReadingLabel(configuredModel)),
            "lastServed" to JsStr(modelReadingLabel(servedModel)),
            "note" to JsStr(
                if (truthy(message)) {
                    "The CLI switched models: ${jsToString(message)}"
                } else {
                    "Configured ${jsToString(configuredModel)}; the last turn was served by ${jsToString(servedModel)}."
                },
            ),
        )
    }
}
