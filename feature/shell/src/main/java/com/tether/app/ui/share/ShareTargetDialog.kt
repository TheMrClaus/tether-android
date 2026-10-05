package com.tether.app.ui.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.client.LabelText
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialog
import com.tether.app.ui.components.TetherDialogText
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights

/*
 * T11.2: where something shared into Tether from another app goes. The web has no share target; once
 * the content is in hand it is what the composer's attach and paste do, so the choice is only which
 * composer: a new session's (the draft sheet, as New session opens it) or an existing session's (as a
 * pick in the sidebar opens it). Nothing is sent: the composer opens with the items staged and the
 * text in its draft, and the user sends as usual.
 */

object ShareTargetTags {
    const val DIALOG = "share-target"
    const val NEW_SESSION = "share-target-new"
    const val CANCEL = "share-target-cancel"
    fun session(id: String) = "share-target-session-$id"
}

object ShareTargetCopy {
    const val TITLE = "Share to Tether"
    const val NEW_SESSION = "New session"
    const val NEW_SESSION_DETAIL = "Start a session with this"
    const val SESSIONS = "Or add to a session"
    const val NO_SESSIONS = "No sessions are running."
    const val CANCEL = "Cancel"

    /** What came in: "Text", "1 file", "Text and 3 files". */
    fun summary(hasText: Boolean, files: Int): String {
        val f = if (files == 1) "1 file" else "$files files"
        return when {
            hasText && files > 0 -> "Text and $f"
            files > 0 -> f
            else -> "Text"
        }
    }
}

/** The sessions offered: the running ones (the sidebar's default, ended hidden), most recent first. */
fun shareTargets(sessions: List<AgentSession>): List<AgentSession> =
    sessions.filter { it.status != "exited" }.sortedByDescending { it.lastMessageAt ?: it.updatedAt }

@Composable
fun ShareTargetDialog(
    summary: String,
    sessions: List<AgentSession>,
    onNewSession: () -> Unit,
    onSession: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val t = LocalTetherTokens.current
    TetherDialog(onDismiss = onDismiss, title = ShareTargetCopy.TITLE) {
        Column(Modifier.fillMaxWidth().testTag(ShareTargetTags.DIALOG)) {
            TetherDialogText(summary, Modifier.padding(bottom = 8.dp))
            TargetRow(
                icon = TetherIcons.Plus,
                name = AnnotatedString(ShareTargetCopy.NEW_SESSION),
                detail = AnnotatedString(ShareTargetCopy.NEW_SESSION_DETAIL),
                tag = ShareTargetTags.NEW_SESSION,
                onClick = onNewSession,
            )
            Text(
                ShareTargetCopy.SESSIONS,
                color = t.faint,
                fontFamily = Manrope,
                fontWeight = TetherWeights.name,
                fontSize = 11.2.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            if (sessions.isEmpty()) {
                Text(
                    ShareTargetCopy.NO_SESSIONS,
                    color = t.muted,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.body,
                    fontSize = 12.8.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            for (s in sessions) {
                TargetRow(
                    icon = TetherIcons.MessageSquare,
                    // ta-28i: server text by the label rule; the folder is code.
                    name = AnnotatedString(LabelText.title(s.name).ifEmpty { s.id }),
                    detail = codeLabel(s.cwd),
                    tag = ShareTargetTags.session(s.id),
                    onClick = { onSession(s.id) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TetherKey(
                    onClick = onDismiss,
                    classes = KeyClasses.ButtonSecondary,
                    label = ShareTargetCopy.CANCEL,
                    modifier = Modifier.testTag(ShareTargetTags.CANCEL),
                )
            }
        }
    }
}

@Composable
private fun TargetRow(icon: ImageVector, name: AnnotatedString, detail: AnnotatedString, tag: String, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(onClick = onClick)
            .heightIn(min = TetherDimens.touchTargetDp)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = t.ink, fontFamily = Manrope, fontWeight = TetherWeights.name, fontSize = 13.1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = t.faint, fontFamily = JetBrainsMono, fontSize = 10.4.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
