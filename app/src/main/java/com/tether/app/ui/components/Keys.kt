package com.tether.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights

/**
 * The molded "key" control (visual-spec §4 composer + §2.3 + globals.css
 * ":root .button-*" material layer): a raised face over a solid side-wall,
 * lit top-left bevel, soft drop contact shadow, uppercase legend. Pressing
 * travels the face down onto the side, swaps the lit bevel for a recessed
 * [pressShade], and shrinks the contact shadow. Hover deepens the fill only.
 * Disabled keys lose elevation and contrast. The most-used keys (primary
 * CTAs and the sidebar's New session) carry a subtle radial wear polish.
 *
 * A strong mechanical haptic (QUICK_RISE on press, THUD on release, both at
 * maximum scale) fires on every enabled click, so the key feels like a
 * physical button.
 */

enum class KeyVariant { Primary, Secondary, Brick, Utility }

@Composable
fun TetherKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: KeyVariant = KeyVariant.Secondary,
    label: String? = null,
    icon: ImageVector? = null,
    iconSize: Dp = 15.dp,
    fontSize: TextUnit = 13.sp,
    enabled: Boolean = true,
    showSlit: Boolean = false,
    /** Subtle contact-polish wear on the face — primary CTAs and the New
     *  session key in the sidebar (visual-spec §2.3 "Wear / contact polish
     *  goes ONLY on genuinely frequent controls"). */
    wear: Boolean = variant == KeyVariant.Primary,
    minHeight: Dp = TetherDimens.touchTargetDp,
    contentDescription: String? = null,
) {
    val t = LocalTetherTokens.current
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()

    // QUICK_RISE on press-down sells the button biting back; the THUD on
    // release completes the mechanical feel — both at maximum scale.
    LaunchedEffect(pressed) {
        if (pressed && enabled) haptics.press()
        else if (!pressed && enabled) haptics.release()
    }

    val face: Color
    val faceHover: Color
    val facePressed: Color
    val side: Color
    val inkColor: Color
    when (variant) {
        KeyVariant.Primary -> {
            face = t.accent; faceHover = t.accentHover; facePressed = t.accentDeep
            side = t.accentSide; inkColor = t.accentInk
        }
        KeyVariant.Secondary -> {
            face = t.keyFace; faceHover = t.keyFaceHover; facePressed = t.keyFaceDeep
            side = t.keySide; inkColor = t.ink
        }
        KeyVariant.Brick -> {
            face = t.brick; faceHover = t.brickDeep; facePressed = t.brickDeep
            side = t.brickSide; inkColor = t.accentInk
        }
        KeyVariant.Utility -> {
            face = t.charcoal; faceHover = t.charcoal; facePressed = t.charcoal
            side = t.charcoalSide; inkColor = t.utilityInk
        }
    }

    val travel = t.pressTravel
    val shape = RoundedCornerShape(t.radiusKey)
    val down = pressed && enabled
    // Disabled keys lose elevation: no travel, no contact shadow, no bevel.
    val resting = enabled && !down
    val density = LocalDensity.current
    val radiusPx = with(density) { t.radiusKey.toPx() }
    val travelPx = with(density) { travel.toPx() }
    val elevationPx = with(density) { t.shadowElevation.toPx() }

    // Face fill: hover deepens the fill, press sinks to the deep face.
    val faceColor = when {
        !enabled -> face
        down -> facePressed
        hovered -> faceHover
        else -> face
    }

    // The contact shadow under a resting key (the soft "0 3px 6px -2px"
    // component of --shadow-key). Compose's Modifier.shadow draws a single
    // Gaussian with the key's own shape, which is exactly this. We offset
    // it down by ~half the side-wall so the shadow sits under the side slab.
    val shadowModifier = if (resting) {
        Modifier.shadow(
            elevation = t.shadowElevation,
            shape = shape,
            clip = false,
            ambientColor = t.contact.copy(alpha = 0.45f),
            spotColor = t.contact.copy(alpha = 0.55f),
        )
    } else if (down) {
        // Pressed: shrink to the --shadow-key-pressed "0 1px 2px" residual.
        Modifier.shadow(
            elevation = 1.dp,
            shape = shape,
            clip = false,
            ambientColor = t.contact.copy(alpha = 0.4f),
            spotColor = t.contact.copy(alpha = 0.45f),
        )
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .then(shadowModifier)
            .drawBehind {
                // The hard side-wall the face travels onto (--shadow-key's
                // "0 2px 0 key-side" component). Drawn as a solid slab offset
                // down by the press travel; the face sits on top of it.
                if (enabled) {
                    drawRoundRect(
                        color = side,
                        topLeft = Offset(0f, travelPx),
                        size = Size(size.width, size.height - travelPx),
                        cornerRadius = CornerRadius(radiusPx, radiusPx),
                    )
                }
            }
            .padding(bottom = if (enabled) travel else 0.dp)
            .offset(y = if (down) travel else 0.dp)
            .background(faceColor, shape)
            .drawBehind {
                // Lit bevel on a resting key: the web's --edge-highlight is an
                // INSET box-shadow ("inset 0 1px 0 lit-strong"), which feathers
                // naturally. A 1dp solid strip reads as a crisp line, so we
                // approximate the inset with a short vertical gradient that
                // fades from litStrong to transparent over ~2dp — the same
                // feather CSS gets for free. Press swaps it for a recessed
                // [pressShade] along the top edge (--bevel-pressed).
                val cornerRadius = CornerRadius(radiusPx, radiusPx)
                if (resting) {
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(t.litStrong, Color.Transparent),
                            startY = 0f,
                            endY = 2.dp.toPx(),
                        ),
                        cornerRadius = cornerRadius,
                    )
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(t.litSoft, Color.Transparent),
                            startX = 0f,
                            endX = 2.dp.toPx(),
                        ),
                        cornerRadius = cornerRadius,
                    )
                } else if (down) {
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(t.pressShade, Color.Transparent),
                            startY = 0f,
                            endY = 3.dp.toPx(),
                        ),
                        cornerRadius = cornerRadius,
                    )
                }
            }
            .then(if (wear && enabled) Modifier.drawBehind {
                // Wear: a single soft radial polish, only on the most-used
                // keys. Subtle material texture, never glossy.
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(t.wearHi, Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.3f),
                        radius = size.maxDimension * 0.7f,
                    ),
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                )
            } else Modifier)
            .border(1.dp, side, shape)
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .heightIn(min = minHeight)
            .padding(horizontal = if (label != null) 14.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showSlit && t.keySlit > 0.dp) {
            Box(
                Modifier
                    .size(width = t.keySlit, height = 10.dp)
                    .alpha(0.55f)
                    .background(inkColor, RoundedCornerShape(1.5.dp)),
            )
        }
        if (icon != null) {
            Icon(icon, contentDescription = contentDescription ?: label, tint = inkColor, modifier = Modifier.size(iconSize))
        }
        if (label != null) {
            Text(
                text = label.uppercase(),
                color = inkColor,
                fontFamily = Manrope,
                fontWeight = TetherWeights.name,
                fontSize = fontSize,
                letterSpacing = t.keyTracking.em,
                maxLines = 1,
            )
        }
    }
}