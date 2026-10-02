package com.tether.app.client

import com.tether.app.protocol.DelegateMention
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.fold.isOpenCurrentTurn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * T7.3: what became of one `!` command run (`run-command`, v53/v54). Only [Sent] put a frame on the
 * wire; every other value means nothing was transmitted and nothing is held for later.
 */
enum class RunCommandResult {
    Sent,

    /** No live, handshaken socket (or the frame could not be handed to it). */
    NotConnected,

    /** Connected, but the session is not confirmed live on this connection, or the key was drawn for another server. */
    NotLive,

    /** The session is read-only, handed off, archived, or not listed (fail closed). */
    Locked,

    /** The server does not offer command mode for this session's provider (`capabilities.commandRunner`). */
    NotOffered,

    /** Nothing to run, or more than the server's command limit (16 KiB of UTF-8). */
    Invalid,

    /** A foreground run needs the turn slot, and a turn is running (the web: "use Send to background"). */
    Busy,
}

/** T7.3: what became of one "Background" tap (`background-command`, v54). Only [Sent] put a frame on the wire. */
enum class BackgroundCommandResult {
    Sent,
    NotConnected,
    NotLive,
    Locked,

    /** The turn the key was drawn for is no longer a running foreground command turn. */
    NotRunning,
}

/** T7.3: what became of a delegated send (`send.mention`, v103). */
enum class MentionResult {
    /** Recorded in the durable outbox (it goes out like any send). */
    Sent,

    /** Drawn for another server than the one the outbox (and any socket) belongs to, or for none. */
    NotLive,

    /** The session is read-only, handed off, archived, or not listed (fail closed). */
    Locked,

    /** The mention names an agent, model, effort or mode the current catalog does not offer. */
    NotOffered,
}

/**
 * T7.3: one `providers-snapshot` entry (lib/protocol.ts ProviderCatalogEntry), as the composer's `@`
 * picker reads it. Values are kept raw (they go back to the server as the mention); display text is
 * cleaned where it is drawn ([LabelText]).
 */
