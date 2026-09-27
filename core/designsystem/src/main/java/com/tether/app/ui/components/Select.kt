package com.tether.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.ThemeFamily

/** One row of a [TetherSelect] (components/tether-select.tsx `TetherSelectOption`, flat form). */
@Immutable
data class TetherSelectOption(
    val value: String,
    val label: String,
    val description: String? = null,
    val tag: String? = null,
    /** Warning-toned option (e.g. bypass permissions): label in `--warning`. */
    val danger: Boolean = false,
    val disabled: Boolean = false,
)

/** Where the trigger sits: a settings row (compact cap) or a dialog field (full-width cap). */
enum class SelectTriggerStyle { SettingsRow, Field }

/**
 * `tether-select`'s trigger: "Selects read as small raised keys (dropdown caps)" (globals.css
 * 8946-8957): `var(--key-face)` over `1px var(--key-side)`, `inset 0 1px 0 var(--lit-strong),
 * var(--shadow-key-sm)`, white legend and a faint 13px chevron. Settings rows are 0.72rem/600
 * on `--radius-sm` (8148-8157); dialog fields are full-width, 1rem (8162-8172). Studio
 * (studio.css:568): 44px tall, 13px, 8px radius, `var(--graphite)` face (its material is flat).
 * Touch height is at least 44dp in every skin.
 */
