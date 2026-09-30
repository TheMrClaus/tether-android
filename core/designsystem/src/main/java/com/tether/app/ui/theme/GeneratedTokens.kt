// GENERATED FILE — DO NOT EDIT. PLAN D9 / T3.1.
// Source: parity-corpus/tokens/design-tokens.json — tether 356b456 (scripts/export-design-tokens.mjs),
// protocol v128, from app/globals.css + app/studio.css.
// Generator: tools/design-tokens. Regenerate: ./gradlew generateDesignTokens
// Drift check: ./gradlew verifyDesignTokens (wired into :core:designsystem:check).
// 2 skins, 126 tokens. Category mapping:
//   color      #hex / rgb() / rgba() / transparent -> Color (sRGB ARGB; alpha quantized like Compose's Color(Float...))
//   rgb_triple bare `r g b` channel triple (read as rgb(var(--x) / a) in CSS) -> opaque Color
//   length     px / rem length (bare 0 allowed) -> Dp; 1px = 1dp, 1rem = 16dp
//   em         em length (bare 0 allowed) -> TextUnit(Em)
//   percent    percentage -> Float fraction (86% -> 0.86)
//   duration   ms / s duration -> Int milliseconds
//   int        unitless integer -> Int
//   number     unitless number -> Float
//   easing     cubic-bezier() -> CssCubicBezier (toEasing() gives the Compose Easing)
//   shadow     box-shadow list -> List<CssShadow> (outer + inset layers; none -> empty)
//   string     no Compose equivalent (gradients, keywords, font stacks) -> resolved CSS text

package com.tether.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType

/** Provenance of the token corpus this file was generated from. */
object DesignTokenSource {
    const val TETHER_SHA: String = "356b456"
    const val PROTOCOL_VERSION: Int = 128
    const val EXPORTER: String = "scripts/export-design-tokens.mjs"
    val SOURCES: List<String> = listOf("app/globals.css", "app/studio.css")
}

/**
 * The web skins (`<html data-theme>`): Studio light and dark, in skinMap order. [isDark] is the skin's CSS
 * `color-scheme`; [systemBarColor] is its `chrome.graphite` (the web's theme-color / boot-script
 * COLORS entry), used for the status and navigation bars.
 */
enum class TetherSkin(val id: String, val isDark: Boolean, val systemBarColor: Color) {
    Studio("studio", isDark = false, systemBarColor = Color(0xFFFFFFFF)),
    StudioDark("studio-dark", isDark = true, systemBarColor = Color(0xFF172032)),
    ;

    /** This skin's complete token set. */
    val tokens: SkinTokens
        get() = when (this) {
            Studio -> GeneratedTokens.Studio
            StudioDark -> GeneratedTokens.StudioDark
        }

    companion object {
        fun fromId(id: String?): TetherSkin? = entries.firstOrNull { it.id == id }

        /** The web's resolveThemeSkin: concrete lighting → skin. */
        fun of(dark: Boolean): TetherSkin = if (dark) StudioDark else Studio
    }
}

