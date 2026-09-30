package com.tether.app.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquareText
import com.composables.icons.lucide.CirclePlay
import com.composables.icons.lucide.OctagonPause
import com.tether.app.protocol.model.OverviewActivity
import com.tether.app.protocol.model.OverviewCard
import com.tether.app.protocol.model.OverviewPending
import com.tether.app.protocol.model.OverviewPendingPanel
import com.tether.app.protocol.overview.OverviewClient
import com.tether.app.protocol.overview.OverviewClientState
import com.tether.app.protocol.overview.OverviewPhase
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.FreshnessCopy
import com.tether.app.ui.components.FreshnessPill
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherSelect
import com.tether.app.ui.components.TetherSelectOption
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.ProviderLogo
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.text.codeText
import com.tether.app.ui.theme.CssLineHeight
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherTokens

/** Test tags (stable hooks for behaviour tests and screenshots). */
object OverviewTags {
    const val Root = "overview"
    const val NewSession = "overview-new-session"
    const val Stale = "overview-stale"
    const val Pending = "overview-pending"
    const val Activity = "overview-activity"
    const val Empty = "overview-empty"
    const val Loading = "overview-loading"
    const val HostUsage = "overview-host-usage"
    const val Announcement = "overview-announcement"
    fun card(sessionId: String) = "overview-card:$sessionId"
    fun open(sessionId: String) = "overview-open:$sessionId"
    fun review(sessionId: String) = "overview-review:$sessionId"
    fun reviewPending(sessionId: String, requestId: String) = "overview-review-pending:$sessionId:$requestId"
    fun activityRow(id: String) = "overview-activity-row:$id"
    fun tab(tab: StatusTab) = "overview-tab:${tab.key}"
}

/**
 * Server/agent text as a single displayed line: bidi and invisible code points out, bounded (T6.4
 * L5); a title of invisibles spelled out (ta-28i). Workspace names and paths are the one-line code
 * rule instead ([codeLabel] / [SafeText.line]), as the sidebar draws them.
 */
private fun label(text: String?): String = OverviewPresentation.label(text)
private fun title(text: String?): String = OverviewPresentation.title(text)
private fun prose(text: String?): String = OverviewPresentation.prose(text)

private fun css(family: FontFamily, rem: Float, weight: Int, trackingEm: Float = 0f, lineHeight: Float? = null) = TextStyle(
    fontFamily = family,
    fontSize = (rem * 16f).sp,
    fontWeight = FontWeight(weight),
    letterSpacing = if (trackingEm == 0f) androidx.compose.ui.unit.TextUnit.Unspecified else trackingEm.em,
    lineHeight = lineHeight?.em ?: androidx.compose.ui.unit.TextUnit.Unspecified,
    lineHeightStyle = CssLineHeight,
)

