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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.SessionCommandOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights

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
                            command.description!!,
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
                        Icon(TetherIcons.Terminal, contentDescription = null, tint = t.faint, modifier = Modifier.size(11.dp))
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
        Icon(TetherIcons.Terminal, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
        Text(message, color = t.ink, fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = 12.8.sp)
    }
}