/** One skin's full CSS custom-property set, typed. Property KDoc names the CSS variable. */
@Immutable
data class SkinTokens(
    /** `--accent` (color) */
    val accent: Color,
    /** `--accent-deep` (color) */
    val accentDeep: Color,
    /** `--accent-hover` (color) */
    val accentHover: Color,
    /** `--accent-ink` (color) */
    val accentInk: Color,
    /** `--accent-side` (color) */
    val accentSide: Color,
    /** `--accent-wash` (color) */
    val accentWash: Color,
    /** `--active` (color) */
    val active: Color,
    /** `--active-wash` (color) */
    val activeWash: Color,
    /** `--amber` (color) */
    val amber: Color,
    /** `--amber-wash` (color) */
    val amberWash: Color,
    /** `--attention-bg` (color) */
    val attentionBg: Color,
    /** `--attention-border` (color) */
    val attentionBorder: Color,
    /** `--attention-ink` (color) */
    val attentionInk: Color,
    /** `--bay-floor` (color) */
    val bayFloor: Color,
    /** `--bevel-pressed` (shadow) */
    val bevelPressed: List<CssShadow>,
    /** `--bevel-raised` (shadow) */
    val bevelRaised: List<CssShadow>,
    /** `--bevel-raised-sm` (shadow) */
    val bevelRaisedSm: List<CssShadow>,
    /** `--bezel` (shadow) */
    val bezel: List<CssShadow>,
    /** `--border` (color) */
    val border: Color,
    /** `--brick` (color) */
    val brick: Color,
    /** `--brick-deep` (color) */
    val brickDeep: Color,
    /** `--brick-side` (color) */
    val brickSide: Color,
    /** `--brick-wash` (color) */
    val brickWash: Color,
    /** `--charcoal` (color) */
    val charcoal: Color,
    /** `--charcoal-side` (color) */
    val charcoalSide: Color,
    /** `--chat-bubble-max` (percent) */
    val chatBubbleMax: Float,
    /** `--chat-card-width` (percent) */
    val chatCardWidth: Float,
    /** `--chat-clamp` (length) */
    val chatClamp: Dp,
    /** `--contact` (rgb_triple) */
    val contact: Color,
    /** `--danger` (color) */
    val danger: Color,
    /** `--danger-edge` (color) */
    val dangerEdge: Color,
    /** `--danger-wash` (color) */
    val dangerWash: Color,
    /** `--diff-add-bg` (color) */
    val diffAddBg: Color,
    /** `--diff-add-ink` (color) */
    val diffAddInk: Color,
    /** `--diff-del-bg` (color) */
    val diffDelBg: Color,
    /** `--diff-del-ink` (color) */
    val diffDelInk: Color,
    /** `--drop-overlay` (color) */
    val dropOverlay: Color,
    /** `--duration` (duration) */
    val duration: Int,
    /** `--duration-fast` (duration) */
    val durationFast: Int,
    /** `--ease-out` (easing) */
    val easeOut: CssCubicBezier,
    /** `--edge-highlight` (shadow) */
    val edgeHighlight: List<CssShadow>,
    /** `--faint` (color) */
    val faint: Color,
    /** `--find-match-active-bg` (color) */
    val findMatchActiveBg: Color,
    /** `--find-match-bg` (color) */
    val findMatchBg: Color,
    /** `--find-match-ink` (color) */
    val findMatchInk: Color,
    /** `--focus-glow` (color) */
    val focusGlow: Color,
    /** `--font-mono` (string) */
    val fontMono: String,
    /** `--font-ui` (string) */
    val fontUi: String,
    /** `--graphite` (color) */
    val graphite: Color,
    /** `--graphite-raised` (color) */
    val graphiteRaised: Color,
    /** `--ink` (color) */
    val ink: Color,
    /** `--inspector-width` (length) */
    val inspectorWidth: Dp,
    /** `--key-face` (color) */
    val keyFace: Color,
    /** `--key-face-deep` (color) */
    val keyFaceDeep: Color,
    /** `--key-face-hover` (color) */
    val keyFaceHover: Color,
    /** `--key-label-tracking` (em) */
    val keyLabelTracking: TextUnit,
    /** `--key-label-transform` (string) */
    val keyLabelTransform: String,
    /** `--key-side` (color) */
    val keySide: Color,
    /** `--key-slit` (length) */
    val keySlit: Dp,
    /** `--line` (color) */
    val line: Color,
    /** `--line-strong` (color) */
    val lineStrong: Color,
    /** `--lit-faint` (color) */
    val litFaint: Color,
    /** `--lit-soft` (color) */
    val litSoft: Color,
    /** `--lit-strong` (color) */
    val litStrong: Color,
    /** `--mineral` (color) */
    val mineral: Color,
    /** `--mineral-deep` (color) */
    val mineralDeep: Color,
    /** `--muted` (color) */
    val muted: Color,
    /** `--panel-veil` (color) */
    val panelVeil: Color,
    /** `--perf-dots` (string) */
    val perfDots: String,
    /** `--press-shade` (color) */
    val pressShade: Color,
    /** `--press-travel` (length) */
    val pressTravel: Dp,
    /** `--question-bg` (color) */
    val questionBg: Color,
    /** `--question-border` (color) */
    val questionBorder: Color,
    /** `--question-ink` (color) */
    val questionInk: Color,
    /** `--radius-key` (length) */
    val radiusKey: Dp,
    /** `--radius-lg` (length) */
    val radiusLg: Dp,
    /** `--radius-md` (length) */
    val radiusMd: Dp,
    /** `--radius-sm` (length) */
    val radiusSm: Dp,
    /** `--rail-width` (length) */
    val railWidth: Dp,
    /** `--rocker-ms` (duration) */
    val rockerMs: Int,
    /** `--running` (color) */
    val running: Color,
    /** `--scrim` (color) */
    val scrim: Color,
    /** `--seam-lip` (color) */
    val seamLip: Color,
    /** `--selection-bg` (color) */
    val selectionBg: Color,
    /** `--shadow-floating` (shadow) */
    val shadowFloating: List<CssShadow>,
    /** `--shadow-key` (shadow) */
    val shadowKey: List<CssShadow>,
    /** `--shadow-key-pressed` (shadow) */
    val shadowKeyPressed: List<CssShadow>,
    /** `--shadow-key-sm` (shadow) */
    val shadowKeySm: List<CssShadow>,
    /** `--shadow-menu` (shadow) */
    val shadowMenu: List<CssShadow>,
    /** `--shadow-menu-a` (number) */
    val shadowMenuA: Float,
    /** `--shadow-menu-up` (shadow) */
    val shadowMenuUp: List<CssShadow>,
    /** `--shadow-modal` (shadow) */
    val shadowModal: List<CssShadow>,
    /** `--shadow-raised` (shadow) */
    val shadowRaised: List<CssShadow>,
    /** `--slate` (color) */
    val slate: Color,
    /** `--space-2xl` (length) */
    val space2xl: Dp,
    /** `--space-lg` (length) */
    val spaceLg: Dp,
    /** `--space-md` (length) */
    val spaceMd: Dp,
    /** `--space-sm` (length) */
    val spaceSm: Dp,
    /** `--space-xl` (length) */
    val spaceXl: Dp,
    /** `--space-xs` (length) */
    val spaceXs: Dp,
    /** `--surface-2` (color) */
    val surface2: Color,
    /** `--tint-boost` (number) */
    val tintBoost: Float,
    /** `--tint-lg` (color) */
    val tintLg: Color,
    /** `--tint-line` (color) */
    val tintLine: Color,
    /** `--tint-md` (color) */
    val tintMd: Color,
    /** `--tint-rgb` (rgb_triple) */
    val tintRgb: Color,
    /** `--tint-sm` (color) */
    val tintSm: Color,
    /** `--tint-xs` (color) */
    val tintXs: Color,
    /** `--user-bubble-bg` (color) */
    val userBubbleBg: Color,
    /** `--user-bubble-border` (color) */
    val userBubbleBorder: Color,
    /** `--user-bubble-ink` (color) */
    val userBubbleInk: Color,
    /** `--utility-ink` (color) */
    val utilityInk: Color,
    /** `--violet` (color) */
    val violet: Color,
    /** `--violet-deep` (color) */
    val violetDeep: Color,
    /** `--violet-soft` (color) */
    val violetSoft: Color,
    /** `--violet-strong` (color) */
    val violetStrong: Color,
    /** `--violet-wash` (color) */
    val violetWash: Color,
    /** `--warning` (color) */
    val warning: Color,
    /** `--wear-hi` (color) */
    val wearHi: Color,
    /** `--wear-lo` (color) */
    val wearLo: Color,
    /** `--well` (shadow) */
    val well: List<CssShadow>,
    /** `--white` (color) */
    val white: Color,
    /** `--z-backdrop` (int) */
    val zBackdrop: Int,
    /** `--z-modal` (int) */
    val zModal: Int,
    /** `--z-sticky` (int) */
    val zSticky: Int,
    /** `--z-toast` (int) */
    val zToast: Int,
) {
    /** Every token keyed by its CSS name (tests compare this against the JSON). */
    fun byCssName(): Map<String, Any?> = linkedMapOf(
        "--accent" to accent,
        "--accent-deep" to accentDeep,
        "--accent-hover" to accentHover,
        "--accent-ink" to accentInk,
        "--accent-side" to accentSide,
        "--accent-wash" to accentWash,
        "--active" to active,
        "--active-wash" to activeWash,
        "--amber" to amber,
        "--amber-wash" to amberWash,
        "--attention-bg" to attentionBg,
        "--attention-border" to attentionBorder,
        "--attention-ink" to attentionInk,
        "--bay-floor" to bayFloor,
        "--bevel-pressed" to bevelPressed,
        "--bevel-raised" to bevelRaised,
        "--bevel-raised-sm" to bevelRaisedSm,
        "--bezel" to bezel,
        "--border" to border,
        "--brick" to brick,
        "--brick-deep" to brickDeep,
        "--brick-side" to brickSide,
        "--brick-wash" to brickWash,
        "--charcoal" to charcoal,
        "--charcoal-side" to charcoalSide,
        "--chat-bubble-max" to chatBubbleMax,
        "--chat-card-width" to chatCardWidth,
        "--chat-clamp" to chatClamp,
        "--contact" to contact,
        "--danger" to danger,
        "--danger-edge" to dangerEdge,
        "--danger-wash" to dangerWash,
        "--diff-add-bg" to diffAddBg,
        "--diff-add-ink" to diffAddInk,
        "--diff-del-bg" to diffDelBg,
        "--diff-del-ink" to diffDelInk,
        "--drop-overlay" to dropOverlay,
        "--duration" to duration,
        "--duration-fast" to durationFast,
        "--ease-out" to easeOut,
        "--edge-highlight" to edgeHighlight,
        "--faint" to faint,
        "--find-match-active-bg" to findMatchActiveBg,
        "--find-match-bg" to findMatchBg,
        "--find-match-ink" to findMatchInk,
        "--focus-glow" to focusGlow,
        "--font-mono" to fontMono,
        "--font-ui" to fontUi,
        "--graphite" to graphite,
        "--graphite-raised" to graphiteRaised,
        "--ink" to ink,
        "--inspector-width" to inspectorWidth,
        "--key-face" to keyFace,
        "--key-face-deep" to keyFaceDeep,
        "--key-face-hover" to keyFaceHover,
        "--key-label-tracking" to keyLabelTracking,
        "--key-label-transform" to keyLabelTransform,
        "--key-side" to keySide,
        "--key-slit" to keySlit,
        "--line" to line,
        "--line-strong" to lineStrong,
        "--lit-faint" to litFaint,
        "--lit-soft" to litSoft,
        "--lit-strong" to litStrong,
        "--mineral" to mineral,
        "--mineral-deep" to mineralDeep,
        "--muted" to muted,
        "--panel-veil" to panelVeil,
        "--perf-dots" to perfDots,
        "--press-shade" to pressShade,
        "--press-travel" to pressTravel,
        "--question-bg" to questionBg,
        "--question-border" to questionBorder,
        "--question-ink" to questionInk,
        "--radius-key" to radiusKey,
        "--radius-lg" to radiusLg,
        "--radius-md" to radiusMd,
        "--radius-sm" to radiusSm,
        "--rail-width" to railWidth,
        "--rocker-ms" to rockerMs,
        "--running" to running,
        "--scrim" to scrim,
        "--seam-lip" to seamLip,
        "--selection-bg" to selectionBg,
        "--shadow-floating" to shadowFloating,
        "--shadow-key" to shadowKey,
        "--shadow-key-pressed" to shadowKeyPressed,
        "--shadow-key-sm" to shadowKeySm,
        "--shadow-menu" to shadowMenu,
        "--shadow-menu-a" to shadowMenuA,
        "--shadow-menu-up" to shadowMenuUp,
        "--shadow-modal" to shadowModal,
        "--shadow-raised" to shadowRaised,
        "--slate" to slate,
        "--space-2xl" to space2xl,
        "--space-lg" to spaceLg,
        "--space-md" to spaceMd,
        "--space-sm" to spaceSm,
        "--space-xl" to spaceXl,
        "--space-xs" to spaceXs,
        "--surface-2" to surface2,
        "--tint-boost" to tintBoost,
        "--tint-lg" to tintLg,
        "--tint-line" to tintLine,
        "--tint-md" to tintMd,
        "--tint-rgb" to tintRgb,
        "--tint-sm" to tintSm,
        "--tint-xs" to tintXs,
        "--user-bubble-bg" to userBubbleBg,
        "--user-bubble-border" to userBubbleBorder,
        "--user-bubble-ink" to userBubbleInk,
        "--utility-ink" to utilityInk,
        "--violet" to violet,
        "--violet-deep" to violetDeep,
        "--violet-soft" to violetSoft,
        "--violet-strong" to violetStrong,
        "--violet-wash" to violetWash,
        "--warning" to warning,
        "--wear-hi" to wearHi,
        "--wear-lo" to wearLo,
        "--well" to well,
        "--white" to white,
        "--z-backdrop" to zBackdrop,
        "--z-modal" to zModal,
        "--z-sticky" to zSticky,
        "--z-toast" to zToast,
    )
}

