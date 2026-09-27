package com.tether.app.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.R
import com.tether.app.ui.components.BrandMark
import com.tether.app.ui.components.Wordmark
import com.tether.app.ui.icons.ProviderLogo
import com.tether.app.ui.icons.ProviderLogoDefaults
import com.tether.app.ui.icons.ProviderLogos
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherLabelStyle
import com.tether.app.ui.theme.TetherTypography

/**
 * The gallery sections that have goldens of their own (the primitives' goldens are T3.3's, in
 * :core:designsystem). Key = golden folder under `src/testDebug/screenshots/gallery/`. The icon
 * grid is split in three and the type roles in two so each part fits a phone-height window.
 */
val GalleryGoldens: Map<String, Pair<String, @Composable ColumnScope.() -> Unit>> = linkedMapOf(
    "typography-1" to ("Typography 1/2" to { TypographyRoles(0, TypeSplit) }),
    "typography-2" to ("Typography 2/2" to { TypographyRoles(TypeSplit, Int.MAX_VALUE) }),
    "icons-1" to ("Icons 1/3" to { IconGrid(IconNames.subList(0, 46)) }),
    "icons-2" to ("Icons 2/3" to { IconGrid(IconNames.subList(46, 92)) }),
    "icons-3" to ("Icons 3/3" to { IconGrid(IconNames.subList(92, IconNames.size)) }),
    "provider-logos" to ("Provider logos" to { ProviderLogosSection() }),
    "launcher-icon" to ("Launcher icon" to { LauncherIconSection() }),
)

// ---------------------------------------------------------------------------------------------
// Typography (T3.2)

/** One type role as the gallery shows it: name, the CSS source, and a sample. */
private class TypeSample(val name: String, val source: String, val sample: @Composable (TetherTypography) -> Unit)

@Composable
private fun RoleText(text: String, style: TextStyle) {
    Text(text, color = LocalTetherTokens.current.ink, style = style)
}

@Composable
private fun LabelText(text: String, label: TetherLabelStyle) {
    Text(label.format(text), color = LocalTetherTokens.current.ink, style = label.style)
}

private val TypeSamples: List<TypeSample> = listOf(
    TypeSample("body", "body") { RoleText("The quick brown fox", it.body) },
    TypeSample("chatBody", ".chat-bubble") {
        val codeInline = it.codeInline
        Text(
            buildAnnotatedString {
                append("Run ")
                withStyle(codeInline) { append("npm test") }
                append(" and report back.")
            },
            color = LocalTetherTokens.current.ink,
            style = it.chatBody,
        )
    },
    TypeSample("composerInput", ".chat-input") { RoleText("Ask Claude to…", it.composerInput) },
    TypeSample("screenTitle", ".workspace-title-row h1") { RoleText("tether-android", it.screenTitle) },
    TypeSample("displayTitle", ".empty-workspace h1") { RoleText("Start a session", it.displayTitle) },
    TypeSample("markdownH3", ".md-h h3") { RoleText("Heading three", it.markdownH3) },
    TypeSample("markdownH4", ".md-h h4") { RoleText("Heading four", it.markdownH4) },
    TypeSample("markdownH5", ".md-h h5/h6") { RoleText("Heading five", it.markdownH5) },
    TypeSample("listTitle", ".session-item-copy strong") { RoleText("Fix the flaky login test", it.listTitle) },
    TypeSample("codeBlock", ".md-pre code") { RoleText("fun main() = println(\"hi\")", it.codeBlock) },
    TypeSample("timestamp", ".chat-msg-time") { RoleText("12:04:59", it.timestamp) },
    TypeSample("numeral", ".usage-totals strong") { RoleText("1,284,090", it.numeral) },
    TypeSample("sectionLabel", ".section-label") { LabelText("Recent sessions", it.sectionLabel) },
    TypeSample("statusLabel", ".status-badge") { LabelText("Waiting", it.statusLabel) },
    TypeSample("keyLabel", ".button-primary legend") { LabelText("Save settings", it.keyLabel) },
)

private const val TypeSplit = 8

