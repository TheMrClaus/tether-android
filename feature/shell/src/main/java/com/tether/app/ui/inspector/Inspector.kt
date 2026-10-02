package com.tether.app.ui.inspector

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tether.app.client.ServiceOpenSource
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.model.SessionView
import com.tether.app.ui.chat.CustomTabLinkOpener
import com.tether.app.ui.chat.GitChangesCard
import com.tether.app.ui.chat.LinkOpener
import com.tether.app.ui.chat.ProviderNoticeRow
import com.tether.app.ui.chat.SubagentRoster
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.StatusDot
import com.tether.app.ui.components.WaitingPingDot
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.statusColor
import com.tether.app.ui.components.statusToneOf
import com.tether.app.ui.icons.ProviderLogo
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.shell.cssText
import com.tether.app.ui.statusline.ReadingEnv
import com.tether.app.ui.statusline.UsageTrack
import com.tether.app.ui.statusline.UsageTrackPlacement
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.appendSafe
import com.tether.app.ui.text.codeDirection
import com.tether.app.ui.text.proseDirection
import com.tether.app.ui.text.tokenStyle
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTokens
import kotlinx.coroutines.launch

/*
 * T9.1: `<Inspector>` (components/inspector.tsx 387-731), drawn from an [InspectorModel]. The same
 * body fills the phone's telemetry sheet and the expanded layout's inspector column (the web's
 * telemetry-sheet.tsx renders the very same component), so the two surfaces cannot drift.
 *
 * Sections, in the web's order: identity, the sub-agent roster, Usage (session- or run-scoped),
 * Repository, Account limits, the limit notice, MCP health, Services, Codex notices, opencode's
 * Plugins, then the Runtime details disclosure and the acp capability set. Display only: no key here
 * sends anything but the reads the web's inspector makes (a file's diff hunks); the reset actions
 * ("Use reset"), the change-request refresh, the draft actions and the service controls belong to
 * T9.2 / T8.3 / T8.5 and are omitted. T15.7 adds a running service's "Open" link (confirm first,
 * external browser); the web's "On this machine" link (the console's loopback-only path form) is
 * not offered in the app.
 */

object InspectorTags {
    const val Root = "inspector"
    const val Identity = "inspector-identity"
    const val Usage = "inspector-usage"
    const val RunUsage = "inspector-run-usage"
    const val ShowSession = "inspector-show-session"
    const val PerModel = "inspector-per-model"
    const val Repository = "inspector-repository"
    const val Changes = "inspector-changes"
    const val Limits = "inspector-limits"
    const val Services = "inspector-services"
    const val ServiceOpen = "inspector-service-open"
    const val ServiceLocal = "inspector-service-local"
    const val ServiceOpenRefusal = "inspector-service-open-refusal"
    const val CodexNotices = "inspector-codex-notices"
    const val SessionDivider = "inspector-session-divider"
    const val Runtime = "inspector-runtime"
    const val RuntimeRow = "inspector-runtime-row"
    const val Names = "inspector-names"
    const val Capabilities = "inspector-capabilities"
}

