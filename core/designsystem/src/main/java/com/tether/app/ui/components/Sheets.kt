package com.tether.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/** The web's phone breakpoint: `@media (max-width: 47.9375rem)` — below 48rem a sheet docks. */
val SheetDockBelow = 768.dp

/**
 * The action sheet (`.attach-sheet`, globals.css 7161-7240 + phone rules 8518-8545): on a phone
 * a true bottom sheet — edge to edge, `--radius-lg` top corners only, a `1px var(--line-strong)`
 * top edge, a 2.25rem × 0.25rem `--line-strong` drag handle, `max-height: 80dvh`; from 48rem a
 * centred `min(22rem, 100vw - 1.5rem)` card with every edge. Both: `var(--graphite)`,
 * `--edge-highlight` + `--shadow-modal`, header 1.05rem/650 white over a `--line` rule. Studio
 * restyles no part of this surface beyond its tokens. This is the inline surface; [TetherSheet]
 * hosts it in a window.
 */
@Composable
fun TetherSheetSurface(
    title: String,
    modifier: Modifier = Modifier,
    docked: Boolean = LocalConfiguration.current.screenWidthDp.dp < SheetDockBelow,
    onClose: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val r = t.radiusLg
    val shape = if (docked) RoundedCornerShape(topStart = r, topEnd = r) else RoundedCornerShape(r)
    val shadows: List<CssShadow> = t.css.edgeHighlight + t.css.shadowModal
    BoxWithConstraints(modifier) {
        val boxWidth = if (docked) maxWidth else minOf(352.dp, maxWidth - 24.dp)
        Column(
            Modifier
                .width(boxWidth)
                .heightIn(max = if (docked) maxHeight * 0.8f else maxHeight - 24.dp)
                // `border-width: 1px 0 0 0` when docked (drawn as the equivalent 1px inset top
                // line, which follows the rounded top corners); every edge as a card.
                .cssSurface(
                    shape, t.graphite,
                    if (docked) null else CssBorder(1.dp, t.lineStrong),
                    if (docked) shadows + hardShadow(1.dp, t.lineStrong, inset = true) else shadows,
                )
                .then(if (docked) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier),
        ) {
            if (docked) {
                Box(
                    Modifier
                        .padding(top = t.css.spaceSm)
                        .align(Alignment.CenterHorizontally)
                        .size(width = 36.dp, height = 4.dp)
                        .background(t.lineStrong, RoundedCornerShape(percent = 50))
                        .clearAndSetSemantics { },
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = t.css.spaceLg, vertical = if (docked) t.css.spaceMd else t.css.spaceLg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            ) {
                Text(
                    title,
                    color = t.white,
                    style = type.body.copy(fontSize = 16.8.sp, fontWeight = FontWeight(650), letterSpacing = (-0.02).em),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (onClose != null) {
                    TetherKey(onClick = onClose, variant = KeyVariant.Quiet, icon = TetherIcons.X, iconSize = 16.dp, contentDescription = "Close")
                }
            }
            PerfDivider()
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(t.css.spaceSm),
                content = content,
            )
        }
    }
}

/**
 * One sheet row (`.attach-sheet-row`, globals.css 7207-7233): ≥44px, `--radius-md`, a muted
 * leading glyph, 0.86rem ink; pressed = `--graphite-raised`; keyboard focus adds the
 * `inset 0 0 0 1px var(--violet-strong)` ring (violet = focus).
 */
@Composable
fun TetherSheetRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(t.radiusMd)
    val lit = (pressed || focused) && enabled
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = label }
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .cssSurface(
                shape,
                if (lit) t.graphiteRaised else Color.Transparent,
                shadows = if (focused) listOf(CssShadow(true, 0.dp, 0.dp, 0.dp, 1.dp, t.violetStrong)) else emptyList(),
            )
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = t.muted, modifier = Modifier.size(16.dp))
        Text(label, color = t.ink, style = type.body.copy(fontSize = 13.76.sp), modifier = Modifier.clearAndSetSemantics { })
    }
}

/**
 * A modal action sheet: docks to the bottom on a phone (sliding up, `attach-sheet-up`), a centred
 * card from 48rem (`dialog-in`), over the skin's scrim. Reduced motion: no slide.
 */
@Composable
fun TetherSheet(
    onDismiss: () -> Unit,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalTetherTokens.current
    val docked = LocalConfiguration.current.screenWidthDp.dp < SheetDockBelow
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        NoWindowDim()
        ModalScrim(onDismiss, dialogScrim(t), if (docked) Alignment.BottomCenter else Alignment.Center) {
            val progress = rememberEnterProgress()
            TetherSheetSurface(
                title = title,
                docked = docked,
                onClose = onDismiss,
                content = content,
                modifier = Modifier
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .graphicsLayer {
                        val p = progress.value
                        if (docked) {
                            translationY = (1f - p) * size.height
                        } else {
                            alpha = p
                            translationY = (1f - p) * 8.dp.toPx()
                        }
                    },
            )
        }
    }
}
