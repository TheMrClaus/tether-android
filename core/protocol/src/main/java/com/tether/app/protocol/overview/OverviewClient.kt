package com.tether.app.protocol.overview

import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.model.OverviewActivity
import com.tether.app.protocol.model.OverviewCard
import com.tether.app.protocol.model.OverviewCounts
import com.tether.app.protocol.model.OverviewFacets
import com.tether.app.protocol.model.OverviewFilters
import com.tether.app.protocol.model.OverviewNormalizedFilters
import com.tether.app.protocol.model.OverviewPending
import com.tether.app.protocol.model.OverviewPendingPanel

/*
 * T15.1: faithful port of the browser's pure half of the v131 Overview feed, lib/overview-client.mjs
 * (OVERVIEW_STUDIO_PLAN.md §4/§5), plus the client-side bounds of lib/overview-model.mjs
 * (OVERVIEW_LIMITS, normalizePaging, the status-filter set) the subscription is built with.
 *
 * Pure: no I/O, no clock reads (callers pass `now`), inputs are never mutated. The server's
 * `overview-snapshot` / `overview-delta` frames fold here into one [OverviewClientState].
 *
 * CLIENT RULE (lib/protocol.ts, the Overview block): keep `feedId` and the last `cursor`. A delta
 * whose `prevCursor` is not our cursor, or whose `feedId` differs, means local state is out of
 * step: re-subscribe and replace everything from the next snapshot, ignoring deltas until it
 * lands. A snapshot always replaces all local overview state.
 */

/** overview-client.mjs `phase`. */
enum class OverviewPhase {
    /** Not subscribed (Overview hidden). Frames are ignored. */
    Idle,

    /** Subscribed, no snapshot ever received. */
    Loading,

    /** Subscribed, holding older data while a fresh snapshot is due. */
    Resyncing,

    /** In step with the server. */
    Live,

    /** The socket dropped; `data` is the last known (stale) state. */
    Offline,
}

/** overview-client.mjs `snapshotData(message)`: everything a snapshot carries but the step identity. */
data class OverviewData(
    val filters: OverviewNormalizedFilters = OverviewNormalizedFilters(),
    val page: Int = 0,
    val pageSize: Int = 0,
    val pageCount: Int = 0,
    val totalCards: Int = 0,
    val counts: OverviewCounts = OverviewCounts(),
    val facets: OverviewFacets = OverviewFacets(),
    val cards: List<OverviewCard> = emptyList(),
    val pending: OverviewPendingPanel = OverviewPendingPanel(),
    val activitySince: Long = 0,
    val generatedAt: Long = 0,
)

/** overview-client.mjs `initialOverviewState()` and its successors. */
data class OverviewClientState(
    val phase: OverviewPhase = OverviewPhase.Idle,
    val feedId: String? = null,
    val cursor: Long? = null,
    val awaitingSnapshot: Boolean = true,
    val data: OverviewData? = null,
    val activity: List<OverviewActivity> = emptyList(),
    val updatedAt: Long? = null,
)

/** What [OverviewClient.applyFrame] returns: `resubscribe` asks the caller to re-send `overview-subscribe`. */
data class OverviewFold(val state: OverviewClientState, val resubscribe: Boolean)

/**
 * use-tether.ts `OverviewSubscription` (`{ filters?, page?, pageSize? }`), the one thing the
 * client remembers so a reconnect re-subscribes. Build it with [OverviewClient.subscription] so
 * the page and filters are bounded as the server bounds them.
 */
data class OverviewSubscription(
    val filters: OverviewFilters? = null,
    val page: Int? = null,
    val pageSize: Int? = null,
)

object OverviewClient {

    /** overview-client.mjs:16 — the visible activity tail; the server sends at most this many too. */
    const val ACTIVITY_CAP = 50

    /** overview-client.mjs:19 — the statuses the server applies when the filter names none. */
    val DEFAULT_STATUSES: List<String> = listOf("running", "waiting", "attention")

    /** overview-model.mjs:33 — every status a filter may name, in canonical order. */
    val STATUS_FILTERS: List<String> = listOf("running", "waiting", "attention", "ready")

    /** overview-model.mjs:14-31 OVERVIEW_LIMITS, the ones a client subscription is bounded by. */
    const val PAGE_SIZE_DEFAULT = 24
    const val PAGE_SIZE_MAX = 48
    const val MAX_PAGE = 10_000
    const val FILTER_ENTRIES = 64
    const val FILTER_CHARS = 4096

    /** The Overview page's own size (components/overview/overview.tsx:23 OVERVIEW_PAGE_SIZE). */
    const val PAGE_SIZE = 24

    /** overview-client.mjs:29 initialOverviewState. */
    fun initial(): OverviewClientState = OverviewClientState()

    /** overview-client.mjs:42 — a subscribe was just sent: deltas are ignored until its snapshot arrives. */
    fun requested(state: OverviewClientState): OverviewClientState =
        state.copy(awaitingSnapshot = true, phase = if (state.data != null) OverviewPhase.Resyncing else OverviewPhase.Loading)

