// GENERATED FILE — DO NOT EDIT. PLAN D9 / T3.1.
// Source: parity-corpus/tokens/design-tokens.json — tether 356b456 (scripts/export-design-tokens.mjs),
// protocol v128, from app/globals.css + app/studio.css.
// Generator: tools/design-tokens. Regenerate: ./gradlew generateDesignTokens
// Drift check: ./gradlew verifyDesignTokens (wired into :core:designsystem:check).
// 6 skins, 126 tokens. Category mapping:
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
 * The six web skins (`<html data-theme>`), in skinMap order. [isDark] is the skin's CSS
 * `color-scheme`; [systemBarColor] is its `chrome.graphite` (the web's theme-color / boot-script
 * COLORS entry), used for the status and navigation bars.
 */
enum class TetherSkin(val id: String, val family: ThemeFamily, val isDark: Boolean, val systemBarColor: Color) {
    Tactile("tactile", ThemeFamily.Tactile, isDark = false, systemBarColor = Color(0xFFE6E7E2)),
    Night("night", ThemeFamily.Tactile, isDark = true, systemBarColor = Color(0xFF181A19)),
    Precision("precision", ThemeFamily.Precision, isDark = false, systemBarColor = Color(0xFFF2F4F3)),
    Machine("machine", ThemeFamily.Precision, isDark = true, systemBarColor = Color(0xFF111517)),
    Studio("studio", ThemeFamily.Studio, isDark = false, systemBarColor = Color(0xFFFFFFFF)),
    StudioDark("studio-dark", ThemeFamily.Studio, isDark = true, systemBarColor = Color(0xFF172032)),
    ;

    /** This skin's complete token set. */
    val tokens: SkinTokens
        get() = when (this) {
            Tactile -> GeneratedTokens.Tactile
            Night -> GeneratedTokens.Night
            Precision -> GeneratedTokens.Precision
            Machine -> GeneratedTokens.Machine
            Studio -> GeneratedTokens.Studio
            StudioDark -> GeneratedTokens.StudioDark
        }

