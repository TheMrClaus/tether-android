package com.tether.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember

/**
 * A CSS scope that REDECLARES custom properties for everything inside it — the web's
 * `.selector { --graphite: …; --ink: …; }` (e.g. studio.css `.session-sidebar`, which gives the
 * Studio rail its own ink-blue palette in both lightings, New session key included).
 *
 * [redeclare] returns the skin's tokens with the scope's values (a `SkinTokens.copy(...)` of the
 * properties the rule sets, nothing else). The CSS cascade is kept exactly: only the redeclared
 * properties change. A property DERIVED from another on `:root` (e.g. `--tint-xs:
 * rgb(var(--tint-rgb) / …)`) is computed where it is declared and inherited as a value, so it
 * keeps its `:root` value even when its source is redeclared in the scope — redeclare it too if
 * the scope's rule does.
 *
 * Everything that reads [LocalTetherTokens] inside [ProvideTokenScope] (keys, wells, dots, text)
 * resolves against the scoped values; the skin itself (family, `isDark`, typography) is unchanged.
 */
@Immutable
class TokenScope(
    /** The web selector this scope mirrors, for provenance and debugging. */
    val selector: String,
    private val redeclare: (SkinTokens) -> SkinTokens,
) {
    /** [base] with this scope's redeclared properties. */
    fun applyTo(base: TetherTokens): TetherTokens {
        val css = redeclare(base.css)
        return if (css == base.css) base else TetherTokens(base.skin, css)
    }

    override fun toString(): String = "TokenScope($selector)"
}

/**
 * Provides [scope]'s tokens to [content] (null: the current tokens, unchanged). Surfaces the web
 * renders OUTSIDE the scoped element (dialogs raised from inside it, for example) must be
 * composed outside this call so they keep the page's tokens.
 */
@Composable
fun ProvideTokenScope(scope: TokenScope?, content: @Composable () -> Unit) {
    if (scope == null) {
        content()
        return
    }
    val base = LocalTetherTokens.current
    val scoped = remember(base, scope) { scope.applyTo(base) }
    CompositionLocalProvider(LocalTetherTokens provides scoped, content = content)
}
