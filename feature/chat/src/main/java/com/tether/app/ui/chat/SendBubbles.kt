package com.tether.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.FailedSend
import com.tether.app.client.FailedSendReason
import com.tether.app.client.PendingSendRow
import com.tether.app.client.SendStatus
import com.tether.app.protocol.helpers.AttachmentDraft
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTypography
import com.tether.app.ui.text.proseText

/*
 * ta-coik.19 (web issue #135 at 90fbb9f): the visible send state. An unresolved send is a ghost
 * user bubble at the foot of the transcript ("Sending…" / "Waiting for link — …"), a send given up
 * on is a "Not delivered" bubble carrying the operator's own words with a dismiss, and the composer
 * says how many are still going out. Status is never colour alone: each state pairs an icon with
 * words (globals.css 90fbb9f :4225-4256).
 */

/** What the chat draws of its session's sends: the rows and the one action, dismiss. */
@Immutable
class SendBubbles(
    val pending: List<PendingSendRow>,
    val failed: List<FailedSend>,
    val onDismiss: (key: String) -> Unit,
) {
    val isEmpty: Boolean get() = pending.isEmpty() && failed.isEmpty()

    companion object {
        val None = SendBubbles(emptyList(), emptyList()) {}

        /** chat-view.tsx 90fbb9f :1983-1990: every client row filtered to this session, in order. */
        fun forSession(sessionId: String?, pending: List<PendingSendRow>, failed: List<FailedSend>, onDismiss: (String) -> Unit): SendBubbles {
            if (sessionId == null) return None
            val p = pending.filter { it.sessionId == sessionId }
            val f = failed.filter { it.sessionId == sessionId }
            return if (p.isEmpty() && f.isEmpty()) None else SendBubbles(p, f, onDismiss)
        }
    }
}

/**
 * chat-view.tsx 90fbb9f :1543-1553 attachmentSummary: "2 images · 3.0 MB", "1 file · 216 KB";
 * empty for a send with no attachments. All images say images, anything else says files.
 */
fun attachmentSummary(attachmentCount: Int, imageCount: Int, bytes: Double): String {
    if (attachmentCount == 0) return ""
    val noun = if (imageCount == attachmentCount) {
        if (attachmentCount == 1) "image" else "images"
    } else {
        if (attachmentCount == 1) "file" else "files"
    }
    return "$attachmentCount $noun · ${AttachmentDraft.humanSize(bytes)}"
}

/** chat-view.tsx 90fbb9f :1571-1576: the pending bubble's status line. */
fun pendingSendLabel(row: PendingSendRow): String {
    val summary = attachmentSummary(row.attachmentCount, row.imageCount, row.bytes)
    return when {
        row.status == SendStatus.Waiting -> "Waiting for link — reconnecting, your message will be sent automatically"
        summary.isNotEmpty() -> "Sending — $summary"
        else -> "Sending…"
    }
}

/** chat-view.tsx 90fbb9f :1596-1600: the failed bubble's status line. */
fun failedSendLabel(row: FailedSend): String {
    val summary = attachmentSummary(row.attachmentCount, row.imageCount, row.bytes)
    return if (row.reason == FailedSendReason.Link) {
        if (summary.isNotEmpty()) "Not sent — the link was down ($summary)" else "Not sent — the link was down"
    } else {
        if (summary.isNotEmpty()) "Not delivered ($summary)" else "Not delivered"
    }
}

/**
 * chat-view.tsx 90fbb9f :3757-3768: the composer's send-status row; `waiting` takes precedence (it
 * is the actionable state) and says so in words. Null when nothing of this session is unresolved.
 */
fun sendRowLabel(rows: List<PendingSendRow>): String? {
    if (rows.isEmpty()) return null
    val waiting = rows.count { it.status == SendStatus.Waiting }
    val sending = rows.size - waiting
    return if (waiting > 0) {
        "Waiting for link — $waiting message${if (waiting > 1) "s" else ""} will send automatically when the connection returns."
    } else {
        "Sending $sending message${if (sending > 1) "s" else ""}…"
    }
}

const val PENDING_SEND_TAG = "pending-send"
const val FAILED_SEND_TAG = "failed-send"
const val SEND_ROW_TAG = "composer-send-row"

private val DISMISS_TARGET = 44.dp

/**
 * chat-view.tsx 90fbb9f :1566-1584 PendingSendBubble: the operator's text in a user bubble, dimmed
 * while `sending` (`.chat-bubble-pending { opacity: 0.82 }`), full strength while `waiting`
 * (globals.css :4231-4235; its violet border-color has no width to show on, studio.css :379
 * `border: 0`). It leaves the instant the server's journaled proof arrives.
 */
