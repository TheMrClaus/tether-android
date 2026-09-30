package com.tether.app.ui.text

/**
 * ta-blf: how the app DRAWS and COPIES server-, agent- and user-supplied text that carries Unicode
 * bidi controls, invisible code points or terminal controls (the Trojan Source class of spoofing:
 * text that reads as something other than what it is, and paste-jacking: a copy that carries
 * something the reader never saw). Pure Kotlin, no Compose; the drawing helpers are in
 * SafeTextCompose.kt. First used by the chat transcript (T6.x); built to be adopted by other
 * surfaces (T7.3's command panel takes [terminal]).
 *
 * The web (components/markdown.tsx, chat-view.tsx) renders all of it raw, with no `dir`, no
 * `unicode-bidi` and no escaping, so "Fix the U+202E parser U+202C bug" reads "Fix the resrap bug"
 * there. This deliberately diverges. Nothing is ever dropped: a code point the reader must see is
 * drawn as a visible, inert TOKEN, styled `--warning`:
 *   `⟨U+202E⟩`          one code point
 *   `⟨U+200B ×12⟩`      a run of one code point
 *   `⟨tags:hidden text⟩` a run of printable tag characters (U+E0020-U+E007E), shown as the ASCII
 *                       they spell: that is exactly the text "ASCII smuggling" hides
 * Every token starts with [MARK] (WORD JOINER, U+2060). [MARK] followed by U+200B is an inserted
 * BREAK OPPORTUNITY (between two tokens, or between the characters of a path): zero width, never
 * content. No surface that draws through this file ever draws a raw U+2060: both rules make it a
 * token. So a look-alike typed as text ("⟨U+202E⟩" without the mark) can never pass for a token.
 *
 * PROSE ([Rule.Prose]; bubbles, markdown, thinking, card copy, answered questions, todo items):
 * - Always tokens: the embeddings and overrides LRE RLE PDF LRO RLO (U+202A-U+202E); C0 controls
 *   except TAB, LF and the CR of a CRLF; DEL; C1; the LINE and PARAGRAPH SEPARATORS (U+2028,
 *   U+2029: they break a line without ending the bidi paragraph); WORD JOINER; tag characters,
 *   except in the three RGI subdivision flags (U+1F3F4 + `gbeng` / `gbsct` / `gbwls` + U+E007F).
 * - The isolates LRI RLI FSI PDI (U+2066-U+2069) and the marks LRM RLM ALM (U+200E, U+200F,
 *   U+061C) are how real RTL text is written, but they can reorder text as fully as an override
 *   (RLI + one letter per LRI...PDI draws "resrap" as "parser"; RLMs between letters do the same;
 *   RLI around "/ tmp" draws "tmp /"; RLI around "1 - 2" draws "2 - 1"; an isolated "-rf" beside
 *   "rm" in an RTL line trades places with it). So one is drawn raw only where real RTL text needs
 *   it ([ProsePlan]); otherwise it is a token. An RTL LETTER below is a real letter (Lu/Ll/Lt/Lo)
 *   of bidi class R or AL: the geresh, gershayim and tatweel are not letters. A WORD GAP is
 *   U+0020, U+00A0, U+3000 or a tab: a hair, thin or narrow space is not.
 *   - never on a LINE with no RTL letter: there they have no job but to reorder;
 *   - a mark is kept only when one of its nearest strong neighbours is an RTL letter, and never
 *     when it cuts a run in two (L or digits on both sides; an LRM between two RTL letters). So a
 *     leading RLM followed by Latin is a token;
 *   - an isolate nested inside another is always a token (with its PDI), and so is a stray PDI;
 *   - an isolate whose content has no letter of its own direction is a token (an RLI around
 *     Latin only, an LRI around Hebrew only). An LRI / FSI around neutrals only (an emoji) is an
 *     LTR island that keeps their order and reorders nothing around it, so it is kept;
 *   - an isolate whose edge splits a word (letters or digits of the SAME direction on both sides
 *     of the edge, across punctuation but not across a word gap) is a token;
 *   - an isolate that could trade places with a word: its paragraph (the first strong character
 *     outside every isolate, UAX #9 P2) runs the other way, and the nearest letter outside it on
 *     either side, across spaces, is of the isolate's own direction (a line edge counts as the
 *     paragraph). Without the isolate those two would read as one run; with it they reorder.
 *   What stays possible, by design: RTL text is drawn right to left and mixed-direction text takes
 *   the order UAX #9 gives it (a Hebrew line's "1 - 2" reads "2 - 1" with no control at all).
 *   That is the algorithm doing its job for an RTL reader, not something to hide.
 * - Kept: ZWSP, ZWNJ, ZWJ, BOM, variation selectors and the other default-ignorables. They shape
 *   (Persian ZWNJ, emoji ZWJ sequences, VS16) or hint line breaks, and never reorder.
 * Each block is its own Text layout and Android starts a new bidi paragraph at every '\n', so
 * nothing reaches past its line. Nothing is inserted around paragraphs: wrapping one in FSI...PDI
 * would hide its first strong letter from the paragraph-direction rule (UAX #9 P2) and flip an
 * Arabic paragraph to LTR.
 *
 * CODE ([Rule.Code]; fences, inline code, tool I/O, diffs, paths, names, ids): code shows exactly
 * what it holds, so every code point that is invisible or reorders is a token: all twelve bidi
 * controls, every FORMAT character, the line and paragraph separators, the default-ignorables,
 * the braille blank, lone surrogates, and C0/C1 controls except TAB, LF and the CR of a CRLF. An
 * emoji's ZWJ/VS16 shows as a token in code; exactness wins there. The RGI subdivision flags are
 * the one exception: they draw as the flag they are. RTL LETTERS are never tokens, in code or in
 * prose; code lays out LTR, as the web's `<pre>` in its LTR page and every editor do.
 *
 * TERMINAL ([terminal]; command output): [code] after the ANSI SGR colour sequences are dropped.
 * Every other escape sequence (cursor moves, erase, OSC titles) is shown, never interpreted.
 *
 * COPY ([forCopy], [original]): a copy never carries a hidden control the reader did not see.
 * The DANGEROUS set ([dangerous]: C0 except TAB/LF/CRLF, DEL, C1, U+202A-U+202E,
 * U+2066-U+2069, tag characters outside the three flags), and any mark the plan made a token, is
 * copied as its VISIBLE token text, without the mark (`⟨U+001B⟩`), and the copy reports how many
 * ("N hidden control characters copied as ⟨U+…⟩"). Everything else, including every character
 * real RTL writing needs (letters, the marks it kept, ZWNJ/ZWJ), copies exactly. [original] gives
 * the exact source text for an explicit "Copy raw". Inserted break opportunities never reach a
 * copy, and neither does a U+2060 left over when a selection cuts a token in two. A selection that
 * STARTS between the two halves of a break opportunity keeps a leading U+200B: the copy path hands
 * over the selected text only, no offset, and in prose a U+200B is real content, so it cannot be
 * told apart. It is zero-width and outside the dangerous set.
 *
 * Cost: one linear pass per text; a text with nothing to escape (almost all) comes back as the
 * same String. Adjacent tokens are one styled span with a break opportunity between them, and a
 * run of one code point is one token, so a flood of hidden characters stays cheap to lay out.
 */
