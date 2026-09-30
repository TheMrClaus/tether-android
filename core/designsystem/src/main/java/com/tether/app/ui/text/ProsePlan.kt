package com.tether.app.ui.text

/**
 * ta-blf: which of a text's isolates (U+2066-U+2069) and marks (U+200E, U+200F, U+061C) prose
 * draws raw; every other one is a token. The rules are in [SafeText]'s doc; this is the analysis.
 *
 * A plan is built over one rendered UNIT (a bubble's text, a markdown line with all its inline
 * pieces) and consumed, occurrence by occurrence and in order, as the unit's prose pieces are
 * encoded ([SafeText.encode] with the plan). [Segment.code] pieces (inline code) are drawn by the
 * code rule: they take no decision, but their letters count (they are on screen), and their
 * controls count as the visible token text they become. Lines are split at '\n', the only bidi
 * paragraph break an Android layout makes (prose draws U+2028 / U+2029 as tokens, so no other
 * separator can hide inside a line). A plan that runs out of decisions says "token": fail closed.
 *
 * Classes, per code point (r3): an RTL LETTER is a letter (Lu/Ll/Lt/Lo, not a modifier letter)
 * of bidi class R or AL, so the Hebrew geresh / gershayim and the Arabic tatweel are not; a WORD
 * GAP is U+0020, U+00A0, U+3000 or a tab only (a hair or thin space is not a gap between words).
 */
class ProsePlan private constructor(private val keep: BooleanArray) {
    private var next = 0

    /** The next conditional control's fate: true = drawn raw. */
    fun take(): Boolean {
        val k = next < keep.size && keep[next]
        next++
        return k
    }

    /** One piece of a unit, in reading order; [code] = drawn by the code rule. */
    class Segment(val text: String, val code: Boolean = false)

