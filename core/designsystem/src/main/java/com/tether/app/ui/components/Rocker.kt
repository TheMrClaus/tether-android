package com.tether.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherTokens

/**
 * Geometry of the settings rocker in one skin (globals.css 8861-8921, Studio studio.css 570-586).
 * Offsets are inside the frame's border (the positioned children's containing block).
 */
data class RockerGeometry(
    val width: Dp,
    val height: Dp,
    val border: Dp,
    val radius: Dp,
    val capTop: Dp,
    val capWidth: Dp,
    val capHeight: Dp,
    val capRadius: Dp,
    /** Cap `left` when OFF and when ON (after its translateX). */
    val capLeftOff: Dp,
    val capLeftOn: Dp,
)

fun rockerGeometry(t: TetherTokens): RockerGeometry = // 40×24, no border, the :root 0.5rem radius survives; an 18px cap at 3px, +16px when on.
        RockerGeometry(40.dp, 24.dp, 0.dp, 8.dp, 3.dp, 18.dp, 18.dp, 5.44.dp, 3.dp, 19.dp)

/**
 * The bi-stable settings rocker (`.settings-toggle > i`): a recessed frame (`--key-face-deep`,
 * `--well`, 1px `--line-strong`, `--accent-side` when on); the SELECTED half pressed into it with
 * its etched legend on the well floor (OFF muted / ON `--accent` with `--accent-ink`); the other
 * half a raised `--key-face` cap that slides across in `--rocker-ms` `--ease-out` (a state jump
 * under reduced motion). Studio re-dresses it as a 40×24 track (`--line-strong`, violet-strong
 * when on) with a white 18px cap; the globals legend layer still sits under the cap, exactly as
 * the browser paints it.
 *
 * The visual stays the web's size; the touch target around it is at least 44×44dp.
 */
@Composable
fun TetherRocker(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val reduced = LocalReducedMotion.current
    val g = rockerGeometry(t)
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val capLeft by animateFloatAsState(
        if (checked) g.capLeftOn.value else g.capLeftOff.value,
        if (reduced) snap() else tween(t.css.rockerMs, easing = t.css.easeOut.toEasing()),
        label = "rockerCap",
    )
    val frameShape = RoundedCornerShape(g.radius)
    val frame = Modifier.cssSurface(frameShape, if (checked) t.violetStrong else t.lineStrong)
    // The rocker is an `<i>` element, so its legend inherits the UA `font-style: italic`; the
    // bundled mono face has no italic, so both Chromium and Compose synthesize the oblique.
    val legend = TextStyle(
        fontFamily = type.mono, fontSize = 8.32.sp, fontWeight = FontWeight(750), fontStyle = FontStyle.Italic, letterSpacing = 0.08.em,
    )
    val capShadows: List<CssShadow> = listOf(softShadow(1.dp, 3.dp, Color(16, 30, 58).copy(alpha = 0.14f)))
    Box(
        modifier
            .semantics { contentDescription?.let { this.contentDescription = it } }
            .toggleable(checked, interaction, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .defaultMinSize(TetherDimens.touchTargetDp, TetherDimens.touchTargetDp)
            // `opacity: 0.48` composites the rocker as ONE group (the cap must not show the legend
            // through itself), so a disabled rocker draws offscreen. The layer is the ≥44dp touch
            // box, which holds the well's 1px outer lip; a disabled rocker never shows focus.
            .then(
                if (enabled) Modifier else Modifier.graphicsLayer {
                    alpha = DisabledOpacity
                    compositingStrategy = CompositingStrategy.Offscreen
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(g.width, g.height)
                .focusRing(focused, frameShape, t.violet)
                .then(frame),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(g.border)
                    .clip(RoundedCornerShape((g.radius - g.border).coerceAtLeast(0.dp))),
            ) {
                val half = (g.width - g.border * 2) / 2
                // ::before: the depressed (selected) half and its legend.
                Box(
                    Modifier
                        .offset(x = if (checked) half else 0.dp)
                        .size(half, g.height - g.border * 2)
                        .cssSurface(
                            RoundedCornerShape(0.dp),
                            if (checked) t.accent else Color.Transparent,
                            shadows = if (checked) {
                                listOf(softShadow(2.dp, 3.dp, t.pressShade, inset = true), hardShadow((-1).dp, t.litFaint, inset = true))
                            } else {
                                listOf(softShadow(2.dp, 3.dp, t.contact.copy(alpha = 0.2f), inset = true))
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (checked) "ON" else "OFF",
                        style = legend,
                        color = if (checked) t.accentInk else t.muted,
                        maxLines = 1,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
                // b: the raised cap.
                Box(
                    Modifier
                        .offset(x = capLeft.dp, y = g.capTop)
                        .size(g.capWidth, g.capHeight)
                        .cssSurface(RoundedCornerShape(g.capRadius), Color.White, shadows = capShadows),
                )
            }
        }
    }
}
