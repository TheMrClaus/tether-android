package com.tether.app.client

/**
 * ta-28i: where a server or agent text may be CUT to a bound without breaking what the reader
 * sees. `String.take(n)` / `substring(0, n)` cut at a UTF-16 index, so they can split a surrogate
 * pair (a lone surrogate draws as a replacement box, or as a `U+D83D` token under the code rule),
 * strip a base letter's combining accent, pull a skin tone off its emoji, split an emoji ZWJ
 * sequence or a flag's regional-indicator pair. This cuts only at a boundary where none of that can
 * happen: a conservative, dependency-free approximation of the UAX #29 extended grapheme cluster
 * boundary, which never cuts INSIDE a cluster (it may cut slightly earlier than ICU would).
 *
 * Tokens: a caller that draws tokens (the SafeText rules, [LabelText.visibleValue]) always cuts
 * the SOURCE text here first and encodes after, so a token is never cut in half.
 *
 * Cost (r2): BOUNDED. A cut backs off at most [MAX_BACKOFF] code points (never below its floor);
 * a cluster longer than that (a flood of combining marks, a long ZWJ chain) is cut at a code point
 * instead, never inside a surrogate pair. The regional-indicator pairing looks back at most
 * [MAX_RI_SCAN] indicators (a longer run of them may pair from the wrong one: it is not text a
 * reader can tell apart anyway). So a cut costs O(MAX_BACKOFF x MAX_RI_SCAN) at worst, whatever
 * the text, and cutting a 1 MiB line into pieces is linear.
 */
object TextCut {
    /** Most code points a cut backs off looking for a cluster boundary. */
    const val MAX_BACKOFF = 64

    /** Most regional indicators the flag pairing looks back over. */
    const val MAX_RI_SCAN = 64

    /**
     * Test seam (r2): while set, every boundary test and every regional-indicator step adds one, so a
     * test can show the work is linear without timing anything.
     */
    @Volatile
    var stepProbe: java.util.concurrent.atomic.AtomicLong? = null

    /** [s] cut to at most [max] UTF-16 units, at a cluster boundary ([boundaryAtOrBefore]). */
    fun cut(s: String, max: Int): String {
        if (s.length <= max) return s
        return s.substring(0, boundaryAtOrBefore(s, max.coerceAtLeast(0)))
    }

    /**
     * Where [s] may be cut at or before [index]: the nearest cluster boundary within [MAX_BACKOFF]
     * code points above [floor], else (a cluster too long to keep whole) a code-point cut at [index]
     * that never splits a surrogate pair. 0 and `s.length` always are boundaries.
     */
    fun boundaryAtOrBefore(s: CharSequence, index: Int, floor: Int = 0): Int {
        val hi = index.coerceIn(0, s.length)
        if (hi == 0 || hi == s.length) return hi
        val lo = floor.coerceIn(0, hi)
        var p = hi
        var steps = 0
        while (p > lo && steps < MAX_BACKOFF) {
            if (isBoundary(s, p)) return p
            p = previousStart(s, p)
            steps++
        }
        if (p == 0) return 0
        // No boundary near enough: a code point, never half a pair (and above the floor when it can be).
        val cp = if (Character.isLowSurrogate(s[hi]) && Character.isHighSurrogate(s[hi - 1])) hi - 1 else hi
        return if (cp > lo) cp else hi
    }

    /** Whether a cut between `s[i - 1]` and `s[i]` keeps every cluster whole. */
    fun isBoundary(s: CharSequence, i: Int): Boolean {
        stepProbe?.incrementAndGet()
        if (i <= 0 || i >= s.length) return true
        // Never between the two halves of a pair.
        if (Character.isLowSurrogate(s[i]) && Character.isHighSurrogate(s[i - 1])) return false
        val next = Character.codePointAt(s, i)
        if (extendsPrevious(next)) return false
        val prev = Character.codePointBefore(s, i)
        // An emoji ZWJ sequence: the ZWJ joins what follows it.
        if (prev == ZWJ) return false
        // Hangul syllable blocks: a vowel or final jamo joins the jamo before it.
        if (hangulLeading(prev) && (hangulLeading(next) || hangulVowel(next))) return false
        // Regional indicators pair up from the start of their run: between the two of a pair, no.
        if (regional(next) && regional(prev)) {
            var run = 0
            var k = i
            while (k > 0 && run < MAX_RI_SCAN) {
                stepProbe?.incrementAndGet()
                val cp = Character.codePointBefore(s, k)
                if (!regional(cp)) break
                run++
                k -= Character.charCount(cp)
            }
            if (run % 2 == 1) return false
        }
        return true
    }

    private const val ZWJ = 0x200D

    /** A code point that belongs to the cluster before it (UAX #29 Extend, SpacingMark, ZWJ, V/T jamo). */
    fun extendsPrevious(cp: Int): Boolean {
        when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> return true
        }
        return cp == ZWJ || cp == 0x200C || // ZWJ, ZWNJ (Extend)
            cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || // variation selectors
            cp in 0x1F3FB..0x1F3FF || // emoji skin-tone modifiers
            cp in 0xE0020..0xE007F || // tag characters (subdivision flags)
            hangulVowel(cp) || hangulTrailing(cp)
    }

    private fun regional(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    private fun hangulLeading(cp: Int): Boolean = cp in 0x1100..0x115F || cp in 0xA960..0xA97C

    private fun hangulVowel(cp: Int): Boolean = cp in 0x1160..0x11A7 || cp in 0xD7B0..0xD7C6

    private fun hangulTrailing(cp: Int): Boolean = cp in 0x11A8..0x11FF || cp in 0xD7CB..0xD7FB

    private fun previousStart(s: CharSequence, p: Int): Int = p - Character.charCount(Character.codePointBefore(s, p))
}