/** The inspector body, in the host's column (the sheet's scroller or the expanded column). */
@Composable
fun ColumnScope.Inspector(
    model: InspectorModel,
    state: SessionView?,
    onSelectRun: (String?) -> Unit,
    fileDiffs: Map<String, ServerMessage.GitDiffFile>?,
    onRequestFileDiff: (String) -> Unit,
    env: () -> ReadingEnv = ReadingEnv::current,
    /**
     * T15.7: how a service link leaves the app: the browser (a browsable-only Custom Tab intent,
     * no app credential). Deliberately NOT [com.tether.app.ui.chat.LocalLinkOpener] (whose
     * session-link routing could keep a console URL in the app). Tests may observe it.
     */
    serviceOpener: LinkOpener = CustomTabLinkOpener,
    /** ta-coik.2: asks the console's worktree-open route with the app's sign-in (the client's). */
    serviceOpen: ServiceOpenSource = ServiceOpenSource.Unavailable,
) {
    Column(Modifier.fillMaxWidth().testTag(InspectorTags.Root)) {
        IdentityHeading(model.identity)
        if (model.runs.isNotEmpty()) {
            Spacer(Modifier.padding(top = LocalTetherTokens.current.css.spaceMd))
            SubagentRoster(model.runs, model.activeRunId, onSelectRun, open = model.activeRunId != null)
        }
        when (val usage = model.usage) {
            is SessionUsage -> SessionUsageSection(usage)
            is RunUsage -> RunUsageSection(usage) { onSelectRun(null) }
        }
        model.repository?.let { RepositoryPanel(it, fileDiffs, onRequestFileDiff) }
        if (!model.limits.empty) LimitsPanel(model.limits)
        InspectorLimitNotice(state, env = env)
        if (model.mcpHealth) {
            val servers = remember(state) { mcpServers(state) }
            McpHealthCard(servers, "MCP health", compact = true)
        }
        model.services?.let { ServicesCard(it, serviceOpener, serviceOpen) }
        if (model.codexNotices.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = LocalTetherTokens.current.css.spaceLg).semantics { contentDescription = "Codex notices" }.testTag(InspectorTags.CodexNotices),
                verticalArrangement = Arrangement.spacedBy(LocalTetherTokens.current.css.spaceSm),
            ) { model.codexNotices.forEach { ProviderNoticeRow(it) } }
        }
        if (model.opencodePlugins) {
            val servers = remember(state) { mcpServers(state) }
            McpHealthCard(servers, "Plugins", compact = false, count = { n -> "$n loaded" })
        }
        if (model.sessionDivider) SectionLabel("Session", Modifier.padding(top = LocalTetherTokens.current.css.spaceMd).testTag(InspectorTags.SessionDivider))
        RuntimeDetails(model.runtime)
        model.acpCapabilities?.let { AcpCapabilities(it) }
    }
}

// --- Text ------------------------------------------------------------------------------------

/**
 * A [Line] drawn by each segment's rule: app copy and cleaned labels as they are, code through
 * SafeText's line / code rules, messages through its prose rule (hidden code points as `--warning` tokens).
 * A line of prose alone lays out in its content's direction; anything else LTR (code, app copy).
 * One-line values (paths, branches, ids) use the LINE rule: TAB / LF / CR are tokens too.
 */
@Composable
internal fun rendered(line: Line): AnnotatedString {
    val t = LocalTetherTokens.current
    return remember(line, t) { renderLine(line, t) }
}

internal fun renderLine(line: Line, t: TetherTokens): AnnotatedString {
    val style = tokenStyle(t)
    val proseOnly = line.isNotEmpty() && line.all { it.rule == Rule.Prose }
    return buildAnnotatedString {
        withStyle(ParagraphStyle(textDirection = if (proseOnly) proseDirection else codeDirection)) {
            line.forEach { seg ->
                when (seg.rule) {
                    Rule.App, Rule.Label -> append(seg.text)
                    Rule.Line -> appendSafe(seg.text, SafeText.Rule.Line, style)
                    Rule.Code -> appendSafe(seg.text, SafeText.Rule.Code, style)
                    Rule.Prose -> appendSafe(seg.text, SafeText.Rule.Prose, style)
                }
            }
        }
    }
}

@Composable
private fun RuledText(line: Line, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(rendered(line), style = style, color = color, modifier = modifier, maxLines = maxLines, overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis)
}

private fun Modifier.topRule(color: Color): Modifier = drawBehind { drawRect(color, Offset.Zero, Size(size.width, 1.dp.toPx())) }
private fun Modifier.bottomRule(color: Color): Modifier = drawBehind { drawRect(color, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }

// --- Identity --------------------------------------------------------------------------------

/** `.inspector-heading`: a 2.25rem mineral mark with the provider logo, the label, the status line. */
@Composable
private fun IdentityHeading(identity: Identity) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val tone = statusToneOf(identity.status)
    val ink = statusColor(tone)
    Row(
        Modifier
            .fillMaxWidth()
            .bottomRule(t.line)
            .padding(bottom = (20.dp) + 1.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(InspectorTags.Identity),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd),
    ) {
        Box(
            Modifier.size(36.dp).cssSurface(RoundedCornerShape(t.radiusMd), t.mineralDeep, CssBorder(1.dp, t.lineStrong)),
            contentAlignment = Alignment.Center,
        ) {
            ProviderLogo(identity.provider, color = t.ink, markSize = 20.8.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
            RuledText(
                listOf(identity.providerLabel),
                cssText(type.ui, 0.95f, 720, trackingEm = -0.015f),
                t.white,
                Modifier.semantics { heading() },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.6.dp)) {
                if (identity.status == "waiting") WaitingPingDot(ink) else StatusDot(ink)
                RuledText(listOf(identity.statusText), cssText(type.ui, 0.7f, 400), ink)
            }
        }
    }
}

