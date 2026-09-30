package com.tether.app.ui.chat

import com.tether.app.ui.chat.TranscriptText.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * ta-blf: the transcript's text rules ([TranscriptText]), code point by code point. Every control
 * in this file is written as an escape: the source must not carry what it tests.
 */
class TranscriptTextTest {
    private fun tok(cp: Int): String = "\u2060\u27E8U+" + Integer.toHexString(cp).uppercase().padStart(4, '0') + "\u27E9"
    private fun cps(vararg cp: Int) = cp.toList()
    private fun s(cp: Int): String = String(Character.toChars(cp))

    private val overrides = cps(0x202A, 0x202B, 0x202C, 0x202D, 0x202E) // LRE RLE PDF LRO RLO
    private val isolates = cps(0x2066, 0x2067, 0x2068, 0x2069) // LRI RLI FSI PDI
    private val marks = cps(0x200E, 0x200F, 0x061C) // LRM RLM ALM
    private val allBidi = overrides + isolates + marks

    private val arabic = "\u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645"
    private val hebrew = "\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD"

    // ---- prose -------------------------------------------------------------------------------

    @Test fun proseMakesEveryEmbeddingAndOverrideAToken() {
        for (cp in overrides) {
            val shown = TranscriptText.prose("Fix the ${s(cp)}parser bug")
            assertEquals("Fix the ${tok(cp)}parser bug", shown)
            assertFalse("U+%04X passed raw".format(cp), shown.contains(s(cp)))
        }
        assertEquals("Fix the ${tok(0x202E)}parser${tok(0x202C)}   bug now", TranscriptText.prose("Fix the \u202Eparser\u202C   bug now"))
    }

    @Test fun proseKeepsIsolatesAndMarksForRealRtlText() {
        for (cp in isolates + marks) {
            val text = "a${s(cp)}b $arabic"
            assertSame("U+%04X".format(cp), text, TranscriptText.prose(text))
        }
        val rtl = "\u2067$hebrew\u2069 \u2014 $arabic\u200F"
        assertSame(rtl, TranscriptText.prose(rtl))
    }

    @Test fun proseKeepsZeroWidthShapingCharacters() {
        // ZWNJ in Persian, a ZWJ family, VS16, ZWSP, BOM: shaping and line breaks, never order.
        val text = "\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645 \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 \u2764\uFE0F a\u200Bb \uFEFFc"
        assertSame(text, TranscriptText.prose(text))
    }

    @Test fun proseShowsStrayTagCharactersButKeepsAFlag() {
        val england = "\uD83C\uDFF4" + "gbeng".map { s(0xE0000 + it.code) }.joinToString("") + s(0xE007F)
        assertSame(england, TranscriptText.prose(england))
        val smuggled = "Hello" + "hi".map { s(0xE0000 + it.code) }.joinToString("")
        assertEquals("Hello" + tok(0xE0068) + tok(0xE0069), TranscriptText.prose(smuggled))
        // Tags after the black flag but with no cancel tag: not a flag, shown.
        val broken = "\uD83C\uDFF4" + s(0xE0067)
        assertEquals("\uD83C\uDFF4" + tok(0xE0067), TranscriptText.prose(broken))
    }

    @Test fun proseShowsTheWordJoinerBecauseItIsTheTokensMark() {
        assertEquals("a${tok(0x2060)}b", TranscriptText.prose("a\u2060b"))
    }

    // ---- code --------------------------------------------------------------------------------

    @Test fun codeMakesEveryBidiControlAToken() {
        for (cp in allBidi) {
            val shown = TranscriptText.code("x = \"${s(cp)}admin\"")
            assertEquals("U+%04X".format(cp), "x = \"${tok(cp)}admin\"", shown)
        }
    }

    @Test fun codeMakesEveryInvisibleCodePointAToken() {
        val invisible = cps(
            0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x00AD, 0x180E, 0x034F, 0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800,
            0xFE0F, 0xFE00, 0x17B4, 0x2028, 0x2029, 0xE0001, 0xE0041, 0xE007F, 0xE0100, 0x1D173, 0x0000, 0x0007, 0x001B,
            0x007F, 0x0085, 0x009F,
        )
        for (cp in invisible) assertEquals("U+%04X".format(cp), "a${tok(cp)}b", TranscriptText.code("a${s(cp)}b"))
        // A flag is exact in code too: every tag shows.
        assertTrue(TranscriptText.code("\uD83C\uDFF4" + s(0xE0067) + s(0xE007F)).contains(tok(0xE007F)))
        // A lone surrogate is shown, not drawn as a broken glyph.
        assertEquals("a${tok(0xD800)}b", TranscriptText.code("a\uD800b"))
    }

