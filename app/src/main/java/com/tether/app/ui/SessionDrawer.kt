package com.tether.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.X
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.components.KeyVariant
import com.tether.app.ui.components.SpinnerRing
import com.tether.app.ui.components.StatusDot
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.WaitingPingDot
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import com.tether.app.ui.theme.ThemeChoice
import com.tether.app.ui.util.projectName
import com.tether.app.ui.util.providerGlyph
import com.tether.app.ui.util.relativeTime
import com.tether.app.ui.util.statusCopy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Left drawer: new-session key, workspace row, session list, footer (visual-spec §3). */
@Composable
fun SessionDrawer(
    vm: TetherViewModel,
    prefs: UiPrefs,
    sessions: List<AgentSession>,
    selectedId: String?,
    workspaceRoot: String?,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val scope = rememberCoroutineScope()
    val providers by vm.client.providers.collectAsStateWithLifecycle()
    val currentWorkspace by vm.currentWorkspace.collectAsStateWithLifecycle()
    val directories by vm.client.directories.collectAsStateWithLifecycle()
    val showEnded by prefs.showEnded.collectAsStateWithLifecycle(initialValue = false)
    val showThinking by prefs.showThinking.collectAsStateWithLifecycle(initialValue = true)
    val themeChoice by prefs.themeChoice.collectAsStateWithLifecycle(initialValue = ThemeChoice.System)

    var providerPicker by remember { mutableStateOf(false) }
    var folderPicker by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }

    // The folder the workspace row names: the picker's choice, else the
    // server's default folder.
    val effectiveWorkspace = currentWorkspace ?: workspaceRoot

    // Relative times tick once a minute while the drawer is open.
    var now by remember { mutableLongStateOf(vm.serverNow(null)) }
    LaunchedEffect(Unit) {
        while (true) {
            now = vm.serverNow(null)
            delay(30_000)
        }
    }

    val visibleSessions = sessions
        .filter { showEnded || it.status != "exited" }
        .filter {
            filter.isBlank() ||
                it.name.contains(filter, ignoreCase = true) ||
                it.cwd.contains(filter, ignoreCase = true)
        }
        .sortedByDescending { it.updatedAt }

    Column(
        Modifier
            .fillMaxSize()
            .background(t.graphite)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Header: "Sessions" + close.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Sessions",
                color = t.white,
                fontFamily = Manrope,
                fontWeight = TetherWeights.heading,
                fontSize = 14.7.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(TetherDimens.touchTargetDp)) {
                Icon(Lucide.X, contentDescription = "Close", tint = t.muted, modifier = Modifier.size(18.dp))
            }
        }

        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TetherKey(
                onClick = { providerPicker = true },
                modifier = Modifier.fillMaxWidth(),
                variant = KeyVariant.Secondary,
                label = "New session",
                icon = Lucide.Plus,
                iconSize = 17.dp,
                fontSize = 13.1.sp,
            )

            // Workspace row: opens the folder picker (browse + select).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TetherDimens.touchTargetDp)
                    .clickable {
                        folderPicker = true
                        vm.client.browse(effectiveWorkspace)
                    }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Lucide.FolderOpen, contentDescription = null, tint = t.muted, modifier = Modifier.size(17.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "WORKSPACE",
                        color = t.faint,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.strong,
                        fontSize = 9.3.sp,
                        letterSpacing = 0.08.em,
                    )
                    Text(
                        effectiveWorkspace?.let(::projectName) ?: "—",
                        color = t.ink,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.label,
                        fontSize = 12.2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Lucide.ChevronRight, contentDescription = null, tint = t.faint, modifier = Modifier.size(15.dp))
            }

            // SESSIONS header + count + show-ended toggle.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SESSIONS",
                    color = t.faint,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.strong,
                    fontSize = 10.7.sp,
                    letterSpacing = 0.08.em,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    "${visibleSessions.size}",
                    color = t.faint,
                    fontFamily = JetBrainsMono,
                    fontSize = 10.7.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (showEnded) "HIDE ENDED" else "SHOW ENDED",
                    color = if (showEnded) t.violet else t.faint,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.strong,
                    fontSize = 9.9.sp,
                    letterSpacing = 0.06.em,
                    modifier = Modifier
                        .clickable { scope.launch { prefs.setShowEnded(!showEnded) } }
                        .padding(8.dp),
                )
            }

            if (sessions.size > 5) {
                TetherInputWell(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Filter sessions…",
                    singleLine = true,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(visibleSessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    selected = session.id == selectedId,
                    now = now,
                    onClick = { onSelect(session.id) },
                )
            }
        }

        // Footer: private-runtime mark + settings.
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(t.violet, size = 6.4.dp)
            Spacer(Modifier.size(8.dp))
            Text(
                "Private runtime",
                color = t.muted,
                fontFamily = Manrope,
                fontWeight = TetherWeights.label,
                fontSize = 11.2.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { settingsOpen = true }, modifier = Modifier.size(TetherDimens.touchTargetDp)) {
                Icon(Lucide.Settings, contentDescription = "Settings", tint = t.muted, modifier = Modifier.size(17.dp))
            }
        }
    }

    if (providerPicker) {
        TetherDialog(onDismiss = { providerPicker = false }, title = "New session") {
            providers.forEach { provider ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = provider.available) {
                            providerPicker = false
                            vm.createSession(provider.id)
                        }
                        .heightIn(min = TetherDimens.touchTargetDp)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProviderGlyph(provider.glyph)
                    Column(Modifier.weight(1f)) {
                        Text(
                            provider.label,
                            color = if (provider.available) t.white else t.faint,
                            fontFamily = Manrope,
                            fontWeight = TetherWeights.name,
                            fontSize = 13.6.sp,
                        )
                        if (!provider.available) {
                            Text(
                                "Not configured on this server",
                                color = t.faint,
                                fontFamily = Manrope,
                                fontSize = 11.5.sp,
                            )
                        }
                    }
                    Icon(Lucide.ChevronRight, contentDescription = null, tint = t.faint, modifier = Modifier.size(15.dp))
                }
            }
        }
    }

    if (folderPicker) {
        val listing = directories
        val pickerCurrent = listing?.current ?: effectiveWorkspace
        TetherDialog(onDismiss = { folderPicker = false }, title = "Choose a folder") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Lucide.FolderOpen, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp))
                Text(
                    pickerCurrent ?: "—",
                    color = t.muted,
                    fontFamily = JetBrainsMono,
                    fontSize = 11.2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            listing?.parent?.let { parent ->
                FolderRow(
                    icon = { Icon(Lucide.ArrowLeft, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp)) },
                    name = "Parent folder",
                    detail = "Go up one level",
                    onClick = { vm.client.browse(parent) },
                )
            }
            listing?.entries?.forEach { entry ->
                FolderRow(
                    icon = { Icon(Lucide.Folder, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp)) },
                    name = entry.name,
                    detail = entry.path,
                    onClick = { vm.client.browse(entry.path) },
                )
            }
            if (listing != null && listing.entries.isEmpty() && listing.parent == null) {
                Text(
                    "No folders are available here.",
                    color = t.muted,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.body,
                    fontSize = 12.8.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TetherKey(
                    onClick = { folderPicker = false },
                    variant = KeyVariant.Secondary,
                    label = "Cancel",
                )
                TetherKey(
                    onClick = {
                        pickerCurrent?.let(vm::selectWorkspace)
                        folderPicker = false
                    },
                    variant = KeyVariant.Primary,
                    label = "Use this folder",
                    icon = Lucide.Check,
                    iconSize = 15.dp,
                )
            }
        }
    }

    if (settingsOpen) {
        TetherDialog(onDismiss = { settingsOpen = false }, title = "Settings") {
            Text(
                "THEME",
                color = t.faint,
                fontFamily = Manrope,
                fontWeight = TetherWeights.strong,
                fontSize = 10.7.sp,
                letterSpacing = 0.08.em,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ThemeChoice.entries.forEach { choice ->
                    val chosen = choice == themeChoice
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (chosen) t.violetWash else t.keyFace,
                                RoundedCornerShape(TetherDimens.radiusSm),
                            )
                            .border(
                                1.dp,
                                if (chosen) t.violetStrong else t.keySide,
                                RoundedCornerShape(TetherDimens.radiusSm),
                            )
                            .clickable { scope.launch { prefs.setThemeChoice(choice) } }
                            .heightIn(min = TetherDimens.touchTargetDp)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            choice.label,
                            color = if (chosen) t.white else t.ink,
                            fontFamily = Manrope,
                            fontWeight = TetherWeights.name,
                            fontSize = 13.1.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (chosen) StatusDot(t.violet, size = 6.4.dp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { scope.launch { prefs.setShowThinking(!showThinking) } }
                    .heightIn(min = TetherDimens.touchTargetDp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Show thinking",
                    color = t.ink,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.name,
                    fontSize = 13.1.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (showThinking) "ON" else "OFF",
                    color = if (showThinking) t.violet else t.faint,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.strong,
                    fontSize = 10.7.sp,
                    letterSpacing = 0.06.em,
                )
            }
        }
    }
}

