package com.tether.app.ui.icons

import androidx.compose.ui.graphics.vector.VectorPath
import com.tether.app.ui.util.providerGlyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The provider -> logo table of components/provider-logo.tsx (issue #59). */
class ProviderLogoTest {

    /**
     * Byte-for-byte against the web's LOGO_MARKS, extracted into a checked-in fixture
     * (src/test/resources/provider-logo-marks.json), so an edited or truncated path fails here.
     */
    @Test
    fun pathsMatchTheWebLogoMarksVerbatim() {
        val json = checkNotNull(javaClass.classLoader?.getResource("provider-logo-marks.json")).readText()
        val web = Regex("""\"(\w+)\":\s*\{\s*\"viewBox\":\s*\"([^\"]+)\",\s*\"path\":\s*\"([^\"]+)\"""")
            .findAll(json).associate { it.groupValues[1] to (it.groupValues[2] to it.groupValues[3]) }
        assertEquals(listOf("claude", "codex", "opencode"), web.keys.toList())
        for ((id, mark) in web) {
            assertEquals("$id viewBox", "0 0 24 24", mark.first)
            assertEquals("$id path", mark.second, ProviderLogos.paths[id])
        }
        assertEquals(web.keys, ProviderLogos.paths.keys)
    }

    @Test
    fun onlyVerifiedMarksHaveAVector() {
        assertEquals(listOf("claude", "codex", "opencode"), ProviderLogos.paths.keys.toList())
        for (id in listOf("claude", "codex", "opencode")) {
            val mark = ProviderLogos.mark(id)
            assertNotNull(id, mark)
            mark!!
            assertEquals(24f, mark.viewportWidth)
            assertEquals(24f, mark.viewportHeight)
            val path = mark.root.single() as VectorPath
            assertTrue("$id path parsed", path.pathData.size > 1)
        }
    }

    @Test
    fun unverifiedAndUnknownProvidersFallBackToALetter() {
        for (id in listOf("reasonix", "pi", "acp", "gemini", "dsh", "future-agent", "", null)) {
            assertNull("$id has no mark", ProviderLogos.mark(id))
        }
    }

    @Test
    fun fallbackLetterMatchesTheWeb() {
        // fallback ?? (provider ? provider.slice(0, 1).toUpperCase() : "?")
        assertEquals("R", ProviderLogos.fallbackLetter("reasonix"))
        assertEquals("P", ProviderLogos.fallbackLetter("pi"))
        assertEquals("A", ProviderLogos.fallbackLetter("acp"))
        assertEquals("D", ProviderLogos.fallbackLetter("dsh"))
        assertEquals("?", ProviderLogos.fallbackLetter(null))
        assertEquals("?", ProviderLogos.fallbackLetter(""))
        assertEquals("X", ProviderLogos.fallbackLetter("pi", "X"))
        assertEquals("", ProviderLogos.fallbackLetter("pi", "")) // `??` keeps an empty fallback
    }

    @Test
    fun callersFallbackIsLibFormatProviderGlyph() {
        // lib/format.ts providerGlyph, the fallback most web call sites pass.
        val expected = mapOf(
            "claude" to "C", "codex" to "X", "opencode" to "O", "reasonix" to "R",
            "pi" to "P", "gemini" to "G", "acp" to "?", null to "?",
        )
        for ((id, glyph) in expected) assertEquals("$id", glyph, providerGlyph(id))
    }

    @Test
    fun inkProvidersMatchTheCss() {
        // .provider-claude, .provider-codex, .provider-opencode { color: var(--ink) }
        assertEquals(setOf("claude", "codex", "opencode"), ProviderLogoDefaults.INK_PROVIDERS)
        assertEquals(18.56f, ProviderLogoDefaults.markSize().value, 0.001f)
    }
}
