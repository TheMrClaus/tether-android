package com.tether.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.tether.app.ui.text.codeLabel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.push.PushScope
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.StatusDot
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.prefs.TetherPreferences
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import com.tether.app.ui.theme.ThemeMode
import kotlinx.coroutines.launch

// The drawer's interim dialogs, kept from the pre-parity drawer until their web surfaces land:
// the folder picker (T8.2) and the settings sheet (T10.1); the New session picker is
// NewSessionPicker.kt (ta-895, until T8.1's draft composer). The web renders these OUTSIDE `.session-sidebar`, so the host composes them
// outside the sidebar's token scope.

/** Interim folder picker for "Add workspace" (T8.2 replaces it with folder-picker-dialog). */
@Composable
internal fun FolderPickerDialog(
    directories: DirectoryListing?,
    current: String?,
    onDismiss: () -> Unit,
    onBrowse: (String) -> Unit,
    onChoose: (String) -> Unit,
) {
    val t = LocalTetherTokens.current
    val effectiveWorkspace = current
    val listing = directories
    val pickerCurrent = listing?.current ?: effectiveWorkspace
    TetherDialog(onDismiss = { onDismiss() }, title = "Choose a folder") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(TetherIcons.FolderOpen, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp))
            // ta-28i: folder names and paths from the server are code (every control a token), LTR.
            Text(
                pickerCurrent?.let { codeLabel(it) } ?: AnnotatedString("—"),
                color = t.muted,
                fontFamily = JetBrainsMono,
                fontSize = 11.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        listing?.parent?.let { parent ->
            FolderRow(
                icon = { Icon(TetherIcons.ArrowLeft, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp)) },
                name = "Parent folder",
                detail = "Go up one level",
                onClick = { onBrowse(parent) },
            )
        }
        listing?.entries?.forEach { entry ->
            FolderRow(
                icon = { Icon(TetherIcons.Folder, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp)) },
                name = entry.name,
                detail = entry.path,
                onClick = { onBrowse(entry.path) },
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
                onClick = { onDismiss() },
                classes = KeyClasses.ButtonSecondary,
                label = "Cancel",
            )
            TetherKey(
                onClick = {
                    pickerCurrent?.let(onChoose)
                    onDismiss()
                },
                classes = KeyClasses.ButtonPrimary,
                label = "Use this folder",
                icon = TetherIcons.Check,
                iconSize = 15.dp,
            )
        }
    }
}

/**
 * Interim settings sheet (theme, ended sessions, thinking, notifications) until T10.1. T15.4: public,
 * so the top bar's Settings (the shell) and the rail's footer open the same one.
 */
