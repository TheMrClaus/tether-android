package com.tether.app.ui.inspector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.client.ServiceOpenSource
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.model.SessionView
import com.tether.app.ui.chat.CustomTabLinkOpener
import com.tether.app.ui.chat.GitChangesCard
import com.tether.app.ui.chat.LinkOpener
import com.tether.app.ui.chat.LocalLinkOpener
import com.tether.app.ui.chat.ProviderNoticeRow
import com.tether.app.ui.chat.RUN_ERROR
import com.tether.app.ui.chat.RUN_RUNNING
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.components.StatusDot
import com.tether.app.ui.components.WaitingPingDot
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.originalWords
import com.tether.app.ui.components.statusColor
import com.tether.app.ui.components.statusToneOf
import com.tether.app.ui.icons.ProviderTile
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.shell.cssText
import com.tether.app.ui.statusline.ReadingEnv
import com.tether.app.ui.statusline.UsageTrack
import com.tether.app.ui.statusline.UsageTrackPlacement
import com.tether.app.ui.statusline.UsageTone
import com.tether.app.ui.statusline.WrapUpPill
import com.tether.app.ui.statusline.usageTone
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
 * T9.1 / ta-coik.10: the session telemetry panel (tether 90fbb9f components/inspector.tsx, the
 * f4c4133 "Headroom first" redesign; styles app/telemetry-panel.css), drawn from an
 * [InspectorModel]. ONE body, two hosts: the phone's telemetry sheet and the expanded layout's
 * inspector column (the web's telemetry-sheet.tsx renders the very same component).
 *
 * Bands, in the operator's order: the header (harness + status, model · effort · account, the
 * divergence note, "Now"), the attention strip (only while something is wrong), Context, Limits,
 * Subagents, the selected run, MCP health, Tokens, Repository, Services, Codex notices, opencode's
 * Plugins, Runtime (collapsed), the acp capability set. Bands are separated by one hairline; meters
 * are ink (warning at 75%, danger at 90%, the number always printed); violet marks only the
 * selected run row and focus.
 *
 * Sizes are the panel's coarse-pointer set (`@media (max-width: 47.999rem), (pointer: coarse)`,
 * telemetry-panel.css 338-353): Android is always a coarse pointer, on a phone and a tablet alike.
 *
 * The keys are the web panel's own: a file's diff hunks, the pull request's link and its refresh,
 * each worktree script's Run / Stop / Restart and "Output of" view (ta-coik.14), a service's links,
 * and the Limits band's "Use reset" (T9.2's shared Codex confirmation).
 */

object InspectorTags {
    /** ta-m7ef: the Services card's i-th "did not run" sentence (setup first, then teardown). */
    fun serviceSkipped(i: Int) = "inspector-service-skipped:$i"
    const val Root = "inspector"

    /** inspector.tsx:471: the run row's "effort not set" line, with the reason it is missing on a long-press. */
    const val EffortMissingReason = "inspector-effort-missing-reason"
    const val UseCodexReset = "inspector-codex-reset-use"
    const val Identity = "inspector-identity"
    const val Header = "inspector-header"
    const val Attention = "inspector-attention"
    const val RateLimit = "inspector-rate-limit"
    const val Context = "inspector-context"
    const val Gauge = "inspector-gauge"
    const val Subagents = "inspector-subagents"
    const val RunRow = "inspector-run-row"
    const val RunsMore = "inspector-runs-more"
    const val RunUsage = "inspector-run-usage"
    const val ShowSession = "inspector-show-session"
    const val Tokens = "inspector-tokens"
    const val LedgerRow = "inspector-ledger-row"
    const val LedgerValue = "inspector-ledger-value"
    const val PerModel = "inspector-per-model"
    const val Repository = "inspector-repository"
    const val Changes = "inspector-changes"
    const val PullRequestLink = "inspector-pull-request-link"
    const val RefreshPullRequest = "inspector-pull-request-refresh"
    const val PullRequestUnopened = "inspector-pull-request-unopened"
    const val ScriptLog = "inspector-script-log"
    const val DraftActions = "inspector-draft-actions"
    const val DraftCommitMessage = "inspector-draft-commit-message"
    const val DraftPullRequest = "inspector-draft-pull-request"
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

/** telemetry-panel.css `.inspector.ti` custom properties at the coarse-pointer size (1rem = 16dp). */
private object Ti {
    /** `--ti-label: 4.75rem`: the label column every meter, row and spec shares. */
    val label: Dp = 76.dp

    /** `--ti-value: 3rem`: the right-aligned number column. */
    val value: Dp = 48.dp

    /** `--ti-gap: 0.625rem`. */
    val gap: Dp = 10.dp

    /** `--ti-band-gap: 1.125rem`. */
    val bandGap: Dp = 18.dp

