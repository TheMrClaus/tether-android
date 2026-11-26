// GENERATED FILE — DO NOT EDIT. PLAN D9 / T3.1.
// Source: parity-corpus/tokens/design-tokens.json — tether 4c380bdd (scripts/export-design-tokens.mjs),
// protocol v174, from app/tokens.css + app/globals.css + app/studio.css.
// Generator: tools/design-tokens. Regenerate: ./gradlew generateDesignTokens
// Drift check: ./gradlew verifyDesignTokens (wired into :core:designsystem:check).
// 2 skins, 138 tokens. Category mapping:
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
    const val TETHER_SHA: String = "4c380bdd"
    const val PROTOCOL_VERSION: Int = 174
    const val EXPORTER: String = "scripts/export-design-tokens.mjs"
    val SOURCES: List<String> = listOf("app/tokens.css", "app/globals.css", "app/studio.css")
}

/**
 * The web skins (`<html data-theme>`): Studio light and dark, in skinMap order. [isDark] is the skin's CSS
 * `color-scheme`; [systemBarColor] is its `chrome.graphite` (the web's theme-color / boot-script
 * COLORS entry), used for the status and navigation bars.
 */
enum class TetherSkin(val id: String, val isDark: Boolean, val systemBarColor: Color) {
    Studio("studio", isDark = false, systemBarColor = Color(0xFFFFFFFF)),
    StudioDark("studio-dark", isDark = true, systemBarColor = Color(0xFF11141B)),
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
    /** `--accent-border` (color) */
    val accentBorder: Color,
    /** `--accent-deep` (color) */
    val accentDeep: Color,
    /** `--accent-hover` (color) */
    val accentHover: Color,
    /** `--accent-ink` (color) */
    val accentInk: Color,
    /** `--accent-side` (color) */
    val accentSide: Color,
    /** `--accent-soft` (color) */
    val accentSoft: Color,
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
    /** `--bg-canvas` (color) */
    val bgCanvas: Color,
    /** `--bg-code` (color) */
    val bgCode: Color,
    /** `--bg-panel` (color) */
    val bgPanel: Color,
    /** `--bg-rail` (color) */
    val bgRail: Color,
    /** `--bg-raised` (color) */
    val bgRaised: Color,
    /** `--bg-subtle` (color) */
    val bgSubtle: Color,
    /** `--border` (color) */
    val border: Color,
    /** `--border-control` (color) */
    val borderControl: Color,
    /** `--border-strong` (color) */
    val borderStrong: Color,
    /** `--brand-blue` (color) */
    val brandBlue: Color,
    /** `--brand-claude` (color) */
    val brandClaude: Color,
    /** `--brand-ink` (color) */
    val brandInk: Color,
    /** `--brand-paper` (color) */
    val brandPaper: Color,
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
    /** `--danger-bg` (color) */
    val dangerBg: Color,
    /** `--danger-border` (color) */
    val dangerBorder: Color,
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
    /** `--ink-faint` (color) */
    val inkFaint: Color,
    /** `--ink-muted` (color) */
    val inkMuted: Color,
    /** `--ink-strong` (color) */
    val inkStrong: Color,
    /** `--inspector-width` (length) */
    val inspectorWidth: Dp,
    /** `--key-face` (color) */
    val keyFace: Color,
    /** `--key-face-deep` (color) */
    val keyFaceDeep: Color,
    /** `--key-face-hover` (color) */
    val keyFaceHover: Color,
    /** `--key-side` (color) */
    val keySide: Color,
    /** `--line` (color) */
    val line: Color,
    /** `--line-strong` (color) */
    val lineStrong: Color,
    /** `--mineral` (color) */
    val mineral: Color,
    /** `--mineral-deep` (color) */
    val mineralDeep: Color,
    /** `--muted` (color) */
    val muted: Color,
    /** `--panel-veil` (color) */
    val panelVeil: Color,
    /** `--press-shade` (color) */
    val pressShade: Color,
    /** `--question-bg` (color) */
    val questionBg: Color,
    /** `--question-border` (color) */
    val questionBorder: Color,
    /** `--question-ink` (color) */
    val questionInk: Color,
    /** `--r-dialog` (length) */
    val rDialog: Dp,
    /** `--r-lg` (length) */
    val rLg: Dp,
    /** `--r-md` (length) */
    val rMd: Dp,
    /** `--r-sm` (length) */
    val rSm: Dp,
    /** `--r-xs` (length) */
    val rXs: Dp,
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
    /** `--run` (color) */
    val run: Color,
    /** `--run-bg` (color) */
    val runBg: Color,
    /** `--run-border` (color) */
    val runBorder: Color,
    /** `--running` (color) */
    val running: Color,
    /** `--scene-dim` (color) */
    val sceneDim: Color,
    /** `--scrim` (color) */
    val scrim: Color,
    /** `--selection-bg` (color) */
    val selectionBg: Color,
    /** `--shadow-floating` (shadow) */
    val shadowFloating: List<CssShadow>,
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
    /** `--wait` (color) */
    val wait: Color,
    /** `--wait-bg` (color) */
    val waitBg: Color,
    /** `--wait-border` (color) */
    val waitBorder: Color,
    /** `--wait-ink` (color) */
    val waitInk: Color,
    /** `--wait-solid` (color) */
    val waitSolid: Color,
    /** `--warning` (color) */
    val warning: Color,
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
        "--accent-border" to accentBorder,
        "--accent-deep" to accentDeep,
        "--accent-hover" to accentHover,
        "--accent-ink" to accentInk,
        "--accent-side" to accentSide,
        "--accent-soft" to accentSoft,
        "--accent-wash" to accentWash,
        "--active" to active,
        "--active-wash" to activeWash,
        "--amber" to amber,
        "--amber-wash" to amberWash,
        "--attention-bg" to attentionBg,
        "--attention-border" to attentionBorder,
        "--attention-ink" to attentionInk,
        "--bay-floor" to bayFloor,
        "--bg-canvas" to bgCanvas,
        "--bg-code" to bgCode,
        "--bg-panel" to bgPanel,
        "--bg-rail" to bgRail,
        "--bg-raised" to bgRaised,
        "--bg-subtle" to bgSubtle,
        "--border" to border,
        "--border-control" to borderControl,
        "--border-strong" to borderStrong,
        "--brand-blue" to brandBlue,
        "--brand-claude" to brandClaude,
        "--brand-ink" to brandInk,
        "--brand-paper" to brandPaper,
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
        "--danger-bg" to dangerBg,
        "--danger-border" to dangerBorder,
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
        "--ink-faint" to inkFaint,
        "--ink-muted" to inkMuted,
        "--ink-strong" to inkStrong,
        "--inspector-width" to inspectorWidth,
        "--key-face" to keyFace,
        "--key-face-deep" to keyFaceDeep,
        "--key-face-hover" to keyFaceHover,
        "--key-side" to keySide,
        "--line" to line,
        "--line-strong" to lineStrong,
        "--mineral" to mineral,
        "--mineral-deep" to mineralDeep,
        "--muted" to muted,
        "--panel-veil" to panelVeil,
        "--press-shade" to pressShade,
        "--question-bg" to questionBg,
        "--question-border" to questionBorder,
        "--question-ink" to questionInk,
        "--r-dialog" to rDialog,
        "--r-lg" to rLg,
        "--r-md" to rMd,
        "--r-sm" to rSm,
        "--r-xs" to rXs,
        "--radius-key" to radiusKey,
        "--radius-lg" to radiusLg,
        "--radius-md" to radiusMd,
        "--radius-sm" to radiusSm,
        "--rail-width" to railWidth,
        "--run" to run,
        "--run-bg" to runBg,
        "--run-border" to runBorder,
        "--running" to running,
        "--scene-dim" to sceneDim,
        "--scrim" to scrim,
        "--selection-bg" to selectionBg,
        "--shadow-floating" to shadowFloating,
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
        "--wait" to wait,
        "--wait-bg" to waitBg,
        "--wait-border" to waitBorder,
        "--wait-ink" to waitInk,
        "--wait-solid" to waitSolid,
        "--warning" to warning,
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
        CssMediaToken("--rail-width", "@media (min-width: 90rem)", Dp(1440f), null, Dp(320f)),
    )
}

