package com.tether.app.protocol

import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.protocol.model.HistorySession
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.model.OverviewActivity
import com.tether.app.protocol.model.OverviewCard
import com.tether.app.protocol.model.OverviewCounts
import com.tether.app.protocol.model.OverviewFacets
import com.tether.app.protocol.model.OverviewNormalizedFilters
import com.tether.app.protocol.model.OverviewPendingPanel
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Server -> client frames: every member of tether lib/protocol.ts `ServerMessage`
 * at v132 (see [TYPES]). One JSON object per text frame.
 *
 * TOLERANT DECODING (PLAN D4) — [parse] never throws:
 *  - unknown `type`, non-JSON, or no `type` -> [Unknown] (the caller logs it);
 *  - unknown fields are ignored;
 *  - a missing optional field -> null / its documented default;
 *  - a present-but-wrongly-typed OPTIONAL scalar degrades to null (e.g. a string
 *    `nonce: 5` reads as absent), and a wrongly-typed element inside an optional
 *    list is dropped rather than failing the frame;
 *  - a missing or wrongly-typed REQUIRED field -> [Unknown] with a `reason`;
 *  - nested objects that already have a Kotlin model (AgentSession, ProviderInfo,
 *    HistorySession, DirectoryListing, SearchHit, …) are decoded per element: a
 *    malformed LIST element is dropped (the frame survives), a malformed required
 *    SINGLE object makes the frame [Unknown] with a reason.
 *
 * Deep payloads with no Kotlin model yet stay raw [JsonObject]s (named after
 * their TS type in each KDoc). `snapshot.state` and `turns-detail.turns` are the
 * v128 fold's own JsValue trees ([JsObj], T2.1D), handed to the reducer untouched.
 */
sealed interface ServerMessage {

    // ---- handshake / liveness ---------------------------------------------------

    /**
     * [nativeProtocolFloor] is v129 (required in TS, but absent from a v128
     * server, hence nullable). [workspaceRoot] is TS `string`; a null / wrongly
     * typed value degrades to null rather than dropping the whole handshake.
     * [hiddenAgentSessionCount] is v135 (issue #227 part 2): how many agent-created / derived
     * sessions the server kept out of [sessions]. Display only ("N agent sessions are not on
     * your list"); null when absent (a pre-v135 server) or not a non-negative number.
     */
    data class Ready(
        val protocolVersion: Int,
        val sessions: List<AgentSession>,
        val providers: List<ProviderInfo>,
        val workspaceRoot: String?,
        val nativeProtocolFloor: Int? = null,
        val hiddenAgentSessionCount: Int? = null,
    ) : ServerMessage

    data class Pong(val nonce: String?) : ServerMessage

    /**
     * [requiredVersion] defaults to -1 when missing: the arrival of the frame is
     * itself the signal, so it is never demoted to Unknown. The v129 fields are
     * present only on a reply to a NATIVE hello. [reason]: "client_too_old" |
     * "server_too_old".
     */
    data class VersionMismatch(
        val requiredVersion: Int,
        val message: String?,
        val nativeProtocolFloor: Int? = null,
        val serverProtocolVersion: Int? = null,
        val reason: String? = null,
    ) : ServerMessage

    /** Batch of operational records. [bootId] is a number on the wire, kept as its text. */
    data class Log(val entries: List<LogEntry>, val bootId: String) : ServerMessage

    // ---- nodes --------------------------------------------------------------------

    data class Nodes(val nodes: List<NodeSummary>) : ServerMessage

    data class NodeResult(
        val ok: Boolean,
        val nodeId: String? = null,
        val message: String? = null,
        val requestId: String? = null,
    ) : ServerMessage

    // ---- sessions / discovery ---------------------------------------------------

    data class Created(val session: AgentSession, val requestId: String? = null) : ServerMessage

    /** Broadcast session-row update. */
    data class SessionUpdate(val session: AgentSession) : ServerMessage

    data class Histories(
        val cwd: String,
        val sessions: List<HistorySession>,
        val requestId: String? = null,
    ) : ServerMessage

    data class SessionOrder(val cwd: String, val order: List<String>) : ServerMessage

    /** [schedules]: ScheduledAction[]; [continuations]: ScheduledContinuation[] — raw. */
    data class ScheduledActions(
        val schedules: List<JsonObject>,
        val continuations: List<JsonObject>,
    ) : ServerMessage

    data class Seen(val historyId: String, val seenAt: Long) : ServerMessage

    data class SearchResults(val cwd: String, val query: String, val hits: List<SearchHit>) : ServerMessage

    data class GlobalSearchResults(val requestId: Long, val query: String, val hits: List<SearchHit>) : ServerMessage

    data class Directories(val listing: DirectoryListing, val requestId: String? = null) : ServerMessage

