package com.tether.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
 * The molded "key" control (visual-spec §4 composer + §2.3): a raised face over a
 * solid side, 1px border, press = travel down onto the side, uppercase legend.
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
    minHeight: Dp = TetherDimens.touchTargetDp,
    contentDescription: String? = null,
) {
    val t = LocalTetherTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val face: Color
    val facePressed: Color
    val side: Color
    val inkColor: Color
    when (variant) {
        KeyVariant.Primary -> {
            face = t.accent; facePressed = t.accentDeep; side = t.accentSide; inkColor = t.accentInk
        }
        KeyVariant.Secondary -> {
            face = t.keyFace; facePressed = t.keyFaceDeep; side = t.keySide; inkColor = t.ink
        }
        KeyVariant.Brick -> {
            face = t.brick; facePressed = t.brickDeep; side = t.brickSide; inkColor = t.accentInk
        }
        KeyVariant.Utility -> {
            face = t.charcoal; facePressed = t.charcoal; side = t.charcoalSide; inkColor = t.utilityInk
        }
    }

    val travel = t.pressTravel
    val shape = RoundedCornerShape(t.radiusKey)
    val down = pressed && enabled
    val density = LocalDensity.current
    val radiusPx = with(density) { t.radiusKey.toPx() }
    val travelPx = with(density) { travel.toPx() }

    Box(
        modifier = modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .drawBehind {
                // The static side the face travels onto (shadow-key "0 Npx 0 key-side").
                drawRoundRect(
                    color = side,
                    topLeft = Offset(0f, travelPx),
                    size = Size(size.width, size.height - travelPx),
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                )
            }
            .padding(bottom = travel),
    ) {
        Row(
            modifier = Modifier
                .offset(y = if (down) travel else 0.dp)
                .background(if (down) facePressed else face, shape)
                .border(1.dp, side, shape)
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
}
