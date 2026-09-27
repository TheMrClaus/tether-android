package com.tether.app.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.ThemeFamily
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * The key system of the web's material layer, resolved per skin the way the CSS cascade resolves
 * it. Sources: globals.css "MATERIAL LAYER" (key system 8589-8769, icon controls 8970-8989,
 * latched filter keys 11048-11056) and, for the Studio family, studio.css 260-278 + 489-497,
 * which flatten the material. Where a globals rule out-ranks Studio's `:root:where()` rules
 * (`:active:not(:disabled)` and `:disabled`, specificity (0,4,0)/(0,3,0) vs Studio's (0,2,0)),
 * the globals rule wins in Studio too, exactly as in the browser.
 */
enum class KeyVariant {
    /** `.button-primary` / `.chat-send`: the accent key (sage / mineral / cobalt). */
    Primary,

    /** `.button-secondary` and the neutral key group (quick keys, attach, question options). */
    Secondary,

    /** Destructive: `.chat-interrupt`, `.chat-approval-deny`, `.button-danger`, `.end-session`. */
    Brick,

    /** `.chat-jump`: the charcoal utility cap that floats over the transcript. */
    Utility,

    /** `.icon-button`: quiet at rest, molded only while pressed. */
    Quiet,
}

/** Which drop-shadow scale a neutral key uses: `--shadow-key` or the compact `--shadow-key-sm`. */
enum class KeySize { Regular, Small }

/** Interaction state that changes the key's material (focus is a separate, colour-free ring). */
enum class KeyState { Rest, Pressed, Disabled }

@Immutable
data class KeyLook(
    val face: Color,
    val border: Color,
    val ink: Color,
    /** The full box-shadow list (inset bevels + side-wall + contact shadow), CSS order. */
    val shadows: List<CssShadow>,
    /** translateY while pressed (`--press-travel`; 0 in Studio). */
    val travel: Dp,
    val radius: Dp,
    /** `button:disabled { opacity: 0.48 }` (globals.css 641-647). */
    val alpha: Float,
    /** Execution-slit opacity (0.55 at rest, 0.25 disabled; globals.css 9245/9253). */
    val slitAlpha: Float,
)

/** Studio's labelled keys: `border-radius: 0.625rem` (studio.css:264, a literal, not a token). */
val StudioKeyRadius: Dp = 10.dp

/** `button:disabled` opacity (globals.css:647). */
const val DisabledOpacity: Float = 0.48f

fun resolveKey(
    t: TetherTokens,
    variant: KeyVariant,
    state: KeyState,
    selected: Boolean = false,
    size: KeySize = KeySize.Regular,
): KeyLook {
    val studio = t.skin.family == ThemeFamily.Studio
    val css = t.css
    val contact = { a: Float -> t.contact.copy(alpha = a) }
    val bevel = listOf(hardShadow(1.dp, t.litStrong, inset = true), hardShadow(0.dp, t.litSoft, x = 1.dp, inset = true))
    val keyDrop = if (size == KeySize.Small) css.shadowKeySm else css.shadowKey
    val radius = when {
        variant == KeyVariant.Quiet -> t.radiusSm
        studio -> StudioKeyRadius
        else -> t.radiusKey
    }

    if (state == KeyState.Disabled && variant != KeyVariant.Quiet && variant != KeyVariant.Utility) {
        // globals.css 8757-8769: flat on the panel, muted legend (wins in Studio: (0,3,0)).
        return KeyLook(
            face = t.keyFace,
            border = t.lineStrong,
            ink = t.muted,
            shadows = listOf(hardShadow(1.dp, t.keySide)),
            travel = 0.dp,
            radius = radius,
            alpha = DisabledOpacity,
            slitAlpha = 0.25f,
        )
    }
    val pressed = state == KeyState.Pressed
    val disabledAlpha = if (state == KeyState.Disabled) DisabledOpacity else 1f
    val travel = if (pressed) t.pressTravel else 0.dp

    return when (variant) {
        KeyVariant.Secondary -> when {
            pressed -> KeyLook(
                face = t.keyFaceDeep,
                border = if (studio) t.lineStrong else t.keySide,
                ink = t.ink,
                shadows = css.bevelPressed + css.shadowKeyPressed,
                travel = travel, radius = radius, alpha = 1f, slitAlpha = 0.55f,
            )
            selected -> latched(t, radius)
            studio -> KeyLook(t.graphite, t.lineStrong, t.ink, emptyList(), 0.dp, radius, 1f, 0.55f)
            else -> KeyLook(t.keyFace, t.keySide, t.ink, bevel + keyDrop, 0.dp, radius, 1f, 0.55f)
        }

        KeyVariant.Primary -> when {
            // globals.css 8711-8717 ((0,4,0): also wins over Studio's flat rule).
            pressed -> KeyLook(
                face = t.accentDeep,
                border = if (studio) Color.Transparent else t.accentSide,
                ink = t.accentInk,
                shadows = listOf(
                    softShadow(2.dp, 3.dp, t.pressShade, inset = true),
                    hardShadow((-1).dp, t.litFaint, inset = true),
                    hardShadow(1.dp, t.accentSide),
                    softShadow(1.dp, 2.dp, contact(0.24f)),
                ),
                travel = travel, radius = radius, alpha = 1f, slitAlpha = 0.55f,
            )
            studio -> KeyLook(t.accent, Color.Transparent, t.accentInk, emptyList(), 0.dp, radius, 1f, 0.55f)
            else -> KeyLook(
                face = t.accent,
                border = t.accentSide,
                ink = t.accentInk,
                shadows = listOf(
                    hardShadow(1.dp, t.litFaint, inset = true),
                    hardShadow(0.dp, t.litFaint, x = 1.dp, inset = true),
                    hardShadow((-1).dp, contact(0.1f), x = (-1).dp, inset = true),
                    hardShadow(3.dp, t.accentSide),
                    softShadow(5.dp, 7.dp, contact(0.32f), spread = (-2).dp),
                ),
                travel = 0.dp, radius = radius, alpha = 1f, slitAlpha = 0.55f,
            )
        }

        KeyVariant.Brick -> when {
            // Studio flattens destructive keys in every state (studio.css 489-497).
            studio -> KeyLook(if (pressed) t.brickDeep else t.brick, Color.Transparent, t.accentInk, emptyList(), 0.dp, radius, 1f, 0.55f)
            pressed -> KeyLook(
                face = t.brickDeep,
                border = t.brickSide,
                ink = t.accentInk,
                shadows = listOf(
                    softShadow(2.dp, 3.dp, t.pressShade, inset = true),
                    hardShadow(1.dp, t.brickSide),
                    softShadow(1.dp, 2.dp, contact(0.24f)),
                ),
                travel = travel, radius = radius, alpha = 1f, slitAlpha = 0.55f,
            )
            else -> KeyLook(
                face = t.brick,
                border = t.brickSide,
                ink = t.accentInk,
                shadows = listOf(
                    hardShadow(1.dp, t.litFaint, inset = true),
                    hardShadow((-1).dp, contact(0.1f), x = (-1).dp, inset = true),
                    hardShadow(2.dp, t.brickSide),
                    softShadow(4.dp, 6.dp, contact(0.3f), spread = (-2).dp),
                ),
                travel = 0.dp, radius = radius, alpha = 1f, slitAlpha = 0.55f,
            )
        }

        // globals.css 8734-8753 (no Studio override: Studio's tokens flatten the lit edge).
        KeyVariant.Utility -> if (pressed) {
            KeyLook(
                face = oklabMix(t.charcoal, t.contact, 0.9f),
                border = t.charcoalSide,
                ink = t.utilityInk,
                shadows = listOf(softShadow(2.dp, 3.dp, t.pressShade, inset = true), hardShadow(1.dp, t.charcoalSide)) + css.shadowFloating,
                travel = travel, radius = radius, alpha = 1f, slitAlpha = 0.55f,
            )
        } else {
            KeyLook(
                face = t.charcoal,
                border = t.charcoalSide,
                ink = t.utilityInk,
                shadows = listOf(hardShadow(1.dp, t.litSoft, inset = true), hardShadow(2.dp, t.charcoalSide)) + css.shadowFloating,
                travel = 0.dp, radius = radius, alpha = disabledAlpha, slitAlpha = 0.55f,
            )
        }

        // globals.css 755-768 + 8981-8989; latched filter keys 11048-11056.
        KeyVariant.Quiet -> when {
            pressed -> KeyLook(t.keyFaceDeep, Color.Transparent, t.white, css.bevelPressed + css.shadowKeyPressed, travel, radius, 1f, 0.55f)
            selected -> latched(t, radius).copy(border = Color.Transparent)
            else -> KeyLook(Color.Transparent, Color.Transparent, t.muted, emptyList(), 0.dp, radius, disabledAlpha, 0.55f)
        }
    }
}

/**
 * A latched (selected) key: it "sits raised and carries the selected tone" — violet wash, a 1px
 * violet-strong inset ring, violet legend (globals.css 11048-11056). Violet here marks SELECTED.
 */
private fun latched(t: TetherTokens, radius: Dp): KeyLook = KeyLook(
    face = t.violetWash,
    border = Color.Transparent,
    ink = t.violet,
    shadows = listOf(
        hardShadow(1.dp, t.litStrong, inset = true),
        CssShadow(inset = true, offsetX = 0.dp, offsetY = 0.dp, blur = 0.dp, spread = 1.dp, color = t.violetStrong),
    ) + t.css.shadowKeySm,
    travel = 0.dp,
    radius = radius,
    alpha = 1f,
    slitAlpha = 0.55f,
)

/**
 * CSS `color-mix(in oklab, a p, b)` for opaque colours (the chat-jump press face). Exact: sRGB →
 * linear → OKLab (Björn Ottosson's matrices, as CSS Color 4 specifies), lerp, and back.
 */
fun oklabMix(a: Color, b: Color, pA: Float): Color {
    val la = a.toOklab()
    val lb = b.toOklab()
    val mix = FloatArray(3) { la[it] * pA + lb[it] * (1f - pA) }
    return fromOklab(mix, alpha = a.alpha * pA + b.alpha * (1f - pA))
}

private fun Color.toOklab(): FloatArray {
    fun lin(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    val r = lin(red); val g = lin(green); val b = lin(blue)
    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
    return floatArrayOf(
        0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
    )
}

private fun fromOklab(lab: FloatArray, alpha: Float): Color {
    val l = lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2]
    val m = lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2]
    val s = lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2]
    val l3 = l * l * l; val m3 = m * m * m; val s3 = s * s * s
    fun enc(c: Float): Float {
        val v = c.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) 12.92f * v else (1.055f * v.toDouble().pow(1 / 2.4) - 0.055).toFloat()
    }
    return Color(
        red = enc(4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3),
        green = enc(-1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3),
        blue = enc(-0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3),
        alpha = alpha.coerceIn(0f, 1f),
    )
}
