package com.tether.app.client

import com.tether.app.protocol.ModelVariantOption
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.model.AgentSession

/** A mode row (lib/protocol.ts ModeOption). [danger] = the posture that runs without asking. */
data class ModeChoice(val value: String, val label: String, val hint: String, val danger: Boolean = false)

/** The per-engine mode vocabularies, as the web ships them (lib/protocol.ts:832-911 at 7d65611). */
object ModeVocabulary {
    /** The Claude / opencode-serve Auto posture's permissionMode (chat-view.tsx:1559 OPENCODE_AUTO_MODE_ID). */
    const val AUTO = "bypassPermissions"

    /** v100: `bypassPermissions`/"Auto" is a real row; `dontAsk`/"Locked" is no longer offered. */
    val PERMISSION: List<ModeChoice> = listOf(
        ModeChoice("default", "Manual", "Prompts before every gated tool (Bash, Write, Edit…)"),
        ModeChoice("acceptEdits", "Accept Edits", "Auto-accepts file edits; still prompts for other tools"),
        ModeChoice("plan", "Plan", "Researches and proposes a plan without making changes"),
        ModeChoice(AUTO, "Auto", "Runs every tool without asking — including destructive commands", danger = true),
    )

    /** The static fallback before / without the v62 discovered agents. */
    val OPENCODE: List<ModeChoice> = listOf(
        ModeChoice("default", "Build", "opencode's build agent — edits and runs tools freely"),
        ModeChoice("plan", "Plan", "opencode's plan agent — writes plans only, edits denied"),
    )

    val REASONIX: List<ModeChoice> = listOf(
        ModeChoice("default", "Ask", "Prompts before every gated tool (bash, edits…) via approval chips"),
        ModeChoice("plan", "Plan", "Reasonix plan mode — plans only, edits denied"),
        ModeChoice(AUTO, "YOLO", "Runs everything without asking (tool_approval yolo)", danger = true),
    )

    val PI: List<ModeChoice> = listOf(
        ModeChoice("default", "Manual", "Prompts before every gated tool (bash, write, edit…)"),
        ModeChoice("acceptEdits", "Accept Edits", "Auto-accepts file writes/edits; still prompts for bash"),
        ModeChoice("plan", "Plan", "Blocks every mutating tool with a plan-mode reason"),
        ModeChoice(AUTO, "Auto", "Runs everything without asking — including destructive commands", danger = true),
    )
}

/** One selectable row of a composer control. */
data class ControlOption(
    val value: String,
    val label: String,
    val description: String? = null,
    val tag: String? = null,
    val danger: Boolean = false,
    val disabled: Boolean = false,
)

/** A select: its rows, the value in force, whether it can be opened. [legacy] = the "Legacy models" group. */
data class SelectControl(
    val value: String,
    val options: List<ControlOption>,
    val enabled: Boolean = true,
    val legacy: List<ControlOption> = emptyList(),
    val legacyNote: String? = null,
) {
    /** The label of [value] in [options] or [legacy], else the raw value (session-settings-sheet.tsx findModelOptionLabel). */
    val label: String get() = (options + legacy).firstOrNull { it.value == value }?.label ?: LabelText.label(value).ifEmpty { LabelText.visibleValue(value) }
    val current: ControlOption? get() = (options + legacy).firstOrNull { it.value == value }
}

/** issue #48's standalone Auto toggle (opencode serve-v2 and codex v2; Claude's Auto is a Mode row). */
data class AutoToggle(val on: Boolean, val hint: String)

/** v95 Fast row. [state]: "off" | "cooldown" | "on". */
data class FastModeControl(val state: String, val disabledReason: String?)

/** The row for a read-only or legacy-Codex session: what was restored from the native turn, never editable. */
data class RestoredSettings(val model: String?, val effort: String?, val mode: String?)

/**
 * Everything the composer's Model / Effort / Mode row (and its phone sheet) shows for one session:
 * chat-view.tsx:2128-2495, 2886-2955 and 3704-3776, 4199-4430 at 7d65611. Pure.
 */