    // ---- headless session stream ------------------------------------------------

    /** Flat envelope: `{ sessionId, event: { ...AgentEvent, seq, ts } }`. */
    data class Event(val sessionId: String, val event: AgentEvent) : ServerMessage

    /**
     * Attach reply or manager-pushed broadcast.
     *
     * [state] is the SessionProjection as a JsValue tree — the source of truth the
     * v128 fold continues from (T2.1D). v115: it is ABSENT (null) when the client's
     * cursor already equals [throughSeq]. [trimmedBefore]: turns below that index had
     * their blocksById stripped (fetch them with `fetch-turns`). [events] is normally
     * omitted from the wire.
     *
     * [projection] is the legacy typed view (protocol/model) through the
     * [LegacyProjectionAdapter], decoded lazily and tolerantly: null when state is
     * absent OR its required session fields do not fit the typed model.
     */
    data class Snapshot(
        val sessionId: String,
        val throughSeq: Long,
        val state: JsObj?,
        val reset: Boolean = false,
        val trimmedBefore: Int? = null,
        val events: List<AgentEvent>? = null,
    ) : ServerMessage {
        val projection: SessionProjection? by lazy { state?.let { LegacyProjectionAdapter.adaptOnce(it) } }

        /** True when the frame carries a state (false = v115 "nothing changed" delta). */
        val hasState: Boolean get() = state != null

        /**
         * Legacy non-null typed accessor. Throws when there is no decodable state —
         * check [projection] / [hasState] first.
         */
        val typedState: SessionProjection
            get() = checkNotNull(projection) { "snapshot for $sessionId carries no decodable state" }
    }

    /** v115: `turns` is Record<turnId, TurnProjection>, as JsValue trees, for the reducer layer. */
    data class TurnsDetail(
        val sessionId: String,
        val fromIndex: Int,
        val toIndex: Int,
        val turns: JsObj,
    ) : ServerMessage

    /**
     * Declared in the TS union but not sent by server.mjs at 7d65611 (approvals
     * arrive as `event` frames). [input] is TS `unknown` (null when absent);
     * [metadata] is ApprovalRequestMetadata, raw.
     */
    data class Approval(
        val sessionId: String,
        val requestId: String,
        val toolId: String,
        val name: String,
        val input: JsonElement?,
        val choices: List<ApprovalChoice>? = null,
        val metadata: JsonObject? = null,
    ) : ServerMessage

    /** Declared-but-unsent twin of [Approval]. */
    data class ApprovalResolved(val sessionId: String, val requestId: String, val choiceId: String) : ServerMessage

    /**
     * All three TS variants: [status] "requested" (turnId, optional stillQueued
     * queueIds) | "no_active_turn" (turnId null) | "failed" (turnId, error).
     */
    data class InterruptResult(
        val sessionId: String,
        val turnId: String?,
        val status: String,
        val error: String?,
        val stillQueued: List<String>? = null,
    ) : ServerMessage

    // ---- per-session engine controls --------------------------------------------

    data class SessionControls(
        val sessionId: String,
        val models: List<SessionModelOption>,
        val commands: List<SessionCommandOption>,
        val model: String?,
        val reasoningEffort: String? = null,
        val defaultModel: String? = null,
        val defaultReasoningEffort: String? = null,
        val modes: List<ModeOption>? = null,
        /** "off" | "cooldown" | "on". */
        val fastModeState: String? = null,
        val fastModeDisabledReason: String? = null,
    ) : ServerMessage

    /** [snapshot]: CodexControlCatalogSnapshot, raw. */
    data class CodexControls(val sessionId: String, val snapshot: JsonObject) : ServerMessage

    data class CodexControlResult(val sessionId: String, val ok: Boolean, val message: String) : ServerMessage

    /** [snapshot]: OpencodeServeControlCatalogSnapshot, raw. */
    data class OpencodeControls(val sessionId: String, val snapshot: JsonObject) : ServerMessage

    data class OpencodeControlResult(val sessionId: String, val ok: Boolean, val message: String) : ServerMessage

    // ---- operator settings / registries ------------------------------------------

    data class AdvancedSettings(
        val claudeCliVersion: String?,
        val discovered: List<ClaudeCliVersion>,
        val envForced: Boolean,
        val envPath: String?,
        val effectiveSource: String,
        val effectiveVersion: String?,
    ) : ServerMessage

