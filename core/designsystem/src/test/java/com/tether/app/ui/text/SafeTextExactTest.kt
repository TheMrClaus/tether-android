package com.tether.app.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-dh1 r2: [SafeText.exact], the confirmation's rule: [SafeText.line] plus every non-ASCII space as a token. */
class SafeTextExactTest {
    private val spaces = listOf(0x00A0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x202F, 0x205F, 0x3000)

    @Test fun everyNonAsciiSpaceIsAToken() {
        for (cp in spaces) {
            val text = "/opt/x" + String(Character.toChars(cp)) + "y/claude"
            val shown = SafeText.exact(text)
            val hex = "U+%04X".format(cp)
            assertTrue("$hex: $shown", shown.contains("⟨$hex⟩"))
            assertFalse(hex, shown.contains(String(Character.toChars(cp))))
            assertEquals(hex, text, SafeText.original(shown))
            // The line rule (every other one-line surface) is unchanged: the space is drawn as it is.
            assertEquals(hex, text, SafeText.line(text))
        }
    }

    @Test fun anOrdinarySpaceAndPlainTextAreUntouched() {
        val text = "jean-claude run -- claude"
        assertSame(text, SafeText.exact(text))
    }

    @Test fun itKeepsTheLineRulesTokens() {
        val text = "/srv/\u202Egnp.exe\u200B/a\tb"
        val shown = SafeText.exact(text)
        for (t in listOf("⟨U+202E⟩", "⟨U+200B⟩", "⟨U+0009⟩")) assertTrue(t, shown.contains(t))
        assertEquals(text, SafeText.original(shown))
        // A run of one space is one token.
        assertTrue(SafeText.exact("a\u00A0\u00A0\u00A0b").contains("⟨U+00A0 ×3⟩"))
    }
}
