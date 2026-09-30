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

/**
 * T7.3 (chat-view.tsx:231): commands withheld from passthrough because they would tear down the
 * session Tether is managing, not just the turn. Everything else the CLI advertises is dispatchable.
 */
val TETHER_BLOCKED_COMMANDS: Set<String> = setOf("exit", "stop")

/**
 * T7.3 (chat-view.tsx:237): commands that succeed but leave the journal describing a conversation
 * the model no longer has. Forwarded with a warning, never silently.
 */
val TETHER_DESYNC_COMMANDS: Set<String> = setOf("clear", "reset", "new", "compact")

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
 * chat-view.tsx:244-274 (v128) `composerCommandList`. The CLI advertises exactly the commands it can
 * dispatch in headless mode, so membership in the advertised list IS the dispatchability signal: the
 * advertised list wins on membership (replace semantics; the controls reply is the fallback), a
 * name-only entry is enriched from the controls reply, and a command is `supported` unless its name
 * or an alias is one of [TETHER_BLOCKED_COMMANDS]. /model is guaranteed present. Sorted
 * supported-first, then by name ([collator] = `localeCompare`; the plain code-unit order when null).
 */
fun composerCommandList(
    advertised: List<CliCommand>?,
    controls: List<SessionCommandOption>,
    collator: com.tether.app.protocol.helpers.JsCollator? = null,
): List<SessionCommandOption> {
    val source: List<CliCommand> = advertised ?: controls.map {
        CliCommand(name = it.name, description = it.description, argumentHint = it.argumentHint, aliases = it.aliases)
    }
    val controlByName = controls.associateBy { it.name }
    val commands = source.map { command ->
        val detail = controlByName[command.name]
        val aliases = command.aliases ?: detail?.aliases
        val names = listOf(command.name) + aliases.orEmpty()
        SessionCommandOption(
            name = command.name,
            description = command.description ?: detail?.description ?: "",
            argumentHint = command.argumentHint ?: detail?.argumentHint,
            aliases = aliases,
            supported = names.none { it in TETHER_BLOCKED_COMMANDS },
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
    val byName: Comparator<SessionCommandOption> = if (collator != null) {
        Comparator { a, b -> collator.compare(a.name, b.name) }
    } else {
        compareBy { it.name }
    }
    return commands.sortedWith(compareBy<SessionCommandOption> { !it.supported }.then(byName))
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
