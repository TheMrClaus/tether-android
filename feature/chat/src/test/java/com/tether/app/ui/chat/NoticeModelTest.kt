package com.tether.app.ui.chat

import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

/** T6.6: the notice model against the web's copy (chat-view.tsx, codex-rich-renderers.tsx, lib/interrupt-notice.mjs). */
class NoticeModelTest {

    private fun items(f: ChatFixtures.Folded, richCodex: Boolean = false) =
        buildChatItems(f.projection, f.tree, showThinking = false, zone = ChatFixtures.zone, richCodex = richCodex)

    @Test
    fun noticeAndLimitKeysAreScopedToTheirSession() {
        // r2: two sessions showing the same dismiss key / resetsAt never share a lazy slot.
        fun keys(sessionId: String) = listOf(NoticeFixtures.sessionNotices, NoticeFixtures.limit, NoticeFixtures.scheduled, NoticeFixtures.codexNotices, NoticeFixtures.claudeFallback)
            .flatMap { f ->
                buildChatItems(f.projection, f.tree, showThinking = false, zone = ChatFixtures.zone, richCodex = true, consentSessionId = sessionId)
                    .filter { it is ChatItem.SessionNotice || it is ChatItem.RateLimit || it is ChatItem.ProviderNotice || it is ChatItem.Compaction }
                    .map { it.key }
            }
        val a = keys("sess-a")
        val b = keys("sess-b")
        assertTrue("every kind is present", a.size >= 8)
        assertTrue("no shared key: ${a.intersect(b.toSet())}", a.intersect(b.toSet()).isEmpty())
        // Without a client key the tree's own session id scopes them.
        val own = buildChatItems(NoticeFixtures.limit.projection, NoticeFixtures.limit.tree, showThinking = false, zone = ChatFixtures.zone)
            .filterIsInstance<ChatItem.RateLimit>().single()
        assertEquals(NoticeFixtures.limit.projection.tetherSessionId, own.scope)
    }

    @Test
    fun claudeModelFallbackIsATurnNoticeWithTheClassAwareLeadIn() {
        val notices = items(NoticeFixtures.claudeFallback).filterIsInstance<ChatItem.ProviderNotice>()
        assertEquals(1, notices.size)
        val n = notices.single().notice
        // engines/events.mjs modelFallbackLeadIn("overloaded") = "Model switched · capacity".
        assertEquals("Claude · Model switched · capacity", n.heading)
        assertEquals("Opus is temporarily overloaded, so this turn continues on Sonnet.", n.message)
        assertEquals("warning", n.level)
        assertTrue(n.dismissKey!!.isNotEmpty())
        assertEquals("t1", notices.single().turnId)
    }

    @Test
    fun providerNoticesShowOnlyForRichCodexAndClaude() {
        assertEquals("Codex", noticeProviderLabel("codex", richCodex = true))
        assertEquals("Claude", noticeProviderLabel("claude", richCodex = false))
        assertNull(noticeProviderLabel("codex", richCodex = false))
        assertNull(noticeProviderLabel("opencode", richCodex = false))
        // A non-rich session of another provider shows none (the fixture tree is folded as claude).
        val f = NoticeFixtures.codexNotices
        val asOpencode = f.copy(projection = f.projection.copy(provider = "opencode"))
        assertTrue(items(asOpencode).none { it is ChatItem.ProviderNotice || it is ChatItem.Compaction })
    }

    @Test
    fun codexTurnDetailsCarryCompactionsThenNoticesInOrder() {
        val rows = items(NoticeFixtures.codexNotices, richCodex = true)
        val compaction = rows.filterIsInstance<ChatItem.Compaction>().single()
        val notices = rows.filterIsInstance<ChatItem.ProviderNotice>()
        assertEquals(listOf("n-info", "n-err"), notices.map { it.notice.noticeId })
        assertEquals("Codex · config", notices[0].notice.heading)
        assertEquals("Codex", notices[1].notice.heading)
        assertEquals("error", notices[1].notice.level)
        assertTrue(rows.indexOf(compaction) < rows.indexOf(notices[0]))
        // The details stack opens on the turn gap, then stays tight (space-sm), like plan / diff / review.
        assertTrue(!compaction.tight && notices.all { it.tight })
        assertTrue(compaction.compaction.dismissKey!!.startsWith("context_compacted:t1:"))
    }

    @Test
    fun sessionNoticesReadAsTheWebWordsThem() {
        val rows = items(NoticeFixtures.sessionNotices).filterIsInstance<ChatItem.SessionNotice>().map { it.notice }
        assertEquals(3, rows.size)
        assertEquals("2 turns advanced outside Tether (resumed in a terminal or another editor) — the latest turns above reflect that external activity.", rows[0].text)
        assertEquals("ext-1", rows[0].dismissKey)
        assertEquals("Dismiss external-advancement notice", rows[0].dismissLabel)
        assertEquals("A background task was dropped because the agent process terminated unexpectedly and won’t report back (2 tasks) — re-run it if you still need the result.", rows[1].text)
        assertEquals("A background task was interrupted by a restart and won’t report back — re-run it if you still need the result.", rows[2].text)
        assertEquals("Dismiss background-loss notice", rows[2].dismissLabel)
    }