    companion object {
        fun fromId(id: String?): TetherSkin? = entries.firstOrNull { it.id == id }

        /** The web's THEME_SKINS: family × concrete lighting → skin. */
        fun of(family: ThemeFamily, dark: Boolean): TetherSkin = when (family) {
            ThemeFamily.Tactile -> if (dark) Night else Tactile
            ThemeFamily.Precision -> if (dark) Machine else Precision
            ThemeFamily.Studio -> if (dark) StudioDark else Studio
        }
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
    /** `--font-ui` (string, absent in some skins) */
    val fontUi: String?,
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
    val Tactile: SkinTokens = tactileTokens()
    val Night: SkinTokens = nightTokens()
    val Precision: SkinTokens = precisionTokens()
    val Machine: SkinTokens = machineTokens()
    val Studio: SkinTokens = studioTokens()
    val StudioDark: SkinTokens = studioDarkTokens()

    /** `@media` tokens (the same in every skin), in source order. */
    val responsive: List<CssMediaToken> = listOf(
        CssMediaToken("--rail-width", "@media (min-width: 48rem) and (max-width: 99.999rem)", Dp(768f), Dp(1599.984f), Dp(264f)),
        CssMediaToken("--inspector-width", "@media (min-width: 90rem)", Dp(1440f), null, Dp(272f)),
        CssMediaToken("--rail-width", "@media (min-width: 90rem)", Dp(1440f), null, Dp(320f)),
    )
}

private fun tactileTokens(): SkinTokens = SkinTokens(
    accent = Color(0xFF67785A),
    accentDeep = Color(0xFF5B6B4F),
    accentHover = Color(0xFF607052),
    accentInk = Color(0xFFFFFFFF),
    accentSide = Color(0xFF46543C),
    accentWash = Color(0xFFB9C5AC),
    active = Color(0xFF5A4FB4),
    activeWash = Color(0xFFE5E3F3),
    amber = Color(0xFFB8892E),
    amberWash = Color(0xFFEFE8D0),
    attentionBg = Color(0xFFEFE8D0),
    attentionBorder = Color(0xFFC8A24B),
    attentionInk = Color(0xFF7D5C15),
    bayFloor = Color(0xFFC7C9C2),
    bevelPressed = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(3f), spread = Dp(0f), color = Color(0x3D2D302A)),
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(-1f), blur = Dp(0f), spread = Dp(0f), color = Color(0x66FFFFFF)),
    ),
    bevelRaised = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0xE6FFFFFF)),
        CssShadow(inset = true, offsetX = Dp(1f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x73FFFFFF)),
    ),
    bevelRaisedSm = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0xE6FFFFFF)),
    ),
    bezel = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(1f), color = Color(0xFFA3A79C)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(6f), color = Color(0xFFE6E7E2)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(7f), color = Color(0xFFBFC2B9)),
    ),
    border = Color(0xFFBFC2B9),
    brick = Color(0xFFA34F44),
    brickDeep = Color(0xFF8E4238),
    brickSide = Color(0xFF6F382F),
    brickWash = Color(0xFFEDDCD7),
    charcoal = Color(0xFF3F444A),
    charcoalSide = Color(0xFF24272B),
    chatBubbleMax = 0.86f,
    chatCardWidth = 0.94f,
    chatClamp = Dp(256f),
    contact = Color(0xFF2D302A),
    danger = Color(0xFFA34F44),
    dangerEdge = Color(0x8CA34F44),
    dangerWash = Color(0xFFF1E3DF),
    diffAddBg = Color(0xFFDBE6D2),
    diffAddInk = Color(0xFF375833),
    diffDelBg = Color(0xFFECD9D4),
    diffDelInk = Color(0xFF83392F),
    dropOverlay = Color(0xD9E5E3F3),
    duration = 200,
    durationFast = 140,
    easeOut = CssCubicBezier(0.22f, 1f, 0.36f, 1f),
    edgeHighlight = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0xE6FFFFFF)),
        CssShadow(inset = true, offsetX = Dp(1f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x73FFFFFF)),
        CssShadow(inset = true, offsetX = Dp(-1f), offsetY = Dp(-1f), blur = Dp(0f), spread = Dp(0f), color = Color(0x122D302A)),
    ),
    faint = Color(0xFF575C53),
    findMatchActiveBg = Color(0xFFFFB700),
    findMatchBg = Color(0xFFFFE45C),
    findMatchInk = Color(0xFF1F1A05),
    focusGlow = Color(0x2E5747C8),
    fontMono = "\"JetBrains Mono Variable\", ui-monospace",
    fontUi = null,
    graphite = Color(0xFFE6E7E2),
    graphiteRaised = Color(0xFFF1F2ED),
    ink = Color(0xFF33373C),
    inspectorWidth = Dp(264f),
    keyFace = Color(0xFFEFF0EB),
    keyFaceDeep = Color(0xFFE0E2DB),
    keyFaceHover = Color(0xFFF6F7F2),
    keyLabelTracking = TextUnit(0.05f, TextUnitType.Em),
    keyLabelTransform = "uppercase",
    keySide = Color(0xFFB3B6AC),
    keySlit = Dp(0f),
    line = Color(0xFFBFC2B9),
    lineStrong = Color(0xFFA3A79C),
    litFaint = Color(0x47FFFFFF),
    litSoft = Color(0x73FFFFFF),
    litStrong = Color(0xE6FFFFFF),
    mineral = Color(0xFFD2D4CE),
    mineralDeep = Color(0xFFEFF1EB),
    muted = Color(0xFF4C514C),
    panelVeil = Color(0xF7EEF0EA),
    perfDots = "none",
    pressShade = Color(0x3D2D302A),
    pressTravel = Dp(3f),
    questionBg = Color(0xFFE4E9F1),
    questionBorder = Color(0xFF93A2BD),
    questionInk = Color(0xFF44608E),
    radiusKey = Dp(9.6f),
    radiusLg = Dp(14f),
    radiusMd = Dp(10f),
    radiusSm = Dp(6f),
    railWidth = Dp(288f),
    rockerMs = 130,
    running = Color(0xFF35693F),
    scrim = Color(0x6B2D302A),
    seamLip = Color(0x99FFFFFF),
    selectionBg = Color(0xFFCFCBE8),
    shadowFloating = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(10f), blur = Dp(24f), spread = Dp(-8f), color = Color(0x592D302A)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(6f), spread = Dp(0f), color = Color(0x2E2D302A)),
    ),
    shadowKey = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(3f), blur = Dp(0f), spread = Dp(0f), color = Color(0xFFB3B6AC)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(5f), blur = Dp(7f), spread = Dp(-2f), color = Color(0x4D2D302A)),
    ),
    shadowKeyPressed = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0xFFB3B6AC)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(2f), spread = Dp(0f), color = Color(0x382D302A)),
    ),
    shadowKeySm = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(0f), spread = Dp(0f), color = Color(0xFFB3B6AC)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(3f), blur = Dp(5f), spread = Dp(-1f), color = Color(0x402D302A)),
    ),
    shadowMenu = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(8f), blur = Dp(24f), spread = Dp(0f), color = Color(0x382D302A)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(4f), spread = Dp(0f), color = Color(0x1C2D302A)),
    ),
    shadowMenuA = 0.22f,
    shadowMenuUp = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(-8f), blur = Dp(24f), spread = Dp(0f), color = Color(0x382D302A)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(6f), spread = Dp(0f), color = Color(0x1C2D302A)),
    ),
    shadowModal = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(22f), blur = Dp(48f), spread = Dp(-16f), color = Color(0x662D302A)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(6f), blur = Dp(14f), spread = Dp(0f), color = Color(0x382D302A)),
    ),
    shadowRaised = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(2f), spread = Dp(0f), color = Color(0x332D302A)),
    ),
    slate = Color(0xFFC8CBC2),
    space2xl = Dp(32f),
    spaceLg = Dp(16f),
    spaceMd = Dp(12f),
    spaceSm = Dp(8f),
    spaceXl = Dp(24f),
    spaceXs = Dp(4f),
    surface2 = Color(0x142D302A),
    tintBoost = 1.6f,
    tintLg = Color(0x292D302A),
    tintLine = Color(0x312D302A),
    tintMd = Color(0x182D302A),
    tintRgb = Color(0xFF2D302A),
    tintSm = Color(0x0C2D302A),
    tintXs = Color(0x062D302A),
    userBubbleBg = Color(0xFFB9C5AC),
    userBubbleBorder = Color(0xFF9AA98C),
    userBubbleInk = Color(0xFF262A26),
    utilityInk = Color(0xFFEEF0EA),
    violet = Color(0xFF5A4FB4),
    violetDeep = Color(0xFF4A3FA0),
    violetSoft = Color(0xFFE5E3F3),
    violetStrong = Color(0xFF5747C8),
    violetWash = Color(0xFFE5E3F3),
    warning = Color(0xFF7D5C15),
    wearHi = Color(0x3DFFFFFF),
    wearLo = Color(0x21000000),
    well = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(3f), spread = Dp(0f), color = Color(0x332D302A)),
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(5f), blur = Dp(10f), spread = Dp(-7f), color = Color(0x382D302A)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0xB3FFFFFF)),
    ),
    white = Color(0xFF23262B),
    zBackdrop = 40,
    zModal = 50,
    zSticky = 20,
    zToast = 60,
)

