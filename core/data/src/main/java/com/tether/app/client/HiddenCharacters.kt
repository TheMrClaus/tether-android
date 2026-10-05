package com.tether.app.client

/*
 * ta-m7ef: lib/hidden-characters.mjs + lib/draft-form.ts revealHiddenCharacters (tether 1bf4a465). Pure, so both
 * :core:net (the steps) and :core:designsystem (the command lists) read it.
 */

/** One piece of a command's text: plain, or one hidden character drawn as its `U+XXXX` label. */
data class CommandSegment(val text: String, val hidden: Boolean)

/** lib/hidden-characters.mjs + lib/draft-form.ts revealHiddenCharacters. */
object HiddenCharacters {
    // HIDDEN_CHARACTER_SOURCE: \p{Cc}\p{Cf}\p{Zl}\p{Zp}\p{Zs}\p{Co} and the listed code points, never \t \n or a plain space.
    private val HIDDEN = Regex(
        "(?![\\t\\n ])[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}\\p{Zs}\\p{Co}\\x{2800}\\x{115F}\\x{1160}\\x{3164}\\x{FFA0}\\x{034F}" +
            "\\x{180B}-\\x{180F}\\x{FE00}-\\x{FE0F}\\x{E0000}-\\x{E007F}\\x{E0100}-\\x{E01EF}]",
    )

    // NON_COMMAND_CHARACTER_SOURCE: anything outside printable ASCII plus \t \n (a hook command, a port script path).
    private val NON_COMMAND = Regex("[^\\t\\n\\x20-\\x7E]")

    /** "U+202E" style label for one code point (codePointLabel). */
    fun label(character: String): String = "U+" + character.codePointAt(0).toString(16).uppercase().padStart(4, '0')

    /**
     * [text] split so every hidden character is its own `hidden` segment drawn as its label; nothing of the
     * original is left raw. [command]: a hook command or port script path (every code point past printable
     * ASCII + \t \n is revealed); other text keeps the shared set.
     */
    fun reveal(text: String, command: Boolean = false): List<CommandSegment> {
        val matcher = if (command) NON_COMMAND else HIDDEN
        val out = ArrayList<CommandSegment>()
        var last = 0
        for (match in matcher.findAll(text)) {
            if (match.range.first > last) out += CommandSegment(text.substring(last, match.range.first), hidden = false)
            out += CommandSegment(label(match.value), hidden = true)
            last = match.range.last + 1
        }
        if (last < text.length) out += CommandSegment(text.substring(last), hidden = false)
        return out
    }
}