    /**
     * [settings]: ServerSettings, raw. [envForced]: setting key -> forced by env
     * (non-boolean values dropped). [detected]: Record<string, EngineDetection>, raw.
     *
     * ta-t7l: decoded tolerantly (a missing or wrongly typed part is empty / false, never a dropped
     * frame), so this frame never lands in [Unknown.raw] with its secrets. [settings] carries the
     * server's `password` and `proxyToken` in plaintext: [toString] prints no setting VALUE, only
     * the keys, so a stray log line cannot leak them.
     */
    data class ServerSettings(
        val settings: JsonObject,
        val envForced: Map<String, Boolean>,
        val restartRequired: Boolean,
        val discovered: List<ClaudeCliVersion>,
        val detected: JsonObject,
    ) : ServerMessage {
        override fun toString(): String =
            "ServerSettings(settings=${settings.keys.sorted()}, envForced=$envForced, restartRequired=$restartRequired, " +
                "discovered=$discovered, detected=${detected.keys.sorted()})"
    }

    /**
     * Retired server-side (server.mjs 887c222 never sends it; the request is answered with
     * `error`), still in the TS union. [agents]: AcpAgentEntry[], raw. ta-q6p: decoded tolerantly
     * (a missing or non-array `agents` is empty, a non-object entry dropped) and routed nowhere.
     * An entry carries its `env` in plaintext, so [toString] prints the entry count only.
     */
    data class AcpAgents(val agents: List<JsonObject>) : ServerMessage {
        override fun toString(): String = "AcpAgents(agents=${agents.size})"
    }

    /**
     * v84 `providers` (the reply to `providers`, and the broadcast after every `set-providers`):
     * [profiles] is ProfileEntry[], raw, every key as it came. ta-q6p: decoded tolerantly, so the
     * frame never lands in [Unknown.raw] with its secrets: a missing or non-array `profiles` reads
     * as an empty list and a non-object entry is dropped, and either makes [intact] false (the list
     * is not the array as sent; the server's own store never sends one). Each profile's
     * `env` VALUES are secrets the server sends in plaintext: [toString] prints the count only.
     */
    data class Providers(val profiles: List<JsonObject>, val intact: Boolean = true) : ServerMessage {
        override fun toString(): String = "Providers(profiles=${profiles.size}, intact=$intact)"
    }

    /** [entries]: ProviderCatalogEntry[], raw. */
    data class ProvidersSnapshot(val entries: List<JsonObject>) : ServerMessage

    data class MetadataDraftResult(val requestId: String, val result: MetadataDraft) : ServerMessage

    data class MetadataDraftError(val requestId: String, val error: String) : ServerMessage

    // ---- worktrees / git -----------------------------------------------------------

    /** [info]: WorktreeSourceInfo, raw. */
    data class WorktreeSource(val info: JsonObject, val requestId: String? = null) : ServerMessage

    /** [snapshot]: WorktreeScriptsSnapshot, raw. */
    data class WorktreeScripts(val snapshot: JsonObject) : ServerMessage

    data class WorktreeLogs(
        val sessionId: String,
        val name: String,
        val lines: List<String>,
        val dropped: Int,
    ) : ServerMessage

    /** [diff]: WorktreeDiffSummary | null, raw. */
    data class WorktreeDiff(val sessionId: String, val diff: JsonObject?) : ServerMessage

    data class GitDiffFile(
        val sessionId: String,
        val path: String,
        val hunks: String,
        val truncated: Boolean,
        val binary: Boolean,
        val error: String? = null,
    ) : ServerMessage

    /** [changeRequest]: ChangeRequestState | null, raw. [unknown] = lookup failed (distinct from null). */
    data class ChangeRequest(
        val sessionId: String,
        val changeRequest: JsonObject?,
        val unknown: Boolean,
    ) : ServerMessage

    /** [brief]: Brief, raw. [instruction] is the composer prefill; [markdown] the digest. */
    data class HandoffBrief(
        val sourceId: String,
        val brief: JsonObject,
        val markdown: String,
        val instruction: String,
        val requestId: String? = null,
    ) : ServerMessage

    data class ErrorFrame(val message: String, val requestId: String? = null) : ServerMessage

    // ---- v131 Overview feed (opt-in; see model/Overview.kt) ---------------------------

    /**
     * Sent only to a socket that sent `overview-subscribe` — this app never does yet. Replaces all
     * local overview state. [feedId] + [cursor] are required (the step identity); the rest
     * defaults tolerantly. [activity]: <= 50, newest first.
     */
    data class OverviewSnapshot(
        val feedId: String,
        val cursor: Long,
        val generatedAt: Long = 0,
        val activitySince: Long = 0,
        val filters: OverviewNormalizedFilters = OverviewNormalizedFilters(),
        val page: Int = 0,
        val pageSize: Int = 0,
        val pageCount: Int = 0,
        val totalCards: Int = 0,
        val counts: OverviewCounts = OverviewCounts(),
        val facets: OverviewFacets = OverviewFacets(),
        val cards: List<OverviewCard> = emptyList(),
        val pending: OverviewPendingPanel = OverviewPendingPanel(),
        val activity: List<OverviewActivity> = emptyList(),
    ) : ServerMessage