// --- Usage -----------------------------------------------------------------------------------

/** `.usage-heading`: the uppercase faint h2 and its mono pill (a running dot before it). */
@Composable
private fun UsageHeading(title: String, badge: String?, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        modifier.fillMaxWidth().padding(bottom = t.css.spaceLg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), style = cssText(type.ui, 0.66f, 720, trackingEm = 0.12f), color = t.faint, modifier = Modifier.weight(1f).semantics { heading() })
        if (badge != null) {
            Row(
                Modifier.border(1.dp, t.lineStrong, RoundedCornerShape(percent = 50)).padding(horizontal = 7.2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(t.running, size = 5.6.dp)
                Spacer(Modifier.width(5.6.dp))
                Text(badge.uppercase(), style = cssText(type.mono, 0.56f, 700, trackingEm = 0.08f, lineHeight = 2.05f), color = t.running)
            }
        }
    }
}

/** `.usage-totals`: the recessed plate of one or two readings. */
@Composable
private fun Totals(cells: List<Triple<String, String, String>>) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val line = t.line
    Row(
        Modifier
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(t.radiusMd), t.mineralDeep, CssBorder(1.dp, t.lineStrong))
            .padding(1.dp),
    ) {
        cells.forEachIndexed { i, (label, value, caption) ->
            Column(
                Modifier
                    .weight(1f)
                    .then(if (i > 0) Modifier.drawBehind { drawRect(line, Offset.Zero, Size(1.dp.toPx(), size.height)) }.padding(start = 1.dp) else Modifier)
                    .padding(start = t.css.spaceMd, end = t.css.spaceMd, top = t.css.spaceMd, bottom = 11.2.dp)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(2.9.dp),
            ) {
                Text(label.uppercase(), style = cssText(type.ui, 0.58f, 700, trackingEm = 0.1f), color = t.faint)
                Text(value, style = cssText(type.mono, 1.2f, 640, trackingEm = -0.01f), color = t.white, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(caption, style = cssText(type.ui, 0.6f, 400), color = t.faint)
            }
        }
    }
}

/** `.usage-meter`: label and printed value, the track, the caption. */
@Composable
private fun Meter(label: String, value: String, percent: Int?, caption: String, track: Boolean = true) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.fillMaxWidth().padding(top = t.css.spaceLg).semantics(mergeDescendants = true) {}) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), style = cssText(type.ui, 0.58f, 700, trackingEm = 0.1f), color = t.faint, modifier = Modifier.weight(1f))
            Text(value, style = cssText(type.mono, 0.78f, 660), color = t.white)
        }
        if (track) {
            UsageTrack(percent, label, Modifier.padding(top = 7.2.dp, bottom = 5.6.dp), placement = UsageTrackPlacement.Meter)
        }
        Text(caption, style = cssText(type.ui, 0.58f, 400), color = t.faint)
    }
}

