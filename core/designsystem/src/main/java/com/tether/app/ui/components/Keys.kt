package com.tether.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherDimens

/** The key's silhouette: a labelled/rounded key, or the round `.chat-jump` cap. */
enum class KeyShape { Rounded, Circle }

/**
 * A key of the web's button grammar (globals.css 8565-8578, dressed flat by studio.css 260-278):
 * the face, border and box-shadow list resolved by [resolveKey] and drawn by [cssSurface];
 * disabled keys sit at 0.48 opacity; focus is an independent violet ring ([focusRing]); a latched
 * ([selected]) key carries the violet selected tone.
 *
 * Legends: [label] is a fixed verb by default and takes the skin's etched-legend transform
 * (none in Studio: `--key-label-transform`/`--key-label-tracking`); pass `fixedVerb = false` for
 * user/provider content, which must render as authored (globals.css 9197-9213). The accessible
 * name is always the ORIGINAL words (`contentDescription ?: label`), never the uppercased
 * string — like the web, where text-transform leaves the DOM text alone.
 *
 * Touch: at least 44×44dp (DESIGN.md). Haptics: [TetherHaptics.keyDown] / [TetherHaptics.keyUp].
 */
@Composable
fun TetherKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The web class set this key mirrors (e.g. [KeyClasses.ChatInterrupt]); see [resolveKey]. */
    classes: Set<KeyClass> = KeyClasses.ButtonSecondary,
    label: String? = null,
    icon: ImageVector? = null,
    iconSize: Dp = 15.dp,
    /** Unspecified: Studio's key-legend size (0.8125rem). */
    fontSize: TextUnit = TextUnit.Unspecified,
    enabled: Boolean = true,
    minHeight: Dp = TetherDimens.touchTargetDp,
    contentDescription: String? = null,
    selected: Boolean = false,
    size: KeySize = KeySize.Regular,
    /** Null: the class set's radius (a round cap for `chat-jump`). */
    shape: KeyShape? = null,
    fixedVerb: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    /** Null: the legend centred with `space-sm` gaps. T5.1: New session lays out `gap: space-md` from the start. */
    contentArrangement: Arrangement.Horizontal? = null,
    /** Null: `space-lg` inline padding (0 for an icon-only key). */
    contentPadding: Dp? = null,
    /** Content after the legend, e.g. New session's `<kbd>N</kbd>` cap (`margin-left: auto`). */
    trailing: (@Composable RowScope.() -> Unit)? = null,
    /** The legend's line limit: 1 (a key's legend never wraps) unless a caller must show a long legend whole. */
    maxLines: Int = 1,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val reduced = LocalReducedMotion.current
    val haptics = rememberTetherHaptics()
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val down = pressed && enabled

    // Press-down and release are the key's two moments (see TetherHaptics for the web map).
    var wasDown by remember { mutableStateOf(false) }
    LaunchedEffect(down) {
        if (down && !wasDown) haptics.keyDown() else if (!down && wasDown) haptics.keyUp()
        wasDown = down
    }

    val state = when {
        !enabled -> KeyState.Disabled
        down -> KeyState.Pressed
        else -> KeyState.Rest
    }
    val look = resolveKey(t, classes, state, selected = selected, size = size, layout = currentLayoutClass())
    val round = shape == KeyShape.Circle || (shape == null && look.radius == KeyRadiusCircle)
    val keyShape: Shape = if (round) CircleShape else RoundedCornerShape(if (look.radius == KeyRadiusCircle) 0.dp else look.radius)

    // CSS: `transition: background var(--duration-fast), transform 90ms var(--ease-out)`; the
    // global prefers-reduced-motion rule collapses every transition to a state jump.
    val face by animateColorAsState(
        look.face,
        if (reduced) snap() else tween(t.css.durationFast, easing = t.css.easeOut.toEasing()),
        label = "keyFace",
    )

    val legend = type.keyLabel
    val baseStyle = legend.style.copy(fontSize = 13.sp)
    val textStyle = (if (fixedVerb) baseStyle else baseStyle.copy(letterSpacing = 0.sp))
        .let { if (fontSize != TextUnit.Unspecified) it.copy(fontSize = fontSize) else it }
    val shown = label?.let { if (fixedVerb) legend.format(it) else it }
    val iconOnly = label == null

    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                (contentDescription ?: label)?.let { this.contentDescription = it }
                // The visible legend in its original words (label-in-name; Voice Access matches it).
                label?.let { this.text = AnnotatedString(it) }
                if (selected) this.selected = true
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .defaultMinSize(minWidth = if (iconOnly) TetherDimens.touchTargetDp else 0.dp, minHeight = minHeight)
            .graphicsLayer {
                alpha = look.alpha
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .focusRing(focused, keyShape, t.violet)
            .cssSurface(keyShape, face, CssBorder(1.dp, look.border), look.shadows)
            .padding(horizontal = contentPadding ?: if (iconOnly) 0.dp else t.css.spaceLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = contentArrangement ?: Arrangement.spacedBy(t.css.spaceSm, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = look.ink, modifier = Modifier.size(iconSize))
        }
        if (shown != null) {
            Text(
                text = shown,
                color = look.ink,
                style = textStyle,
                maxLines = maxLines,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        trailing?.invoke(this)
    }
}
