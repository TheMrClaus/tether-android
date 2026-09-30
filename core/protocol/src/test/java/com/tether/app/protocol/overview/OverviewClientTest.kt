package com.tether.app.protocol.overview

import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.model.OverviewActivity
import com.tether.app.protocol.model.OverviewCard
import com.tether.app.protocol.model.OverviewCounts
import com.tether.app.protocol.model.OverviewFilters
import com.tether.app.protocol.model.OverviewNormalizedFilters
import com.tether.app.protocol.model.OverviewPending
import com.tether.app.protocol.model.OverviewPendingPanel
import com.tether.app.protocol.model.OverviewWorkspace
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T15.1: tests/overview-client.test.mjs ported case for case (tether main, OVERVIEW_STUDIO_PLAN.md
 * §5), plus the client-side bounds of lib/overview-model.mjs (normalizePaging / normalizeFilters,
 * from tests/overview-model.test.mjs "filters, counts and paging agree"). Each test keeps the web
 * test's name.
 */
class OverviewClientTest {

    private fun counts(
        running: Int = 1,
        waiting: Int = 1,
        attention: Int = 0,
        ready: Int = 2,
        workspaces: Int = 1,
        total: Int = 4,
    ) = OverviewCounts(running, waiting, attention, ready, workspaces, total)

    private fun card(sessionId: String, status: String = "running", title: String = sessionId, pending: List<OverviewPending> = emptyList()) =
        OverviewCard(
            sessionId = sessionId, nodeId = "n", seq = 1, title = title, provider = "claude", providerLabel = "Claude Code",
            workspace = OverviewWorkspace("/w", "w"), cwd = "/w", status = status, pending = pending,
        )

    private fun activity(id: String, ts: Long) =
        OverviewActivity(id = id, ts = ts, sessionId = "a", nodeId = "n", seq = ts, title = "a", provider = "claude", kind = "tool_started", text = id)

    private fun pendingPanel(items: List<OverviewPending> = emptyList()) = OverviewPendingPanel(items, items.size, 0)

    private fun pending(sessionId: String, requestId: String) = OverviewPending(sessionId = sessionId, requestId = requestId)

    private fun snapshot(
        feedId: String = "feed_1",
        cursor: Long = 1,
        cards: List<OverviewCard> = listOf(card("a"), card("b")),
        activity: List<OverviewActivity> = listOf(activity("x", 10)),
    ) = ServerMessage.OverviewSnapshot(
        feedId = feedId, cursor = cursor, generatedAt = 100, activitySince = 50,
        filters = OverviewNormalizedFilters(emptyList(), emptyList(), listOf("running", "waiting", "attention")),
        page = 0, pageSize = 24, pageCount = 1, totalCards = 2, counts = counts(), cards = cards,
        pending = pendingPanel(), activity = activity,
    )

    private fun delta(
        feedId: String = "feed_1",
        cursor: Long = 2,
        prevCursor: Long = 1,
        upserts: List<OverviewCard> = emptyList(),
        removals: List<String> = emptyList(),
        counts: OverviewCounts = counts(),
        pending: OverviewPendingPanel = pendingPanel(),
        activity: List<OverviewActivity> = emptyList(),
    ) = ServerMessage.OverviewDelta(feedId, cursor, prevCursor, upserts, removals, counts, pending, activity)

    private fun live(): OverviewClientState =
        OverviewClient.applyFrame(OverviewClient.requested(OverviewClient.initial()), snapshot(), 1).state

    @Test fun `an unsubscribed state ignores frames still in flight`() {
        val idle = OverviewClient.initial()
        val result = OverviewClient.applyFrame(idle, snapshot())
        assertSame(idle, result.state)
        assertFalse(result.resubscribe)
    }

    @Test fun `a snapshot replaces all local state, including a previous feed's activity`() {
        var state = live()
        state = OverviewClient.applyFrame(state, delta(activity = listOf(activity("y", 20)))).state
        assertEquals(2, state.activity.size)
        val next = OverviewClient.applyFrame(state, snapshot(feedId = "feed_2", cursor = 7, cards = listOf(card("c")), activity = listOf(activity("z", 30))), 5)
        assertFalse(next.resubscribe)
        assertEquals(OverviewPhase.Live, next.state.phase)
        assertEquals("feed_2", next.state.feedId)
        assertEquals(7L, next.state.cursor)
        assertEquals(listOf("c"), next.state.data!!.cards.map { it.sessionId })
        assertEquals(listOf("z"), next.state.activity.map { it.id })
        assertEquals(5L, next.state.updatedAt)
    }