    @Test
    fun oneExternalTurnIsSingular() {
        val tree = JsCodec.parse("""{"notices":[{"kind":"external_advancement","count":1,"dismissKey":"k"}]}""") as JsObj
        assertEquals("1 turn advanced outside Tether (resumed in a terminal or another editor) — the latest turn above reflects that external activity.", sessionNotices(tree).single().text)
        val idle = JsCodec.parse("""{"notices":[{"kind":"background_abandoned","outstanding":1,"dismissKey":"k"}]}""") as JsObj
        assertEquals("A background task was dropped when its idle session was recycled and won’t report back — re-run it if you still need the result.", sessionNotices(idle).single().text)
    }

    @Test
    fun anInterruptedTurnSaysWhoAndWhatAtItsOutcome() {
        val outcome = items(NoticeFixtures.interrupted).filterIsInstance<ChatItem.Outcome>().single()
        // 01:01:00 + 42 s, UTC.
        assertEquals(
            "Interrupted at 01:01:42 by your message · 1 tool stopped · 2 background tasks killed",
            outcomeText("cancelled", outcome.turn.error, outcome.interrupt, ZoneOffset.UTC),
        )
        assertEquals("Interrupted", interruptedNoticeCopy(InterruptNoticeView(by = "nobody", at = null, stoppedTools = 0, stoppedBackground = 0)))
        assertEquals("Interrupted by the watchdog (no output for too long) · 2 tools stopped", interruptedNoticeCopy(InterruptNoticeView("watchdog", null, 2, 0)))
    }

    @Test
    fun outcomeCopyPrefersTheErrorThenTheOutcomesWords() {
        assertEquals("boom", outcomeText("error", "boom", null))
        assertEquals("Turn ended with an error", outcomeText("error", null, null))
        assertEquals("Turn interrupted", outcomeText("cancelled", null, null))
        assertEquals(OUTCOME_COPY.getValue("outcome_unknown"), outcomeText("outcome_unknown", null, null))
        // An interrupt notice only speaks for a cancelled turn.
        assertEquals("Turn ended with an error", outcomeText("error", null, InterruptNoticeView("operator-stop", null, 0, 0)))
    }

    @Test
    fun apiRetryReadsItsReasonAndAttempt() {
        assertEquals("the model is overloaded — retrying (attempt 2 of 5)", apiRetryText("overloaded", 2, 5))
        assertEquals("the provider call failed — retrying (attempt 1)", apiRetryText("brand_new_reason", 1, null))
        assertEquals("the provider call failed — retrying (attempt 3)", apiRetryText(null, 3, 0))
    }

    @Test
    fun theLimitPromptIsTheCardThenTheScheduledRowAndSitsAfterTheNotices() {
        val card = items(NoticeFixtures.limit).filterIsInstance<ChatItem.RateLimit>().single().prompt
        assertEquals(RateLimitPromptView("awaiting_choice", NoticeFixtures.RESETS_AT, NoticeFixtures.RESETS_AT + 120_000), card)
        assertEquals("scheduled", items(NoticeFixtures.scheduled).filterIsInstance<ChatItem.RateLimit>().single().prompt.status)
        val fired = JsCodec.parse("""{"rateLimitResume":{"status":"fired","resetsAt":5,"resumeAt":6}}""") as JsObj
        assertNull(rateLimitPrompt(fired))
        val fractional = JsCodec.parse("""{"rateLimitResume":{"status":"awaiting_choice","resetsAt":5.5,"resumeAt":6}}""") as JsObj
        assertNull("a reset that cannot be echoed exactly offers nothing", rateLimitPrompt(fractional))
    }

    @Test
    fun theClockReadsLikeIntlShortTimeWithTheZone() {
        assertEquals("2:01 AM UTC", limitClockTime(NoticeFixtures.RESETS_AT, Locale.US, ZoneOffset.UTC))
    }

    @Test
    fun serverTextIsCleanedAndBounded() {
        val long = "x".repeat(NOTICE_BODY_MAX + 500)
        val tree = JsCodec.parse(
            """{"providerNotices":[{"noticeId":"a","level":"weird","code":"c‮ode","message":"$long","dismissKey":"k"},{"noticeId":"","message":"m"},{"noticeId":"b","message":"line1\n\nline2","dismissKey":"${"k".repeat(600)}"}]}""",
        ) as JsObj
        val rows = providerNotices(tree["providerNotices"], "Codex")
        assertEquals(2, rows.size)
        assertEquals("warning", rows[0].level)
        assertTrue(rows[0].message.length <= NOTICE_BODY_MAX + 1)
        assertTrue("bidi override stripped", !rows[0].heading.contains('‮'))
        assertEquals("line1 line2", rows[1].message)
        assertNull("an over-long key is never offered", rows[1].dismissKey)
    }
}
