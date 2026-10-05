package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.HandoffBriefReading
import com.tether.app.client.LabelText
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.ProviderTile
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTypography
import com.tether.app.ui.util.providerGlyph
import com.tether.app.ui.util.relativeTime
import com.tether.app.ui.util.statusCopy

/*
 * T8.5 (slice c): the composer's takeover from the `@` picker (tether components/chat-view.tsx 90fbb9f):
 * the "Sessions on this project" section (:2786-2802, :3972-4011), beginHandoff (:2834-2851), the
 * editable takeover draft (:2905-2924, :4014-4078) and commitHandoff / cancelHandoff (:2925-2950).
 * CSS: globals.css 7204-7282 (`.chat-mention-*`), 7346-7465 (`.chat-handoff-draft*`).
 */

private fun rem(r: Float): TextUnit = (r * TetherTypography.SP_PER_REM).sp

/**
 * What the takeover may do for one session: the live roster (the `@` picker's Sessions), the briefs
 * the server derived, and the three wire actions (use-tether.ts 90fbb9f :1889-1913). [onRequestBrief]
 * and [onHandoff] are taps only. [now] is the clock the rows' relative time reads.
 */
@Immutable
class ComposerTakeover(
    val sessions: List<AgentSession>,
    val briefs: Map<String, HandoffBriefReading>,
    val onRequestBrief: (sourceId: String) -> Boolean,
    val onHandoff: (sourceId: String, engineText: String) -> Boolean,
    val onClearBrief: (sourceId: String) -> Unit,
    val now: () -> Long = System::currentTimeMillis,
)

internal object TakeoverTags {
    const val Draft = "composer-handoff-draft"
    const val Editor = "composer-handoff-editor"
    const val Loading = "composer-handoff-loading"
    const val Commit = "composer-handoff-commit"
    const val Cancel = "composer-handoff-cancel"
    const val CancelX = "composer-handoff-cancel-x"
    const val SummaryToggle = "composer-handoff-summary-toggle"
    const val Summary = "composer-handoff-summary"

    fun session(id: String) = "mention-session-$id"
}

/**
 * chat-view.tsx 90fbb9f :2791-2802 handoffCandidates: sessions on THIS project (same source `cwd`),
 * excluding [self], handed-off sources and archived rows, matching [query] by name or provider,
 * newest message first. Running / waiting rows stay listed (drawn locked).
 */
internal fun handoffCandidates(sessions: List<AgentSession>, self: AgentSession, query: String): List<AgentSession> {
    val q = query.lowercase()
    return sessions
        .filter {
            it.id != self.id && it.cwd == self.cwd && it.handedOffTo.isNullOrEmpty() && !it.runtimeArchived &&
                (q.isEmpty() || it.name.lowercase().contains(q) || it.provider.lowercase().contains(q))
        }
        .sortedByDescending { it.lastMessageAt ?: 0L }
}

/** chat-view.tsx :2838-2844: why a source cannot be taken over now (null: it can). */
internal fun handoffRefusal(source: AgentSession): String? = when (source.status) {
    "active" -> "“${LabelText.label(source.name)}” is running — interrupt it first, then take over."
    "waiting" -> "“${LabelText.label(source.name)}” is waiting on a prompt — resolve it first, then take over."
    else -> null
}

/** chat-view.tsx :2939. */
internal const val HANDOFF_EMPTY_COPY = "The brief is empty — add a note before taking over."

/** chat-view.tsx :2948. */
internal const val HANDOFF_NOT_CONNECTED_COPY = "Not connected — reconnecting. Try the takeover again in a moment."

/** `.chat-mention-section-label` (globals.css 7219-7226). */
@Composable
internal fun MentionSectionLabel(text: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text.uppercase(),
        style = type.body.copy(fontSize = rem(0.66f), fontWeight = FontWeight(650), letterSpacing = 0.04.em),
        color = t.faint,
        modifier = Modifier.padding(start = t.css.spaceMd, end = t.css.spaceMd, top = t.css.spaceSm, bottom = t.css.spaceXs).clearAndSetSemantics { contentDescription = text },
    )
}

/**
 * One "Sessions on this project" row (chat-view.tsx :3975-4008): the provider mark, the name, the
 * model, the status in words and the relative time, and the tag — "take over", or (locked, at 0.6)
 * "interrupt first" / "resolve first". A locked row does nothing on a tap.
 */
@Composable
internal fun MentionSessionRow(candidate: AgentSession, active: Boolean, now: Long, onPick: (AgentSession) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val running = candidate.status == "active"
    val waiting = candidate.status == "waiting"
    val locked = running || waiting
    val name = LabelText.label(candidate.name).ifEmpty { LabelText.visibleValue(candidate.name) }
    val model = candidate.model?.let { LabelText.label(it) }?.takeIf { it.isNotEmpty() }
    val status = statusCopy(candidate.status)
    val time = relativeTime(candidate.lastMessageAt ?: 0L, now)
    val tag = if (running) "interrupt first" else if (waiting) "resolve first" else "take over"
    val title = if (running) "Running — interrupt it first" else if (waiting) "Waiting on a prompt — resolve it first" else "Take over “$name”"
    Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (active) Modifier.background(t.violetWash) else Modifier)
            .clickable(enabled = !locked, role = Role.Button, onClickLabel = title) { onPick(candidate) }
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = listOfNotNull(name, model, status, time, tag).joinToString(", ")
                stateDescription = title
                if (locked) disabled() else onClick(title) { onPick(candidate); true }
                testTag = TakeoverTags.session(candidate.id)
            }
            .heightIn(min = 44.dp)
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm)
            .alpha(if (locked) 0.6f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        ProviderTile(candidate.provider, Modifier.size(20.dp), fallback = providerGlyph(candidate.provider), color = t.muted, markSize = 16.dp, letterSize = 11.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = type.body.copy(fontSize = rem(0.82f), fontWeight = FontWeight(650)), color = t.white, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
                val meta = type.body.copy(fontSize = rem(0.72f))
                if (model != null) Text(model, style = meta, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(status, style = meta, color = t.muted, maxLines = 1)
                Text("· $time", style = meta, color = t.faint, maxLines = 1)
            }
        }
        MentionTag(tag, locked)
    }
}