object GeneratedTokens {
    val Studio: SkinTokens = studioTokens()
    val StudioDark: SkinTokens = studioDarkTokens()

    /** `@media` tokens (the same in every skin), in source order. */
    val responsive: List<CssMediaToken> = listOf(
        CssMediaToken("--rail-width", "@media (min-width: 48rem) and (max-width: 99.999rem)", Dp(768f), Dp(1599.984f), Dp(264f)),
        CssMediaToken("--inspector-width", "@media (min-width: 90rem)", Dp(1440f), null, Dp(272f)),
        CssMediaToken("--rail-width", "@media (min-width: 90rem)", Dp(1440f), null, Dp(320f)),
    )
}

private fun studioTokens(): SkinTokens = SkinTokens(
    accent = Color(0xFF365CDE),
    accentDeep = Color(0xFF2341A7),
    accentHover = Color(0xFF294CC6),
    accentInk = Color(0xFFFFFFFF),
    accentSide = Color(0xFF365CDE),
    accentWash = Color(0xFFEDF2FF),
    active = Color(0xFF365CDE),
    activeWash = Color(0xFFEDF2FF),
    amber = Color(0xFF926416),
    amberWash = Color(0xFFFFF8E9),
    attentionBg = Color(0xFFFFF9EC),
    attentionBorder = Color(0xFFE7C98D),
    attentionInk = Color(0xFF80570F),
    bayFloor = Color(0xFFF4F6FA),
    bevelPressed = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    bevelRaised = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    bevelRaisedSm = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    bezel = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    border = Color(0xFFE4E8F0),
    brick = Color(0xFFBE3C49),
    brickDeep = Color(0xFFA52F3B),
    brickSide = Color(0xFFA52F3B),
    brickWash = Color(0xFFFFF0F2),
    charcoal = Color(0xFFE9EDF5),
    charcoalSide = Color(0xFFD9E0ED),
    chatBubbleMax = 0.88f,
    chatCardWidth = 1f,
    chatClamp = Dp(256f),
    contact = Color(0xFF142341),
    danger = Color(0xFFBE3C49),
    dangerEdge = Color(0xFFE5A6AD),
    dangerWash = Color(0xFFFFF0F2),
    diffAddBg = Color(0xFFEDF8F1),
    diffAddInk = Color(0xFF267344),
    diffDelBg = Color(0xFFFFF0F2),
    diffDelInk = Color(0xFFAC3545),
    dropOverlay = Color(0xF0EDF2FF),
    duration = 200,
    durationFast = 140,
    easeOut = CssCubicBezier(0.22f, 1f, 0.36f, 1f),
    edgeHighlight = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    faint = Color(0xFF6B778C),
    findMatchActiveBg = Color(0xFFFFB700),
    findMatchBg = Color(0xFFFFE45C),
    findMatchInk = Color(0xFF1F1A05),
    focusGlow = Color(0x1F365CDE),
    fontMono = "\"JetBrains Mono Variable\", ui-monospace",
    fontUi = "\"Manrope Variable\", -apple-system, BlinkMacSystemFont, \"Segoe UI\", sans-serif",
    graphite = Color(0xFFFFFFFF),
    graphiteRaised = Color(0xFFF5F7FB),
    ink = Color(0xFF303C52),
    inspectorWidth = Dp(288f),
    keyFace = Color(0xFFFFFFFF),
    keyFaceDeep = Color(0xFFF4F6FA),
    keyFaceHover = Color(0xFFEEF2F8),
    keyLabelTracking = TextUnit(0f, TextUnitType.Em),
    keyLabelTransform = "none",
    keySide = Color(0xFFDDE3EE),
    keySlit = Dp(0f),
    line = Color(0xFFE4E8F0),
    lineStrong = Color(0xFFCBD3E2),
    litFaint = Color(0x00000000),
    litSoft = Color(0x00000000),
    litStrong = Color(0x00000000),
    mineral = Color(0xFFF4F6FA),
    mineralDeep = Color(0xFFF8F9FC),
    muted = Color(0xFF5E6C83),
    panelVeil = Color(0xFFFFFFFF),
    perfDots = "none",
    pressShade = Color(0x3D1B2428),
    pressTravel = Dp(0f),
    questionBg = Color(0xFFF0F5FF),
    questionBorder = Color(0xFFBBCCEF),
    questionInk = Color(0xFF3156A1),
    radiusKey = Dp(8f),
    radiusLg = Dp(16f),
    radiusMd = Dp(12f),
    radiusSm = Dp(8f),
    railWidth = Dp(272f),
    rockerMs = 110,
    running = Color(0xFF178263),
    scrim = Color(0x6B0E182B),
    seamLip = Color(0x00000000),
    selectionBg = Color(0xFFDCE5FF),
    shadowFloating = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(12f), blur = Dp(40f), spread = Dp(-12f), color = Color(0x330E1A32)),
    ),
    shadowKey = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    shadowKeyPressed = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    shadowKeySm = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    shadowMenu = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(8f), blur = Dp(24f), spread = Dp(0f), color = Color(0x1F142341)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(4f), spread = Dp(0f), color = Color(0x0F142341)),
    ),
    shadowMenuA = 0.12f,
    shadowMenuUp = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(-8f), blur = Dp(24f), spread = Dp(0f), color = Color(0x1F142341)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(6f), spread = Dp(0f), color = Color(0x0F142341)),
    ),
    shadowModal = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(24f), blur = Dp(90f), spread = Dp(-20f), color = Color(0x4D0E1A32)),
    ),
    shadowRaised = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(2f), spread = Dp(0f), color = Color(0x08142341)),
    ),
    slate = Color(0xFFE9EDF5),
    space2xl = Dp(32f),
    spaceLg = Dp(16f),
    spaceMd = Dp(12f),
    spaceSm = Dp(8f),
    spaceXl = Dp(24f),
    spaceXs = Dp(4f),
    surface2 = Color(0xFFF5F7FB),
    tintBoost = 1f,
    tintLg = Color(0x1A142341),
    tintLine = Color(0x1F142341),
    tintMd = Color(0x0F142341),
    tintRgb = Color(0xFF142341),
    tintSm = Color(0x08142341),
    tintXs = Color(0x04142341),
    userBubbleBg = Color(0xFFEDF2FF),
    userBubbleBorder = Color(0xFFEDF2FF),
    userBubbleInk = Color(0xFF233B77),
    utilityInk = Color(0xFF303C52),
    violet = Color(0xFF365CDE),
    violetDeep = Color(0xFF2546B6),
    violetSoft = Color(0xFFEDF2FF),
    violetStrong = Color(0xFF2B4FC9),
    violetWash = Color(0xFFEDF2FF),
    warning = Color(0xFF926416),
    wearHi = Color(0x00000000),
    wearLo = Color(0x00000000),
    well = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    white = Color(0xFF182238),
    zBackdrop = 40,
    zModal = 50,
    zSticky = 20,
    zToast = 60,
)