object SafeText {
    /** WORD JOINER: the first character of every token and of every inserted break opportunity. */
    const val MARK: Char = '\u2060'
    internal const val OPEN: Char = '⟨' // MATHEMATICAL LEFT ANGLE BRACKET
    internal const val CLOSE: Char = '⟩' // MATHEMATICAL RIGHT ANGLE BRACKET
    internal const val TIMES: Char = '×' // MULTIPLICATION SIGN
    internal const val ZWSP: Char = '\u200B'
    private const val HEX = "0123456789ABCDEF"
    private const val TAGS = "tags:"

    /** A printable-tag run at least this long, of one code point, is a `×N` token instead. */
    private const val TAG_RUN_COLLAPSE = 16

    /** The three RGI subdivision flags (England, Scotland, Wales): tag text after U+1F3F4. */
    val RGI_FLAG_TAGS: Set<String> = setOf("gbeng", "gbsct", "gbwls")

    enum class Rule { Prose, Code }

    fun prose(text: String): String = encode(text, Rule.Prose)

    fun code(text: String): String = encode(text, Rule.Code)

    /** Command output: [code] after the SGR colour sequences are dropped (see the class doc). */
    fun terminal(text: String): String = code(dropSgr(text))

    /** [text] drawn by [rule]; prose uses [plan] for its isolates and marks (one is built from [text] if null). */
    fun encode(text: String, rule: Rule, plan: ProsePlan? = null): String = Encoder(text, rule, plan, mapped = false).run().display