@Composable
fun TetherSelectTrigger(
    label: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SelectTriggerStyle = SelectTriggerStyle.SettingsRow,
    enabled: Boolean = true,
    contentDescription: String? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val studio = t.skin.family == ThemeFamily.Studio
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(if (studio) 8.dp else t.radiusSm)
    val face = if (studio) t.graphite else t.keyFace
    val fontSize = when {
        studio -> 13.sp
        style == SelectTriggerStyle.Field -> 16.sp
        else -> 11.52.sp
    }
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                role = Role.DropdownList
                this.contentDescription = contentDescription ?: label
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .graphicsLayer {
                alpha = if (enabled) 1f else DisabledOpacity
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .then(if (style == SelectTriggerStyle.Field) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = TetherDimens.touchTargetDp)
            .focusRing(focused, shape, t.violet)
            .cssSurface(
                shape, face, CssBorder(1.dp, t.keySide),
                listOf(hardShadow(1.dp, t.litStrong, inset = true)) + t.css.shadowKeySm,
            )
            .padding(horizontal = t.css.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (style == SelectTriggerStyle.Field) Arrangement.SpaceBetween else Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Text(
            label,
            style = type.body.copy(fontSize = fontSize, fontWeight = FontWeight(if (style == SelectTriggerStyle.Field && !studio) 400 else 600)),
            color = t.white,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Icon(Lucide.ChevronDown, contentDescription = null, tint = t.faint, modifier = Modifier.size(13.dp))
    }
}

/**
 * The open `tether-select` menu (globals.css 8174-8246): `var(--graphite)` panel, `1px
 * var(--key-side)` edge (material layer, 9127-9132), `--radius-md`, `--shadow-menu`
 * (`--shadow-menu-up` when it opens upward). Content-sized up to `min(28rem, 100vw - 1rem)` and
 * `min(60vh, 26rem)` tall, then it scrolls. Rows: ≥44dp, 12dp padding, a `--line` divider,
 * 0.82rem ink; the selected row's label is white/650 with a violet check (violet = selected);
 * a pressed/focused row takes `--violet-wash`; a danger row prints its label in `--warning`.
 */
@Composable
fun TetherSelectMenu(
    options: List<TetherSelectOption>,
    selectedValue: String?,
    onSelect: (TetherSelectOption) -> Unit,
    modifier: Modifier = Modifier,
    opensUp: Boolean = false,
    minWidth: Dp = 0.dp,
    /** Test/preview hook: render this row in its focused (roving selection) state. */
    focusedValue: String? = null,
) {
    val t = LocalTetherTokens.current
    val screen = LocalConfiguration.current
    val shape = RoundedCornerShape(t.radiusMd)
    val maxWidth = minOf(448.dp, screen.screenWidthDp.dp - t.css.spaceLg)
    val maxHeight = minOf(416.dp, (screen.screenHeightDp * 0.6f).dp)
    Column(
        modifier = modifier
            .semantics { role = Role.DropdownList }
            .widthIn(min = minWidth, max = maxWidth)
            .width(IntrinsicSize.Max)
            .heightIn(max = maxHeight)
            .cssSurface(shape, t.graphite, CssBorder(1.dp, t.keySide), if (opensUp) t.css.shadowMenuUp else t.css.shadowMenu)
            .padding(1.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        options.forEachIndexed { index, option ->
            SelectOptionRow(
                option = option,
                selected = option.value == selectedValue,
                last = index == options.lastIndex,
                forcedFocus = option.value == focusedValue,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun SelectOptionRow(option: TetherSelectOption, selected: Boolean, last: Boolean, forcedFocus: Boolean, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val wash = (pressed || focused || forcedFocus) && !option.disabled
    val labelColor = when {
        option.danger -> t.warning
        selected -> t.white
        else -> t.ink
    }
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                this.selected = selected
                this.contentDescription = listOfNotNull(option.label, option.tag, option.description).joinToString(", ")
            }
            .clickable(interaction, indication = null, enabled = !option.disabled, role = Role.Button, onClick = onClick)
            .graphicsLayer {
                alpha = if (option.disabled) DisabledOpacity else 1f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .then(if (wash) Modifier.background(t.violetWash) else Modifier),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = TetherDimens.touchTargetDp)
                .padding(t.css.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd),
        ) {
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(3.2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        option.label,
                        style = type.body.copy(fontSize = 13.12.sp, lineHeight = 1.35.em, fontWeight = FontWeight(if (selected) 650 else 400)),
                        color = labelColor,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    if (option.tag != null) {
                        Text(
                            option.tag,
                            style = type.body.copy(fontSize = 10.4.sp, fontWeight = FontWeight(500), lineHeight = 1.5.em),
                            color = t.muted,
                            modifier = Modifier
                                .padding(start = 5.2.dp)
                                // `background: var(--surface-1)` names no defined token, so it computes to transparent.
                                .cssSurface(RoundedCornerShape(3.dp), border = CssBorder(1.dp, t.line))
                                .padding(horizontal = 4.16.dp, vertical = 0.52.dp)
                                .clearAndSetSemantics { },
                        )
                    }
                }
                if (option.description != null) {
                    Text(
                        option.description,
                        style = type.body.copy(fontSize = 11.2.sp, lineHeight = 1.45.em),
                        color = t.muted,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
            if (selected) Icon(Lucide.Check, contentDescription = null, tint = t.violet, modifier = Modifier.size(14.dp))
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}

/**
 * The whole control: trigger + a popup menu placed below the trigger (4dp gap), or above when
 * there is more room there (`useMenuPlacement`'s flip; `is-up` then swaps to `--shadow-menu-up`).
 */
@Composable
fun TetherSelect(
    options: List<TetherSelectOption>,
    selectedValue: String?,
    onSelect: (TetherSelectOption) -> Unit,
    modifier: Modifier = Modifier,
    style: SelectTriggerStyle = SelectTriggerStyle.SettingsRow,
    placeholder: String = "",
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var opensUp by remember { mutableStateOf(false) }
    val t = LocalTetherTokens.current
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { t.css.spaceXs.roundToPx() }
    val current = options.firstOrNull { it.value == selectedValue }
    Box(modifier) {
        TetherSelectTrigger(
            label = current?.label ?: placeholder,
            expanded = expanded,
            onClick = { expanded = !expanded },
            style = style,
            enabled = enabled,
            contentDescription = contentDescription,
        )
        if (expanded) {
            val provider = remember(gap) {
                object : PopupPositionProvider {
                    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                        val below = windowSize.height - anchorBounds.bottom
                        val up = below < popupContentSize.height + gap && anchorBounds.top > below
                        opensUp = up
                        val y = if (up) anchorBounds.top - gap - popupContentSize.height else anchorBounds.bottom + gap
                        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
                        return IntOffset(x, y)
                    }
                }
            }
            Popup(popupPositionProvider = provider, onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
                TetherSelectMenu(
                    options = options,
                    selectedValue = selectedValue,
                    onSelect = {
                        expanded = false
                        onSelect(it)
                    },
                    opensUp = opensUp,
                )
            }
        }
    }
}
