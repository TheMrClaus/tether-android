package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
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
    /** A9: the Send again key's tap (an explicit act, one frame under the original key); only drawn for a [FailedSend.canSendAgain] row. */
    val onSendAgain: (key: String) -> Unit = {},
    val onDismiss: (key: String) -> Unit,
) {
    val isEmpty: Boolean get() = pending.isEmpty() && failed.isEmpty()

    companion object {
        val None = SendBubbles(emptyList(), emptyList()) {}

        /** chat-view.tsx 90fbb9f :1983-1990: every client row filtered to this session, in order. */
        fun forSession(sessionId: String?, pending: List<PendingSendRow>, failed: List<FailedSend>, onSendAgain: (String) -> Unit = {}, onDismiss: (String) -> Unit): SendBubbles {
            if (sessionId == null) return None
            val p = pending.filter { it.sessionId == sessionId }
            val f = failed.filter { it.sessionId == sessionId }
            return if (p.isEmpty() && f.isEmpty()) None else SendBubbles(p, f, onSendAgain, onDismiss)
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

/**
 * ta-delivery V1 (spec 1, 5.1): `{summary} · Uploading — kept on this device until it reaches the server`, the same
 * words in the strip row and the transcript bubble; the percent is printed beside the bar, never in these words.
 * A record whose photos are held only in memory (no attachment spool) must not claim the device keeps them
 * (spec 4.1b I item 4).
 */
fun uploadingLabel(row: PendingSendRow): String {
    val summary = attachmentSummary(row.attachmentCount, row.imageCount, row.bytes)
    val tail = if (row.keptOnDevice) "Uploading — kept on this device until it reaches the server"
    else "Uploading — may be lost if the app closes before it reaches the server"
    return if (summary.isEmpty()) tail else "$summary · $tail"
}

/** The link state wins over an upload percent (V3): nothing is uploading while there is no link. */
private fun PendingSendRow.isUploading(): Boolean = uploadPercent != null && status != SendStatus.Waiting

/** chat-view.tsx 90fbb9f :1571-1576: the pending bubble's status line. */
fun pendingSendLabel(row: PendingSendRow): String {
    val summary = attachmentSummary(row.attachmentCount, row.imageCount, row.bytes)
    return when {
        // V3 first: with no link the percent stays out until the link is back.
        // §4.1d A.2: a record the device does not keep says so, never "your message will be sent automatically".
        row.status == SendStatus.Waiting -> if (row.keptOnDevice) BUBBLE_WAITING_LABEL else BUBBLE_WAITING_NOT_KEPT_LABEL
        // ta-delivery A2: the photos are still uploading in the background; nothing has been sent yet.
        row.uploadPercent != null -> uploadingLabel(row)
        summary.isNotEmpty() -> "Sending — $summary"
        else -> "Sending…"
    }
}

const val UPLOAD_PROGRESS_TAG = "upload-progress"
const val STRIP_WAITING_LABEL = "Waiting for link — kept on this device, sends when the connection returns"
const val STRIP_WAITING_NOT_KEPT_LABEL = "Waiting for link — sends when the connection returns; may be lost if the app closes first"
const val BUBBLE_WAITING_LABEL = "Waiting for link — reconnecting, your message will be sent automatically"
const val BUBBLE_WAITING_NOT_KEPT_LABEL = "Waiting for link — reconnecting; may be lost if the app closes first"

/** AW.4 / AW.5 (web C.1): why a message cannot be sent again; m = the record's attachment count (one reason for all of them). */
internal fun attachmentsGoneSentence(count: Int): String =
    if (count >= 2) "$count attachments are no longer on this device; attach them again and resend."
    else "An attachment is no longer on this device; attach it again and resend."

/** chat-view.tsx 90fbb9f :1596-1600: the failed bubble's status line. */
fun failedSendLabel(row: FailedSend): String {
    // AW.4: the web's C2 label for the same case (restored, bytes gone): the upload-failed lead, then the C.1 sentence.
    if (row.reason == FailedSendReason.PhotoLost) {
        val summary = attachmentSummary(row.attachmentCount, row.imageCount, row.bytes)
        return buildString {
            append("Not sent — the upload failed. ").append(attachmentsGoneSentence(row.attachmentCount))
            if (summary.isNotEmpty()) append(" · ").append(summary)
        }
    }
    // ta-delivery A2: the upload failed; the server's own reason follows (never the bytes, never a path).
    // spec 4.1b B.1: "Not sent — the upload failed." then " {reason}" when there is one, then " · {summary}".
    if (row.reason == FailedSendReason.Upload) {
        val reason = row.detail?.takeIf { it.isNotBlank() }
        val summary = attachmentSummary(row.attachmentCount, row.imageCount, row.bytes)
        return buildString {
            append("Not sent — the upload failed.")
            if (reason != null) append(' ').append(reason)
            if (summary.isNotEmpty()) append(" · ").append(summary)
        }
    }
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
        // §4.1d A.2: ANY waiting record the device does not keep turns the whole line to the not-kept words.
        if (rows.any { it.status == SendStatus.Waiting && !it.keptOnDevice }) STRIP_WAITING_NOT_KEPT_LABEL else STRIP_WAITING_LABEL
    } else {
        "Sending $sending message${if (sending > 1) "s" else ""}…"
    }
}

const val PENDING_SEND_TAG = "pending-send"
const val FAILED_SEND_TAG = "failed-send"
const val SEND_ROW_TAG = "composer-send-row"
const val SEND_AGAIN_TAG = "failed-send-again"

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
        // §4.1d B/A.3: one glyph per state (V3 alert in warning, V1 the static Upload, V2 the spinner); the composer strip
        // is the ONE announcer of a pending record, so this line is not a live region.
        val glyph = if (waiting) StatusGlyph.Alert else if (row.isUploading()) StatusGlyph.Upload else StatusGlyph.Spinner
        SendStatusLine(pendingSendLabel(row), look.style, if (waiting) LocalTetherTokens.current.warning else look.ink, glyph, announce = false)
        if (row.isUploading()) {
            Spacer(Modifier.height(4.8.dp))
            UploadProgressBar(row.uploadPercent ?: 0, look.style.copy(fontSize = 10.88.sp, lineHeight = 1.25.em), look.ink)
        }
    }
}

