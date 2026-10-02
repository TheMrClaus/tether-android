package com.tether.app.client

import com.tether.app.protocol.helpers.CodexModePresets
import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr

/**
 * ta-xki (T8.1 slice 4): the new-session draft's Effort and Mode, as components/draft-composer.tsx
 * (887c222 ~113-160, ~373-431) derives them from the draft form and the picked catalog row. Pure.
 *
 * - **Effort** lists the selected model's reasoning-effort variants (the model whose value is the
 *   form's, else the CLI-default row, else the first), and is shown only when there is at least one
 *   (issue #44). An untouched effort ("" = the engine's default) reads "Default".
 * - **Mode** is per provider: Claude's four permission modes (Auto = `bypassPermissions`, the danger
 *   row), Codex's three presets ([CodexModePresets.CODEX_MODE_OPTIONS]), opencode's Build / Plan with
 *   the standalone Auto chip beside them (issue #48: Build + `approvalPolicy: never`); every other
 *   provider has no Mode row. Elevated modes are ordinary choices (owner 2026-10-02): nothing here asks
 *   for a confirmation.
 *
 * Labels the server supplies (the variants) are drawn by the label rule ([LabelText]); the mode rows
 * are the web's own constants.
 */
data class DraftSessionOptions(
    /** The picked row's engine ("" while no row is picked). */
    val provider: String,
    /** The Effort select, or null when the selected model offers no variant. */
    val effort: SelectControl?,
    /** The Mode select, or null for a provider with no Mode row (or no row picked). */
    val mode: SelectControl?,
    /** The Mode select's accessible name: "Permission mode" (Claude, opencode) or "Codex mode". */
    val modeAriaLabel: String,
    /** The selected mode row is the danger one (Claude Auto, Codex Full access): the `is-danger` trigger. */
    val modeDanger: Boolean,
    /** opencode's standalone Auto chip (issue #48), or null where Auto is a Mode row or not offered. */
    val auto: AutoToggle?,
) {
    /** draft-composer.tsx `hasOtherSettings` minus Model: the phone's settings chip is drawn. */
    val hasSettings: Boolean get() = effort != null || mode != null

    /**
     * The elevated posture in force, in words, for the phone's settings chip (app addition: the web
     * says it only for opencode's Auto toggle): "Auto" for Claude Auto or opencode Auto, the row's own
     * label for another danger row (Codex "Full access"); null for an ordinary mode.
     */
    val elevatedLabel: String?
        get() = when {
            auto?.on == true -> AUTO_LABEL
            modeDanger -> mode?.current?.label
            else -> null
        }

    companion object {
        val None = DraftSessionOptions("", null, null, PERMISSION_ARIA, false, null)
    }
}

/** draft-composer.tsx's mode vocabulary per provider, and the pure rules over it. */
object DraftModes {

    /** lib/protocol.ts PERMISSION_MODE_OPTIONS (887c222 ~956-961): Claude's Mode rows. */
    val CLAUDE: List<ModeChoice> get() = ModeVocabulary.PERMISSION

    /** lib/protocol.ts OPENCODE_MODE_OPTIONS (887c222 ~990-993): opencode's static Build / Plan rows. */
    val OPENCODE: List<ModeChoice> get() = ModeVocabulary.OPENCODE

    /** lib/codex-mode-presets.mjs CODEX_MODE_OPTIONS: the three presets as Mode rows. */
    val CODEX: List<ModeChoice> = CodexModePresets.CODEX_MODE_OPTIONS.map { o ->
        ModeChoice(
            value = (o["value"] as JsStr).value,
            label = (o["label"] as JsStr).value,
            hint = (o["hint"] as JsStr).value,
            danger = o["danger"] == JsBool.TRUE,
        )
    }

    /** draft-composer.tsx `modeOptions`: null for every provider without a Mode row. */
    fun options(provider: String): List<ModeChoice>? = when (provider) {
        "claude" -> CLAUDE
        "codex" -> CODEX
        "opencode" -> OPENCODE
        else -> null
    }

    /** draft-composer.tsx `supportsAuto && !autoIsModeRow`: only opencode keeps the standalone chip. */
    fun hasAutoChip(provider: String): Boolean = provider == "opencode"

    /**
     * The mode values a [provider]'s create may carry from this draft: its Mode rows, plus opencode's
     * Auto (`bypassPermissions`) and its untouched "" (the web's cold start: Build). Null = the provider
     * has no Mode row, so its frame ignores the form's mode ([CreateFrame]).
     */
    fun known(provider: String): Set<String>? {
        val rows = options(provider) ?: return null
        val values = rows.mapTo(LinkedHashSet()) { it.value }
        if (hasAutoChip(provider)) {
            values += ModeVocabulary.AUTO
            values += ""
        }
        return values
    }

