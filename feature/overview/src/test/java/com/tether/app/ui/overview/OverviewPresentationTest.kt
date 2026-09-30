package com.tether.app.ui.overview

import com.tether.app.protocol.model.OverviewAttention
import com.tether.app.protocol.model.OverviewCard
import com.tether.app.protocol.model.OverviewCounts
import com.tether.app.protocol.model.OverviewExcerpt
import com.tether.app.protocol.model.OverviewFacets
import com.tether.app.protocol.model.OverviewFilters
import com.tether.app.protocol.model.OverviewPending
import com.tether.app.protocol.model.OverviewWorkspaceFacet
import com.tether.app.protocol.overview.OverviewClientState
import com.tether.app.protocol.overview.OverviewPhase
import com.tether.app.protocol.overview.OverviewSubscription
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * T15.2: components/overview/overview-format.ts, overview-card.tsx (cardStatus, the state clock),
 * overview.tsx (tabs, subscription, counts, empty states, the arrival announcement) and
 * dashboard.tsx reviewRequest's pending check, ported as pure rules.
 */
class OverviewPresentationTest {

    private fun card(status: String, attention: OverviewAttention? = null, pending: List<OverviewPending> = emptyList(), excerpt: OverviewExcerpt? = null) =
        OverviewCard(sessionId = "s", status = status, attention = attention, pending = pending, excerpt = excerpt, statusSince = 500, turnStartedAt = 100)

    @Test fun `formatDuration and describeDuration read like the web`() {
        assertEquals("<1m", OverviewFormat.duration(59_999))
        assertEquals("4m", OverviewFormat.duration(4 * 60_000L + 5))
        assertEquals("2h 14m", OverviewFormat.duration((2 * 60 + 14) * 60_000L))
        assertEquals("2h", OverviewFormat.duration(2 * 3_600_000L))
        assertEquals("3d", OverviewFormat.duration(3 * 86_400_000L + 5))
        assertEquals("less than a minute", OverviewFormat.describeDuration(0))
        assertEquals("1 minute", OverviewFormat.describeDuration(60_000))
        assertEquals("1 hour 1 minute", OverviewFormat.describeDuration(61 * 60_000L))
        assertEquals("2 hours", OverviewFormat.describeDuration(2 * 3_600_000L))
        assertEquals("1 day", OverviewFormat.describeDuration(86_400_000L))
        assertEquals("09:05", OverviewFormat.clock(9 * 3_600_000L + 5 * 60_000L, ZoneOffset.UTC))
        assertEquals("", OverviewFormat.ago(null, 5))
        assertEquals("just now", OverviewFormat.ago(1_000, 30_000))
        assertEquals("3m ago", OverviewFormat.ago(1_000, 1_000 + 3 * 60_000L))
        assertEquals("1 workspace", OverviewFormat.plural(1, "workspace"))
        assertEquals("2 workspaces", OverviewFormat.plural(2, "workspace"))
    }

    @Test fun `every status has words and a glyph of its own`() {
        assertEquals(CardStatus("Running", CardTone.Running, StatusGlyph.Running), OverviewPresentation.cardStatus(card("running")))
        val waiting = OverviewPresentation.cardStatus(card("waiting", OverviewAttention("approval", "Approval needed: Bash")))
        assertEquals(CardStatus("Waiting for you", CardTone.Waiting, StatusGlyph.Waiting, "Approval needed: Bash"), waiting)
        val kinds = mapOf(
            "rate_limit" to ("Rate limited" to StatusGlyph.RateLimit),
            "auth" to ("Signed out" to StatusGlyph.Auth),
            "outcome_unknown" to ("Outcome unknown" to StatusGlyph.OutcomeUnknown),
            "interrupted" to ("Interrupted" to StatusGlyph.Interrupted),
            "error" to ("Turn failed" to StatusGlyph.Failed),
        )
        for ((kind, expected) in kinds) {
            val status = OverviewPresentation.cardStatus(card("attention", OverviewAttention(kind, "why")))
            assertEquals(kind, expected.first, status.label)
            assertEquals(kind, expected.second, status.glyph)
            assertEquals("only a failure is danger-toned", if (kind == "error") CardTone.Danger else CardTone.Attention, status.tone)
            assertEquals("why", status.detail)
        }
        assertEquals("Needs attention", OverviewPresentation.cardStatus(card("attention")).label)
        assertEquals(CardTone.Ready, OverviewPresentation.cardStatus(card("ready")).tone)
        assertEquals("Ended", OverviewPresentation.cardStatus(card("ended")).label)
        assertEquals("unknown statuses read as ended", "Ended", OverviewPresentation.cardStatus(card("bogus")).label)
    }

    @Test fun `the clock measures the current state, and only a waiting card reviews`() {
        assertEquals("running measures the open turn", 100L, OverviewPresentation.stateSince(card("running")))
        assertEquals(500L, OverviewPresentation.stateSince(card("waiting")))
        assertEquals("working for", OverviewPresentation.durationVerb(card("running")))
        assertEquals("waiting for", OverviewPresentation.durationVerb(card("waiting")))
        assertEquals("in this state for", OverviewPresentation.durationVerb(card("ready")))
        val refs = listOf(OverviewPending("s", "r1"), OverviewPending("s", "r2"))
        assertEquals("r1", OverviewPresentation.reviewTarget(card("waiting", pending = refs))?.requestId)
        assertNull(OverviewPresentation.reviewTarget(card("attention", pending = refs)))
    }