    /** The minimum touch target of every visible control on a coarse pointer. */
    val target: Dp = 44.dp
}

/** The panel body, in the host's column (the sheet's scroller or the expanded column). */
@Composable
fun ColumnScope.Inspector(
    model: InspectorModel,
    state: SessionView?,
    onSelectRun: (String?) -> Unit,
    fileDiffs: Map<String, ServerMessage.GitDiffFile>?,
    onRequestFileDiff: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") env: () -> ReadingEnv = ReadingEnv::current,
    /**
     * T15.7: how a service link leaves the app: the browser (a browsable-only Custom Tab intent,
     * no app credential). Deliberately NOT [com.tether.app.ui.chat.LocalLinkOpener] (whose
     * session-link routing could keep a console URL in the app). Tests may observe it.
     */
    serviceOpener: LinkOpener = CustomTabLinkOpener,
    /** ta-coik.2: asks the console's worktree-open route with the app's sign-in (the client's). */
    serviceOpen: ServiceOpenSource = ServiceOpenSource.Unavailable,
    /** T9.2 (inspector.tsx:759-769): the banked resets row's "Use reset" opens the shared Codex confirmation. */
    onUseCodexReset: (() -> Unit)? = null,
    /** ta-coik.14 (dashboard.tsx:1457): "Refresh pull request status", `change-request` with `refresh: true`. */
    onRefreshChangeRequest: () -> Unit = {},
    /** ta-coik.14 (dashboard.tsx:1458): Run / Stop / Restart, the script's exact name and "start" | "stop" | "restart". */
    onWorktreeScript: (name: String, action: String) -> Unit = { _, _ -> },
    /** ta-coik.14 (dashboard.tsx:1459): "Output of", asks for the script's recent output. */
    onWorktreeLogs: (name: String) -> Unit = {},
    /** T8.5 (dashboard.tsx:1461, repository-panel.tsx:42/46): "commitMessage" | "pullRequest" for this session. */
    onRequestDraft: (draftKind: String) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().testTag(InspectorTags.Root)) {
        HeaderBlock(model.identity, model.header)
        if (model.attention.any) AttentionStrip(model.attention)
        ContextBandView(model.context)
        model.limits?.let { LimitsBand(it, onUseCodexReset) }
        model.subagents?.let { SubagentsBandView(it, onSelectRun) }
        model.runUsage?.let { RunUsageBand(it) { onSelectRun(null) } }
        if (model.sessionDivider) SessionDivider()
        if (model.mcpHealth) {
            val servers = remember(state) { mcpServers(state) }
            McpHealthCard(servers, "MCP health", compact = true, banded = true)
        }
        if (model.opencodePlugins) {
            val servers = remember(state) { mcpServers(state) }
            McpHealthCard(servers, "Plugins", compact = false, count = { n -> "$n loaded" })
        }
        model.tokens?.let { TokensBandView(it) }
        model.repository?.let { RepositoryPanel(it, fileDiffs, onRequestFileDiff, onRefreshChangeRequest, onRequestDraft) }
        model.services?.let { ServicesCard(it, serviceOpener, serviceOpen, onWorktreeScript, onWorktreeLogs) }
        if (model.codexNotices.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = LocalTetherTokens.current.css.spaceLg).semantics { contentDescription = "Codex notices" }.testTag(InspectorTags.CodexNotices),
                verticalArrangement = Arrangement.spacedBy(LocalTetherTokens.current.css.spaceSm),
            ) { model.codexNotices.forEach { ProviderNoticeRow(it) } }
        }
        RuntimeDisclosure(model.runtime)
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
    val proseOnly = line.isNotEmpty() && line.all { it.rule == Rule.Prose }
    return buildAnnotatedString {
        withStyle(ParagraphStyle(textDirection = if (proseOnly) proseDirection else codeDirection)) {
            line.forEach { seg -> appendSeg(seg, t) }
        }
    }
}

/** One [Seg] by its rule, into a builder (hidden code points as `--warning` tokens). */
internal fun AnnotatedString.Builder.appendSeg(seg: Seg, t: TetherTokens) {
    val style = tokenStyle(t)
    when (seg.rule) {
        Rule.App, Rule.Label -> append(seg.text)
        Rule.Line -> appendSafe(seg.text, SafeText.Rule.Line, style)
        Rule.Code -> appendSafe(seg.text, SafeText.Rule.Code, style)
        Rule.Prose -> appendSafe(seg.text, SafeText.Rule.Prose, style)
    }
}

@Composable
private fun RuledText(line: Line, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(rendered(line), style = style, color = color, modifier = modifier, maxLines = maxLines, overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis)
}

private fun Modifier.topRule(color: Color): Modifier = drawBehind { drawRect(color, Offset.Zero, Size(size.width, 1.dp.toPx())) }
private fun Modifier.bottomRule(color: Color): Modifier = drawBehind { drawRect(color, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }

/** The panel's small uppercase heading (`.ti-band-head h2`, `.ti-identity dt`, `.ti-now-label`). */
@Composable
private fun capsStyle(rem: Float): TextStyle = cssText(LocalTetherTypography.current.ui, rem, 720, trackingEm = 0.07f)

// --- 1. Header -------------------------------------------------------------------------------

@Composable
private fun HeaderBlock(identity: Identity, header: Header) {
    val t = LocalTetherTokens.current
    Column(Modifier.fillMaxWidth().testTag(InspectorTags.Header), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HarnessRow(identity)
        IdentityBox(header)
        header.note?.let { note ->
            RuledText(
                listOf(note),
                cssText(LocalTetherTypography.current.ui, 0.68f, 400, lineHeight = 1.45f),
                t.warning,
                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        header.task?.let { NowRow(it, header.taskProgress) }
    }
}

/** `.ti-harness`: the 1.75rem mark (the brand tile), the harness name, the status in words at the end. */
@Composable
private fun HarnessRow(identity: Identity) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val tone = statusToneOf(identity.status)
    val ink = statusColor(tone)
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(InspectorTags.Identity),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProviderTile(
            identity.provider,
            Modifier.size(28.dp),
            shape = RoundedCornerShape(t.radiusSm),
            background = t.graphiteRaised,
            border = CssBorder(1.dp, t.line),
            color = t.ink,
            markSize = 15.2.dp,
            letterSize = 12.8.sp,
        )
        RuledText(
            listOf(identity.providerLabel),
            cssText(type.ui, 0.8f, 650),
            t.ink,
            Modifier.weight(1f).semantics { heading() },
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.6.dp)) {
            if (identity.status == "waiting") WaitingPingDot(ink, 6.4.dp) else StatusDot(ink, 6.4.dp)
            RuledText(listOf(identity.statusText), cssText(type.ui, 0.7f, 500), ink, maxLines = 1)
        }
    }
}

