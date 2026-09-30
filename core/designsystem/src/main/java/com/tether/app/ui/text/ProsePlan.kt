package com.tether.app.ui.text

/**
 * ta-blf r2: which of a text's isolates (U+2066-U+2069) and marks (U+200E, U+200F, U+061C) prose
 * draws raw; every other one is a token. The rules are in [SafeText]'s doc; this is the analysis.
 *
 * A plan is built over one rendered UNIT (a bubble's text, a markdown line with all its inline
 * pieces) and consumed, occurrence by occurrence and in order, as the unit's prose pieces are
 * encoded ([SafeText.encode] with the plan). [Segment.code] pieces (inline code) are drawn by the
 * code rule: they take no decision, but their letters count (they are on screen), and their
 * controls count as the visible token text they become. Lines are split at '\n' (a new bidi
 * paragraph on Android). A plan that runs out of decisions says "token": failing closed.
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

        private fun hasConditional(s: String): Boolean = s.any { it == '\u200E' || it == '\u200F' || it == '\u061C' || it in '\u2066'..'\u2069' }

        private const val NONE_C: Byte = 0
        private const val L: Byte = 1 // an LTR letter or any digit
        private const val R: Byte = 2 // an RTL letter (bidi class R or AL)
        private const val W: Byte = 3 // whitespace
        private const val P: Byte = 4 // other visible: punctuation, symbols
        private const val F: Byte = 5 // invisible: format, combining marks
        private const val X: Byte = 6 // a conditional control itself

        private fun conditional(c: Char): Boolean = c == '\u200E' || c == '\u200F' || c == '\u061C' || c in '\u2066'..'\u2069'

        private fun base(cp: Int): Byte {
            when (Character.getType(cp)) {
                Character.FORMAT.toInt(), Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                -> return F
            }
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return W
            val d = Character.getDirectionality(cp)
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return R
            if (Character.isLetterOrDigit(cp)) return L
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
                    // Drawn as a visible token (it holds "U+" and digits): count it as a letter.
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
                if (here > 0) analyzeLine(s, inert, a, b, keep, occ)
                occ += here
                a = b + 1
            }
            return keep
        }

        private fun analyzeLine(s: String, inert: BooleanArray?, a: Int, b: Int, keep: BooleanArray, firstOcc: Int) {
            val cls = classes(s, inert, a, b)
            // No RTL letter on the line: every conditional control stays a token (the default).
            if (cls.none { it == R }) return
            val len = b - a
            // Nearest strong (L/R) before / after each index: marks.
            val prevStrong = ByteArray(len)
            val nextStrong = ByteArray(len)
            // Nearest letter/digit before / after, across punctuation and invisibles, not across a space: isolate edges.
            val prevJoin = ByteArray(len)
            val nextJoin = ByteArray(len)
            var cur = NONE_C
            var join = NONE_C
            for (k in 0 until len) {
                prevStrong[k] = cur
                prevJoin[k] = join
                when (cls[k]) {
                    L, R -> {
                        cur = cls[k]
                        join = cls[k]
                    }
                    W -> join = NONE_C
                }
            }
            cur = NONE_C
            join = NONE_C
            for (k in len - 1 downTo 0) {
                nextStrong[k] = cur
                nextJoin[k] = join
                when (cls[k]) {
                    L, R -> {
                        cur = cls[k]
                        join = cls[k]
                    }
                    W -> join = NONE_C
                }
            }
            // Prefix counts of L and R letters: an isolate's content in O(1).
            val cntL = IntArray(len + 1)
            val cntR = IntArray(len + 1)
            for (k in 0 until len) {
                cntL[k + 1] = cntL[k] + if (cls[k] == L) 1 else 0
                cntR[k + 1] = cntR[k] + if (cls[k] == R) 1 else 0
            }

            class Open(val occ: Int, val at: Int, val top: Boolean)
            val stack = ArrayList<Open>()

            fun split(before: Byte, after: Byte): Boolean = before != NONE_C && before == after

            fun decide(o: Open, closeAt: Int?, closeOcc: Int) {
                if (!o.top) return // nested: token, with its PDI (the default)
                val from = o.at + 1
                val to = closeAt ?: len
                val hasL = cntL[to] - cntL[from] > 0
                val hasR = cntR[to] - cntR[from] > 0
                val ownDirection = when (s[a + o.at]) {
                    '\u2066' -> hasL // LRI
                    '\u2067' -> hasR // RLI
                    else -> hasL || hasR // FSI: whichever letter comes first
                }
                val openSplits = split(prevJoin[o.at], nextJoin[o.at])
                val closeSplits = closeAt != null && split(prevJoin[closeAt], nextJoin[closeAt])
                val k = ownDirection && !openSplits && !closeSplits
                keep[o.occ] = k
                if (closeOcc >= 0) keep[closeOcc] = k
            }

            var occ = firstOcc
            for (k in 0 until len) {
                if (cls[k] != X) continue
                val c = s[a + k]
                when {
                    c in '\u2066'..'\u2068' -> stack.add(Open(occ, k, stack.isEmpty()))
                    c == '\u2069' -> if (stack.isNotEmpty()) decide(stack.removeAt(stack.size - 1), k, occ) // else: stray, token
                    else -> {
                        // A mark: a token when it cuts an LTR run (L or digits both sides), or an LRM cuts an RTL word.
                        val l = prevStrong[k]
                        val r = nextStrong[k]
                        val cuts = l != NONE_C && l == r && (l == L || c == '\u200E')
                        keep[occ] = !cuts
                    }
                }
                occ++
            }
            for (o in stack) decide(o, null, -1) // unterminated: ends with its line
        }
    }
}
