package com.tether.app.ui.overview

import com.tether.app.client.ConsentGuard
import com.tether.app.protocol.model.OverviewCard
import com.tether.app.protocol.model.OverviewCounts
import com.tether.app.protocol.model.OverviewFacets
import com.tether.app.protocol.model.OverviewPending
import com.tether.app.protocol.overview.OverviewClient
import com.tether.app.protocol.overview.OverviewClientState
import com.tether.app.protocol.overview.OverviewPhase
import com.tether.app.protocol.overview.OverviewSubscription
import com.tether.app.protocol.tree.JsObj

/**
 * T15.2: the Overview's pure presentation rules — components/overview/overview.tsx (status tabs,
 * the subscription, counts, empty states, the arrival announcement), overview-card.tsx (the
 * status label / tone / clock of a card) and dashboard.tsx `reviewRequest` (is the request still
 * pending once its session is on screen). No Compose, no clock reads: unit-tested on the JVM.
 */

/** overview.tsx:26-33 STATUS_TABS; `statuses == null` = the server's operational default. */
enum class StatusTab(val key: String, val label: String, val statuses: List<String>?) {
    Active("active", "Active", null),
    Waiting("waiting", "Waiting", listOf("waiting")),
    Running("running", "Running", listOf("running")),
    Attention("attention", "Needs attention", listOf("attention")),
    Ready("ready", "Ready", listOf("ready")),
}

/** overview.tsx:35-40 OverviewFilterChoice. */
data class OverviewChoice(
    val workspace: String? = null,
    val provider: String? = null,
    val status: StatusTab = StatusTab.Active,
) {
    /** overview.tsx:130 — a workspace or provider scope is in force. */
    val filtered: Boolean get() = workspace != null || provider != null
}

/** Each status carries words and a glyph of its own; colour only reinforces (overview-card.tsx:9). */
enum class CardTone { Running, Waiting, Attention, Danger, Ready, Ended }

enum class StatusGlyph { Running, Waiting, RateLimit, Auth, OutcomeUnknown, Interrupted, Failed, NeedsAttention, Ready, Ended }

data class CardStatus(val label: String, val tone: CardTone, val glyph: StatusGlyph, val detail: String? = null)

/** The Overview hand-offs: the Dashboard's existing handlers (overview.tsx:77-95 props). */
data class OverviewActions(
    val onOpenSession: (String) -> Unit = {},
    val onReviewRequest: (sessionId: String, requestId: String) -> Unit = { _, _ -> },
    val onNewSession: () -> Unit = {},
    val onOpenEventLog: () -> Unit = {},
)

object OverviewPresentation {

    /** overview.tsx:64 subscriptionFor, bounded (T15.1 [OverviewClient.subscription]). */
    fun subscriptionFor(choice: OverviewChoice, page: Int): OverviewSubscription =
        OverviewClient.subscription(choice.workspace, choice.provider, choice.status.statuses, page)

    /** overview-card.tsx:10-18 ATTENTION. */
    private fun attention(kind: String?): Pair<String, StatusGlyph>? = when (kind) {
        "approval", "question" -> "Waiting for you" to StatusGlyph.Waiting
        "rate_limit" -> "Rate limited" to StatusGlyph.RateLimit
        "auth" -> "Signed out" to StatusGlyph.Auth
        "outcome_unknown" -> "Outcome unknown" to StatusGlyph.OutcomeUnknown
        "interrupted" -> "Interrupted" to StatusGlyph.Interrupted
        "error" -> "Turn failed" to StatusGlyph.Failed
        else -> null
    }

    /** overview-card.tsx:20 cardStatus. */
    fun cardStatus(card: OverviewCard): CardStatus = when (card.status) {
        "running" -> CardStatus("Running", CardTone.Running, StatusGlyph.Running)
        "waiting" -> CardStatus("Waiting for you", CardTone.Waiting, StatusGlyph.Waiting, card.attention?.label)
        "attention" -> {
            val meta = attention(card.attention?.kind)
            CardStatus(
                label = meta?.first ?: "Needs attention",
                tone = if (card.attention?.kind == "error") CardTone.Danger else CardTone.Attention,
                glyph = meta?.second ?: StatusGlyph.NeedsAttention,
                detail = card.attention?.label,
            )
        }
        "ready" -> CardStatus("Ready", CardTone.Ready, StatusGlyph.Ready)
        else -> CardStatus("Ended", CardTone.Ended, StatusGlyph.Ended)
    }

    /** overview-card.tsx:44 — when the CURRENT state began (running: the open turn; waiting: time waiting). */
    fun stateSince(card: OverviewCard): Long? =
        if (card.status == "running") card.turnStartedAt ?: card.statusSince else card.statusSince

    /** overview-card.tsx:49. */
    fun durationVerb(card: OverviewCard): String = when (card.status) {
        "running" -> "working for"
        "waiting" -> "waiting for"
        else -> "in this state for"
    }

