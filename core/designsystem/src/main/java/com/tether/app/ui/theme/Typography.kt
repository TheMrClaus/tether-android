package com.tether.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * The web's type scale as typed roles (PLAN T3.2). The web has no type-scale tokens: each role
 * below is the *cascaded* value of one web selector at phone width (`max-width: 47.9375rem`),
 * with the CSS lines it comes from. Where a token exists it is read, never restated:
 * `--font-mono`, `--font-ui`, `--key-label-tracking`, `--key-label-transform` ([SkinTokens]).
 *
 * Unit mapping (same as the token generator): 1rem = 16sp — `sp`, so the scale follows the
 * system font scale (1.3× included); CSS `em` letter-spacing -> [TextUnit] em (relative to the
 * font size, like CSS); unitless CSS line-height -> em (a multiple of the font size);
 * `line-height: normal` -> [TextUnit.Unspecified] (the font's own metrics). Every role with a
 * line-height centres its half-leading like CSS ([CssLineHeight]). Nothing here fixes a
 * container height.
 *
 * Skins: the four instrument skins (tactile/night/precision/machine) share one scale — only
 * `--key-label-tracking` differs (0.05em vs 0.06em). Studio re-dresses the labels in
 * app/studio.css: micro-labels move to `var(--font-ui)` (Manrope — the same bundled face, so no
 * extra font; its -apple-system/Segoe UI fallbacks don't exist on Android), drop the
 * uppercase and the tracking, and several roles change size/weight/leading.
 */
@Immutable
class TetherTypography internal constructor(val skin: TetherSkin) {
    private val t: SkinTokens = skin.tokens
    private val studio: Boolean = skin.family == ThemeFamily.Studio

    /** The UI face: Studio's `--font-ui`, else the `body` stack (globals.css:609). */
    val ui: FontFamily = fontFamilyForStack(t.fontUi ?: BODY_FONT_STACK)

    /** The mono face: `--font-mono` (globals.css:58). */
    val mono: FontFamily = fontFamilyForStack(t.fontMono)

    /** `body` — globals.css:609-610: 1rem, weight 400 (initial), line-height normal. */
    val body: TextStyle = uiRole(1f, 400)

    /**
     * Chat bubble text (`.chat-bubble`). Instrument: 0.92rem (globals.css:11272), line-height
     * 1.65 (globals.css:11743). Studio: 0.9rem on phones (studio.css:455), line-height 1.8
     * (studio.css:372).
     */
    val chatBody: TextStyle = if (studio) uiRole(0.9f, 400, lineHeight = 1.8f) else uiRole(0.92f, 400, lineHeight = 1.65f)

    /**
     * The composer textarea (`.chat-input`). Instrument: 1rem on phones (globals.css:8512),
     * line-height 1.5 (globals.css:6741). Studio: `.chat-composer-well .chat-input:focus`
     * 0.925rem / 1.6 (studio.css:386-387) — the focused (typing) state; the phone rule
     * (studio.css:458, 1rem) only reaches the unfocused textarea, the focus selector outranks it.
     */
    val composerInput: TextStyle = if (studio) uiRole(0.925f, 400, lineHeight = 1.6f) else uiRole(1f, 400, lineHeight = 1.5f)

    /**
     * The workspace header title (`.workspace-title-row h1`). Instrument: 1.05rem
     * (globals.css:11859), 680, -0.01em (globals.css:1686-1687), line-height 1.45
     * (globals.css:11742). Studio: 740, -0.025em (studio.css:352), 0.925rem on phones
     * (studio.css:452); line-height 1.45 inherited from globals.css:11742.
     */
    val screenTitle: TextStyle = if (studio) {
        uiRole(0.925f, 740, tracking = -0.025f, lineHeight = 1.45f)
    } else {
        uiRole(1.05f, 680, tracking = -0.01f, lineHeight = 1.45f)
    }

    /**
     * The empty-state display heading (`.empty-workspace h1`): clamp(1.7rem, 2.6vw, 2.15rem)
     * (globals.css:11257) is its 1.7rem floor on a phone; 640, -0.035em (globals.css:11257),
     * line-height 1.18 (globals.css:2285). Studio does not restyle it.
     */
    val displayTitle: TextStyle = uiRole(1.7f, 640, tracking = -0.035f, lineHeight = 1.18f)

    /** Markdown headings (`.md-h`, globals.css:4915-4918): 680, line-height 1.3; all skins. */
    val markdownH3: TextStyle = uiRole(1.05f, 680, lineHeight = 1.3f)
    val markdownH4: TextStyle = uiRole(0.98f, 680, lineHeight = 1.3f)

    /** h5/h6 share one size (globals.css:4918). */
    val markdownH5: TextStyle = uiRole(0.92f, 680, lineHeight = 1.3f)

    /**
     * A list row's title (`.session-item-copy strong`). Instrument: 0.84rem, 650, -0.005em
     * (globals.css:11113), line-height 1.4 (globals.css:11736). Studio: 0.78rem, 600,
     * line-height 1.45 (studio.css:331); the -0.005em tracking is not reset, so it stays.
     */
    val listTitle: TextStyle = if (studio) {
        uiRole(0.78f, 600, tracking = -0.005f, lineHeight = 1.45f)
    } else {
        uiRole(0.84f, 650, tracking = -0.005f, lineHeight = 1.4f)
    }

    /** Fenced code (`.md-pre code`, globals.css:5032-5035): mono 0.8rem, line-height 1.5; all skins. */
    val codeBlock: TextStyle = monoRole(0.8f, 400, lineHeight = 1.5f)

    /**
     * Inline code (`.md-code`, globals.css:4951-4953): mono at 0.85em of the surrounding text —
     * relative, so it is a [SpanStyle] for an AnnotatedString inside another role.
     */
    val codeInline: SpanStyle = SpanStyle(fontFamily = mono, fontSize = 0.85.em)

    /**
     * A message send-time (`.chat-msg-time`, globals.css:4856-4863): 0.65rem, tabular figures;
     * line-height inherited from the bubble. Studio: 0.64rem in `--font-ui` (studio.css:375).
     */
    val timestamp: TextStyle = if (studio) {
        uiRole(0.64f, 400, lineHeight = 1.8f, tabular = true)
    } else {
        uiRole(0.65f, 400, lineHeight = 1.65f, tabular = true)
    }

    /**
     * A headline figure (`.usage-totals strong`): mono 1.35rem (globals.css:11748), 640, -0.01em,
     * tabular figures (globals.css:11573); all skins.
     */
    val numeral: TextStyle = monoRole(1.35f, 640, tracking = -0.01f, tabular = true)

    /**
     * The uppercase micro-label (`.section-label`). Instrument: 0.67rem, 720, 0.1em, uppercase
     * (globals.css:2270-2277). Studio: 0.75rem, no transform, tracking 0 (studio.css:279).
     */
    val sectionLabel: TetherLabelStyle = if (studio) {
        TetherLabelStyle(uiRole(0.75f, 720), uppercase = false)
    } else {
        TetherLabelStyle(uiRole(0.67f, 720, tracking = 0.1f), uppercase = true)
    }

    /**
     * The etched mono status legend (`.status-badge`). Instrument: JetBrains Mono 0.6rem, 700,
     * 0.08em, uppercase (globals.css:11162-11173). Studio: `font: 600 0.65rem var(--font-ui)`,
     * no transform, tracking 0 (studio.css:354).
     */
    val statusLabel: TetherLabelStyle = if (studio) {
        TetherLabelStyle(uiRole(0.65f, 600), uppercase = false)
    } else {
        TetherLabelStyle(monoRole(0.6f, 700, tracking = 0.08f), uppercase = true)
    }

    /**
     * A fixed-verb key legend (`.button-primary`/`.button-secondary`: 0.8rem, 680 —
     * globals.css:2307-2308) with the etched-legend transform and tracking from the tokens
     * `--key-label-transform` / `--key-label-tracking` (globals.css:9204-9213; Studio: none / 0,
     * studio.css:77-78). Never for user or provider content (globals.css:9200-9203).
     */
    val keyLabel: TetherLabelStyle = TetherLabelStyle(
        uiRole(0.8f, 680).copy(letterSpacing = t.keyLabelTracking),
        uppercase = t.keyLabelTransform == "uppercase",
    )

    private fun uiRole(rem: Float, weight: Int, tracking: Float = 0f, lineHeight: Float? = null, tabular: Boolean = false) =
        role(ui, rem, weight, tracking, lineHeight, tabular)

    private fun monoRole(rem: Float, weight: Int, tracking: Float = 0f, lineHeight: Float? = null, tabular: Boolean = false) =
        role(mono, rem, weight, tracking, lineHeight, tabular)

    override fun equals(other: Any?): Boolean = other is TetherTypography && other.skin == skin
    override fun hashCode(): Int = skin.hashCode()
    override fun toString(): String = "TetherTypography(${skin.id})"

    companion object {
        /** `body { font-family }` — globals.css:609 (instrument skins define no `--font-ui`). */
        const val BODY_FONT_STACK: String = "\"Manrope Variable\", -apple-system, BlinkMacSystemFont, \"Segoe UI\", sans-serif"

        /** CSS `font-variant-numeric: tabular-nums`. */
        const val TABULAR_NUMS: String = "tnum"

        /** CSS rem -> sp (1rem = 16px = 16sp at font scale 1, the token generator's mapping). */
        const val SP_PER_REM: Float = 16f
    }
}

/** CSS places half the leading above and half below each line; Compose's default is proportional. */
val CssLineHeight: LineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun role(family: FontFamily, rem: Float, weight: Int, tracking: Float, lineHeight: Float?, tabular: Boolean) = TextStyle(
    fontFamily = family,
    fontSize = (rem * TetherTypography.SP_PER_REM).sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
    lineHeight = lineHeight?.em ?: TextUnit.Unspecified,
    lineHeightStyle = CssLineHeight,
    fontFeatureSettings = if (tabular) TetherTypography.TABULAR_NUMS else null,
)

/**
 * A label role plus its CSS `text-transform`, which Compose's [TextStyle] has no field for.
 * Render `format(text)`; like the web (where text-transform leaves the DOM text alone), keep
 * the original words for accessibility where the uppercase form would read differently.
 */
@Immutable
data class TetherLabelStyle(val style: TextStyle, val uppercase: Boolean) {
    fun format(text: String): String = if (uppercase) text.uppercase(Locale.ROOT) else text
}

/** Numerals that must not jitter as they change (`font-variant-numeric: tabular-nums`). */
fun TextStyle.tabularNums(): TextStyle = copy(fontFeatureSettings = TetherTypography.TABULAR_NUMS)

/**
 * Resolves a CSS font stack to a bundled family: the first family we can honour wins —
 * `Manrope Variable` / `JetBrains Mono Variable` (the bundled faces), else the generic
 * `monospace`/`ui-monospace`/`sans-serif`; platform names (-apple-system, Segoe UI…) are skipped.
 */
fun fontFamilyForStack(stack: String): FontFamily {
    for (raw in stack.split(',')) {
        when (raw.trim().trim('"', '\'').lowercase(Locale.ROOT)) {
            "manrope variable", "manrope" -> return Manrope
            "jetbrains mono variable", "jetbrains mono" -> return JetBrainsMono
            "monospace", "ui-monospace" -> return FontFamily.Monospace
            "sans-serif", "system-ui", "ui-sans-serif" -> return FontFamily.SansSerif
        }
    }
    return FontFamily.SansSerif
}

private val typographyBySkin: Map<TetherSkin, TetherTypography> = TetherSkin.entries.associateWith(::TetherTypography)

fun typographyFor(skin: TetherSkin): TetherTypography = typographyBySkin.getValue(skin)

val LocalTetherTypography = staticCompositionLocalOf { typographyFor(TetherSkin.Machine) }