/** The T3.2 type roles of the current skin, [from] until [to] (exclusive), plus the two faces. */
@Composable
fun TypographyRoles(from: Int, to: Int) {
    val type = LocalTetherTypography.current
    val t = LocalTetherTokens.current
    val caption = TextStyle(fontFamily = type.mono, fontSize = 10.sp)
    if (from == 0) {
        GalleryRow("faces: ui · mono", wrap = false) {
            Text("Manrope Variable", color = t.ink, style = type.body.copy(fontFamily = type.ui))
            Text("JetBrains Mono", color = t.ink, style = type.body.copy(fontFamily = type.mono))
        }
    }
    for (s in TypeSamples.subList(from, minOf(to, TypeSamples.size))) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${s.name} · ${s.source} · ${describe(roleStyle(type, s.name))}", color = t.faint, style = caption)
            s.sample(type)
        }
    }
}

@Composable
fun TypographySection() = TypographyRoles(0, Int.MAX_VALUE)

private fun roleStyle(type: TetherTypography, name: String): TextStyle = when (name) {
    "body" -> type.body
    "chatBody" -> type.chatBody
    "composerInput" -> type.composerInput
    "screenTitle" -> type.screenTitle
    "displayTitle" -> type.displayTitle
    "markdownH3" -> type.markdownH3
    "markdownH4" -> type.markdownH4
    "markdownH5" -> type.markdownH5
    "listTitle" -> type.listTitle
    "codeBlock" -> type.codeBlock
    "timestamp" -> type.timestamp
    "numeral" -> type.numeral
    "sectionLabel" -> type.sectionLabel.style
    "statusLabel" -> type.statusLabel.style
    "keyLabel" -> type.keyLabel.style
    else -> error("unknown role $name")
}

/** "Manrope 14.7sp w400 lh1.65em +0.1em" — what the role resolves to in this skin. */
private fun describe(style: TextStyle): String {
    val face = when (style.fontFamily) {
        Manrope -> "Manrope"
        JetBrainsMono -> "Mono"
        else -> style.fontFamily.toString()
    }
    val parts = mutableListOf(face, "${fmt(style.fontSize)}", "w${(style.fontWeight ?: FontWeight.Normal).weight}")
    if (style.letterSpacing != TextUnit.Unspecified && style.letterSpacing.value != 0f) parts += "ls${fmt(style.letterSpacing)}"
    if (style.lineHeight != TextUnit.Unspecified) parts += "lh${fmt(style.lineHeight)}"
    if (style.fontFeatureSettings != null) parts += style.fontFeatureSettings!!
    return parts.joinToString(" ")
}

private fun fmt(u: TextUnit): String {
    val v = (Math.round(u.value * 100) / 100.0).toString().removeSuffix(".0")
    return v + if (u.isSp) "sp" else if (u.isEm) "em" else ""
}

// ---------------------------------------------------------------------------------------------
// Icons (T3.5)

/** Every lucide-react name the web imports (136), in TetherIcons' order. */
val IconNames: List<String> = TetherIcons.byWebName.keys.toList()

