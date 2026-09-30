package com.tether.app.client

/**
 * T7.2 round 2 (L5): server-supplied text shown on a control (a model or agent name, a hint, a
 * catalog error) is cleaned the way T6.4 cleans a command label before it is drawn: no bidi
 * embedding / override / isolate controls or directional marks (they could reorder the words around
 * them), no default-ignorable or blank-looking code points, whitespace (line breaks included)
 * collapsed to one space, trimmed, and cut at a code-point boundary to a bound. Values sent back to
 * the server are never cleaned: only what is displayed.
 */
object LabelText {
    const val MAX_LABEL = 80
    const val MAX_HINT = 300
    const val MAX_ERROR = 500

    /** Most catalog items one snapshot / controls reply may offer. */
    const val MAX_ITEMS = 200

    /**
     * A code point that draws nothing a reader can see: whitespace, FORMAT characters, the
     * blank-looking letters and symbols (U+115F/1160, U+3164, U+FFA0 Hangul fillers, U+2800 braille
     * blank), tag characters (U+E0000-E007F), variation selectors and the other default-ignorable
     * code points (U+034F, U+17B4/17B5, U+180B-180F, U+FE00-FE0F, U+E0100-E01EF). (T6.4 round 5.)
     */
    fun invisibleCodePoint(cp: Int): Boolean =
        Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.getType(cp) == Character.FORMAT.toInt() ||
            cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 || cp == 0x2800 ||
            cp in 0xE0000..0xE007F || cp == 0x034F || cp == 0x17B4 || cp == 0x17B5 || cp in 0x180B..0x180F ||
            cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF

    private fun bidiControl(cp: Int): Boolean = cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == 0x200E || cp == 0x200F || cp == 0x061C

    private fun spacing(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.getType(cp) == Character.CONTROL.toInt()

    /** [text] cleaned and cut to [max] UTF-16 units ("…" marks a cut); "" when nothing visible is left. */
    fun clean(text: String?, max: Int): String {
        if (text.isNullOrEmpty()) return ""
        val out = StringBuilder()
        var pendingSpace = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                bidiControl(cp) -> Unit
                spacing(cp) -> pendingSpace = out.isNotEmpty()
                invisibleCodePoint(cp) -> Unit
                else -> {
                    if (pendingSpace) out.append(' ')
                    pendingSpace = false
                    out.appendCodePoint(cp)
                    // Round 3 (I-c): nothing past the bound is ever looked at.
                    if (out.length > max) break
                }
            }
        }
        if (out.length <= max) return out.toString()
        val cut = ConsentGuard.cutCodePoints(out.toString(), max - 1).trimEnd()
        return "$cut…"
    }

    /**
     * Round 3 (N-M1): a server-supplied VALUE (an agent or model id) shown so it cannot pass for
     * another: every bidi control and every invisible code point except U+0020 is written out as
     * `\u{XXXX}`; nothing is collapsed or trimmed; cut to [MAX_LABEL] with "…".
     */
    fun visibleValue(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val out = StringBuilder()
        var i = 0
        while (i < value.length && out.length <= MAX_LABEL) {
            val cp = value.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '\\'.code) {
                // Round 4 (P3): a literal backslash is doubled, so "\\u{200B}" typed out can never pass for an escape.
                out.append("\\\\")
            } else if (cp != 0x20 && (bidiControl(cp) || invisibleCodePoint(cp) || Character.getType(cp) == Character.CONTROL.toInt())) {
                out.append("\\u{").append(Integer.toHexString(cp).uppercase().padStart(4, '0')).append('}')
            } else {
                out.appendCodePoint(cp)
            }
        }
        if (out.length <= MAX_LABEL && i >= value.length) return out.toString()
        // Round 4 (P3): a cut value keeps a stable tag of the WHOLE value, so two long values that
        // share their first characters never display identically.
        val tag = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(6)
        return ConsentGuard.cutCodePoints(out.toString(), MAX_LABEL - 8) + "…#" + tag
    }

    /**
     * T7.3: a command's OUTPUT (a terminal stream) as it is drawn and read aloud. The line structure is
     * kept: "\n" stays, "\r\n" and a lone "\r" (a progress line rewriting itself) read as one line
     * break, U+2028 / U+2029 as a line break, a tab stays. Removed: terminal escape sequences, whole
     * when complete and only their introducer when not ([TerminalEscapes], 7-bit and 8-bit), every other
     * C0 / C1 control, DEL, the bidi embedding / override / isolate controls and directional marks (so
     * output can never reorder what is drawn around it), and the invisible or blank-looking code
     * points ([invisibleCodePoint]); any other kind of space is drawn as a plain space. Nothing is
     * collapsed or cut here: the surface draws a bounded tail.
     */
    fun output(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            when {
                cp < 0x10000 && TerminalEscapes.opens(cp.toChar()) -> i = TerminalEscapes.skip(text, i)
                cp == '\r'.code -> {
                    out.append('\n')
                    i += if (i + 1 < n && text[i + 1] == '\n') 2 else 1
                }
                cp == '\n'.code || cp == '\t'.code || cp == ' '.code -> { out.appendCodePoint(cp); i += width }
                cp == 0x2028 || cp == 0x2029 -> { out.append('\n'); i += width }
                cp < 0x20 || cp in 0x7F..0x9F -> i += width
                bidiControl(cp) -> i += width
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> { out.append(' '); i += width }
                invisibleCodePoint(cp) -> i += width
                else -> { out.appendCodePoint(cp); i += width }
            }
        }
        return out.toString()
    }

    fun label(text: String?): String = clean(text, MAX_LABEL)
    fun hint(text: String?): String = clean(text, MAX_HINT)
    fun error(text: String?): String = clean(text, MAX_ERROR)
}
