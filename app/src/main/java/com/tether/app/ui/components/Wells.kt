package com.tether.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights

/**
 * Recessed input well (visual-spec §4 composer / §6): mineral-deep bg, 1px
 * line-strong border, inner top shadow approximated by a top-edge gradient,
 * violet-strong border + 3dp focus-glow ring when focused.
 */
@Composable
fun TetherInputWell(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    maxLines: Int = if (singleLine) 1 else 6,
) {
    val t = LocalTetherTokens.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TetherDimens.radiusMd)
    val borderColor = if (focused) t.violetStrong else t.lineStrong

    Box(
        modifier = modifier
            .drawBehind {
                if (focused) {
                    // 3dp focus-glow ring just outside the border.
                    val ring = 3.dp.toPx()
                    drawRoundRect(
                        color = t.focusGlow,
                        topLeft = Offset(-ring / 2f, -ring / 2f),
                        size = Size(size.width + ring, size.height + ring),
                        cornerRadius = CornerRadius(TetherDimens.radiusMd.toPx() + ring / 2f),
                        style = Stroke(width = ring),
                    )
                }
            }
            .clip(shape)
            .background(t.mineralDeep)
            .drawBehind {
                // Recessed: subtle dark shadow along the top edge.
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(t.contact.copy(alpha = 0.18f), Color.Transparent),
                        startY = 0f,
                        endY = 8.dp.toPx(),
                    ),
                )
            }
            .border(1.dp, borderColor, shape)
            .heightIn(min = TetherDimens.touchTargetDp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            enabled = enabled,
            textStyle = TextStyle(
                color = t.ink,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 16.sp,
            ),
            cursorBrush = SolidColor(t.violet),
            singleLine = singleLine,
            maxLines = maxLines,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interaction,
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = t.faint,
                            fontFamily = Manrope,
                            fontWeight = TetherWeights.body,
                            fontSize = 16.sp,
                            maxLines = 2,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}
