package com.tether.app.ui.chat

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.tether.app.ui.components.PreDisplay
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherTokens

/**
 * ta-blf: how the TRANSCRIPT draws server-, agent- and user-supplied text that carries Unicode
 * bidi controls or invisible code points (the Trojan Source class of spoofing: text that reads as
 * something other than what it is).
 *
 * The web (components/markdown.tsx, chat-view.tsx) renders all of it raw. It sets no `dir`, no
 * `unicode-bidi` and no escaping. The only confinement is the browser's own per-block bidi
 * paragraph, so on the web "Fix the U+202E parser U+202C bug" reads "Fix the resrap bug". This
 * client deliberately diverges, as T6.4 (command labels) and T7.2 (`LabelText`) already do for
 * labels. But a transcript is reading, not a label: real Arabic, Hebrew and Persian text must stay
 * readable, and nothing may be dropped. So the transcript never strips. It replaces a code point
 * with a visible, inert token `⟨U+202E⟩` (styled `--warning`, see [tokenStyle]), and it does that
 * per surface:
 *
 * PROSE ([Rule.Prose]): agent/user bubbles (streaming and finished), markdown paragraphs,
 * headings, lists, quotes, table cells and link labels, thinking, card copy (results, errors, plan
 * steps), the sub-agent panel's titles and notes.
 * - Escaped: the explicit embeddings and overrides LRE, RLE, PDF, LRO, RLO (U+202A-U+202E). An
 *   override is the one control that reverses the letters INSIDE a word ("gnp.exe" as "exe.png").
 *   UAX #9 has recommended isolates instead since Unicode 6.3, so real RTL prose does not need
 *   these. Once they are tokens, no embedding is ever open, so a stray PDI has nothing to pop.
 * - Escaped: tag characters (U+E0000-U+E007F) outside an emoji tag sequence (U+1F3F4, tags
 *   U+E0020-U+E007E, then U+E007F: the subdivision flags). They have no other use, and they hide
 *   text ("ASCII smuggling"). A flag sequence is kept, so the flag still draws.
 * - Escaped: WORD JOINER U+2060, because it is the token's own [MARK] (see "Copy" below).
 * - KEPT: the isolates LRI, RLI, FSI, PDI (U+2066-U+2069) and the marks LRM, RLM, ALM (U+200E,
 *   U+200F, U+061C). This is how real RTL text is written. An isolate groups runs but cannot
 *   reverse letters, and its reach is bounded without inserting anything. Each block is its own
 *   Text layout, and Android starts a new bidi paragraph at every '\n', which is where a markdown
 *   `<br/>` and a plain bubble's line break both land. So an unterminated isolate ends at its
 *   line, as UAX #9 ends it at the paragraph (BD7). Nothing is inserted: wrapping every
 *   paragraph in FSI...PDI would hide its first strong letter from the paragraph-direction rule
 *   (UAX #9 P2), which would flip an Arabic paragraph to LTR, and inserted closers could not be
 *   told apart from the author's own PDIs when copying.
 * - KEPT: ZWSP, ZWNJ, ZWJ, BOM, variation selectors and the other default-ignorables. They shape
 *   (Persian ZWNJ, emoji ZWJ sequences, VS16) or hint line breaks, and never reorder anything.
 *   C0/C1 controls stay too (a CRLF from the agent must not become tokens).
 *
 * CODE ([Rule.Code]): fenced blocks, inline code, tool input/output, command lines and output,
 * diffs (tool edits, Codex file changes, the repository panel's git hunks), paths, attachment
 * names, tool/MCP names and ids, the sub-agent panel's ids and paths. Code must show exactly what it contains (the Trojan
 * Source mitigation), so every code point that is invisible or reorders becomes a token. That
 * covers all twelve bidi controls, every FORMAT character (ZWSP/ZWNJ/ZWJ, WJ, BOM, soft hyphen,
 * tags), the line and paragraph separators, the other default-ignorables (variation selectors,
 * Hangul fillers, CGJ, Mongolian and Khmer ignorables), the braille blank, lone surrogates, and
 * C0/C1 controls except TAB, LF and the CR of a CRLF. Yes, an emoji's ZWJ/VS16 shows as a token
 * in code: exactness wins there. RTL LETTERS are never escaped, in code or in prose; code
 * surfaces lay out LTR ([codeDirection]) as the web's `<pre>` in its LTR page and every editor do.
 *
 * TERMINAL ([terminal]): the background command's output sheet (T6.4). This is CODE after the
 * ANSI SGR colour sequences are dropped. Every other escape sequence is shown, never interpreted.
 *
 * NOT here: notices, outcome/session-error rows, the timeline bubble and command labels are
 * LABELS. They already go through `LabelText.clean` (bidi and invisible code points dropped,
 * whitespace collapsed), and the approval cards escape with `displayPath` / `displayText`.
 *
 * COPY: copying yields the ORIGINAL text. The code-block key copies the raw fence body. A row's
 * selection (T6.7) copies the drawn text, and [OriginalTextClipboard] turns every token back into
 * the code point it names ([original]). That is exact because a token always starts with [MARK]
 * (U+2060) and no covered surface ever draws a raw U+2060 (both rules escape it). So a
 * look-alike typed as text ("⟨U+202E⟩" without the mark) is never decoded. A token cut in half
 * by the selection stays literal.
 *
 * TalkBack reads the drawn text: a token is spoken as its words ("U+202E"), never as an
 * instruction to reorder. The kept isolates and marks are ignored by speech.
 *
 * Cost: one linear pass per text, remembered. A text with nothing to escape (almost all of them)
 * is returned as the same String, with no copy.
 */
