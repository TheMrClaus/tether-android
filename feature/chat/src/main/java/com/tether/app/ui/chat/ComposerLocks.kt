package com.tether.app.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.tether.app.ui.components.focusRing
import com.tether.app.ui.components.SpinnerRing
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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

/** #273 (U1-spec §4): which of the one notice's three states is drawn. */
internal enum class HandoffVariant(val tag: String) {
    Pending("handoff-notice-pending"),
    To("handoff-notice-to"),
    From("handoff-notice-from"),
}

internal const val HANDOFF_PENDING_LINE = "Handover under way — a new session takes over when this session finishes."
internal const val HANDOFF_PENDING_SUB = "Stop cancels it · new messages go to the new session."

internal const val HANDOFF_STAGE_LINE_TAG = "handoff-stage-line"
internal const val HANDOFF_STAGE_SUB_TAG = "handoff-stage-sub"

/** The notice's one sentence for [variant] and [peer], the words a screen reader reads (the link's name included). */
internal fun handoffNoticeText(variant: HandoffVariant, peer: AgentSession?, change: HandoverChangeView? = null): String {
    val line = when (variant) {
        HandoffVariant.Pending -> return "$HANDOFF_PENDING_LINE $HANDOFF_PENDING_SUB"
        HandoffVariant.To -> peer?.let { "Handed off to ${LabelText.label(it.name).ifEmpty { "another session" }}" } ?: "Handed off to another session"
        HandoffVariant.From -> peer?.let { "Continued from ${LabelText.label(it.name).ifEmpty { "an earlier session" }}" } ?: "Continued from an earlier session"
    }
    // A change reads as sentences after the first line (the web's hidden ". " after line 1).
    return if (change == null) line else "$line. ${change.spoken}"
}

/**
 * `components/handoff-notice.tsx` (web fe4aa8c0): the ONE notice for every handoff state, replacing the old
 * pre-#273 lock row. `to` takes the source's composer slot ("Handed off to <name> →"), `from` is the
 * successor's first transcript item ("Continued from <name>"), `pending` sits above the composer while a handover is
 * requested ("Handover under way", two lines, no link). The link only opens that session; it sends nothing. An
 * unlisted peer reads as words only. [linkFocus] lets the host move focus to the link when the composer under the
 * keyboard went away.
 */
@Composable
internal fun HandoffNotice(
    variant: HandoffVariant,
    peer: AgentSession?,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
    linkFocus: FocusRequester? = null,
    /** W34 (server protocol >= 151): the Pending notice's stage line (null = the W33 two lines, as shipped). */
    stage: com.tether.app.client.Handover.StageView? = null,
    /** #301 (To / From only): what the handover changed on the successor's model / effort and the agent's reason; null draws today's banner. */
    change: HandoverChangeView? = null,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.82f))
    val linked = variant != HandoffVariant.Pending && peer != null
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(variant.tag),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        val stopping = variant == HandoffVariant.Pending && stage?.key == com.tether.app.client.Handover.StageKey.Stopping
        if (stopping) {
            // gc:7631: the spinner takes the glyph's place, about 2dp higher.
            Box(Modifier.padding(top = 1.dp).size(14.dp), contentAlignment = Alignment.Center) { SpinnerRing(t.muted, 9.6.dp) }
        } else Icon(
            TetherIcons.ArrowRightLeft,
            contentDescription = null,
            tint = t.muted,
            modifier = Modifier.padding(top = if (linked) 17.dp else 3.dp).size(14.dp),
        )
        if (variant == HandoffVariant.Pending && stage != null) {
            // gc:7630: the stage line is ink, 650, never violet and never muted; the sub line is muted.
            Column {
                Text(stage.line, style = style.copy(fontWeight = FontWeight(650)), color = t.ink, modifier = Modifier.testTag(HANDOFF_STAGE_LINE_TAG))
                if (!stopping) Text(com.tether.app.client.Handover.STRIP_SUB, style = style.copy(fontSize = rem(0.75f)), color = t.muted, modifier = Modifier.testTag(HANDOFF_STAGE_SUB_TAG))
            }
        } else if (variant == HandoffVariant.Pending) {
            Column {
                Text(HANDOFF_PENDING_LINE, style = style, color = t.muted)
                Text(HANDOFF_PENDING_SUB, style = style.copy(fontSize = rem(0.75f)), color = t.muted)
            }
        } else {
            val lead = if (variant == HandoffVariant.To) "Handed off to" else "Continued from"
            Column(Modifier.weight(1f, fill = false)) {
                if (peer == null) {
                    Text(handoffNoticeText(variant, null), style = style, color = t.muted)
                } else {
                    val name = LabelText.label(peer.name).ifEmpty { if (variant == HandoffVariant.To) "another session" else "an earlier session" }
                    val interaction = remember { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    val linkShape = RoundedCornerShape(t.radiusSm)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$lead ", style = style, color = t.muted)
                        Row(
                            Modifier
                                .heightIn(min = 48.dp)
                                .then(if (linkFocus != null) Modifier.focusRequester(linkFocus) else Modifier)
                                .focusRing(focused, linkShape, t.violet)
                                .clickable(interaction, indication = null, role = Role.Button, onClickLabel = "Open $name") { onOpenSession(peer.id) }
                                .padding(horizontal = t.css.spaceXs)
                                .testTag("handoff-link"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(name, style = style.copy(fontWeight = FontWeight(650), textDecoration = TextDecoration.Underline), color = t.white, modifier = Modifier.weight(1f, fill = false))
                            if (variant == HandoffVariant.To) Icon(TetherIcons.ArrowRight, contentDescription = null, tint = t.white, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                if (change != null) HandoverChangeBlock(change)
            }
        }
    }
}

internal const val HANDOFF_CHANGE_TAG = "handoff-change"

/**
 * `.handoff-notice-change` + `.handoff-notice-reason` (web, #301): one item per changed setting, model first ("Model A -> B"),
 * then the agent's own words ("Reason: "..."") in full (no clamp). Label and arrow muted, values ink at weight 650; the block sits
 * below the peer link's 48dp target (no negative margin). Read as one node: "Model A to B. Effort C to D. Reason: ...".
 */
@Composable
private fun HandoverChangeBlock(change: HandoverChangeView) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val small = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.75f), lineHeight = rem(0.75f * 1.45f))
    Column(
        Modifier
            .semantics(mergeDescendants = true) { contentDescription = change.spoken }
            .testTag(HANDOFF_CHANGE_TAG),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            change.items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${item.label} ", style = small, color = t.muted)
                    Text(item.from, style = small.copy(fontWeight = FontWeight(650)), color = t.ink)
                    Icon(TetherIcons.ArrowRight, contentDescription = null, tint = t.muted, modifier = Modifier.padding(horizontal = 4.dp).size(12.dp))
                    Text(item.to, style = small.copy(fontWeight = FontWeight(650)), color = t.ink)
                }
            }
        }
        change.reason?.let { reason ->
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = t.muted)) { append("Reason: \u201C") }
                    withStyle(androidx.compose.ui.text.SpanStyle(color = t.ink)) { append(reason) }
                    withStyle(androidx.compose.ui.text.SpanStyle(color = t.muted)) { append("\u201D") }
                },
                style = small,
            )
        }
    }
}
