package com.tether.app.protocol.reduce

import com.tether.app.protocol.SessionCommandOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.model.CliCommand

/**
 * Ports of the composer-controls derivations in aidash/components/chat-view.tsx
 * (pickerModels / activeModel / composerCommandList / resolveModelArg). Pure:
 * no I/O, no Compose. The wire types SessionModelOption / SessionCommandOption
 * are field-identical to the web's ModelOption / SlashCommandInfo.
 */

/** Tether-native slash commands — the closed allow-set (advertisement ≠ executable). */
private val TETHER_NATIVE_COMMANDS = setOf("model")

/** The picker always offers exactly ONE "Default" (clear) row. */
fun isDefaultModelRow(model: SessionModelOption): Boolean =
    model.value.isEmpty() || model.value == "default"

/**
 * The model-picker rows: the live list plus a synthesized Default row when the
 * source has none; default rows are labeled with the concrete model they
 * resolve to (exact id match first, then the normalized-containment fallback);
 * with no explicit selection the default row is marked `current`.
 */
fun pickerModels(models: List<SessionModelOption>, sessionModel: String?): List<SessionModelOption> {
    if (models.isEmpty()) return emptyList()
    val list = if (models.any(::isDefaultModelRow)) {
        models
    } else {
        listOf(SessionModelOption(value = "", displayName = "Default", description = "The CLI's default model")) + models
    }
    val labeled = list.map { model ->
        if (!isDefaultModelRow(model)) return@map model
        val resolved = model.resolvedModel?.takeIf { it.isNotEmpty() }
        val match = resolved?.let { r ->
            list.firstOrNull { !isDefaultModelRow(it) && it.value == r }
                ?: list.firstOrNull { !isDefaultModelRow(it) && it.value.isNotEmpty() && !modelsDiverge(it.value, r) }
        }
        val name = match?.displayName ?: resolved
        model.copy(displayName = if (!name.isNullOrEmpty()) "CLI Default ($name)" else "CLI Default")
    }
    if (sessionModel.isNullOrEmpty() && labeled.none { it.current == true }) {
        return labeled.map { if (isDefaultModelRow(it)) it.copy(current = true) else it }
    }
    return labeled
}

/** The model actually in effect: explicit pick, else server `current`, else the default row. */
fun activeModel(
    models: List<SessionModelOption>,
    picker: List<SessionModelOption>,
    sessionModel: String?,
): SessionModelOption? =
    models.firstOrNull { it.value == sessionModel }
        ?: models.firstOrNull { it.current == true }
        ?: picker.firstOrNull { it.current == true }

/**
 * The composer's slash-command list. CLI advertisement and Tether execution
 * support are deliberately independent: the advertised list wins on membership
 * (replace semantics), name-only entries are enriched from controls, and the
 * closed native allow-set decides `supported`. /model is guaranteed present.
 * Sorted supported-first, then by name.
 */
fun composerCommandList(
    advertised: List<CliCommand>?,
    controls: List<SessionCommandOption>,
): List<SessionCommandOption> {
    val source: List<CliCommand> = advertised ?: controls.map {
        CliCommand(name = it.name, description = it.description, argumentHint = it.argumentHint, aliases = it.aliases)
    }
    val controlByName = controls.associateBy { it.name }
    val commands = source.map { command ->
        val detail = controlByName[command.name]
        val aliases = command.aliases ?: detail?.aliases
        SessionCommandOption(
            name = command.name,
            description = command.description ?: detail?.description ?: "",
            argumentHint = command.argumentHint ?: detail?.argumentHint,
            aliases = aliases,
            supported = TETHER_NATIVE_COMMANDS.contains(command.name) ||
                aliases?.any { TETHER_NATIVE_COMMANDS.contains(it) } == true,
        )
    }.toMutableList()
    if (commands.none { it.name == "model" }) {
        commands.add(
            0,
            SessionCommandOption(
                name = "model",
                description = "Switch the model for this session",
                argumentHint = "[model]",
                aliases = null,
                supported = true,
            ),
        )
    }
    return commands.sortedWith(compareBy({ !it.supported }, { it.name }))
}

/**
 * Resolve a free-text /model argument ("fable", "Sonnet", a full id) against
 * the known models: exact value, then exact display name, then substring of
 * either — all case-insensitive.
 */
fun resolveModelArg(arg: String, models: List<SessionModelOption>): SessionModelOption? {
    val q = arg.trim().lowercase()
    if (q.isEmpty()) return null
    return models.firstOrNull { it.value.lowercase() == q }
        ?: models.firstOrNull { it.displayName.lowercase() == q }
        ?: models.firstOrNull { it.value.lowercase().contains(q) || it.displayName.lowercase().contains(q) }
}