    @Test fun `a delta upserts in place, removes, and replaces counts and pending`() {
        val state = live()
        val pending = pendingPanel(listOf(OverviewPending("a", "r1", "approval", title = "a", provider = "claude", summary = "Bash")))
        val next = OverviewClient.applyFrame(
            state,
            delta(
                upserts = listOf(card("a", status = "waiting", title = "A!")),
                removals = listOf("b"),
                counts = counts(running = 0, waiting = 1),
                pending = pending,
            ),
        ).state
        assertEquals(listOf(Triple("a", "A!", "waiting")), next.data!!.cards.map { Triple(it.sessionId, it.title, it.status) })
        assertEquals(2L, next.cursor)
        assertEquals(0, next.data!!.counts.running)
        assertEquals("totalCards follows the counts of the paged statuses", 1, next.data!!.totalCards)
        assertSame(pending, next.data!!.pending)
        // Inputs are not mutated.
        assertEquals(listOf("a", "b"), state.data!!.cards.map { it.sessionId })
        assertEquals(1L, state.cursor)
    }

    @Test fun `a cursor gap asks to resubscribe and ignores deltas until the snapshot`() {
        val state = live()
        val gap = OverviewClient.applyFrame(state, delta(cursor = 5, prevCursor = 4))
        assertTrue(gap.resubscribe)
        assertTrue(gap.state.awaitingSnapshot)
        assertEquals(OverviewPhase.Resyncing, gap.state.phase)
        assertSame("stale data stays on screen meanwhile", state.data, gap.state.data)
        // A further (in-sequence-looking) delta is ignored without another resubscribe.
        val after = OverviewClient.applyFrame(gap.state, delta(cursor = 6, prevCursor = 5, upserts = listOf(card("a", title = "late"))))
        assertFalse(after.resubscribe)
        assertSame(gap.state, after.state)
        val fresh = OverviewClient.applyFrame(after.state, snapshot(cursor = 9)).state
        assertFalse(fresh.awaitingSnapshot)
        assertEquals(9L, fresh.cursor)
    }

    @Test fun `a feedId change (server restart) asks to resubscribe`() {
        val result = OverviewClient.applyFrame(live(), delta(feedId = "feed_other"))
        assertTrue(result.resubscribe)
        assertTrue(result.state.awaitingSnapshot)
    }

    @Test fun `an upsert for a card not on this page means we are out of step`() {
        val result = OverviewClient.applyFrame(live(), delta(upserts = listOf(card("stranger"))))
        assertTrue(result.resubscribe)
    }

    @Test fun `a delta before any snapshot is ignored, not applied`() {
        val loading = OverviewClient.requested(OverviewClient.initial())
        val result = OverviewClient.applyFrame(loading, delta())
        assertSame(loading, result.state)
        assertFalse(result.resubscribe)
    }

    @Test fun `lifecycle transitions keep data and mark the phase`() {
        val state = live()
        assertEquals(OverviewPhase.Resyncing, OverviewClient.requested(state).phase)
        assertEquals(OverviewPhase.Loading, OverviewClient.requested(OverviewClient.initial()).phase)
        val offline = OverviewClient.disconnected(state)
        assertEquals(OverviewPhase.Offline, offline.phase)
        assertTrue(offline.awaitingSnapshot)
        assertSame(state.data, offline.data)
        assertEquals(OverviewPhase.Idle, OverviewClient.disconnected(OverviewClient.unsubscribed(state)).phase)
        // An offline state ignores deltas from the dead socket's tail until a snapshot.
        assertSame(offline, OverviewClient.applyFrame(offline, delta()).state)
        // Other frame types pass through untouched.
        assertSame(state, OverviewClient.applyFrame(state, ServerMessage.Pong(null)).state)
    }

