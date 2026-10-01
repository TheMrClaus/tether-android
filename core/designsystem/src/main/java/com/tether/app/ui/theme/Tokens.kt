package com.tether.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Tether token set the UI reads via [LocalTetherTokens]. Since T3.1 (PLAN D9) every value
 * comes from [GeneratedTokens] (generated from the web's app/globals.css + app/studio.css);
 * nothing here is hand-copied. This class is the stable, named facade the components already
 * use; [css] exposes the complete typed skin (all ~126 custom properties) for anything newer.
 * Material3's ColorScheme is only mapped for interop (dialogs, text selection).
 */

/** The web's mode → Studio skin resolution (lib/theme-mode.mjs resolveThemeSkin). */
fun ThemeMode.resolve(systemDark: Boolean): TetherSkin = TetherSkin.of(isDark(systemDark))

/** The explicit mode that renders this skin whatever the device asks for (previews, tests, goldens). */
val TetherSkin.mode: ThemeMode get() = if (isDark) ThemeMode.Dark else ThemeMode.Light

/**
 * Structural dimensions the components treat as theme-invariant: Studio light's values, which
 * both Studio skins share (spacing, radii, durations are not lighting-dependent).
 */
object TetherDimens {
    private val base = GeneratedTokens.Studio

    val spaceXs = base.spaceXs
    val spaceSm = base.spaceSm
    val spaceMd = base.spaceMd
    val spaceLg = base.spaceLg
    val spaceXl = base.spaceXl
    val radiusSm = base.radiusSm
    val radiusMd = base.radiusMd
    val radiusLg = base.radiusLg

    val durationFastMs = base.durationFast
    val durationSlowMs = base.duration
    const val touchTarget = 44 // dp — DESIGN.md 44dp targets (not a CSS token)
    val touchTargetDp = 44.dp
}

@Immutable
class TetherTokens internal constructor(
    val skin: TetherSkin,
    /**
     * Every generated token of this skin — or, inside a CSS scope that redeclares custom
     * properties ([scoped], T5.1), the skin's tokens with that scope's values.
     */
    val css: SkinTokens,
) {
    constructor(skin: TetherSkin) : this(skin, skin.tokens)

    // Surfaces
    val mineral: Color = css.mineral
    val mineralDeep: Color = css.mineralDeep
    val graphite: Color = css.graphite
    val graphiteRaised: Color = css.graphiteRaised
    val slate: Color = css.slate

    // Seams
    val line: Color = css.line
    val lineStrong: Color = css.lineStrong

    // Text
    val white: Color = css.white
    val ink: Color = css.ink
    val muted: Color = css.muted
    val faint: Color = css.faint

    // Violet — ONLY focus / selected / waiting-for-user
    val violet: Color = css.violet
    val violetStrong: Color = css.violetStrong
    val violetDeep: Color = css.violetDeep
    val violetWash: Color = css.violetWash
    val focusGlow: Color = css.focusGlow
    val selectionBg: Color = css.selectionBg

    // Status
    val running: Color = css.running
    val danger: Color = css.danger
    val warning: Color = css.warning
    val dangerEdge: Color = css.dangerEdge
    val dangerWash: Color = css.dangerWash

    // Keys
    val keyFace: Color = css.keyFace
    val keyFaceHover: Color = css.keyFaceHover
    val keyFaceDeep: Color = css.keyFaceDeep
    val keySide: Color = css.keySide
    val accent: Color = css.accent
    val accentHover: Color = css.accentHover
    val accentDeep: Color = css.accentDeep
    val accentSide: Color = css.accentSide
    val accentInk: Color = css.accentInk
    val accentWash: Color = css.accentWash
    val brick: Color = css.brick
    val brickDeep: Color = css.brickDeep
    val brickSide: Color = css.brickSide
    val brickWash: Color = css.brickWash
    val amber: Color = css.amber
    val amberWash: Color = css.amberWash
    val charcoal: Color = css.charcoal
    val charcoalSide: Color = css.charcoalSide
    val utilityInk: Color = css.utilityInk

    // Light & shade
    val contact: Color = css.contact
    val tintRgb: Color = css.tintRgb
    val tintBoost: Float = css.tintBoost
    val tintXs: Color = css.tintXs
    val tintSm: Color = css.tintSm
    val tintMd: Color = css.tintMd
    val tintLg: Color = css.tintLg
    val tintLine: Color = css.tintLine

    // Key geometry
    val radiusKey: Dp = css.radiusKey

    // Radii
    val radiusSm: Dp = css.radiusSm
    val radiusMd: Dp = css.radiusMd
    val radiusLg: Dp = css.radiusLg

    // Overlays / transcript
    val scrim: Color = css.scrim
    val userBubbleBg: Color = css.userBubbleBg
    val userBubbleBorder: Color = css.userBubbleBorder
    val userBubbleInk: Color = css.userBubbleInk
    val diffAddBg: Color = css.diffAddBg
    val diffAddInk: Color = css.diffAddInk
    val diffDelBg: Color = css.diffDelBg
    val diffDelInk: Color = css.diffDelInk
    val attentionBorder: Color = css.attentionBorder
    val attentionBg: Color = css.attentionBg
    val attentionInk: Color = css.attentionInk
    val questionBorder: Color = css.questionBorder
    val questionBg: Color = css.questionBg
    val questionInk: Color = css.questionInk
    val dropOverlay: Color = css.dropOverlay

    // Depth (the Machine-era --lit-* / --wear-* bevels were retired at tether 887c222)
    val pressShade: Color = css.pressShade

    private val hash: Int = 31 * skin.hashCode() + css.hashCode()

    override fun equals(other: Any?): Boolean =
        this === other || (other is TetherTokens && other.skin == skin && other.hash == hash && other.css == css)
    override fun hashCode(): Int = hash
    override fun toString(): String = if (css == skin.tokens) "TetherTokens(${skin.id})" else "TetherTokens(${skin.id}, scoped)"
}

private val tokensBySkin: Map<TetherSkin, TetherTokens> = TetherSkin.entries.associateWith(::TetherTokens)

fun tokensFor(skin: TetherSkin): TetherTokens = tokensBySkin.getValue(skin)

val LocalTetherTokens = staticCompositionLocalOf { tokensFor(TetherSkin.Studio) }
