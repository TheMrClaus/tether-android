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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.tether.app.client.GitHubWorkPrompt
import com.tether.app.client.LabelText
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.cssSurface
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
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.codeLabel
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
 * ta-coik.3: plus "Take photo" after "Add image" ([rememberCameraCapture]): on Android the web's file
 * input reaches the camera through Chrome's chooser, which the Photo Picker and the document picker
 * do not offer, so the app gives the camera its own row.
 *
 * T8.4: and the web's "Add issue or PR" after "Paste image" (attach-sheet.tsx 90fbb9f :259-261): the
 * sheet steps into "Attach issue or PR" (Back returns to the rows), lists the session folder's open
 * issues and pull requests with a search, and a pick closes the sheet and attaches the item's prompt
 * as `issue-N.md` / `pr-N.md` ([GitHubWorkAttachmentSource], chat-view.tsx :2695-2723). Drawn where
 * the composer lends it the reads ([AttachSheetGitHub]; the chat composer does, as the web's ChatView).
 */

internal const val ATTACH_SHEET_TAG = "attach-sheet"
internal const val ATTACH_ROW_IMAGES = "Add image"
internal const val ATTACH_ROW_PASTE = "Paste image"
internal const val ATTACH_ROW_FILES = "Upload file"
internal const val ATTACH_SHEET_TITLE = "Add attachment"

internal const val ATTACH_ROW_GITHUB_TAG = "attach-row-github"
internal const val ATTACH_GITHUB_TAG = "attach-github"
internal const val ATTACH_GITHUB_SEARCH_TAG = "attach-github-search"
internal const val ATTACH_GITHUB_RETRY_TAG = "attach-github-retry"
internal fun attachGitHubRowTag(selection: GitHubWorkSelection) =
    "attach-github-row:${if (selection is GitHubWorkSelection.Issue) "issue" else "pr"}-${selection.number}"

/**
 * T8.4: what the sheet's GitHub view needs: the session's folder ([cwd]), the reads' state
 * ([controller], kept by the composer so a re-open does not read again) and what a pick does ([onAttach]).
 */
@Immutable
class AttachSheetGitHub(val cwd: String, val controller: AttachGitHubController, val onAttach: (repository: String, GitHubWorkSelection) -> Unit)

/**
 * The sheet's rows. A tap closes the sheet first, then runs its row (attach-sheet.tsx run()); T8.4:
 * "Add issue or PR" ([onGitHub], when given) steps into the GitHub view instead, the sheet staying up.
 */
@Composable
internal fun ColumnScope.AttachSheetRows(
    onClose: () -> Unit,
    onPickImages: () -> Unit,
    onTakePhoto: () -> Unit,
    onPasteImage: () -> Unit,
    onPickFiles: () -> Unit,
    onGitHub: (() -> Unit)? = null,
) {
    fun run(action: () -> Unit) {
        onClose()
        action()
    }
    TetherSheetRow(ATTACH_ROW_IMAGES, onClick = { run(onPickImages) }, icon = TetherIcons.Image)
    TetherSheetRow(ATTACH_ROW_CAMERA, onClick = { run(onTakePhoto) }, icon = TetherIcons.Camera)
    TetherSheetRow(ATTACH_ROW_PASTE, onClick = { run(onPasteImage) }, icon = TetherIcons.ClipboardPaste)
    if (onGitHub != null) {
        TetherSheetRow(GitHubWorkCopy.ATTACH_ROW, onClick = onGitHub, icon = TetherIcons.GitPullRequestArrow, modifier = Modifier.testTag(ATTACH_ROW_GITHUB_TAG))
    }
    TetherSheetRow(ATTACH_ROW_FILES, onClick = { run(onPickFiles) }, icon = TetherIcons.Paperclip)
}

/** The modal sheet (a bottom sheet on a phone, a centred card from 48rem). */
@Composable
fun AttachSheet(
    onDismiss: () -> Unit,
    onPickImages: () -> Unit,
    onTakePhoto: () -> Unit,
    onPasteImage: () -> Unit,
    onPickFiles: () -> Unit,
    github: AttachSheetGitHub? = null,
) {
    var githubView by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // attach-sheet.tsx close(): the read in flight stops.
    val dismiss = {
        github?.controller?.cancel()
        onDismiss()
    }
    val back = {
        githubView = false
        query = ""
    }
    TetherSheet(
        onDismiss = dismiss,
        title = if (githubView) GitHubWorkCopy.ATTACH_TITLE else ATTACH_SHEET_TITLE,
        onBack = if (githubView) back else null,
        backLabel = GitHubWorkCopy.ATTACH_BACK,
    ) {
        if (githubView && github != null) {
            AttachGitHubList(github.controller, query, { query = it }, retry = { github.controller.open(github.cwd) }) { repository, selection ->
                dismiss()
                github.onAttach(repository, selection)
            }
        } else {
            Column(Modifier.testTag(ATTACH_SHEET_TAG)) {
                AttachSheetRows(
                    dismiss, onPickImages, onTakePhoto, onPasteImage, onPickFiles,
                    onGitHub = github?.let { g ->
                        {
                            // :189-192 openGitHub.
                            githubView = true
                            query = ""
                            g.controller.open(g.cwd)
                        }
                    },
                )
            }
        }
    }
}

/** The sheet's surface drawn inline (goldens; the modal hosts the same surface in a window). */
@Composable
internal fun AttachSheetSurface(modifier: Modifier = Modifier, docked: Boolean, github: Boolean = true) {
    TetherSheetSurface(title = ATTACH_SHEET_TITLE, modifier = modifier, docked = docked, onClose = {}) {
        AttachSheetRows({}, {}, {}, {}, {}, onGitHub = if (github) ({}) else null)
    }
}

/** T8.4: the GitHub view drawn inline (goldens). */
@Composable
internal fun AttachGitHubSurface(controller: AttachGitHubController, query: String, modifier: Modifier = Modifier, docked: Boolean) {
    TetherSheetSurface(title = GitHubWorkCopy.ATTACH_TITLE, modifier = modifier, docked = docked, onClose = {}, onBack = {}, backLabel = GitHubWorkCopy.ATTACH_BACK) {
        AttachGitHubList(controller, query, {}, retry = {}) { _, _ -> }
    }
}

/** attach-sheet.tsx :269-330: the search and the list (loading, the error and Try again, no repository, none, the rows). */
@Composable
private fun ColumnScope.AttachGitHubList(
    c: AttachGitHubController,
    query: String,
    onQuery: (String) -> Unit,
    retry: () -> Unit,
    onPick: (String, GitHubWorkSelection) -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    GitHubSearchField(query, onQuery)
    val repository = c.repository
    val issues = c.issues.filter { matchesGitHubQuery(query, it.number, it.title) }
    val pulls = c.pullRequests.filter { matchesGitHubQuery(query, it.number, it.title) }
    Column(Modifier.testTag(ATTACH_GITHUB_TAG).padding(top = t.css.spaceSm)) {
        @Composable
        fun note(text: String) = Text(
            text,
            color = t.muted,
            style = type.body.copy(fontSize = 13.12.sp),
            modifier = Modifier.padding(t.css.spaceMd).semantics { liveRegion = LiveRegionMode.Polite },
        )
        when {
            c.loading -> note(GitHubWorkCopy.LOADING_BOTH)
            c.error.isNotEmpty() -> {
                note(c.error)
                TetherKey(
                    onClick = retry,
                    classes = KeyClasses.ButtonSecondary,
                    label = GitHubWorkCopy.TRY_AGAIN,
                    modifier = Modifier.padding(horizontal = t.css.spaceMd).testTag(ATTACH_GITHUB_RETRY_TAG),
                )
            }
            repository == null -> note(GitHubWorkCopy.NO_REPO)
            issues.isEmpty() && pulls.isEmpty() -> note(if (GitHubWorkPrompt.jsTrim(query).isNotEmpty()) GitHubWorkCopy.NO_MATCHES else GitHubWorkCopy.NONE_OPEN)
            else -> {
                issues.forEach { issue ->
                    val selection = GitHubWorkSelection.Issue(issue)
                    TetherSheetRow(
                        "#${issue.number} · ${LabelText.title(issue.title)}",
                        onClick = { onPick(repository, selection) },
                        icon = TetherIcons.CircleDot,
                        modifier = Modifier.testTag(attachGitHubRowTag(selection)),
                    )
                }
                pulls.forEach { pr ->
                    val selection = GitHubWorkSelection.PullRequest(pr)
                    TetherSheetRow(
                        "#${pr.number} · ${LabelText.title(pr.title)}",
                        onClick = { onPick(repository, selection) },
                        icon = if (pr.isDraft) TetherIcons.GitPullRequestDraft else TetherIcons.GitPullRequest,
                        modifier = Modifier.testTag(attachGitHubRowTag(selection)),
                    )
                }
            }
        }
    }
}

/** `.attach-sheet-search`: the Search glyph and the field ("Search issues and PRs…"). */
@Composable
private fun GitHubSearchField(value: String, onValue: (String) -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(t.radiusMd)
    val style = type.body.copy(fontSize = 13.76.sp, color = t.ink)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .cssSurface(shape, t.mineralDeep, CssBorder(1.dp, if (focused) t.violetStrong else t.line), emptyList())
            .padding(horizontal = t.css.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Icon(TetherIcons.Search, contentDescription = null, tint = t.faint, modifier = Modifier.size(14.dp))
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(t.violet),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).semantics { contentDescription = GitHubWorkCopy.SEARCH_LABEL }.testTag(ATTACH_GITHUB_SEARCH_TAG),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(GitHubWorkCopy.SEARCH_PLACEHOLDER, style = style.copy(color = t.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
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
 * whose set was dropped while it was being read (a server or session switch, a lock, a sign-out:
 * each bumps [StagedAttachments.generation], staged or not) is discarded, never added to what
 * replaced it. r2: [allowed] must also still hold for the session when the read is done (it is
 * the one selected, listed and unlocked), or the batch is discarded.
 */
class AttachmentStager(
    private val store: StagedAttachments,
    private val originNow: () -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val allowed: (sessionId: String) -> Boolean = { true },
    private val limits: ReadLimits = ReadLimits.DEFAULT,
) {
    private val mutex = Mutex()

    suspend fun stage(sessionId: String, sources: List<AttachmentSource>): List<String> = mutex.withLock {
        if (sources.isEmpty() || !allowed(sessionId)) return@withLock emptyList()
        val origin = originNow()
        val generation = store.generation
        val existing = store.items(origin, sessionId)
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        // Cancelled (the composer left, the session switched): the intake stops waiting for the
        // provider within ReadLimits.pollMs, even one stuck in its query or open (r3 F1: those run
        // on provider-call threads it walks away from), and withContext throws, so nothing read is
        // staged and the lock is released. A stalled provider is walked away from the same way.
        val result = withContext(io) { AttachmentIntake.intake(sources, existing, store::newId, active = { job?.isActive != false }, limits = limits) }
        if (store.generation != generation || originNow() != origin || !allowed(sessionId)) return@withLock result.flashes
        val now = store.items(origin, sessionId)
        if (result.added.isNotEmpty()) store.set(origin, sessionId, (now + result.added).take(AttachmentDraft.MAX_ATTACHMENTS))
        result.flashes
    }
}

/** Why a Send with attachments sent nothing (null: it was sent, or there was nothing to send). */
internal fun attachmentRefusalCopy(result: AttachmentSendResult): String? = when (result) {
    AttachmentSendResult.Sent, AttachmentSendResult.Empty -> null
    AttachmentSendResult.NotConnected -> "Not connected — the message and its attachments were not sent."
    // ta-coik.25: NotLive means only "drawn for another server" (RealTetherClient sendAttachments); the
    // web sends on any open link, a session still catching up included.
    AttachmentSendResult.NotLive -> OTHER_SERVER_NOT_SENT
    AttachmentSendResult.Locked -> "This session can’t take messages from here — the attachments were not sent."
    AttachmentSendResult.Busy -> "Wait for the current turn to finish before sending attachments."
    AttachmentSendResult.TooLarge -> "This message is too large to send in one piece (at most ${AttachmentCopy.size(com.tether.app.client.AttachmentFrame.MAX_SEND_FRAME_BYTES)} encoded). Remove an attachment and try again."
    AttachmentSendResult.LinkBusy -> "Still sending your previous attachments — try again when that finishes."
    AttachmentSendResult.NotOffered -> "That agent isn’t offered for this session any more — nothing was sent."
}

/**
 * `.chat-attachment-chip` (globals.css 6942-7008): a 2rem thumbnail for a picture (decoded small
 * from the staged bytes) or a FileText glyph, the name (the one-line rule, `codeLabel`: an untrusted
 * name shows every control it holds, TAB / LF / CR included, as a token, LTR) over its size, and the
 * 1.6rem remove key (its label names the file by the same rule).
 */
@Composable
fun StagedAttachmentChip(item: StagedAttachment, onRemove: () -> Unit) {
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
                codeLabel(item.attachment.name),
                color = t.ink,
                style = type.body.copy(fontSize = 12.8.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(AttachmentCopy.size(item.sizeBytes), color = t.muted, style = type.body.copy(fontSize = 11.2.sp), maxLines = 1)
        }
        // The one-line rule in the spoken label too (ta-28i): a TAB / LF / CR is named, not read out as a break.
        TetherKey(
            onClick = onRemove,
            classes = KeyClasses.IconButton,
            icon = TetherIcons.X,
            iconSize = 14.dp,
            contentDescription = "Remove ${SafeText.line(item.attachment.name)}",
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

/** L1 (r2): the most a chip thumbnail's decoded bitmap may take (192 × 192 ARGB is 144 KiB). */
internal const val CHIP_THUMB_MAX_BYTES: Long = 256L * 1024

/**
 * L1 (r2): the power-of-two sample for a [width] × [height] chip thumbnail, bounded on the OUTPUT:
 * both decoded sides at most 2 × [CHIP_THUMB_SIDE] and the bitmap at most [CHIP_THUMB_MAX_BYTES]
 * ([BoundedMediaDecoder.plan]). A bound on the short side alone let a crafted wide picture (a few KB
 * of GIF, 65535 × 191) decode at about 50 MB per chip. Null: not drawn (past the pixel cap).
 */
internal fun chipThumbnailSample(width: Int, height: Int, bytesPerPixel: Int = 4): Int? =
    BoundedMediaDecoder.plan(width, height, bytesPerPixel, CHIP_THUMB_SIDE * 2, CHIP_THUMB_MAX_BYTES)

/** Decode [base64] small (the staged bytes are at most 9 MB; the decode is sampled). Null: not a picture. */
internal fun chipThumbnail(base64: String): ImageBitmap? = try {
    val bytes = java.util.Base64.getDecoder().decode(base64)
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sample = chipThumbnailSample(bounds.outWidth, bounds.outHeight, if (bounds.outConfig == android.graphics.Bitmap.Config.RGBA_F16) 8 else 4)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || sample == null) {
        null
    } else {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        when {
            bitmap == null -> null
            // A decoder that did not honour the sample: never kept.
            bitmap.allocationByteCount > CHIP_THUMB_MAX_BYTES -> {
                bitmap.recycle()
                null
            }
            else -> bitmap.asImageBitmap()
        }
    }
} catch (_: OutOfMemoryError) {
    null
} catch (_: RuntimeException) {
    null
}