@Composable
fun InterimSettingsDialog(prefs: UiPrefs, onDismiss: () -> Unit) {
    val t = LocalTetherTokens.current
    val scope = rememberCoroutineScope()
    val showEnded by prefs.showEnded.collectAsStateWithLifecycle(initialValue = TetherPreferences.Default.showEndedSessions)
    val showThinking by prefs.showThinking.collectAsStateWithLifecycle(initialValue = TetherPreferences.Default.showThinking)
    val themeMode by prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.Default)
    val pushEnabled by prefs.pushEnabled.collectAsStateWithLifecycle(initialValue = true)
    val pushScope by prefs.pushScope.collectAsStateWithLifecycle(initialValue = PushScope.All)
    val pushPermissionAsked by prefs.pushPermissionAsked.collectAsStateWithLifecycle(initialValue = false)
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        scope.launch {
            if (!granted) prefs.setPushEnabled(false)
            prefs.setPushPermissionAsked(true)
        }
    }
    TetherDialog(onDismiss = { onDismiss() }, title = "Settings") {
        Text(
            "APPEARANCE",
            color = t.faint,
            fontFamily = Manrope,
            fontWeight = TetherWeights.strong,
            fontSize = 10.7.sp,
            letterSpacing = 0.08.em,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        // Settings → Appearance (hooks/use-preferences.ts THEME_MODES): Studio's lighting only.
        // One radio group, so TalkBack announces "n of 3".
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ThemeMode.entries.forEach { mode ->
                ThemeOption(
                    label = mode.label,
                    chosen = mode == themeMode,
                    onClick = { scope.launch { prefs.setThemeMode(mode) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { scope.launch { prefs.setShowEnded(!showEnded) } }
                .heightIn(min = TetherDimens.touchTargetDp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Show ended sessions",
                color = t.ink,
                fontFamily = Manrope,
                fontWeight = TetherWeights.name,
                fontSize = 13.1.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (showEnded) "ON" else "OFF",
                color = if (showEnded) t.violet else t.faint,
                fontFamily = Manrope,
                fontWeight = TetherWeights.strong,
                fontSize = 10.7.sp,
                letterSpacing = 0.06.em,
            )
        }
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

        Spacer(Modifier.height(16.dp))
        Text(
            "NOTIFICATIONS",
            color = t.faint,
            fontFamily = Manrope,
            fontWeight = TetherWeights.strong,
            fontSize = 10.7.sp,
            letterSpacing = 0.08.em,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        // Master toggle. On first enable, request POST_NOTIFICATIONS; if
        // denied the toggle reverts and the status line explains why.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (!pushEnabled) {
                        if (!pushPermissionAsked) {
                            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            scope.launch { prefs.setPushEnabled(true) }
                        }
                    } else {
                        scope.launch { prefs.setPushEnabled(false) }
                    }
                }
                .heightIn(min = TetherDimens.touchTargetDp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Notifications",
                color = t.ink,
                fontFamily = Manrope,
                fontWeight = TetherWeights.name,
                fontSize = 13.1.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (pushEnabled) "ON" else "OFF",
                color = if (pushEnabled) t.violet else t.faint,
                fontFamily = Manrope,
                fontWeight = TetherWeights.strong,
                fontSize = 10.7.sp,
                letterSpacing = 0.06.em,
            )
        }
        // Scope selector — disabled while the master toggle is off.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PushScope.entries.forEach { choice ->
                val chosen = choice == pushScope
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
                        .clickable(enabled = pushEnabled) {
                            scope.launch { prefs.setPushScope(choice) }
                        }
                        .heightIn(min = TetherDimens.touchTargetDp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (choice) {
                            PushScope.All -> "All sessions"
                            PushScope.Attached -> "Sessions opened on this phone"
                            PushScope.Pinned -> "Pinned sessions"
                        },
                        color = if (chosen) t.white else t.ink,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.name,
                        fontSize = 13.1.sp,
                        modifier = Modifier.weight(1f).graphicsLayer { alpha = if (pushEnabled) 1f else 0.5f },
                    )
                    if (chosen) StatusDot(t.violet, size = 6.4.dp)
                }
            }
        }
        // Status line. The runtime permission state is read here so the
        // line tracks a denial even after the sheet is reopened.
        val notificationManager = remember(context) {
            context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        }
        val permissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val statusLine = when {
            !pushEnabled -> "Off"
            !permissionGranted -> "Permission required."
            !notificationManager.areNotificationsEnabled() -> "Notifications disabled in system settings."
            else -> "Notifications on"
        }
        Text(
            statusLine,
            color = t.faint,
            fontFamily = Manrope,
            fontWeight = TetherWeights.body,
            fontSize = 11.2.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** One tappable row in the folder picker: icon + name over truncated path. */
@Composable
internal fun FolderRow(
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
                codeLabel(name),
                color = t.ink,
                fontFamily = Manrope,
                fontWeight = TetherWeights.name,
                fontSize = 13.1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                codeLabel(detail),
                color = t.faint,
                fontFamily = JetBrainsMono,
                fontSize = 10.4.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 32dp provider glyph circle: key-face, 1px line-strong, mono letter
 *  (.provider-glyph + the material layer's molded-round-cap treatment). */
@Composable
fun ProviderGlyph(glyph: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Box(
        modifier = modifier
            .size(32.dp)
            .background(t.keyFace, CircleShape)
            .border(1.dp, t.lineStrong, CircleShape)
            .clip(CircleShape),
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

/** One Appearance mode in Settings: a radio option, so the choice is announced, not only coloured. */
@Composable
internal fun ThemeOption(label: String, chosen: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Row(
        modifier = modifier
            .background(
                if (chosen) t.violetWash else t.keyFace,
                RoundedCornerShape(t.radiusSm),
            )
            .border(
                1.dp,
                if (chosen) t.violetStrong else t.keySide,
                RoundedCornerShape(t.radiusSm),
            )
            .selectable(selected = chosen, onClick = onClick, role = Role.RadioButton)
            .heightIn(min = TetherDimens.touchTargetDp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (chosen) t.white else t.ink,
            fontFamily = Manrope,
            fontWeight = TetherWeights.name,
            fontSize = 13.1.sp,
            modifier = Modifier.weight(1f),
        )
        if (chosen) StatusDot(t.violet, size = 6.4.dp)
    }
}
