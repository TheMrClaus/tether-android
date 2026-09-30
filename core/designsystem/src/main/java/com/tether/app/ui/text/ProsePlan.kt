package com.tether.app.ui.text

/**
 * ta-blf: which of a text's bidi MARKS (LRM U+200E, RLM U+200F, ALM U+061C) prose draws raw;
 * every other one is a token. Isolates, embeddings and overrides are always tokens in prose
 * ([SafeText]), so the marks are the only explicit bidi characters left to decide.
 *
 * The rule (r3, kept in r4, r5 for ALM): a mark is drawn raw only when its nearest strong neighbour
 * on one side (the nearest letter or digit, across punctuation, spaces and invisibles) is a real
 * RTL letter: Lu/Ll/Lt/Lo of bidi class R or AL, so the geresh, gershayim and tatweel are not. An
 * ALM needs an ARABIC-class (AL) letter there, and is judged by bidi class as UAX #9 W2 applies it:
 * the strong character before it (a letter or not: a geresh is class R) must be AL, or the one
 * after it AL with no digit in between. It is class AL itself, so anywhere else it turns European
 * digits Arabic ("2024-01-02" beside a Hebrew letter draws "02-01-2024"). Even then it is
 * a token when it cuts a run in two: letters or digits of the LTR kind on both sides, or an LRM
 * between two RTL letters; and when its nearest strong neighbour is another mark (r5: stacked
 * marks, with no letter between them, judge each other, so they fail closed). This is how RTL text uses marks ("\u05E9\u05DC\u05D5\u05DD!RLM", "C++LRM" before Hebrew,
 * "30%ALM" in Arabic), and it cannot cut a Latin word or a number apart.
 *
 * A plan is built over one rendered UNIT (a bubble's text, a markdown line with all its inline
 * pieces) and consumed, mark by mark and in order, as the unit's prose pieces are encoded
 * ([SafeText.encode] with the plan). [Segment.code] pieces (inline code) are drawn by the code rule:
 * they take no decision, but their letters count (they are on screen), and their controls count as
 * the visible token text they become. Lines split at '\n', the only bidi paragraph break an Android
 * layout makes (prose draws U+2028 / U+2029 as tokens). A plan that runs out of decisions says
 * "token": failing closed.
 */
class ProsePlan private constructor(private val keep: BooleanArray) {
    private var next = 0

    /** The next mark's fate: true = drawn raw. */
    fun take(): Boolean {
        val k = next < keep.size && keep[next]
        next++
        return k
    }

    /** One piece of a unit, in reading order; [code] = drawn by the code rule. */
    class Segment(val text: String, val code: Boolean = false)

