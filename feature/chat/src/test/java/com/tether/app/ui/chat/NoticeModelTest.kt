package com.tether.app.ui.chat

import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.tree.JsObj
import kotlinx.serialization.json.put
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
    fun turnTailAddsOpencodeAndSessionTailDoesNot() {
        assertEquals("Pi", noticeProviderLabel("pi", richCodex = false))
        assertEquals("Reasonix", noticeProviderLabel("reasonix", richCodex = false))
        assertNull(noticeProviderLabel("opencode", richCodex = false))
        assertEquals("opencode", turnNoticeProviderLabel("opencode", richCodex = false))
        assertEquals("Pi", turnNoticeProviderLabel("pi", richCodex = false))
        assertEquals("Reasonix", turnNoticeProviderLabel("reasonix", richCodex = false))
        assertEquals("Codex", turnNoticeProviderLabel("codex", richCodex = true))
        assertNull(turnNoticeProviderLabel("codex", richCodex = false))
        assertNull(turnNoticeProviderLabel(null, richCodex = false))
    }

    @Test
    fun turnNoticesShowForPiOpencodeAndReasonix() {
        for ((provider, label) in listOf("pi" to "Pi", "opencode" to "opencode", "reasonix" to "Reasonix")) {
            val f = NoticeFixtures.codexNotices
            val rows = items(f.copy(projection = f.projection.copy(provider = provider))).filterIsInstance<ChatItem.ProviderNotice>()
            assertEquals(provider, listOf("n-info", "n-err"), rows.map { it.notice.noticeId })
            assertEquals(provider, "$label · config", rows[0].notice.heading)
            assertEquals(provider, label, rows[1].notice.heading)
        }
    }

    @Test
    fun reasonixHeadingsUseTheTitleMapAndUnmappedKeepTheCode() {
        val rows = items(NoticeFixtures.reasonixNotices).filterIsInstance<ChatItem.ProviderNotice>()
        assertEquals("Reasonix · Approvals differ", rows[0].notice.heading)
        assertEquals("Reasonix · reasonix_something_new", rows[1].notice.heading)
        assertEquals("Setting not applied", reasonixNoticeTitle("reasonix_model_not_applied"))
        assertNull(reasonixNoticeTitle("reasonix__not_applied"))
        assertEquals("Mode changed", reasonixNoticeTitle("reasonix_mode_changed"))
        // Other engines keep the raw code.
        assertEquals("Pi · reasonix_mode_changed", providerNoticeHeading("Pi", "reasonix_mode_changed"))
    }

    private fun receiptTail(provider: String, code: String, message: String?, dismissKey: String? = null, level: String = "info"): List<ChatItem.ProviderNotice> {
        val f = ChatFixtures.fold(
            *ChatFixtures.turn("t1", "hi", "hello", ChatFixtures.T_IDLE),
            evNullTurn("provider_notice", seq = 10, ts = ChatFixtures.T_IDLE) {
                put("noticeId", "r-1"); put("level", level); put("code", code)
                if (message != null) put("message", message)
                if (dismissKey != null) put("dismissKey", dismissKey)
            },
        )
        return items(f.copy(projection = f.projection.copy(provider = provider))).filterIsInstance<ChatItem.ProviderNotice>()
    }

    @Test
    fun aPeerReceiptOnTheSessionTailReadsTetherWithTheServersSentence() {
        val words = "Your message to qtarget was withdrawn."
        for (provider in listOf("claude", "pi", "reasonix")) {
            val n = receiptTail(provider, "peer_message_receipt", words, level = "warning").single().notice
            assertEquals(provider, "Tether", n.heading)
            assertEquals(provider, words, n.message)
            assertEquals(provider, "warning", n.level)
            assertEquals(provider, "provider_notice:session:r-1", n.dismissKey)
        }
    }

    @Test
    fun aPeerReceiptOnATurnTailReadsTetherToo() {
        val f = ChatFixtures.fold(
            ev("turn_started", "t1", ts = ChatFixtures.T_IDLE) { put("idempotencyKey", "k-t1") },
            ev("user_message_accepted", "t1", ts = ChatFixtures.T_IDLE) { put("text", "hi") },
            ev("provider_notice", "t1", ts = ChatFixtures.T_IDLE) { put("noticeId", "r-2"); put("level", "warning"); put("code", "peer_message_receipt"); put("message", "Your message to qtarget was withdrawn.") },
            ev("turn_end", "t1", ts = ChatFixtures.T_IDLE) { put("outcome", "ok") },
        )
        val n = items(f).filterIsInstance<ChatItem.ProviderNotice>().single().notice
        assertEquals("Tether", n.heading)
        assertEquals("Your message to qtarget was withdrawn.", n.message)
    }

    @Test
    fun aSuccessReceiptShowsNothingButAFailureReceiptKeepsTheTetherRow() {
        // D-GH-25 / D-GH-L14: code peer_message_receipt AND level info (delivered, carried) renders nothing, on every provider
        // and on both tails; a warning receipt (withdrawn, held, archived, handover_failed) keeps the Tether row.
        for (provider in listOf("claude", "dsh", "codex", "acp", "pi")) {
            assertTrue(provider, receiptTail(provider, "peer_message_receipt", "Your message to q was delivered.").isEmpty())
            assertTrue(provider, receiptTail(provider, "peer_message_receipt", "Your message to q was carried to the new lead.").isEmpty())
            assertEquals(provider, "Tether", receiptTail(provider, "peer_message_receipt", "Your message to q was withdrawn.", level = "warning").single().notice.heading)
        }
        // another info notice on the same tail is not touched
        assertEquals(1, receiptTail("claude", "some_code", "x").size)
    }

    @Test
    fun aPeerReceiptWithoutAMessageKeepsTheUsualHeading() {
        for (raw in listOf("""{"noticeId":"r-3","level":"warning","code":"peer_message_receipt"}""", """{"noticeId":"r-3","level":"warning","code":"peer_message_receipt","message":""}""")) {
            val n = providerNotices(JsCodec.parse("[$raw]"), "Pi").single()
            assertEquals("Pi · peer_message_receipt", n.heading)
        }
    }

    @Test
    fun anotherCodeKeepsItsProviderHeading() {
        assertEquals("Pi · some_other_code", receiptTail("pi", "some_other_code", "Something happened.").single().notice.heading)
        assertEquals("Reasonix · Mode changed", receiptTail("reasonix", "reasonix_mode_changed", "Mode moved.").single().notice.heading)
    }

    @Test
    fun sessionTailNoticesShowForPiAndReasonixNotOpencode() {
        fun tail(provider: String): List<ChatItem.ProviderNotice> {
            val f = ChatFixtures.fold(
                *ChatFixtures.turn("t1", "hi", "hello", ChatFixtures.T_IDLE),
                evNullTurn("provider_notice", seq = 10, ts = ChatFixtures.T_IDLE) { put("noticeId", "s-1"); put("level", "info"); put("code", "reasonix_mode_changed"); put("message", "Mode moved.") },
            )
            return items(f.copy(projection = f.projection.copy(provider = provider))).filterIsInstance<ChatItem.ProviderNotice>()
        }
        assertEquals("Reasonix · Mode changed", tail("reasonix").single().notice.heading)
        assertEquals("Pi · reasonix_mode_changed", tail("pi").single().notice.heading)
        // D-GH-21 (1.6.9) FLIP 1: was `assertTrue(tail("opencode").isEmpty())`. The session tail now shows for every provider
        // (web E.3, chat-view.tsx:3880-3887), so a session-level notice on opencode reads "opencode · <code>".
        assertEquals("opencode · reasonix_mode_changed", tail("opencode").single().notice.heading)
    }

    @Test
    fun providerNoticesShowOnlyForRichCodexAndClaude() {
        assertEquals("Codex", noticeProviderLabel("codex", richCodex = true))
        assertEquals("Claude", noticeProviderLabel("claude", richCodex = false))
        assertNull(noticeProviderLabel("codex", richCodex = false))
        assertNull(noticeProviderLabel("opencode", richCodex = false))
        // A non-rich opencode session shows no compactions (Codex-only) and no TURN notices of a provider the web hides;
        // D-GH-21 (1.6.9) FLIP 2: it used to also show no session-tail notices. The session tail now shows for every provider
        // (see sessionTailLabelMatchesTheWeb), the turn tail and compactions are unchanged.
        val f = NoticeFixtures.codexNotices
        val asOpencode = f.copy(projection = f.projection.copy(provider = "opencode"))
        assertTrue(items(asOpencode).none { it is ChatItem.Compaction })
    }

    @Test
    fun sessionTailLabelMatchesTheWeb() {
        // lib/provider-notice-label.ts: any codex is "Codex" (rich or not), an unknown id is "Agent".
        val want = mapOf(
            "claude" to "Claude", "codex" to "Codex", "opencode" to "opencode", "pi" to "Pi", "reasonix" to "Reasonix",
            "dsh" to "DeepSeek Harness", "acp" to "ACP", "gemini" to "Gemini", "zzz" to "Agent",
        )
        for ((provider, label) in want) assertEquals(provider, label, sessionNoticeProviderLabel(provider))
        assertEquals("Agent", sessionNoticeProviderLabel(null))
    }

    @Test
    fun sessionTailShowsPeerReceiptAndRestartOnEveryProvider() {
        for (provider in listOf("codex", "opencode", "dsh", "acp", "gemini", "claude", "pi", "reasonix")) {
            assertEquals(provider, 1, receiptTail(provider, "peer_message_receipt", "Your message to q was withdrawn.", level = "warning").size)
            assertEquals(provider, 1, receiptTail(provider, "engine_restarted", "Engine restarted").size)
        }
    }

    @Test
    fun aTurnScopedNoticeStillShowsNothingOnDshAcpAndPlainCodex() {
        // Negative control (web parity, chat-view.tsx:3725-3746): turn notices show for claude/opencode/pi/reasonix/rich codex only.
        for (provider in listOf("dsh", "acp", "codex")) {
            val f = ChatFixtures.fold(
                ev("turn_started", "t1", ts = ChatFixtures.T_IDLE) { put("idempotencyKey", "k-t1") },
                ev("user_message_accepted", "t1", ts = ChatFixtures.T_IDLE) { put("text", "hi") },
                ev("provider_notice", "t1", ts = ChatFixtures.T_IDLE) { put("noticeId", "tn-1"); put("level", "info"); put("code", "engine_restarted"); put("message", "x") },
                ev("turn_end", "t1", ts = ChatFixtures.T_IDLE) { put("outcome", "ok") },
            )
            val rows = items(f.copy(projection = f.projection.copy(provider = provider))).filterIsInstance<ChatItem.ProviderNotice>()
            assertTrue(provider, rows.isEmpty())
        }
    }

    @Test
    fun engineRestartedReadsLikeTheWebWithNoHeading() {
        // codex-rich-renderers.tsx:408-432 + restartNoticeView: no heading, the message, level from the notice, its own dismiss words.
        for (provider in listOf("claude", "pi", "reasonix", "dsh", "opencode")) {
            val n = receiptTail(provider, "engine_restarted", "Engine restarted · 1 subagent stopped", "dk-1", level = "warning").single().notice
            assertEquals(provider, "", n.heading)
            assertEquals(provider, "Engine restarted · 1 subagent stopped", n.message)
            assertEquals(provider, "warning", n.level)
            assertTrue(provider, n.restart)
            assertEquals(provider, "Dismiss engine-restart notice", n.dismissLabel)
            assertTrue(provider, n.dismissKey!!.isNotEmpty())
        }
        val info = receiptTail("claude", "engine_restarted", "Engine restarted").single().notice
        assertEquals("info", info.level)
        assertTrue(info.restart)
    }

    @Test
    fun engineRestartedWithoutAMessageFallsBackToEngineRestarted() {
        // The fold keeps no notice without a message, so the fallback is exercised on the mapper (as the web's restartNoticeView is).
        for (raw in listOf("""{"noticeId":"r-9","code":"engine_restarted"}""", """{"noticeId":"r-9","code":"engine_restarted","message":""}""")) {
            val n = providerNotices(JsCodec.parse("[$raw]"), "Claude").single()
            assertEquals("Engine restarted", n.message)
            assertEquals("", n.heading)
            assertTrue(n.restart)
        }
    }

    @Test
    fun otherNoticesKeepTheirOrdinaryDismissLabel() {
        val n = receiptTail("claude", "some_code", "x").single().notice
        assertEquals("Dismiss notice", n.dismissLabel)
        assertTrue(!n.restart)
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
    fun anInterruptedTurnSaysWhatCouldNotBeStopped() {
        val at = 3_702_000.0 // 01:01:42 UTC
        assertEquals(
            "Interrupted at 01:01:42 by Stop · 1 tool stopped · 2 background tasks killed · 1 background task could not be stopped",
            interruptedNoticeCopy(InterruptNoticeView("operator-stop", at, 1, 2, 1), ZoneOffset.UTC),
        )
        assertEquals(
            "Interrupted at 01:01:42 by Stop · 2 background tasks could not be stopped",
            interruptedNoticeCopy(InterruptNoticeView("operator-stop", at, 0, 0, 2), ZoneOffset.UTC),
        )
        // An old journal (no unstoppedBackground) reads as before.
        assertEquals("Interrupted at 01:01:42 by Stop", interruptedNoticeCopy(InterruptNoticeView("operator-stop", at, 0, 0), ZoneOffset.UTC))
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

    /** tth-5i8m (D7 1, server 159): a requested engine restart. (a1) unknown outcome, (a2) honoured/Stop-shaped, (b) crash unchanged. */
    @Test
    fun aRequestedEngineRestartHasItsOwnWordsAndACrashKeepsTheOld() {
        val at = 3_702_000.0 // 01:01:42 UTC
        // (a1)
        assertEquals(
            "Outcome unknown — an engine restart ended the turn before it finished (it may have partially applied)",
            outcomeText("outcome_unknown", null, null, cause = "engine_restart"),
        )
        // (a2) with the turn_interrupted notice, and without one
        assertEquals(
            "Interrupted at 01:01:42 by an engine restart · 1 tool stopped",
            outcomeText("cancelled", null, InterruptNoticeView("operator-stop", at, 1, 0), ZoneOffset.UTC, cause = "engine_restart"),
        )
        assertEquals("Interrupted by an engine restart", outcomeText("cancelled", null, null, cause = "engine_restart"))
        // (b) a crash and a plain Stop are unchanged
        assertEquals(OUTCOME_COPY.getValue("outcome_unknown"), outcomeText("outcome_unknown", null, null, cause = null))
        assertEquals(
            "Interrupted at 01:01:42 by Stop · 1 tool stopped",
            outcomeText("cancelled", null, InterruptNoticeView("operator-stop", at, 1, 0), ZoneOffset.UTC, cause = "interrupted"),
        )
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