    /** Sent only to a subscribed socket. [prevCursor] != the last cursor (or a new [feedId]) = re-subscribe. */
    data class OverviewDelta(
        val feedId: String,
        val cursor: Long,
        val prevCursor: Long,
        val upserts: List<OverviewCard> = emptyList(),
        val removals: List<String> = emptyList(),
        val counts: OverviewCounts = OverviewCounts(),
        val pending: OverviewPendingPanel = OverviewPendingPanel(),
        val activity: List<OverviewActivity> = emptyList(),
    ) : ServerMessage

    /**
     * Anything unrecognized or unparseable — must be inert. [type] is null for a
     * non-object / typeless frame; [raw] is the parsed frame when it was JSON;
     * [reason] explains a known-type frame that failed a required field.
     */
    data class Unknown(val type: String?, val raw: JsonObject? = null, val reason: String? = null) : ServerMessage

    companion object {
        /** Every `type` discriminator of the v132 ServerMessage union this module decodes. */
        val TYPES: Set<String> get() = ServerDecoders.decoders.keys

        /**
         * How deep a frame may nest. kotlinx's tree reader recurses per nested bracket, so a
         * hostile 100k-deep frame overflowed the socket reader's stack (a crash on every
         * reconnect). A deeper frame is NOT dropped (a dropped event or snapshot would re-attach
         * in a quiet loop): every container past this depth is replaced by `null` in the text
         * before kotlinx reads it ([flattenDeeperThan]), the rule the fold applies at
         * [com.tether.app.protocol.tree.JsCodec.MAX_DEPTH] anyway.
         */
        const val MAX_FRAME_DEPTH: Int = 1024

        /** Whether [text] nests brackets deeper than [limit] (strings and escapes skipped; linear, no allocation). */
        fun nestsDeeperThan(text: String, limit: Int): Boolean {
            var depth = 0
            var inString = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (inString) {
                    if (c == '\\') i++ else if (c == '"') inString = false
                } else when (c) {
                    '"' -> inString = true
                    '[', '{' -> if (++depth > limit) return true
                    ']', '}' -> depth--
                }
                i++
            }
            return false
        }