/** `.chat-mention-tag` (globals.css 7271-7282); `.is-locked` on `--mineral` in `--faint`. */
@Composable
private fun MentionTag(text: String, locked: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text,
        style = type.body.copy(fontSize = rem(0.66f), fontWeight = FontWeight(650)),
        color = if (locked) t.faint else t.violet,
        maxLines = 1,
        modifier = Modifier.background(if (locked) t.mineral else t.violetWash, RoundedCornerShape(t.radiusSm)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * `.chat-handoff-draft` (chat-view.tsx 90fbb9f :4014-4078): "Take over “name”" with the provider and
 * model, the X ("Cancel takeover"); until the brief lands "Preparing takeover brief…", then the
 * editable instruction, the collapsed "Session summary" (the digest) and Cancel / Take over (disabled
 * while the text is blank).
 */
@Composable
internal fun HandoffDraftPanel(
    source: AgentSession?,
    brief: HandoffBriefReading?,
    text: String,
    onTextChange: (String) -> Unit,
    summaryCollapsed: Boolean,
    onToggleSummary: () -> Unit,
    onCancel: () -> Unit,
    onCommit: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusMd)
    val name = source?.let { LabelText.label(it.name).ifEmpty { LabelText.visibleValue(it.name) } }
    Column(
        Modifier
            .fillMaxWidth()
            .cssSurface(shape, t.graphite, CssBorder(1.dp, t.lineStrong), emptyList())
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm)
            .semantics { paneTitle = "Take over a session" }
            .testTag(TakeoverTags.Draft),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (source != null) ProviderTile(source.provider, Modifier.size(20.dp), fallback = providerGlyph(source.provider), color = t.muted, markSize = 16.dp, letterSize = 11.sp)
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text(
                    if (name != null) "Take over “$name”" else "Take over session",
                    style = type.body.copy(fontSize = rem(0.82f), fontWeight = FontWeight(650)),
                    color = t.white,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (source != null) {
                    val sub = LabelText.visibleValue(source.provider) + (source.model?.let { LabelText.label(it) }?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                    Text(" $sub", style = type.body.copy(fontSize = rem(0.76f)), color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(role = Role.Button, onClickLabel = "Cancel takeover", onClick = onCancel)
                    .semantics { contentDescription = "Cancel takeover" }
                    .testTag(TakeoverTags.CancelX),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TetherIcons.X, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
            }
        }
        if (brief == null) {
            Text(
                "Preparing takeover brief…",
                style = type.body.copy(fontSize = rem(0.78f)),
                color = t.muted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(TakeoverTags.Loading),
            )
            return@Column
        }
        val mono = TextStyle(fontFamily = type.mono, fontSize = rem(0.76f), lineHeight = 1.5.em)
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = mono.copy(color = t.ink),
            cursorBrush = SolidColor(t.violet),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 260.dp)
                .background(t.slate, RoundedCornerShape(t.radiusSm))
                .border(1.dp, t.line, RoundedCornerShape(t.radiusSm))
                .padding(t.css.spaceSm)
                .semantics { contentDescription = "Takeover instruction (editable)" }
                .testTag(TakeoverTags.Editor),
        )
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .clickable(role = Role.Button, onClick = onToggleSummary)
                .semantics { stateDescription = if (summaryCollapsed) "Collapsed" else "Expanded" }
                .testTag(TakeoverTags.SummaryToggle)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(if (summaryCollapsed) TetherIcons.ChevronRight else TetherIcons.ChevronDown, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
            Text("Session summary", style = type.body.copy(fontSize = rem(0.72f), fontWeight = FontWeight(650)), color = t.muted)
        }
        if (!summaryCollapsed) {
            Text(
                brief.markdown,
                style = mono.copy(fontSize = rem(0.72f)),
                color = t.muted,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 288.dp)
                    .background(t.slate, RoundedCornerShape(t.radiusSm))
                    .border(1.dp, t.line, RoundedCornerShape(t.radiusSm))
                    .verticalScroll(rememberScrollState())
                    .padding(t.css.spaceSm)
                    .semantics { contentDescription = "Session summary" }
                    .testTag(TakeoverTags.Summary),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = t.css.spaceXs),
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // `button-ghost` has no rule of its own: the bare button in the panel's ink.
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClick = onCancel)
                    .testTag(TakeoverTags.Cancel)
                    .padding(horizontal = t.css.spaceSm),
                contentAlignment = Alignment.Center,
            ) {
                Text("Cancel", style = type.body.copy(fontSize = rem(0.82f)), color = t.ink)
            }
            TetherKey(
                onClick = onCommit,
                classes = KeyClasses.ButtonPrimary,
                label = "Take over",
                icon = TetherIcons.ArrowRightLeft,
                iconSize = 14.dp,
                enabled = text.trim().isNotEmpty(),
                modifier = Modifier.testTag(TakeoverTags.Commit),
            )
        }
    }
}