    companion object {
        private val NONE = BooleanArray(0)

        fun of(text: String): ProsePlan = if (!hasConditional(text)) ProsePlan(NONE) else ProsePlan(analyze(text, null))

        fun of(segments: List<Segment>): ProsePlan {
            if (segments.none { !it.code && hasConditional(it.text) }) return ProsePlan(NONE)
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

        private fun hasConditional(s: String): Boolean = s.any { conditional(it) }

        private const val NONE_C: Byte = 0
        private const val L: Byte = 1 // an LTR letter, or a drawn token (it holds "U+" and hex)
        private const val R: Byte = 2 // an RTL letter
        private const val W: Byte = 3 // a word gap
        private const val P: Byte = 4 // other visible: punctuation, symbols, other spaces, R-class non-letters
        private const val F: Byte = 5 // invisible: format, combining marks
        private const val X: Byte = 6 // a conditional control itself
        private const val D: Byte = 7 // a digit

        private const val LRI = '\u2066'
        private const val RLI = '\u2067'
        private const val PDI = '\u2069'
        private const val LRM = '\u200E'

        private fun conditional(c: Char): Boolean = c == '\u200E' || c == '\u200F' || c == '\u061C' || c in '\u2066'..'\u2069'

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

        private fun base(cp: Int): Byte {
            when (Character.getType(cp)) {
                Character.FORMAT.toInt(), Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                -> return F
            }
            if (cp == 0x20 || cp == 0xA0 || cp == 0x3000 || cp == 0x09) return W
            if (realLetter(cp)) return if (rtlLetter(cp)) R else L
            if (Character.isDigit(cp)) return D
            return P
        }

        /** The classes of s[a, b), by UTF-16 index (a pair's low half takes the pair's class). */
        private fun classes(s: String, inert: BooleanArray?, a: Int, b: Int): ByteArray {
            val cls = ByteArray(b - a)
            var i = a
            while (i < b) {
                val cp = s.codePointAt(i)
                val len = Character.charCount(cp)
                val code = inert != null && inert[i]
                val c: Byte = when {
                    // Drawn as a visible token (it holds "U+" and hex): an LTR letter.
                    code && SafeText.codeEscapes(cp) -> L
                    !code && (SafeText.proseAlways(cp) || SafeText.isTag(cp)) -> L
                    !code && conditional(s[i]) -> X
                    else -> base(cp)
                }
                for (q in i until minOf(i + len, b)) cls[q - a] = c
                i += len
            }
            return cls
        }

        private fun analyze(s: String, inert: BooleanArray?): BooleanArray {
            var count = 0
            for (i in s.indices) if (conditional(s[i]) && (inert == null || !inert[i])) count++
            val keep = BooleanArray(count)
            if (count == 0) return keep
            var occ = 0
            var a = 0
            while (a <= s.length) {
                var b = s.indexOf('\n', a)
                if (b < 0) b = s.length
                var here = 0
                for (i in a until b) if (conditional(s[i]) && (inert == null || !inert[i])) here++
                if (here > 0) analyzeLine(s, a, b, classes(s, inert, a, b), keep, occ)
                occ += here
                a = b + 1
            }
            return keep
        }

        /** Nearest class in [want] before / after each index, skipping the rest; [reset] clears it. */
        private fun nearest(cls: ByteArray, map: (Byte) -> Byte, reset: (Byte) -> Boolean): Pair<ByteArray, ByteArray> {
            val n = cls.size
            val before = ByteArray(n)
            val after = ByteArray(n)
            var cur = NONE_C
            for (k in 0 until n) {
                before[k] = cur
                val m = map(cls[k])
                if (m != NONE_C) cur = m else if (reset(cls[k])) cur = NONE_C
            }
            cur = NONE_C
            for (k in n - 1 downTo 0) {
                after[k] = cur
                val m = map(cls[k])
                if (m != NONE_C) cur = m else if (reset(cls[k])) cur = NONE_C
            }
            return before to after
        }

        private fun analyzeLine(s: String, a: Int, b: Int, cls: ByteArray, keep: BooleanArray, firstOcc: Int) {
            // No RTL letter on the line: every conditional control stays a token (the default).
            if (cls.none { it == R }) return
            val len = b - a
            val ld: (Byte) -> Byte = { c -> if (c == L || c == D) L else if (c == R) R else NONE_C }
            // Marks: the nearest strong (letter or digit) on each side, across anything.
            val (prevStrong, nextStrong) = nearest(cls, ld) { false }
            // Isolate edges: the nearest letter or digit, across punctuation and invisibles, not across a word gap.
            val (prevJoin, nextJoin) = nearest(cls, ld) { it == W }
            // Swaps: the nearest LETTER outside (digits are weak), across anything.
            val (prevLetter, nextLetter) = nearest(cls, { c -> if (c == L || c == R) c else NONE_C }) { false }
            val cntL = IntArray(len + 1)
            val cntR = IntArray(len + 1)
            for (k in 0 until len) {
                cntL[k + 1] = cntL[k] + if (cls[k] == L || cls[k] == D) 1 else 0
                cntR[k + 1] = cntR[k] + if (cls[k] == R) 1 else 0
            }

            // The line's conditional controls, with their plan indices.
            val pos = ArrayList<Int>()
            for (k in 0 until len) if (cls[k] == X) pos.add(k)
            fun occOf(n: Int) = firstOcc + n

            // 1. Marks: kept only beside a real RTL letter, and never cutting a run in two.
            val markKept = BooleanArray(len)
            for ((n, k) in pos.withIndex()) {
                val c = s[a + k]
                if (c in LRI..PDI) continue
                val l = prevStrong[k]
                val r = nextStrong[k]
                val cuts = l != NONE_C && l == r && (l == L || c == LRM)
                val kept = (l == R || r == R) && !cuts
                keep[occOf(n)] = kept
                markKept[k] = kept
            }

            // 2. Isolate pairs (nested ones and stray PDIs stay tokens: the default).
            class Pair2(val open: Int, val openN: Int, var close: Int = -1, var closeN: Int = -1)
            val top = ArrayList<Pair2>()
            val stack = ArrayList<Pair2?>()
            for ((n, k) in pos.withIndex()) {
                when (s[a + k]) {
                    in LRI..'\u2068' -> stack.add(if (stack.isEmpty()) Pair2(k, n).also { top.add(it) } else null)
                    PDI -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)?.let { it.close = k; it.closeN = n }
                }
            }

            // 3. The paragraph's direction (UAX #9 P2): the first strong outside every isolate; a mark
            // drawn as a token, like any token, reads as Latin.
            val inside = BooleanArray(len)
            for (p in top) for (k in p.open..(if (p.close >= 0) p.close else len - 1)) inside[k] = true
            var para = NONE_C
            for (k in 0 until len) {
                if (inside[k]) continue
                val c = cls[k]
                para = when {
                    c == L || c == R -> c
                    c == X && markKept[k] -> if (s[a + k] == LRM) L else R
                    c == X -> L
                    else -> continue
                }
                break
            }

            // 4. Each top-level isolate.
            for (p in top) {
                val from = p.open + 1
                val to = if (p.close >= 0) p.close else len
                val hasL = cntL[to] - cntL[from] > 0
                val hasR = cntR[to] - cntR[from] > 0
                val kind = s[a + p.open]
                var firstLetter = NONE_C
                for (k in from until to) if (cls[k] == L || cls[k] == R) {
                    firstLetter = cls[k]
                    break
                }
                val dir: Byte = when (kind) {
                    LRI -> L
                    RLI -> R
                    else -> if (firstLetter == NONE_C) L else firstLetter // FSI; none: LTR (P3)
                }
                // Its content must be of its own direction; an LTR island of neutrals only (an emoji) is allowed.
                val own = when (kind) {
                    RLI -> hasR
                    LRI -> hasL || !hasR
                    else -> true
                }
                val splits = (prevJoin[p.open] != NONE_C && prevJoin[p.open] == nextJoin[p.open]) ||
                    (p.close >= 0 && prevJoin[p.close] != NONE_C && prevJoin[p.close] == nextJoin[p.close])
                // A swap: in a paragraph of the other direction, an isolate beside a letter of its own
                // direction orders against it. The line's edge reads as the paragraph (unknown: a match).
                val edge = if (para == NONE_C) dir else para
                val left = prevLetter[p.open].takeIf { it != NONE_C } ?: edge
                val right = if (p.close >= 0) nextLetter[p.close].takeIf { it != NONE_C } ?: edge else edge
                val swaps = (para == NONE_C || para != dir) && (left == dir || right == dir)
                val k = own && !splits && !swaps
                keep[occOf(p.openN)] = k
                if (p.closeN >= 0) keep[occOf(p.closeN)] = k
            }
        }
    }
}