    /** [encode], plus where each source character lands in the display (find highlights). */
    fun encodeMapped(text: String, rule: Rule, plan: ProsePlan? = null): Encoded = Encoder(text, rule, plan, mapped = true).run()

    /** A drawn text and, when mapped, the display range of each source character's unit. */
    class Encoded internal constructor(val display: String, private val starts: IntArray?, private val ends: IntArray?) {
        /** Where the unit holding source char [i] starts in [display]. */
        fun displayStart(i: Int): Int = when {
            starts == null -> i
            i >= starts.size -> display.length
            else -> starts[i]
        }

        /** Where the unit holding source char `endExclusive - 1` ends in [display] (a token stays whole). */
        fun displayEnd(endExclusive: Int): Int = when {
            ends == null -> endExclusive
            endExclusive <= 0 -> 0
            else -> ends[minOf(endExclusive, ends.size) - 1]
        }
    }

    /** SGR (`ESC [ digits ; : m`) dropped; every other escape kept for [code] to show. */
    fun dropSgr(text: String): String {
        var at = text.indexOf('\u001B')
        if (at < 0) return text
        val out = StringBuilder(text.length)
        var from = 0
        while (at >= 0) {
            var k = at + 1
            if (k < text.length && text[k] == '[') {
                k++
                while (k < text.length && (text[k] in '0'..'9' || text[k] == ';' || text[k] == ':')) k++
                if (k < text.length && text[k] == 'm') {
                    out.append(text, from, at)
                    from = k + 1
                }
            }
            at = text.indexOf('\u001B', maxOf(at + 1, from))
        }
        out.append(text, from, text.length)
        return out.toString()
    }

    // ---- classes of code points ---------------------------------------------------------------

    internal fun isTag(cp: Int): Boolean = cp in 0xE0000..0xE007F

    internal fun isIsolate(cp: Int): Boolean = cp in 0x2066..0x2069

    internal fun isMark(cp: Int): Boolean = cp == 0x200E || cp == 0x200F || cp == 0x061C

    /** C0 except TAB and LF (a CR is judged with its successor), DEL, C1. */
    internal fun isControl(cp: Int): Boolean = (cp < 0x20 && cp != 0x09 && cp != 0x0A) || cp == 0x7F || cp in 0x80..0x9F

    /** Prose's unconditional tokens (tags are decided per run, isolates and marks by the plan). */
    internal fun proseAlways(cp: Int): Boolean = cp in 0x202A..0x202E || cp == MARK.code || isControl(cp) || cp == 0x2028 || cp == 0x2029

    /** Code's set (see the class doc); a CR is judged with its successor. */
    fun codeEscapes(cp: Int): Boolean {
        if (cp < 0x20) return cp != 0x09 && cp != 0x0A
        if (cp < 0x7F) return false
        if (cp <= 0x9F) return true
        when (Character.getType(cp)) {
            Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
            Character.SURROGATE.toInt(), Character.CONTROL.toInt(),
            -> return true
        }
        return defaultIgnorable(cp) || cp == 0x2800
    }

    /** Default_Ignorable_Code_Point outside FORMAT (DerivedCoreProperties), written out: no ICU needed. */
    private fun defaultIgnorable(cp: Int): Boolean =
        cp == 0x034F || cp == 0x115F || cp == 0x1160 || cp == 0x17B4 || cp == 0x17B5 || cp in 0x180B..0x180F ||
            cp in 0x2060..0x206F || cp == 0x3164 || cp in 0xFE00..0xFE0F || cp == 0xFEFF || cp == 0xFFA0 ||
            cp in 0xFFF0..0xFFF8 || cp in 0x1BCA0..0x1BCA3 || cp in 0x1D173..0x1D17A || cp in 0xE0000..0xE0FFF

    /** The copy's DANGEROUS set (a CR only when it is not the CR of a CRLF: judged by the caller). */
    fun dangerous(cp: Int): Boolean = isControl(cp) || cp in 0x202A..0x202E || isIsolate(cp) || isTag(cp)

    /** [start, end) (UTF-16) is a tag run that, after U+1F3F4, spells one of the three RGI flags. */
    internal fun rgiFlag(text: CharSequence, start: Int, end: Int): Boolean {
        if (start < 2 || Character.codePointAt(text, start - 2) != 0x1F3F4) return false
        val tags = (end - start) / 2 - 1
        if (tags !in 5..5 || Character.codePointAt(text, end - 2) != 0xE007F) return false
        val sb = StringBuilder(5)
        var k = start
        while (k < end - 2) {
            val cp = Character.codePointAt(text, k)
            if (cp !in 0xE0061..0xE007A) return false
            sb.append((cp - 0xE0000).toChar())
            k += 2
        }
        return sb.toString() in RGI_FLAG_TAGS
    }