@Composable
private fun SessionUsageSection(usage: SessionUsage) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.fillMaxWidth().padding(top = t.css.spaceXl).testTag(InspectorTags.Usage)) {
        UsageHeading("Usage", usage.badge)
        Totals(
            listOfNotNull(
                Triple(usage.totalLabel, usage.totalValue, usage.totalCaption),
                usage.lastTurn?.let { Triple("Last turn", it, "Whole-tree tokens") },
            ),
        )
        Row(Modifier.padding(top = t.css.spaceSm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
            Icon(TetherIcons.Bot, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
            Text(usage.subagentsSpawned, style = cssText(type.ui, 0.68f, 400), color = t.muted)
        }
        usage.context?.let { Meter("Context", "${it.percent}%", it.percent, it.caption) }
        usage.snapshot?.let {
            Meter("Context", com.tether.app.protocol.helpers.Format.compactNumber(it.tokens), null, "Snapshot · ${it.asOf} · window size not reported", track = false)
        }
        if (usage.perModel.isNotEmpty()) {
            Column(Modifier.padding(top = t.css.spaceLg)) {
                SmallDisclosure(usage.perModelSummary, Modifier.testTag(InspectorTags.PerModel)) {
                    usage.perModel.forEach { ModelUsageItem(it) }
                }
            }
        }
    }
}

@Composable
private fun ModelUsageItem(row: ModelUsageRow) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val small = cssText(type.ui, 0.58f, 400, lineHeight = 1.45f)
    Column(
        Modifier.fillMaxWidth().bottomRule(t.line).padding(top = t.css.spaceLg, bottom = t.css.spaceLg + 1.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.9.dp),
    ) {
        RuledText(listOf(row.identity), cssText(type.mono, 0.68f, 620), t.ink)
        if (row.contributors.isNotEmpty()) {
            val joined = buildList { row.contributors.forEachIndexed { i, seg -> if (i > 0) add(app(", ")); add(seg) } }
            RuledText(joined, cssText(type.ui, 0.58f, 560, lineHeight = 1.45f), t.ink)
        }
        RuledText(row.provider, small, t.faint)
        row.figures.forEach { Text(it, style = small, color = t.faint) }
    }
}

@Composable
private fun RunUsageSection(usage: RunUsage, onShowSession: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.fillMaxWidth().padding(top = t.css.spaceXl).testTag(InspectorTags.RunUsage)) {
        UsageHeading("Sub-agent", usage.status)
        Row(
            Modifier.fillMaxWidth().padding(bottom = t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Icon(TetherIcons.Bot, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
            RuledText(listOf(usage.title), cssText(type.ui, 0.78f, 400), t.ink, Modifier.weight(1f), maxLines = 1)
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClick = onShowSession)
                    .testTag(InspectorTags.ShowSession),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Show session",
                    style = cssText(type.ui, 0.68f, 400),
                    color = t.muted,
                    modifier = Modifier.border(1.dp, t.line, RoundedCornerShape(t.radiusSm)).padding(horizontal = 7.2.dp, vertical = 2.4.dp),
                )
            }
        }
        Totals(listOf(Triple("Tokens", usage.tokens, usage.tokensCaption), Triple("Steps", usage.steps, usage.stepsCaption)))
        usage.gap?.let { EmptyNote(it) }
        Specs(usage.specs)
    }
}

// --- Notes and specs -------------------------------------------------------------------------

/** `.telemetry-empty`: a faint ruled note. */
@Composable
private fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(
        text,
        style = cssText(type.ui, 0.65f, 400, lineHeight = 1.5f),
        color = t.faint,
        modifier = modifier.fillMaxWidth().padding(top = t.css.spaceXl).topRule(t.line).padding(top = t.css.spaceLg),
    )
}

/** `.section-label`. */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Text(text.uppercase(), style = cssText(type.ui, 0.67f, 720, trackingEm = 0.1f), color = t.faint, modifier = modifier.semantics { heading() })
}

/** `.telemetry-specs`: rows of an uppercase label over a mono value (Studio: sentence-case labels). */
@Composable
private fun Specs(rows: List<SpecRow>) {
    Column(Modifier.fillMaxWidth()) { rows.forEach { SpecItem(it) } }
}