private fun nightTokens(): SkinTokens = SkinTokens(
    accent = Color(0xFF626E49),
    accentDeep = Color(0xFF55603E),
    accentHover = Color(0xFF6C7952),
    accentInk = Color(0xFFF4F6EC),
    accentSide = Color(0xFF333A24),
    accentWash = Color(0xFF242A1A),
    active = Color(0xFF8F83E6),
    activeWash = Color(0xFF1F1C33),
    amber = Color(0xFFB8892E),
    amberWash = Color(0xFF2A2413),
    attentionBg = Color(0xFF221D0F),
    attentionBorder = Color(0xFF6F5622),
    attentionInk = Color(0xFFD3A04A),
    bayFloor = Color(0xFF0B0D0C),
    bevelPressed = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(4f), spread = Dp(0f), color = Color(0x8C000000)),
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(-1f), blur = Dp(0f), spread = Dp(0f), color = Color(0x09FFFFFF)),
    ),
    bevelRaised = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0x1CFFFFFF)),
        CssShadow(inset = true, offsetX = Dp(1f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x0FFFFFFF)),
    ),
    bevelRaisedSm = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0x0FFFFFFF)),
    ),
    bezel = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(1f), color = Color(0xFF454A42)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(6f), color = Color(0xFF181A19)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(7f), color = Color(0xFF2C302B)),
    ),
    border = Color(0xFF2C302B),
    brick = Color(0xFF9E4034),
    brickDeep = Color(0xFFB04A3D),
    brickSide = Color(0xFF5A231C),
    brickWash = Color(0xFF2A1613),
    charcoal = Color(0xFF33372F),
    charcoalSide = Color(0xFF0E100D),
    chatBubbleMax = 0.86f,
    chatCardWidth = 0.94f,
    chatClamp = Dp(256f),
    contact = Color(0xFF000000),
    danger = Color(0xFFD4685A),
    dangerEdge = Color(0x80D4685A),
    dangerWash = Color(0xFF26110F),
    diffAddBg = Color(0xFF1C2617),
    diffAddInk = Color(0xFFA2C78C),
    diffDelBg = Color(0xFF2B1614),
    diffDelInk = Color(0xFFE08B7D),
    dropOverlay = Color(0xDB1F1C33),
    duration = 200,
    durationFast = 140,
    easeOut = CssCubicBezier(0.22f, 1f, 0.36f, 1f),
    edgeHighlight = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0x1CFFFFFF)),
        CssShadow(inset = true, offsetX = Dp(1f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x09FFFFFF)),
    ),
    faint = Color(0xFF8D9284),
    findMatchActiveBg = Color(0xFFFFD21F),
    findMatchBg = Color(0xFFC2A63A),
    findMatchInk = Color(0xFF1F1A05),
    focusGlow = Color(0x4D7264DD),
    fontMono = "\"JetBrains Mono Variable\", ui-monospace",
    fontUi = null,
    graphite = Color(0xFF181A19),
    graphiteRaised = Color(0xFF252826),
    ink = Color(0xFFD6D8CD),
    inspectorWidth = Dp(264f),
    keyFace = Color(0xFF242725),
    keyFaceDeep = Color(0xFF1B1E1C),
    keyFaceHover = Color(0xFF2C302D),
    keyLabelTracking = TextUnit(0.05f, TextUnitType.Em),
    keyLabelTransform = "uppercase",
    keySide = Color(0xFF080908),
    keySlit = Dp(0f),
    line = Color(0xFF2C302B),
    lineStrong = Color(0xFF454A42),
    litFaint = Color(0x09FFFFFF),
    litSoft = Color(0x0FFFFFFF),
    litStrong = Color(0x1CFFFFFF),
    mineral = Color(0xFF101211),
    mineralDeep = Color(0xFF0A0C0B),
    muted = Color(0xFF9BA093),
    panelVeil = Color(0xF7171918),
    perfDots = "none",
    pressShade = Color(0x80000000),
    pressTravel = Dp(3f),
    questionBg = Color(0xFF151A22),
    questionBorder = Color(0xFF3C4A63),
    questionInk = Color(0xFF9DB2D1),
    radiusKey = Dp(9.6f),
    radiusLg = Dp(14f),
    radiusMd = Dp(10f),
    radiusSm = Dp(6f),
    railWidth = Dp(288f),
    rockerMs = 130,
    running = Color(0xFF8FBF7A),
    scrim = Color(0x9E060706),
    seamLip = Color(0x12FFFFFF),
    selectionBg = Color(0xFF322C57),
    shadowFloating = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(12f), blur = Dp(30f), spread = Dp(-8f), color = Color(0x9E000000)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(8f), spread = Dp(0f), color = Color(0x6B000000)),
    ),
    shadowKey = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(3f), blur = Dp(0f), spread = Dp(0f), color = Color(0xFF080908)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(5f), blur = Dp(7f), spread = Dp(-2f), color = Color(0x80000000)),
    ),
    shadowKeyPressed = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0xFF080908)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(2f), spread = Dp(0f), color = Color(0x66000000)),
    ),
    shadowKeySm = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(0f), spread = Dp(0f), color = Color(0xFF080908)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(3f), blur = Dp(5f), spread = Dp(-1f), color = Color(0x73000000)),
    ),
    shadowMenu = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(8f), blur = Dp(24f), spread = Dp(0f), color = Color(0x80000000)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(4f), spread = Dp(0f), color = Color(0x40000000)),
    ),
    shadowMenuA = 0.5f,
    shadowMenuUp = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(-8f), blur = Dp(24f), spread = Dp(0f), color = Color(0x80000000)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(6f), spread = Dp(0f), color = Color(0x40000000)),
    ),
    shadowModal = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(24f), blur = Dp(60f), spread = Dp(-16f), color = Color(0xAD000000)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(6f), blur = Dp(16f), spread = Dp(0f), color = Color(0x75000000)),
    ),
    shadowRaised = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(2f), spread = Dp(0f), color = Color(0x80000000)),
    ),
    slate = Color(0xFF31352F),
    space2xl = Dp(32f),
    spaceLg = Dp(16f),
    spaceMd = Dp(12f),
    spaceSm = Dp(8f),
    spaceXl = Dp(24f),
    spaceXs = Dp(4f),
    surface2 = Color(0x0FFFFFFF),
    tintBoost = 1f,
    tintLg = Color(0x1AFFFFFF),
    tintLine = Color(0x1FFFFFFF),
    tintMd = Color(0x0FFFFFFF),
    tintRgb = Color(0xFFFFFFFF),
    tintSm = Color(0x08FFFFFF),
    tintXs = Color(0x04FFFFFF),
    userBubbleBg = Color(0xFF2F3826),
    userBubbleBorder = Color(0xFF4A5639),
    userBubbleInk = Color(0xFFE9ECDF),
    utilityInk = Color(0xFFE2E4D9),
    violet = Color(0xFF8F83E6),
    violetDeep = Color(0xFFB3A9FF),
    violetSoft = Color(0xFF1F1C33),
    violetStrong = Color(0xFF7264DD),
    violetWash = Color(0xFF1F1C33),
    warning = Color(0xFFD3A04A),
    wearHi = Color(0x12FFFFFF),
    wearLo = Color(0x33000000),
    well = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(3f), spread = Dp(0f), color = Color(0x8C000000)),
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(6f), blur = Dp(12f), spread = Dp(-8f), color = Color(0x80000000)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0x09FFFFFF)),
    ),
    white = Color(0xFFECEEE6),
    zBackdrop = 40,
    zModal = 50,
    zSticky = 20,
    zToast = 60,
)

