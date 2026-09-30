package com.tether.app.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.client.AttachmentSendResult
import com.tether.app.client.StagedAttachment
import com.tether.app.client.StagedAttachments
import com.tether.app.protocol.DelegateMention
import com.tether.app.protocol.helpers.AttachmentDraft
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherSheet
import com.tether.app.ui.components.TetherSheetRow
import com.tether.app.ui.components.TetherSheetSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.codeText
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/*
 * T7.4: the paperclip's "Add attachment" sheet (tether components/attach-sheet.tsx, the touch shell:
 * a phone and a tablet both answer `pointer: coarse`, so the image and file rows stay separate).
 * The rows, in the web's order: "Add image" (the Android Photo Picker: no storage permission),
 * "Paste image" (the clipboard's pictures only, as on the web) and "Upload file" (the Storage Access
 * Framework document picker).
 *
 * Logged divergence: the web's fourth row, "Add issue or PR", browses the workspace's GitHub issues
 * and pull requests (GET /api/github/issues, /api/github/pull-requests); the app has no GitHub work
 * client yet (T8.4), so the row is left out rather than drawn as a row that cannot work. It returns
 * with T8.4.
 */

internal const val ATTACH_SHEET_TAG = "attach-sheet"
internal const val ATTACH_ROW_IMAGES = "Add image"
internal const val ATTACH_ROW_PASTE = "Paste image"
internal const val ATTACH_ROW_FILES = "Upload file"
internal const val ATTACH_SHEET_TITLE = "Add attachment"

/** The sheet's rows. A tap closes the sheet first, then runs its row (attach-sheet.tsx run()). */
@Composable
internal fun ColumnScope.AttachSheetRows(onClose: () -> Unit, onPickImages: () -> Unit, onPasteImage: () -> Unit, onPickFiles: () -> Unit) {
    fun run(action: () -> Unit) {
        onClose()
        action()
    }
    TetherSheetRow(ATTACH_ROW_IMAGES, onClick = { run(onPickImages) }, icon = TetherIcons.Image)
    TetherSheetRow(ATTACH_ROW_PASTE, onClick = { run(onPasteImage) }, icon = TetherIcons.ClipboardPaste)
    TetherSheetRow(ATTACH_ROW_FILES, onClick = { run(onPickFiles) }, icon = TetherIcons.Paperclip)
}

/** The modal sheet (a bottom sheet on a phone, a centred card from 48rem). */
@Composable
internal fun AttachSheet(onDismiss: () -> Unit, onPickImages: () -> Unit, onPasteImage: () -> Unit, onPickFiles: () -> Unit) {
    TetherSheet(onDismiss = onDismiss, title = ATTACH_SHEET_TITLE) {
        Column(Modifier.testTag(ATTACH_SHEET_TAG)) { AttachSheetRows(onDismiss, onPickImages, onPasteImage, onPickFiles) }
    }
}

/** The sheet's surface drawn inline (goldens; the modal hosts the same surface in a window). */
@Composable
internal fun AttachSheetSurface(modifier: Modifier = Modifier, docked: Boolean) {
    TetherSheetSurface(title = ATTACH_SHEET_TITLE, modifier = modifier, docked = docked, onClose = {}) {
        AttachSheetRows({}, {}, {}, {})
    }
}

/**
 * The composer's attachments: what is staged for this session ([staged]), staging new picks
 * ([stage]: returns the flashes, the last is shown), removing one, and the ONE send that carries
 * them ([send]: an explicit Send only; see [com.tether.app.client.TetherClient.sendAttachments]).
 */
@Immutable
class ComposerAttachments(
    val staged: List<StagedAttachment>,
    val stage: suspend (List<AttachmentSource>) -> List<String>,
    val onRemove: (Long) -> Unit,
    val send: (text: String, mention: DelegateMention?) -> AttachmentSendResult,
) {
    companion object {
        val Unavailable = ComposerAttachments(emptyList(), { emptyList() }, {}, { _, _ -> AttachmentSendResult.NotConnected })
    }
}

/**
 * Stages picks into [store] for one session on the server [originNow] names. Picks are read one
 * batch at a time (a second pick waits for the first, so neither overwrites the other); a batch
 * whose set was dropped while it was being read (a server or session switch, a lock, a sign-out) is
 * discarded, never added to what replaced it.
 */
