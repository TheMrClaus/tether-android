package com.tether.app.ui.chat

import com.tether.app.protocol.reduce.ev
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T5.3: the pure half of the in-chat find (chat-view.tsx 514-531, 2013-2088; markdown.tsx 83-146,
 * 410-415) — the matcher, the markdown-path count, the per-projection hits in document order, the
 * clamped index, the wrap-around step and the counter's words.
 */
class ChatFindTest {

    // ---- the matcher (markdown.tsx createFindHighlighter) ------------------------------------

    @Test fun matchesAreCaseInsensitiveNonOverlappingLeftToRight() {
        assertEquals(listOf(0..1, 4..5), findRanges("Abxxab", "ab"))
        // "aaaa" / "aa": two, not three (the regex resumes after each match).
        assertEquals(listOf(0..1, 2..3), findRanges("aaaa", "aa"))
        assertEquals(emptyList<IntRange>(), findRanges("abc", ""))
        assertEquals(emptyList<IntRange>(), findRanges("a", "ab"))
    }

    @Test fun caseFoldingIsJsNonUnicodeIgnoreCase() {
        // Non-ASCII letters fold among themselves (É/é) ...
        assertEquals(listOf(0..0), findRanges("É", "é"))
        // ... but never onto ASCII: ſ (long s) uppercases to S, which JS /i without u refuses.
        assertEquals(emptyList<IntRange>(), findRanges("ſ", "s"))
        // ß uppercases to two units ("SS"), so it only matches itself.
        assertEquals(listOf(0..0), findRanges("ß", "ß"))
        assertEquals(emptyList<IntRange>(), findRanges("ss", "ß"))
    }

    @Test fun theNeedleIsTheTrimmedLowercasedQuery() {
        assertEquals("parity pass", findNeedle("  Parity PASS  "))
    }

    // ---- the markdown path (countMarkdownMatches) --------------------------------------------

    @Test fun aMatchInsideMarkupOrALinkUrlIsNotAMatch() {
        // The link's label is searched, its URL is not; `**` markers are consumed first.
        assertEquals(1, countMarkdownMatches("[docs here](https://docs.example/docs)", "docs"))
        assertEquals(0, countMarkdownMatches("**bold**", "**"))
        assertEquals(1, countMarkdownMatches("**bold** text", "bold"))
        // Plain counting sees all three.
        assertEquals(3, countPlainMatches("[docs here](https://docs.example/docs)", "docs"))
    }

    @Test fun fencesInlineCodeTablesAndListsAreSearchedInDocumentOrder() {
        val text = "# Head parity\n\nSee `parity` in:\n\n- parity one\n- two\n\n| parity | b |\n| --- | --- |\n| c | parity |\n\n```\nparity()\n```"
        assertEquals(6, countMarkdownMatches(text, "parity"))
        val blocks = parseMarkdown(text)
        assertEquals(listOf(1, 1, 1, 2, 1), blocks.map { countBlockMatches(it, "parity") })
    }

    @Test fun theChatCorpusMarkdownCountsLikeTheWebWould() {
        // LONG_MARKDOWN (the S0.4 long-markdown reply): "parity" once, inside **bold**; "retry"
        // twice, both in the ts fence; "docs" once in the link label, never in its URL.
        assertEquals(1, countMarkdownMatches(ChatFixtures.LONG_MARKDOWN, "parity"))
        assertEquals(2, countMarkdownMatches(ChatFixtures.LONG_MARKDOWN, "retry"))
        assertEquals(1, countMarkdownMatches(ChatFixtures.LONG_MARKDOWN, "docs"))
        assertEquals(2, countPlainMatches(ChatFixtures.LONG_MARKDOWN, "docs"))
    }

    // ---- which blocks count (countFindOccurrences) --------------------------------------------

    @Test fun onlyChatTextIsSearchedAndAStreamingReplyIsPlainText() {
        val folded = ChatFixtures.streaming
        val turn = folded.projection.turnsById.getValue("t1")
        val user = turn.blocksById.values.single { it.kind == "user_message" }
        val streaming = turn.blocksById.getValue("t1:m1")
        assertEquals(1, countFindOccurrences(user, "test suite"))
        // Still streaming: `reducer` in backticks is searched as plain text (backticks and all).
        assertEquals(1, countFindOccurrences(streaming, "`reducer`"))
        val thinking = ChatFixtures.thinking.projection.turnsById.getValue("t1").blocksById.getValue("t1:th0")
        assertEquals(0, countFindOccurrences(thinking, "retry"))
    }

    // ---- hits over a projection ----------------------------------------------------------------

