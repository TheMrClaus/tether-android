package com.tether.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** T5.1: a scope redeclares exactly the properties its rule sets; everything else is the skin's. */
class TokenScopeTest {

    private val ink = Color(0xFFD7DFEF)
    private val tintRgb = Color(0xFFEBF0FF)
    private val scope = TokenScope(".test-scope") { it.copy(graphite = Color(0xFF141D2E), ink = ink, tintRgb = tintRgb) }

    @Test fun onlyTheRedeclaredPropertiesChange() {
        val base = tokensFor(TetherSkin.Studio)
        val scoped = scope.applyTo(base)
        assertEquals(Color(0xFF141D2E), scoped.graphite)
        assertEquals(ink, scoped.ink)
        assertEquals(ink, scoped.css.ink)
        // Not redeclared: the skin's own value.
        assertEquals(base.violet, scoped.violet)
        assertEquals(base.keyFace, scoped.keyFace)
        assertEquals(TetherSkin.Studio, scoped.skin)
    }

    @Test fun derivedPropertiesKeepTheirRootValueLikeCss() {
        // --tint-xs is `rgb(var(--tint-rgb) / …)` computed on :root; redeclaring --tint-rgb in a
        // descendant scope does not recompute it (custom properties inherit computed values).
        val base = tokensFor(TetherSkin.Studio)
        val scoped = scope.applyTo(base)
        assertEquals(tintRgb, scoped.tintRgb)
        assertEquals(base.tintXs, scoped.tintXs)
        assertEquals(base.tintLine, scoped.tintLine)
    }

    @Test fun equalityFollowsTheValuesNotJustTheSkin() {
        val base = tokensFor(TetherSkin.Studio)
        val scoped = scope.applyTo(base)
        assertNotEquals(base, scoped)
        assertEquals(scoped, scope.applyTo(base))
        assertEquals(scoped.hashCode(), scope.applyTo(base).hashCode())
        assertEquals(base, TetherTokens(TetherSkin.Studio))
    }

    @Test fun aScopeThatChangesNothingReturnsTheSameTokens() {
        val base = tokensFor(TetherSkin.StudioDark)
        assertSame(base, TokenScope(".noop") { it }.applyTo(base))
    }
}