data class ComposerControls(
    val provider: String,
    val codexV2: Boolean,
    /** The live row renders (chat-view.tsx:4199). */
    val live: Boolean,
    /** "Model, effort, and mode are set outside Tether…" (a legacy Codex thread). */
    val legacyCodexHint: Boolean,
    val restored: RestoredSettings?,
    val model: SelectControl?,
    val effort: SelectControl?,
    val mode: SelectControl?,
    /** "Permission mode" for Claude / pi, "Mode" otherwise (the select's accessible name). */
    val modeAriaLabel: String,
    val auto: AutoToggle?,
    val fastMode: FastModeControl?,
    /** The flex-1 `.chat-mode-hint`. */
    val hint: String,
    val hintDanger: Boolean,
    /** Round 2 (M1): the stored mode / approval policy is one this app does not know. */
    val unknownMode: Boolean = false,
)

object ComposerControlsModel {

    /**
     * chat-view.tsx:2136-2142 (+ the v62 discovered opencode agents). Round 2 (M2): a discovered
     * agent is shown as "label (value)" unless it is a built-in pair, and marked dangerous by
     * [opencodeAgentNeedsConfirmation] (either source's flag, or a non-built-in with no `false`);
     * [opencode] is the provider-controls snapshot, the second source.
     */
    fun modeOptions(session: AgentSession, controls: ServerMessage.SessionControls?, opencode: OpencodeSnapshot? = null): List<ModeChoice> = when (session.provider) {
        "opencode" -> {
            val discovered = controls?.modes?.takeIf { it.isNotEmpty() }?.take(LabelText.MAX_ITEMS)?.map {
                ModeChoice(
                    it.value,
                    opencodeAgentLabel(it.value, it.label),
                    LabelText.hint(it.hint),
                    opencodeAgentNeedsConfirmation(it.value, controls, opencode),
                )
            }
            // Round 3 (N-L3): the static fallback rows take the same danger rule.
            val fallback = ModeVocabulary.OPENCODE.map { it.copy(danger = opencodeAgentNeedsConfirmation(it.value, controls, opencode)) }
            when {
                discovered == null -> fallback
                // "default" is opencode's build agent even when the discovered list omits it.
                discovered.none { it.value == "default" } -> listOf(fallback.first()) + discovered
                else -> discovered
            }
        }
        "reasonix" -> ModeVocabulary.REASONIX
        "pi" -> ModeVocabulary.PI
        else -> ModeVocabulary.PERMISSION
    }

    /** opencode's own agents: never escalations unless a source flags them. */
    val OPENCODE_BUILTIN_AGENTS: Set<String> = setOf("default", "build", "plan")

    private val OPENCODE_BUILTIN_PAIRS = setOf("default" to "build", "build" to "build", "plan" to "plan")
    private val OPENCODE_BUILTIN_LABELS = setOf("build", "plan", "default", "auto", "manual")

    /**
     * The merged `danger` of an opencode agent over both sources that may list it (the
     * `session-controls` modes and the `opencode-controls` snapshot): true when either flags it,
     * false when one says false and none true, null when neither says.
     */
    fun opencodeAgentDanger(value: String, controls: ServerMessage.SessionControls?, opencode: OpencodeSnapshot?): Boolean? {
        val flags = controls?.modes.orEmpty().filter { it.value == value }.map { it.danger } +
            opencode?.modes?.items.orEmpty().filter { it.value == value }.map { it.danger }
        return when {
            flags.any { it == true } -> true
            flags.any { it == false } -> false
            else -> null
        }
    }

    /** Round 2 (M2): confirmed unless a built-in agent, or one no source flags and one says `false`. Auto always. */
    fun opencodeAgentNeedsConfirmation(value: String, controls: ServerMessage.SessionControls?, opencode: OpencodeSnapshot?): Boolean {
        if (value == ModeVocabulary.AUTO) return true
        val danger = opencodeAgentDanger(value, controls, opencode)
        return danger == true || (value !in OPENCODE_BUILTIN_AGENTS && danger != false)
    }

