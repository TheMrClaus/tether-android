package com.tether.app.ui.sidebar

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.ui.components.hardShadow
import com.tether.app.ui.icons.ProviderTile
import com.tether.app.ui.theme.CssLineHeight
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.SkinTokens
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.TokenScope

/**
 * studio.css 295-301: `.session-sidebar` redeclares the palette for everything inside the rail —
 * "Navigation keeps a stable ink-blue finish in either lighting mode." Only these properties; the
 * derived tints (`--tint-xs` … on `:root`) keep their root values, as in CSS ([TokenScope]).
 */
val StudioSidebarScope: TokenScope = TokenScope(":root:where([data-theme^=\"studio\"]) .session-sidebar") { t: SkinTokens ->
    t.copy(
        mineral = Color(0xFF141D2E),
        mineralDeep = Color(0xFF111A2B),
        graphite = Color(0xFF141D2E),
        graphiteRaised = Color(0xFF1D2940),
        slate = Color(0xFF2A3750),
        line = Color(0xFF2B374B),
        lineStrong = Color(0xFF3B4861),
        white = Color(0xFFEFF3FC),
        ink = Color(0xFFD7DFEF),
        muted = Color(0xFFA9B7CE),
        faint = Color(0xFF9CACC6),
        violet = Color(0xFFA7BDFF),
        violetStrong = Color(0xFF8EAAFF),
        violetWash = Color(0xFF263B66),
        keyFace = Color(0xFF1D2940),
        keyFaceHover = Color(0xFF293851),
        keyFaceDeep = Color(0xFF111A2B),
        keySide = Color(0xFF34425A),
        running = Color(0xFF75DABA),
        danger = Color(0xFFFF9DAA),
        tintRgb = Color(0xFFEBF0FF),
        tintBoost = 1f,
    )
}


/** A CSS declaration block's text: `font: <weight> <rem> <family>`, tracking in em, unitless line-height. */
internal fun css(family: FontFamily, rem: Float, weight: Int, trackingEm: Float = 0f, lineHeight: Float? = null): TextStyle = TextStyle(
    fontFamily = family,
    fontSize = (rem * 16f).sp,
    fontWeight = FontWeight(weight),
    letterSpacing = if (trackingEm == 0f) TextUnit.Unspecified else trackingEm.em,
    lineHeight = lineHeight?.em ?: TextUnit.Unspecified,
    lineHeightStyle = CssLineHeight,
)

internal val Float.rem: Dp get() = (this * 16f).dp

/** Lucide `star` with `fill: currentColor` (`.workspace-block-pin.is-pinned svg`, globals.css 3128). */
internal val FilledStar: ImageVector = ImageVector.Builder("StarFilled", 24.dp, 24.dp, 24f, 24f).addPath(
    pathData = addPathNodes(
        "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 " +
            ".294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 " +
            "2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 " +
            "9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z",
    ),
    fill = SolidColor(Color.Black),
    stroke = SolidColor(Color.Black),
    strokeLineWidth = 2f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
).build()

/**
 * `.provider-glyph` as a molded round cap (globals.css 1417-1437, 9044-9049; inside a row
 * 11683-11684; Studio 324: flat, `--graphite-raised`, radius 0.45rem). The claude / codex /
 * opencode marks take `--ink`, the others `--white`; a verified mark takes its brand tile instead.
 */
@Composable
internal fun ProviderCap(
    provider: String,
    size: Dp,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    inRow: Boolean = true,
    letterRem: Float = 0.8f,
) {
    val t = LocalTetherTokens.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(0.45f.rem)
    val border = null
    val shadows = emptyList<CssShadow>()
    val ink = if (provider == "claude" || provider == "codex" || provider == "opencode") t.ink else t.white
    // A verified mark gives the cap its brand tile (globals.css 11204-11224).
    ProviderTile(
        provider = provider,
        modifier = modifier.size(size),
        shape = shape,
        background = t.graphiteRaised,
        border = border,
        shadows = shadows,
        color = ink,
        markSize = size * 0.58f,
        letterSize = (letterRem * 16f).sp,
    )
}

@Composable
internal fun SmallIcon(icon: ImageVector, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}
