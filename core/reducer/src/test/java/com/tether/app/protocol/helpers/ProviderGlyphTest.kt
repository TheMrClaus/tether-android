package com.tether.app.protocol.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

/** The provider glyph table of the web's lib/format.ts:88-96 (tether 15f74ecd). */
class ProviderGlyphTest {
    @Test
    fun everyProviderGlyphMatchesTheWebTable() {
        val expected = mapOf(
            "claude" to "C", "codex" to "X", "opencode" to "O", "reasonix" to "R", "pi" to "P",
            "gemini" to "G", "antigravity" to "G",
        )
        for ((id, glyph) in expected) assertEquals(id, glyph, Format.providerGlyph(id))
        assertEquals("?", Format.providerGlyph("acp"))
        assertEquals("?", Format.providerGlyph(null as String?))
    }
}
