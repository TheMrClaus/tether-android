package com.tether.app.ui.text

/**
 * ta-blf: which of a text's bidi MARKS (LRM U+200E, RLM U+200F, ALM U+061C) prose draws raw;
 * every other one is a token. Isolates, embeddings and overrides are always tokens in prose
 * ([SafeText]), so the marks are the only explicit bidi characters left to decide.
 *
 * The rule (r3, kept in r4): a mark is drawn raw only when its nearest strong neighbour on one side
 * (the nearest letter or digit, across punctuation, spaces and invisibles) is a real RTL letter:
 * Lu/Ll/Lt/Lo of bidi class R or AL, so the geresh, gershayim and tatweel are not. Even then it is
 * a token when it cuts a run in two: letters or digits of the LTR kind on both sides, or an LRM
 * between two RTL letters. This is how RTL text uses marks ("\u05E9\u05DC\u05D5\u05DD!RLM", "C++LRM" before Hebrew,
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
        private const val R: Byte = 2 // an RTL letter

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
                !code && mark(s[i]) -> NONE_C // a mark is judged, not a neighbour
                !code && (SafeText.proseAlways(cp) || SafeText.isTag(cp)) -> L // drawn as a token
                rtlLetter(cp) -> R
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
            var n = 0
            for (k in s.indices) {
                if (!mark(s[k]) || (inert != null && inert[k])) continue
                val l = before[k]
                val r = after[k]
                val cuts = l != NONE_C && l == r && (l == L || s[k] == '\u200E')
                keep[n++] = (l == R || r == R) && !cuts
            }
            return keep
        }
    }
}