@Composable
private fun SpecItem(row: SpecRow) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val labelStyle = cssText(type.ui, 0.72f, 700)
    val valueStyle = cssText(type.mono, 0.74f, 400, lineHeight = 1.5f)
    val noteStyle = cssText(type.ui, 0.64f, 400, lineHeight = 1.5f)
    Column(
        Modifier
            .fillMaxWidth()
            .bottomRule(t.line)
            .padding(top = t.css.spaceLg, bottom = t.css.spaceLg + 1.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(InspectorTags.RuntimeRow),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Text(row.label, style = labelStyle, color = t.faint)
        Column {
            if (row.value.isNotEmpty()) {
                val value = if (row.capitalize) row.value.map { if (it.rule == Rule.Label || it.rule == Rule.App) it.copy(text = capitalizeWords(it.text)) else it } else row.value
                RuledText(value, valueStyle, t.ink)
            }
            row.notes.forEach { note ->
                RuledText(
                    note.line,
                    noteStyle,
                    if (note.warning) t.warning else t.faint,
                    Modifier.padding(top = t.css.spaceXs).then(if (note.status) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
                )
            }
            if (row.names.isNotEmpty()) {
                SmallDisclosure("Names", Modifier.testTag(InspectorTags.Names)) {
                    row.names.forEach { RuledText(it, noteStyle, t.faint, Modifier.padding(top = t.css.spaceXs)) }
                }
            }
        }
    }
}

/** CSS `text-transform: capitalize`: the first letter of each word. */
internal fun capitalizeWords(text: String): String =
    text.split(' ').joinToString(" ") { w -> w.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() } }

// --- Disclosures -----------------------------------------------------------------------------

/** A disclosure caret: the web's rotated border corner (right when closed, down when open). */
@Composable
private fun Caret(open: Boolean, tint: Color, size: Dp = 11.dp) {
    Icon(TetherIcons.ChevronRight, contentDescription = null, tint = tint, modifier = Modifier.size(size).rotate(if (open) 90f else 0f))
}

/** `.telemetry-disclosure`: a quiet uppercase summary with a caret (Per-model breakdown, Names). */
@Composable
private fun SmallDisclosure(summary: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    var open by rememberSaveable(summary) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = t.css.spaceXs)) {
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button) { open = !open }
                .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Caret(open, t.faint, 9.dp)
            Text(summary.uppercase(), style = cssText(type.ui, 0.62f, 650, trackingEm = 0.04f), color = t.faint)
        }
        if (open) content()
    }
}

/** `.inspector-disclosure`: a ruled, ink summary (Runtime details, Changes) with an optional count. */
@Composable
private fun SectionDisclosure(summary: String, count: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    var open by rememberSaveable(summary) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = t.css.spaceLg).topRule(t.line).padding(top = 1.dp)) {
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button) { open = !open }
                .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Caret(open, t.ink)
            Text(summary, style = cssText(type.ui, 0.74f, 650), color = t.ink, modifier = Modifier.weight(1f))
            count?.let { Text(it, style = cssText(type.ui, 0.65f, 500), color = t.muted) }
        }
        if (open) content()
    }
}

// --- Repository ------------------------------------------------------------------------------

@Composable
private fun RepositoryPanel(repo: RepositorySection, fileDiffs: Map<String, ServerMessage.GitDiffFile>?, onRequestFileDiff: (String) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = t.css.spaceLg)
            .topRule(t.line)
            .padding(top = t.css.spaceLg)
            .semantics { contentDescription = "Repository" }
            .testTag(InspectorTags.Repository),
    ) {
        Text("Repository", style = cssText(type.ui, 0.78f, 700), color = t.ink, modifier = Modifier.padding(bottom = t.css.spaceMd).semantics { heading() })
        repo.branch?.let { branch ->
            RepoLine(TetherIcons.GitBranch, listOf(branch), repo.divergence, code = true)
        }
        repo.pullRequest?.let { pr ->
            // Plain text: the link opens nowhere from here (the app's link gate is ta-fz3's, the refresh T8.3's).
            RepoLine(TetherIcons.GitPullRequest, listOf(app(pr.headline)), pr.state, code = false)
        }
        repo.changes?.let { diff ->
            SectionDisclosure("Changes", repo.changesCount, Modifier.testTag(InspectorTags.Changes)) {
                Spacer(Modifier.padding(top = t.css.spaceXs))
                GitChangesCard(diff, fileDiffs, onRequestFile = onRequestFileDiff)
            }
        }
    }
}

@Composable
private fun RepoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, line: Line, small: String?, code: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier.fillMaxWidth().padding(bottom = t.css.spaceMd).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Icon(icon, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp))
        Column(Modifier.weight(1f)) {
            RuledText(line, cssText(if (code) type.mono else type.ui, 0.72f, 400), if (code) t.ink else t.muted)
            small?.let { Text(it, style = cssText(type.ui, 0.65f, 400), color = t.muted, modifier = Modifier.padding(top = t.css.spaceXs)) }
        }
    }
}