/** `.ti-identity`: Model | Effort, then the Account across both, on a raised plate. */
@Composable
private fun IdentityBox(header: Header) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val line = t.line
    val reading = cssText(type.mono, 0.95f, 640, trackingEm = -0.01f)
    Column(
        Modifier
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(t.radiusMd), t.graphiteRaised, CssBorder(1.dp, t.line))
            .padding(1.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            IdentityCell("Model", Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        header.model.forEach { appendSeg(it, t) }
                        header.lastServed?.let {
                            withStyle(SpanStyle(color = t.muted, fontSize = (0.66f * 16).sp, fontWeight = FontWeight(500))) {
                                append(" · last served ")
                                appendSeg(it, t)
                            }
                        }
                    },
                    style = reading,
                    color = t.white,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(line))
            IdentityCell("Effort", Modifier, end = true) {
                RuledText(listOf(header.effort), reading, t.white, maxLines = 1)
            }
        }
        header.account?.let { account ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(line))
            IdentityCell("Account", Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The email takes the row; the organisation keeps its own width at the end.
                    RuledText(listOf(account), cssText(type.mono, 0.74f, 400), t.white, Modifier.weight(1f), maxLines = 1)
                    header.organization?.let { RuledText(listOf(it), cssText(type.ui, 0.7f, 620), t.ink, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun IdentityCell(label: String, modifier: Modifier, end: Boolean = false, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    Column(
        modifier.padding(start = 12.dp, end = 12.dp, top = 8.8.dp, bottom = 9.6.dp).semantics(mergeDescendants = true) {},
        horizontalAlignment = if (end) Alignment.End else Alignment.Start,
    ) {
        Text(label.uppercase(), style = capsStyle(0.6f), color = t.muted, modifier = Modifier.padding(bottom = 3.2.dp).originalWords(label))
        content()
    }
}

/** `.ti-now`: "NOW", the agent's own phrasing, its progress under it. */
@Composable
private fun NowRow(task: Seg, progress: String?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("NOW", style = capsStyle(0.6f), color = t.muted, modifier = Modifier.padding(top = 2.dp).originalWords("Now"))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.4.dp)) {
            RuledText(listOf(task), cssText(type.ui, 0.76f, 400, lineHeight = 1.4f), t.white)
            progress?.let { Text(it, style = cssText(type.mono, 0.66f, 400), color = t.muted) }
        }
    }
}

// --- 2. Attention ----------------------------------------------------------------------------

/** `.ti-attention`: words with an icon, a warning (or danger) left edge on its wash. */
@Composable
internal fun AttentionStrip(attention: Attention) {
    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp).semantics { contentDescription = "Needs attention" }.testTag(InspectorTags.Attention),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val wrapUp = attention.wrapUp
        val rate = attention.rateLimit
        if (wrapUp != null) {
            Alert(danger = false, icon = false, status = true) {
                WrapUpPill(wrapUp.label, alertText().copy(fontSize = (0.66f * 16).sp))
                Text(wrapUp.detail, style = alertText(), color = LocalTetherTokens.current.ink)
            }
        } else if (rate != null) {
            Alert(danger = rate.danger, icon = true, status = true, modifier = Modifier.testTag(InspectorTags.RateLimit)) {
                Text(rate.text, style = alertText(), color = LocalTetherTokens.current.ink)
            }
        }
        attention.mcpProblem?.let { problem ->
            Alert(danger = false, icon = true, status = false) {
                Text("MCP servers: $problem — details under MCP health", style = alertText(), color = LocalTetherTokens.current.ink)
            }
        }
        if (attention.setupFailed) {
            Alert(danger = false, icon = true, status = false) {
                Text("Worktree setup did not finish — the checkout is still usable", style = alertText(), color = LocalTetherTokens.current.ink)
            }
        }
    }
}

@Composable
private fun alertText(): TextStyle = cssText(LocalTetherTypography.current.ui, 0.74f, 400, lineHeight = 1.42f)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Alert(danger: Boolean, icon: Boolean, status: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    val edge = if (danger) t.danger else t.warning
    val shape = RoundedCornerShape(t.radiusSm)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (danger) t.brickWash else t.amberWash)
            .border(1.dp, t.lineStrong, shape)
            .drawBehind { drawRect(edge, Offset.Zero, Size(3.dp.toPx(), size.height)) }
            .padding(start = 3.dp + 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) { if (status) liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (icon) Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = edge, modifier = Modifier.padding(top = 1.9.dp).size(14.dp))
        androidx.compose.foundation.layout.FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

// --- Bands -----------------------------------------------------------------------------------

/** `.ti-band`: one hairline above, the uppercase title and its printed provenance/scope. */
@Composable
private fun Band(title: String, aside: String?, modifier: Modifier = Modifier, description: String = title, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = Ti.bandGap)
            .topRule(t.line)
            .padding(top = Ti.bandGap)
            .semantics { contentDescription = description },
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title.uppercase(), style = capsStyle(0.66f), color = t.muted, modifier = Modifier.weight(1f).semantics { heading() }.originalWords(title))
            aside?.let { Text(it, style = cssText(type.mono, 0.7f, 400), color = t.faint, maxLines = 1, softWrap = false) }
        }
        content()
    }
}