    // ---- encoding -----------------------------------------------------------------------------

    private class Encoder(val text: String, val rule: Rule, var plan: ProsePlan?, val mapped: Boolean) {
        val n = text.length
        var out: StringBuilder? = null
        var starts: IntArray? = null
        var ends: IntArray? = null
        var lastWasToken = false

        fun candidate(c: Char): Boolean = when (rule) {
            Rule.Prose -> c < ' ' && c != '\t' && c != '\n' || c in '\u007F'..'\u009F' || c == '\u200E' || c == '\u200F' ||
                c == '\u061C' || c in '\u2028'..'\u202E' || c == MARK || c in '\u2066'..'\u2069' || c == '\uDB40'
            Rule.Code -> c < ' ' && c != '\t' && c != '\n' || c >= '\u007F'
        }

        fun crlf(i: Int): Boolean = text[i] == '\r' && i + 1 < n && text[i + 1] == '\n'

        fun run(): Encoded {
            var i = 0
            while (i < n && !candidate(text[i])) i++
            if (i == n) return Encoded(text, null, null)
            if (mapped) begin()
            var run = 0 // the source not yet appended (nothing is copied until the first escape)
            while (i < n) {
                val c = text[i]
                if (!candidate(c)) {
                    i++
                    continue
                }
                val cp = text.codePointAt(i)
                val len = Character.charCount(cp)
                if (isTag(cp)) {
                    val end = tagRunEnd(i)
                    if (end == i) { // a lone high surrogate from the tag block
                        if (rule == Rule.Code) {
                            flushTo(run, i)
                            token(hex(cp), i, i + 1)
                            run = i + 1
                        }
                        i++
                        continue
                    }
                    if (rgiFlag(text, i, end)) {
                        i = end
                        continue
                    }
                    flushTo(run, i)
                    tagTokens(i, end)
                    i = end
                    run = i
                    continue
                }
                if (rule == Rule.Prose && (isIsolate(cp) || isMark(cp))) {
                    val p = plan ?: ProsePlan.of(text).also { plan = it }
                    if (p.take()) {
                        i += len
                        continue
                    }
                    flushTo(run, i)
                    token(hex(cp), i, i + len)
                    i += len
                    run = i
                    continue
                }
                val esc = if (rule == Rule.Prose) proseAlways(cp) && !crlf(i) else codeEscapes(cp) && !crlf(i)
                if (!esc) {
                    i += len
                    continue
                }
                flushTo(run, i)
                var j = i + len
                var count = 1
                while (j < n && text.codePointAt(j) == cp && !crlf(j)) {
                    j += len
                    count++
                }
                token(if (count > 1) hex(cp) + " " + TIMES + count else hex(cp), i, j)
                i = j
                run = i
            }
            if (out == null) return Encoded(text, null, null)
            raw(run, n)
            return Encoded(out.toString(), starts, ends)
        }

        fun tagRunEnd(i: Int): Int {
            var e = i
            while (e + 1 < n && Character.isHighSurrogate(text[e]) && isTag(text.codePointAt(e))) e += 2
            return e
        }

        fun begin() {
            out = StringBuilder(n + 16)
            if (mapped) {
                starts = IntArray(n)
                ends = IntArray(n)
            }
        }

        /** Start the copy if needed, then append the source [from, to). */
        fun flushTo(from: Int, to: Int) {
            if (out == null) begin()
            raw(from, to)
        }

        fun raw(from: Int, to: Int) {
            if (from >= to) return
            val o = out!!
            if (mapped) {
                var k = from
                while (k < to) {
                    val len = if (Character.isHighSurrogate(text[k]) && k + 1 < to && Character.isLowSurrogate(text[k + 1])) 2 else 1
                    val s = o.length + (k - from)
                    for (q in k until k + len) {
                        starts!![q] = s
                        ends!![q] = s + len
                    }
                    k += len
                }
            }
            o.append(text, from, to)
            lastWasToken = false
        }

        fun token(body: String, from: Int, to: Int) {
            val o = out!!
            if (lastWasToken) o.append(MARK).append(ZWSP)
            val s = o.length
            o.append(MARK).append(OPEN).append(body).append(CLOSE)
            if (mapped) for (q in from until to) {
                starts!![q] = s
                ends!![q] = o.length
            }
            lastWasToken = true
        }

