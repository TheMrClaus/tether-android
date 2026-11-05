package com.tether.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    onClose: () -> Unit,
    entityTrailing: @Composable (String) -> Unit = {},
    banner: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    detail: @Composable () -> Unit,
) {
    val t = LocalTetherTokens.current
    val selectedDestination = items.firstOrNull { it.id == selected }?.label ?: "Settings"
    val selectedDetail = entities.firstOrNull { it.id == selectedEntity }?.label ?: selectedDestination

    BackHandler { if (level == RuntimeDeskLevel.Destinations) onClose() else onBack() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(t.graphite)
            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),
    ) {
        if (maxWidth >= RuntimeDeskStyle.ExpandedBreakpoint) {
            ExpandedRuntimeDesk(
                items = items,
                selected = selected,
                onSelect = onSelect,
                entities = entities,
                selectedEntity = selectedEntity,
                onEntity = onEntity,
                selectedDestination = selectedDestination,
                selectedDetail = selectedDetail,
                onClose = onClose,
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
                onBack = onBack,
                onClose = onClose,
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
    onClose: () -> Unit,
    entityTrailing: @Composable (String) -> Unit,
    banner: (@Composable () -> Unit)?,
    footer: (@Composable () -> Unit)?,
    detail: @Composable () -> Unit,
) {
    val t = LocalTetherTokens.current
    Row(Modifier.fillMaxSize()) {
        ProvideTokenScope(RuntimeDeskRailScope) {
            RuntimeDeskDestinationRail(items, selected, onSelect)
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
            DetailHeader(selectedDestination, selectedDetail, onClose)
            banner?.invoke()
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { detail() }
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
) {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .width(RuntimeDeskStyle.DestinationWidth)
            .fillMaxHeight()
            .background(t.graphite)
            .padding(RuntimeDeskStyle.PanePadding),
    ) {
        Column(
            Modifier
                .height(RuntimeDeskStyle.HeaderHeight)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Tether", color = t.white, style = runtimeDeskPaneTitleStyle())
            Text("Settings", color = t.muted, style = runtimeDeskSupportingStyle())
        }
        RuntimeDeskList(items, selected, onSelect, dark = true, showChevron = false)
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
    onClose: () -> Unit,
    entityTrailing: @Composable (String) -> Unit,
    banner: (@Composable () -> Unit)?,
    footer: (@Composable () -> Unit)?,
    detail: @Composable () -> Unit,
) {
    val t = LocalTetherTokens.current
    val effectiveLevel = if (level == RuntimeDeskLevel.Entities && entities.isEmpty()) RuntimeDeskLevel.Detail else level
    val title = when (effectiveLevel) {
        RuntimeDeskLevel.Destinations -> "Settings"
        RuntimeDeskLevel.Entities -> selectedDestination
        RuntimeDeskLevel.Detail -> selectedDetail
    }

    Column(Modifier.fillMaxSize().background(t.graphite).semantics { paneTitle = title }) {
        RuntimeDeskTopBar(title, effectiveLevel != RuntimeDeskLevel.Destinations, onBack, onClose)
        banner?.invoke()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (effectiveLevel) {
                RuntimeDeskLevel.Destinations -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(RuntimeDeskStyle.PanePadding)) {
                    RuntimeDeskList(items, selected, onSelect, dark = false, showChevron = true)
                }
                RuntimeDeskLevel.Entities -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    RuntimeDeskEntityList(entities, selectedEntity, onEntity, true, entityTrailing)
                }
                RuntimeDeskLevel.Detail -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { detail() }
            }
        }
        footer?.let {
            HorizontalRule()
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterEnd) { it() }
        }
    }
}

@Composable
private fun RuntimeDeskTopBar(title: String, canGoBack: Boolean, onBack: () -> Unit, onClose: () -> Unit) {
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
                icon = TetherIcons.ArrowLeft,
                iconSize = 20.dp,
                contentDescription = "Back",
                modifier = Modifier.size(RuntimeDeskStyle.TouchTarget),
            )
        } else {
            Spacer(Modifier.size(RuntimeDeskStyle.TouchTarget))
        }
        Text(
            title,
            modifier = Modifier.weight(1f).semantics { heading() },
            color = t.ink,
            style = runtimeDeskPaneTitleStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        TetherKey(
            onClick = onClose,
            classes = KeyClasses.IconButton,
            icon = TetherIcons.X,
            iconSize = 19.dp,
            contentDescription = "Close settings",
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
private fun DetailHeader(destination: String, detail: String, onClose: () -> Unit) {
    val t = LocalTetherTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = RuntimeDeskStyle.HeaderHeight).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(destination, color = t.muted, style = runtimeDeskSupportingStyle())
            Text(detail, color = t.ink, style = runtimeDeskTitleStyle(), modifier = Modifier.semantics { heading() })
        }
        TetherKey(
            onClick = onClose,
            classes = KeyClasses.IconButton,
            icon = TetherIcons.X,
            iconSize = 19.dp,
            contentDescription = "Close settings",
            modifier = Modifier.size(RuntimeDeskStyle.TouchTarget),
        )
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
) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            RuntimeDeskItem(item, item.id == selected, dark, showChevron) { onSelect(item.id) }
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
) {
    Column(
        Modifier.fillMaxWidth().selectableGroup().padding(RuntimeDeskStyle.PanePadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEach { item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    RuntimeDeskItem(item, item.id == selected, dark = false, showChevron = showChevron) { onSelect(item.id) }
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

@Composable
private fun RuntimeDeskItem(
    item: RuntimeDeskNavItem,
    active: Boolean,
    dark: Boolean,
    showChevron: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(RuntimeDeskStyle.ItemRadius)
    val activeBackground = if (dark) t.accent else t.violetWash
    val idleText = if (dark) t.css.faint else t.ink
    val activeText = if (dark) t.accentInk else t.violetStrong
    val supporting = if (dark) t.css.faint else t.muted
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RuntimeDeskStyle.TouchTarget)
            .clip(shape)
            .background(if (active) activeBackground else Color.Transparent)
            .selectable(
                selected = active,
                enabled = item.enabled,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
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
