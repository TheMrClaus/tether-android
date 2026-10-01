package com.tether.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.tether.app.ui.text.codeLabel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights

// The drawer's interim dialogs, kept from the pre-parity drawer until their web surfaces land:
// the folder picker (T8.2); Settings is feature/settings (T10.1); the New session picker is
// NewSessionPicker.kt's rows (ta-895; ta-abm: the draft composer sheet's provider stage). The web renders these OUTSIDE `.session-sidebar`, so the host composes them
// outside the sidebar's token scope.

/**
 * Interim folder picker for "Add workspace" and (ta-abm) the new-session sheet's "Browse for another
 * folder…" (T8.2 replaces it with folder-picker-dialog). [title] is the web's per-use title
 * (FolderPickerDialog's `title` prop: "Choose a working folder" from the draft composer).
 */
@Composable
fun FolderPickerDialog(
    directories: DirectoryListing?,
    current: String?,
    onDismiss: () -> Unit,
    onBrowse: (String) -> Unit,
    onChoose: (String) -> Unit,
    title: String = "Choose a folder",
) {
    val t = LocalTetherTokens.current
    val effectiveWorkspace = current
    val listing = directories
    val pickerCurrent = listing?.current ?: effectiveWorkspace
    TetherDialog(onDismiss = { onDismiss() }, title = title) {
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