    private val threeTurns = ChatFixtures.fold(
        *ChatFixtures.turn("t1", "find the parity notes", "Parity is **parity**.", 1_000),
        *ChatFixtures.turn("t2", "nothing here", "still nothing", 2_000),
        *ChatFixtures.turn("t3", "and parity again", "done", 3_000),
    )

    @Test fun hitsAreOccurrencesInDocumentOrderWithTheirTurnCount() {
        val results = findResults(threeTurns.projection, "parity", open = true)
        assertEquals(
            listOf(
                FindHit("t1", threeTurns.projection.turnsById.getValue("t1").blocks[0], 0),
                FindHit("t1", "t1:m0", 0),
                FindHit("t1", "t1:m0", 1),
                FindHit("t3", threeTurns.projection.turnsById.getValue("t3").blocks[0], 0),
            ),
            results.hits,
        )
        assertEquals(2, results.turnCount)
        assertEquals(3, results.blocksWithHits.size)
    }

    @Test fun aClosedBarOrAShortNeedleFindsNothing() {
        assertSame(FindResults.Empty, findResults(threeTurns.projection, "parity", open = false))
        assertSame(FindResults.Empty, findResults(threeTurns.projection, "p", open = true))
        assertSame(FindResults.Empty, findResults(null, "parity", open = true))
    }

    @Test fun trimmedTurnsOfABoundedSnapshotHoldNoHits() {
        // v115: the trimmed turns carry no blocks, so only the loaded tail is searched (as on the web).
        val results = findResults(ChatFixtures.boundedOf(5, trimmed = 3).projection, "prompt", open = true)
        assertEquals(listOf("t4", "t5"), results.hits.map { it.turnId })
    }

    @Test fun theIndexIsClampedAndTheStepWraps() {
        val results = findResults(threeTurns.projection, "parity", open = true)
        assertEquals(3, activeHitIndex(results, 99))
        assertEquals(-1, activeHitIndex(FindResults.Empty, 0))
        assertEquals(1, stepMatch(results, 0, 1))
        assertEquals(3, stepMatch(results, 0, -1))
        assertEquals(0, stepMatch(results, 3, 1))
        // From the CLAMPED index: a shrunken set does not skip.
        assertEquals(2, stepMatch(results, 99, -1))
        assertEquals(5, stepMatch(FindResults.Empty, 5, 1))
    }

    @Test fun theCounterSaysWhereTheActiveMatchIsInWords() {
        val results = findResults(threeTurns.projection, "parity", open = true)
        assertEquals(FindCount("", ""), findCount("p", results, 0))
        assertEquals(FindCount("No matches", ""), findCount("zzz", findResults(threeTurns.projection, "zzz", true), 0))
        assertEquals(FindCount("1 of 4", " · turn 1 of 2"), findCount("parity", results, 0))
        assertEquals(FindCount("4 of 4", " · turn 2 of 2"), findCount("parity", results, 3))
        assertEquals(FindCount("4 of 4", " · turn 2 of 2"), findCount("parity", results, 42))
    }

    @Test fun onlyBlocksWithHitsGetMarksAndOnlyTheActiveOneAnOrdinal() {
        val results = findResults(threeTurns.projection, "parity", open = true)
        val active = results.hits[2]
        assertEquals(FindMarks("parity", 1), findMarksFor(results, "parity", active, "t1", "t1:m0"))
        assertEquals(FindMarks("parity", -1), findMarksFor(results, "parity", active, "t3", results.hits[3].blockId))
        assertNull(findMarksFor(results, "parity", active, "t2", "t2:m0"))
    }

    @Test fun theCounterCacheFollowsBlockIdentityAndTheNeedle() {
        val counter = FindCounter()
        val first = findResults(threeTurns.projection, "parity", true, counter)
        // A streamed delta replaces only the touched block: the others keep their cached count.
        val more = ChatFixtures.fold(
            *ChatFixtures.turn("t1", "find the parity notes", "Parity is **parity**.", 1_000),
            *ChatFixtures.turn("t2", "nothing here", "still nothing", 2_000),
            *ChatFixtures.turn("t3", "and parity again", "done", 3_000),
            ev("turn_started", "t4", ts = 4_000) { put("idempotencyKey", "k-t4") },
            ev("user_message_accepted", "t4", ts = 4_000) { put("text", "one more parity") },
        )
        val second = findResults(more.projection, "parity", true, counter)
        assertEquals(first.hits.size + 1, second.hits.size)
        assertTrue(findResults(more.projection, "notes", true, counter).hits.size == 1)
    }
}
