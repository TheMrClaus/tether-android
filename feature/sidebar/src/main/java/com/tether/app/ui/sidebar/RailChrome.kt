package com.tether.app.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/**
 * W53 (ta-hkk7.8): the tablet rail's chrome, the web's SB rail chrome at `19c56e25` on a coarse pointer
 * (studio.css:1241-1300, session-sidebar.tsx:1157-1215), at the app's 48 dp touch floor (the web's coarse size is 44).
 * Its own file: SessionSidebar.kt keeps only the one call.
 */
internal object RailGeometry {
    /** `@container rail (min-width: 18.25rem)` (studio.css:1270): the long Scheduled label, on the sidebar's CONTENT box. */
    const val LongScheduledMinContentDp = 292f

    /** The rail pads 0.875rem on each side (studio.css:300; ExpandedShell.SidebarColumn 14 + 14). */
    const val RailPaddingDp = 14f

    fun longScheduledLabel(contentWidthDp: Float): Boolean = contentWidthDp >= LongScheduledMinContentDp

    fun scheduledLabel(contentWidthDp: Float): String = if (longScheduledLabel(contentWidthDp)) "Scheduled actions" else "Scheduled"

    /** The long label from a rail of 320 dp (content 292). */
    fun scheduledLabelForRail(railWidthDp: Float): String = scheduledLabel(railWidthDp - 2 * RailPaddingDp)
}

/** Test tags of the rail chrome (the primary row and its fields). */
internal object RailTags {
    const val PrimaryRow = "rail-primary-row"
    const val ScheduledLabel = "rail-scheduled-label"
    const val Legend = "rail-legend"
    const val Toggles = "rail-toggles"
    const val Search = "rail-search"
    const val Filter = "rail-filter"
}

@Composable
internal fun RailChrome(
    state: SidebarState,
    actions: SidebarActions,
    openCount: Int,
    harnessOpen: Boolean,
    onHarnessOpen: (Boolean) -> Unit,
    sortOpen: Boolean,
    onSortOpen: (Boolean) -> Unit,
) {
    RailPrimaryRow(
        onNewSession = actions.onNewSession,
        scheduledCount = state.scheduledActionCount,
        scheduledActive = state.scheduledActionsActive,
        onScheduled = actions.onOpenScheduledActions,
    )
    RailLegend(openCount, state, actions, harnessOpen, onHarnessOpen, sortOpen, onSortOpen)
    RailSearch(actions.onOpenGlobalSearch)
    RailFilter(state.query, actions.onSessionQueryChange)
}

