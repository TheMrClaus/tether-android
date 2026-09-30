package com.tether.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherDimens

/**
 * A composer chip (`.draft-chip` / `.model-selector-chip`, globals.css 9724-9748): a raised pill
 * on the panel — `1px var(--line)`, `var(--graphite-raised)`, `--edge-highlight`, white legend
 * 0.72rem/600, 1.8rem tall. [active] (its panel is open) is the violet border — violet marks the
 * control whose panel is up. Studio (studio.css:394) drops the border and shadow and sits the
 * chip at 2.25rem.
 *
 * The visual pill keeps the web's height; the touch target around it is at least 44×44dp
 * (DESIGN.md) — the extra is transparent layout, centred on the pill.
 */
@Composable
fun TetherChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailingIcon: ImageVector? = null,
    contentDescription: String? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(percent = 50)
    val border = when {
        active -> CssBorder(1.dp, t.violet)
        else -> null
    }
    val shadows = emptyList<CssShadow>()
    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription ?: label
                if (active) {
                    selected = true
                    stateDescription = "Open"
                }
            }
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minWidth = TetherDimens.touchTargetDp, minHeight = TetherDimens.touchTargetDp)
            // `.draft-chip` is a <button>: `button:disabled { opacity: 0.48 }` (globals.css 641-647)
            // dims the whole chip as one group. Every chip shadow is inset, so an offscreen layer
            // clips nothing; a disabled chip never shows the focus ring.
            .then(
                if (enabled) Modifier else Modifier.graphicsLayer {
                    alpha = DisabledOpacity
                    compositingStrategy = CompositingStrategy.Offscreen
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .then(Modifier.heightIn(min = 36.dp))
                .focusRing(focused, shape, t.violet)
                .cssSurface(shape, t.graphiteRaised, border, shadows)
                .padding(horizontal = t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.4.dp),
        ) {
            leading?.invoke(this)
            Text(
                label,
                style = type.body.copy(fontSize = 11.52.sp, fontWeight = FontWeight(600)),
                color = t.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { },
            )
            if (trailingIcon != null) Icon(trailingIcon, contentDescription = null, tint = t.faint, modifier = Modifier.size(13.dp))
        }
    }
}
