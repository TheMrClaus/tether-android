package com.tether.app.ui.icons

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.SkinTokens

/** A brand-coloured harness tile: the container's [background] and the mark's (and label's) [content]. */
@Immutable
data class ProviderBrandTile(val background: Color, val content: Color)

/**
 * The web's brand-coloured harness marks (tether 90fbb9f app/globals.css 11204-11233, tokens
 * app/studio.css 51-54), keyed off [ProviderLogos.brand] (the svg's `data-brand`: set only when a
 * mark is verified, so a letter fallback never takes a brand).
 *
 * - [tile]: `:root :where(*):has(> svg.provider-logo-svg[data-brand=…])` — whatever container directly
 *   holds the mark gets `border-color: transparent`, the brand background and the brand ink: claude
 *   terracotta / paper; codex, opencode, pi ink / paper; reasonix, dsh, gemini paper / blue (gemini's
 *   own four fills ignore the ink). Box shadows are untouched.
 * - [glyphTint]: `.chat-mode-glyph` (an inline glyph with no tile) drops its background and tints the
 *   mark instead: claude terracotta, codex / opencode / pi `--white`, reasonix / dsh blue; gemini has
 *   no rule (its fills are its own).
 *
 * Both rules are `:root`-scoped and the brand tokens are shared by both lightings ("a brand does not
 * change with the skin"), so they apply in the light and the dark skin alike; only `--white` (the
 * inline glyph's ink-mark colour) follows the skin.
 */
object ProviderBrand {
    /** The tile [provider]'s mark gives its container, or null (no verified mark, or no brand rule). */
    fun tile(provider: String?, tokens: SkinTokens): ProviderBrandTile? = when (ProviderLogos.brand(provider)) {
        "claude" -> ProviderBrandTile(tokens.brandClaude, tokens.brandPaper)
        "codex", "opencode", "pi" -> ProviderBrandTile(tokens.brandInk, tokens.brandPaper)
        "reasonix", "dsh", "gemini" -> ProviderBrandTile(tokens.brandPaper, tokens.brandBlue)
        else -> null
    }

    /** The `.chat-mode-glyph` tint of [provider]'s mark, or null (keep the caller's colour). */
    fun glyphTint(provider: String?, tokens: SkinTokens): Color? = when (ProviderLogos.brand(provider)) {
        "claude" -> tokens.brandClaude
        "codex", "opencode", "pi" -> tokens.white
        "reasonix", "dsh" -> tokens.brandBlue
        else -> null
    }
}

/** [ProviderBrand.tile] under the current skin. */
@Composable
fun providerBrandTile(provider: String?): ProviderBrandTile? = ProviderBrand.tile(provider, LocalTetherTokens.current.css)

/**
 * A container that directly holds a [ProviderLogo] (the web's `.provider-glyph`, `.chat-mention-logo`,
 * `.schedule-provider-logo`, `.ti-mark`, overview's `.providerMark`, …), themed by the brand rule.
 *
 * [background], [border] and [color] are the container's own (neutral) look, used as they are for a
 * letter fallback; a verified mark replaces them with its [ProviderBrandTile] (the border goes
 * transparent: no line, the background under it). [shadows] are kept either way. The caller sizes the
 * box through [modifier].
 */
@Composable
fun ProviderTile(
    provider: String?,
    modifier: Modifier = Modifier,
    fallback: String? = null,
    shape: Shape = RectangleShape,
    background: Color = Color.Transparent,
    border: CssBorder? = null,
    shadows: List<CssShadow> = emptyList(),
    color: Color = ProviderLogoDefaults.color(provider),
    markSize: Dp = ProviderLogoDefaults.markSize(),
    letterSize: TextUnit = ProviderLogoDefaults.LetterSize,
) {
    val tile = providerBrandTile(provider)
    Box(
        modifier.cssSurface(shape, tile?.background ?: background, if (tile != null) null else border, shadows),
        contentAlignment = Alignment.Center,
    ) {
        ProviderLogo(provider, fallback = fallback, color = tile?.content ?: color, markSize = markSize, letterSize = letterSize)
    }
}

/**
 * An inline provider glyph with no tile of its own (the web's `.chat-mode-glyph`): the mark tinted by
 * [ProviderBrand.glyphTint], else [color] (a letter fallback, or a mark with no tint rule).
 */
@Composable
fun ProviderInlineGlyph(
    provider: String?,
    modifier: Modifier = Modifier,
    fallback: String? = null,
    color: Color,
    markSize: Dp,
    letterSize: TextUnit = ProviderLogoDefaults.LetterSize,
) {
    val tint = ProviderBrand.glyphTint(provider, LocalTetherTokens.current.css) ?: color
    ProviderLogo(provider, modifier, fallback = fallback, color = tint, markSize = markSize, letterSize = letterSize)
}