internal object TranscriptText {
    /** WORD JOINER: every token starts with it, and a covered surface never draws it raw. */
    const val MARK: Char = '\u2060'
    private const val OPEN: Char = '\u27E8' // MATHEMATICAL LEFT ANGLE BRACKET
    private const val CLOSE: Char = '\u27E9' // MATHEMATICAL RIGHT ANGLE BRACKET
    private const val HEX = "0123456789ABCDEF"

    enum class Rule { Prose, Code }

    /** [text] as prose draws it (see the rules above). */
    fun prose(text: String): String = encode(text, Rule.Prose)

    /** [text] as code draws it (see the rules above). */
    fun code(text: String): String = encode(text, Rule.Code)

    /**
     * A background command's live output (T6.4's output sheet), which is not selectable, so
     * nothing is copied from it. It is [code], after the ANSI SGR sequences (`ESC [ digits ; : m`,
     * colour and weight) are dropped. Their parameters are digits and separators only, so dropping
     * one can hide no text. Every other escape sequence (cursor moves, erase, OSC titles, which
     * could hide or overwrite text in a terminal) is never interpreted: its ESC is a visible token
     * and the rest shows as the literal text it is. An SGR split across two output chunks shows the
     * same way. Overlap, noted for convergence: T7.3's `LabelText.output` (its command panel)
     * strips ANSI and controls and DROPS bidi and invisible code points. This rule keeps every one
     * of them visible instead. A shared helper would take the SGR drop from here and choose one
     * rule for the rest.
     */
    fun terminal(text: String): String = code(dropSgr(text))