    /**
     * An agent's display name: its cleaned label, plus " (value)" whenever the value is not the label
     * or the label copies a built-in's, so an agent calling itself "Plan" that is not `plan` shows so.
     * Round 3 (N-M1): the built-in check is on the RAW value (so `PLAN` or `plan` + U+200B is not
     * `plan`), and the value is shown with [LabelText.visibleValue] (its invisible characters
     * spelled out); with no label the collision rule applies to the value itself.
     */
    fun opencodeAgentLabel(value: String, label: String): String {
        val shown = LabelText.visibleValue(value)
        val clean = LabelText.label(label)
        if (clean.isEmpty()) {
            if (value in OPENCODE_BUILTIN_AGENTS) return value
            val looksBuiltIn = LabelText.label(value).lowercase() in OPENCODE_BUILTIN_LABELS
            return if (looksBuiltIn) "Custom agent ($shown)" else shown
        }
        val l = clean.lowercase()
        if ((value to l) in OPENCODE_BUILTIN_PAIRS) return clean
        val collides = l in OPENCODE_BUILTIN_LABELS
        return if (collides || l != value.lowercase()) "$clean ($shown)" else clean
    }

    const val UNKNOWN_MODE_HINT = "This app doesn't know this mode; it may run tools without asking"

    /**
     * chat-view.tsx:2278-2289: the row in force — the operator's pin, else the CLI default the
     * server resolved (v44), else the server-marked `current` row, else a surviving default row.
     */
    fun activeModel(session: AgentSession, controls: ServerMessage.SessionControls?): SessionModelOption? {
        val rows = controls?.models.orEmpty()
        val effective = effectiveModel(session, controls)
        return rows.firstOrNull { it.value == effective }
            ?: rows.firstOrNull { it.current == true }
            ?: rows.firstOrNull { it.value == "" || it.value == "default" }
    }

    fun effectiveModel(session: AgentSession, controls: ServerMessage.SessionControls?): String =
        session.model?.takeIf { it.isNotEmpty() } ?: controls?.defaultModel?.takeIf { it.isNotEmpty() } ?: ""