/** `.ti-gauge`: label | track | number, the caption under the track. [GaugeRow.percent] null: a dashed hairline. */
@Composable
private fun Gauge(row: GaugeRow) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val tone = usageTone(row.percent)
    val valueColor = when (tone) {
        UsageTone.High -> t.warning
        UsageTone.Critical -> t.danger
        UsageTone.None -> t.white
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.8.dp).semantics(mergeDescendants = true) {}.testTag(InspectorTags.Gauge)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ti.gap)) {
            Text(row.label, style = cssText(type.ui, 0.82f, 620), color = t.ink, maxLines = 1, softWrap = false, modifier = Modifier.width(Ti.label))
            if (row.percent == null) {
                val dash = t.lineStrong
                Canvas(Modifier.weight(1f).height(1.dp)) {
                    drawLine(dash, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
                }
            } else {
                UsageTrack(row.percent, row.label, Modifier.weight(1f), placement = UsageTrackPlacement.Panel)
            }
            Text(row.value, style = cssText(type.mono, 0.88f, 620), color = valueColor, textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = Ti.value))
        }
        Text(
            row.caption,
            style = cssText(type.ui, 0.7f, 400, lineHeight = 1.35f),
            color = t.muted,
            modifier = Modifier.padding(start = Ti.label + Ti.gap, end = Ti.value + Ti.gap, top = 2.4.dp),
        )
    }
}

/** `.ti-ledger`: right-aligned mono numbers; sub rows indented on a rule. */
@Composable
private fun Ledger(rows: List<LedgerRow>, compact: Boolean = false) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            val rule = t.lineStrong
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (row.sub) {
                            Modifier.padding(start = 3.2.dp).drawBehind { drawRect(rule, Offset.Zero, Size(1.dp.toPx(), size.height)) }.padding(start = 1.dp + 11.2.dp, top = 2.4.dp, bottom = 2.4.dp)
                        } else {
                            Modifier.padding(vertical = if (compact) 1.6.dp else 4.8.dp)
                        },
                    )
                    .semantics(mergeDescendants = true) {}
                    .testTag(InspectorTags.LedgerRow),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // `.ti-ledger > div { align-items: baseline }`: the number sits on the label's first
                // line (a flex column's baseline is its first line's), never at the note's level.
                Column(Modifier.weight(1f).alignByBaseline()) {
                    val labelStyle = when {
                        compact -> cssText(type.ui, 0.68f, 520)
                        row.sub -> cssText(type.ui, 0.72f, 520)
                        else -> cssText(type.ui, 0.82f, 620)
                    }
                    Text(row.label, style = labelStyle, color = if (compact || row.sub) t.muted else t.ink)
                    row.note?.let { Text(it, style = cssText(type.ui, 0.64f, 500), color = t.muted) }
                }
                val valueStyle = when {
                    compact -> cssText(type.mono, 0.7f, 520)
                    row.sub -> cssText(type.mono, 0.74f, 520)
                    else -> cssText(type.mono, 0.88f, 620)
                }
                Text(row.value, style = valueStyle, color = if (compact || row.sub) t.ink else t.white, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline().testTag(InspectorTags.LedgerValue))
            }
        }
    }
}

/** `.ti-note`. */
@Composable
private fun TiNote(text: String) {
    Text(
        text,
        style = cssText(LocalTetherTypography.current.ui, 0.7f, 400, lineHeight = 1.45f),
        color = LocalTetherTokens.current.muted,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

/** `.ti-row`: label | value | (action), the note under the value. */
@Composable
private fun TiRow(label: String, value: String, note: String?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Ti.gap),
    ) {
        Text(label, style = cssText(type.ui, 0.74f, 620), color = t.ink, modifier = Modifier.width(Ti.label))
        Column(Modifier.weight(1f)) {
            Text(value, style = cssText(type.ui, 0.74f, 600), color = t.white)
            note?.let { Text(it, style = cssText(type.ui, 0.66f, 400), color = t.muted, modifier = Modifier.padding(top = 1.6.dp)) }
        }
    }
}

// --- 2. Context · 3. Limits ------------------------------------------------------------------

@Composable
private fun ContextBandView(band: ContextBand) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Band("Context", band.aside, Modifier.testTag(InspectorTags.Context)) {
        if (band.awaiting) {
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text("Telemetry appears after the agent completes its first response.", style = cssText(type.ui, 0.8f, 400, lineHeight = 1.45f), color = t.ink)
                Text(
                    "Context, account limits and token use are measured here once it does — nothing is estimated before then.",
                    style = cssText(type.ui, 0.68f, 400, lineHeight = 1.45f),
                    color = t.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        band.gauge?.let { Gauge(it) }
        if (band.noWindow) TiNote("This engine has not reported a context window.")
    }
}

@Composable
private fun LimitsBand(limits: LimitsSection, onUseCodexReset: (() -> Unit)?) {
    Band("Limits", "This account", Modifier.testTag(InspectorTags.Limits), description = "Account limits") {
        limits.gauges.forEach { Gauge(it) }
        limits.resetGrants?.let { (headline, note) -> TiRow("Limit resets", headline, note) }
        // `.ti-row.codex-reset-credit-row-inline` (inspector.tsx:755-770): the count, then "Use reset",
        // which opens the shared Codex confirmation with this session's id, as the web's does.
        limits.bankedResets?.let { count ->
            if (onUseCodexReset == null) {
                TiRow("Banked resets", count, null)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Ti.gap)) {
                    Box(Modifier.weight(1f)) { TiRow("Banked resets", count, null) }
                    com.tether.app.ui.components.TetherKey(
                        onClick = onUseCodexReset,
                        classes = com.tether.app.ui.components.KeyClasses.ButtonSecondary,
                        label = "Use reset",
                        modifier = Modifier.testTag(InspectorTags.UseCodexReset),
                    )
                }
            }
        }
        if (limits.noReading) TiNote("No current 5-hour or weekly reading for this account.")
    }
}