class AttachmentStager(
    private val store: StagedAttachments,
    private val originNow: () -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    suspend fun stage(sessionId: String, sources: List<AttachmentSource>): List<String> = mutex.withLock {
        if (sources.isEmpty()) return@withLock emptyList()
        val origin = originNow()
        val generation = store.generation
        val existing = store.items(origin, sessionId)
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        // Cancelled (the composer left, the session switched): the read stops at its next chunk,
        // and withContext throws, so nothing read is staged.
        val result = withContext(io) { AttachmentIntake.intake(sources, existing, store::newId, active = { job?.isActive != false }) }
        if (store.generation != generation || originNow() != origin) return@withLock result.flashes
        val now = store.items(origin, sessionId)
        if (result.added.isNotEmpty()) store.set(origin, sessionId, (now + result.added).take(AttachmentDraft.MAX_ATTACHMENTS))
        result.flashes
    }
}

/** Why a Send with attachments sent nothing (null: it was sent, or there was nothing to send). */
internal fun attachmentRefusalCopy(result: AttachmentSendResult): String? = when (result) {
    AttachmentSendResult.Sent, AttachmentSendResult.Empty -> null
    AttachmentSendResult.NotConnected -> "Not connected — the message and its attachments were not sent."
    AttachmentSendResult.NotLive -> "Catching up — the message and its attachments were not sent. Try again in a moment."
    AttachmentSendResult.Locked -> "This session can’t take messages from here — the attachments were not sent."
    AttachmentSendResult.Busy -> "Wait for the current turn to finish before sending attachments."
    AttachmentSendResult.PendingAhead -> "Your previous message is still being delivered — send the attachments once it arrives."
    AttachmentSendResult.TooLarge -> "This message is too large to send in one piece (at most ${AttachmentCopy.size(com.tether.app.client.AttachmentFrame.MAX_SEND_FRAME_BYTES)} encoded). Remove an attachment and try again."
    AttachmentSendResult.LinkBusy -> "Still sending your previous attachments — try again when that finishes."
    AttachmentSendResult.NotOffered -> "That agent isn’t offered for this session any more — nothing was sent."
}

/**
 * `.chat-attachment-chip` (globals.css 6942-7008): a 2rem thumbnail for a picture (decoded small
 * from the staged bytes) or a FileText glyph, the name (the code rule: an untrusted name shows every
 * control it holds, on one line) over its size, and the 1.6rem remove key.
 */
@Composable
internal fun StagedAttachmentChip(item: StagedAttachment, onRemove: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusMd)
    Row(
        modifier = Modifier
            .widthIn(max = 256.dp)
            .background(t.mineralDeep, shape)
            .border(1.dp, t.line, shape)
            .padding(horizontal = t.css.spaceSm, vertical = t.css.spaceXs)
            .testTag("staged-attachment"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        val thumb = rememberChipThumbnail(item)
        if (thumb != null) {
            Image(
                thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(t.radiusSm)).testTag("staged-attachment-thumb"),
            )
        } else {
            Icon(TetherIcons.FileText, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                codeText(item.attachment.name),
                color = t.ink,
                style = type.body.copy(fontSize = 12.8.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(AttachmentCopy.size(item.sizeBytes), color = t.muted, style = type.body.copy(fontSize = 11.2.sp), maxLines = 1)
        }
        // The name is already the cleaned wire name (no controls): safe inside the spoken label.
        TetherKey(
            onClick = onRemove,
            classes = KeyClasses.IconButton,
            icon = TetherIcons.X,
            iconSize = 14.dp,
            contentDescription = "Remove ${item.attachment.name}",
        )
    }
}

/** A staged picture's chip thumbnail: decoded off the main thread, sampled to at most [CHIP_THUMB_SIDE]. */
@Composable
private fun rememberChipThumbnail(item: StagedAttachment): ImageBitmap? {
    val state by produceState<ImageBitmap?>(null, item.id) {
        value = if (item.attachment.mediaType in MediaLimits.IMAGE_TYPES) withContext(Dispatchers.Default) { chipThumbnail(item.attachment.data) } else null
    }
    return state
}

internal const val CHIP_THUMB_SIDE = 96

/** Decode [base64] small (the staged bytes are at most 9 MB; the decode is sampled). Null: not a picture. */
internal fun chipThumbnail(base64: String): ImageBitmap? = try {
    val bytes = java.util.Base64.getDecoder().decode(base64)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > MediaLimits.MAX_IMAGE_PIXELS) {
        null
    } else {
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CHIP_THUMB_SIDE) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
} catch (_: OutOfMemoryError) {
    null
} catch (_: RuntimeException) {
    null
}