/**
 * chat-view.tsx 90fbb9f :1586-1606 FailedSendBubble: the words never silently cleared, on
 * `--graphite-raised` in `--ink` with a 1px `--brick` edge, the status in brick (globals.css
 * :4236-4250), and one action, Dismiss (`.chat-queue-remove`, :7084-7098: an X in muted).
 */
@Composable
internal fun FailedSendBubble(row: FailedSend, onDismiss: () -> Unit, modifier: Modifier = Modifier, onSendAgain: () -> Unit = {}) {
    FailedSendBubbleBody(row, onDismiss, onSendAgain, modifier)
}

@Composable
private fun FailedSendBubbleBody(row: FailedSend, onDismiss: () -> Unit, onSendAgain: () -> Unit, modifier: Modifier = Modifier) {
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
        // The operator's own words stay recoverable: selectable and copyable like every transcript row
        // (the Dismiss key below stays outside the selection, as the notice keys do).
        if (row.text.isNotEmpty()) SelectableRow { Text(proseText(row.text), style = look.style, color = look.ink) }
        SendStatusLine(failedSendLabel(row), look.style, t.brick, StatusGlyph.Alert, announce = true)
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
        // ta-delivery A9 (spec 5.4): "Send again" (RotateCw + words, neutral, 44dp) on its own row, only where the
        // client still holds the whole content; one explicit tap re-dispatches it under the same key.
        if (row.canSendAgain) {
            TetherKey(
                onClick = onSendAgain,
                classes = KeyClasses.ButtonSecondary,
                label = "Send again",
                icon = TetherIcons.RotateCw,
                iconSize = 13.dp,
                contentDescription = "Send again",
                modifier = Modifier.testTag(SEND_AGAIN_TAG),
            )
        }
    }
}