    @Test fun `activity merges newest first, dedupes by id and caps`() {
        val merged = OverviewClient.mergeActivity(listOf(activity("a", 1), activity("b", 2)), listOf(activity("b", 2), activity("c", 3)))
        assertEquals(listOf("c", "b", "a"), merged.map { it.id })
        val many = (0 until 80).map { activity("k$it", it.toLong()) }
        val capped = OverviewClient.mergeActivity(emptyList(), many)
        assertEquals(OverviewClient.ACTIVITY_CAP, capped.size)
        assertEquals("k79", capped[0].id)
        // Delta activity lands on top of the existing tail, deduped.
        val state = OverviewClient.applyFrame(live(), delta(activity = listOf(activity("x", 10), activity("y", 11)))).state
        assertEquals(listOf("y", "x"), state.activity.map { it.id })
        // Junk records are dropped. Here an id-less record never decodes: the frame decoder drops it.
        assertEquals(emptyList<OverviewActivity>(), OverviewClient.mergeActivity(null, null))
        val frame = TetherJson.parseToJsonElement(
            """{"type":"overview-delta","feedId":"f","cursor":2,"prevCursor":1,"activity":[null,{"ts":1},{"id":"ok","ts":2}]}""",
        ).jsonObject
        assertEquals(listOf("ok"), (ServerMessage.parse(frame) as ServerMessage.OverviewDelta).activity.map { it.id })
    }

    @Test fun `equal timestamps keep their merged order, incoming first`() {
        val merged = OverviewClient.mergeActivity(listOf(activity("old", 5)), listOf(activity("new", 5)))
        assertEquals(listOf("new", "old"), merged.map { it.id })
    }

    @Test fun `overviewTotalCards sums the paged statuses and defaults to the operational set`() {
        val c = counts(running = 2, waiting = 3, attention = 1, ready = 7)
        assertEquals(7, OverviewClient.totalCards(c, listOf("ready")))
        assertEquals(6, OverviewClient.totalCards(c, emptyList()))
        assertEquals(2, OverviewClient.totalCards(c, listOf("running", "running")))
        assertEquals(0, OverviewClient.totalCards(null, listOf("ready")))
    }

    @Test fun `stableCardOrder keeps on-screen cards in place and appends new ones`() {
        val cards = listOf(card("c"), card("a"), card("b"))
        assertSame("no frozen order: the server's order stands", cards, OverviewClient.stableCardOrder(null, cards))
        assertEquals(listOf("a", "b", "c"), OverviewClient.stableCardOrder(listOf("a", "b", "gone"), cards).map { it.sessionId })
        // The fresh content is used, only the position is held.
        val updated = listOf(card("b", title = "new"), card("a"))
        assertEquals("new", OverviewClient.stableCardOrder(listOf("a", "b"), updated)[1].title)
    }

    @Test fun `newPendingItems announces only requests that arrive after the first snapshot`() {
        val r1 = pending("a", "1")
        val r2 = pending("a", "2")
        assertEquals(emptyList<OverviewPending>(), OverviewClient.newPendingItems(null, listOf(r1)))
        assertEquals(listOf(r2), OverviewClient.newPendingItems(setOf(OverviewClient.pendingKey(r1)), listOf(r1, r2)))
        // Same request id in another session is a different request.
        assertEquals(1, OverviewClient.newPendingItems(setOf(OverviewClient.pendingKey(r1)), listOf(pending("b", "1"))).size)
    }

    @Test fun `isRequestPending reads the panel and the cards' own refs`() {
        val data = live().data!!
        assertFalse(OverviewClient.isRequestPending(data, "a", "r"))
        val withPanel = data.copy(pending = pendingPanel(listOf(pending("a", "r"))))
        assertTrue(OverviewClient.isRequestPending(withPanel, "a", "r"))
        val withCard = data.copy(cards = listOf(card("a", pending = listOf(pending("a", "q")))))
        assertTrue(OverviewClient.isRequestPending(withCard, "a", "q"))
        assertFalse(OverviewClient.isRequestPending(null, "a", "q"))
    }

    // ---- lib/overview-model.mjs: the bounds a subscription is built with ----------------------