// --- 4. Subagents ----------------------------------------------------------------------------

@Composable
private fun SubagentsBandView(band: SubagentsBand, onSelect: (String?) -> Unit) {
    Band("Subagents", band.aside, Modifier.testTag(InspectorTags.Subagents)) {
        RunList(band.head, onSelect)
        if (band.rest.isNotEmpty()) {
            var open by rememberSaveable(band.moreOpen) { mutableStateOf(band.moreOpen) }
            TiDisclosure(
                summary = "Show ${band.rest.size} more",
                open = open,
                onToggle = { open = !open },
                modifier = Modifier.testTag(InspectorTags.RunsMore),
                topMargin = 4.dp,
                summaryColor = LocalTetherTokens.current.muted,
                summarySize = 0.7f,
                summaryStart = 8.dp,
            ) { RunList(band.rest, onSelect) }
        }
        band.partialNote?.let { TiNote(it) }
    }
}

@Composable
private fun RunList(rows: List<RunRow>, onSelect: (String?) -> Unit) {
    val tint = LocalTetherTokens.current.tintLine
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(tint))
            RunRowView(row) { onSelect(row.runId) }
        }
    }
}

/** `.ti-run-row`: title and status · tokens, then model · effort with their provenance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunRowView(row: RunRow, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    val accent = t.violetStrong
    val statusInk = if (row.error) t.danger else t.muted
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Ti.target)
            .clip(shape)
            .then(
                if (row.active) {
                    Modifier.background(t.css.violetSoft).drawBehind { drawRect(accent, Offset.Zero, Size(2.dp.toPx(), size.height)) }
                } else {
                    Modifier
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { selected = row.active }
            .padding(start = if (row.nested) 20.dp else 8.dp, end = 8.dp, top = 7.2.dp, bottom = 7.2.dp)
            .testTag(InspectorTags.RunRow),
        verticalArrangement = Arrangement.spacedBy(3.2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.4.dp)) {
                if (row.nested) Icon(TetherIcons.CornerDownRight, contentDescription = "Nested run", tint = t.muted, modifier = Modifier.size(11.dp))
                val glyphTint = if (row.error) t.danger else t.muted
                when (row.status) {
                    RUN_RUNNING -> SpinningIcon(TetherIcons.Loader, glyphTint, 12.dp)
                    RUN_ERROR -> Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = glyphTint, modifier = Modifier.size(12.dp))
                    else -> Icon(TetherIcons.Check, contentDescription = null, tint = glyphTint, modifier = Modifier.size(12.dp))
                }
                RuledText(listOf(row.title), cssText(type.ui, 0.82f, 620), t.white, maxLines = 1)
            }
            Text(
                buildAnnotatedString {
                    appendSeg(row.statusText, t)
                    append(" · ")
                    if (row.tokens != null) {
                        withStyle(SpanStyle(fontFamily = type.mono)) { append(row.tokens) }
                    } else {
                        withStyle(SpanStyle(color = t.faint)) { append("no usage") }
                    }
                },
                style = cssText(type.ui, 0.66f, 400),
                color = statusInk,
                maxLines = 1,
                softWrap = false,
            )
        }
        val noteStyle = SpanStyle(color = t.faint, fontFamily = type.ui, fontSize = (0.6f * 16).sp)
        val missing = SpanStyle(color = t.faint, fontFamily = type.ui)
        val settings: @Composable () -> Unit = {
            Text(
                buildAnnotatedString {
                    fun setting(s: RunSetting?, absent: String, alwaysNote: Boolean) {
                        if (s == null) {
                            withStyle(missing) { append(absent) }
                            return
                        }
                        appendSeg(s.text, t)
                        if (s.note.isNotEmpty() || (alwaysNote && s.note.isNotEmpty())) withStyle(noteStyle) { append(" ${s.note}") }
                    }
                    setting(row.model, "model not captured", false)
                    append(" · ")
                    setting(row.effort, "effort not set", true)
                },
                style = cssText(type.mono, 0.72f, 400),
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.4.dp),
            )
        }
        // inspector.tsx:471 `title`: the reason an effort is missing, on a long-press (the web's hover).
        val reason = row.effortMissingReason
        if (reason != null) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(reason) } },
                state = rememberTooltipState(),
                modifier = Modifier.testTag(InspectorTags.EffortMissingReason),
                content = { settings() },
            )
        } else {
            settings()
        }
    }
}

/** inspector.tsx:285-348: the Sub-agent band, directly under the row that was selected. */
@Composable
private fun RunUsageBand(usage: RunUsage, onShowSession: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Band("Sub-agent", usage.status, Modifier.testTag(InspectorTags.RunUsage)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Icon(TetherIcons.Bot, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
            RuledText(listOf(usage.title), cssText(type.ui, 0.78f, 400), t.ink, Modifier.weight(1f), maxLines = 1)
            Box(
                Modifier
                    .heightIn(min = Ti.target)
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
        Ledger(usage.ledger)
        usage.gap?.let { TiNote(it) }
        if (usage.specs.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Specs(usage.specs)
        }
    }
}

/** `.section-label.ti-scope-divider` (Studio: sentence case, 0.75rem). */
@Composable
private fun SessionDivider() {
    val t = LocalTetherTokens.current
    Text(
        "Session",
        style = cssText(LocalTetherTypography.current.ui, 0.75f, 720),
        color = t.faint,
        modifier = Modifier.padding(top = Ti.bandGap).semantics { heading() }.testTag(InspectorTags.SessionDivider),
    )
}

// --- 6. Tokens -------------------------------------------------------------------------------

@Composable
private fun TokensBandView(band: TokensBand) {
    val t = LocalTetherTokens.current
    Band("Tokens", "This session", Modifier.testTag(InspectorTags.Tokens)) {
        Ledger(band.rows)
        if (band.perModel.isNotEmpty()) {
            var open by rememberSaveable { mutableStateOf(false) }
            TiDisclosure("By model, last turn", open, { open = !open }, Modifier.testTag(InspectorTags.PerModel), count = band.perModelCount) {
                Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    band.perModel.forEach { ModelUsageItem(it) }
                }
            }
        }
    }
}

/** `.ti-model-usage`: the model, who used it, its provider, the exact counters. */
@Composable
private fun ModelUsageItem(row: ModelUsageRow) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val small = cssText(type.ui, 0.64f, 400, lineHeight = 1.4f)
    Column(
        Modifier
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(t.radiusSm), t.graphiteRaised, CssBorder(1.dp, t.line))
            .padding(1.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(1.9.dp),
    ) {
        RuledText(listOf(row.identity), cssText(type.mono, 0.72f, 700), t.white)
        if (row.contributors.isNotEmpty()) {
            val joined = buildList { row.contributors.forEachIndexed { i, seg -> if (i > 0) add(app(", ")); add(seg) } }
            RuledText(joined, small, t.muted)
        }
        RuledText(row.provider, small, t.muted)
        Spacer(Modifier.height(4.dp))
        Ledger(row.ledger, compact = true)
    }
}