    internal fun dropSgr(text: String): String {
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

    fun encode(text: String, rule: Rule): String {
        val n = text.length
        var i = 0
        // Fast path: nothing to escape, the same String back (no copy).
        while (i < n && !candidate(text, i, rule)) i++
        if (i == n) return text
        var out: StringBuilder? = null
        var run = 0
        while (i < n) {
            if (!candidate(text, i, rule)) {
                i++
                continue
            }
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            if (rule == Rule.Prose && isTag(cp)) {
                // A whole run of tag characters is decided once (a long smuggled run stays linear).
                var end = i
                while (end < n && isTag(text.codePointAt(end))) end += 2
                if (emojiTagSequence(text, i, end)) {
                    i = end
                    continue
                }
                val sb = out ?: StringBuilder(n + 16).also { out = it }
                sb.append(text, run, i)
                var k = i
                while (k < end) {
                    appendToken(sb, text.codePointAt(k))
                    k += 2
                }
                i = end
                run = i
                continue
            }
            if (!escapes(cp, rule, text, i)) {
                i += len
                continue
            }
            val sb = out ?: StringBuilder(n + 16).also { out = it }
            sb.append(text, run, i)
            appendToken(sb, cp)
            i += len
            run = i
        }
        val sb = out ?: return text
        sb.append(text, run, n)
        return sb.toString()
    }

    /** A cheap per-char filter: false means the char is certainly drawn as is. */
    private fun candidate(text: String, i: Int, rule: Rule): Boolean {
        val c = text[i]
        return when (rule) {
            Rule.Prose -> c in '\u202A'..'\u202E' || c == MARK || c == '\uDB40'
            Rule.Code -> when {
                c < ' ' -> c != '\t' && c != '\n'
                c < '\u007F' -> false
                else -> true
            }
        }
    }

    private fun escapes(cp: Int, rule: Rule, text: String, i: Int): Boolean = when (rule) {
        Rule.Prose -> cp in 0x202A..0x202E || cp == MARK.code
        Rule.Code -> codeEscapes(cp) && !(cp == '\r'.code && i + 1 < text.length && text[i + 1] == '\n')
    }

    /** Code's set: see the class doc. */
    internal fun codeEscapes(cp: Int): Boolean {
        if (cp < 0x20) return cp != '\t'.code && cp != '\n'.code
        if (cp < 0x7F) return false
        if (cp <= 0x9F) return true
        when (Character.getType(cp)) {
            Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
            Character.SURROGATE.toInt(), Character.CONTROL.toInt(),
            -> return true
        }
        return defaultIgnorable(cp) || cp == 0x2800
    }

    /**
     * Unicode's Default_Ignorable_Code_Point (DerivedCoreProperties) outside the FORMAT category,
     * written out so the unit tests and the device agree without ICU.
     */
    private fun defaultIgnorable(cp: Int): Boolean =
        cp == 0x034F || cp == 0x115F || cp == 0x1160 || cp == 0x17B4 || cp == 0x17B5 || cp in 0x180B..0x180F ||
            cp in 0x2060..0x206F || cp == 0x3164 || cp in 0xFE00..0xFE0F || cp == 0xFEFF || cp == 0xFFA0 ||
            cp in 0xFFF0..0xFFF8 || cp in 0x1BCA0..0x1BCA3 || cp in 0x1D173..0x1D17A || cp in 0xE0000..0xE0FFF

    private fun isTag(cp: Int): Boolean = cp in 0xE0000..0xE007F

    /** [start, end) is the tag run of U+1F3F4 + tag_spec (U+E0020-U+E007E)+ + U+E007F. */
    private fun emojiTagSequence(text: String, start: Int, end: Int): Boolean {
        if (start < 2 || text.codePointAt(start - 2) != 0x1F3F4) return false
        if (end - start < 4 || text.codePointAt(end - 2) != 0xE007F) return false
        var k = start
        while (k < end - 2) {
            if (text.codePointAt(k) !in 0xE0020..0xE007E) return false
            k += 2
        }
        return true
    }

    private fun appendToken(out: StringBuilder, cp: Int) {
        out.append(MARK).append(OPEN).append('U').append('+')
        val digits = when {
            cp > 0xFFFFF -> 6
            cp > 0xFFFF -> 5
            else -> 4
        }
        for (k in digits - 1 downTo 0) out.append(HEX[(cp shr (4 * k)) and 0xF])
        out.append(CLOSE)
    }

    /** The end (exclusive) of the token at [at], or -1 when [at] does not start one. */
    internal fun tokenEnd(s: CharSequence, at: Int): Int {
        if (s[at] != MARK || at + 4 >= s.length || s[at + 1] != OPEN || s[at + 2] != 'U' || s[at + 3] != '+') return -1
        var k = at + 4
        while (k < s.length && k - (at + 4) < 6 && HEX.indexOf(s[k]) >= 0) k++
        val digits = k - (at + 4)
        if (digits < 4 || k >= s.length || s[k] != CLOSE) return -1
        return k + 1
    }

    private fun tokenValue(s: CharSequence, at: Int, end: Int): Int {
        var v = 0
        for (k in at + 4 until end - 1) v = (v shl 4) or HEX.indexOf(s[k])
        return v
    }

    /** The original text of a drawn (possibly partial) [display]: every whole token decoded. */
    fun original(display: CharSequence): String {
        val s = display.toString()
        var at = s.indexOf(MARK)
        if (at < 0) return s
        val out = StringBuilder(s.length)
        var from = 0
        while (at >= 0) {
            val end = tokenEnd(s, at)
            if (end > 0) {
                val v = tokenValue(s, at, end)
                if (v <= 0x10FFFF) {
                    out.append(s, from, at)
                    if (v <= 0xFFFF) out.append(v.toChar()) else out.appendCodePoint(v)
                    from = end
                }
            }
            at = s.indexOf(MARK, if (end > 0) end else at + 1)
        }
        out.append(s, from, s.length)
        return out.toString()
    }

    /** [display] with every token styled [style] (no allocation beyond the wrapper when none). */
    fun styled(display: String, style: SpanStyle): AnnotatedString {
        if (display.indexOf(MARK) < 0) return AnnotatedString(display)
        return AnnotatedString.Builder(display.length).apply { appendStyled(display, style) }.toAnnotatedString()
    }
}

/** Append an already-encoded [display], styling its tokens. */
internal fun AnnotatedString.Builder.appendStyled(display: String, style: SpanStyle) {
    val base = length
    append(display)
    var at = display.indexOf(TranscriptText.MARK)
    while (at >= 0) {
        val end = TranscriptText.tokenEnd(display, at)
        if (end > 0) addStyle(style, base + at, base + end)
        at = display.indexOf(TranscriptText.MARK, if (end > 0) end else at + 1)
    }
}

/** Append [text] encoded by [rule], tokens styled. */
internal fun AnnotatedString.Builder.appendSafe(text: String, rule: TranscriptText.Rule, style: SpanStyle) =
    appendStyled(TranscriptText.encode(text, rule), style)

/** A token's look: the `--warning` ink, so it never passes for the text around it. */
internal fun tokenStyle(t: TetherTokens): SpanStyle = SpanStyle(color = t.warning)

/** Code surfaces lay out LTR, like the web's `<pre>` / `<code>` in its LTR page. */
internal val codeDirection = androidx.compose.ui.text.style.TextDirection.Ltr

/** [text] as prose draws it, tokens styled; remembered. */
@Composable
internal fun proseText(text: String): AnnotatedString {
    val t = LocalTetherTokens.current
    return remember(text, t) { TranscriptText.styled(TranscriptText.prose(text), tokenStyle(t)) }
}

/** [text] as code draws it, tokens styled; [breakAnywhere] lets it wrap between any two characters (not inside a token). */
@Composable
internal fun codeText(text: String, breakAnywhere: Boolean = false): AnnotatedString {
    val t = LocalTetherTokens.current
    return remember(text, t, breakAnywhere) {
        val display = TranscriptText.code(text)
        TranscriptText.styled(if (breakAnywhere) display.breakAnywhere() else display, tokenStyle(t))
    }
}

/** A pre's display as code (stable per skin: it keys the pre's remember). */
@Composable
internal fun codePreDisplay(): PreDisplay {
    val t = LocalTetherTokens.current
    return remember(t) {
        val style = tokenStyle(t)
        PreDisplay { text -> TranscriptText.styled(TranscriptText.code(text), style) }
    }
}

/**
 * T6.7 + ta-blf: the clipboard a transcript row's selection copies through. It puts the ORIGINAL
 * text on the system clipboard ([TranscriptText.original]). A copy with no token passes through
 * untouched, rich text and all.
 */
internal class OriginalTextClipboard(private val delegate: Clipboard) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        delegate.setClipEntry(clipEntry?.let(::originalEntry))
    }

    override val nativeClipboard get() = delegate.nativeClipboard

    companion object {
        fun originalEntry(entry: ClipEntry): ClipEntry {
            val data = entry.clipData
            if (data.itemCount == 0) return entry
            val text = data.getItemAt(0).text ?: return entry
            if (!text.contains(TranscriptText.MARK)) return entry
            val restored = ClipData.newPlainText(data.description?.label ?: "text", TranscriptText.original(text))
            for (n in 1 until data.itemCount) restored.addItem(data.getItemAt(n))
            return ClipEntry(restored)
        }
    }
}