private fun precisionTokens(): SkinTokens = SkinTokens(
    accent = Color(0xFF3D7D6E),
    accentDeep = Color(0xFF2F6558),
    accentHover = Color(0xFF366F62),
    accentInk = Color(0xFFFFFFFF),
    accentSide = Color(0xFF255249),
    accentWash = Color(0xFFDCEBE6),
    active = Color(0xFF5546C9),
    activeWash = Color(0xFFE7E4F8),
    amber = Color(0xFFC9962F),
    amberWash = Color(0xFFF5EDD9),
    attentionBg = Color(0xFFF6EFDC),
    attentionBorder = Color(0xFFCBA653),
    attentionInk = Color(0xFF7D5A10),
    bayFloor = Color(0xFFD5DBDB),
    bevelPressed = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(3f), spread = Dp(0f), color = Color(0x331B2428)),
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(-1f), blur = Dp(0f), spread = Dp(0f), color = Color(0x8CFFFFFF)),
    ),
    bevelRaised = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0xF2FFFFFF)),
        CssShadow(inset = true, offsetX = Dp(1f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x4DFFFFFF)),
    ),
    bevelRaisedSm = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0xF2FFFFFF)),
    ),
    bezel = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(1f), color = Color(0xFFBCC4C6)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(6f), color = Color(0xFFF2F4F3)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(7f), color = Color(0xFFCDD5D6)),
    ),
    border = Color(0xFFCDD5D6),
    brick = Color(0xFFB3392C),
    brickDeep = Color(0xFF9C3025),
    brickSide = Color(0xFF7D271E),
    brickWash = Color(0xFFF7E3E0),
    charcoal = Color(0xFF3A464B),
    charcoalSide = Color(0xFF202A2E),
    chatBubbleMax = 0.86f,
    chatCardWidth = 0.94f,
    chatClamp = Dp(256f),
    contact = Color(0xFF1B2428),
    danger = Color(0xFFB3392C),
    dangerEdge = Color(0x80B3392C),
    dangerWash = Color(0xFFF6E4E1),
    diffAddBg = Color(0xFFD9EBE2),
    diffAddInk = Color(0xFF1F5C43),
    diffDelBg = Color(0xFFF4DEDB),
    diffDelInk = Color(0xFF8D2F24),
    dropOverlay = Color(0xDBE7E4F8),
    duration = 200,
    durationFast = 140,
    easeOut = CssCubicBezier(0.22f, 1f, 0.36f, 1f),
    edgeHighlight = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0xF2FFFFFF)),
        CssShadow(inset = true, offsetX = Dp(1f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x8CFFFFFF)),
        CssShadow(inset = true, offsetX = Dp(-1f), offsetY = Dp(-1f), blur = Dp(0f), spread = Dp(0f), color = Color(0x0F1B2428)),
    ),
    faint = Color(0xFF59666B),
    findMatchActiveBg = Color(0xFFFFB700),
    findMatchBg = Color(0xFFFFE45C),
    findMatchInk = Color(0xFF1F1A05),
    focusGlow = Color(0x384B3CC4),
    fontMono = "\"JetBrains Mono Variable\", ui-monospace",
    fontUi = null,
    graphite = Color(0xFFF2F4F3),
    graphiteRaised = Color(0xFFF8F9F9),
    ink = Color(0xFF2A343A),
    inspectorWidth = Dp(264f),
    keyFace = Color(0xFFF7F8F8),
    keyFaceDeep = Color(0xFFE4E9E9),
    keyFaceHover = Color(0xFFFDFDFD),
    keyLabelTracking = TextUnit(0.06f, TextUnitType.Em),
    keyLabelTransform = "uppercase",
    keySide = Color(0xFFB9C2C4),
    keySlit = Dp(3f),
    line = Color(0xFFCDD5D6),
    lineStrong = Color(0xFFBCC4C6),
    litFaint = Color(0x4DFFFFFF),
    litSoft = Color(0x8CFFFFFF),
    litStrong = Color(0xF2FFFFFF),
    mineral = Color(0xFFE0E5E5),
    mineralDeep = Color(0xFFECEFF0),
    muted = Color(0xFF4B585D),
    panelVeil = Color(0xF7F2F4F3),
    perfDots = "radial-gradient(circle at 1px 1px, #bcc4c6 1px, transparent 1px)",
    pressShade = Color(0x3D1B2428),
    pressTravel = Dp(2f),
    questionBg = Color(0xFFE7EDF4),
    questionBorder = Color(0xFF9DB1C9),
    questionInk = Color(0xFF3D5C85),
    radiusKey = Dp(8f),
    radiusLg = Dp(14f),
    radiusMd = Dp(10f),
    radiusSm = Dp(6f),
    railWidth = Dp(288f),
    rockerMs = 110,
    running = Color(0xFF14707D),
    scrim = Color(0x661B2428),
    seamLip = Color(0xFFFFFFFF),
    selectionBg = Color(0xFFD5D0F2),
    shadowFloating = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(10f), blur = Dp(24f), spread = Dp(-8f), color = Color(0x471B2428)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(6f), spread = Dp(0f), color = Color(0x241B2428)),
    ),
    shadowKey = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(0f), spread = Dp(0f), color = Color(0xFFB9C2C4)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(4f), blur = Dp(6f), spread = Dp(-2f), color = Color(0x331B2428)),
    ),
    shadowKeyPressed = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0xFFB9C2C4)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(2f), spread = Dp(0f), color = Color(0x291B2428)),
    ),
    shadowKeySm = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0xFFB9C2C4)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(4f), spread = Dp(-1f), color = Color(0x291B2428)),
    ),
    shadowMenu = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(8f), blur = Dp(24f), spread = Dp(0f), color = Color(0x331B2428)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(4f), spread = Dp(0f), color = Color(0x1A1B2428)),
    ),
    shadowMenuA = 0.2f,
    shadowMenuUp = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(-8f), blur = Dp(24f), spread = Dp(0f), color = Color(0x331B2428)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(6f), spread = Dp(0f), color = Color(0x1A1B2428)),
    ),
    shadowModal = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(22f), blur = Dp(48f), spread = Dp(-16f), color = Color(0x521B2428)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(6f), blur = Dp(14f), spread = Dp(0f), color = Color(0x2E1B2428)),
    ),
    shadowRaised = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(2f), spread = Dp(0f), color = Color(0x291B2428)),
    ),
    slate = Color(0xFFD2D9DA),
    space2xl = Dp(32f),
    spaceLg = Dp(16f),
    spaceMd = Dp(12f),
    spaceSm = Dp(8f),
    spaceXl = Dp(24f),
    spaceXs = Dp(4f),
    surface2 = Color(0x121B2428),
    tintBoost = 1.6f,
    tintLg = Color(0x291B2428),
    tintLine = Color(0x311B2428),
    tintMd = Color(0x181B2428),
    tintRgb = Color(0xFF1B2428),
    tintSm = Color(0x0C1B2428),
    tintXs = Color(0x061B2428),
    userBubbleBg = Color(0xFFCFE4DD),
    userBubbleBorder = Color(0xFF93BDB0),
    userBubbleInk = Color(0xFF17302A),
    utilityInk = Color(0xFFF2F4F3),
    violet = Color(0xFF5546C9),
    violetDeep = Color(0xFF3D3299),
    violetSoft = Color(0xFFE7E4F8),
    violetStrong = Color(0xFF4B3CC4),
    violetWash = Color(0xFFE7E4F8),
    warning = Color(0xFF8A6412),
    wearHi = Color(0x66FFFFFF),
    wearLo = Color(0x0D1B2428),
    well = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(3f), spread = Dp(0f), color = Color(0x291B2428)),
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(5f), blur = Dp(10f), spread = Dp(-7f), color = Color(0x2E1B2428)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0xF2FFFFFF)),
    ),
    white = Color(0xFF1B2428),
    zBackdrop = 40,
    zModal = 50,
    zSticky = 20,
    zToast = 60,
)