private fun studioDarkTokens(): SkinTokens = SkinTokens(
    accent = Color(0xFF486BE8),
    accentDeep = Color(0xFF3456CD),
    accentHover = Color(0xFF5476F0),
    accentInk = Color(0xFFFFFFFF),
    accentSide = Color(0xFF486BE8),
    accentWash = Color(0xFF24365C),
    active = Color(0xFF9CB4FF),
    activeWash = Color(0xFF24365C),
    amber = Color(0xFFEAC47D),
    amberWash = Color(0xFF3A3021),
    attentionBg = Color(0xFF312A21),
    attentionBorder = Color(0xFF705936),
    attentionInk = Color(0xFFEAC47D),
    bayFloor = Color(0xFF101725),
    bevelPressed = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    bevelRaised = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    bevelRaisedSm = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    bezel = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    border = Color(0xFF2B374E),
    brick = Color(0xFFBC3E50),
    brickDeep = Color(0xFFA83445),
    brickSide = Color(0xFFA83445),
    brickWash = Color(0xFF3B2431),
    charcoal = Color(0xFF2A3750),
    charcoalSide = Color(0xFF141D2E),
    chatBubbleMax = 0.88f,
    chatCardWidth = 1f,
    chatClamp = Dp(256f),
    contact = Color(0xFF000000),
    danger = Color(0xFFFF99A2),
    dangerEdge = Color(0xFFA55C6A),
    dangerWash = Color(0xFF3B2431),
    diffAddBg = Color(0xFF17382E),
    diffAddInk = Color(0xFF8EE0B9),
    diffDelBg = Color(0xFF3B2431),
    diffDelInk = Color(0xFFFFACB3),
    dropOverlay = Color(0xF01D2940),
    duration = 200,
    durationFast = 140,
    easeOut = CssCubicBezier(0.22f, 1f, 0.36f, 1f),
    edgeHighlight = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    faint = Color(0xFF99A8C2),
    findMatchActiveBg = Color(0xFFFFD21F),
    findMatchBg = Color(0xFFC2A63A),
    findMatchInk = Color(0xFF1F1A05),
    focusGlow = Color(0x2E8EAAFF),
    fontMono = "\"JetBrains Mono Variable\", ui-monospace",
    fontUi = "\"Manrope Variable\", -apple-system, BlinkMacSystemFont, \"Segoe UI\", sans-serif",
    graphite = Color(0xFF172032),
    graphiteRaised = Color(0xFF1D2940),
    ink = Color(0xFFD7DFEF),
    inspectorWidth = Dp(288f),
    keyFace = Color(0xFF1D2940),
    keyFaceDeep = Color(0xFF141D2E),
    keyFaceHover = Color(0xFF293851),
    keyLabelTracking = TextUnit(0f, TextUnitType.Em),
    keyLabelTransform = "none",
    keySide = Color(0xFF34425A),
    keySlit = Dp(0f),
    line = Color(0xFF2B374E),
    lineStrong = Color(0xFF3D4D69),
    litFaint = Color(0x00000000),
    litSoft = Color(0x00000000),
    litStrong = Color(0x00000000),
    mineral = Color(0xFF101725),
    mineralDeep = Color(0xFF131C2C),
    muted = Color(0xFFAAB7CD),
    panelVeil = Color(0xFF172032),
    perfDots = "none",
    pressShade = Color(0x80000000),
    pressTravel = Dp(0f),
    questionBg = Color(0xFF1C2E4A),
    questionBorder = Color(0xFF405A88),
    questionInk = Color(0xFFABC5FF),
    radiusKey = Dp(8f),
    radiusLg = Dp(16f),
    radiusMd = Dp(12f),
    radiusSm = Dp(8f),
    railWidth = Dp(272f),
    rockerMs = 110,
    running = Color(0xFF6BD6B0),
    scrim = Color(0xAD050A14),
    seamLip = Color(0x00000000),
    selectionBg = Color(0xFF354C7E),
    shadowFloating = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(12f), blur = Dp(40f), spread = Dp(-12f), color = Color(0x330E1A32)),
    ),
    shadowKey = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    shadowKeyPressed = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    shadowKeySm = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    shadowMenu = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(8f), blur = Dp(24f), spread = Dp(0f), color = Color(0x52000000)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(4f), spread = Dp(0f), color = Color(0x29000000)),
    ),
    shadowMenuA = 0.32f,
    shadowMenuUp = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(-8f), blur = Dp(24f), spread = Dp(0f), color = Color(0x52000000)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(6f), spread = Dp(0f), color = Color(0x29000000)),
    ),
    shadowModal = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(24f), blur = Dp(90f), spread = Dp(-20f), color = Color(0x4D0E1A32)),
    ),
    shadowRaised = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(2f), spread = Dp(0f), color = Color(0x08142341)),
    ),
    slate = Color(0xFF2A3750),
    space2xl = Dp(32f),
    spaceLg = Dp(16f),
    spaceMd = Dp(12f),
    spaceSm = Dp(8f),
    spaceXl = Dp(24f),
    spaceXs = Dp(4f),
    surface2 = Color(0xFF1D2940),
    tintBoost = 1f,
    tintLg = Color(0x1AE6EEFF),
    tintLine = Color(0x1FE6EEFF),
    tintMd = Color(0x0FE6EEFF),
    tintRgb = Color(0xFFE6EEFF),
    tintSm = Color(0x08E6EEFF),
    tintXs = Color(0x04E6EEFF),
    userBubbleBg = Color(0xFF24365C),
    userBubbleBorder = Color(0xFF24365C),
    userBubbleInk = Color(0xFFE3EAFF),
    utilityInk = Color(0xFFD7DFEF),
    violet = Color(0xFF9CB4FF),
    violetDeep = Color(0xFFC5D2FF),
    violetSoft = Color(0xFF24365C),
    violetStrong = Color(0xFF8BA5FA),
    violetWash = Color(0xFF24365C),
    warning = Color(0xFFEAC47D),
    wearHi = Color(0x00000000),
    wearLo = Color(0x00000000),
    well = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x00000000)),
    ),
    white = Color(0xFFF0F4FC),
    zBackdrop = 40,
    zModal = 50,
    zSticky = 20,
    zToast = 60,
)