@Composable
internal fun PendingSendBubble(row: PendingSendRow, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val look = bubbleLook(t, type, user = true, phone = currentLayoutClass() == TetherLayoutClass.Phone)
    val waiting = row.status == SendStatus.Waiting
    BubbleBox(look, alignEnd = true, modifier.alpha(if (waiting) 1f else 0.82f).testTag(PENDING_SEND_TAG)) {
        if (row.text.isNotEmpty()) Text(proseText(row.text), style = look.style, color = look.ink)
        SendStatusLine(pendingSendLabel(row), look.style, look.ink, spinning = !waiting)
    }
}

/**
 * chat-view.tsx 90fbb9f :1586-1606 FailedSendBubble: the words never silently cleared, on
 * `--graphite-raised` in `--ink` with a 1px `--brick` edge, the status in brick (globals.css
 * :4236-4250), and one action, Dismiss (`.chat-queue-remove`, :7084-7098: an X in muted).
 */
@Composable
internal fun FailedSendBubble(row: FailedSend, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val base = bubbleLook(t, type, user = true, phone = currentLayoutClass() == TetherLayoutClass.Phone)
    val look = BubbleLook(
        maxFraction = base.maxFraction,
        padding = base.padding,
        shape = base.shape,
        background = t.graphiteRaised,
        border = CssBorder(1.dp, t.brick),
        shadows = base.shadows,
        ink = t.ink,
        style = base.style,
    )
    BubbleBox(look, alignEnd = true, modifier.testTag(FAILED_SEND_TAG)) {
        if (row.text.isNotEmpty()) Text(proseText(row.text), style = look.style, color = look.ink)
        SendStatusLine(failedSendLabel(row), look.style, t.brick, spinning = false)
        // The web's 1.9rem key at the content edge; here a 44dp target (the app's minimum), shifted so
        // its X sits where the web's does: centred 0.95rem in from the edge.
        Box(
            Modifier
                .offset(x = (30.4.dp - DISMISS_TARGET) / 2)
                .size(DISMISS_TARGET)
                .clickable(role = Role.Button, onClick = onDismiss)
                .semantics { contentDescription = "Dismiss failed message" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(TetherIcons.X, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp))
        }
    }
}

/** `.chat-send-status` (globals.css 90fbb9f :4241-4250): icon + words, 0.3rem apart and above, 0.68rem / 1.25. */
@Composable
private fun SendStatusLine(label: String, bubbleStyle: TextStyle, color: Color, spinning: Boolean) {
    Spacer(Modifier.height(4.8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        if (spinning) {
            SpinningIcon(TetherIcons.Loader, tint = color, size = 12.dp)
        } else {
            Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        }
        Text(label, style = bubbleStyle.copy(fontSize = 10.88.sp, lineHeight = 1.25.em), color = color)
    }
}

/**
 * The bubbles in the order the web draws them (chat-view.tsx 90fbb9f :3730-3737): every pending
 * send, then every failed one. For the views that are not the transcript's list (the empty
 * conversation, a run tab, the loading states), which the web's bubbles follow all the same.
 */
@Composable
internal fun SendBubbleColumn(sends: SendBubbles, gap: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        sends.pending.forEach { row -> androidx.compose.runtime.key("p:${row.key}") { PendingSendBubble(row) } }
        sends.failed.forEach { row -> androidx.compose.runtime.key("f:${row.key}") { FailedSendBubble(row, { sends.onDismiss(row.key) }) } }
    }
}

/**
 * chat-view.tsx 90fbb9f :3754-3768: `.chat-waiting.chat-send-row` above the composer (0.8rem, 620,
 * an icon then the words, globals.css :5823-5830, :10479): `--faint` with a spinner while sending,
 * `--violet` with the alert while waiting for the link (:4255-4256).
 */
@Composable
internal fun ComposerSendRow(rows: List<PendingSendRow>, modifier: Modifier = Modifier) {
    val label = sendRowLabel(rows) ?: return
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val waiting = rows.any { it.status == SendStatus.Waiting }
    val color = if (waiting) t.violet else t.faint
    Row(
        modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag(SEND_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        if (waiting) {
            Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        } else {
            SpinningIcon(TetherIcons.Loader, tint = color, size = 14.dp)
        }
        Text(
            label,
            style = TextStyle(fontFamily = type.body.fontFamily, fontSize = (0.8f * TetherTypography.SP_PER_REM).sp, fontWeight = FontWeight(620)),
            color = color,
        )
    }
}