    /** overview-client.mjs:47 — the Overview stopped being visible and we unsubscribed. Data is kept for a quick return. */
    fun unsubscribed(state: OverviewClientState): OverviewClientState =
        state.copy(awaitingSnapshot = true, phase = OverviewPhase.Idle)

    /** overview-client.mjs:52 — the socket closed. Data stays on screen, marked stale by the phase. */
    fun disconnected(state: OverviewClientState): OverviewClientState =
        if (state.phase == OverviewPhase.Idle) state.copy(awaitingSnapshot = true)
        else state.copy(awaitingSnapshot = true, phase = OverviewPhase.Offline)

    /** overview-client.mjs:58 — Σ counts over the statuses being paged (a delta carries counts, not totalCards). */
    fun totalCards(counts: OverviewCounts?, statuses: List<String>?): Int {
        if (counts == null) return 0
        val list = if (!statuses.isNullOrEmpty()) statuses else DEFAULT_STATUSES
        var total = 0
        for (status in LinkedHashSet(list)) {
            total += when (status) {
                "running" -> counts.running
                "waiting" -> counts.waiting
                "attention" -> counts.attention
                "ready" -> counts.ready
                "workspaces" -> counts.workspaces
                "total" -> counts.total
                else -> 0
            }
        }
        return total
    }

    /**
     * overview-client.mjs:74 — merge activity records newest first, deduped by their `id`
     * (`${nodeId}:${sessionId}:${seq}:${kind}`), capped. `incoming` wins a tie on position because
     * it is the newer frame; order among equal timestamps is kept (a stable sort).
     */
    fun mergeActivity(
        existing: List<OverviewActivity>?,
        incoming: List<OverviewActivity>?,
        cap: Int = ACTIVITY_CAP,
    ): List<OverviewActivity> {
        val seen = HashSet<String>()
        val merged = ArrayList<OverviewActivity>()
        for (record in incoming.orEmpty() + existing.orEmpty()) {
            if (!seen.add(record.id)) continue
            merged.add(record)
        }
        // sortedByDescending is stable: equal timestamps keep their merged order.
        return merged.sortedByDescending { it.ts }.take(maxOf(0, cap))
    }

    private fun snapshotData(message: ServerMessage.OverviewSnapshot) = OverviewData(
        filters = message.filters,
        page = message.page,
        pageSize = message.pageSize,
        pageCount = message.pageCount,
        totalCards = message.totalCards,
        counts = message.counts,
        facets = message.facets,
        cards = message.cards,
        pending = message.pending,
        activitySince = message.activitySince,
        generatedAt = message.generatedAt,
    )

    private fun outOfStep(state: OverviewClientState) = OverviewFold(requested(state), resubscribe = true)

    /**
     * overview-client.mjs:112 applyOverviewFrame — fold one server frame. Frames other than
     * overview-snapshot / overview-delta return the state unchanged.
     */
    fun applyFrame(state: OverviewClientState, message: ServerMessage, now: Long? = null): OverviewFold {
        if (message !is ServerMessage.OverviewSnapshot && message !is ServerMessage.OverviewDelta) return OverviewFold(state, false)
        // Unsubscribed: a frame still in flight from before the unsubscribe.
        if (state.phase == OverviewPhase.Idle) return OverviewFold(state, false)

        if (message is ServerMessage.OverviewSnapshot) {
            return OverviewFold(
                state.copy(
                    phase = OverviewPhase.Live,
                    feedId = message.feedId,
                    cursor = message.cursor,
                    awaitingSnapshot = false,
                    data = snapshotData(message),
                    activity = mergeActivity(emptyList(), message.activity),
                    updatedAt = now,
                ),
                resubscribe = false,
            )
        }

        message as ServerMessage.OverviewDelta
        // Waiting on a snapshot we already asked for: anything before it is noise, and asking
        // again would only queue another snapshot.
        val data = state.data
        if (state.awaitingSnapshot || data == null) return OverviewFold(state, false)
        if (message.feedId != state.feedId || message.prevCursor != state.cursor) return outOfStep(state)

        val byId = LinkedHashMap<String, OverviewCard>()
        for (card in message.upserts) byId[card.sessionId] = card
        val onPage = data.cards.mapTo(HashSet()) { it.sessionId }
        // Upserts only ever name cards already on this page (the server snapshots on any
        // membership change). An unknown id means we are out of step.
        for (id in byId.keys) if (id !in onPage) return outOfStep(state)
        val removed = message.removals.toHashSet()
        val cards = data.cards.filter { it.sessionId !in removed }.map { byId[it.sessionId] ?: it }

        return OverviewFold(
            state.copy(
                phase = OverviewPhase.Live,
                cursor = message.cursor,
                data = data.copy(
                    cards = cards,
                    counts = message.counts,
                    totalCards = totalCards(message.counts, data.filters.statuses),
                    pending = message.pending,
                ),
                activity = mergeActivity(state.activity, message.activity),
                updatedAt = now,
            ),
            resubscribe = false,
        )
    }

