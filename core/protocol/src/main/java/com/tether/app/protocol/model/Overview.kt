package com.tether.app.protocol.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * v131/v132 Overview feed (tether lib/protocol.ts @ 79c3d37, OVERVIEW_STUDIO_PLAN.md §5): an
 * OPT-IN, bounded, read-only feed on the existing socket. The server sends `overview-snapshot` /
 * `overview-delta` ONLY to a socket that sent `overview-subscribe`, so today's app (which never
 * subscribes) never receives them. The types exist so the frames decode (and a later Overview
 * surface task can opt in); no screen reads them yet.
 *
 * Decoded through [com.tether.app.protocol.TetherJson] (unknown keys ignored, absent = default).
 * Identity fields are required; everything else defaults, so a newer server's extra or missing
 * field never drops a card. A malformed element of a list is dropped by the frame decoder.
 * Statuses and kinds are kept verbatim (forward-compatible strings).
 *
 * CLIENT RULE (lib/protocol.ts): keep `feedId` and the last `cursor`; a delta whose `prevCursor`
 * differs from it, or whose `feedId` differs, means re-subscribe and replace everything from the
 * fresh snapshot. A snapshot always replaces all local overview state.
 */

/** `overview-subscribe.filters`. Absent/empty `statuses` = the server's operational set. */
data class OverviewFilters(
    val workspaces: List<String>? = null,
    val providers: List<String>? = null,
    /** "running" | "waiting" | "attention" | "ready". */
    val statuses: List<String>? = null,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        workspaces?.let { put("workspaces", JsonArray(it.map(::JsonPrimitive))) }
        providers?.let { put("providers", JsonArray(it.map(::JsonPrimitive))) }
        statuses?.let { put("statuses", JsonArray(it.map(::JsonPrimitive))) }
    }
}

/** The filters as the server echoes them: deduped, sorted, statuses defaulted. */
@Serializable
data class OverviewNormalizedFilters(
    val workspaces: List<String> = emptyList(),
    val providers: List<String> = emptyList(),
    val statuses: List<String> = emptyList(),
)

/** A pending approval/question (lib/protocol.ts OverviewPending / OverviewPendingRef). */
@Serializable
data class OverviewPending(
    val sessionId: String,
    val requestId: String,
    /** "approval" | "question". */
    val kind: String = "",
    /** Journal-stamped creation time; absent for a request journaled before v131. */
    val createdAt: Long? = null,
    val title: String = "",
    val provider: String = "",
    val profileId: String? = null,
    val summary: String = "",
    val detail: String? = null,
)

@Serializable
data class OverviewWorkspace(val key: String = "", val label: String = "")

@Serializable
data class OverviewExcerpt(
    /** "assistant" | "tool" | "task" | "request" | "error". */
    val kind: String = "",
    val text: String = "",
)

@Serializable
data class OverviewProgress(val done: Int = 0, val total: Int = 0, val label: String? = null)

@Serializable
data class OverviewAttention(
    /** "approval" | "question" | "rate_limit" | "auth" | "outcome_unknown" | "interrupted" | "error". */
    val kind: String = "",
    val label: String = "",
    val since: Long? = null,
)

@Serializable
data class OverviewCard(
    val sessionId: String,
    val nodeId: String = "",
    val seq: Long = 0,
    val title: String = "",
    val provider: String = "",
    val profileId: String? = null,
    val providerLabel: String = "",
    val workspace: OverviewWorkspace = OverviewWorkspace(),
    val cwd: String = "",
    val branch: String? = null,
    /** "running" | "waiting" | "attention" | "ready" | "ended". */
    val status: String = "",
    val statusSince: Long? = null,
    val turnStartedAt: Long? = null,
    val startedAt: Long? = null,
    val lastActivityAt: Long? = null,
    val excerpt: OverviewExcerpt? = null,
    val progress: OverviewProgress? = null,
    val attention: OverviewAttention? = null,
    /** Up to 5 of this session's pending requests, oldest first. */
    val pending: List<OverviewPending> = emptyList(),
    val parentSessionId: String? = null,
    val spawnedRunsActive: Int? = null,
)

/** A Recent activity row. [id] = `${nodeId}:${sessionId}:${seq}:${kind}`, the dedupe identity. */
@Serializable
data class OverviewActivity(
    val id: String,
    val ts: Long = 0,
    val sessionId: String = "",
    val nodeId: String = "",
    val seq: Long = 0,
    val title: String = "",
    val provider: String = "",
    val profileId: String? = null,
    /** v132: the workspace label (basename of the session's workspace root); absent before v132. */
    val workspace: String? = null,
    /** "request" | "turn_completed" | "tool_started" | "tool_result" | "failure". */
    val kind: String = "",
    val text: String = "",
)

@Serializable
data class OverviewCounts(
    val running: Int = 0,
    val waiting: Int = 0,
    val attention: Int = 0,
    val ready: Int = 0,
    val workspaces: Int = 0,
    val total: Int = 0,
)

@Serializable
data class OverviewWorkspaceFacet(val key: String = "", val label: String = "", val count: Int = 0)

@Serializable
data class OverviewProviderFacet(
    val key: String = "",
    val provider: String = "",
    val profileId: String? = null,
    val label: String = "",
    val count: Int = 0,
)

@Serializable
data class OverviewFacets(
    val workspaces: List<OverviewWorkspaceFacet> = emptyList(),
    val providers: List<OverviewProviderFacet> = emptyList(),
)

/** Scoped by workspace/provider (not status): <= 20 items oldest first. */
@Serializable
data class OverviewPendingPanel(
    val items: List<OverviewPending> = emptyList(),
    val total: Int = 0,
    val outsideFilters: Int = 0,
)