private fun studioTokens(): SkinTokens = SkinTokens(
    accent = Color(0xFF0369A1),
    accentBorder = Color(0xFF7DD3FC),
    accentDeep = Color(0xFF075985),
    accentHover = Color(0xFF075985),
    accentInk = Color(0xFFFFFFFF),
    accentSide = Color(0xFF0369A1),
    accentSoft = Color(0xFFE0F2FE),
    accentWash = Color(0xFFE0F2FE),
    active = Color(0xFF0369A1),
    activeWash = Color(0xFFE0F2FE),
    amber = Color(0xFF9A4D06),
    amberWash = Color(0xFFFEF7E5),
    attentionBg = Color(0xFFFEF7E5),
    attentionBorder = Color(0xFFFBBF24),
    attentionInk = Color(0xFF9A4D06),
    bayFloor = Color(0xFFEEF1F5),
    bgCanvas = Color(0xFFEEF1F5),
    bgCode = Color(0xFFEEF1F5),
    bgPanel = Color(0xFFFFFFFF),
    bgRail = Color(0xFFE7EBF2),
    bgRaised = Color(0xFFF6F8FB),
    bgSubtle = Color(0xFFE7EBF2),
    border = Color(0xFFD8DFE9),
    borderControl = Color(0xFF7A889E),
    borderStrong = Color(0xFFBCC6D6),
    brandBlue = Color(0xFF4D6BFE),
    brandClaude = Color(0xFFD97757),
    brandInk = Color(0xFF0D0D0D),
    brandPaper = Color(0xFFFFFFFF),
    brick = Color(0xFFBE123C),
    brickDeep = Color(0xFFA41339),
    brickSide = Color(0xFFBE123C),
    brickWash = Color(0xFFFFF1F4),
    charcoal = Color(0xFFE7EBF2),
    charcoalSide = Color(0xFFD8DFE9),
    chatBubbleMax = 0.88f,
    chatCardWidth = 1f,
    chatClamp = Dp(256f),
    contact = Color(0xFF0F172A),
    danger = Color(0xFFBE123C),
    dangerBg = Color(0xFFFFF1F4),
    dangerBorder = Color(0xFFFB7185),
    dangerEdge = Color(0xFFFB7185),
    dangerWash = Color(0xFFFFF1F4),
    diffAddBg = Color(0xFFE8F8F1),
    diffAddInk = Color(0xFF047857),
    diffDelBg = Color(0xFFFFF1F4),
    diffDelInk = Color(0xFFBE123C),
    dropOverlay = Color(0xFFE0F2FE),
    duration = 200,
    durationFast = 140,
    easeOut = CssCubicBezier(0.22f, 1f, 0.36f, 1f),
    faint = Color(0xFF4F5F77),
    findMatchActiveBg = Color(0xFFFFB700),
    findMatchBg = Color(0xFFFFE45C),
    findMatchInk = Color(0xFF1F1A05),
    focusGlow = Color(0xFFE0F2FE),
    fontMono = "\"Fira Code Variable\", \"JetBrains Mono Variable\", ui-monospace, monospace",
    fontUi = "\"Archivo Variable\", -apple-system, BlinkMacSystemFont, \"Segoe UI\", sans-serif",
    graphite = Color(0xFFFFFFFF),
    graphiteRaised = Color(0xFFF6F8FB),
    ink = Color(0xFF273449),
    inkFaint = Color(0xFF8896AB),
    inkMuted = Color(0xFF4F5F77),
    inkStrong = Color(0xFF0F172A),
    inspectorWidth = Dp(288f),
    keyFace = Color(0xFFF6F8FB),
    keyFaceDeep = Color(0xFFEEF1F5),
    keyFaceHover = Color(0xFFE7EBF2),
    keySide = Color(0xFFD8DFE9),
    line = Color(0xFFD8DFE9),
    lineStrong = Color(0xFFBCC6D6),
    mineral = Color(0xFFEEF1F5),
    mineralDeep = Color(0xFFEEF1F5),
    muted = Color(0xFF4F5F77),
    panelVeil = Color(0xFFFFFFFF),
    pressShade = Color(0x3D0F172A),
    questionBg = Color(0xFFE0F2FE),
    questionBorder = Color(0xFF7DD3FC),
    questionInk = Color(0xFF0369A1),
    rDialog = Dp(12f),
    rLg = Dp(10f),
    rMd = Dp(8f),
    rSm = Dp(6f),
    rXs = Dp(4f),
    radiusKey = Dp(8f),
    radiusLg = Dp(16f),
    radiusMd = Dp(12f),
    radiusSm = Dp(8f),
    railWidth = Dp(272f),
    run = Color(0xFF047857),
    runBg = Color(0xFFE8F8F1),
    runBorder = Color(0xFF34D399),
    running = Color(0xFF047857),
    sceneDim = Color(0x57141D2E),
    scrim = Color(0x6B0F172A),
    selectionBg = Color(0xFFE0F2FE),
    shadowFloating = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(12f), blur = Dp(40f), spread = Dp(-12f), color = Color(0x330E1A32)),
    ),
    shadowMenu = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(8f), blur = Dp(24f), spread = Dp(0f), color = Color(0x1F0F172A)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(4f), spread = Dp(0f), color = Color(0x0F0F172A)),
    ),
    shadowMenuA = 0.12f,
    shadowMenuUp = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(-8f), blur = Dp(24f), spread = Dp(0f), color = Color(0x1F0F172A)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(6f), spread = Dp(0f), color = Color(0x0F0F172A)),
    ),
    shadowModal = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(24f), blur = Dp(90f), spread = Dp(-20f), color = Color(0x4D0E1A32)),
    ),
    shadowRaised = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(2f), spread = Dp(0f), color = Color(0x08142341)),
    ),
    slate = Color(0xFFE7EBF2),
    space2xl = Dp(32f),
    spaceLg = Dp(16f),
    spaceMd = Dp(12f),
    spaceSm = Dp(8f),
    spaceXl = Dp(24f),
    spaceXs = Dp(4f),
    surface2 = Color(0xFFF6F8FB),
    tintBoost = 1f,
    tintLg = Color(0x1A0F172A),
    tintLine = Color(0x1F0F172A),
    tintMd = Color(0x0F0F172A),
    tintRgb = Color(0xFF0F172A),
    tintSm = Color(0x080F172A),
    tintXs = Color(0x040F172A),
    userBubbleBg = Color(0xFFE0F2FE),
    userBubbleBorder = Color(0xFFE0F2FE),
    userBubbleInk = Color(0xFF0F172A),
    utilityInk = Color(0xFF273449),
    violet = Color(0xFF0369A1),
    violetDeep = Color(0xFF075985),
    violetSoft = Color(0xFFE0F2FE),
    violetStrong = Color(0xFF075985),
    violetWash = Color(0xFFE0F2FE),
    wait = Color(0xFF9A4D06),
    waitBg = Color(0xFFFEF7E5),
    waitBorder = Color(0xFFFBBF24),
    waitInk = Color(0xFFFFFFFF),
    waitSolid = Color(0xFF9A4D06),
    warning = Color(0xFF9A4D06),
    white = Color(0xFF0F172A),
    zBackdrop = 40,
    zModal = 50,
    zSticky = 20,
    zToast = 60,
)

