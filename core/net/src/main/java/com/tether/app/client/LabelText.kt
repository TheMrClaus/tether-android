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
                }
            }
        }
        if (out.length <= max) return out.toString()
        val cut = ConsentGuard.cutCodePoints(out.toString(), max - 1).trimEnd()
        return "$cut…"
    }

    fun label(text: String?): String = clean(text, MAX_LABEL)
    fun hint(text: String?): String = clean(text, MAX_HINT)
    fun error(text: String?): String = clean(text, MAX_ERROR)
}
