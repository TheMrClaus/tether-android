package com.tether.app.client

/**
 * T7.2 round 2 (L5): server-supplied text shown on a control (a model or agent name, a hint, a
 * catalog error) is cleaned the way T6.4 cleans a command label before it is drawn: no bidi
 * embedding / override / isolate controls or directional marks (they could reorder the words around
 * them), no default-ignorable or blank-looking code points, whitespace (line breaks included)
 * collapsed to one space, trimmed, and cut to a bound. Values sent back to the server are never
 * cleaned: only what is displayed.
 *
 * ta-28i: this is the LABEL rule every one-line server/agent label outside the transcript uses
 * (session titles and snippets in the sidebar and search, push notifications). A cut is made at a
 * character-cluster boundary ([TextCut]): never inside a surrogate pair, a combining sequence, an
 * emoji ZWJ / modifier sequence or a flag, and never inside a [visibleValue] escape. The label rule
 * drops every explicit bidi control and every mark (it is a single line: a dropped mark cannot
 * reorder anything, and a label draws in its content's direction on screen), which is stricter than
 * the transcript's prose rule (marks kept beside real RTL letters) and never looser.
 */
object LabelText {
    const val MAX_LABEL = 80
    const val MAX_HINT = 300
    const val MAX_ERROR = 500

    /** ta-28i: a session title (the web clamps it to two sidebar lines; this bounds the work). */
    const val MAX_TITLE = 200

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
        // ta-28i: at a cluster boundary: an accent, a skin tone or half a flag is never left behind.
        val cut = TextCut.cut(out.toString(), max - 1).trimEnd()
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
        // ta-28i: where each source unit (a code point, an escape, a doubled backslash) ends, in
        // [out] and in [value]: a cut is made only there, so an escape is never cut in half.
        val outEnds = ArrayList<Int>()
        val srcEnds = ArrayList<Int>()
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
            outEnds.add(out.length)
            srcEnds.add(i)
        }
        if (out.length <= MAX_LABEL && i >= value.length) return out.toString()
        // Round 4 (P3): a cut value keeps a stable tag of the WHOLE value, so two long values that
        // share their first characters never display identically.
        val tag = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(6)
        var keep = 0
        for (k in outEnds.indices) {
            if (outEnds[k] > MAX_LABEL - 8) break
            if (TextCut.isBoundary(value, srcEnds[k])) keep = outEnds[k]
        }
        return out.substring(0, keep) + "…#" + tag
    }

    /** ta-28i: a session title (sidebar, search, headers), by the label rule. */
    fun title(text: String?): String = clean(text, MAX_TITLE)

    fun label(text: String?): String = clean(text, MAX_LABEL)
    fun hint(text: String?): String = clean(text, MAX_HINT)
    fun error(text: String?): String = clean(text, MAX_ERROR)
}