    companion object {
        private val NONE = BooleanArray(0)

        fun of(text: String): ProsePlan = if (text.none { mark(it) }) ProsePlan(NONE) else ProsePlan(analyze(text, null))

        fun of(segments: List<Segment>): ProsePlan {
            if (segments.none { !it.code && it.text.any { c -> mark(c) } }) return ProsePlan(NONE)
            val sb = StringBuilder()
            val codeRanges = ArrayList<IntRange>()
            for (seg in segments) {
                if (seg.code && seg.text.isNotEmpty()) codeRanges.add(sb.length until sb.length + seg.text.length)
                sb.append(seg.text)
            }
            val s = sb.toString()
            val inert = if (codeRanges.isEmpty()) null else BooleanArray(s.length).also { a -> for (r in codeRanges) for (k in r) a[k] = true }
            return ProsePlan(analyze(s, inert))
        }

        private const val NONE_C: Byte = 0
        private const val L: Byte = 1 // an LTR letter, a digit, or a drawn token (it holds "U+" and hex)
        private const val R: Byte = 2 // an RTL letter of bidi class R (Hebrew and the like)
        private const val A: Byte = 3 // an RTL letter of bidi class AL (Arabic script)
        private const val M: Byte = 4 // another mark (r5): its own fate is not known here

        private fun mark(c: Char): Boolean = c == '\u200E' || c == '\u200F' || c == '\u061C'

        /** A real letter: Lu, Ll, Lt or Lo (not Lm: the tatweel is a modifier letter). */
        internal fun realLetter(cp: Int): Boolean = when (Character.getType(cp)) {
            Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
            Character.OTHER_LETTER.toInt(),
            -> true
            else -> false
        }

        /** An RTL letter: a real letter of bidi class R or AL. */
        internal fun rtlLetter(cp: Int): Boolean {
            if (!realLetter(cp)) return false
            val d = Character.getDirectionality(cp)
            return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        }

        /** The strong class of the code point at [i]: L, R, or none (everything else is looked across). */
        private fun strong(s: String, inert: BooleanArray?, i: Int, cp: Int): Byte {
            val code = inert != null && inert[i]
            return when {
                code && SafeText.codeEscapes(cp) -> L // drawn as a token
                !code && mark(s[i]) -> M // another mark: a neighbour of unknown kind
                !code && (SafeText.proseAlways(cp) || SafeText.isTag(cp)) -> L // drawn as a token
                rtlLetter(cp) -> if (Character.getDirectionality(cp) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) A else R
                realLetter(cp) || Character.isDigit(cp) -> L
                else -> NONE_C
            }
        }

        private fun analyze(s: String, inert: BooleanArray?): BooleanArray {
            var count = 0
            for (i in s.indices) if (mark(s[i]) && (inert == null || !inert[i])) count++
            val keep = BooleanArray(count)
            if (count == 0) return keep
            // The strong class of each index, by UTF-16 index (a pair's low half: none).
            val cls = ByteArray(s.length)
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                cls[i] = strong(s, inert, i, cp)
                i += Character.charCount(cp)
            }
            // Nearest strong before / after each index, within its line.
            val before = ByteArray(s.length)
            val after = ByteArray(s.length)
            var cur = NONE_C
            for (k in s.indices) {
                if (s[k] == '\n') cur = NONE_C
                before[k] = cur
                if (cls[k] != NONE_C) cur = cls[k]
            }
            cur = NONE_C
            for (k in s.indices.reversed()) {
                if (s[k] == '\n') cur = NONE_C
                after[k] = cur
                if (cls[k] != NONE_C) cur = cls[k]
            }
            // r5: for ALM, the nearest STRONG BIDI CLASS before / after (letters or not: a geresh is class R),
            // as UAX #9 W2 sees it, and whether European digits come before the next one.
            val bidiCls = ByteArray(s.length)
            i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val code = inert != null && inert[i]
                bidiCls[i] = when {
                    (code && SafeText.codeEscapes(cp)) || (!code && !mark(s[i]) && (SafeText.proseAlways(cp) || SafeText.isTag(cp))) -> L
                    // Another mark counts as what it may be, never as AL: two ALMs cannot vouch for each other.
                    !code && s[i] == '\u061C' -> R
                    else -> when (Character.getDirectionality(cp)) {
                        Character.DIRECTIONALITY_LEFT_TO_RIGHT -> L
                        Character.DIRECTIONALITY_RIGHT_TO_LEFT -> R
                        Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> A
                        else -> NONE_C
                    }
                }
                i += Character.charCount(cp)
            }
            val prevBidi = ByteArray(s.length)
            val nextBidi = ByteArray(s.length)
            val digitsAhead = BooleanArray(s.length) // a European digit before the next strong character
            cur = NONE_C
            for (k in s.indices) {
                if (s[k] == '\n') cur = NONE_C
                prevBidi[k] = cur
                if (bidiCls[k] != NONE_C) cur = bidiCls[k]
            }
            cur = NONE_C
            var digits = false
            for (k in s.indices.reversed()) {
                if (s[k] == '\n') {
                    cur = NONE_C
                    digits = false
                }
                nextBidi[k] = cur
                digitsAhead[k] = digits
                if (bidiCls[k] != NONE_C) {
                    cur = bidiCls[k]
                    digits = false
                } else if (Character.getDirectionality(s[k]) == Character.DIRECTIONALITY_EUROPEAN_NUMBER) {
                    digits = true
                }
            }
            var n = 0
            for (k in s.indices) {
                if (!mark(s[k]) || (inert != null && inert[k])) continue
                val l = before[k]
                val r = after[k]
                val rtl = { c: Byte -> c == R || c == A }
                // r5: a mark next to another mark (no letter between them) is a token: each would be
                // judged against the other, and one of them drawn as a token is Latin-like text.
                if (l == M || r == M) {
                    keep[n++] = false
                    continue
                }
                val cuts = (l == L && r == L) || (s[k] == '\u200E' && rtl(l) && rtl(r))
                // r5: an ALM is itself class AL, so it turns the European digits after it into Arabic
                // ones (UAX #9 W2), and "-", "+", "$", "#" between them stop joining them: beside a
                // Hebrew letter that draws "2024-01-02" as "02-01-2024". So an ALM is kept only beside
                // an Arabic-class letter, where that is how the text already behaves.
                // The ALM's own test is by bidi CLASS, as W2 applies it: the strong character before it is
                // AL (W2 already makes those digits Arabic), or the one after it is AL with no digit in
                // between (nothing for it to change).
                val beside = if (s[k] == '\u061C') {
                    (rtl(l) || rtl(r)) && (prevBidi[k] == A || (nextBidi[k] == A && !digitsAhead[k]))
                } else {
                    rtl(l) || rtl(r)
                }
                keep[n++] = beside && !cuts
            }
            return keep
        }
    }
}
