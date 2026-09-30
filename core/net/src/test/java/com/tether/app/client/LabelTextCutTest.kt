package com.tether.app.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-28i: the label rule and [TextCut] cut only at a character-cluster boundary: never a lone
 * surrogate, an accent without its letter, a skin tone without its emoji, half a flag, half a ZWJ
 * sequence, or half an escape; legitimate Arabic and Hebrew stay whole and readable.
 */
class LabelTextCutTest {
    private fun noLoneSurrogate(s: String) {
        for (i in s.indices) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) assertTrue("lone high surrogate at $i in ${hex(s)}", i + 1 < s.length && Character.isLowSurrogate(s[i + 1]))
            if (Character.isLowSurrogate(c)) assertTrue("lone low surrogate at $i in ${hex(s)}", i > 0 && Character.isHighSurrogate(s[i - 1]))
        }
    }

    private fun hex(s: String) = s.map { "%04X".format(it.code) }

    @Test fun aSurrogatePairAtTheEdgeIsNeverSplit() {
        // "ab" + an emoji: a cut at 3 would keep only the high half.
        assertEquals("ab", TextCut.cut("ab\uD83D\uDE00c", 3))
        assertEquals("ab\uD83D\uDE00", TextCut.cut("ab\uD83D\uDE00c", 4))
        for (max in 0..12) noLoneSurrogate(TextCut.cut("x\uD83D\uDE00".repeat(4), max))
    }

    @Test fun aCombiningSequenceIsNeverSplit() {
        // "e" + COMBINING ACUTE: cutting after the "e" would drop the accent and show a plain "e".
        assertEquals("caf", TextCut.cut("cafe\u0301!", 4))
        assertEquals("cafe\u0301", TextCut.cut("cafe\u0301!", 5))
        // Arabic with harakat: a letter keeps its marks or goes with them.
        val arabic = "\u0645\u064E\u0631\u0652\u062D\u064E\u0628\u064B\u0627" // marhaban, vowelled
        for (max in 0..arabic.length) {
            val cut = TextCut.cut(arabic, max)
            assertTrue(arabic.startsWith(cut))
            if (cut.length < arabic.length) assertFalse("a mark was cut from its letter at $max", TextCut.extendsPrevious(arabic[cut.length].code))
        }
        // A short cluster at the edge goes whole; a pathological run of marks (a cluster longer than
        // the bounded back-off, r2) is cut at a code point instead, never splitting a pair.
        assertEquals("ok ", TextCut.cut("ok a" + "\u0301".repeat(20), 10))
        val flood = TextCut.cut("ok a" + "\u0301".repeat(500), 100)
        assertEquals(100, flood.length)
        assertTrue(flood.startsWith("ok a\u0301"))
    }

    @Test fun emojiSequencesAndFlagsStayWhole() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67" // man ZWJ woman ZWJ girl
        for (max in 1 until family.length) assertEquals("cut at $max", "", TextCut.cut(family, max))
        val thumb = "\uD83D\uDC4D\uD83C\uDFFD" // thumbs up + medium skin tone
        assertEquals("", TextCut.cut(thumb, 2))
        val flags = "\uD83C\uDDE9\uD83C\uDDEA\uD83C\uDDEB\uD83C\uDDF7" // DE FR
        assertEquals("", TextCut.cut(flags, 2))
        assertEquals("\uD83C\uDDE9\uD83C\uDDEA", TextCut.cut(flags, 4))
        assertEquals("\uD83C\uDDE9\uD83C\uDDEA", TextCut.cut(flags, 6))
        val keycap = "1\uFE0F\u20E3"
        assertEquals("", TextCut.cut(keycap, 2))
    }

    @Test fun theLabelRuleCutsAtAClusterAndDropsBidiControls() {
        // 79 x's and an emoji right at the edge: the "\u2026" never follows half of it.
        val title = LabelText.clean("x".repeat(78) + "\uD83D\uDE00" + "tail", 80)
        noLoneSurrogate(title)
        assertEquals("x".repeat(78) + "\u2026", title)
        val accent = LabelText.clean("x".repeat(78) + "e\u0301" + "tail", 80)
        assertEquals("x".repeat(78) + "\u2026", accent)
        // RLO / LRI / PDI / marks are dropped: the words read in their stored order.
        assertEquals("Fix the parser bug", LabelText.title("Fix the \u202Eparser\u202C bug"))
        assertEquals("mv old new", LabelText.title("mv \u2066old\u2069 \u2067new\u2069"))
        assertEquals("ab", LabelText.title("a\u200B\u200E\u2060b"))
        // Legitimate RTL text keeps every letter and its order.
        val hebrew = "\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD"
        val arabic = "\u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645"
        assertEquals(hebrew, LabelText.title(hebrew))
        assertEquals(arabic, LabelText.title(arabic))
        assertEquals(LabelText.MAX_TITLE, LabelText.title("y".repeat(10_000)).length)
        // A title of nothing but invisibles is spelled out, never an empty row.
        assertEquals("\\u{202E}\\u{200B}", LabelText.title("\u202E\u200B"))
        assertEquals("", LabelText.title("   "))
    }

    @Test fun aVisibleValueNeverCutsAnEscapeOrAPairInHalf() {
        for (pad in 0..10) {
            val v = LabelText.visibleValue("a".repeat(pad) + "\u200B".repeat(40))
            val body = v.substringBeforeLast("\u2026#")
            // Every escape that is shown is whole.
            assertTrue(v, Regex("^a*(\\\\u\\{200B\\})*$").matches(body))
        }
        for (pad in 0..4) {
            val v = LabelText.visibleValue("b".repeat(pad) + "\uD83D\uDE00".repeat(60))
            noLoneSurrogate(v)
        }
        // A doubled backslash is one unit: never left as a single "\" before the cut.
        for (pad in 0..4) {
            val v = LabelText.visibleValue("c".repeat(pad) + "\\".repeat(60))
            val body = v.substringBeforeLast("\u2026#")
            assertEquals(v, 0, body.count { it == '\\' } % 2)
        }
    }

    /** r2: cutting stays linear on a hostile 1 MiB text: the back-off and the flag pairing are bounded. */
    @Test fun cuttingAHostileMegabyteIsLinear() {
        val n = 1 shl 20
        val floods = mapOf(
            "combining marks" to "a" + "\u0301".repeat(n - 1),
            "zwj chain" to "\uD83D\uDC68\u200D".repeat(n / 3),
            "regional indicators" to "\uD83C\uDDE9".repeat(n / 2),
        )
        for ((what, text) in floods) {
            val probe = java.util.concurrent.atomic.AtomicLong()
            TextCut.stepProbe = probe
            try {
                // Cut the whole text into 4,000-unit pieces, as the file preview does.
                var start = 0
                var pieces = 0
                while (start < text.length) {
                    var end = TextCut.boundaryAtOrBefore(text, minOf(text.length, start + 4_000), floor = start)
                    if (end <= start) end = minOf(text.length, start + 4_000)
                    assertFalse("$what: a pair split at $end", end < text.length && Character.isLowSurrogate(text[end]) && Character.isHighSurrogate(text[end - 1]))
                    start = end
                    pieces++
                }
                assertTrue("$what: $pieces pieces", pieces <= text.length / 3_900 + 1)
                // At most MAX_BACKOFF boundary tests per piece, each with at most MAX_RI_SCAN steps.
                val bound = pieces.toLong() * (TextCut.MAX_BACKOFF + 1) * (TextCut.MAX_RI_SCAN + 1)
                assertTrue("$what: ${probe.get()} steps > $bound", probe.get() <= bound)
                assertTrue("$what: ${probe.get()} steps for ${text.length} units", probe.get() <= 8L * text.length)
            } finally {
                TextCut.stepProbe = null
            }
        }
    }
}
