package com.tether.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AW.2.9: the pure tab model, mirroring the web's tests/question-tabs.test.mjs, by answer slot. */
class QuestionTabsModelTest {
    private val empty = QuestionSelection()

    @Test fun labelsAreTheTrimmedHeaderElseQuestionN() {
        assertEquals(listOf("Crash reports", "Question 2", "Question 3"), questionTabLabels(listOf("  Crash reports ", null, "   ")))
    }

    @Test fun duplicateLabelsGetTheirNumberOnEveryOccurrenceCaseFolded() {
        assertEquals(listOf("Auth 1", "auth 2", "Other"), questionTabLabels(listOf("Auth", "auth", "Other")))
    }

    @Test fun statusPrecedenceIsAnsweredThenSkippedThenNeedsAnswerOrUnanswered() {
        val both = empty.copy(picks = mapOf(0 to listOf(1)), skipped = setOf(0))
        assertEquals(QuestionStatus.Answered, slotStatus(both, 0, false))
        assertEquals(QuestionStatus.Skipped, slotStatus(empty.copy(skipped = setOf(0)), 0, true))
        assertEquals(QuestionStatus.NeedsAnswer, slotStatus(empty, 0, true))
        assertEquals(QuestionStatus.Unanswered, slotStatus(empty, 0, false))
    }

    @Test fun skipClearsTheAnswerIntoUndoAndMarksTheSlot() {
        val answered = empty.copy(picks = mapOf(2 to listOf(0, 1)), other = mapOf(2 to "mine"))
        val skipped = applySkip(answered, 2)
        assertTrue(skipped.picks[2] == null && skipped.other[2] == null)
        assertEquals(setOf(2), skipped.skipped)
        assertEquals(SkippedAnswer(listOf(0, 1), "mine"), skipped.skipUndo[2])
    }

    @Test fun skipWithNothingToClearKeepsNoUndoEntry() {
        val stale = empty.copy(skipUndo = mapOf(1 to SkippedAnswer(listOf(0), "")))
        val skipped = applySkip(stale, 1)
        assertEquals(setOf(1), skipped.skipped)
        assertFalse(1 in skipped.skipUndo)
    }

    @Test fun undoSkipRestoresPicksAndOtherAndUnSkips() {
        val skipped = applySkip(empty.copy(picks = mapOf(0 to listOf(1)), other = mapOf(0 to "text")), 0)
        val back = applyUndoSkip(skipped, 0)
        assertEquals(listOf(1), back.picks[0])
        assertEquals("text", back.other[0])
        assertTrue(0 !in back.skipped && 0 !in back.skipUndo)
        assertEquals(skipped, applyUndoSkip(skipped, 5)) // no entry, no change
    }

    @Test fun aPickOrOtherThatLeavesTheSlotAnsweredUnSkipsIt() {
        val skipped = applySkip(empty.copy(picks = mapOf(0 to listOf(1))), 0)
        val picked = applyPick(skipped, 0, multi = false, labelAt = 0)
        assertTrue(0 !in picked.skipped && 0 !in picked.skipUndo)
        val typed = applyOther(skipped, 0, "words")
        assertTrue(0 !in typed.skipped && 0 !in typed.skipUndo)
    }

    @Test fun aWhitespaceOtherDoesNotUnSkip() {
        val skipped = applySkip(empty.copy(picks = mapOf(0 to listOf(1))), 0)
        val typed = applyOther(skipped, 0, "   ")
        assertTrue(0 in typed.skipped)
        assertTrue(0 in typed.skipUndo)
    }

    @Test fun firstUnsettledPageSkipsAnsweredAndSkippedSlots() {
        val sel = empty.copy(picks = mapOf(0 to listOf(0)), skipped = setOf(1))
        assertEquals(2, firstUnsettledPage(sel, listOf(0, 1, 2, 3)))
        assertEquals(-1, firstUnsettledPage(sel.copy(skipped = setOf(1, 2, 3)), listOf(0, 1, 2, 3)))
    }