    /** overview-card.tsx:65 — only a waiting card reviews; its oldest pending request. */
    fun reviewTarget(card: OverviewCard): OverviewPending? = if (card.status == "waiting") card.pending.firstOrNull() else null

    /** overview-card.tsx:69 — the excerpt repeats the status detail only when it adds something. */
    fun shownExcerpt(card: OverviewCard, status: CardStatus = cardStatus(card)) =
        card.excerpt?.takeIf { it.text.isNotEmpty() && it.text != status.detail }

    /** overview.tsx:178 tabCount; null before the first snapshot. */
    fun tabCount(counts: OverviewCounts?, tab: StatusTab): Int? {
        counts ?: return null
        return when (tab) {
            StatusTab.Active -> counts.running + counts.waiting + counts.attention
            StatusTab.Waiting -> counts.waiting
            StatusTab.Running -> counts.running
            StatusTab.Attention -> counts.attention
            StatusTab.Ready -> counts.ready
        }
    }

    /** overview.tsx:184 — not live (the link, or the feed) reads as offline once data is shown. */
    fun offline(state: OverviewClientState, connected: Boolean): Boolean = !connected || state.phase == OverviewPhase.Offline

    /** overview.tsx:185. */
    fun live(state: OverviewClientState, connected: Boolean): Boolean = state.phase == OverviewPhase.Live && connected

    /** overview.tsx:186 — the node has no managed session at all (not just none in this filter). */
    fun noSessionsAtAll(facets: OverviewFacets?): Boolean = facets != null && facets.workspaces.isEmpty()

    /** overview.tsx:198-209 — the counts line; before data, what we wait for. */
    fun countsLine(counts: OverviewCounts?, offline: Boolean): List<String> =
        if (counts == null) {
            listOf(if (offline) "Waiting for the secure link…" else "Loading sessions…")
        } else {
            listOf("${counts.running} running", "${counts.waiting} waiting for you", OverviewFormat.plural(counts.workspaces, "workspace"))
        }

    /** overview.tsx:291-302 — an empty filtered page: its heading, hint and the ways out. */
    data class EmptyPage(val title: String, val hint: String, val showAll: Boolean, val showReady: Boolean, val showActive: Boolean)

    fun emptyPage(choice: OverviewChoice, counts: OverviewCounts?): EmptyPage {
        val ready = counts != null && choice.status != StatusTab.Ready && counts.ready > 0
        val hint = buildString {
            if (choice.filtered) append("Other workspaces or providers have sessions.")
            if (ready) append(" ${OverviewFormat.plural(counts!!.ready, "session")} ${if (counts.ready == 1) "is" else "are"} ready.")
        }.trim()
        return EmptyPage(
            title = when {
                choice.filtered -> "No sessions match these filters"
                choice.status == StatusTab.Active -> "Nothing is running or waiting"
                else -> "No sessions in this state"
            },
            hint = hint,
            showAll = choice.filtered,
            showReady = ready,
            showActive = choice.status != StatusTab.Active,
        )
    }

    /** overview.tsx:189-190 — "first–last of total" on the shown page. */
    fun pageRange(page: Int, shown: Int, total: Int, pageSize: Int = OverviewClient.PAGE_SIZE): Pair<Int, Int> {
        val first = if (total > 0) page * pageSize + 1 else 0
        val last = minOf(total, page * pageSize + shown)
        return first to last
    }

    /**
     * overview.tsx:137-148 — announce only requests that ARRIVE while the operator is here: never
     * the first snapshot, never ordinary stream updates. Null = nothing new to say.
     */
    fun announcement(previousKeys: Set<String>?, items: List<OverviewPending>): String? {
        val fresh = OverviewClient.newPendingItems(previousKeys, items)
        return when {
            fresh.isEmpty() -> null
            fresh.size == 1 -> "New ${if (fresh[0].kind == "approval") "approval request" else "question"} from ${fresh[0].title}: ${fresh[0].summary}"
            else -> "${fresh.size} new requests need your attention."
        }
    }

    // ---- dashboard.tsx:1392-1443 reviewRequest: the hand-off's outcome ----------------------

    const val REVIEW_RESOLVED = "That request was already answered or withdrawn. Here is the session it came from."
    const val REVIEW_NOT_FOUND = "Couldn't find that request in the session. It may already have been answered."

    /** dashboard.tsx:1431 — how long the hand-off waits for the session's projection. */
    const val REVIEW_WAIT_MS = 8_000L

    /**
     * dashboard.tsx:1406-1409: once the session's projection is on screen, is the request still
     * pending in its OPEN turn (where the existing approval UI draws it)? Null = no projection yet.
     */
    fun reviewStillPending(tree: JsObj?, requestId: String): Boolean? {
        tree ?: return null
        return ConsentGuard.pendingApproval(tree, requestId) != null || ConsentGuard.pendingQuestion(tree, requestId) != null
    }
}