/**
 * T15.2: components/overview/overview.tsx — every managed session on this node at a glance,
 * attention first, with what needs the operator pulled out into its own panel. Stateless over
 * [state] (the T15.1 feed) and the filter [choice]; [OverviewHost] owns the subscription.
 *
 * READ-ONLY by construction: nothing here attaches a session, marks one seen, focuses a composer or
 * sends anything an agent would act on. The buttons hand off to [actions], the shell's existing
 * open / review / new-session / event-log handlers, which the operator triggers deliberately.
 *
 * Phone order (overview.module.css narrow screens): header + counts, filters, the stale note,
 * pending attention, session cards (1 / 2 / 4 columns at <40rem / <80rem / wider), pagination,
 * recent activity, then the host & usage slot (T15.3), then the footer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewScreen(
    state: OverviewClientState,
    connected: Boolean,
    choice: OverviewChoice,
    onChoice: (OverviewChoice) -> Unit,
    page: Int,
    onPage: (Int) -> Unit,
    now: Long,
    actions: OverviewActions,
    modifier: Modifier = Modifier,
    /** The polite announcement of requests that arrived while the operator watches (overview.tsx:349). */
    announcement: String = "",
    /** T15.3: the "Host & usage" tile (host readings and daily token usage). Not built yet: nothing renders. */
    hostUsage: (@Composable () -> Unit)? = null,
) {
    val t = LocalTetherTokens.current
    val data = state.data
    val counts = data?.counts
    val offline = OverviewPresentation.offline(state, connected)
    val live = OverviewPresentation.live(state, connected)
    val cards = OverviewClient.stableCardOrder(null, data?.cards)

    // `.page { background: var(--mineral) }`.
    BoxWithConstraints(modifier.fillMaxSize().background(t.mineral).testTag(OverviewTags.Root)) {
        val narrow = maxWidth < 768.dp
        val columns = when {
            maxWidth >= 1280.dp -> 4
            maxWidth >= 640.dp -> 2
            else -> 1
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (narrow) 14.dp else 32.dp, vertical = if (narrow) 17.6.dp else 28.dp),
            verticalArrangement = Arrangement.spacedBy(if (narrow) 16.dp else 20.dp),
        ) {
            Header(counts?.let { OverviewPresentation.countsLine(it, offline) } ?: OverviewPresentation.countsLine(null, offline), choice.filtered, narrow, actions.onNewSession)
            Toolbar(state, choice, onChoice, narrow)
            if (offline && data != null) StaleNote(state.updatedAt, now)

            PendingPanel(
                panel = data?.pending,
                now = now,
                filtered = choice.filtered,
                onReview = actions.onReviewRequest,
                onShowOutside = { onChoice(choice.copy(workspace = null, provider = null)) },
            )

            // `.cardsArea` (its "Sessions" heading is visually hidden on the web; each card title is a heading here).
            when {
                data == null -> SkeletonGrid(columns)
                OverviewPresentation.noSessionsAtAll(data.facets) -> NoSessions(actions.onNewSession)
                cards.isEmpty() -> EmptyPage(OverviewPresentation.emptyPage(choice, counts), choice, onChoice)
                else -> CardGrid(cards, columns, now, offline, state.updatedAt, actions)
            }
            if (data != null && data.pageCount > 1) {
                Pagination(data.page, data.pageCount, cards.size, data.totalCards, onPage)
            }

            ActivityPanel(state.activity, data?.activitySince?.takeIf { it > 0 }, live, actions.onOpenSession, actions.onOpenEventLog)

            hostUsage?.let { slot -> Box(Modifier.fillMaxWidth().testTag(OverviewTags.HostUsage)) { slot() } }

            Footer(state, offline, now)
        }
        // overview.tsx:349 — polite, atomic, invisible.
        Box(
            Modifier
                .size(1.dp)
                .testTag(OverviewTags.Announcement)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = announcement
                },
        )
    }
}

// ── Header ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(countsLine: List<String>, filtered: Boolean, narrow: Boolean, onNewSession: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                "Overview",
                style = css(type.ui, if (narrow) 1.75f else 2.25f, 780, trackingEm = -0.04f, lineHeight = 1.1f),
                color = t.white,
                modifier = Modifier.semantics { heading() },
            )
            FlowRow(
                Modifier.padding(top = 5.6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.2.dp),
            ) {
                val style = css(type.ui, if (narrow) 0.9f else 1f, 400).copy(fontFeatureSettings = "tnum")
                countsLine.forEachIndexed { index, part ->
                    if (index > 0) Text("·", style = style, color = t.faint, modifier = Modifier.clearAndSetSemantics { })
                    Text(part, style = style, color = t.muted)
                }
                if (filtered && countsLine.size > 1) Text("(filtered)", style = css(type.ui, 0.85f, 400), color = t.faint)
            }
        }
        TetherKey(
            onClick = onNewSession,
            classes = KeyClasses.ButtonPrimary,
            label = "New session",
            icon = TetherIcons.Plus,
            iconSize = 17.dp,
            contentPadding = if (narrow) 14.4.dp else 20.dp,
            modifier = Modifier.testTag(OverviewTags.NewSession),
        )
    }
}