/** Two columns of glyph + web name; a deprecated alias (drawn as its canonical glyph) is marked `*`. */
@Composable
fun IconGrid(names: List<String>) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val nameStyle = TextStyle(fontFamily = type.mono, fontSize = 10.sp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in names.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (name in row) {
                    Row(Modifier.weight(1f).height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(TetherIcons.byWebName.getValue(name), contentDescription = name, tint = t.ink, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        val alias = name in TetherIcons.deprecatedAliases
                        Text(
                            if (alias) "$name*" else name,
                            color = if (alias) t.muted else t.ink,
                            style = nameStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Provider logos (T3.5) and the brand

/** The web's LOGO_MARKS providers, the letter fallbacks, and an unknown/empty id. */
val GalleryProviders: List<String> = ProviderLogos.paths.keys.toList() + listOf("gemini", "reasonix", "pi", "acp", "")

@Composable
fun ProviderLogosSection() {
    val t = LocalTetherTokens.current
    val caption = TextStyle(fontFamily = LocalTetherTypography.current.mono, fontSize = 10.sp)
    GalleryRow("2rem glyph circle (neutral stand-in; .provider-glyph's material is the sidebar's) · mark 58% · letter fallback") {
        for (p in GalleryProviders) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier.size(ProviderLogoDefaults.GlyphSize).clip(CircleShape).background(t.graphiteRaised).border(1.dp, t.line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    ProviderLogo(p, color = ProviderLogoDefaults.color(p))
                }
                Text(p.ifEmpty { "(empty)" }, color = t.faint, style = caption)
            }
        }
    }
    GalleryRow("bare marks at 24dp and 48dp, ink") {
        for (p in ProviderLogos.paths.keys) {
            ProviderLogo(p, color = t.ink, markSize = 24.dp)
            ProviderLogo(p, color = t.ink, markSize = 48.dp)
        }
    }
    GalleryRow("brand mark · wordmark (on graphite)") {
        Row(
            Modifier.background(t.graphite).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BrandMark()
            Wordmark()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Adaptive launcher icon (T3.5)

/** A 108dp adaptive layer's visible viewport: the inner 72dp, which the launcher's mask clips. */
private val LayerSize = 108.dp
private val ViewportSize = 72.dp

/** Launcher masks (OEM shapes vary; these are the common ones). */
private val LauncherMasks: List<Pair<String, Shape>> = listOf(
    "circle" to CircleShape,
    "squircle" to RoundedCornerShape(32),
    "rounded" to RoundedCornerShape(18),
    "teardrop" to RoundedCornerShape(50, 50, 50, 12),
    "square" to RectangleShape,
)

/** Material You themed-icon tints (Android 13+): representative light and dark system palettes. */
private val ThemedLight = Color(0xFFD3E3FD) to Color(0xFF041E49)
private val ThemedDark = Color(0xFF004A77) to Color(0xFFC2E7FF)

@Composable
fun LauncherIconSection() {
    val t = LocalTetherTokens.current
    GalleryRow("layers, full 108dp (dashed box: the 72dp viewport): background · foreground · monochrome") {
        LayerTile { Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.size(LayerSize)) }
        LayerTile { Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(LayerSize)) }
        LayerTile { Image(painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.size(LayerSize), colorFilter = ColorFilter.tint(t.ink)) }
    }
    GalleryRow("adaptive, masked: circle · squircle · rounded · teardrop · square") {
        for ((_, shape) in LauncherMasks) {
            MaskedIcon(shape) {
                Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(LayerSize))
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(LayerSize))
            }
        }
    }
    GalleryRow("themed (monochrome layer, representative Material You tints): light · dark") {
        for ((bg, fg) in listOf(ThemedLight, ThemedDark)) {
            MaskedIcon(CircleShape) {
                Box(Modifier.requiredSize(LayerSize).background(bg))
                Image(painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.requiredSize(LayerSize), colorFilter = ColorFilter.tint(fg))
            }
        }
    }
    GalleryRow("platform-drawn @mipmap/ic_launcher (AdaptiveIconDrawable, the device mask) · round") {
        PlatformIcon(R.mipmap.ic_launcher)
        PlatformIcon(R.mipmap.ic_launcher_round)
    }
}

@Composable
private fun LayerTile(content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    Box(Modifier.size(LayerSize).border(1.dp, t.line), contentAlignment = Alignment.Center) {
        content()
        Box(Modifier.size(ViewportSize).dashedBorder(t.faint))
    }
}

@Composable
private fun MaskedIcon(shape: Shape, content: @Composable () -> Unit) {
    Box(Modifier.size(ViewportSize).clip(shape), contentAlignment = Alignment.Center) { content() }
}

/** The launcher icon exactly as the platform draws it (its own adaptive mask and layer scale). */
@Composable
private fun PlatformIcon(res: Int, size: Dp = ViewportSize) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(res, px) {
        val drawable = requireNotNull(context.getDrawable(res))
        Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { b ->
            drawable.setBounds(0, 0, px, px)
            drawable.draw(Canvas(b))
        }
    }
    Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size))
}

private fun Modifier.dashedBorder(color: Color): Modifier = drawBehind {
    drawRect(
        color = color,
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}