// --- Specs -----------------------------------------------------------------------------------

/** `.ti-specs`: label | mono value, its notes under the value. */
@Composable
private fun Specs(rows: List<SpecRow>) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { rows.forEach { SpecItem(it) } }
}

@Composable
private fun SpecItem(row: SpecRow) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val valueStyle = cssText(type.mono, 0.72f, 400)
    val noteStyle = cssText(type.ui, 0.64f, 400, lineHeight = 1.45f)
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(InspectorTags.RuntimeRow),
        horizontalArrangement = Arrangement.spacedBy(Ti.gap),
    ) {
        Text(row.label, style = cssText(type.ui, 0.7f, 620), color = t.muted, modifier = Modifier.width(Ti.label).padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.2.dp)) {
            if (row.value.isNotEmpty()) {
                val value = if (row.capitalize) row.value.map { if (it.rule == Rule.Label || it.rule == Rule.App) it.copy(text = capitalizeWords(it.text)) else it } else row.value
                RuledText(value, valueStyle, t.white)
            }
            row.notes.forEach { note ->
                RuledText(
                    note.line,
                    noteStyle,
                    if (note.warning) t.warning else t.muted,
                    if (note.status) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                )
            }
            if (row.names.isNotEmpty()) {
                var open by rememberSaveable { mutableStateOf(false) }
                Column {
                    Row(
                        Modifier
                            .heightIn(min = Ti.target)
                            .clickable(role = Role.Button) { open = !open }
                            .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed" }
                            .testTag(InspectorTags.Names),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Text("Names", style = cssText(type.ui, 0.68f, 650), color = t.ink) }
                    if (open) row.names.forEach { RuledText(it, noteStyle, t.muted, Modifier.padding(top = 4.dp)) }
                }
            }
        }
    }
}

/** CSS `text-transform: capitalize`: the first letter of each word. */
internal fun capitalizeWords(text: String): String =
    text.split(' ').joinToString(" ") { w -> w.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() } }

// --- Disclosures -----------------------------------------------------------------------------

/** `.ti-disclosure > summary::before`: a 0.32rem border corner, pointing right closed and down open. */
@Composable
private fun Caret(open: Boolean, tint: Color) {
    Canvas(Modifier.size(8.dp).rotate(if (open) 90f else 0f)) {
        val arm = 5.12.dp.toPx() * 0.72f
        val stroke = 1.5.dp.toPx()
        val tip = Offset(size.width / 2 + arm / 2, size.height / 2)
        drawLine(tint, Offset(tip.x - arm, tip.y - arm), tip, stroke, cap = StrokeCap.Square)
        drawLine(tint, Offset(tip.x - arm, tip.y + arm), tip, stroke, cap = StrokeCap.Square)
    }
}

/** `.ti-disclosure`: a ruled 44dp summary with a caret and an optional faint count at the end. */
@Composable
private fun TiDisclosure(
    summary: String,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    count: String? = null,
    topMargin: Dp = 10.dp,
    rule: Color = LocalTetherTokens.current.tintLine,
    summaryColor: Color = LocalTetherTokens.current.ink,
    summarySize: Float = 0.74f,
    summaryStart: Dp = 0.dp,
    caps: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(Modifier.fillMaxWidth().padding(top = topMargin).topRule(rule).padding(top = 1.dp)) {
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = Ti.target)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(start = summaryStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Caret(open, t.muted)
            Text(
                if (caps) summary.uppercase() else summary,
                style = if (caps) capsStyle(0.66f) else cssText(type.ui, summarySize, 650),
                color = summaryColor,
                modifier = Modifier.weight(1f).originalWords(summary),
            )
            count?.let { Text(it, style = cssText(type.mono, 0.64f, 500), color = t.faint, maxLines = 1, softWrap = false) }
        }
        if (open) content()
    }
}

