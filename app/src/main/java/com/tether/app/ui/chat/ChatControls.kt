package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.X
import com.tether.app.protocol.SessionCommandOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights

/**
 * The composer mode row (visual-spec §4, Claude only): ShieldCheck + "Mode" +
 * the permission-mode pill (drop-up) + the model chip. Mirrors the web
 * chat-mode-row; the inline hint lives in the menu rows on the phone.
 *
 * The mode pill and model chip share the web's `.chat-mode-select` /
 * `.chat-model-chip` raised-pill vocabulary (globals.css §4344 + §5198):
 * graphite-raised face, 1px line border, lit top bevel (--edge-highlight),
 * 999px pill radius. The mode pill's danger variant swaps border + text to
 * amber/warning (.chat-mode-select.is-danger); the model chip hovers to
 * violet (`.chat-model-chip:hover`).
 */
@Composable
fun ChatModeRow(
    permissionMode: String?,
    modelLabel: String,
    onSetMode: (String) -> Unit,
    onModelClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val current = permissionModeOption(permissionMode)
    var modeMenuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Lucide.ShieldCheck, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
        Text("Mode", color = t.muted, fontFamily = Manrope, fontWeight = TetherWeights.label, fontSize = 12.5.sp)
        Box {
            RaisedPill(
                onClick = { modeMenuOpen = true },
                danger = current.danger,
                modifier = Modifier.height(29.dp),
            ) {
                Text(
                    current.label,
                    color = if (current.danger) t.warning else t.ink,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.label,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(
                expanded = modeMenuOpen,
                onDismissRequest = { modeMenuOpen = false },
                modifier = Modifier.background(t.graphite).border(1.dp, t.line, RoundedCornerShape(TetherDimens.radiusMd)),
            ) {
                PERMISSION_MODE_OPTIONS.forEach { option ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                modeMenuOpen = false
                                onSetMode(option.value)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .heightIn(min = TetherDimens.touchTargetDp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                option.label,
                                color = if (option.danger) t.danger else t.white,
                                fontFamily = Manrope,
                                fontWeight = TetherWeights.label,
                                fontSize = 13.6.sp,
                            )
                            if (option.value == current.value) {
                                Icon(Lucide.Check, contentDescription = null, tint = t.violet, modifier = Modifier.size(13.dp))
                            }
                        }
                        Text(
                            option.hint,
                            color = if (option.danger) t.danger else t.muted,
                            fontFamily = Manrope,
                            fontWeight = TetherWeights.body,
                            fontSize = 12.2.sp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Box {
            val modelHover = remember { MutableInteractionSource() }
            RaisedPill(
                onClick = onModelClick,
                interactionSource = modelHover,
                modifier = Modifier.height(29.dp),
            ) {
                Icon(Lucide.Cpu, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
                Text(
                    modelLabel,
                    color = t.ink,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.label,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The shared raised-pill cap (web `.chat-mode-select` / `.chat-model-chip`):
 * graphite-raised face, 1px line border, lit top bevel, 999px pill radius,
 * soft contact shadow. Press travels 1dp; hover deepens the face. The [danger]
 * variant swaps the border to amber + text to warning (mode pill only).
 */
@Composable
private fun RaisedPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    val t = LocalTetherTokens.current
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(999.dp)
    val face = if (hovered && !pressed) t.graphiteRaised.copy(alpha = 0.92f) else t.graphiteRaised
    val borderColor = if (danger) t.amber else t.line
    val density = LocalDensity.current
    val radiusPx = with(density) { 999.dp.toPx() }
    val down = pressed

    Row(
        modifier = modifier
            .shadow(
                elevation = if (!down) 2.dp else 1.dp,
                shape = shape,
                clip = false,
                ambientColor = t.contact.copy(alpha = 0.35f),
                spotColor = t.contact.copy(alpha = 0.4f),
            )
            .background(face, shape)
            .drawBehind {
                // Lit top bevel (--edge-highlight "inset 0 1px 0 lit-strong").
                drawRoundRect(
                    color = t.litStrong,
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, 1.dp.toPx()),
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                )
            }
            .border(1.dp, borderColor, shape)
            .offset(y = if (down) 1.dp else 0.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/**
 * The model picker drop-up panel (web chat-model-picker): header, empty state,
 * one row per picker model with a Check on the active one.
 */
@Composable
fun ModelPickerPanel(
    pickerModels: List<SessionModelOption>,
    sessionModel: String?,
    onChoose: (SessionModelOption) -> Unit,
    onClose: () -> Unit,
) {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(t.graphite, RoundedCornerShape(TetherDimens.radiusMd))
            .border(1.dp, t.line, RoundedCornerShape(TetherDimens.radiusMd))
            .padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Cpu, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
            Spacer(Modifier.size(6.dp))
            Text("Model", color = t.white, fontFamily = Manrope, fontWeight = TetherWeights.label, fontSize = 13.1.sp)
            Spacer(Modifier.weight(1f))
            Icon(
                Lucide.X,
                contentDescription = "Close",
                tint = t.muted,
                modifier = Modifier
                    .size(TetherDimens.touchTargetDp)
                    .clickable(onClick = onClose)
                    .padding(14.dp),
            )
        }
        if (pickerModels.isEmpty()) {
            Text(
                "Send a message first to load the available models.",
                color = t.muted,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 12.8.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            pickerModels.forEach { model ->
                val isActive = model.value == (sessionModel ?: "") || (sessionModel.isNullOrEmpty() && model.current == true)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onChoose(model) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .heightIn(min = TetherDimens.touchTargetDp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            model.displayName,
                            color = if (isActive) t.white else t.ink,
                            fontFamily = Manrope,
                            fontWeight = if (isActive) TetherWeights.label else TetherWeights.body,
                            fontSize = 13.6.sp,
                        )
                        if (isActive) Icon(Lucide.Check, contentDescription = null, tint = t.violet, modifier = Modifier.size(13.dp))
                    }
                    model.description?.let {
                        Text(it, color = t.muted, fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = 12.2.sp)
                    }
                }
            }
        }
    }
}

/** The slash-command autocomplete drop-up (web chat-slash-menu). */
@Composable
fun SlashCommandMenu(
    matches: List<SessionCommandOption>,
    onAccept: (SessionCommandOption) -> Unit,
) {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .background(t.graphite, RoundedCornerShape(TetherDimens.radiusMd))
            .border(1.dp, t.line, RoundedCornerShape(TetherDimens.radiusMd))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        matches.forEach { command ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onAccept(command) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .heightIn(min = TetherDimens.touchTargetDp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "/${command.name}",
                            color = t.white,
                            fontFamily = JetBrainsMono,
                            fontWeight = TetherWeights.label,
                            fontSize = 13.1.sp,
                        )
                        command.argumentHint?.let {
                            Text(" $it", color = t.faint, fontFamily = JetBrainsMono, fontSize = 12.2.sp)
                        }
                    }
                    if (!command.description.isNullOrEmpty()) {
                        Text(
                            command.description,
                            color = t.muted,
                            fontFamily = Manrope,
                            fontWeight = TetherWeights.body,
                            fontSize = 12.2.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (command.supported) {
                    Text("Tether", color = t.violet, fontFamily = Manrope, fontWeight = TetherWeights.strong, fontSize = 10.6.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Lucide.Terminal, contentDescription = null, tint = t.faint, modifier = Modifier.size(11.dp))
                        Text("terminal only", color = t.faint, fontFamily = Manrope, fontWeight = TetherWeights.label, fontSize = 10.6.sp)
                    }
                }
            }
        }
    }
}

/** The in-composer flash notice (web chat-notice): Terminal icon + text. */
@Composable
fun ComposerNotice(message: String) {
    val t = LocalTetherTokens.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Lucide.Terminal, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
        Text(message, color = t.ink, fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = 12.8.sp)
    }
}