/** `.sidebar-primary-row` (studio.css:1262): New session and Scheduled actions on one line, space-between, 14 below. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RailPrimaryRow(onNewSession: () -> Unit, scheduledCount: Int, scheduledActive: Boolean, onScheduled: (() -> Unit)?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 14.dp).testTag(RailTags.PrimaryRow)) {
        val label = RailGeometry.scheduledLabel(maxWidth.value)
        // Wraps (not the web's nowrap) so a 2.0x font never clips a key.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // `.new-session-button` (studio.css:1263): #365cde, radius 10, 0 14 0 12, gap 8, Plus 16, 13/680; no kbd chip on a touch rail (:1276).
            val keyShape = RoundedCornerShape(10.dp)
            Row(
                Modifier
                    .heightIn(min = PhoneDrawer.Floor)
                    .clip(keyShape)
                    .background(Color(0xFF365CDE), keyShape)
                    .semantics(mergeDescendants = true) { role = Role.Button }
                    .clickable(role = Role.Button, onClick = onNewSession)
                    .padding(start = 12.dp, end = 14.dp)
                    .testTag(SidebarTags.NewSession),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallIcon(TetherIcons.Plus, Color.White, 16.dp)
                Text("New session", style = css(type.ui, 0.8125f, 680), color = Color.White, maxLines = 1, softWrap = false)
            }
            // `.scheduled-actions-nav` (studio.css:1267-1268): radius 8, 0 6, gap 8, CalendarClock 16, 13/600; is-active = graphite-raised + white.
            val navShape = RoundedCornerShape(8.dp)
            val ink = if (scheduledActive) t.white else t.muted
            Row(
                Modifier
                    .heightIn(min = PhoneDrawer.Floor)
                    .clip(navShape)
                    .then(if (scheduledActive) Modifier.background(t.graphiteRaised, navShape).semantics { selected = true } else Modifier)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = if (scheduledCount > 0) "Scheduled actions, $scheduledCount" else "Scheduled actions"
                    }
                    .clickable(enabled = onScheduled != null, role = Role.Button) { onScheduled?.invoke() }
                    .alpha(if (onScheduled == null) 0.48f else 1f)
                    .padding(horizontal = 6.dp)
                    .testTag(SidebarTags.Scheduled),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallIcon(TetherIcons.CalendarClock, ink, 16.dp)
                Text(label, style = css(type.ui, 0.8125f, 600), color = ink, maxLines = 1, softWrap = false, modifier = Modifier.testTag(RailTags.ScheduledLabel))
                // The count only when there is one (session-sidebar.tsx:1171).
                if (scheduledCount > 0) CountPill(scheduledCount, if (scheduledActive) t.violetStrong else t.lineStrong, 0.64f, if (scheduledActive) t.violetDeep else t.faint)
            }
        }
    }
}

/**
 * `.session-list-header` on a coarse pointer (studio.css:1279-1287, 1451-1454): the word "Workspaces" is hidden (it stays
 * in the name), the count shows 12/500 `faint`, then the five 48 dp toggles. The toggles wrap under the count when they do not fit (272).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RailLegend(
    openCount: Int,
    state: SidebarState,
    actions: SidebarActions,
    harnessOpen: Boolean,
    onHarnessOpen: (Boolean) -> Unit,
    sortOpen: Boolean,
    onSortOpen: (Boolean) -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 10.dp).testTag(RailTags.Legend),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.heightIn(min = PhoneDrawer.Floor), contentAlignment = Alignment.CenterStart) {
            Text(
                "$openCount",
                style = css(type.ui, 0.75f, 500),
                color = t.faint,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.semantics { heading(); contentDescription = "Workspaces, $openCount open" },
            )
        }
        Box(Modifier.testTag(RailTags.Toggles)) {
            FilterBank(state, actions, false, harnessOpen, onHarnessOpen, sortOpen, onSortOpen)
        }
    }
}

/** `.session-global-search` (studio.css:1292-1299): radius 8, 1 dp `line`, `mineral-deep`, icon 15, text 16 on coarse, 6 below. */
@Composable
private fun RailSearch(onClick: (() -> Unit)?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        Modifier
            .padding(bottom = 6.dp)
            .fillMaxWidth()
            .heightIn(min = PhoneDrawer.Floor)
            .clickable(enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
            .semantics { if (onClick == null) disabled() }
            .cssSurface(RoundedCornerShape(8.dp), t.mineralDeep, CssBorder(1.dp, t.line), emptyList())
            .padding(horizontal = 12.dp)
            .testTag(RailTags.Search),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SmallIcon(TetherIcons.Search, t.faint, 15.dp)
        Text("Search all conversations…", style = css(type.ui, 1f, 500), color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** `.session-filter` (studio.css:1292-1300): the same well, always visible, a 2 dp `violet` ring in focus, text 16, 14 below. */
@Composable
private fun RailFilter(query: String, onChange: (String) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier
            .padding(bottom = 14.dp)
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(8.dp), t.mineralDeep, CssBorder(if (focused) 2.dp else 1.dp, if (focused) t.violet else t.line))
            .padding(horizontal = 12.dp)
            .testTag(RailTags.Filter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SmallIcon(TetherIcons.Filter, t.faint, 15.dp)
        val fieldStyle = css(type.ui, 1f, 400).copy(color = t.ink)
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = fieldStyle,
            cursorBrush = SolidColor(t.violet),
            interactionSource = interaction,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = PhoneDrawer.Floor)
                .semantics { contentDescription = "Filter sessions" },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Filter sessions…", style = fieldStyle.ltrWords(), color = t.faint, maxLines = 1)
                    inner()
                }
            },
        )
    }
}
