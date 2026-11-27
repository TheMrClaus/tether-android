package com.tether.app.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.KeyState
import com.tether.app.ui.components.StatusDot
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.resolveKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.prefs.SidebarSort
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.protocol.helpers.SessionSidebar as Web

/**
 * Vector m02 (P8C): the phone drawer's chrome, from the top: the labelled "+ New session" and "Scheduled" keys and Close,
 * the merged search bar with its scope pill, the quick-filter chips (Fira Code), and the footer's link line.
 * The list body below it is [SessionSidebar]'s; the tablet rail keeps its own chrome ([RailChrome]).
 */
/** P8C-FIX3: the phone's block rule: an 8 dp gap above the 1 dp hairline (off the card border above it). */
internal object PhoneChromeRule {
    val GapAbove = 8.dp
}

internal object PhoneChromeTags {
    const val SearchScope = "sidebar-search-scope"
    const val ChipRow = "sidebar-chips"
    const val FooterStatus = "sidebar-footer-status"
    const val FooterSettings = "sidebar-footer-settings"
    const val BlockRule = "sidebar-block-rule"
    fun chip(id: String) = "sidebar-chip:$id"
    const val HiddenRuns = "sidebar-hidden-runs"
}

/** The keys' visible face height; the touch target around it is [PhoneDrawer.Floor]. */
private val KeyFace = 44.dp

/** The footer's words: online "Secure link", a dropped link the web status bar's "Reconnecting" (status-bar.tsx:25). */
internal fun footerLinkWord(connected: Boolean): String = if (connected) "Secure link" else "Reconnecting"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneHeader(
    scheduledCount: Int,
    scheduledActive: Boolean,
    onNewSession: () -> Unit,
    onScheduled: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    FlowRow(
        Modifier.fillMaxWidth().heightIn(min = PhoneLook.RowMinHeight).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        // The one primary action: the accent key with its words. The whole 48 dp target is ONE node (click, name, role).
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val look = resolveKey(t, KeyClasses.NewSession, if (pressed) KeyState.Pressed else KeyState.Rest)
        Box(
            Modifier
                .heightIn(min = PhoneDrawer.Floor)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onNewSession)
                .clearAndSetSemantics {
                    testTag = SidebarTags.NewSession
                    contentDescription = "New session"
                    role = Role.Button
                    onClick { onNewSession(); true }
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .height(KeyFace)
                    .cssSurface(RoundedCornerShape(t.radiusMd), look.face, CssBorder(1.dp, look.border), look.shadows)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SmallIcon(TetherIcons.Plus, look.ink, 18.dp)
                Text("New session", style = css(type.ui, 0.9375f, 700), color = look.ink, maxLines = 1, softWrap = false)
            }
        }
        // "Scheduled" key (the web's short label), with its count; active = the destination is on screen.
        Box(
            Modifier
                .heightIn(min = PhoneDrawer.Floor)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = if (scheduledCount > 0) "Scheduled actions, $scheduledCount" else "Scheduled actions"
                    if (scheduledActive) selected = true
                }
                .clickable(enabled = onScheduled != null, role = Role.Button) { onScheduled?.invoke() }
                .alpha(if (onScheduled == null) 0.48f else 1f)
                .testTag(SidebarTags.Scheduled),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .height(KeyFace)
                    .cssSurface(
                        RoundedCornerShape(t.radiusMd),
                        if (scheduledActive) t.graphiteRaised else t.css.bgPanel,
                        CssBorder(1.dp, t.lineStrong),
                    )
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Scheduled", style = css(type.ui, 0.9375f, 600), color = t.white, maxLines = 1, softWrap = false)
                if (scheduledCount > 0) CountPill(scheduledCount, if (scheduledActive) t.violetStrong else t.lineStrong, 0.64f, if (scheduledActive) t.violetDeep else t.muted)
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(PhoneDrawer.Floor)
                    .clickable(role = Role.Button, onClickLabel = null, onClick = onClose)
                    .semantics { contentDescription = "Close sessions" },
                contentAlignment = Alignment.Center,
            ) { SmallIcon(TetherIcons.X, t.white, 20.dp) }
        }
    }
}

/**
 * The merged search bar (web session-sidebar.tsx:1296-1322): ONE field and a scope pill. Scope "All · N ws" = the field is a
 * door to the global search ([onOpenGlobal]); scope "Filter" = the field filters this list. The pill switches between them.
 */