// --- Repository ------------------------------------------------------------------------------

/** repository-panel.tsx, flattened into the band rhythm (telemetry-panel.css 327-328). */
@Composable
private fun RepositoryPanel(
    repo: RepositorySection,
    fileDiffs: Map<String, ServerMessage.GitDiffFile>?,
    onRequestFileDiff: (String) -> Unit,
    onRefresh: () -> Unit,
    onRequestDraft: (String) -> Unit,
) {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Ti.bandGap)
            .topRule(t.line)
            .padding(top = Ti.bandGap)
            .semantics { contentDescription = "Repository" }
            .testTag(InspectorTags.Repository),
    ) {
        Text("REPOSITORY", style = capsStyle(0.66f), color = t.muted, modifier = Modifier.padding(bottom = 10.dp).semantics { heading() }.originalWords("Repository"))
        repo.branch?.let { branch ->
            RepoLine(TetherIcons.GitBranch, listOf(branch), repo.divergence, code = true)
        }
        if (repo.canDraft) DraftActions(onRequestDraft)
        repo.pullRequest?.let { pr -> PullRequestRow(pr, onRefresh) }
        repo.changes?.let { diff ->
            var open by rememberSaveable { mutableStateOf(false) }
            TiDisclosure("Changes", open, { open = !open }, Modifier.testTag(InspectorTags.Changes), count = repo.changesCount, rule = t.line) {
                Spacer(Modifier.padding(top = t.css.spaceXs))
                GitChangesCard(diff, fileDiffs, onRequestFile = onRequestFileDiff)
            }
        }
    }
}

/**
 * repository-panel.tsx:53-61: the headline (a link to the pull request when the reply carries its
 * address, opening on one tap like the web's `<a target="_blank">` and a chat link), its state, and
 * the refresh key. The refresh shows nothing of its own: the reply replaces the line (an `unknown`
 * one reads "PR status unavailable"), as on the web. ta-coik.18: the address is any the web links
 * ([pullRequestHref]); one no app on the phone takes fails as the browser's would, said under the line.
 */
@Composable
private fun PullRequestRow(pr: PullRequestLine, onRefresh: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val context = LocalContext.current
    val opener = LocalLinkOpener.current
    val style = cssText(type.ui, 0.72f, 400)
    var unopened by remember(pr.url) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(bottom = t.css.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Icon(TetherIcons.GitPullRequest, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            val url = pr.url
            if (url != null) {
                Text(
                    pr.headline,
                    style = style.copy(textDecoration = TextDecoration.Underline),
                    color = t.ink,
                    modifier = Modifier
                        .clickable(role = Role.Button, onClickLabel = "Open in browser") {
                            unopened = !openPullRequestLink(context, opener, url, t.graphite)
                        }
                        .testTag(InspectorTags.PullRequestLink),
                )
            } else {
                Text(pr.headline, style = style, color = t.muted)
            }
            pr.state?.let { Text(it, style = cssText(type.ui, 0.65f, 400), color = t.muted, modifier = Modifier.padding(top = t.css.spaceXs)) }
        }
        IconKey(TetherIcons.RefreshCw, "Refresh pull request status", Modifier.testTag(InspectorTags.RefreshPullRequest), onRefresh)
    }
    if (unopened) {
        Text(
            PULL_REQUEST_UNOPENED,
            style = cssText(type.mono, 0.7f, 400, lineHeight = 1.4f),
            color = t.warning,
            modifier = Modifier
                .padding(bottom = t.css.spaceMd)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(InspectorTags.PullRequestUnopened),
        )
    }
}

/**
 * T8.5 repository-panel.tsx:40-51 (`.repository-draft-actions`, globals.css 10677-10699): the two
 * draft keys, one under the other. A tap sends at once (no confirmation, as on the web); the reply
 * opens the shell's metadata draft panel.
 */
@Composable
private fun DraftActions(onRequestDraft: (String) -> Unit) {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = t.css.spaceMd)
            .semantics { contentDescription = "Generate a draft" }
            .testTag(InspectorTags.DraftActions),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        DraftKey(TetherIcons.GitCommitHorizontal, "Draft commit message", "From staged changes", Modifier.testTag(InspectorTags.DraftCommitMessage)) {
            onRequestDraft("commitMessage")
        }
        DraftKey(TetherIcons.GitPullRequest, "Draft pull request", "Title and description to review", Modifier.testTag(InspectorTags.DraftPullRequest)) {
            onRequestDraft("pullRequest")
        }
    }
}

/** One `.repository-draft-actions button`: the glyph, the strong label over its small line; 3rem tall. */
@Composable
private fun DraftKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, detail: String, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusKey)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(t.keyFace, shape)
            .border(1.dp, t.keySide, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd),
    ) {
        Icon(icon, contentDescription = null, tint = t.muted, modifier = Modifier.size(17.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
            Text(label, style = cssText(type.ui, 0.74f, 650), color = t.ink)
            Text(detail, style = cssText(type.ui, 0.64f, 400, lineHeight = 1.4f), color = t.muted)
        }
    }
}