private fun machineTokens(): SkinTokens = SkinTokens(
    accent = Color(0xFF2E6D63),
    accentDeep = Color(0xFF245A51),
    accentHover = Color(0xFF357A6F),
    accentInk = Color(0xFFFFFFFF),
    accentSide = Color(0xFF10322D),
    accentWash = Color(0xFF15302C),
    active = Color(0xFF8B7FF0),
    activeWash = Color(0xFF1C1A33),
    amber = Color(0xFFC98F2C),
    amberWash = Color(0xFF2B2210),
    attentionBg = Color(0xFF211A0D),
    attentionBorder = Color(0xFF7A5C1C),
    attentionInk = Color(0xFFE6B455),
    bayFloor = Color(0xFF070A0B),
    bevelPressed = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(4f), spread = Dp(0f), color = Color(0x8C000000)),
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(-1f), blur = Dp(0f), spread = Dp(0f), color = Color(0x08FFFFFF)),
    ),
    bevelRaised = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0x17FFFFFF)),
        CssShadow(inset = true, offsetX = Dp(1f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0x08FFFFFF)),
    ),
    bevelRaisedSm = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0x0DFFFFFF)),
    ),
    bezel = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(1f), color = Color(0xFF454F53)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(6f), color = Color(0xFF111517)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(7f), color = Color(0xFF293236)),
    ),
    border = Color(0xFF293236),
    brick = Color(0xFFA83C31),
    brickDeep = Color(0xFFBD4839),
    brickSide = Color(0xFF5F211A),
    brickWash = Color(0xFF2A1512),
    charcoal = Color(0xFF2A3336),
    charcoalSide = Color(0xFF0C1113),
    chatBubbleMax = 0.86f,
    chatCardWidth = 0.94f,
    chatClamp = Dp(256f),
    contact = Color(0xFF000000),
    danger = Color(0xFFE2685B),
    dangerEdge = Color(0x80E2685B),
    dangerWash = Color(0xFF24100E),
    diffAddBg = Color(0xFF14291F),
    diffAddInk = Color(0xFF86D3A3),
    diffDelBg = Color(0xFF2D1614),
    diffDelInk = Color(0xFFEB8E82),
    dropOverlay = Color(0xDB1C1A33),
    duration = 200,
    durationFast = 140,
    easeOut = CssCubicBezier(0.22f, 1f, 0.36f, 1f),
    edgeHighlight = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0x17FFFFFF)),
    ),
    faint = Color(0xFF808B8F),
    findMatchActiveBg = Color(0xFFFFD21F),
    findMatchBg = Color(0xFFC2A63A),
    findMatchInk = Color(0xFF1F1A05),
    focusGlow = Color(0x4D6F5FE8),
    fontMono = "\"JetBrains Mono Variable\", ui-monospace",
    fontUi = null,
    graphite = Color(0xFF111517),
    graphiteRaised = Color(0xFF1B2225),
    ink = Color(0xFFD2D9DB),
    inspectorWidth = Dp(264f),
    keyFace = Color(0xFF1B2225),
    keyFaceDeep = Color(0xFF141A1C),
    keyFaceHover = Color(0xFF222A2E),
    keyLabelTracking = TextUnit(0.06f, TextUnitType.Em),
    keyLabelTransform = "uppercase",
    keySide = Color(0xFF05080A),
    keySlit = Dp(3f),
    line = Color(0xFF293236),
    lineStrong = Color(0xFF454F53),
    litFaint = Color(0x08FFFFFF),
    litSoft = Color(0x0DFFFFFF),
    litStrong = Color(0x17FFFFFF),
    mineral = Color(0xFF0B0F10),
    mineralDeep = Color(0xFF070A0B),
    muted = Color(0xFF9BA6AA),
    panelVeil = Color(0xF7111517),
    perfDots = "radial-gradient(circle at 1px 1px, #454f53 1px, transparent 1px)",
    pressShade = Color(0x80000000),
    pressTravel = Dp(2f),
    questionBg = Color(0xFF111A24),
    questionBorder = Color(0xFF35506E),
    questionInk = Color(0xFF8FB4DD),
    radiusKey = Dp(8f),
    radiusLg = Dp(14f),
    radiusMd = Dp(10f),
    radiusSm = Dp(6f),
    railWidth = Dp(288f),
    rockerMs = 110,
    running = Color(0xFF5FD3D8),
    scrim = Color(0xB3020506),
    seamLip = Color(0xFF667277),
    selectionBg = Color(0xFF2F2A5C),
    shadowFloating = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(12f), blur = Dp(32f), spread = Dp(-8f), color = Color(0x99000000)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(8f), spread = Dp(0f), color = Color(0x66000000)),
    ),
    shadowKey = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(0f), spread = Dp(0f), color = Color(0xFF05080A)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(3f), blur = Dp(6f), spread = Dp(-2f), color = Color(0x80000000)),
    ),
    shadowKeyPressed = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(0f), blur = Dp(0f), spread = Dp(0f), color = Color(0xFF05080A)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(2f), spread = Dp(0f), color = Color(0x66000000)),
    ),
    shadowKeySm = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0xFF05080A)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(4f), spread = Dp(-1f), color = Color(0x73000000)),
    ),
    shadowMenu = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(8f), blur = Dp(24f), spread = Dp(0f), color = Color(0x80000000)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(4f), spread = Dp(0f), color = Color(0x40000000)),
    ),
    shadowMenuA = 0.5f,
    shadowMenuUp = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(-8f), blur = Dp(24f), spread = Dp(0f), color = Color(0x80000000)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(2f), blur = Dp(6f), spread = Dp(0f), color = Color(0x40000000)),
    ),
    shadowModal = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(24f), blur = Dp(64f), spread = Dp(-16f), color = Color(0xA8000000)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(6f), blur = Dp(16f), spread = Dp(0f), color = Color(0x70000000)),
    ),
    shadowRaised = listOf(
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(2f), spread = Dp(0f), color = Color(0x80000000)),
    ),
    slate = Color(0xFF283135),
    space2xl = Dp(32f),
    spaceLg = Dp(16f),
    spaceMd = Dp(12f),
    spaceSm = Dp(8f),
    spaceXl = Dp(24f),
    spaceXs = Dp(4f),
    surface2 = Color(0x0DFFFFFF),
    tintBoost = 1f,
    tintLg = Color(0x1AFFFFFF),
    tintLine = Color(0x1FFFFFFF),
    tintMd = Color(0x0FFFFFFF),
    tintRgb = Color(0xFFFFFFFF),
    tintSm = Color(0x08FFFFFF),
    tintXs = Color(0x04FFFFFF),
    userBubbleBg = Color(0xFF1D3B37),
    userBubbleBorder = Color(0xFF2D5B54),
    userBubbleInk = Color(0xFFE4EFED),
    utilityInk = Color(0xFFDFE6E8),
    violet = Color(0xFF8B7FF0),
    violetDeep = Color(0xFFB4AAFF),
    violetSoft = Color(0xFF1C1A33),
    violetStrong = Color(0xFF6F5FE8),
    violetWash = Color(0xFF1C1A33),
    warning = Color(0xFFE0A53A),
    wearHi = Color(0x0DFFFFFF),
    wearLo = Color(0x29000000),
    well = listOf(
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(3f), spread = Dp(0f), color = Color(0x80000000)),
        CssShadow(inset = true, offsetX = Dp(0f), offsetY = Dp(6f), blur = Dp(12f), spread = Dp(-8f), color = Color(0x80000000)),
        CssShadow(inset = false, offsetX = Dp(0f), offsetY = Dp(1f), blur = Dp(0f), spread = Dp(0f), color = Color(0x08FFFFFF)),
    ),
    white = Color(0xFFEEF2F3),
    zBackdrop = 40,
    zModal = 50,
    zSticky = 20,
    zToast = 60,
)

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