    @Test fun `normalizePaging clamps the page size and page like the server`() {
        assertEquals(0 to 24, OverviewClient.normalizePaging(null, null))
        assertEquals(0 to OverviewClient.PAGE_SIZE_MAX, OverviewClient.normalizePaging(null, 500))
        assertEquals(0 to 24, OverviewClient.normalizePaging(-3, 0))
        assertEquals(OverviewClient.MAX_PAGE to 4, OverviewClient.normalizePaging(99_999, 4))
        assertEquals(2 to 4, OverviewClient.normalizePaging(2, 4))
    }

    @Test fun `normalizeFilters dedupes, sorts, caps and keeps only known statuses in canonical order`() {
        val filters = OverviewClient.normalizeFilters(
            OverviewFilters(
                workspaces = listOf("/b", "/a", "/b", ""),
                providers = (0 until 70).map { "p%02d".format(it) },
                statuses = listOf("ready", "bogus", "running", "ready"),
            ),
        )!!
        assertEquals(listOf("/a", "/b"), filters.workspaces)
        assertEquals(OverviewClient.FILTER_ENTRIES, filters.providers!!.size)
        assertEquals(listOf("running", "ready"), filters.statuses)
        // Nothing valid left: the key stays absent, so the server applies its operational default.
        assertEquals(OverviewFilters(), OverviewClient.normalizeFilters(OverviewFilters(workspaces = listOf(""), statuses = listOf("x"))))
    }

    @Test fun `the Overview's subscription is the web's subscriptionFor, bounded`() {
        // overview.tsx:64 — `{ filters, page, pageSize: OVERVIEW_PAGE_SIZE }`, filters `{}` when none.
        assertEquals(OverviewSubscription(OverviewFilters(), 0, 24), OverviewClient.subscription(null, null, null, 0))
        assertEquals(
            OverviewSubscription(OverviewFilters(listOf("/repo"), listOf("work"), listOf("waiting")), 3, 24),
            OverviewClient.subscription("/repo", "work", listOf("waiting"), 3),
        )
        assertEquals(0, OverviewClient.subscription(null, null, null, -1).page)
    }

    // T15.2 r2 (security review L1): the server's OVERVIEW_LIMITS re-applied as a frame is folded.

    @Test fun `an oversized snapshot is folded to the server's own caps`() {
        val manyRefs = (0 until 30).map { pending("c0", "r$it") }
        val cards = (0 until 500).map { card("c$it", status = "waiting", pending = if (it == 0) manyRefs else emptyList()) }
        val panel = OverviewPendingPanel((0 until 300).map { pending("c$it", "p$it") }, total = 300, outsideFilters = 0)
        val facets = com.tether.app.protocol.model.OverviewFacets(
            workspaces = (0 until 1_000).map { com.tether.app.protocol.model.OverviewWorkspaceFacet("/w$it", "w$it", 1) },
            providers = (0 until 1_000).map { com.tether.app.protocol.model.OverviewProviderFacet("p$it", "claude", label = "P$it", count = 1) },
        )
        val data = OverviewClient.applyFrame(
            OverviewClient.requested(OverviewClient.initial()),
            snapshot(cards = cards).copy(pending = panel, facets = facets),
        ).state.data!!
        assertEquals("pageSizeMax", 48, data.cards.size)
        assertEquals("server order kept", (0 until 48).map { "c$it" }, data.cards.map { it.sessionId })
        assertEquals("cardPending", 5, data.cards[0].pending.size)
        assertEquals("pendingItems", 20, data.pending.items.size)
        assertEquals("the total still says how many wait", 300, data.pending.total)
        assertEquals(200, data.facets.workspaces.size)
        assertEquals(200, data.facets.providers.size)
    }

    @Test fun `an oversized delta is folded to the server's own caps`() {
        val state = live()
        val upsert = card("a", status = "waiting", pending = (0 until 40).map { pending("a", "r$it") })
        val panel = OverviewPendingPanel((0 until 90).map { pending("a", "p$it") }, total = 90, outsideFilters = 0)
        val data = OverviewClient.applyFrame(state, delta(upserts = listOf(upsert), pending = panel)).state.data!!
        assertEquals(5, data.cards.single { it.sessionId == "a" }.pending.size)
        assertEquals(20, data.pending.items.size)
        assertEquals(90, data.pending.total)
    }
}