data class ProviderCatalogEntry(
    val key: String,
    val provider: String,
    /** "ready" | "loading" | "error" | "unavailable". */
    val status: String,
    val models: List<SessionModelOption>,
    val defaultModel: String? = null,
    val label: String? = null,
    val profileId: String? = null,
    /** ta-895: the operator-facing reason a fetch failed (status "error"); cleaned where drawn. */
    val error: String? = null,
    /** ta-895 (v86): a PROFILE row's engine, echoed for its badge; absent on the default rows. */
    val extends: String? = null,
    /**
     * ta-2uq: when the server last settled this row's model list (epoch ms; the model browser's
     * "Updated …" and the refresh throttle's settle signal). Null when absent or not a finite number.
     */
    val fetchedAt: Long? = null,
) {
    companion object {
        /**
         * Lenient: an entry without a string `key` / `provider` / `status` is dropped; a model row
         * that does not decode is dropped; at most [LabelText.MAX_ITEMS] entries and models each.
         */
        fun parse(entries: List<JsonObject>): List<ProviderCatalogEntry> = entries.asSequence().take(LabelText.MAX_ITEMS).mapNotNull { o ->
            fun s(name: String): String? = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val key = s("key")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val provider = s("provider")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val status = s("status") ?: return@mapNotNull null
            val models = (o["models"] as? JsonArray).orEmpty().asSequence().take(LabelText.MAX_ITEMS).mapNotNull { m ->
                runCatching { TetherJson.decodeFromJsonElement(SessionModelOption.serializer(), m) }.getOrNull()
            }.toList()
            val fetchedAt = (o["fetchedAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE.toDouble() }?.toLong()
            ProviderCatalogEntry(key, provider, status, models, s("defaultModel"), s("label"), s("profileId"), s("error"), s("extends"), fetchedAt)
        }.toList()
    }
}

/**
 * T7.3 security semantics for the composer's commands, as pure checks. RealTetherClient runs them
 * under its lock, after the link / origin / liveness / lock checks, against the session's CURRENT
 * state: its row, the `ready` frame's provider capabilities, its projection tree, and the catalog the
 * server pushed on this connection.
 */
object CommandGuard {

    /** protocol-validate.mjs LIMITS.COMMAND_BYTES. */
    const val COMMAND_MAX_BYTES = 16 * 1024

    /** protocol-validate.mjs validateDelegateMention: model / reasoningEffort are bounded to 200 bytes. */
    const val MENTION_VALUE_MAX_BYTES = 200

    /**
     * chat-view.tsx:2163: command mode is offered only when the server advertises `commandRunner`
     * for the session's provider (server.mjs commandRunnerEnabledFor). Absent = not offered.
     */
    fun commandModeOffered(session: AgentSession, providers: List<ProviderInfo>): Boolean =
        providers.firstOrNull { it.id == session.provider }?.capabilities?.commandRunner == true

    /**
     * Null when [command] may run for [session] now. The command itself is the operator's own words
     * (the web's `!` mode is a free-form shell line, chat-view.tsx:3083-3102): it is checked for
     * shape (non-empty, the server's size limit), never rewritten.
     */
    fun checkRun(session: AgentSession, providers: List<ProviderInfo>, tree: JsObj?, command: String, background: Boolean): RunCommandResult? {
        if (session.runtimeArchived) return RunCommandResult.Locked
        if (!commandModeOffered(session, providers)) return RunCommandResult.NotOffered
        if (command.isBlank() || command.toByteArray(Charsets.UTF_8).size > COMMAND_MAX_BYTES) return RunCommandResult.Invalid
        // chat-view.tsx:3093: foreground needs the turn slot; background does not.
        if (!background && tree != null && activeOpenTurn(tree) != null) return RunCommandResult.Busy
        return null
    }

    /**
     * v54 `commandRun`: the id of [tree]'s open active turn when it is a FOREGROUND `!` command run
     * (chat-view.tsx:2166), else null. This is what Interrupt reads as "Stop" and what Background moves.
     */
    fun foregroundCommandTurn(tree: JsObj?): String? {
        val turnId = activeOpenTurn(tree ?: return null) ?: return null
        val turn = (tree["turnsById"] as? JsObj)?.get(turnId) as? JsObj ?: return null
        return if (turn["commandRun"] is JsObj) turnId else null
    }

    /** Null when the Background key drawn for [expectedTurnId] may send now. */
    fun checkBackground(tree: JsObj?, expectedTurnId: String): BackgroundCommandResult? =
        if (expectedTurnId.isNotEmpty() && foregroundCommandTurn(tree) == expectedTurnId) null else BackgroundCommandResult.NotRunning

    private fun activeOpenTurn(tree: JsObj): String? {
        val id = (tree["activeTurnId"] as? JsStr)?.value?.takeIf { it.isNotEmpty() } ?: return null
        return if (isOpenCurrentTurn(tree, JsStr(id))) id else null
    }

    /**
     * chat-view.tsx:2744-2755: the agents the `@` picker offers a session — every enabled provider
     * row of the catalog, never a profile row, an ACP agent or Gemini, and none at all to a delegate
     * child (a session with a parent: its tool set has no tether_delegate).
     */
    fun delegateAgents(session: AgentSession, catalog: List<ProviderCatalogEntry>): List<ProviderCatalogEntry> {
        if (!session.parentSessionId.isNullOrEmpty()) return emptyList()
        return catalog.filter { it.status != "unavailable" && it.profileId.isNullOrEmpty() && it.provider != "acp" && it.provider != "gemini" }
    }

    /**
     * T7.3: a mention goes out only as the picker built it from what the server offered: an agent
     * [delegateAgents] lists for [session], a model that agent's catalog row lists (or none: its
     * default), an effort that model lists (or none), mode "review" or "build", every value within
     * the validator's bounds.
     */
    fun mentionOffered(session: AgentSession, mention: DelegateMention, catalog: List<ProviderCatalogEntry>): Boolean {
        if (mention.mode != "review" && mention.mode != "build") return false
        val entry = delegateAgents(session, catalog).firstOrNull { it.provider == mention.provider } ?: return false
        val modelId = mention.model
        if (modelId == null) return mention.reasoningEffort == null
        if (!bounded(modelId)) return false
        val model = entry.models.firstOrNull { it.value == modelId } ?: return false
        val effort = mention.reasoningEffort ?: return true
        return bounded(effort) && model.variants.orEmpty().any { it.value == effort }
    }

    private fun bounded(value: String): Boolean = value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= MENTION_VALUE_MAX_BYTES
}