    @Test fun codeKeepsTabsNewlinesCrlfAndVisibleText() {
        val text = "if (a)\n\treturn b;\r\n// $arabic $hebrew\n  x\u00A0y"
        assertSame(text, TranscriptText.code(text))
        assertEquals("progress${tok(0x0D)}done", TranscriptText.code("progress\rdone"))
    }

    @Test fun rtlLettersAreNeverEscapedOrDropped() {
        val text = "$arabic \u2014 $hebrew \u2014 \u0641\u0627\u0631\u0633\u06CC"
        for (rule in Rule.entries) assertSame(rule.name, text, TranscriptText.encode(text, rule))
        val mixed = "\u202E$arabic"
        for (rule in Rule.entries) assertTrue(rule.name, TranscriptText.encode(mixed, rule).endsWith(arabic))
    }

    @Test fun plainTextIsTheSameStringNoCopy() {
        val text = "Summarize the README in one line.\n```\nnpm test\n```"
        assertSame(text, TranscriptText.prose(text))
        assertSame(text, TranscriptText.code(text))
    }

    // ---- decoding (copy) --------------------------------------------------------------------

    @Test fun everyEncodingDecodesToTheExactOriginal() {
        val corpus = listOf(
            "Fix the \u202Eparser\u202C bug",
            "a\u2060b",
            "literal \u27E8U+202E\u27E9 text",
            "\u2060\u27E8U+202E\u27E9 typed with the mark",
            "\u2060\u27E8U+",
            "a\uD800b\uDC00c",
            "\uD83C\uDFF4" + s(0xE0067) + s(0xE007F),
            "Hello" + s(0xE0068),
            "p\rq\r\nr\u0000",
            "$arabic\u2067$hebrew\u2069",
            "",
        )
        for (text in corpus) for (rule in Rule.entries) {
            assertEquals("$rule: $text", text, TranscriptText.original(TranscriptText.encode(text, rule)))
        }
    }

    @Test fun randomTextRoundTrips() {
        val alphabet = listOf(
            "a", " ", "\n", "\r", "\t", "\u27E8", "\u27E9", "U", "+", "2", "E", "\u2060", "\u202E", "\u202C", "\u2066", "\u2069", "\u200B",
            "\u200F", "\uFEFF", "\uD800", "\uDC00", "\uD83C\uDFF4", s(0xE0067), s(0xE007F), "\u0645", "\u05E9", "\u001B", "[", "m",
        )
        val random = Random(20260930)
        repeat(3000) {
            val text = buildString { repeat(random.nextInt(0, 24)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            for (rule in Rule.entries) assertEquals("$rule: ${text.map { "%04X".format(it.code) }}", text, TranscriptText.original(TranscriptText.encode(text, rule)))
        }
    }

    @Test fun aLookAlikeWithoutTheMarkIsNeverDecoded() {
        assertEquals("\u27E8U+202E\u27E9", TranscriptText.original("\u27E8U+202E\u27E9"))
        // A token cut by the selection stays literal.
        val cut = tok(0x202E).dropLast(1)
        assertEquals(cut, TranscriptText.original(cut))
        assertEquals("U+202E\u27E9", TranscriptText.original(tok(0x202E).drop(2)))
    }

    // ---- break anywhere, terminal output ----------------------------------------------------

    @Test fun breakAnywhereNeverSplitsAToken() {
        val shown = TranscriptText.code("a\u202Eb").breakAnywhere()
        assertEquals("a\u200B${tok(0x202E)}\u200Bb", shown)
        assertEquals("a\u200B\u202E\u200Bb", TranscriptText.original(shown))
        // No token: exactly the old behaviour (the approval cards rely on it).
        assertEquals("a\u200Bb", "ab".breakAnywhere())
        assertEquals("\uD83D\uDE00\u200Bx", "\uD83D\uDE00x".breakAnywhere())
    }

    @Test fun terminalOutputDropsColourButShowsEveryOtherEscape() {
        assertEquals("FAIL ok", TranscriptText.terminal("\u001B[31mFAIL\u001B[0m \u001B[1;32mok\u001B[m"))
        // Cursor moves / erase / OSC are never interpreted: the ESC shows, the rest is literal.
        assertEquals("${tok(0x1B)}[2Kdone", TranscriptText.terminal("\u001B[2Kdone"))
        assertEquals("Build OK ${tok(0x1B)}]0;hidden${tok(0x07)}", TranscriptText.terminal("Build OK \u001B]0;hidden\u0007"))
        assertEquals("${tok(0x1B)}[31", TranscriptText.terminal("\u001B[31"))
        assertEquals("a${tok(0x202E)}b", TranscriptText.terminal("a\u202Eb"))
    }

    @Test fun aLongSmuggledTagRunStaysLinear() {
        val run = s(0xE0041).repeat(200_000)
        val start = System.nanoTime()
        val shown = TranscriptText.prose(run)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(200_000 * tok(0xE0041).length, shown.length)
        assertTrue("took $ms ms", ms < 2_000)
    }
}
