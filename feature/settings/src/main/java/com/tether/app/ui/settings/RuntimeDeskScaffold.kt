package com.tether.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.ProvideTokenScope

data class RuntimeDeskNavItem(
    val id: String,
    val label: String,
    val supporting: String? = null,
    val state: String? = null,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
    val group: String? = null,
)

enum class RuntimeDeskLevel { Destinations, Entities, Detail }

@Composable
fun RuntimeDeskScaffold(
    items: List<RuntimeDeskNavItem>,
    selected: String,
    onSelect: (String) -> Unit,
    entities: List<RuntimeDeskNavItem>,
    selectedEntity: String?,
    onEntity: (String) -> Unit,
    level: RuntimeDeskLevel,
    onBack: () -> Unit,
    onReturn: () -> Unit,
    entityTrailing: @Composable (String) -> Unit = {},
    banner: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    detail: @Composable (Dp) -> Unit,
) {
    val t = LocalTetherTokens.current
    val selectedDestination = items.firstOrNull { it.id == selected }?.label ?: "Settings"
    val selectedDetail = entities.firstOrNull { it.id == selectedEntity }?.label ?: selectedDestination
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val compactBack = {
        if (level == RuntimeDeskLevel.Detail) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
        onBack()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(t.graphite)
            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),
    ) {
        val expanded = maxWidth >= RuntimeDeskStyle.ExpandedBreakpoint
        BackHandler { if (expanded || level == RuntimeDeskLevel.Destinations) onReturn() else compactBack() }

        if (expanded) {
            ExpandedRuntimeDesk(
                items = items,
                selected = selected,
                onSelect = onSelect,
                entities = entities,
                selectedEntity = selectedEntity,
                onEntity = onEntity,
                selectedDestination = selectedDestination,
                selectedDetail = selectedDetail,
                onReturn = onReturn,
                entityTrailing = entityTrailing,
                banner = banner,
                footer = footer,
                detail = detail,
            )
        } else {
            CompactRuntimeDesk(
                items = items,
                selected = selected,
                onSelect = onSelect,
                entities = entities,
                selectedEntity = selectedEntity,
                onEntity = onEntity,
                level = level,
                selectedDestination = selectedDestination,
                selectedDetail = selectedDetail,
                onBack = compactBack,
                onReturn = onReturn,
                entityTrailing = entityTrailing,
                banner = banner,
                footer = footer,
                detail = detail,
            )
        }
    }
}

@Composable
private fun ExpandedRuntimeDesk(
    items: List<RuntimeDeskNavItem>,
    selected: String,
    onSelect: (String) -> Unit,
    entities: List<RuntimeDeskNavItem>,
    selectedEntity: String?,
    onEntity: (String) -> Unit,
    selectedDestination: String,
    selectedDetail: String,
    onReturn: () -> Unit,
    entityTrailing: @Composable (String) -> Unit,
    banner: (@Composable () -> Unit)?,
    footer: (@Composable () -> Unit)?,
    detail: @Composable (Dp) -> Unit,
) {
    val t = LocalTetherTokens.current
    val detailFocus = remember { FocusRequester() }
    LaunchedEffect(selected, selectedEntity) { runCatching { detailFocus.requestFocus() } }
    Row(Modifier.fillMaxSize()) {
        ProvideTokenScope(RuntimeDeskRailScope) {
            RuntimeDeskDestinationRail(items, selected, onSelect, onReturn)
        }

        if (entities.isNotEmpty()) {
            Column(
                Modifier
                    .width(RuntimeDeskStyle.EntityWidth)
                    .fillMaxHeight()
                    .background(t.mineralDeep),
            ) {
                PaneHeading(selectedDestination)
                RuntimeDeskEntityList(entities, selectedEntity, onEntity, false, entityTrailing)
            }
            VerticalRule()
        }

        Column(Modifier.weight(1f).fillMaxHeight().background(t.graphite)) {
            DetailHeader(selectedDestination, selectedDetail, detailFocus)
            banner?.invoke()
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val detailWidth = maxWidth
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { detail(detailWidth) }
            }
            footer?.let {
                HorizontalRule()
                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), contentAlignment = Alignment.CenterEnd) { it() }
            }
        }
    }
}

@Composable
private fun RuntimeDeskDestinationRail(
    items: List<RuntimeDeskNavItem>,
    selected: String,
    onSelect: (String) -> Unit,
    onReturn: () -> Unit,
) {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .width(RuntimeDeskStyle.DestinationWidth)
            .fillMaxHeight()
            .background(t.graphite)
            .padding(RuntimeDeskStyle.PanePadding),
    ) {
        TetherKey(
            onClick = onReturn,
            classes = KeyClasses.ButtonSecondary,
            label = "Return to workspace",
            icon = TetherIcons.PanelLeftOpen,
            iconSize = 20.dp,
            contentDescription = "Return to workspace",
            modifier = Modifier.fillMaxWidth().heightIn(min = RuntimeDeskStyle.TouchTarget),
        )
        Text("Settings", color = t.muted, style = runtimeDeskSupportingStyle(), modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
        RuntimeDeskList(items, selected, onSelect, dark = false, showChevron = false)
    }
}