// --- Account limits --------------------------------------------------------------------------

@Composable
private fun LimitsPanel(limits: LimitsSection) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(
        // `.inspector .usage-windows` (margin-top space-lg, the top rule) also carries `.usage-section`,
        // whose later `.inspector .usage-section` padding-top (space-md) wins over its `padding-top: 0`
        // (ta-dl4): the band between this rule and the empty note's own rule is space-md + space-xl.
        Modifier.fillMaxWidth().padding(top = t.css.spaceLg).semantics { contentDescription = "Account limits" }.testTag(InspectorTags.Limits)
            .topRule(t.line).padding(top = 1.dp + t.css.spaceMd),
    ) {
        limits.windows.forEach { (label, reading) ->
            Meter(label, reading.percent?.let { "$it%" } ?: "—", reading.percent, reading.caption)
        }
        if (limits.awaitingTelemetry) EmptyNote("Telemetry appears after the agent completes its first response.")
        limits.resetGrants?.let { (headline, note) ->
            Column(Modifier.fillMaxWidth().padding(top = t.css.spaceMd).semantics(mergeDescendants = true) {}) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Limit resets", style = cssText(type.ui, 0.7f, 400), color = t.muted)
                    Text(headline, style = cssText(type.ui, 0.72f, 400), color = t.ink)
                }
                Text(note, style = cssText(type.ui, 0.64f, 400, lineHeight = 1.5f), color = t.faint, modifier = Modifier.padding(top = t.css.spaceXs))
            }
        }
        // The web's "Use reset" key (a consuming POST) is the Usage page's (T9.2): the count only here.
        limits.bankedResets?.let { count ->
            Row(Modifier.fillMaxWidth().padding(top = t.css.spaceMd).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Banked resets", style = cssText(type.ui, 0.7f, 400), color = t.muted)
                Text(count, style = cssText(type.ui, 0.72f, 400), color = t.ink)
            }
        }
    }
}

// --- Services --------------------------------------------------------------------------------

/**
 * worktree-services-card.tsx: the MCP card's frame, rows in words. Display only but for the links
 * of a running service (worktree-services-card.tsx:141-154), which open on a tap, as on the web:
 * "Open" through [serviceOpen] (the app's sign-in), "On this machine" straight to [opener].
 */
@Composable
private fun ServicesCard(services: ServicesSection, opener: LinkOpener, serviceOpen: ServiceOpenSource) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusMd)
    val tint = t.tintSm
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = t.css.spaceLg)
            .cssSurface(shape, background = t.mineralDeep, border = CssBorder(1.dp, t.line))
            .padding(1.dp)
            .semantics { contentDescription = "Worktree scripts and services" }
            .testTag(InspectorTags.Services),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Icon(TetherIcons.Terminal, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp))
            Text("Services", style = cssText(type.mono, 0.78f, 650), color = t.ink, modifier = Modifier.weight(1f))
            Text(services.count.uppercase(), style = cssText(type.mono, 0.68f, 650, trackingEm = 0.04f), color = t.muted, textAlign = TextAlign.End)
        }
        val setupStyle = cssText(type.ui, 0.76f, 400, lineHeight = 1.45f)
        services.setup?.let { setup ->
            Column(
                Modifier.fillMaxWidth().topRule(t.line).padding(top = 1.dp).padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm).semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
            ) {
                Text(setup.text, style = setupStyle, color = if (setup.failed) t.warning else t.muted)
                setup.log?.let { RuledText(listOf(it), cssText(type.mono, 0.68f, 400, lineHeight = 1.5f), if (setup.failed) t.warning else t.muted) }
            }
        }
        services.configWarnings.forEach { warning ->
            RuledText(
                listOf(warning),
                setupStyle,
                t.warning,
                Modifier.fillMaxWidth().topRule(t.line).padding(top = 1.dp).padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (services.scripts.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().topRule(t.line).padding(top = 1.dp)) {
                services.scripts.forEachIndexed { i, row ->
                    val last = i == services.scripts.lastIndex
                    ServiceItem(row, Modifier.then(if (last) Modifier else Modifier.bottomRule(tint)), opener, serviceOpen)
                }
            }
        }
    }
}