        /** A run of tag characters: printable ones as `tags:` text, other or long single-code-point runs as `U+` tokens. */
        fun tagTokens(start: Int, end: Int) {
            var k = start
            val payload = StringBuilder()
            var payloadFrom = start
            fun flush(at: Int) {
                if (payload.isNotEmpty()) token(TAGS + payload, payloadFrom, at)
                payload.setLength(0)
            }
            while (k < end) {
                val cp = text.codePointAt(k)
                var m = 1
                while (k + 2 * m < end && text.codePointAt(k + 2 * m) == cp) m++
                if (cp in 0xE0020..0xE007E && m < TAG_RUN_COLLAPSE) {
                    if (payload.isEmpty()) payloadFrom = k
                    repeat(m) { payload.append((cp - 0xE0000).toChar()) }
                } else {
                    flush(k)
                    token(if (m > 1) hex(cp) + " " + TIMES + m else hex(cp), k, k + 2 * m)
                }
                k += 2 * m
            }
            flush(end)
        }
    }

    private fun hex(cp: Int): String {
        val digits = when {
            cp > 0xFFFFF -> 6
            cp > 0xFFFF -> 5
            else -> 4
        }
        val sb = StringBuilder(2 + digits).append("U+")
        for (k in digits - 1 downTo 0) sb.append(HEX[(cp shr (4 * k)) and 0xF])
        return sb.toString()
    }

    // ---- decoding -----------------------------------------------------------------------------

    /** One unit starting at [MARK]: a token or a break opportunity; null when it is neither. */
    class Piece internal constructor(
        val end: Int,
        /** -1 for a break opportunity or a `tags:` token. */
        val cp: Int,
        val count: Int,
        /** The code points a `tags:` token stands for, else null. */
        val tags: String?,
    ) {
        val isBreak: Boolean get() = cp < 0 && tags == null
    }

    fun unitAt(s: CharSequence, at: Int): Piece? {
        if (at + 1 >= s.length || s[at] != MARK) return null
        if (s[at + 1] == ZWSP) return Piece(at + 2, -1, 0, null)
        if (s[at + 1] != OPEN) return null
        var k = at + 2
        if (s.length - k > TAGS.length && s.subSequence(k, k + TAGS.length).toString() == TAGS) {
            k += TAGS.length
            val from = k
            while (k < s.length && s[k] in ' '..'~') k++
            if (k == from || k >= s.length || s[k] != CLOSE) return null
            return Piece(k + 1, -1, k - from, s.subSequence(from, k).toString())
        }
        if (k + 1 >= s.length || s[k] != 'U' || s[k + 1] != '+') return null
        k += 2
        val hexFrom = k
        var v = 0
        while (k < s.length && k - hexFrom < 6 && HEX.indexOf(s[k]) >= 0) v = (v shl 4) or HEX.indexOf(s[k++])
        if (k - hexFrom < 4 || v > 0x10FFFF || k >= s.length) return null
        var count = 1
        if (s[k] == ' ') {
            if (k + 1 >= s.length || s[k + 1] != TIMES) return null
            k += 2
            val numFrom = k
            var c = 0L
            while (k < s.length && k - numFrom < 10 && s[k] in '0'..'9') c = c * 10 + (s[k++] - '0')
            if (k == numFrom || c < 2 || c > Int.MAX_VALUE) return null
            count = c.toInt()
        }
        if (k >= s.length || s[k] != CLOSE) return null
        return Piece(k + 1, v, count, null)
    }

    private fun StringBuilder.appendCp(cp: Int, times: Int) {
        if (cp <= 0xFFFF) repeat(times) { append(cp.toChar()) } else repeat(times) { appendCodePoint(cp) }
    }

    /** The exact source text of a drawn (possibly partial) [display]: "Copy raw". */
    fun original(display: CharSequence): String {
        val s = display.toString()
        var at = s.indexOf(MARK)
        if (at < 0) return s
        val out = StringBuilder(s.length)
        var from = 0
        while (at >= 0) {
            val u = unitAt(s, at)
            if (u != null) {
                out.append(s, from, at)
                when {
                    u.isBreak -> Unit
                    u.tags != null -> for (ch in u.tags) out.appendCodePoint(0xE0000 + ch.code)
                    else -> out.appendCp(u.cp, u.count)
                }
                from = u.end
            } else {
                out.append(s, from, at) // r3: a lone mark (a cut token or break): not the source's
                from = at + 1
            }
            at = s.indexOf(MARK, if (u != null) u.end else at + 1)
        }
        out.append(s, from, s.length)
        return out.toString()
    }

