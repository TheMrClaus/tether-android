package com.tether.app.protocol

import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.protocol.model.HistorySession
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.model.SessionProjection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Server -> client frames (specs/protocol-spec.md §4). One JSON object per
 * text frame. Unknown `type` values parse to [ServerMessage.Unknown] and are
 * ignored; a malformed frame of a known type also degrades to Unknown rather
 * than throwing inside the socket handler.
 */
sealed interface ServerMessage {

    data class Ready(
        val protocolVersion: Int,
        val sessions: List<AgentSession>,
        val providers: List<ProviderInfo>,
        val workspaceRoot: String?,
    ) : ServerMessage

    data class VersionMismatch(val requiredVersion: Int, val message: String?) : ServerMessage

    data class Created(val session: AgentSession) : ServerMessage

    /** Broadcast session-row update. */
    data class SessionUpdate(val session: AgentSession) : ServerMessage

    data class Histories(val cwd: String, val sessions: List<HistorySession>) : ServerMessage

    data class SearchResults(val cwd: String, val query: String, val hits: List<SearchHit>) : ServerMessage

    data class Directories(val listing: DirectoryListing) : ServerMessage

    data class InterruptResult(
        val sessionId: String,
        val turnId: String?,
        val status: String,
        val error: String?,
        val stillQueued: Boolean?,
    ) : ServerMessage

    data class SessionControls(
        val sessionId: String,
        val models: List<SessionModelOption>,
        val commands: List<SessionCommandOption>,
        val model: String?,
    ) : ServerMessage

    data class Log(val entries: List<LogEntry>, val bootId: String) : ServerMessage

    data class ErrorFrame(val message: String) : ServerMessage

    /** Attach reply or manager-pushed broadcast. `state` is always complete. */
    data class Snapshot(
        val sessionId: String,
        val throughSeq: Long,
        val state: SessionProjection,
        val reset: Boolean = false,
    ) : ServerMessage

    /** Flat envelope: `{ sessionId, event: { ...AgentEvent, seq, ts } }`. */
    data class Event(val sessionId: String, val event: AgentEvent) : ServerMessage

    /** Anything unrecognized or unparseable — must be inert. */
    data class Unknown(val type: String?) : ServerMessage

    companion object {
        /** Parse one text frame. Never throws. */
        fun parse(text: String): ServerMessage {
            val root = try {
                TetherJson.parseToJsonElement(text) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return Unknown(null)
            val type = root.str("type") ?: return Unknown(null)
            return try {
                parseKnown(type, root)
            } catch (_: Exception) {
                Unknown(type)
            }
        }

        private fun parseKnown(type: String, root: JsonObject): ServerMessage = when (type) {
            "ready" -> Ready(
                protocolVersion = root.intOrNull("protocolVersion") ?: -1,
                sessions = root.arr("sessions")?.let {
                    TetherJson.decodeFromJsonElement(sessionListSerializer, it)
                } ?: emptyList(),
                providers = root.arr("providers")?.let {
                    TetherJson.decodeFromJsonElement(providerListSerializer, it)
                } ?: emptyList(),
                workspaceRoot = root.str("workspaceRoot"),
            )
            "version_mismatch" -> VersionMismatch(
                requiredVersion = root.intOrNull("requiredVersion") ?: -1,
                message = root.str("message"),
            )
            "created" -> Created(decodeSession(root))
            "session" -> SessionUpdate(decodeSession(root))
            "histories" -> Histories(
                cwd = root.str("cwd") ?: "",
                sessions = root.arr("sessions")?.let {
                    TetherJson.decodeFromJsonElement(historyListSerializer, it)
                } ?: emptyList(),
            )
            "search-results" -> SearchResults(
                cwd = root.str("cwd") ?: "",
                query = root.str("query") ?: "",
                hits = root.arr("hits")?.let {
                    TetherJson.decodeFromJsonElement(searchHitListSerializer, it)
                } ?: emptyList(),
            )
            "directories" -> Directories(
                TetherJson.decodeFromJsonElement(DirectoryListing.serializer(), root.obj("listing")!!),
            )
            "interrupt_result" -> InterruptResult(
                sessionId = root.str("sessionId") ?: "",
                turnId = root.str("turnId"),
                status = root.str("status") ?: "",
                error = root.str("error"),
                stillQueued = root.boolOrNull("stillQueued"),
            )
            "session-controls" -> SessionControls(
                sessionId = root.str("sessionId") ?: "",
                models = root.arr("models")?.let {
                    TetherJson.decodeFromJsonElement(modelOptionListSerializer, it)
                } ?: emptyList(),
                commands = root.arr("commands")?.let {
                    TetherJson.decodeFromJsonElement(commandOptionListSerializer, it)
                } ?: emptyList(),
                model = root.str("model"),
            )
            "log" -> Log(
                entries = root.arr("entries")?.let {
                    TetherJson.decodeFromJsonElement(logEntryListSerializer, it)
                } ?: emptyList(),
                bootId = (root["bootId"]?.jsonPrimitive?.content) ?: "",
            )
            "error" -> ErrorFrame(root.str("message") ?: "")
            "snapshot" -> Snapshot(
                sessionId = root.str("sessionId") ?: "",
                throughSeq = root.nonNegLong("throughSeq") ?: 0,
                state = TetherJson.decodeFromJsonElement(SessionProjection.serializer(), root.obj("state")!!),
                reset = root.boolTrue("reset"),
            )
            "event" -> Event(
                sessionId = root.str("sessionId") ?: "",
                event = AgentEvent.parse(root.obj("event") ?: JsonObject(emptyMap())),
            )
            else -> Unknown(type)
        }

        private fun decodeSession(root: JsonObject): AgentSession =
            TetherJson.decodeFromJsonElement(AgentSession.serializer(), root.obj("session")!!)

        private val sessionListSerializer =
            kotlinx.serialization.builtins.ListSerializer(AgentSession.serializer())
        private val providerListSerializer =
            kotlinx.serialization.builtins.ListSerializer(ProviderInfo.serializer())
        private val historyListSerializer =
            kotlinx.serialization.builtins.ListSerializer(HistorySession.serializer())
        private val searchHitListSerializer =
            kotlinx.serialization.builtins.ListSerializer(SearchHit.serializer())
        private val modelOptionListSerializer =
            kotlinx.serialization.builtins.ListSerializer(SessionModelOption.serializer())
        private val commandOptionListSerializer =
            kotlinx.serialization.builtins.ListSerializer(SessionCommandOption.serializer())
        private val logEntryListSerializer =
            kotlinx.serialization.builtins.ListSerializer(LogEntry.serializer())
    }
}

/** search-results hit: HistorySession & { snippet, matchCount }. */
@Serializable
data class SearchHit(
    val historyId: String,
    val provider: String = "",
    val name: String = "",
    val cwd: String = "",
    val updatedAt: Long = 0,
    val snippet: String = "",
    val matchCount: Int = 0,
)

@Serializable
data class SessionModelOption(
    val value: String,
    val displayName: String = "",
    val description: String? = null,
    val current: Boolean? = null,
    val resolvedModel: String? = null,
)

@Serializable
data class SessionCommandOption(
    val name: String,
    val description: String? = null,
    val argumentHint: String? = null,
    val aliases: List<String>? = null,
    val supported: Boolean = false,
)

@Serializable
data class LogEntry(
    val seq: Long,
    val ts: Long = 0,
    val level: String = "info",
    val event: String = "",
    val sid: String? = null,
    val turnId: String? = null,
    val nativeId: String? = null,
    val outcome: String? = null,
    val durationMs: Long? = null,
    val message: String? = null,
)