@Composable
internal fun PhoneSearchBar(
    query: String,
    allScope: Boolean,
    workspaceCount: Int,
    onQuery: (String) -> Unit,
    onOpenGlobal: (() -> Unit)?,
    onScope: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = remember { FocusRequester() }
    var userSwitched by remember { mutableStateOf(false) }
    LaunchedEffect(allScope) { if (!allScope && userSwitched) runCatching { focus.requestFocus() } }
    val fieldStyle = css(type.ui, 0.9375f, 400).copy(color = t.ink)
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(t.radiusMd), t.mineralDeep, CssBorder(if (focused) 2.dp else 1.dp, if (focused) t.violet else t.line))
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (allScope) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = PhoneDrawer.Floor)
                    .clickable(enabled = onOpenGlobal != null, role = Role.Button) { onOpenGlobal?.invoke() }
                    .semantics {
                        contentDescription = "Search all conversations"
                        if (onOpenGlobal == null) disabled()
                    },
                contentAlignment = Alignment.CenterStart,
            ) { Text("Search conversations or filter…", style = fieldStyle.ltrWords(), color = t.faint, maxLines = 1) }
        } else {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = fieldStyle,
                cursorBrush = SolidColor(t.violet),
                interactionSource = interaction,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = PhoneDrawer.Floor)
                    .focusRequester(focus)
                    .semantics { contentDescription = "Filter sessions" },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Filter sessions…", style = fieldStyle.ltrWords(), color = t.faint, maxLines = 1)
                        inner()
                    }
                },
            )
        }
        Box(
            Modifier
                .heightIn(min = PhoneDrawer.Floor)
                .widthIn(min = PhoneDrawer.Floor)
                .clickable(role = Role.Button) { userSwitched = true; onScope() }
                .clearAndSetSemantics {
                    testTag = PhoneChromeTags.SearchScope
                    role = Role.Button
                    contentDescription = if (allScope) "Search scope: all conversations — switch to filtering this list"
                    else "Search scope: this list — switch to searching all conversations"
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .cssSurface(RoundedCornerShape(8.dp), t.violetWash, CssBorder(1.dp, t.violetStrong))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(if (allScope) "All · $workspaceCount ws" else "Filter", style = css(type.ui, 0.8125f, 600).ltrWords(), color = t.violet, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** One quick-filter chip: 48 dp to touch, 36 dp to see, Fira Code; latched = accent outline on the accent wash. */
@Composable
private fun PhoneChip(
    id: String,
    label: String,
    on: Boolean,
    description: String,
    switchState: String?,
    trailing: androidx.compose.ui.graphics.vector.ImageVector? = null,
    leading: androidx.compose.ui.graphics.vector.ImageVector? = null,
    expanded: Boolean? = null,
    onClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Box(
        Modifier
            .heightIn(min = PhoneDrawer.Floor)
            .widthIn(min = PhoneDrawer.Floor)
            .semantics {
                contentDescription = description
                if (switchState != null) {
                    role = Role.Switch
                    stateDescription = (if (on) "On. " else "Off. ") + switchState
                } else {
                    role = Role.Button
                    if (expanded != null) stateDescription = if (expanded) "Menu open" else "Menu closed"
                }
                if (on) selected = true
            }
            .testTag(PhoneChromeTags.chip(id))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (on) t.white else t.muted
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .cssSurface(RoundedCornerShape(8.dp), if (on) t.violetWash else t.css.bgPanel, CssBorder(1.dp, if (on) t.violet else t.line), emptyList())
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leading != null) SmallIcon(leading, ink, 14.dp)
            Text(label, style = css(type.mono, 0.8125f, if (on) 700 else 500), color = ink, maxLines = 1, softWrap = false, modifier = Modifier.clearAndSetSemantics { })
            if (trailing != null) SmallIcon(trailing, ink, 14.dp)
        }
    }
}

/**
 * The quick filters, one scrolling row (the comp clips the last chip at the edge): All, Running, Unread, Hide runs (Q2: the
 * toggle hides agent runs), the sort (Recent / Created) and the harness filter (kept reachable; the comp draws neither menu).
 * No counts (Q1: the shipped web has none).
 */
@Composable
internal fun PhoneChipRow(
    state: SidebarState,
    actions: SidebarActions,
    harnessOpen: Boolean,
    onHarnessOpen: (Boolean) -> Unit,
    sortOpen: Boolean,
    onSortOpen: (Boolean) -> Unit,
) {
    val none = !state.activeOnly && !state.unreadOnly
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .horizontalScroll(rememberScrollState())
            .testTag(PhoneChromeTags.ChipRow),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoneChip("all", "All", none, "All", null) {
            if (state.activeOnly) actions.onToggleActiveOnly()
            if (state.unreadOnly) actions.onToggleUnreadOnly()
        }
        PhoneChip(
            "running", "Running", state.activeOnly, "Running",
            if (state.activeOnly) "Showing sessions with work in progress" else "Show sessions with work in progress",
            onClick = actions.onToggleActiveOnly,
        )
        PhoneChip(
            "unread", "Unread", state.unreadOnly, "Unread",
            if (state.unreadOnly) "Showing sessions with unread reports" else "Show sessions with unread reports",
            onClick = actions.onToggleUnreadOnly,
        )
        PhoneChip(
            "hide-runs", "Hide runs", state.hideAgentRuns, "Hide runs",
            if (state.hideAgentRuns) "Hiding background CLI runs launched by agents" else "Hide background CLI runs launched by agents",
            onClick = actions.onToggleHideAgentRuns,
        )
        Box {
            val sortLabel = if (state.sort == SidebarSort.Created) "Created Date" else "Last Active"
            PhoneChip(
                "sort", if (state.sort == SidebarSort.Created) "Created" else "Recent", false,
                "Sort sessions by $sortLabel", null,
                trailing = TetherIcons.ArrowDownUp, expanded = sortOpen,
            ) { onSortOpen(!sortOpen) }
            if (sortOpen) {
                SidebarMenu(onDismiss = { onSortOpen(false) }, alignStart = false, label = "Sort sessions") {
                    MenuItem("Created Date", checked = state.sort == SidebarSort.Created, trailingCheck = true) { actions.onSortModeChange(SidebarSort.Created); onSortOpen(false) }
                    MenuItem("Last Active", checked = state.sort == SidebarSort.LastActive, trailingCheck = true) { actions.onSortModeChange(SidebarSort.LastActive); onSortOpen(false) }
                }
            }
        }
        Box {
            val harnessLabel = state.harness?.let { id -> Web.SIDEBAR_HARNESSES.firstOrNull { it.first == id }?.second }
            PhoneChip(
                "harness", harnessLabel ?: "Harness", state.harness != null,
                if (harnessLabel != null) "Filtered to $harnessLabel — tap to change" else "Filter sessions by harness", null,
                leading = TetherIcons.Filter, expanded = harnessOpen,
            ) { onHarnessOpen(!harnessOpen) }
            if (harnessOpen) {
                SidebarMenu(onDismiss = { onHarnessOpen(false) }, alignStart = true, label = "Filter by harness") {
                    MenuItem("All harnesses", checked = state.harness == null) { actions.onHarnessFilterChange(null); onHarnessOpen(false) }
                    Web.SIDEBAR_HARNESSES.forEach { (id, label) ->
                        MenuItem(label, checked = state.harness == id, leading = { ProviderCap(id, 1.5f.rem, inRow = false, letterRem = 0.65f) }) {
                            actions.onHarnessFilterChange(id)
                            onHarnessOpen(false)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The phone footer (m02): "● Secure link · Private runtime" (the dot and the word from the connection: online = success dot and
 * "Secure link", dropped = warning dot and "Reconnecting") and the Settings destination at the end.
 */
@Composable
internal fun PhoneFooter(connected: Boolean, onOpenSettings: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val line = t.line
    val panel = t.css.bgPanel
    // B2 (m02): the band reaches the drawer's own edges and bottom inset. The host pads the panel by max(design minimum,
    // system bars, cutout) per side (SessionDrawerHost) and the sidebar adds its inner gutter; the face and the rule are
    // drawn outward by those same amounts, so layout and touch targets do not move (nothing clips: the panel has no clip).
    val inset = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .union(WindowInsets(left = PhoneDrawer.Edge, top = PhoneDrawer.Edge, right = PhoneDrawer.Edge, bottom = PhoneDrawer.Bottom))
    val gutter = PhoneLook.InnerGutter
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val l = inset.getLeft(density, dir) + gutter.toPx()
                val r = inset.getRight(density, dir) + gutter.toPx()
                val b = inset.getBottom(density).toFloat()
                drawRect(panel, Offset(-l, 0f), androidx.compose.ui.geometry.Size(size.width + l + r, size.height + b))
                drawRect(line, Offset(-l, 0f), androidx.compose.ui.geometry.Size(size.width + l + r, 1.dp.toPx()))
            }
            .heightIn(min = PhoneLook.RowMinHeight)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val words = "${footerLinkWord(connected)} · Private runtime"
        Row(
            Modifier.weight(1f, fill = false).padding(end = 8.dp).testTag(PhoneChromeTags.FooterStatus),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(if (connected) t.running else t.warning, size = 8.dp)
            // ONE text node carries the words as its name (whole words at a large font; LTR English under RTL).
            Text(words, style = css(type.ui, 0.9375f, 400).ltrWords(), color = t.muted, maxLines = 2, modifier = Modifier.semantics { contentDescription = words })
        }
        Box(
            Modifier
                .heightIn(min = PhoneDrawer.Floor)
                .widthIn(min = PhoneDrawer.Floor)
                .semantics(mergeDescendants = true) { contentDescription = "Open settings" }
                .clickable(role = Role.Button, onClick = onOpenSettings)
                .padding(horizontal = 8.dp)
                .testTag(PhoneChromeTags.FooterSettings),
            contentAlignment = Alignment.Center,
        ) { Text("Settings", style = css(type.ui, 0.9375f, 700), color = t.white, maxLines = 1, modifier = Modifier.clearAndSetSemantics { }) }
    }
}
