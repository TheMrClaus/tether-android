package com.tether.app.client

/**
 * T7.3 r2: the terminal escape sequences a command's output may carry, as [LabelText.output] hides
 * them. Self-contained (one entry point, [skip]) so the output rule can be swapped without touching
 * this.
 *
 * A sequence is hidden WHOLE only when it is complete within a bounded scan:
 * - CSI (`ESC [` or 8-bit 0x9B): parameter / intermediate bytes (0x20-0x3F), at most
 *   [MAX_CSI_PARAMS] of them, then one final byte (0x40-0x7E).
 * - The string sequences OSC / DCS / SOS / PM / APC (`ESC ]`, `ESC P`, `ESC X`, `ESC ^`, `ESC _`, or
 *   8-bit 0x9D / 0x90 / 0x98 / 0x9E / 0x9F): their body up to BEL, ST (`ESC \` or 8-bit 0x9C),
 *   within [MAX_STRING] characters, and never across a line break, CAN (0x18), SUB (0x1A) or another
 *   ESC.
 * - `ESC x`: the two characters.
 *
 * An incomplete sequence (no final byte or terminator in reach) hides only its INTRODUCER, and
 * everything after it stays visible. So a stray or hostile `ESC ]` can never swallow the rest of a
 * stream the agent still reads.
 */
internal object TerminalEscapes {
    const val MAX_STRING = 4096
    const val MAX_CSI_PARAMS = 256

    private const val ESC = '\u001B'

    /** True for a character that opens a sequence [skip] handles. */
    fun opens(c: Char): Boolean = c == ESC || c == '\u009B' || c == '\u0090' || c == '\u0098' || c in '\u009D'..'\u009F'

    /** The index just past what is hidden of the sequence opened at [start] (see [opens]). */
    fun skip(text: String, start: Int): Int {
        val n = text.length
        val c = text[start]
        if (c != ESC) return if (c == '\u009B') csi(text, start + 1) else string(text, start + 1)
        if (start + 1 >= n) return n
        val k = text[start + 1]
        return when {
            k == '[' -> csi(text, start + 2)
            k == ']' || k == 'P' || k == 'X' || k == '^' || k == '_' -> string(text, start + 2)
            // A lone ESC before a control or a line break: hide the ESC only.
            k.code < 0x20 || k.code == 0x7F -> start + 1
            else -> start + 2
        }
    }

    /** [body] is just past the introducer: the whole sequence, or the introducer alone. */
    private fun csi(text: String, body: Int): Int {
        var i = body
        val limit = minOf(text.length, body + MAX_CSI_PARAMS)
        while (i < limit && text[i].code in 0x20..0x3F) i++
        return if (i < text.length && text[i].code in 0x40..0x7E) i + 1 else body
    }

    private fun string(text: String, body: Int): Int {
        var i = body
        val limit = minOf(text.length, body + MAX_STRING)
        while (i < limit) {
            val ch = text[i]
            when {
                ch == '\u0007' || ch == '\u009C' -> return i + 1
                ch == ESC -> return if (i + 1 < text.length && text[i + 1] == '\\') i + 2 else body
                ch == '\n' || ch == '\r' || ch == '\u0018' || ch == '\u001A' -> return body
            }
            i++
        }
        return body
    }
}