    /**
     * overview-client.mjs:175 — a display order that does not move cards under a focused operator:
     * ids already on screen keep their place (while still present), new cards append in server
     * order. With no frozen order the server's attention-first order stands.
     */
    fun stableCardOrder(frozenIds: List<String>?, cards: List<OverviewCard>?): List<OverviewCard> {
        val list = cards.orEmpty()
        if (frozenIds.isNullOrEmpty()) return list
        val byId = HashMap<String, OverviewCard>()
        for (card in list) byId.putIfAbsent(card.sessionId, card)
        val ordered = ArrayList<OverviewCard>()
        val placed = HashSet<String>()
        for (id in frozenIds) {
            val card = byId[id]
            if (card != null && placed.add(id)) ordered.add(card)
        }
        for (card in list) if (card.sessionId !in placed) ordered.add(card)
        return ordered
    }

    /** overview-client.mjs:193 — a request id is unique within its session only. */
    fun pendingKey(sessionId: String?, requestId: String?): String = "${sessionId.orEmpty()}\u0000${requestId.orEmpty()}"

    fun pendingKey(item: OverviewPending): String = pendingKey(item.sessionId, item.requestId)

    /**
     * overview-client.mjs:202 — requests present now that were not in [previousKeys].
     * `previousKeys == null` (the first snapshot) announces nothing: only requests that ARRIVE
     * while the operator watches are news.
     */
    fun newPendingItems(previousKeys: Set<String>?, items: List<OverviewPending>?): List<OverviewPending> {
        if (previousKeys == null) return emptyList()
        return items.orEmpty().filter { pendingKey(it) !in previousKeys }
    }

    /**
     * overview-client.mjs:211 — whether a request is still pending according to the overview
     * data: the pending panel, or the card's own pending refs (the panel is capped at 20).
     */
    fun isRequestPending(data: OverviewData?, sessionId: String, requestId: String): Boolean {
        if (data == null) return false
        if (data.pending.items.any { it.sessionId == sessionId && it.requestId == requestId }) return true
        return data.cards.any { card -> card.sessionId == sessionId && card.pending.any { it.requestId == requestId } }
    }

    /**
     * overview-model.mjs:467 normalizePaging: a page size in 1..[PAGE_SIZE_MAX] (else the
     * default) and a page in 0..[MAX_PAGE] (else 0).
     */
    fun normalizePaging(page: Int?, pageSize: Int?): Pair<Int, Int> {
        val size = if (pageSize != null && pageSize > 0) minOf(pageSize, PAGE_SIZE_MAX) else PAGE_SIZE_DEFAULT
        val index = if (page != null && page > 0) minOf(page, MAX_PAGE) else 0
        return index to size
    }

    /**
     * overview-model.mjs:452 normalizeFilters, as the subscription carries it: distinct non-empty
     * strings, sorted, capped at [FILTER_ENTRIES]; statuses restricted to [STATUS_FILTERS] in
     * canonical order. Unlike the server, an empty status list stays absent (the server then
     * applies [DEFAULT_STATUSES]), so the frame is exactly what the web sends.
     */
    fun normalizeFilters(filters: OverviewFilters?): OverviewFilters? {
        if (filters == null) return null
        fun uniq(values: List<String>?): List<String>? {
            values ?: return null
            val list = ArrayList<String>()
            for (value in values) if (value.isNotEmpty() && value.length <= FILTER_CHARS && value !in list) list.add(value)
            return list.sorted().take(FILTER_ENTRIES)
        }
        val statuses = filters.statuses?.let { requested -> STATUS_FILTERS.filter { it in requested } }
        return OverviewFilters(
            workspaces = uniq(filters.workspaces)?.takeIf { it.isNotEmpty() },
            providers = uniq(filters.providers)?.takeIf { it.isNotEmpty() },
            statuses = statuses?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * overview.tsx:64 subscriptionFor, bounded: one workspace / provider (or none), the status
     * tab's statuses (absent = the operational default), the page, and the Overview's page size.
     */
    fun subscription(
        workspace: String?,
        provider: String?,
        statuses: List<String>?,
        page: Int,
        pageSize: Int = PAGE_SIZE,
    ): OverviewSubscription {
        val (index, size) = normalizePaging(page, pageSize)
        val filters = normalizeFilters(
            OverviewFilters(
                workspaces = workspace?.takeIf { it.isNotEmpty() }?.let(::listOf),
                providers = provider?.takeIf { it.isNotEmpty() }?.let(::listOf),
                statuses = statuses?.takeIf { it.isNotEmpty() },
            ),
        ) ?: OverviewFilters()
        return OverviewSubscription(filters = filters, page = index, pageSize = size)
    }
}