/** One tappable row in the folder picker: icon + name over truncated path. */
@Composable
private fun FolderRow(
    icon: @Composable () -> Unit,
    name: String,
    detail: String,
    onClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = TetherDimens.touchTargetDp)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = t.ink,
                fontFamily = Manrope,
                fontWeight = TetherWeights.name,
                fontSize = 13.1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                color = t.faint,
                fontFamily = JetBrainsMono,
                fontSize = 10.4.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 32dp provider glyph circle: key-face, 1px line-strong, mono letter. */
@Composable
fun ProviderGlyph(glyph: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Box(
        modifier = modifier
            .size(32.dp)
            .background(t.keyFace, CircleShape)
            .border(1.dp, t.lineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = t.ink,
            fontFamily = JetBrainsMono,
            fontWeight = TetherWeights.glyph,
            fontSize = 12.8.sp,
        )
    }
}

/** One session row (visual-spec §3 SESSION ROW). */
@Composable
fun SessionRow(
    session: AgentSession,
    selected: Boolean,
    now: Long,
    onClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(TetherDimens.radiusSm)
    val statusColor = when (session.status) {
        "active" -> t.running
        "waiting" -> t.violet
        else -> t.faint
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) t.violetWash else Color.Transparent, shape)
            .border(1.dp, if (selected) t.violetStrong else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .heightIn(min = 68.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProviderGlyph(providerGlyph(session.provider))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                session.name,
                color = if (selected) t.white else t.ink,
                fontFamily = Manrope,
                fontWeight = TetherWeights.name,
                fontSize = 13.1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (session.status) {
                    "active" -> SpinnerRing(color = statusColor, size = 10.4.dp)
                    "waiting" -> WaitingPingDot(color = statusColor)
                    else -> StatusDot(color = statusColor)
                }
                Text(
                    statusCopy(session.status),
                    color = statusColor,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.label,
                    fontSize = 11.2.sp,
                )
                Text(
                    "· ${relativeTime(session.updatedAt, now)}",
                    color = t.faint,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.body,
                    fontSize = 11.2.sp,
                )
            }
        }
        Icon(Lucide.ChevronRight, contentDescription = null, tint = t.faint, modifier = Modifier.size(16.dp))
    }
}