    @Test fun `the excerpt repeats the status detail only when it adds something`() {
        val same = card("waiting", OverviewAttention("question", "Question waiting for your answer"), excerpt = OverviewExcerpt("request", "Question waiting for your answer"))
        assertNull(OverviewPresentation.shownExcerpt(same))
        val adds = card("waiting", OverviewAttention("approval", "Approval needed: Bash"), excerpt = OverviewExcerpt("request", "Approve Bash: npm test"))
        assertEquals("Approve Bash: npm test", OverviewPresentation.shownExcerpt(adds)?.text)
    }

    @Test fun `tabs, subscription and counts follow overview-tsx`() {
        assertEquals(OverviewSubscription(OverviewFilters(), 0, 24), OverviewPresentation.subscriptionFor(OverviewChoice(), 0))
        assertEquals(
            OverviewSubscription(OverviewFilters(listOf("/w"), listOf("codex"), listOf("waiting")), 2, 24),
            OverviewPresentation.subscriptionFor(OverviewChoice("/w", "codex", StatusTab.Waiting), 2),
        )
        val counts = OverviewCounts(running = 2, waiting = 1, attention = 3, ready = 4, workspaces = 2, total = 10)
        assertEquals(6, OverviewPresentation.tabCount(counts, StatusTab.Active))
        assertEquals(4, OverviewPresentation.tabCount(counts, StatusTab.Ready))
        assertNull(OverviewPresentation.tabCount(null, StatusTab.Ready))
        assertEquals(listOf("2 running", "1 waiting for you", "2 workspaces"), OverviewPresentation.countsLine(counts, offline = false))
        assertEquals(listOf("Loading sessions…"), OverviewPresentation.countsLine(null, offline = false))
        assertEquals(listOf("Waiting for the secure link…"), OverviewPresentation.countsLine(null, offline = true))
    }

    @Test fun `offline, live and the no-sessions state`() {
        val live = OverviewClientState(phase = OverviewPhase.Live)
        assertTrue(OverviewPresentation.live(live, connected = true))
        assertFalse(OverviewPresentation.live(live, connected = false))
        assertTrue(OverviewPresentation.offline(live, connected = false))
        assertTrue(OverviewPresentation.offline(OverviewClientState(phase = OverviewPhase.Offline), connected = true))
        assertTrue(OverviewPresentation.noSessionsAtAll(OverviewFacets()))
        assertFalse(OverviewPresentation.noSessionsAtAll(OverviewFacets(workspaces = listOf(OverviewWorkspaceFacet("/w", "w", 1)))))
        assertFalse("no data yet is loading, not empty", OverviewPresentation.noSessionsAtAll(null))
    }

    @Test fun `an empty page offers the ways out overview-tsx offers`() {
        val counts = OverviewCounts(ready = 2)
        val filtered = OverviewPresentation.emptyPage(OverviewChoice(workspace = "/w"), counts)
        assertEquals("No sessions match these filters", filtered.title)
        assertEquals("Other workspaces or providers have sessions. 2 sessions are ready.", filtered.hint)
        assertTrue(filtered.showAll && filtered.showReady && !filtered.showActive)
        val active = OverviewPresentation.emptyPage(OverviewChoice(), OverviewCounts(ready = 1))
        assertEquals("Nothing is running or waiting", active.title)
        assertEquals("1 session is ready.", active.hint)
        val ready = OverviewPresentation.emptyPage(OverviewChoice(status = StatusTab.Ready), counts)
        assertEquals("No sessions in this state", ready.title)
        assertTrue(!ready.showReady && ready.showActive)
        assertEquals(1 to 24, OverviewPresentation.pageRange(0, 24, 30))
        assertEquals(25 to 30, OverviewPresentation.pageRange(1, 6, 30))
        assertEquals(0 to 0, OverviewPresentation.pageRange(0, 0, 0))
    }

    @Test fun `only requests that arrive while watching are announced`() {
        val a = OverviewPending("s1", "r1", "approval", title = "Build", summary = "Bash")
        val b = OverviewPending("s2", "r2", "question", title = "Docs", summary = "Which?")
        assertNull("the first snapshot announces nothing", OverviewPresentation.announcement(null, listOf(a)))
        val seen = setOf(com.tether.app.protocol.overview.OverviewClient.pendingKey(a))
        assertNull(OverviewPresentation.announcement(seen, listOf(a)))
        assertEquals("New question from Docs: Which?", OverviewPresentation.announcement(seen, listOf(a, b)))
        assertEquals("2 new requests need your attention.", OverviewPresentation.announcement(emptySet(), listOf(a, b)))
    }

    @Test fun `the review hand-off reads the open turn's pending requests`() {
        fun tree(approvals: Map<String, JsObj> = emptyMap(), questions: Map<String, JsObj> = emptyMap(), active: String? = "t1") = JsObj.of(
            "activeTurnId" to (active?.let(::JsStr) ?: com.tether.app.protocol.tree.JsNull),
            "turnsById" to JsObj.of("t1" to JsObj.of("turnId" to JsStr("t1"), "pendingApprovals" to JsObj.of(*approvals.toList().toTypedArray()), "pendingQuestions" to JsObj.of(*questions.toList().toTypedArray()))),
        )
        val request = JsObj.of("requestId" to JsStr("r1"))
        assertNull("no projection yet", OverviewPresentation.reviewStillPending(null, "r1"))
        assertEquals(true, OverviewPresentation.reviewStillPending(tree(approvals = mapOf("r1" to request)), "r1"))
        assertEquals(true, OverviewPresentation.reviewStillPending(tree(questions = mapOf("r1" to request)), "r1"))
        assertEquals(false, OverviewPresentation.reviewStillPending(tree(), "r1"))
        assertEquals("a closed turn holds no live request", false, OverviewPresentation.reviewStillPending(tree(approvals = mapOf("r1" to request), active = null), "r1"))
    }
}