    @Test fun tabKeysWrapAndHomeEndJump() {
        assertEquals(0, tabKeyTarget(TabKey.Right, 3, 4))
        assertEquals(3, tabKeyTarget(TabKey.Left, 0, 4))
        assertEquals(2, tabKeyTarget(TabKey.Right, 1, 4))
        assertEquals(0, tabKeyTarget(TabKey.Home, 2, 4))
        assertEquals(3, tabKeyTarget(TabKey.End, 0, 4))
        assertNull(tabKeyTarget(TabKey.Right, 0, 0))
    }

    @Test fun theTwoValidationSentencesAreTheWebsWords() {
        assertEquals("Still unanswered: Analytics. Answer it, or choose Skip to leave it unanswered.", unansweredMessage(listOf("Analytics")))
        assertEquals(
            "Still unanswered: Google login, Analytics. Answer each one, or choose Skip to leave it unanswered.",
            unansweredMessage(listOf("Google login", "Analytics")),
        )
        assertEquals("Answer each highlighted question, or choose Skip to leave it unanswered.", SINGLE_UNANSWERED_MESSAGE)
    }

    @Test fun announcementsAndStatusWordsAreTheWebsWords() {
        assertEquals("Question 2 of 4: Google login, answered", moveAnnouncement(1, 4, "Google login", QuestionStatus.Answered))
        assertEquals("Question 3 of 4: Analytics, skipped", moveAnnouncement(2, 4, "Analytics", QuestionStatus.Skipped))
        assertEquals("Question 1 of 4: Crash reports", moveAnnouncement(0, 4, "Crash reports", QuestionStatus.NeedsAnswer))
        assertEquals(
            listOf(", answered", ", skipped", ", needs an answer", ", not answered"),
            listOf(QuestionStatus.Answered, QuestionStatus.Skipped, QuestionStatus.NeedsAnswer, QuestionStatus.Unanswered).map(::statusWords),
        )
    }

    // (a) the skipUndo saver and its budgets (AW.2.9, Hand-off 2)

    @Test fun skipUndoComesBackEqualThroughTheSaver() {
        val store = CardStateStore()
        val sel = applySkip(empty.copy(page = 2, picks = mapOf(1 to listOf(0, 1)), other = mapOf(1 to "mine")), 1)
        assertTrue(sel.skipUndo.isNotEmpty())
        store.setQuestion("fp", sel)
        val back = CardStateStore.decode(store.encode())
        assertEquals(sel, back.question("fp"))
        assertEquals(SkippedAnswer(listOf(0, 1), "mine"), back.question("fp").skipUndo[1])
    }

    @Test fun skipUndoOtherIsCutToTheGuardsLimitAndCountsTowardTheTotal() {
        val limit = com.tether.app.client.ConsentGuard.MAX_OTHER_CHARS
        val store = CardStateStore()
        store.setQuestion("a", empty.copy(skipped = setOf(0), skipUndo = mapOf(0 to SkippedAnswer(emptyList(), "x".repeat(limit + 50)))))
        assertEquals(limit, store.question("a").skipUndo[0]!!.other.length)
        // Enough older records to pass the total with ONLY skipUndo text: the oldest lose it first.
        val perRecord = limit
        val records = CardStateStore.MAX_OTHER_TOTAL / perRecord + 3
        for (i in 0 until records) store.setQuestion("r$i", empty.copy(skipped = setOf(0), skipUndo = mapOf(0 to SkippedAnswer(emptyList(), "y".repeat(perRecord)))))
        val kept = (0 until records).sumOf { store.question("r$it").skipUndo[0]?.other?.length ?: 0 } + (store.question("a").skipUndo[0]?.other?.length ?: 0)
        assertTrue("skipUndo text is within the total: $kept", kept <= CardStateStore.MAX_OTHER_TOTAL)
        assertTrue(store.question("a").skipUndo.isEmpty())
    }
}