// ── Toolbar: filters and status tabs ────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Toolbar(state: OverviewClientState, choice: OverviewChoice, onChoice: (OverviewChoice) -> Unit, narrow: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val facets = state.data?.facets
    // overview.tsx:161-176 — the facet options, plus a remembered choice that has no sessions now.
    val workspaceOptions = buildList {
        add(TetherSelectOption("", "All workspaces"))
        facets?.workspaces?.forEach {
            val name = OverviewPresentation.capped(it.label, OverviewPresentation.LABEL_CHARS)
            add(TetherSelectOption(it.key, SafeText.line(name), description = SafeText.line(OverviewPresentation.capped(it.key, OverviewClient.FILTER_CHARS))))
        }
        choice.workspace?.takeIf { w -> facets?.workspaces?.none { it.key == w } != false }?.let { w ->
            add(TetherSelectOption(w, SafeText.line(w.split("/").lastOrNull { it.isNotEmpty() } ?: w), description = "${SafeText.line(w)} — no sessions now"))
        }
    }
    val providerOptions = buildList {
        add(TetherSelectOption("", "All providers"))
        facets?.providers?.forEach { add(TetherSelectOption(it.key, label(it.label), tag = if (it.profileId != null) "profile" else null)) }
        choice.provider?.takeIf { p -> facets?.providers?.none { it.key == p } != false }?.let { p ->
            add(TetherSelectOption(p, label(p), description = "No sessions now"))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(
            Modifier.fillMaxWidth().semantics { contentDescription = "Filter sessions" },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TetherSelect(
                options = workspaceOptions,
                selectedValue = choice.workspace.orEmpty(),
                onSelect = { onChoice(choice.copy(workspace = it.value.ifEmpty { null })) },
                contentDescription = "Workspace",
                modifier = if (narrow) Modifier.weight(1f) else Modifier,
            )
            TetherSelect(
                options = providerOptions,
                selectedValue = choice.provider.orEmpty(),
                onSelect = { onChoice(choice.copy(provider = it.value.ifEmpty { null })) },
                contentDescription = "Provider",
                modifier = if (narrow) Modifier.weight(1f) else Modifier,
            )
            if (choice.filtered) {
                Row(
                    Modifier
                        .heightIn(min = TetherDimens.touchTargetDp)
                        .clickable(role = Role.Button) { onChoice(choice.copy(workspace = null, provider = null)) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.6.dp),
                ) {
                    Icon(TetherIcons.X, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
                    Text("Clear", style = css(type.ui, 0.8f, 600), color = t.muted)
                }
            }
        }
        // `.statusTabs`: a pressed-state group (aria-pressed), selection carried by weight too.
        FlowRow(
            Modifier
                .fillMaxWidth()
                .cssSurface(RoundedCornerShape(t.radiusSm), t.graphite, CssBorder(1.dp, t.line))
                .padding(3.2.dp)
                .semantics { contentDescription = "Status" },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatusTab.entries.forEach { tab ->
                val count = OverviewPresentation.tabCount(state.data?.counts, tab)
                val pressed = choice.status == tab
                Row(
                    Modifier
                        .then(if (narrow) Modifier.weight(1f) else Modifier)
                        .heightIn(min = TetherDimens.touchTargetDp)
                        .widthIn(min = TetherDimens.touchTargetDp)
                        .cssSurface(RoundedCornerShape(t.radiusSm - 2.4.dp), if (pressed) t.violetWash else Color.Transparent)
                        .clickable(role = Role.Button) { onChoice(choice.copy(status = tab)) }
                        .semantics(mergeDescendants = true) {
                            selected = pressed
                            stateDescription = if (pressed) "Selected" else "Not selected"
                        }
                        .testTag(OverviewTags.tab(tab))
                        .padding(horizontal = 11.2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.4.dp, Alignment.CenterHorizontally),
                ) {
                    val ink = if (pressed) t.violet else t.muted
                    Text(tab.label, style = css(type.ui, 0.8f, if (pressed) 750 else 600), color = ink, maxLines = 1)
                    if (count != null) {
                        Text(
                            "$count",
                            style = css(type.mono, 0.7f, 650),
                            color = ink,
                            modifier = Modifier
                                .cssSurface(CircleShape, t.tintMd)
                                .widthIn(min = 20.dp)
                                .padding(horizontal = 4.8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

// ── Stale ───────────────────────────────────────────────────────────────────

/**
 * overview.tsx:259-264 in the T13.2 idiom (SYNC_DESIGN §4.2): the neutral `history` pill with the
 * saved copy's age, and the web's sentence. Never violet or red: stale is not an error.
 */
@Composable
private fun StaleNote(updatedAt: Long?, now: Long) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val age = FreshnessCopy.age(updatedAt, now)
    Column(
        Modifier
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(t.radiusSm), t.graphiteRaised, CssBorder(1.dp, t.line))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(OverviewTags.Stale),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FreshnessPill(TetherIcons.History, age?.let { "${FreshnessCopy.SAVED} · updated $it" } ?: FreshnessCopy.SAVED)
        Text(
            "Reconnecting — showing the last update" + (updatedAt?.let { " from ${OverviewFormat.clock(it)}" } ?: "") +
                ". Nothing here is live until the link returns.",
            style = css(type.ui, 0.84f, 400, lineHeight = 1.45f),
            color = t.ink,
        )
    }
}

// ── Panels ──────────────────────────────────────────────────────────────────

@Composable
private fun Panel(
    tag: String,
    attention: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val t = LocalTetherTokens.current
    Column(
        modifier
            .fillMaxWidth()
            .cssSurface(
                RoundedCornerShape(t.radiusMd),
                if (attention) t.attentionBg else t.graphite,
                CssBorder(1.dp, if (attention) t.attentionBorder else t.line),
                t.css.shadowRaised,
            )
            .padding(horizontal = 20.dp, vertical = 17.6.dp)
            .testTag(tag),
        content = content,
    )
}

@Composable
private fun PanelTitle(text: String, badge: Int? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.6.dp)) {
        Row(
            Modifier.semantics(mergeDescendants = true) {
                heading()
                contentDescription = if (badge != null && badge > 0) "$text, $badge ${if (badge == 1) "request" else "requests"}" else text
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.6.dp),
        ) {
            Text(text, style = css(type.ui, 1.15f, 720, trackingEm = -0.02f), color = t.white)
            if (badge != null && badge > 0) {
                Box(
                    Modifier.heightIn(min = 24.dp).widthIn(min = 24.dp).cssSurface(CircleShape, t.attentionBorder).padding(horizontal = 6.4.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("$badge", style = css(type.ui, 0.78f, 700), color = t.attentionInk) }
            }
        }
        trailing()
    }
}

/** overview-panels.tsx PendingPanel — "Needs your attention", oldest first, with each request's age. */
@Composable
private fun PendingPanel(
    panel: OverviewPendingPanel?,
    now: Long,
    filtered: Boolean,
    onReview: (String, String) -> Unit,
    onShowOutside: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val total = panel?.total ?: 0
    val items = panel?.items.orEmpty()
    val outside = panel?.outsideFilters ?: 0
    Panel(OverviewTags.Pending, attention = total > 0) {
        PanelTitle("Needs your attention", badge = total)
        when {
            panel == null -> PanelEmpty(null, "Loading requests…")
            items.isEmpty() -> PanelEmpty(TetherIcons.CircleCheck, if (filtered) "Nothing is waiting for you in these filters." else "Nothing is waiting for you.")
            else -> Column(verticalArrangement = Arrangement.spacedBy(14.4.dp)) {
                items.forEachIndexed { index, item ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).cssSurface(RoundedCornerShape(0.dp), t.attentionBorder))
                    PendingItem(item, now, onReview)
                }
            }
        }
        if (total > items.size) {
            Text(
                "${OverviewFormat.plural(total - items.size, "more request")} — open their sessions from the cards.",
                style = css(type.ui, 0.75f, 400),
                color = t.faint,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (outside > 0) {
            Row(
                Modifier
                    .padding(top = 8.dp)
                    .heightIn(min = TetherDimens.touchTargetDp)
                    .clickable(role = Role.Button, onClick = onShowOutside),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.4.dp),
            ) {
                Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.attentionInk, modifier = Modifier.size(14.dp))
                Text("${OverviewFormat.plural(outside, "request")} waiting outside these filters — show all", style = css(type.ui, 0.86f, 650), color = t.attentionInk)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingItem(item: OverviewPending, now: Long, onReview: (String, String) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val age = OverviewPresentation.requestAge(item.createdAt, now)
    val approval = item.kind == "approval"
    val sessionTitle = title(item.title)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.2.dp)) {
            ProviderMark(item.provider)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(if (approval) TetherIcons.ShieldAlert else TetherIcons.CircleHelp, contentDescription = null, tint = t.attentionInk, modifier = Modifier.size(13.dp))
                Text(if (approval) "Approval" else "Question", style = css(type.ui, 0.8f, 650), color = t.attentionInk)
            }
            Text("·", style = css(type.ui, 0.8f, 400), color = t.faint, modifier = Modifier.clearAndSetSemantics { })
            Text(sessionTitle, style = css(type.ui, 0.8f, 400), color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (age != null) {
                Text(
                    OverviewFormat.duration(age),
                    style = css(type.mono, 0.75f, 500),
                    color = t.muted,
                    modifier = Modifier.semantics { contentDescription = "waiting ${OverviewFormat.describeDuration(age)}" },
                )
            }
        }
        Text(
            OverviewPresentation.requestSummary(item),
            style = css(type.ui, 0.98f, 680),
            color = t.white,
            modifier = Modifier.padding(top = 5.6.dp),
        )
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 9.6.dp),
            horizontalArrangement = Arrangement.spacedBy(9.6.dp),
            verticalArrangement = Arrangement.spacedBy(9.6.dp),
        ) {
            item.detail?.takeIf { it.isNotEmpty() }?.let { OverviewPresentation.capped(it, OverviewPresentation.DETAIL_CHARS) }?.let { detail ->
                // A command or path: the code rule (every hidden code point shown as a token).
                // Its 200 characters fit in 8 lines at phone width; the full request is in the session.
                Text(
                    codeText(detail, breakAnywhere = true),
                    style = css(type.mono, 0.78f, 500),
                    color = t.ink,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 120.dp)
                        .cssSurface(RoundedCornerShape(t.radiusSm), t.graphite, CssBorder(1.dp, t.line))
                        .padding(horizontal = 11.2.dp, vertical = 8.8.dp),
                )
            }
            TetherKey(
                onClick = { onReview(item.sessionId, item.requestId) },
                classes = KeyClasses.ButtonSecondary,
                label = "Review in session",
                contentDescription = "Review in session: $sessionTitle",
                modifier = Modifier.testTag(OverviewTags.reviewPending(item.sessionId, item.requestId)),
                trailing = { Arrow(t.ink) },
            )
        }
    }
}

@Composable
private fun PanelEmpty(icon: ImageVector?, text: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon?.let { Icon(it, contentDescription = null, tint = t.muted, modifier = Modifier.size(16.dp)) }
        Text(text, style = css(type.ui, 0.88f, 400), color = t.muted)
    }
}

// ── Cards ───────────────────────────────────────────────────────────────────

@Composable
private fun CardGrid(cards: List<OverviewCard>, columns: Int, now: Long, offline: Boolean, updatedAt: Long?, actions: OverviewActions) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        cards.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { card -> SessionCard(card, now, offline, updatedAt, actions, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun toneColor(t: TetherTokens, tone: CardTone): Color = when (tone) {
    CardTone.Running -> t.running
    CardTone.Waiting, CardTone.Attention -> t.attentionInk
    CardTone.Danger -> t.danger
    CardTone.Ready, CardTone.Ended -> t.muted
}

private fun glyph(g: StatusGlyph): ImageVector = when (g) {
    StatusGlyph.Running -> TetherIcons.CircleDot
    StatusGlyph.Waiting -> TetherIcons.CircleAlert
    StatusGlyph.RateLimit -> TetherIcons.Clock
    StatusGlyph.Auth -> TetherIcons.KeyRound
    StatusGlyph.OutcomeUnknown -> TetherIcons.CircleHelp
    StatusGlyph.Interrupted -> Lucide.OctagonPause
    StatusGlyph.Failed, StatusGlyph.NeedsAttention -> TetherIcons.TriangleAlert
    StatusGlyph.Ready -> TetherIcons.CircleCheck
    StatusGlyph.Ended -> TetherIcons.Square
}

/** overview-card.tsx OverviewSessionCard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(card: OverviewCard, now: Long, offline: Boolean, updatedAt: Long?, actions: OverviewActions, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val status = OverviewPresentation.cardStatus(card)
    val waiting = status.tone == CardTone.Waiting
    val since = OverviewPresentation.stateSince(card)
    val elapsed = since?.let { OverviewPresentation.elapsed(it, now) }
    val request = OverviewPresentation.reviewTarget(card)
    val excerpt = OverviewPresentation.shownExcerpt(card, status)
    val cardTitle = title(card.title)
    // SYNC_DESIGN §4.2: a saved copy never claims an agent is running or waiting on you NOW.
    val qualified = if (offline) {
        FreshnessCopy.qualifiedStatus(if (card.status == "running") "active" else card.status, updatedAt, now)
    } else {
        null
    }
    Column(
        modifier
            .cssSurface(
                RoundedCornerShape(t.radiusMd),
                if (waiting) t.attentionBg else t.graphite,
                CssBorder(1.dp, if (waiting) t.attentionBorder else t.line),
                t.css.shadowRaised,
            )
            .padding(20.dp)
            .testTag(OverviewTags.card(card.sessionId)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.6.dp)) {
            ProviderMark(card.provider)
            Text(label(card.providerLabel), style = css(type.ui, 0.84f, 600), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            cardTitle,
            style = css(type.ui, 1.2f, 720, trackingEm = -0.02f, lineHeight = 1.3f),
            color = t.white,
            modifier = Modifier.padding(top = 13.6.dp).semantics { heading() },
        )
        FlowRow(
            Modifier.padding(top = 5.6.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.2.dp),
        ) {
            val meta = css(type.mono, 0.78f, 500)
            // ta-28i: a workspace's name is code, one line (the sidebar's rule), so a spoof shows.
            Text(
                codeLabel(OverviewPresentation.capped(card.workspace.label, OverviewPresentation.LABEL_CHARS)),
                style = meta,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            card.branch?.takeIf { it.isNotEmpty() }?.let { OverviewPresentation.capped(it, OverviewPresentation.LABEL_CHARS) }?.let { branch ->
                Text("·", style = meta, color = t.faint, modifier = Modifier.clearAndSetSemantics { })
                Row(
                    Modifier.semantics(mergeDescendants = true) { contentDescription = "branch ${SafeText.code(branch)}" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(TetherIcons.GitBranch, contentDescription = null, tint = t.muted, modifier = Modifier.size(12.dp))
                    Text(codeText(branch, breakAnywhere = true), style = meta, color = t.muted)
                }
            }
        }
        Box(Modifier.padding(top = 14.4.dp).fillMaxWidth().height(1.dp).cssSurface(RoundedCornerShape(0.dp), if (waiting) t.attentionBorder else t.line))

        Row(
            Modifier.padding(top = 14.4.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val ink = if (qualified != null) t.muted else toneColor(t, status.tone)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.2.dp)) {
                Icon(if (qualified != null) TetherIcons.History else glyph(status.glyph), contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
                Text(qualified ?: status.label, style = css(type.ui, 0.9f, 650), color = ink)
            }
            if (elapsed != null) {
                Text(
                    OverviewFormat.duration(elapsed),
                    style = css(type.mono, 0.8f, 500).copy(fontFeatureSettings = "tnum"),
                    color = t.muted,
                    modifier = Modifier.semantics { contentDescription = "${OverviewPresentation.durationVerb(card)} ${OverviewFormat.describeDuration(elapsed)}" },
                )
            }
        }
        Column(Modifier.padding(top = 9.6.dp), verticalArrangement = Arrangement.spacedBy(5.6.dp)) {
            val bodyStyle = css(type.ui, 0.92f, 400, lineHeight = 1.5f)
            val detail = status.detail?.takeIf { card.status != "running" }?.let(::prose)?.takeIf { it.isNotEmpty() }
            detail?.let { Text(it, style = bodyStyle, color = t.ink, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            excerpt?.let { shown ->
                val e = shown.copy(text = OverviewPresentation.capped(shown.text, OverviewPresentation.EXCERPT_CHARS))
                val lines = if (detail != null) 2 else 3
                if (e.kind == "tool" || e.kind == "request") {
                    Text(codeText(e.text), style = css(type.mono, 0.8f, 500), color = t.muted, maxLines = lines, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(prose(e.text), style = bodyStyle, color = t.ink, maxLines = lines, overflow = TextOverflow.Ellipsis)
                }
            }
            val note = css(type.mono, 0.8f, 500)
            card.progress?.let { p ->
                Text("${p.done} / ${p.total}" + (p.label?.let { " ${prose(it)}" } ?: ""), style = note, color = t.muted)
            }
            card.spawnedRunsActive?.takeIf { it > 0 }?.let { Text("${OverviewFormat.plural(it, "agent run")} in progress", style = note, color = t.muted) }
        }
        Spacer(Modifier.height(16.dp))
        if (request != null) {
            TetherKey(
                onClick = { actions.onReviewRequest(card.sessionId, request.requestId) },
                classes = KeyClasses.ButtonPrimary,
                label = "Review request",
                contentDescription = "Review request: $cardTitle",
                modifier = Modifier.fillMaxWidth().testTag(OverviewTags.review(card.sessionId)),
                trailing = { Arrow(t.accentInk) },
            )
        } else {
            TetherKey(
                onClick = { actions.onOpenSession(card.sessionId) },
                classes = KeyClasses.ButtonSecondary,
                label = "Open session",
                contentDescription = "Open session: $cardTitle",
                modifier = Modifier.fillMaxWidth().testTag(OverviewTags.open(card.sessionId)),
                trailing = { Arrow(t.ink) },
            )
        }
    }
}

@Composable
private fun RowScope.Arrow(tint: Color) {
    Icon(TetherIcons.ArrowRight, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
}

@Composable
private fun ProviderMark(provider: String) {
    val t = LocalTetherTokens.current
    Box(Modifier.size(22.4.dp), contentAlignment = Alignment.Center) {
        ProviderLogo(provider, color = t.white, markSize = 18.4.dp, letterSize = 12.sp)
    }
}

@Composable
private fun SkeletonGrid(columns: Int) {
    val t = LocalTetherTokens.current
    Column(
        Modifier.clearAndSetSemantics { contentDescription = "Loading sessions" }.testTag(OverviewTags.Loading),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        (0 until 4).chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { _ ->
                    Box(
                        Modifier.weight(1f).height(if (columns == 1) 120.dp else 272.dp)
                            .cssSurface(RoundedCornerShape(t.radiusMd), t.graphite, CssBorder(1.dp, t.line)),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun EmptyBox(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(t.radiusMd), t.graphite, CssBorder(1.dp, t.lineStrong))
            .padding(32.dp)
            .testTag(OverviewTags.Empty),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun EmptyHeading(text: String, hint: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Icon(Lucide.Inbox, contentDescription = null, tint = t.muted, modifier = Modifier.size(26.dp))
    Text(text, style = css(type.ui, 1.1f, 700), color = t.white, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
    if (hint.isNotEmpty()) Text(hint, style = css(type.ui, 0.9f, 400, lineHeight = 1.5f), color = t.muted)
}

/** overview.tsx:283-289. */
@Composable
private fun NoSessions(onNewSession: () -> Unit) = EmptyBox {
    EmptyHeading("No sessions yet", "Start a session and it appears here while it works, waits for you, or finishes.")
    TetherKey(onClick = onNewSession, classes = KeyClasses.ButtonPrimary, label = "New session", icon = TetherIcons.Plus, iconSize = 16.dp)
}

/** overview.tsx:290-303. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyPage(empty: OverviewPresentation.EmptyPage, choice: OverviewChoice, onChoice: (OverviewChoice) -> Unit) = EmptyBox {
    EmptyHeading(empty.title, empty.hint)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (empty.showAll) TetherKey(onClick = { onChoice(choice.copy(workspace = null, provider = null)) }, label = "Show all workspaces and providers")
        if (empty.showReady) TetherKey(onClick = { onChoice(choice.copy(status = StatusTab.Ready)) }, label = "Show ready sessions")
        if (empty.showActive) TetherKey(onClick = { onChoice(choice.copy(status = StatusTab.Active)) }, label = "Show active sessions")
    }
}

/** overview.tsx:313-323. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pagination(page: Int, pageCount: Int, shown: Int, total: Int, onPage: (Int) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val (first, last) = OverviewPresentation.pageRange(page, shown, total)
    FlowRow(
        Modifier.fillMaxWidth().semantics { contentDescription = "Session pages" },
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TetherKey(onClick = { onPage(maxOf(0, page - 1)) }, label = "Previous", icon = TetherIcons.ChevronLeft, iconSize = 16.dp, enabled = page > 0)
        Text(
            "$first–$last of $total · page ${page + 1} of $pageCount",
            style = css(type.mono, 0.8f, 500),
            color = t.muted,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        TetherKey(
            onClick = { onPage(minOf(pageCount - 1, page + 1)) },
            label = "Next",
            enabled = page < pageCount - 1,
            trailing = { Icon(TetherIcons.ChevronRight, contentDescription = null, tint = t.ink, modifier = Modifier.size(16.dp)) },
        )
    }
}

// ── Recent activity ─────────────────────────────────────────────────────────

/** overview-panels.tsx:83-89 ACTIVITY_KIND. */
private fun activityKind(kind: String): Pair<String, ImageVector> = when (kind) {
    "request" -> "Request" to TetherIcons.ShieldAlert
    "turn_completed" -> "Turn completed" to TetherIcons.CircleCheck
    "tool_result" -> "Tool finished" to TetherIcons.Wrench
    "failure" -> "Failure" to TetherIcons.TriangleAlert
    else -> "Tool started" to Lucide.CirclePlay
}

/** overview-panels.tsx ActivityPanel — the narrow-screen row (mark | title+workspace, time | text). */
@Composable
private fun ActivityPanel(
    activity: List<OverviewActivity>,
    activitySince: Long?,
    live: Boolean,
    onOpen: (String) -> Unit,
    onOpenEventLog: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Panel(OverviewTags.Activity, attention = false) {
        PanelTitle("Recent activity") {
            val ink = if (live) t.running else t.muted
            Row(
                Modifier.semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.6.dp),
            ) {
                Box(
                    Modifier.size(8.dp).cssSurface(CircleShape, if (live) ink else Color.Transparent, CssBorder(1.5.dp, ink)),
                )
                Text(if (live) "Live" else "Paused", style = css(type.ui, 0.82f, 600), color = ink)
            }
            Spacer(Modifier.weight(1f))
        }
        if (activity.isEmpty()) {
            PanelEmpty(Lucide.MessageSquareText, activitySince?.let { "No activity since ${OverviewFormat.clock(it)}." } ?: "No activity yet.")
        } else {
            Column {
                activity.forEachIndexed { index, record ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).cssSurface(RoundedCornerShape(0.dp), t.line))
                    ActivityRow(record, onOpen)
                }
            }
        }
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(1.dp).cssSurface(RoundedCornerShape(0.dp), t.line))
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.heightIn(min = TetherDimens.touchTargetDp).clickable(role = Role.Button, onClick = onOpenEventLog),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.4.dp),
            ) {
                Text("Open event log", style = css(type.ui, 0.86f, 650), color = t.violet)
                Icon(TetherIcons.ArrowRight, contentDescription = null, tint = t.violet, modifier = Modifier.size(14.dp))
            }
            activitySince?.let {
                Text("Activity since ${OverviewFormat.clock(it)} (server start)", style = css(type.ui, 0.75f, 400), color = t.faint)
            }
        }
    }
}