        /**
         * [text] with every array or object that would open deeper than [limit] replaced by
         * `null` (strings and escapes respected; linear). Unbalanced input stays unbalanced, so
         * the parser still rejects it.
         */
        fun flattenDeeperThan(text: String, limit: Int): String {
            if (!nestsDeeperThan(text, limit)) return text
            val out = StringBuilder(text.length)
            var depth = 0
            var inString = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (inString) {
                    out.append(c)
                    if (c == '\\' && i + 1 < text.length) {
                        out.append(text[i + 1])
                        i++
                    } else if (c == '"') {
                        inString = false
                    }
                    i++
                    continue
                }
                when (c) {
                    '"' -> {
                        inString = true
                        out.append(c)
                    }
                    '[', '{' -> if (depth + 1 > limit) {
                        // Skip the whole container, strings included, and write `null` for it.
                        var d = 0
                        var s = false
                        while (i < text.length) {
                            val k = text[i]
                            if (s) {
                                if (k == '\\') i++ else if (k == '"') s = false
                            } else when (k) {
                                '"' -> s = true
                                '[', '{' -> d++
                                ']', '}' -> if (--d == 0) break
                            }
                            i++
                        }
                        out.append("null")
                    } else {
                        depth++
                        out.append(c)
                    }
                    ']', '}' -> {
                        depth--
                        out.append(c)
                    }
                    else -> out.append(c)
                }
                i++
            }
            return out.toString()
        }

        /** Parse one text frame. Never throws. */
        fun parse(text: String): ServerMessage {
            val bounded = flattenDeeperThan(text, MAX_FRAME_DEPTH)
            val root = try {
                TetherJson.parseToJsonElement(bounded) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return Unknown(null, reason = "not a JSON object")
            return parse(root)
        }

        /** Decode one already-parsed frame. Never throws. */
        fun parse(root: JsonObject): ServerMessage {
            val type = root.str("type") ?: return Unknown(null, root, "missing `type`")
            val decoder = ServerDecoders.decoders[type] ?: return Unknown(type, root, "unknown type")
            return try {
                decoder(Req(root))
            } catch (e: MalformedFrame) {
                Unknown(type, root, e.reason)
            } catch (e: Exception) {
                Unknown(type, root, "malformed: ${e.message}")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Nested server-side payload types
// ---------------------------------------------------------------------------

/**
 * search-results / global-search-results hit: `SearchHit extends HistorySession` (lib/protocol.ts
 * 1856-1862) — `snippet` is a trimmed context window around the first match, `matchCount` the
 * bounded number of occurrences. T5.3: the HistorySession fields ride along, because a hit is
 * resumed (v89 `profileId`) and merged into the sidebar as a history row (dashboard.tsx:874-889).
 */
@Serializable
data class SearchHit(
    val historyId: String,
    val provider: String = "",
    val name: String = "",
    val cwd: String = "",
    val updatedAt: Long = 0,
    val snippet: String = "",
    val matchCount: Int = 0,
    val createdAt: Long? = null,
    val lastSeenAt: Long? = null,
    val origin: String? = null,
    val spawnedBy: com.tether.app.protocol.model.HistorySpawnLink? = null,
    val profileId: String? = null,
    val digest: com.tether.app.protocol.model.HistoryDigest? = null,
) {
    /** The hit as the HistorySession it extends (what `resume` and the sidebar row read). */
    fun toHistory(): HistorySession = HistorySession(
        historyId = historyId,
        provider = provider,
        name = name,
        cwd = cwd,
        updatedAt = updatedAt,
        digest = digest,
        createdAt = createdAt,
        lastSeenAt = lastSeenAt,
        origin = origin,
        spawnedBy = spawnedBy,
        profileId = profileId,
    )
}

/**
 * TS ModelOption (the fields the composer uses). T7.2: [variants] (v41, the model's selectable
 * reasoning-effort levels, one provider-neutral field), [legacy] (v49, the "Legacy models" tier),
 * [providerLabel] (v51, the upstream provider tag) and [supportsFastMode] (v95, Claude only).
 */
@Serializable
data class SessionModelOption(
    val value: String,
    val displayName: String = "",
    val description: String? = null,
    val current: Boolean? = null,
    val resolvedModel: String? = null,
    val variants: List<ModelVariantOption>? = null,
    val legacy: Boolean? = null,
    val providerLabel: String? = null,
    val supportsFastMode: Boolean? = null,
)

/** TS ModelVariantOption: `value` is what the engine accepts, `label` the display string. */
@Serializable
data class ModelVariantOption(val value: String, val label: String = "")

/** TS SlashCommandInfo. */
@Serializable
data class SessionCommandOption(
    val name: String,
    val description: String? = null,
    val argumentHint: String? = null,
    val aliases: List<String>? = null,
    val supported: Boolean = false,
)

/**
 * TS LogEntry: one in-UI operational record (server.mjs recordEventLogEntry). Only the fields the
 * log dialog reads are kept, each decoded the way log-dialog.tsx tests it, so an odd extra never
 * drops the entry: [reason] / [message] only when a JSON string (`typeof === "string"`),
 * [durationMs] / [outstanding] only when a JSON number, [outcome] and [continuation] by JS
 * truthiness. [level] is null when absent or not a string; the dialog then counts the entry as a
 * warning, like the web's `level !== "info"`.
 */
data class LogEntry(
    val seq: Long,
    val ts: Long = 0,
    val level: String? = "info",
    val event: String = "",
    val sid: String? = null,
    val turnId: String? = null,
    val nativeId: String? = null,
    /** `String(entry.outcome)` when truthy (TS `TurnOutcome | null`). */
    val outcome: String? = null,
    val durationMs: Double? = null,
    val message: String? = null,
    val reason: String? = null,
    val outstanding: Double? = null,
    val continuation: Boolean = false,
) {
    companion object {
        /**
         * The entry, or null without a numeric `seq`: the web dedupes on `entry.seq > lastSeq`,
         * which is false for a missing seq, so such a record never shows there either.
         */
        fun fromJson(o: JsonObject): LogEntry? {
            val seq = o.long("seq") ?: return null
            return LogEntry(
                seq = seq,
                ts = o.long("ts") ?: 0,
                level = o.str("level"),
                event = o.str("event") ?: "",
                sid = o.str("sid"),
                turnId = o.str("turnId"),
                nativeId = o.str("nativeId"),
                outcome = (o["outcome"] as? JsonPrimitive)?.takeIf { jsTruthy(it) }?.content,
                durationMs = o.num("durationMs"),
                message = o.str("message"),
                reason = o.str("reason"),
                outstanding = o.num("outstanding"),
                continuation = jsTruthy(o["continuation"]),
            )
        }

        /** JS truthiness of a JSON value (absent = `undefined` = false; objects and arrays are truthy). */
        private fun jsTruthy(e: JsonElement?): Boolean = when (e) {
            null, JsonNull -> false
            is JsonPrimitive -> when {
                e.isString -> e.content.isNotEmpty()
                e.content == "true" -> true
                e.content == "false" -> false
                else -> e.content.toDoubleOrNull()?.let { it != 0.0 && !it.isNaN() } ?: false
            }
            else -> true
        }
    }
}

/** v109 multi-host peer. [status]: unknown|reachable|unreachable|unauthorized|identity_mismatch|skew|error. */
@Serializable
data class NodeSummary(
    val nodeId: String,
    val label: String = "",
    val baseUrl: String = "",
    val publicKey: String = "",
    val createdAt: Long = 0,
    val lastSeenAt: Long = 0,
    val status: String = "unknown",
    val peerVersion: String? = null,
    val peerProtocolVersion: Int? = null,
    val revokedAt: Long? = null,
)

/** v62 session-controls `modes` entry (opencode primary agents). */
@Serializable
data class ModeOption(
    val value: String,
    val label: String = "",
    val hint: String = "",
    val danger: Boolean? = null,
)

@Serializable
data class ClaudeCliVersion(val version: String)

/** TS ApprovalChoice. [permissionGrant]: "exact" | "subset". */
@Serializable
data class ApprovalChoice(
    val choiceId: String,
    val label: String = "",
    val description: String? = null,
    val permissionGrant: String? = null,
)

/** `metadata-draft-result.result`: the three TS variants. */
sealed interface MetadataDraft {
    data class CommitMessage(val text: String) : MetadataDraft

    data class PullRequest(val title: String, val body: String) : MetadataDraft

    data class Failure(val error: String) : MetadataDraft
}

// ---------------------------------------------------------------------------
// Decoders
// ---------------------------------------------------------------------------

/** kotlinx decode that returns null instead of throwing. */
internal fun <T> decodeOrNull(serializer: KSerializer<T>, element: JsonElement): T? =
    try {
        TetherJson.decodeFromJsonElement(serializer, element)
    } catch (_: Exception) {
        null
    }

private object ServerDecoders {
    /** Required array of modeled objects: malformed ELEMENTS are dropped. */
    private fun <T> Req.list(key: String, serializer: KSerializer<T>): List<T> =
        arr(key).mapNotNull { decodeOrNull(serializer, it) }

    /** Optional array of modeled objects (null when absent / not an array). */
    private fun <T> Req.optList(key: String, serializer: KSerializer<T>): List<T>? =
        o.arr(key)?.mapNotNull { decodeOrNull(serializer, it) }

    /** Required single modeled object: malformed -> MalformedFrame. */
    private fun <T> Req.model(key: String, serializer: KSerializer<T>): T =
        decodeOrNull(serializer, obj(key)) ?: throw MalformedFrame("`$key` does not match its model")

    private fun Req.int(key: String, default: Int): Int = o.long(key)?.toInt() ?: default

    private fun Req.boolMap(key: String): Map<String, Boolean> = buildMap {
        for ((k, v) in obj(key)) {
            val p = v as? JsonPrimitive ?: continue
            if (!p.isString) p.content.toBooleanStrictOrNull()?.let { put(k, it) }
        }
    }

    private fun Req.draft(key: String): MetadataDraft {
        val r = Req(obj(key))
        val ok = r.bool("ok")
        return when {
            !ok -> MetadataDraft.Failure(r.str("error"))
            r.o.str("text") != null -> MetadataDraft.CommitMessage(r.str("text"))
            else -> MetadataDraft.PullRequest(r.str("title"), r.str("body"))
        }
    }

    val decoders: Map<String, (Req) -> ServerMessage> = linkedMapOf(
        "ready" to { r ->
            ServerMessage.Ready(
                protocolVersion = r.int("protocolVersion"),
                sessions = r.list("sessions", AgentSession.serializer()),
                providers = r.list("providers", ProviderInfo.serializer()),
                workspaceRoot = r.o.str("workspaceRoot"),
                nativeProtocolFloor = r.o.long("nativeProtocolFloor")?.toInt(),
                hiddenAgentSessionCount = r.o.nonNegLong("hiddenAgentSessionCount")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
            )
        },
        "pong" to { r -> ServerMessage.Pong(r.o.str("nonce")) },
        "log" to { r ->
            val boot = r.o["bootId"] as? JsonPrimitive ?: throw MalformedFrame("required field `bootId` is missing")
            ServerMessage.Log(r.objList("entries").mapNotNull(LogEntry::fromJson), boot.content)
        },
        "version_mismatch" to { r ->
            ServerMessage.VersionMismatch(
                requiredVersion = r.int("requiredVersion", -1),
                message = r.o.str("message"),
                nativeProtocolFloor = r.o.long("nativeProtocolFloor")?.toInt(),
                serverProtocolVersion = r.o.long("serverProtocolVersion")?.toInt(),
                reason = r.o.str("reason"),
            )
        },
        "nodes" to { r -> ServerMessage.Nodes(r.list("nodes", NodeSummary.serializer())) },
        "node-result" to { r ->
            ServerMessage.NodeResult(r.bool("ok"), r.o.str("nodeId"), r.o.str("message"), r.o.str("requestId"))
        },
        "created" to { r -> ServerMessage.Created(r.model("session", AgentSession.serializer()), r.o.str("requestId")) },
        "session" to { r -> ServerMessage.SessionUpdate(r.model("session", AgentSession.serializer())) },
        "histories" to { r ->
            ServerMessage.Histories(r.str("cwd"), r.list("sessions", HistorySession.serializer()), r.o.str("requestId"))
        },
        "session-order" to { r -> ServerMessage.SessionOrder(r.str("cwd"), r.strList("order")) },
        "scheduled-actions" to { r -> ServerMessage.ScheduledActions(r.objList("schedules"), r.objList("continuations")) },
        "seen" to { r -> ServerMessage.Seen(r.str("historyId"), r.long("seenAt")) },
        "search-results" to { r ->
            ServerMessage.SearchResults(r.str("cwd"), r.str("query"), r.list("hits", SearchHit.serializer()))
        },
        "global-search-results" to { r ->
            ServerMessage.GlobalSearchResults(r.long("requestId"), r.str("query"), r.list("hits", SearchHit.serializer()))
        },
        "directories" to { r ->
            ServerMessage.Directories(r.model("listing", DirectoryListing.serializer()), r.o.str("requestId"))
        },
        "event" to { r -> ServerMessage.Event(r.str("sessionId"), AgentEvent.parse(r.obj("event"))) },
        "snapshot" to { r ->
            ServerMessage.Snapshot(
                sessionId = r.str("sessionId"),
                throughSeq = r.long("throughSeq"),
                state = r.o.obj("state")?.let { JsCodec.fromJson(it) as JsObj },
                reset = r.o.boolTrue("reset"),
                trimmedBefore = r.o.long("trimmedBefore")?.toInt(),
                events = r.o.objList("events")?.map(AgentEvent::parse),
            )
        },
        "turns-detail" to { r ->
            ServerMessage.TurnsDetail(r.str("sessionId"), r.int("fromIndex"), r.int("toIndex"), JsCodec.fromJson(r.obj("turns")) as JsObj)
        },
        "approval" to { r ->
            ServerMessage.Approval(
                sessionId = r.str("sessionId"),
                requestId = r.str("requestId"),
                toolId = r.str("toolId"),
                name = r.str("name"),
                input = r.o["input"],
                choices = r.optList("choices", ApprovalChoice.serializer()),
                metadata = r.o.obj("metadata"),
            )
        },
        "approval_resolved" to { r ->
            ServerMessage.ApprovalResolved(r.str("sessionId"), r.str("requestId"), r.str("choiceId"))
        },
        "interrupt_result" to { r ->
            ServerMessage.InterruptResult(
                sessionId = r.str("sessionId"),
                turnId = r.o.str("turnId"),
                status = r.str("status"),
                error = r.o.str("error"),
                stillQueued = r.o.strList("stillQueued"),
            )
        },
        "session-controls" to { r ->
            ServerMessage.SessionControls(
                sessionId = r.str("sessionId"),
                models = r.list("models", SessionModelOption.serializer()),
                commands = r.list("commands", SessionCommandOption.serializer()),
                model = r.o.str("model"),
                reasoningEffort = r.o.str("reasoningEffort"),
                defaultModel = r.o.str("defaultModel"),
                defaultReasoningEffort = r.o.str("defaultReasoningEffort"),
                modes = r.optList("modes", ModeOption.serializer()),
                fastModeState = r.o.str("fastModeState"),
                fastModeDisabledReason = r.o.str("fastModeDisabledReason"),
            )
        },
        "codex-controls" to { r -> ServerMessage.CodexControls(r.str("sessionId"), r.obj("snapshot")) },
        "codex-control-result" to { r ->
            ServerMessage.CodexControlResult(r.str("sessionId"), r.bool("ok"), r.str("message"))
        },
        "opencode-controls" to { r -> ServerMessage.OpencodeControls(r.str("sessionId"), r.obj("snapshot")) },
        "opencode-control-result" to { r ->
            ServerMessage.OpencodeControlResult(r.str("sessionId"), r.bool("ok"), r.str("message"))
        },
        // ta-t7l: both settings frames decode tolerantly: a missing or wrongly typed part is its empty
        // value (the web reads them with `?.` / `?? ""` throughout), never a dropped frame.
        "advanced-settings" to { r ->
            ServerMessage.AdvancedSettings(
                claudeCliVersion = r.o.str("claudeCliVersion"),
                discovered = r.optList("discovered", ClaudeCliVersion.serializer()).orEmpty(),
                envForced = r.o.boolOrNull("envForced") == true,
                envPath = r.o.str("envPath"),
                effectiveSource = r.o.str("effectiveSource").orEmpty(),
                effectiveVersion = r.o.str("effectiveVersion"),
            )
        },
        "server-settings" to { r ->
            ServerMessage.ServerSettings(
                settings = r.o.obj("settings") ?: JsonObject(emptyMap()),
                envForced = if (r.o.obj("envForced") != null) r.boolMap("envForced") else emptyMap(),
                restartRequired = r.o.boolOrNull("restartRequired") == true,
                discovered = r.optList("discovered", ClaudeCliVersion.serializer()).orEmpty(),
                detected = r.o.obj("detected") ?: JsonObject(emptyMap()),
            )
        },
        // ta-q6p: both carry plaintext env values, so neither ever becomes an Unknown holding its raw frame.
        "acp-agents" to { r -> ServerMessage.AcpAgents(r.o.objList("agents").orEmpty()) },
        "providers" to { r ->
            val raw = r.o.arr("profiles")
            val profiles = raw?.mapNotNull { it as? JsonObject }.orEmpty()
            ServerMessage.Providers(profiles, intact = raw != null && profiles.size == raw.size)
        },
        "providers-snapshot" to { r -> ServerMessage.ProvidersSnapshot(r.objList("entries")) },
        "metadata-draft-result" to { r -> ServerMessage.MetadataDraftResult(r.str("requestId"), r.draft("result")) },
        "metadata-draft-error" to { r -> ServerMessage.MetadataDraftError(r.str("requestId"), r.str("error")) },
        "worktree-source" to { r -> ServerMessage.WorktreeSource(r.obj("info"), r.o.str("requestId")) },
        "worktree-scripts" to { r -> ServerMessage.WorktreeScripts(r.obj("snapshot")) },
        "worktree-logs" to { r ->
            ServerMessage.WorktreeLogs(r.str("sessionId"), r.str("name"), r.strList("lines"), r.int("dropped", 0))
        },
        "worktree-diff" to { r -> ServerMessage.WorktreeDiff(r.str("sessionId"), r.objOrNull("diff")) },
        "git-diff-file" to { r ->
            ServerMessage.GitDiffFile(
                sessionId = r.str("sessionId"),
                path = r.str("path"),
                hunks = r.str("hunks"),
                truncated = r.bool("truncated"),
                binary = r.bool("binary"),
                error = r.o.str("error"),
            )
        },
        "change-request" to { r ->
            ServerMessage.ChangeRequest(r.str("sessionId"), r.objOrNull("changeRequest"), r.bool("unknown"))
        },
        "handoff-brief" to { r ->
            ServerMessage.HandoffBrief(
                sourceId = r.str("sourceId"),
                brief = r.obj("brief"),
                markdown = r.str("markdown"),
                instruction = r.str("instruction"),
                requestId = r.o.str("requestId"),
            )
        },
        "error" to { r -> ServerMessage.ErrorFrame(r.str("message"), r.o.str("requestId")) },
        "overview-snapshot" to { r ->
            ServerMessage.OverviewSnapshot(
                feedId = r.str("feedId"),
                cursor = r.long("cursor"),
                generatedAt = r.o.long("generatedAt") ?: 0,
                activitySince = r.o.long("activitySince") ?: 0,
                filters = r.optModel("filters", OverviewNormalizedFilters.serializer()) ?: OverviewNormalizedFilters(),
                page = r.int("page", 0),
                pageSize = r.int("pageSize", 0),
                pageCount = r.int("pageCount", 0),
                totalCards = r.int("totalCards", 0),
                counts = r.optModel("counts", OverviewCounts.serializer()) ?: OverviewCounts(),
                facets = r.optModel("facets", OverviewFacets.serializer()) ?: OverviewFacets(),
                cards = r.optList("cards", OverviewCard.serializer()).orEmpty(),
                pending = r.optModel("pending", OverviewPendingPanel.serializer()) ?: OverviewPendingPanel(),
                activity = r.optList("activity", OverviewActivity.serializer()).orEmpty(),
            )
        },
        "overview-delta" to { r ->
            ServerMessage.OverviewDelta(
                feedId = r.str("feedId"),
                cursor = r.long("cursor"),
                prevCursor = r.long("prevCursor"),
                upserts = r.optList("upserts", OverviewCard.serializer()).orEmpty(),
                removals = r.o.strList("removals").orEmpty(),
                counts = r.optModel("counts", OverviewCounts.serializer()) ?: OverviewCounts(),
                pending = r.optModel("pending", OverviewPendingPanel.serializer()) ?: OverviewPendingPanel(),
                activity = r.optList("activity", OverviewActivity.serializer()).orEmpty(),
            )
        },
    )

    /** Optional single modeled object: absent or malformed -> null (the caller defaults it). */
    private fun <T> Req.optModel(key: String, serializer: KSerializer<T>): T? = o.obj(key)?.let { decodeOrNull(serializer, it) }
}