    fun derive(
        session: AgentSession,
        controls: ServerMessage.SessionControls?,
        codex: ProviderControlsState<CodexSnapshot>?,
        pinnedModels: List<String>,
        opencode: ProviderControlsState<OpencodeSnapshot>? = null,
    ): ComposerControls {
        val provider = session.provider
        val codexV2 = provider == "codex" && session.engineGeneration == CODEX_V2
        val readOnly = session.readOnly
        val live = !readOnly && (provider in setOf("claude", "opencode", "reasonix", "pi", "dsh") || codexV2)
        val restoredMode = if (session.approvalPolicy == "never") "never" else session.permissionMode ?: session.collaborationMode?.mode ?: "default"
        val restored = if ((readOnly || (provider == "codex" && !codexV2)) &&
            (!session.model.isNullOrEmpty() || !session.reasoningEffort.isNullOrEmpty() || restoredMode != "default")
        ) {
            // Round 3 (F1): display text, cleaned like every server value shown.
            fun shown(v: String?) = v?.takeIf { it.isNotEmpty() }?.let { LabelText.label(it).ifEmpty { LabelText.visibleValue(it) } }
            RestoredSettings(shown(session.model), shown(session.reasoningEffort), shown(restoredMode.takeIf { it != "default" }))
        } else {
            null
        }
        val modeProviders = provider in setOf("claude", "opencode", "reasonix", "pi")
        val offeredModes = modeOptions(session, controls, opencode?.snapshot)
        // Round 3 (N-L1): Auto for opencode is the approval policy, never a bare permissionMode.
        val autoByPolicy = provider == "opencode" && session.approvalPolicy == "never"
        val stored = if (autoByPolicy) ModeVocabulary.AUTO else session.permissionMode ?: "default"
        // Round 2 (M1): a stored posture this app does not know is shown as exactly that, with a
        // warning — never as the safe default. Not selectable (the client would refuse it anyway).
        val unknownPolicy = session.approvalPolicy?.takeIf { provider == "opencode" && it != "never" }
        val unknown = when {
            !modeProviders -> null
            unknownPolicy != null -> ModeChoice(UNKNOWN_POLICY_VALUE, "Unknown approval policy (${LabelText.label(unknownPolicy)})", UNKNOWN_MODE_HINT, danger = true)
            offeredModes.none { it.value == stored } && !autoByPolicy ->
                ModeChoice(stored, "Unknown mode (${LabelText.visibleValue(stored)})", UNKNOWN_MODE_HINT, danger = true)
            else -> null
        }
        val modeChoices = if (unknown != null) offeredModes + unknown else offeredModes
        val effectiveMode = when {
            unknown != null -> unknown.value
            modeChoices.any { it.value == stored } -> stored
            else -> "default"
        }
        val currentMode = modeChoices.firstOrNull { it.value == effectiveMode }

        val autoSupported = provider == "claude" || (provider == "opencode" && session.engineGeneration == OPENCODE_V2) || codexV2
        val autoOn = if (codexV2 || provider == "opencode") session.approvalPolicy == "never" else session.permissionMode == ModeVocabulary.AUTO
        val autoHint = when {
            codexV2 -> "Runs everything without asking (never prompts)"
            provider == "opencode" -> "Auto-approves permission requests that are not explicitly denied"
            else -> "Runs everything without asking — including destructive commands"
        }
        val auto = if (autoSupported && provider != "claude") AutoToggle(autoOn, autoHint) else null

        var model: SelectControl? = null
        var effort: SelectControl? = null
        var mode: SelectControl? = null
        var currentEffortLabel = ""
        if (codexV2) {
            val snap = codex?.snapshot
            fun placeholder(status: String?) = listOf(
                ControlOption("", if (snap == null || codex.busy) "Loading…" else if (status == "unsupported") "Not supported" else "Unavailable", disabled = true),
            )
            val catalog = snap?.models?.takeIf { it.ready }
            val selected = catalog?.let { c -> c.items.firstOrNull { it.id == session.model } ?: c.items.firstOrNull() }
            model = SelectControl(
                value = selected?.id ?: "",
                options = catalog?.items?.map { ControlOption(it.id, it.name.ifEmpty { LabelText.visibleValue(it.id) }, it.description.ifEmpty { null }) } ?: placeholder(snap?.models?.status),
                enabled = catalog != null,
            )
            effort = SelectControl(
                value = session.reasoningEffort ?: selected?.defaultReasoningEffort ?: "",
                options = selected?.reasoningEfforts?.map { ControlOption(it.id, LabelText.label(it.id).ifEmpty { LabelText.visibleValue(it.id) }, it.description.ifEmpty { null }) } ?: placeholder(snap?.models?.status),
                enabled = selected != null,
            )
            val collab = snap?.collaborationModes?.takeIf { it.ready }
            mode = SelectControl(
                value = codexCollaborationId(session, collab),
                options = collab?.items?.map { ControlOption(it.id, it.name.ifEmpty { LabelText.visibleValue(it.id) }, LabelText.label(it.mode).ifEmpty { null }) } ?: placeholder(snap?.collaborationModes?.status),
                enabled = collab != null,
            )
        } else if (live) {
            val grouped = groupModelOptions(controls?.models.orEmpty(), pinnedModels)
            model = SelectControl(
                value = effectiveModel(session, controls),
                options = grouped.first,
                legacy = grouped.second,
                legacyNote = if (grouped.second.isNotEmpty()) LEGACY_NOTE else null,
            )
            if (provider in setOf("opencode", "claude", "reasonix", "pi")) {
                val active = activeModel(session, controls)
                val variants = active?.variants.orEmpty()
                val defaultEffort = resolveDefaultEffort(controls?.defaultReasoningEffort, variants)
                val effortValue = session.reasoningEffort?.takeIf { it.isNotEmpty() } ?: defaultEffort
                if (variants.isNotEmpty()) {
                    val rows = variants.take(LabelText.MAX_ITEMS).map { ControlOption(it.value, LabelText.label(it.label).ifEmpty { LabelText.label(it.value) }.ifEmpty { LabelText.visibleValue(it.value) }) }
                    effort = SelectControl(
                        value = effortValue,
                        options = if (effortValue.isNotEmpty()) rows else listOf(ControlOption("", "Default", "The model's default reasoning effort")) + rows,
                    )
                    currentEffortLabel = effort.current?.label ?: ""
                }
            }
            if (modeProviders) {
                mode = SelectControl(
                    value = effectiveMode,
                    options = modeChoices.map { ControlOption(it.value, it.label, it.hint, danger = it.danger, disabled = it === unknown) },
                )
            }
        }

        val fastMode = if (live && provider == "claude" && activeModel(session, controls)?.supportsFastMode == true) {
            FastModeControl(session.fastModeState ?: "off", session.fastModeDisabledReason)
        } else {
            null
        }
        val hint = when {
            auto?.on == true -> auto.hint
            modeProviders -> currentMode?.hint ?: ""
            codexV2 -> ""
            else -> currentEffortLabel
        }
        return ComposerControls(
            provider = provider,
            codexV2 = codexV2,
            live = live,
            legacyCodexHint = !readOnly && provider == "codex" && !codexV2,
            restored = restored,
            model = if (live) model else null,
            effort = if (live) effort else null,
            mode = if (live) mode else null,
            modeAriaLabel = if (provider == "opencode" || provider == "reasonix" || codexV2) "Mode" else "Permission mode",
            auto = if (live) auto else null,
            fastMode = fastMode,
            hint = hint,
            hintDanger = currentMode?.danger == true || autoOn,
            unknownMode = unknown != null,
        )
    }