@Composable
private fun ServiceItem(row: ServiceRow, modifier: Modifier, opener: LinkOpener, serviceOpen: ServiceOpenSource) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val problem = if (row.failed) t.warning else t.muted
    val mono = cssText(type.mono, 0.7f, 400, lineHeight = 1.4f)
    Column(
        modifier.fillMaxWidth().padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(3.2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
            RuledText(listOf(row.name), cssText(type.mono, 0.76f, 400), t.ink, Modifier.weight(1f, fill = false), maxLines = 1)
            Text(row.status, style = cssText(type.ui, 0.76f, 400), color = problem)
        }
        RuledText(listOf(row.command), mono, t.muted)
        row.address?.let { RuledText(listOf(app("Own address "), it), mono, t.ink) }
        if (row.open != null || row.local != null) ServiceLinks(row.open, row.local, opener, serviceOpen)
        row.unavailable?.let { Text(it, style = mono, color = t.warning, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        row.error?.let { RuledText(listOf(it), mono, t.warning, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

/**
 * `.links`: "Open" then "On this machine", each with its external-link glyph (ink, 0.72rem, 2.75rem
 * tall), opening on a tap like the web's `<a target="_blank">`.
 *
 * ta-coik.2: "Open" asks the console's worktree-open route with the app's own sign-in (the web's
 * browser follows it with its console session) and hands the browser the service-host address the
 * server redirects to; one request at a time. When the console refuses, its reason shows under the
 * links (the web's tab would show the same answer). "On this machine" opens the console-origin path
 * form in the browser, as the web's link does (that browser's own console sign-in authenticates it).
 */
@Composable
private fun ServiceLinks(open: ServiceOpen?, local: ServiceLocal?, opener: LinkOpener, serviceOpen: ServiceOpenSource) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(open?.url) { mutableStateOf(false) }
    var refusal by remember(open?.url) { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd), verticalAlignment = Alignment.CenterVertically) {
        open?.let { link ->
            ServiceLinkKey("Open", InspectorTags.ServiceOpen) {
                if (busy) return@ServiceLinkKey
                busy = true
                refusal = null
                scope.launch {
                    try {
                        when (val outcome = serviceOpen.open(link.url, link.host.text)) {
                            is ServiceOpenSource.Outcome.Open -> opener.open(context, outcome.url, t.graphite)
                            is ServiceOpenSource.Outcome.Refused -> refusal = outcome.message
                        }
                    } finally {
                        busy = false
                    }
                }
            }
        }
        local?.let { link -> ServiceLinkKey("On this machine", InspectorTags.ServiceLocal) { opener.open(context, link.url, t.graphite) } }
    }
    refusal?.let {
        Text(
            it,
            style = cssText(type.mono, 0.7f, 400, lineHeight = 1.4f),
            color = t.warning,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(InspectorTags.ServiceOpenRefusal),
        )
    }
}

@Composable
private fun ServiceLinkKey(label: String, tag: String, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            .clickable(role = Role.Button, onClickLabel = "Open in browser", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.8.dp),
    ) {
        Text(label, style = cssText(type.ui, 0.72f, 400), color = t.ink)
        Icon(TetherIcons.ExternalLink, contentDescription = null, tint = t.ink, modifier = Modifier.size(12.dp))
    }
}

// --- Runtime details and capabilities --------------------------------------------------------

@Composable
private fun RuntimeDetails(rows: List<SpecRow>) {
    SectionDisclosure("Runtime details", null, Modifier.testTag(InspectorTags.Runtime)) { Specs(rows) }
}

@Composable
private fun AcpCapabilities(rows: List<Pair<String, Boolean>>) {
    val t = LocalTetherTokens.current
    Column(Modifier.fillMaxWidth().padding(top = t.css.spaceXl).semantics { contentDescription = "ACP agent capabilities" }.testTag(InspectorTags.Capabilities)) {
        UsageHeading("Capabilities", "advertised")
        Specs(rows.map { (label, on) -> SpecRow(label, listOf(app(if (on) "Yes" else "No"))) })
    }
}