    /**
     * ta-xki (prefs rule): a mode the provider does not offer (a stored preference from an older build,
     * such as Claude's retired `dontAsk` or Codex's pre-v100 `off`, a hand-edited or garbage value) is
     * the provider's default ([DraftForm.defaultModeFor]: Claude Auto, Codex "default", opencode "").
     * A provider with no Mode row keeps the value: its frame never reads it.
     */
    fun normalize(provider: String, mode: String): String {
        val known = known(provider) ?: return mode
        return if (mode in known) mode else DraftForm.defaultModeFor(JsStr(provider))
    }

    /** True when [mode] is one the [provider]'s Mode row (or opencode's Auto chip) can set. */
    fun selectable(provider: String, mode: String): Boolean {
        val known = known(provider) ?: return false
        return mode.isNotEmpty() && mode in known
    }

    /**
     * draft-composer.tsx `modeDisplay`: opencode's Auto shows its Mode select on Build ("default"),
     * since Auto is its own chip there. App addition: opencode's untouched "" (Build on the wire) shows
     * as Build too, where the web's select draws an empty pill.
     */
    fun display(provider: String, mode: String): String = when {
        hasAutoChip(provider) && (mode == ModeVocabulary.AUTO || mode.isEmpty()) -> "default"
        else -> mode
    }
}

/** ta-xki: the options model, from the draft's state (the entries it lists and its form). */
object DraftSessionOptionsModel {

    fun of(state: DraftComposerState): DraftSessionOptions {
        val key = (state.form["key"] as? JsStr)?.value.orEmpty()
        val entry = state.entries.firstOrNull { it.key == key } ?: return DraftSessionOptions.None
        return of(entry, state.form)
    }

    fun of(entry: ProviderCatalogEntry, form: JsObj): DraftSessionOptions {
        val provider = entry.provider
        val model = (form["model"] as? JsStr)?.value.orEmpty()
        val effort = (form["reasoningEffort"] as? JsStr)?.value.orEmpty()
        val mode = DraftModes.normalize(provider, (form["mode"] as? JsStr)?.value.orEmpty())

        val variants = effortVariants(entry, model)
        val effortControl = if (variants.isEmpty()) {
            null
        } else {
            SelectControl(
                value = effort,
                options = variants.map { v -> ControlOption(v.value, LabelText.label(v.label).ifEmpty { LabelText.visibleValue(v.value) }) },
            )
        }

        val rows = DraftModes.options(provider)
        val shown = DraftModes.display(provider, mode)
        val modeControl = rows?.let { choices ->
            SelectControl(value = shown, options = choices.map { ControlOption(it.value, it.label, it.hint, danger = it.danger) })
        }
        val current = rows?.firstOrNull { it.value == shown }
        val auto = if (DraftModes.hasAutoChip(provider)) AutoToggle(on = mode == ModeVocabulary.AUTO, hint = AUTO_HINT) else null
        return DraftSessionOptions(
            provider = provider,
            effort = effortControl,
            mode = modeControl,
            modeAriaLabel = if (provider == "codex") CODEX_ARIA else PERMISSION_ARIA,
            modeDanger = current?.danger == true,
            auto = auto,
        )
    }

    /**
     * draft-composer.tsx `effortVariants`: the variants of the model whose value is [model], else the
     * CLI-default row ("" or "default"), else the first row; none when the row lists no models.
     */
    fun effortVariants(entry: ProviderCatalogEntry, model: String): List<com.tether.app.protocol.ModelVariantOption> {
        val models = entry.models
        val picked = models.firstOrNull { it.value == model }
            ?: models.firstOrNull { it.value == "" || it.value == "default" }
            ?: models.firstOrNull()
        return picked?.variants.orEmpty()
    }

    /**
     * The efforts a create may carry for [model] on [entry] (lib/draft-form.ts modelDefinition): the
     * row whose value is [model], else the one whose resolvedModel is; for "" the CLI-default row, else
     * the first. An id the row does not list (a hand-added custom id) offers none.
     */
    fun offeredEfforts(entry: ProviderCatalogEntry, model: String): List<com.tether.app.protocol.ModelVariantOption> {
        val models = entry.models
        val row = models.firstOrNull { it.value == model }
            ?: if (model.isEmpty()) {
                models.firstOrNull { it.value == "" || it.value == "default" } ?: models.firstOrNull()
            } else {
                models.firstOrNull { it.resolvedModel == model }
            }
        return row?.variants.orEmpty()
    }
}

/** issue #48's chip: "Auto" (draft-composer.tsx). */
const val AUTO_LABEL = "Auto"

/** session-settings-sheet.tsx's default Auto row hint. */
const val AUTO_HINT = "Runs without asking — including destructive commands."

/** draft-composer.tsx Mode select `ariaLabel`. */
const val PERMISSION_ARIA = "Permission mode"
const val CODEX_ARIA = "Codex mode"