/** An `.icon-button` / `.actions button`: a 14dp glyph in a 44dp target, named by [label]. */
@Composable
private fun IconKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    Box(
        modifier
            .size(Ti.target)
            .clip(RoundedCornerShape(t.radiusSm))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
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

// --- Services --------------------------------------------------------------------------------

/**
 * worktree-services-card.tsx: the MCP card's frame, rows in words. Each script has the web's keys
 * (worktree-services-card.tsx:110-131): Restart and Stop while it runs or starts, Run otherwise, and
 * "Output of" toggling its log view; none asks first, as on the web. A running service's links
 * (141-154) open on a tap: "Open" through [serviceOpen] (the app's sign-in), "On this machine"
 * straight to [opener].
 */
@Composable
private fun ServicesCard(
    services: ServicesSection,
    opener: LinkOpener,
    serviceOpen: ServiceOpenSource,
    onControl: (name: String, action: String) -> Unit,
    onRequestLogs: (name: String) -> Unit,
) {
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
            Text(services.count.uppercase(), style = cssText(type.mono, 0.68f, 650, trackingEm = 0.04f), color = t.muted, textAlign = TextAlign.End, modifier = Modifier.originalWords(services.count))
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
        // ta-m7ef (worktree-services-card.tsx 1bf4a465): why the setup / teardown did not run, as problems.
        services.skipped.forEachIndexed { i, sentence ->
            RuledText(
                listOf(Seg(sentence, Rule.App)),
                setupStyle,
                t.warning,
                Modifier.fillMaxWidth().topRule(t.line).padding(top = 1.dp).padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm).semantics { liveRegion = LiveRegionMode.Polite }.testTag(InspectorTags.serviceSkipped(i)),
            )
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
                // worktree-services-card.tsx:52, 63-70: one log view open at a time; opening one asks for its output.
                var openLog by rememberSaveable { mutableStateOf<String?>(null) }
                services.scripts.forEachIndexed { i, row ->
                    val last = i == services.scripts.lastIndex
                    val name = row.scriptName
                    ServiceItem(
                        row,
                        Modifier.then(if (last) Modifier else Modifier.bottomRule(tint)),
                        opener,
                        serviceOpen,
                        onControl = { action -> onControl(name, action) },
                        logOpen = openLog == name,
                        onToggleLog = {
                            if (openLog == name) {
                                openLog = null
                            } else {
                                openLog = name
                                onRequestLogs(name)
                            }
                        },
                        log = services.logs?.takeIf { it.name == name }?.output,
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceItem(
    row: ServiceRow,
    modifier: Modifier,
    opener: LinkOpener,
    serviceOpen: ServiceOpenSource,
    onControl: (action: String) -> Unit,
    logOpen: Boolean,
    onToggleLog: () -> Unit,
    /** The output of this script's last `worktree-logs` reply; null = none for it, or no lines. */
    log: Seg?,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val problem = if (row.failed) t.warning else t.muted
    val mono = cssText(type.mono, 0.7f, 400, lineHeight = 1.4f)
    Column(
        modifier.fillMaxWidth().padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(3.2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm), verticalAlignment = Alignment.CenterVertically) {
            // `.actions { margin-left: auto }`: the name and status take the room, the keys sit at the end.
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
                RuledText(listOf(row.name), cssText(type.mono, 0.76f, 400), t.ink, Modifier.weight(1f, fill = false), maxLines = 1)
                Text(row.status, style = cssText(type.ui, 0.76f, 400), color = problem)
            }
            val label = row.name.text
            Row(horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
                if (row.running) {
                    IconKey(TetherIcons.RotateCcw, "Restart $label") { onControl("restart") }
                    IconKey(TetherIcons.Square, "Stop $label") { onControl("stop") }
                } else {
                    IconKey(TetherIcons.Play, "Run $label") { onControl("start") }
                }
                IconKey(
                    TetherIcons.Terminal,
                    "Output of $label",
                    Modifier.semantics { stateDescription = if (logOpen) "Expanded" else "Collapsed" },
                    onToggleLog,
                )
            }
        }
        RuledText(listOf(row.command), mono, t.muted)
        row.address?.let { RuledText(listOf(app("Own address "), it), mono, t.ink) }
        if (row.open != null || row.local != null) ServiceLinks(row.open, row.local, opener, serviceOpen)
        row.unavailable?.let { Text(it, style = mono, color = t.warning, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        row.error?.let { RuledText(listOf(it), mono, t.warning, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        // `.log`: at most 12rem tall, scrolled within, from the top; the web's copy when there is nothing.
        if (logOpen) {
            Box(Modifier.fillMaxWidth().padding(top = 3.2.dp).heightIn(max = 192.dp).verticalScroll(rememberScrollState()).testTag(InspectorTags.ScriptLog)) {
                RuledText(listOf(log ?: app("No output yet.")), cssText(type.mono, 0.68f, 400, lineHeight = 1.5f), t.muted)
            }
        }
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
                        when (val outcome = serviceOpen.open(link.url, link.serviceUrl)) {
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

// --- 7. Runtime and capabilities -------------------------------------------------------------

/** `.ti-disclosure.ti-runtime`: collapsed; its summary carries which CLI answered. */
@Composable
private fun RuntimeDisclosure(runtime: RuntimeSection) {
    val t = LocalTetherTokens.current
    var open by rememberSaveable { mutableStateOf(false) }
    TiDisclosure(
        "Runtime",
        open,
        { open = !open },
        Modifier.testTag(InspectorTags.Runtime),
        count = runtime.cli?.let { "CLI ${it.text}" },
        topMargin = Ti.bandGap,
        rule = t.line,
        summaryColor = t.muted,
        caps = true,
    ) {
        if (runtime.rows.isNotEmpty()) Specs(runtime.rows)
    }
}

@Composable
private fun AcpCapabilities(rows: List<Pair<String, Boolean>>) {
    Band("Capabilities", "advertised", Modifier.testTag(InspectorTags.Capabilities), description = "ACP agent capabilities") {
        Specs(rows.map { (label, on) -> SpecRow(label, listOf(app(if (on) "yes" else "no")), capitalize = true) })
    }
}