private enum class StatusGlyph { Spinner, Upload, Alert }

/** `.chat-send-status` (globals.css 90fbb9f :4241-4250): icon + words, 0.3rem apart and above, 0.68rem / 1.25. */
@Composable
private fun SendStatusLine(label: String, bubbleStyle: TextStyle, color: Color, glyph: StatusGlyph, announce: Boolean) {
    Spacer(Modifier.height(4.8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { if (announce) liveRegion = LiveRegionMode.Polite },
    ) {
        when (glyph) {
            StatusGlyph.Spinner -> SpinningIcon(TetherIcons.Loader, tint = color, size = 12.dp)
            StatusGlyph.Upload -> Icon(TetherIcons.Upload, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            StatusGlyph.Alert -> Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
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
        sends.failed.forEach { row -> androidx.compose.runtime.key("f:${row.key}") { FailedSendBubble(row, { sends.onDismiss(row.key) }, onSendAgain = { sends.onSendAgain(row.key) }) } }
    }
}

/**
 * chat-view.tsx 90fbb9f :3754-3768: `.chat-waiting.chat-send-row` above the composer (0.8rem, 620,
 * an icon then the words, globals.css :5823-5830, :10479): `--faint` with a spinner while sending,
 * `--warning` with the alert while waiting for the link (§14 :445; it was violet before §4.1d B4).
 */
@Composable
internal fun ComposerSendRow(rows: List<PendingSendRow>, modifier: Modifier = Modifier) {
    val label = sendRowLabel(rows) ?: return
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val waiting = rows.any { it.status == SendStatus.Waiting }
    val color = if (waiting) t.warning else t.faint
    val textStyle = TextStyle(fontFamily = type.body.fontFamily, fontSize = (0.8f * TetherTypography.SP_PER_REM).sp, fontWeight = FontWeight(620))
    // V3 wins over everything; otherwise every uploading row says V1 with its bar, and the rest is the V2 count.
    val uploading = if (waiting) emptyList() else rows.filter { it.isUploading() }
    val others = rows.size - uploading.size
    androidx.compose.foundation.layout.Column(modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        uploading.forEach { row ->
            androidx.compose.runtime.key("u:${row.key}") {
                androidx.compose.foundation.layout.Column(
                    Modifier.testTag(SEND_ROW_TAG),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // §4.1d B item 4: the polite region holds only the glyph and words; the bar (and its %) is a sibling.
                    Row(
                        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
                    ) {
                        Icon(TetherIcons.Upload, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                        Text(uploadingLabel(row), style = textStyle, color = color)
                    }
                    UploadProgressBar(row.uploadPercent ?: 0, textStyle, color)
                }
            }
        }
        if (uploading.isEmpty() || others > 0) {
            val text = if (uploading.isEmpty()) label else sendRowLabel(rows.filterNot { it.isUploading() }) ?: label
            Row(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag(SEND_ROW_TAG),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            ) {
                if (waiting) {
                    Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                } else {
                    SpinningIcon(TetherIcons.Loader, tint = color, size = 14.dp)
                }
                Text(text, style = textStyle, color = color)
            }
        }
    }
}

/**
 * Spec 5.1: a determinate 3dp bar (track `--line`, fill `--muted`) and the printed `{n}%`; never colour alone. The
 * progress role carries the same number the text prints.
 */
@Composable
internal fun UploadProgressBar(percent: Int, style: TextStyle, ink: Color, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val shown = percent.coerceIn(0, 100)
    Row(
        modifier
            .semantics(mergeDescendants = true) { progressBarRangeInfo = ProgressBarRangeInfo(shown / 100f, 0f..1f) }
            .testTag(UPLOAD_PROGRESS_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f).height(3.dp).background(t.line)) {
            Box(Modifier.fillMaxWidth(shown / 100f).height(3.dp).background(t.muted))
        }
        // The bar's range info is the one place the value is exposed; the printed number is not read a second time.
        Text("$shown%", style = style, color = ink, modifier = Modifier.clearAndSetSemantics {})
    }
}