@Composable
private fun CompactRuntimeDesk(
    items: List<RuntimeDeskNavItem>,
    selected: String,
    onSelect: (String) -> Unit,
    entities: List<RuntimeDeskNavItem>,
    selectedEntity: String?,
    onEntity: (String) -> Unit,
    level: RuntimeDeskLevel,
    selectedDestination: String,
    selectedDetail: String,
    onBack: () -> Unit,
    onReturn: () -> Unit,
    entityTrailing: @Composable (String) -> Unit,
    banner: (@Composable () -> Unit)?,
    footer: (@Composable () -> Unit)?,
    detail: @Composable (Dp) -> Unit,
) {
    val t = LocalTetherTokens.current
    val effectiveLevel = if (level == RuntimeDeskLevel.Entities && entities.isEmpty()) RuntimeDeskLevel.Detail else level
    val title = when (effectiveLevel) {
        RuntimeDeskLevel.Destinations -> "Settings"
        RuntimeDeskLevel.Entities -> selectedDestination
        RuntimeDeskLevel.Detail -> selectedDetail
    }
    val paneFocus = remember { FocusRequester() }
    val detailScroll = rememberScrollState()
    LaunchedEffect(effectiveLevel, selected, selectedEntity) { runCatching { paneFocus.requestFocus() } }
    LaunchedEffect(effectiveLevel, selected, selectedEntity) {
        if (effectiveLevel == RuntimeDeskLevel.Detail) detailScroll.scrollTo(0)
    }

    Column(Modifier.fillMaxSize().background(t.graphite).semantics { paneTitle = title }) {
        val backLabel = if (effectiveLevel == RuntimeDeskLevel.Detail && entities.isNotEmpty()) selectedDestination else "Settings"
        RuntimeDeskTopBar(
            title,
            effectiveLevel != RuntimeDeskLevel.Destinations,
            backLabel,
            onBack,
            onReturn,
            if (effectiveLevel == RuntimeDeskLevel.Detail) paneFocus else null,
        )
        banner?.invoke()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val detailWidth = maxWidth
            when (effectiveLevel) {
                RuntimeDeskLevel.Destinations -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(RuntimeDeskStyle.PanePadding)) {
                    RuntimeDeskList(items, selected, onSelect, dark = false, showChevron = true, selectedFocus = paneFocus)
                }
                RuntimeDeskLevel.Entities -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    RuntimeDeskEntityList(entities, selectedEntity, onEntity, true, entityTrailing, paneFocus)
                }
                RuntimeDeskLevel.Detail -> Unit
            }
            Box(
                Modifier
                    .keepRuntimeDeskMounted(effectiveLevel == RuntimeDeskLevel.Detail)
                    .fillMaxSize()
                    .verticalScroll(detailScroll),
            ) {
                detail(detailWidth)
            }
        }
        footer?.let {
            HorizontalRule()
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterEnd) { it() }
        }
    }
}

@Composable
private fun RuntimeDeskTopBar(
    title: String,
    canGoBack: Boolean,
    backLabel: String,
    onBack: () -> Unit,
    onReturn: () -> Unit,
    titleFocus: FocusRequester? = null,
) {
    val t = LocalTetherTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = RuntimeDeskStyle.HeaderHeight).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (canGoBack) {
            TetherKey(
                onClick = onBack,
                classes = KeyClasses.IconButton,
                icon = TetherIcons.ChevronLeft,
                iconSize = 20.dp,
                contentDescription = "Back to $backLabel",
                modifier = Modifier.size(RuntimeDeskStyle.TouchTarget),
            )
        } else {
            Spacer(Modifier.size(RuntimeDeskStyle.TouchTarget))
        }
        Text(
            title,
            modifier = Modifier
                .weight(1f)
                .then(if (titleFocus != null) Modifier.focusRequester(titleFocus).focusable() else Modifier)
                .semantics { heading() },
            color = t.ink,
            style = runtimeDeskPaneTitleStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        TetherKey(
            onClick = onReturn,
            classes = KeyClasses.IconButton,
            icon = TetherIcons.PanelLeftOpen,
            iconSize = 20.dp,
            contentDescription = "Return to workspace",
            modifier = Modifier.size(RuntimeDeskStyle.TouchTarget),
        )
    }
    HorizontalRule()
}

@Composable
private fun PaneHeading(title: String) {
    val t = LocalTetherTokens.current
    Column(
        Modifier.fillMaxWidth().heightIn(min = RuntimeDeskStyle.HeaderHeight).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Settings", color = t.muted, style = runtimeDeskSupportingStyle())
        Text(title, color = t.ink, style = runtimeDeskPaneTitleStyle(), modifier = Modifier.semantics { heading() })
    }
    HorizontalRule()
}