    /** chat-view.tsx:2393-2411: the catalog item matching the session's mode, else Default, else the first with a mode. */
    fun codexCollaborationId(session: AgentSession, catalog: CodexCatalog<CodexCollaboration>?): String {
        catalog ?: return ""
        val active = session.collaborationMode
        val match = if (active != null) catalog.items.firstOrNull { it.mode == active.mode && it.model == active.settings?.model } else null
        return match?.id
            ?: catalog.items.firstOrNull { it.mode == "default" }?.id
            ?: catalog.items.firstOrNull { !it.mode.isNullOrEmpty() }?.id
            ?: ""
    }

    /**
     * lib/model-picker.mjs groupModelOptions: the advertised rows, then the pinned legacy rows, and
     * (second) the unpinned legacy rows for the "Legacy models" group.
     */
    fun groupModelOptions(models: List<SessionModelOption>, pinned: List<String>): Pair<List<ControlOption>, List<ControlOption>> {
        fun toOption(m: SessionModelOption) = ControlOption(
            value = m.value,
            label = LabelText.label(m.displayName).ifEmpty { LabelText.label(m.value) }.ifEmpty { LabelText.visibleValue(m.value) },
            description = m.description?.let(LabelText::hint)?.ifEmpty { null } ?: if (m.value == "" || m.value == "default") "The CLI's default model" else null,
            tag = LabelText.label(m.providerLabel).ifEmpty { null },
        )
        val live = models.take(LabelText.MAX_ITEMS).filter { it.legacy != true }.map(::toOption)
        val legacy = models.take(LabelText.MAX_ITEMS).filter { it.legacy == true }
        val pinnedRows = legacy.filter { it.value in pinned }.map(::toOption)
        val unpinned = legacy.filter { it.value !in pinned }.map(::toOption)
        return (live + pinnedRows) to unpinned
    }

    /** lib/model-picker.mjs resolveDefaultEffort: the applied effort, only when this model can express it. */
    fun resolveDefaultEffort(applied: String?, variants: List<ModelVariantOption>): String {
        val effort = applied ?: ""
        if (effort.isEmpty()) return ""
        return if (variants.any { it.value == effort }) effort else ""
    }

    /** The synthetic row's value for an unknown opencode approval policy (never a real mode). */
    const val UNKNOWN_POLICY_VALUE = "__unknown_approval_policy__"

    const val LEGACY_NOTE = "Not advertised by your CLI, so this list can be out of date and may include " +
        "models your account cannot use — the CLI validates on the next turn."
}
