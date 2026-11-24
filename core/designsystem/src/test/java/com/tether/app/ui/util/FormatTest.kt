package com.tether.app.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun estimatedUsdMatchesTheWebCompactStyle() {
        assertEquals("$1.23", estimatedUsd(1.234))
        assertEquals("$0.30", estimatedUsd(0.3))
        assertEquals("$0.0045", estimatedUsd(0.0045)) // sub-cent: 4 digits
        assertEquals("—", estimatedUsd(null))
    }

    /** lib/format.ts:95 (tether 15f74ecd): antigravity shares gemini's "G". */
    @Test
    fun providerGlyphMapsAntigravityLikeGemini() {
        assertEquals("G", providerGlyph("gemini"))
        assertEquals("G", providerGlyph("antigravity"))
        assertEquals("?", providerGlyph("unknown"))
    }
}