@Composable
private fun DetailHeader(destination: String, detail: String, focus: FocusRequester? = null) {
    val t = LocalTetherTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = RuntimeDeskStyle.HeaderHeight).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(destination, color = t.muted, style = runtimeDeskSupportingStyle())
            Text(
                detail,
                color = t.ink,
                style = runtimeDeskTitleStyle(),
                modifier = Modifier
                    .then(if (focus != null) Modifier.focusRequester(focus).focusable() else Modifier)
                    .semantics { heading() },
            )
        }
    }
    HorizontalRule()
}

@Composable
private fun RuntimeDeskList(
    items: List<RuntimeDeskNavItem>,
    selected: String,
    onSelect: (String) -> Unit,
    dark: Boolean,
    showChevron: Boolean,
    selectedFocus: FocusRequester? = null,
) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var group: String? = null
        items.forEach { item ->
            if (item.group != null && item.group != group) {
                RuntimeDeskGroupLabel(item.group, first = group == null)
                group = item.group
            }
            RuntimeDeskItem(item, item.id == selected, dark, showChevron, selectedFocus) { onSelect(item.id) }
        }
    }
}

@Composable
private fun RuntimeDeskEntityList(
    items: List<RuntimeDeskNavItem>,
    selected: String?,
    onSelect: (String) -> Unit,
    showChevron: Boolean,
    trailing: @Composable (String) -> Unit,
    selectedFocus: FocusRequester? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val foldTrailing = maxWidth < RuntimeDeskStyle.EntityWidth + RuntimeDeskStyle.TouchTarget
        Column(
            Modifier.fillMaxWidth().selectableGroup().padding(RuntimeDeskStyle.PanePadding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            var group: String? = null
            items.forEach { item ->
                if (item.group != null && item.group != group) {
                    RuntimeDeskGroupLabel(item.group, first = group == null)
                    group = item.group
                }
                if (foldTrailing) {
                    RuntimeDeskItem(item, item.id == selected, dark = false, showChevron = showChevron, selectedFocus = selectedFocus) {
                        onSelect(item.id)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            RuntimeDeskItem(item, item.id == selected, dark = false, showChevron = showChevron, selectedFocus = selectedFocus) {
                                onSelect(item.id)
                            }
                        }
                        // The switch/action slot is deliberately a sibling of the selectable row, never a nested control.
                        Box(
                            Modifier.sizeIn(
                                minWidth = RuntimeDeskStyle.TouchTarget,
                                minHeight = RuntimeDeskStyle.TouchTarget,
                            ),
                            contentAlignment = Alignment.Center,
                        ) { trailing(item.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeDeskGroupLabel(label: String, first: Boolean) {
    Text(
        label,
        color = LocalTetherTokens.current.muted,
        style = runtimeDeskStateStyle(),
        modifier = Modifier
            .padding(start = 10.dp, end = 10.dp, top = if (first) 4.dp else 18.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/** The colours a rail row paints (P8A-FIX3 B1 pins their contrast); [dark] is the legacy navy-rail style, no caller on the light/skin rail. */
internal data class RuntimeDeskItemPalette(val activeBackground: Color, val idleText: Color, val activeText: Color, val supporting: Color)

internal fun runtimeDeskItemPalette(t: com.tether.app.ui.theme.TetherTokens, dark: Boolean) = RuntimeDeskItemPalette(
    activeBackground = if (dark) t.accent else t.css.accentSoft,
    idleText = if (dark) t.css.faint else t.ink,
    activeText = if (dark) t.accentInk else t.accent,
    supporting = if (dark) t.css.faint else t.muted,
)

@Composable
internal fun RuntimeDeskItem(
    item: RuntimeDeskNavItem,
    active: Boolean,
    dark: Boolean,
    showChevron: Boolean,
    selectedFocus: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(RuntimeDeskStyle.ItemRadius)
    val palette = runtimeDeskItemPalette(t, dark)
    val activeBackground = palette.activeBackground
    val idleText = palette.idleText
    val activeText = palette.activeText
    val supporting = palette.supporting
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RuntimeDeskStyle.TouchTarget)
            .clip(shape)
            .background(if (active) activeBackground else Color.Transparent)
            .then(if (active && selectedFocus != null) Modifier.focusRequester(selectedFocus) else Modifier)
            .then(
                if (showChevron) {
                    Modifier
                        .semantics { selected = active }
                        .clickable(enabled = item.enabled, role = Role.Button, onClick = onClick)
                } else {
                    Modifier.selectable(
                        selected = active,
                        enabled = item.enabled,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item.icon?.let { Icon(it, contentDescription = null, tint = if (active) activeText else idleText, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.label, color = if (active) activeText else idleText, style = runtimeDeskItemStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.supporting?.let { Text(it, color = supporting, style = runtimeDeskSupportingStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        item.state?.let {
            Text(it, color = if (active) activeText else supporting, style = runtimeDeskStateStyle(), maxLines = 2, textAlign = TextAlign.End)
        }
        if (showChevron) Icon(TetherIcons.ChevronRight, contentDescription = null, tint = supporting, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun HorizontalRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalTetherTokens.current.line))
}

@Composable
private fun VerticalRule() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(LocalTetherTokens.current.line))
}