    /** What a copy puts on the clipboard, and how many hidden control characters it shows as tokens. */
    class Copied(val text: String, val hidden: Int)

    /** The clipboard text of a drawn [display] (see COPY in the class doc). */
    fun forCopy(display: CharSequence): Copied {
        val s = display.toString()
        if (s.none { it == MARK || it < ' ' || it in '\u007F'..'\u009F' || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' || it == '\uDB40' }) {
            return Copied(s, 0)
        }
        val out = StringBuilder(s.length)
        var hidden = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == MARK) {
                val u = unitAt(s, i)
                if (u != null) {
                    when {
                        u.isBreak -> Unit
                        u.tags != null -> {
                            out.append(s, i + 1, u.end)
                            hidden += u.count
                        }
                        // r3: a mark the plan made a token is copied as the token too (it was cutting a run).
                        (dangerous(u.cp) || isMark(u.cp)) && !(u.cp == '\r'.code && u.count == 1 && u.end < s.length && s[u.end] == '\n') -> {
                            out.append(s, i + 1, u.end)
                            hidden += u.count
                        }
                        else -> out.appendCp(u.cp, u.count)
                    }
                    i = u.end
                    continue
                }
                // r3: a mark with no whole unit after it is half a token or break a selection cut:
                // no surface draws a raw U+2060, so the source never had it.
                i++
                continue
            }
            val cp = s.codePointAt(i)
            val len = Character.charCount(cp)
            if (isTag(cp)) {
                var e = i
                while (e + 1 < s.length && Character.isHighSurrogate(s[e]) && isTag(s.codePointAt(e))) e += 2
                if (e > i && rgiFlag(s, i, e)) {
                    out.append(s, i, e)
                    i = e
                    continue
                }
            }
            val crlf = c == '\r' && i + 1 < s.length && s[i + 1] == '\n'
            if (dangerous(cp) && !crlf) {
                out.append(OPEN).append(hex(cp)).append(CLOSE)
                hidden++
            } else {
                out.append(s, i, i + len)
            }
            i += len
        }
        return Copied(out.toString(), hidden)
    }

    /** How many code points of the dangerous set a RAW text holds (a CRLF and the three flags are not). */
    fun hiddenIn(raw: CharSequence): Int {
        var n = 0
        var i = 0
        while (i < raw.length) {
            val cp = Character.codePointAt(raw, i)
            if (isTag(cp)) {
                var e = i
                while (e + 1 < raw.length && Character.isHighSurrogate(raw[e]) && isTag(Character.codePointAt(raw, e))) e += 2
                if (e > i && rgiFlag(raw, i, e)) {
                    i = e
                    continue
                }
            }
            if (dangerous(cp) && !(cp == '\r'.code && i + 1 < raw.length && raw[i + 1] == '\n')) n++
            i += Character.charCount(cp)
        }
        return n
    }

    /** The notice a raw copy that holds hidden characters shows. */
    fun includedNotice(hidden: Int): String =
        if (hidden == 1) "Copied raw: 1 hidden control character included" else "Copied raw: $hidden hidden control characters included"

    /** The notice a copy with hidden characters shows. */
    fun copyNotice(hidden: Int): String =
        if (hidden == 1) "1 hidden control character copied as $OPEN" + "U+…$CLOSE" else "$hidden hidden control characters copied as $OPEN" + "U+…$CLOSE"

    /**
     * `word-break: break-all` for an already-drawn [display]: a break opportunity between every two
     * characters, a token kept whole. The opportunity is [MARK] + ZWSP, so no copy ever carries it.
     */
    fun breakAnywhere(display: String): String {
        if (display.length < 2) return display
        val out = StringBuilder(display.length * 3)
        var i = 0
        while (i < display.length) {
            val u = if (display[i] == MARK) unitAt(display, i) else null
            val end = when {
                u != null -> u.end
                Character.isHighSurrogate(display[i]) && i + 1 < display.length && Character.isLowSurrogate(display[i + 1]) -> i + 2
                else -> i + 1
            }
            out.append(display, i, end)
            if (end < display.length && u?.isBreak != true) out.append(MARK).append(ZWSP)
            i = end
        }
        return out.toString()
    }
}
