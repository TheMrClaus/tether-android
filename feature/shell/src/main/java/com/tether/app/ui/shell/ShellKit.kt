package com.tether.app.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.KeyState
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.focusRing
import com.tether.app.ui.components.resolveKey
import com.tether.app.ui.theme.CssLineHeight
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherTokens

/** A CSS text role at [rem] × 16sp with the web's centred CSS line box. */
internal fun cssText(
    family: FontFamily,
    rem: Float,
    weight: Int,
    trackingEm: Float = 0f,
    lineHeight: Float? = null,
): TextStyle = TextStyle(
    fontFamily = family,
    fontSize = (rem * 16f).sp,
    fontWeight = FontWeight(weight),
    letterSpacing = if (trackingEm == 0f) TextUnit.Unspecified else trackingEm.em,
    lineHeight = lineHeight?.em ?: TextUnit.Unspecified,
    lineHeightStyle = CssLineHeight,
)

/**
 * `@keyframes dialog-in` progress, 0 → 1 over `--duration` with `--ease-out` (globals.css 3869);
 * 1 at once under reduced motion (the global `prefers-reduced-motion` rule).
 */
@Composable
internal fun rememberDialogIn(): Animatable<Float, AnimationVector1D> {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) {
        if (!reduced) progress.animateTo(1f, tween(t.css.duration, easing = t.css.easeOut.toEasing()))
    }
    return progress
}

/**
 * Widens a centred-content control by [extra] beyond its intrinsic width — for a key whose web
 * rule pads its legend more than [com.tether.app.ui.components.TetherKey]'s `0 var(--space-lg)`.
 */
internal fun Modifier.extraWidth(extra: Dp): Modifier = layout { measurable, constraints ->
    val wanted = measurable.maxIntrinsicWidth(constraints.maxHeight) + extra.roundToPx()
    val w = wanted.coerceIn(constraints.minWidth, constraints.maxWidth)
    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** One painted state of a chrome control (face, edge, legend colour, box-shadow list). */
internal data class ChromeLook(
    val face: Color,
    val border: Color,
    val ink: Color,
    val shadows: List<CssShadow>,
    val radius: Dp,
    val alpha: Float = 1f,
)

/**
 * The quiet icon controls of the shell chrome — `.mobile-menu`, `.logout-button`, `.icon-button`,
 * `.rename-session` — which share the `.icon-button` material (globals.css 753-776, 8971-8989):
 * transparent at rest, seated `--key-face-deep` with the pressed bevel while held. Their colour is
 * inherited from the host, so it is a parameter here ([ink]); the material comes from the T3.3
 * cascade ([resolveKey] with the `icon-button` class set) unless a host passes its own [look].
 */
@Composable
internal fun rememberIconLook(ink: Color, radius: Dp, enabled: Boolean = true): (KeyState) -> ChromeLook {
    val t = LocalTetherTokens.current
    return remember(t, ink, radius, enabled) {
        { state ->
            val k = resolveKey(t, KeyClasses.IconButton, state)
            ChromeLook(k.face, Color.Transparent, ink, k.shadows, radius, if (enabled) 1f else k.alpha)
        }
    }
}

/**
 * A square chrome control of an exact web size ([width] × [height]) holding one glyph. Compose
 * extends a clickable's hit area to the 48dp minimum touch target beyond its drawn bounds
 * (ViewConfiguration.minimumTouchTargetSize), so a control the web draws smaller than 44dp
 * (the 34.4dp tool keys, the rename pencil) still takes a ≥ 44dp touch.
 */
@Composable
internal fun ChromeIconKey(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    look: (KeyState) -> ChromeLook,
    width: Dp,
    height: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    stateDescription: String? = null,
    interactionSource: MutableInteractionSource? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val state = when {
        !enabled -> KeyState.Disabled
        pressed -> KeyState.Pressed
        else -> KeyState.Rest
    }
    val l = look(state)
    val shape = RoundedCornerShape(l.radius)
    Box(
        modifier = modifier
            .semantics {
                this.contentDescription = contentDescription
                stateDescription?.let { this.stateDescription = it }
            }
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .size(width, height)
            .graphicsLayer {
                alpha = l.alpha
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .focusRing(focused, shape, t.violet)
            .cssSurface(shape, l.face, if (l.border.alpha > 0f) CssBorder(1.dp, l.border) else null, l.shadows),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = l.ink, modifier = Modifier.size(iconSize))
        overlay?.invoke(this)
    }
}

/**
 * [ChromeIconKey] with a printed word: the desktop topbar's tool keys (`Files`, `Accounts`,
 * `Usage`, `Health`, globals.css 11843-11846), Lock ("Lock", 4029-4031) and the rail's dock. At
 * least [minWidth] × [height], the glyph and [label] [gap] apart inside [padStart] / [padEnd].
 * A null [label] draws the glyph alone.
 */
@Composable
internal fun ChromeLabelKey(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String?,
    contentDescription: String,
    look: (KeyState) -> ChromeLook,
    minWidth: Dp,
    height: Dp,
    iconSize: Dp,
    labelStyle: TextStyle,
    modifier: Modifier = Modifier,
    padStart: Dp = 0.dp,
    padEnd: Dp = 0.dp,
    gap: Dp = 0.dp,
    underline: Boolean = false,
    enabled: Boolean = true,
    stateDescription: String? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val state = when {
        !enabled -> KeyState.Disabled
        pressed -> KeyState.Pressed
        else -> KeyState.Rest
    }
    val l = look(state)
    val shape = RoundedCornerShape(l.radius)
    Box(
        modifier = modifier
            .semantics {
                this.contentDescription = contentDescription
                stateDescription?.let { this.stateDescription = it }
            }
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .widthIn(min = minWidth)
            .height(height)
            .graphicsLayer {
                alpha = l.alpha
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .focusRing(focused, shape, t.violet)
            .cssSurface(shape, l.face, if (l.border.alpha > 0f) CssBorder(1.dp, l.border) else null, l.shadows),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(start = padStart, end = padEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Icon(icon, contentDescription = null, tint = l.ink, modifier = Modifier.size(iconSize))
            if (label != null) {
                Text(
                    label,
                    color = l.ink,
                    maxLines = 1,
                    style = if (underline) labelStyle.copy(textDecoration = TextDecoration.Underline) else labelStyle,
                )
            }
        }
        overlay?.invoke(this)
    }
}