@Composable
private fun ActivityRow(record: OverviewActivity, onOpen: (String) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val (kindLabel, kindIcon) = activityKind(record.kind)
    val failure = record.kind == "failure"
    val rowTitle = title(record.title)
    val workspace = record.workspace?.takeIf { it.isNotEmpty() }?.let { OverviewPresentation.capped(it, OverviewPresentation.LABEL_CHARS) }
    val text = prose(record.text)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TetherDimens.touchTargetDp)
            .clickable(role = Role.Button) { onOpen(record.sessionId) }
            .semantics(mergeDescendants = true) {
                contentDescription = "${OverviewFormat.clock(record.ts)}, $rowTitle" + (workspace?.let { ", in workspace ${SafeText.line(it)}" } ?: "") + ", $kindLabel: $text"
            }
            .testTag(OverviewTags.activityRow(record.id))
            .padding(horizontal = 4.dp, vertical = 8.8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 1.dp)) { ProviderMark(record.provider) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(rowTitle, style = css(type.ui, 0.86f, 620, lineHeight = 1.25f), color = t.white, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    workspace?.let { Text(codeLabel(it), style = css(type.mono, 0.72f, 400, lineHeight = 1.25f), color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Text(OverviewFormat.clock(record.ts), style = css(type.mono, 0.8f, 500).copy(fontFeatureSettings = "tnum"), color = t.muted)
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.4.dp)) {
                val ink = if (failure) t.danger else t.muted
                Icon(kindIcon, contentDescription = null, tint = ink, modifier = Modifier.padding(top = 2.dp).size(13.dp))
                Text(text, style = css(type.ui, 0.86f, 500), color = ink)
            }
        }
    }
}

// ── Footer ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Footer(state: OverviewClientState, offline: Boolean, now: Long) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val style = css(type.ui, 0.75f, 400)
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("This Tether node · usage and resources are node-wide, never filtered", style = style, color = t.faint)
        val updated = when {
            state.phase == OverviewPhase.Resyncing -> "Updating…"
            state.updatedAt != null -> "Updated ${OverviewFormat.ago(state.updatedAt, now)}"
            else -> ""
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (updated.isNotEmpty()) Text(updated, style = style, color = t.faint)
            if (offline && state.data != null) {
                Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.faint, modifier = Modifier.size(12.dp))
                Text("stale", style = style, color = t.faint)
            }
        }
    }
}

