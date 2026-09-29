package com.tether.app.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.client.LabelText
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTypography

/*
 * T6.6: the composer states of a session Tether cannot drive (chat-view.tsx 3718-3723, 3814-3833;
 * globals.css 6462-6469 .chat-waiting, 6768-6781 .chat-command-flag, 8091-8111 .chat-handoff-lock).
 * Every state is said in words; the colour only reinforces it.
 */

private fun rem(r: Float): TextUnit = (r * TetherTypography.SP_PER_REM).sp

/** `.chat-waiting` (`role="status"`, `--warning`, 620): why a read-only session takes no input. */
@Composable
internal fun ReadOnlyRow(modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("composer-read-only"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.warning, modifier = Modifier.size(14.dp))
        Text(
            "Read-only — Tether isn’t driving this conversation: an imported replay, or a Codex thread another run is writing.",
            style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.8f), fontWeight = FontWeight(620)),
            color = t.warning,
        )
    }
}

/** `.chat-command-flag` in place of the composer: "Replay only — configure the <provider> engine …". */
@Composable
internal fun ReplayOnlyFlag(provider: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    Text(
        "Replay only — configure the ${LabelText.label(provider)} engine to continue this conversation.",
        style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.72f), fontWeight = FontWeight(600)),
        color = t.danger,
        modifier = modifier
            .fillMaxWidth()
            .cssSurface(shape, background = t.dangerWash)
            .border(1.dp, t.dangerEdge, shape)
            .padding(horizontal = t.css.spaceSm, vertical = t.css.spaceXs)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("composer-replay-only"),
    )
}

/**
 * `.chat-handoff-lock` in place of the composer: ArrowRightLeft, "Continued in →" and the target —
 * a link (violet, underlined, ≥44dp) when it is still listed, else its name or "another session".
 * The link only opens that session; it sends nothing to the agent.
 */
@Composable
internal fun HandoffLockRow(target: AgentSession?, onOpenSession: (String) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.82f))
    val name = target?.let { LabelText.label(it.name).ifEmpty { "another session" } }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("composer-handoff-lock"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Icon(TetherIcons.ArrowRightLeft, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
        Text("Continued in →", style = style, color = t.muted)
        if (target != null) {
            Text(
                name!!,
                style = style.copy(fontWeight = FontWeight(650), textDecoration = TextDecoration.Underline),
                color = t.violet,
                maxLines = 1,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open $name") { onOpenSession(target.id) }
                    .padding(vertical = 12.dp)
                    .testTag("handoff-link"),
            )
        } else {
            Text("another session", style = style.copy(fontWeight = FontWeight(650)), color = t.ink)
        }
    }
}