private fun studioDarkTokens(): SkinTokens = SkinTokens(
    accent = Color(0xFF38BDF8),
    accentBorder = Color(0xFF075985),
    accentDeep = Color(0xFF7DD3FC),
    accentHover = Color(0xFF7DD3FC),
    accentInk = Color(0xFF06131C),
    accentSide = Color(0xFF38BDF8),
    accentSoft = Color(0xFF102A3C),
    accentWash = Color(0xFF102A3C),
    active = Color(0xFF38BDF8),
    activeWash = Color(0xFF102A3C),
    amber = Color(0xFFFBBF24),
    amberWash = Color(0xFF29200E),
    attentionBg = Color(0xFF29200E),
    attentionBorder = Color(0xFF664C16),
    attentionInk = Color(0xFFFBBF24),
    bayFloor = Color(0xFF0A0C10),
    bgCanvas = Color(0xFF0A0C10),
    bgCode = Color(0xFF0D1016),
    bgPanel = Color(0xFF11141B),
    bgRail = Color(0xFF0D1016),
    bgRaised = Color(0xFF171C26),
    bgSubtle = Color(0xFF1C222E),
    border = Color(0xFF232A38),
    borderControl = Color(0xFF626E83),
    borderStrong = Color(0xFF353F54),
    brandBlue = Color(0xFF4D6BFE),
    brandClaude = Color(0xFFD97757),
    brandInk = Color(0xFF0D0D0D),
    brandPaper = Color(0xFFFFFFFF),
    brick = Color(0xFFFB7185),
    brickDeep = Color(0xFFFA8597),
    brickSide = Color(0xFFFB7185),
    brickWash = Color(0xFF2D141B),
    charcoal = Color(0xFF1C222E),
    charcoalSide = Color(0xFF232A38),
    chatBubbleMax = 0.88f,
    chatCardWidth = 1f,
    chatClamp = Dp(256f),
    contact = Color(0xFF0A0C10),
    danger = Color(0xFFFB7185),
    dangerBg = Color(0xFF2D141B),
    dangerBorder = Color(0xFF6B2435),
    dangerEdge = Color(0xFF6B2435),
    dangerWash = Color(0xFF2D141B),
    diffAddBg = Color(0xFF0E261F),
    diffAddInk = Color(0xFF34D399),
    diffDelBg = Color(0xFF2D141B),
    diffDelInk = Color(0xFFFB7185),
    dropOverlay = Color(0xFF102A3C),
    duration = 200,
    durationFast = 140,
    easeOut = CssCubicBezier(0.22f, 1f, 0.36f, 1f),
    faint = Color(0xFF8F9BB0),
    findMatchActiveBg = Color(0xFFFFD21F),
    findMatchBg = Color(0xFFC2A63A),
    findMatchInk = Color(0xFF1F1A05),
    focusGlow = Color(0xFF102A3C),
    fontMono = "\"Fira Code Variable\", \"JetBrains Mono Variable\", ui-monospace, monospace",
    fontUi = "\"Archivo Variable\", -apple-system, BlinkMacSystemFont, \"Segoe UI\", sans-serif",
    graphite = Color(0xFF11141B),
    graphiteRaised = Color(0xFF171C26),
    ink = Color(0xFFCFD7E5),
    inkFaint = Color(0xFF4F5B72),
    inkMuted = Color(0xFF8F9BB0),
    inkStrong = Color(0xFFF3F6FB),
    inspectorWidth = Dp(288f),
    keyFace = Color(0xFF171C26),
    keyFaceDeep = Color(0xFF0A0C10),
    keyFaceHover = Color(0xFF1C222E),
    keySide = Color(0xFF232A38),
    line = Color(0xFF232A38),
    lineStrong = Color(0xFF353F54),
    mineral = Color(0xFF0A0C10),
    mineralDeep = Color(0xFF0D1016),
    muted = Color(0xFF8F9BB0),
    panelVeil = Color(0xFF11141B),
    pressShade = Color(0x800A0C10),
    questionBg = Color(0xFF102A3C),
    questionBorder = Color(0xFF075985),
    questionInk = Color(0xFF38BDF8),
    rDialog = Dp(12f),
    rLg = Dp(10f),
    rMd = Dp(8f),
    rSm = Dp(6f),
    rXs = Dp(4f),
    radiusKey = Dp(8f),
    radiusLg = Dp(16f),
    radiusMd = Dp(12f),
    radiusSm = Dp(8f),
    railWidth = Dp(272f),
    run = Color(0xFF34D399),
    runBg = Color(0xFF0E261F),
    runBorder = Color(0xFF1B5443),
    running = Color(0xFF34D399),
    sceneDim = Color(0x6B060A14),
    scrim = Color(0xAD0A0C10),
    selectionBg = Color(0xFF075985),
    shadowFloating = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(12f), blur = Dp(40f), spread = Dp(-12f), color = Color(0x330E1A32)),
    ),
    shadowMenu = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(8f), blur = Dp(24f), spread = Dp(0f), color = Color(0x520A0C10)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(4f), spread = Dp(0f), color = Color(0x290A0C10)),
    ),
    shadowMenuA = 0.32f,
    shadowMenuUp = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(-8f), blur = Dp(24f), spread = Dp(0f), color = Color(0x520A0C10)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(6f), spread = Dp(0f), color = Color(0x290A0C10)),
    ),
    shadowModal = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(24f), blur = Dp(90f), spread = Dp(-20f), color = Color(0x4D0E1A32)),
    ),
    shadowRaised = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(2f), spread = Dp(0f), color = Color(0x08142341)),
    ),
    slate = Color(0xFF1C222E),
    space2xl = Dp(32f),
    spaceLg = Dp(16f),
    spaceMd = Dp(12f),
    spaceSm = Dp(8f),
    spaceXl = Dp(24f),
    spaceXs = Dp(4f),
    surface2 = Color(0xFF171C26),
    tintBoost = 1f,
    tintLg = Color(0x1AF3F6FB),
    tintLine = Color(0x1FF3F6FB),
    tintMd = Color(0x0FF3F6FB),
    tintRgb = Color(0xFFF3F6FB),
    tintSm = Color(0x08F3F6FB),
    tintXs = Color(0x04F3F6FB),
    userBubbleBg = Color(0xFF102A3C),
    userBubbleBorder = Color(0xFF102A3C),
    userBubbleInk = Color(0xFFF3F6FB),
    utilityInk = Color(0xFFCFD7E5),
    violet = Color(0xFF38BDF8),
    violetDeep = Color(0xFF7DD3FC),
    violetSoft = Color(0xFF102A3C),
    violetStrong = Color(0xFF7DD3FC),
    violetWash = Color(0xFF102A3C),
    wait = Color(0xFFFBBF24),
    waitBg = Color(0xFF29200E),
    waitBorder = Color(0xFF664C16),
    waitInk = Color(0xFF120E02),
    waitSolid = Color(0xFFFBBF24),
    warning = Color(0xFFFBBF24),
    white = Color(0xFFF3F6FB),
    zBackdrop = 40,
    zModal = 50,
    zSticky = 20,
    zToast = 60,
)
