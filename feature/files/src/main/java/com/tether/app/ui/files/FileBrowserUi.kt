package com.tether.app.ui.files

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Share2
import com.tether.app.client.LabelText
import com.tether.app.client.TextCut
import com.tether.app.client.WorkspaceFileEntry
import com.tether.app.client.WorkspaceFiles
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.PerfDivider
import com.tether.app.ui.components.StudioDialog
import com.tether.app.ui.components.TetherDialogSurface
import com.tether.app.ui.components.TetherDialogText
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.components.hardShadow
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.text.CopyNoticeHost
import com.tether.app.ui.text.CopyNotices
import com.tether.app.ui.text.SafeCopyClipboard
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.appendSafe
import com.tether.app.ui.text.codeDirection
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.text.proseText
import com.tether.app.ui.text.styledDisplay
import com.tether.app.ui.text.tokenStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.buildAnnotatedString

/** Test tags for the browser's regions and controls. */
object FileBrowserTags {
    const val Dialog = "files-dialog"
    const val Breadcrumbs = "files-breadcrumbs"
    const val ListPane = "files-list-pane"
    const val Preview = "files-preview"
    const val Text = "files-text"
    const val Image = "files-image"
    const val MutationBanner = "files-mutation-banner"
    const val PreviewError = "files-preview-error"
    const val Uploading = "files-uploading"
    const val HiddenRemoved = "files-hidden-removed"
    fun row(path: String) = "files-row:$path"
}

/** The breakpoints the web's file browser uses (globals.css 3552, 3565; studio.css 953). */
internal object FileBrowserBreakpoints {
    /** `max-width: 47.9375rem`: master-detail (list or preview, never both). */
    val MasterDetail: Dp = 767.dp

    /** `max-width: 35rem`: the Modified column goes, the header subtitle clamps. */
    val Narrow: Dp = 560.dp

    /** Studio's phone query (`max-width: 640px`). */
    val StudioPhone: Dp = 640.dp
}

/** A CSS text role in the skin's UI face: [size] in sp (rem x 16 or Studio px), weight, tracking. */
@Composable
private fun ui(size: Float, weight: Int = 400, trackingEm: Float = 0f, lineHeight: Float? = null, mono: Boolean = false): TextStyle {
    val type = LocalTetherTypography.current
    return TextStyle(
        fontFamily = if (mono) type.mono else type.ui,
        fontSize = size.sp,
        fontWeight = FontWeight(weight),
        letterSpacing = trackingEm.em,
        lineHeight = lineHeight?.em ?: TextStyle.Default.lineHeight,
    )
}

private fun rem(r: Float) = r * 16f

/**
 * Consumes taps on a dialog case (so they never reach the scrim behind it) WITHOUT semantics: a
 * `clickable {}` here would merge every control of the dialog into one button for TalkBack.
 */
internal fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/** A tap on the scrim dismisses; like the swallow, no merged semantics (Back is the a11y path). */
internal fun Modifier.tapToDismiss(onDismiss: () -> Unit): Modifier = pointerInput(onDismiss) { detectTapGestures { onDismiss() } }

/**
 * `.icon-button`: a transparent 44dp control whose glyph takes the host's colour (the web's
 * buttons inherit `color`), `--graphite-raised` while pressed, 0.48 when disabled.
 */
@Composable
internal fun IconKey(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 17.dp,
    enabled: Boolean = true,
    selected: Boolean? = null,
) {
    val t = LocalTetherTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(TetherDimens.touchTargetDp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.48f }
            .clip(RoundedCornerShape(t.radiusSm))
            .background(if (pressed && enabled) t.graphiteRaised else Color.Transparent)
            .semantics {
                this.contentDescription = contentDescription
                if (selected != null) {
                    this.selected = selected
                    stateDescription = if (selected) "On" else "Off"
                }
            }
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (pressed && enabled) t.white else tint, modifier = Modifier.size(iconSize))
    }
}

/** The web's `.spin` LoaderCircle (900ms a turn); still under reduced motion. */
@Composable
internal fun Spinner(size: Dp, tint: Color) {
    val reduced = LocalReducedMotion.current
    val angle = if (reduced) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "spin")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spin").value
    }
    Icon(TetherIcons.LoaderCircle, contentDescription = null, tint = tint, modifier = Modifier.size(size).rotate(angle))
}

/**
 * The browser: the skin's scrim with the file-browser case centred in it — the inline form the
 * goldens shoot; [WorkspaceFileBrowser] hosts it in a window.
 */
@Composable
fun FileBrowserFrame(
    state: FileBrowserState,
    onClose: () -> Unit,
    onUpload: () -> Unit,
    modifier: Modifier = Modifier,
    env: FileFormatEnv = FileFormatEnv(),
) {
    val t = LocalTetherTokens.current
    BoxWithConstraints(modifier.fillMaxSize().background(dialogScrim(t)), contentAlignment = Alignment.Center) {
        val viewport = maxWidth
        val masterDetail = viewport <= FileBrowserBreakpoints.MasterDetail
        val narrow = viewport <= FileBrowserBreakpoints.Narrow
        val studioPhone = viewport <= FileBrowserBreakpoints.StudioPhone
        val full = state.previewFullscreen
        // globals.css 3348-3372 / 3553; studio.css 654, 954, 986.
        val (width, height) = when {
            full -> maxWidth to maxHeight
            studioPhone -> (maxWidth - 24.dp) to (maxHeight - 32.dp)
            else -> minOf(1400.dp, maxWidth - 48.dp) to minOf(820.dp, maxHeight - 48.dp)
        }
        val radius = when {
            full -> 0.dp
            else -> if (studioPhone) 14.dp else StudioDialog.radius
        }
        val shape = RoundedCornerShape(radius)
        Column(
            Modifier
                .testTag(FileBrowserTags.Dialog)
                .width(width)
                .height(height)
                .cssSurface(
                    shape, t.graphite,
                    null,
                    StudioDialog.shadows,
                )
                .clip(shape)
                // The <dialog> swallows taps: only Back and Close dismiss it.
                .swallowTaps(),
        ) {
            BrowserHeader(state, narrow, studioPhone, onClose)
            Breadcrumbs(state, studioPhone)
            val listShown = !full && !(masterDetail && state.selected != null)
            val previewShown = full || !masterDetail || state.selected != null
            Row(Modifier.fillMaxWidth().weight(1f)) {
                if (listShown) {
                    // grid-template-columns: minmax(20rem, 0.85fr) minmax(0, 1.35fr)
                    val listWidth = if (previewShown) maxOf(320.dp, width * (0.85f / 2.2f)) else width
                    ListPane(state, narrow, studioPhone, onUpload, env, Modifier.width(listWidth).fillMaxHeight())
                    if (previewShown) Box(Modifier.width(1.dp).fillMaxHeight().background(t.line))
                }
                if (previewShown) PreviewPane(state, masterDetail, studioPhone, env, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun BrowserHeader(state: FileBrowserState, narrow: Boolean, studioPhone: Boolean, onClose: () -> Unit) {
    val t = LocalTetherTokens.current
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = (if (studioPhone) 76.dp else 88.dp))
                .padding(
                    horizontal = if (studioPhone) 20.dp else 28.dp,
                    vertical = (if (studioPhone) 18.dp else 22.dp),
                ),
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceLg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Workspace files",
                    color = t.white,
                    style = ui(if (studioPhone) 20f else 22f, 700, -0.025f, 1.3f),
                    modifier = Modifier.semantics { heading() },
                )
                // ta-28i: the session's title by the label rule (no bidi control, no invisible).
                Text(
                    "Browse ${LabelText.title(state.sessionName).ifEmpty { "the current session" }} without leaving the console.",
                    color = t.muted,
                    style = ui(12f),
                    maxLines = if (narrow) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 5.dp)
                        .then(if (narrow) Modifier.widthIn(max = 288.dp) else Modifier),
                )
            }
            IconKey(TetherIcons.X, "Close file browser", t.ink, onClose, iconSize = 19.dp)
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(t.line))
        // `.file-browser-dialog > header::after`: the perforation over the header's bottom edge.
    }
}

@Composable
private fun Breadcrumbs(state: FileBrowserState, studioPhone: Boolean) {
    val t = LocalTetherTokens.current
    val listing = state.listing
    Column {
        Row(
            Modifier
                .testTag(FileBrowserTags.Breadcrumbs)
                .fillMaxWidth()
                .background(t.graphite)
                .heightIn(min = 54.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .semantics { contentDescription = "Workspace path" },
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (listing == null) {
                Text(codeLabel(state.cwd), color = t.faint, style = ui(rem(0.68f), mono = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                listing.breadcrumbs.forEachIndexed { index, crumb ->
                    if (index > 0) Icon(TetherIcons.ChevronRight, contentDescription = null, tint = t.ink, modifier = Modifier.size(14.dp))
                    val current = index == listing.breadcrumbs.lastIndex
                    Box(
                        Modifier
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(t.radiusSm))
                            .semantics { if (current) stateDescription = "Current folder" }
                            .clickable(role = Role.Button) { state.loadDirectory(crumb.path) }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            codeLabel(crumb.name),
                            color = if (current) t.white else t.muted,
                            style = ui(12f, if (current) 650 else 400, mono = true),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}

/** Grid tracks: name, size, modified (hidden when narrow), actions (globals.css 3445 / 3569). */
private class Tracks(val size: Dp, val modified: Dp?, val actions: Dp = 40.dp)

private fun tracksFor(narrow: Boolean) = if (narrow) Tracks(72.dp, null) else Tracks(88.dp, 168.dp)

@Composable
private fun ListPane(
    state: FileBrowserState,
    narrow: Boolean,
    studioPhone: Boolean,
    onUpload: () -> Unit,
    env: FileFormatEnv,
    modifier: Modifier,
) {
    val t = LocalTetherTokens.current
    val listing = state.listing
    val tracks = tracksFor(narrow)
    val inline = (if (studioPhone) 14.dp else 20.dp)
    Column(modifier.testTag(FileBrowserTags.ListPane).semantics { contentDescription = "Files and folders" }) {
        // Toolbar: Parent folder | n items + New folder, New file, Upload.
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = inline),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (studioPhone) 6.dp else t.css.spaceMd),
        ) {
            val upEnabled = listing?.parent != null && !state.loading
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .graphicsLayer { alpha = if (upEnabled) 1f else 0.48f }
                    .clip(RoundedCornerShape(t.radiusSm))
                    .clickable(enabled = upEnabled, role = Role.Button) { state.openParent() }
                    .padding(horizontal = t.css.spaceSm),
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TetherIcons.ArrowUp, contentDescription = null, tint = t.muted, modifier = Modifier.size(16.dp))
                Text("Parent folder", color = t.muted, style = ui(13f, 630), maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Text("${listing?.entries?.size ?: 0} items", color = t.faint, style = ui(12f), maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
                val ready = listing != null
                IconKey(TetherIcons.FolderPlus, "New folder", t.faint, { state.openNamePrompt(NamePromptMode.NewFolder) }, enabled = ready)
                IconKey(TetherIcons.FilePlus, "New file", t.faint, { state.openNamePrompt(NamePromptMode.NewFile) }, enabled = ready)
                IconKey(TetherIcons.Upload, "Upload files", t.faint, onUpload, enabled = ready && state.uploading == null)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        // Column heads (aria-hidden on the web: the rows carry their own words).
        Row(
            Modifier
                .fillMaxWidth()
                .background(t.mineral)
                .heightIn(min = 38.dp)
                .padding(horizontal = inline)
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val style = ui(11f, 700)
            val color = t.muted
            fun head(label: String) = label
            Text(head("Name"), color = color, style = style, modifier = Modifier.weight(1f))
            Text(head("Size"), color = color, style = style, modifier = Modifier.width(tracks.size))
            tracks.modified?.let { Text(head("Modified"), color = color, style = style, modifier = Modifier.width(it)) }
            Spacer(Modifier.width(tracks.actions))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        state.uploading?.let { name ->
            Row(
                Modifier
                    .testTag(FileBrowserTags.Uploading)
                    .fillMaxWidth()
                    .padding(horizontal = t.css.spaceLg, vertical = t.css.spaceSm)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spinner(14.dp, t.muted)
                val style = tokenStyle(t)
                val line = remember(name, style) {
                    buildAnnotatedString {
                        append("Uploading “")
                        appendSafe(name, SafeText.Rule.Line, style)
                        append("”…")
                    }
                }
                Text(line, color = t.muted, style = ui(rem(0.7f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (state.mutationError.isNotEmpty()) {
            Banner(state.mutationError, danger = true, modifier = Modifier.testTag(FileBrowserTags.MutationBanner))
        } else if (state.notice != null) {
            // r2: the file's name in the notice is code (one line), between our own words.
            val notice = state.notice!!
            val tokens = tokenStyle(t)
            val line = remember(notice, tokens) {
                buildAnnotatedString {
                    append(notice.before)
                    appendSafe(notice.name, SafeText.Rule.Line, tokens)
                    append(notice.after)
                }
            }
            Banner(line, danger = false, modifier = Modifier.testTag(FileBrowserTags.MutationBanner))
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.loading -> StateBlock(icon = null, message = "Reading workspace…", spinner = true)
                state.error.isNotEmpty() -> StateBlock(TetherIcons.FolderOpen, state.error, alert = true) {
                    TetherKey(onClick = state::retry, classes = KeyClasses.ButtonSecondary, label = "Try again", fixedVerb = false)
                }
                listing != null && listing.entries.isEmpty() -> StateBlock(TetherIcons.FolderOpen, "No visible files or folders here.")
                listing != null -> LazyColumn(Modifier.fillMaxSize()) {
                    items(listing.entries, key = { it.path }) { entry ->
                        EntryRow(entry, state.selected?.path == entry.path, tracks, inline, env, state)
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: WorkspaceFileEntry,
    selected: Boolean,
    tracks: Tracks,
    inline: Dp,
    env: FileFormatEnv,
    state: FileBrowserState,
) {
    val t = LocalTetherTokens.current
    val ink = if (selected) t.white else t.muted
    val meta = ui(11f)
    Column(Modifier.testTag(FileBrowserTags.row(entry.path))) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) t.violetWash else Color.Transparent)
                .heightIn(min = 54.dp)
                .padding(horizontal = inline),
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = TetherDimens.touchTargetDp)
                    .semantics(mergeDescendants = true) {
                        if (!entry.isDirectory) {
                            this.selected = selected
                        }
                    }
                    .clickable(role = Role.Button) { state.activate(entry) },
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(iconFor(entry), contentDescription = if (entry.isDirectory) "Folder" else null, tint = if (selected) t.violet else t.faint, modifier = Modifier.size(18.dp))
                // ta-28i: a file name is code: every bidi / invisible code point a token, laid out LTR.
                Text(
                    codeLabel(entry.name),
                    color = ink,
                    style = ui(13f, 620),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (entry.isDirectory) "Folder" else FileFormat.size(entry.size),
                color = t.faint,
                style = meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(tracks.size),
            )
            tracks.modified?.let {
                Text(FileFormat.modified(entry.mtime, env), color = t.faint, style = meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(it))
            }
            Box(Modifier.width(tracks.actions), contentAlignment = Alignment.Center) {
                IconKey(TetherIcons.EllipsisVertical, "Actions for ${SafeText.line(entry.name)}", ink, { state.openItemActions(entry) }, iconSize = 16.dp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}

private fun iconFor(entry: WorkspaceFileEntry): ImageVector = when {
    entry.isDirectory -> TetherIcons.Folder
    else -> when (FileKinds.previewKind(entry)) {
        PreviewKind.Image -> TetherIcons.FileImage
        PreviewKind.Video -> TetherIcons.FileVideo2
        PreviewKind.Text -> TetherIcons.FileCode2
        PreviewKind.Unsupported -> TetherIcons.File
    }
}

/** `.file-browser-preview-error` (also the mutation banner); neutral for a native notice. */
@Composable
private fun Banner(message: String, danger: Boolean, modifier: Modifier = Modifier) = Banner(proseText(message), danger, modifier)

/** [Banner] over an already drawn line (a server's error by the prose rule, a notice with a coded name). */
@Composable
private fun Banner(message: AnnotatedString, danger: Boolean, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (danger) t.dangerEdge else t.line))
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (danger) t.dangerWash else t.graphiteRaised)
                .padding(horizontal = t.css.spaceLg, vertical = t.css.spaceSm)
                .semantics(mergeDescendants = true) { liveRegion = if (danger) LiveRegionMode.Assertive else LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status is never colour alone: the danger line carries the alert glyph.
            if (danger) Icon(TetherIcons.CircleAlert, contentDescription = "Error", tint = t.danger, modifier = Modifier.size(14.dp))
            // ta-28i: a server's error is prose; a notice's file name is code (r2): no bidi control drawn raw.
            Text(message, color = if (danger) t.danger else t.muted, style = ui(rem(0.7f)))
        }
    }
}

/** `.file-browser-state` / `.file-browser-preview-empty`. */
@Composable
private fun StateBlock(
    icon: ImageVector?,
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    iconSize: Dp = 24.dp,
    spinner: Boolean = false,
    alert: Boolean = false,
    fill: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val t = LocalTetherTokens.current
    Column(
        modifier
            .fillMaxWidth()
            .then(if (fill) Modifier.fillMaxHeight() else Modifier)
            .heightIn(min = 192.dp)
            .padding(t.css.spaceXl)
            .semantics(mergeDescendants = true) { liveRegion = if (alert) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(t.css.spaceSm, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (spinner) Spinner(22.dp, t.faint) else if (icon != null) Icon(icon, contentDescription = null, tint = t.faint, modifier = Modifier.size(iconSize))
        if (title != null) {
            Text(
                title,
                color = t.white,
                style = ui(16f, 650),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = t.css.spaceXs).semantics { heading() },
            )
        }
        Text(
            proseText(message),
            color = t.faint,
            style = ui(13f, lineHeight = 1.55f),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 544.dp),
        )
        action?.invoke()
    }
}

@Composable
private fun PreviewPane(state: FileBrowserState, masterDetail: Boolean, studioPhone: Boolean, env: FileFormatEnv, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val selected = state.selected
    Column(
        modifier
            .testTag(FileBrowserTags.Preview)
            .background(t.mineral)
            .semantics { contentDescription = "File preview"; liveRegion = LiveRegionMode.Polite },
    ) {
        if (selected == null) {
            StateBlock(
                TetherIcons.FileText,
                "Images, videos, and small text files open here in read-only mode.",
                title = "Select a file to preview",
                iconSize = 30.dp,
                fill = true,
            )
            return@Column
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(
                    horizontal = 20.dp,
                    vertical = 14.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (masterDetail) IconKey(TetherIcons.ArrowLeft, "Back to file list", t.ink, state::clearSelection, iconSize = 18.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    codeLabel(selected.name),
                    color = t.white,
                    style = ui(14f, 650),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "${FileFormat.size(selected.size)} · Modified ${FileFormat.modified(selected.mtime, env)}",
                    color = t.faint,
                    style = ui(12f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.2.dp),
                )
            }
            val full = state.previewFullscreen
            IconKey(
                if (full) TetherIcons.Minimize2 else TetherIcons.Maximize2,
                if (full) "Exit fullscreen preview" else "Expand preview to fullscreen",
                t.ink,
                state::toggleFullscreen,
                selected = full,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        val pad = if (studioPhone) 16.dp else 24.dp
        // ta-28i: a copy from the text preview shows its hidden controls as tokens, with "Copy raw".
        val notices = remember(selected.path) { CopyNotices() }
        Box(Modifier.fillMaxWidth().weight(1f).padding(pad), contentAlignment = Alignment.Center) {
            PreviewContent(state, selected, notices)
            CopyNoticeHost(notices, Modifier.align(Alignment.BottomCenter).padding(t.css.spaceSm))
        }
        if (state.previewError.isNotEmpty()) Banner(state.previewError, danger = true, modifier = Modifier.testTag(FileBrowserTags.PreviewError))
    }
}

@Composable
private fun PreviewContent(state: FileBrowserState, entry: WorkspaceFileEntry, notices: CopyNotices) {
    val t = LocalTetherTokens.current
    val kind = FileKinds.previewKind(entry)
    val tooLargeText = kind == PreviewKind.Text && entry.size > WorkspaceFiles.MAX_TEXT_PREVIEW_BYTES
    when {
        kind == PreviewKind.Image && FileKinds.nativeImage(entry.name) -> {
            val image = state.image
            when {
                image != null -> Image(
                    image,
                    contentDescription = "Preview of ${SafeText.line(entry.name)}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .testTag(FileBrowserTags.Image)
                        .cssSurface(RoundedCornerShape(t.radiusMd), t.graphite, null, t.css.shadowRaised)
                        .clip(RoundedCornerShape(t.radiusMd)),
                )
                state.imageTooLarge -> StateBlock(
                    TetherIcons.FileImage,
                    "Image preview is limited to ${FileFormat.size(BrowserLimits.MAX_IMAGE_PREVIEW_BYTES)} and ${BrowserLimits.MAX_IMAGE_PIXELS / 1_000_000} megapixels in the app. The file remains unchanged.",
                    title = "Image is too large to preview",
                    iconSize = 30.dp,
                )
                state.previewLoading -> StateBlock(null, "Opening image preview…", spinner = true)
            }
        }
        kind == PreviewKind.Image || kind == PreviewKind.Video -> StateBlock(
            if (kind == PreviewKind.Video) TetherIcons.FileVideo2 else TetherIcons.FileImage,
            "Use Save to device or Share from the file's actions to open it in another app. The file remains unchanged.",
            title = if (kind == PreviewKind.Video) "Video preview is not available in the app yet" else "SVG preview is not available in the app",
            iconSize = 30.dp,
        )
        kind == PreviewKind.Text && tooLargeText -> StateBlock(
            TetherIcons.FileText,
            "Text preview is limited to 1 MB. The file remains unchanged.",
            title = "File is too large to preview",
            iconSize = 30.dp,
        )
        kind == PreviewKind.Text && state.previewLoading -> StateBlock(null, "Opening text preview…", spinner = true)
        kind == PreviewKind.Text && state.text != null -> TextPreview(state.text!!, notices)
        kind == PreviewKind.Unsupported -> StateBlock(
            TetherIcons.File,
            "This file type is shown as metadata only. The browser is read-only.",
            title = "No safe inline preview",
            iconSize = 30.dp,
        )
    }
}

/**
 * The web's `<pre><code>`: plain text only (never rendered as markup, never executed), wrapped
 * (`white-space: pre-wrap; overflow-wrap: anywhere`), selectable, `tab-size: 2`. Lines are lazy
 * so a 1 MB preview does not lay out at once.
 *
 * ta-28i (the Trojan Source case): the web draws the file raw, so a bidi override or isolate in a
 * source file reorders what the reader sees ("access level" checks that read as comments). Here a
 * file body is CODE ([SafeText.code]): every bidi control, format / invisible / default-ignorable
 * code point, lone surrogate and C0/C1 control (except TAB and the line break) is a visible
 * `--warning` token, and every line lays out LTR ([codeDirection]) whatever the UI direction; RTL
 * letters stay letters. Each line is its own layout, so nothing reaches past its line. A copy goes
 * through [SafeCopyClipboard]: the hidden controls as their visible tokens, a notice saying how
 * many, and the notice's "Copy raw" as the only way to the exact source (no long press copies raw).
 */
@Composable
private fun TextPreview(text: String, notices: CopyNotices) {
    val t = LocalTetherTokens.current
    val lines = remember(text) { previewPieces(text) }
    val shape = RoundedCornerShape(8.dp)
    val base = ui(13f, lineHeight = 1.75f, mono = true)
    val style = base.copy(textDirection = codeDirection)
    val tokens = tokenStyle(t)
    val clipboard = LocalClipboard.current
    // r2: the lines' own markers become their line breaks and tabs again first ([PreviewCopy]).
    val safeClipboard = remember(clipboard, notices) { PreviewCopy.Clipboard(SafeCopyClipboard(clipboard, notices)) }
    CompositionLocalProvider(LocalClipboard provides safeClipboard) {
        SelectionContainer {
            LazyColumn(
                Modifier
                    .testTag(FileBrowserTags.Text)
                    .fillMaxSize()
                    .clip(shape)
                    .background(t.graphite)
                    .then(Modifier)
                    .padding(20.dp),
            ) {
                itemsIndexed(lines) { _, piece ->
                    val shown = remember(piece, tokens) { styledDisplay(PreviewCopy.display(piece, last = piece === lines.last()), tokens) }
                    Text(shown, color = t.ink, style = style)
                }
            }
        }
    }
}

/**
 * One laid-out line of the text preview: [text] is source text (tabs kept), [end] the source line
 * break that follows it ("\n", "\r\n", or "" when the piece does not end its line: the last line, or
 * all but the last piece of a pathological long line).
 */
internal data class PreviewPiece(val text: String, val end: String = "")

/**
 * Split on newlines; a pathological single line is cut into pieces.
 * ta-28i: the CR of a CRLF goes with its line break (a lone CR stays, and shows as a token); a
 * piece is cut at a character-cluster boundary ([TextCut]), never inside a surrogate pair (which
 * would draw as two `U+D8xx` tokens) or between a letter and its accent. r2: bounded (TextCut backs
 * off at most 64 code points, never below the piece start), so a hostile 1 MiB line of combining
 * marks, ZWJs or flags is cut in linear time; each piece keeps its line break and tabs, for the copy.
 */
internal fun previewPieces(text: String): List<PreviewPiece> {
    val out = ArrayList<PreviewPiece>()
    val split = text.split('\n')
    for ((index, raw) in split.withIndex()) {
        val last = index == split.lastIndex
        val crlf = !last && raw.endsWith('\r')
        val line = if (crlf) raw.substring(0, raw.length - 1) else raw
        val end = when {
            last -> ""
            crlf -> "\r\n"
            else -> "\n"
        }
        if (line.length <= LINE_PIECE) {
            out += PreviewPiece(line, end)
        } else {
            var start = 0
            while (start < line.length) {
                val limit = minOf(line.length, start + LINE_PIECE)
                var cut = TextCut.boundaryAtOrBefore(line, limit, floor = start)
                if (cut <= start) cut = limit
                out += PreviewPiece(line.substring(start, cut), if (cut == line.length) end else "")
                start = cut
            }
        }
    }
    return out
}

/** The lines as drawn before r2 (tabs at two columns, no line breaks): what [previewPieces] shows. */
internal fun previewLines(text: String): List<String> = previewPieces(text).map { it.text.replace("\t", "  ") }

/**
 * ta-28i r2: copying ACROSS preview lines. Each line is its own Text in one SelectionContainer, and
 * the selection joins the selected parts of the Texts with a "\n" between them (whatever the file
 * had), so a CRLF lost its CR, a TAB came back as two spaces, and a pathological long line split
 * into pieces gained line breaks it never had: "Copy raw" was not the source. So each drawn piece
 * but the file's last carries an invisible END marker (the line's own "\n", its "\r\n", or "no
 * break": the piece continues the line), and each TAB (drawn at two columns, the web's
 * `tab-size: 2`) a marker before its two spaces; [Clipboard] turns each END marker and the
 * selection's "\n" after it into the source's break, and each tab marker and its spaces into the
 * TAB, before the safe copy ([SafeCopyClipboard]) sees the text. So the safe copy AND "Copy raw"
 * keep the file's line breaks, CRLFs and tabs. The markers are WORD JOINER + ZWNJ / ZWJ: zero-width,
 * and never in a code display otherwise (the code rule draws every WJ, ZWNJ and ZWJ of the file as
 * a token), so file content can never pass for one. A selection that starts inside a tab's two
 * spaces copies those spaces as spaces.
 */
internal object PreviewCopy {
    private const val MARK = SafeText.MARK
    private const val ZWNJ = '\u200C'
    private const val ZWJ = '\u200D'
    val LF_END = "$MARK$ZWNJ"
    val CRLF_END = "$MARK$ZWNJ$ZWNJ"
    val CONT_END = "$MARK$ZWNJ$ZWJ"
    val TAB = "$MARK$ZWJ  "

    /** What a piece draws: the code rule, tabs at two columns, its line break (or [last]: none) as a marker. */
    fun display(piece: PreviewPiece, last: Boolean = false): String {
        val code = SafeText.code(piece.text)
        val body = if (code.indexOf('\t') < 0) code else code.replace("\t", TAB)
        return when {
            piece.end == "\n" -> body + LF_END
            piece.end == "\r\n" -> body + CRLF_END
            last -> body
            else -> body + CONT_END
        }
    }

    /** A copied selection with the markers turned back into the source's line breaks and tabs. */
    fun decode(selected: CharSequence): String {
        val s = selected.toString()
        if (s.none { it == ZWNJ || it == ZWJ } && !s.endsWith(MARK)) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == MARK && i + 1 < s.length && s[i + 1] == ZWNJ) {
                val next = if (i + 2 < s.length) s[i + 2] else ' '
                when (next) {
                    ZWNJ -> out.append("\r\n")
                    ZWJ -> Unit // the piece continues its line
                    else -> out.append('\n')
                }
                i += if (next == ZWNJ || next == ZWJ) 3 else 2
                // The selection's own separator after this piece: the marker already said what the file had.
                if (i < s.length && s[i] == '\n') i++
                continue
            }
            if (c == MARK && i + 1 < s.length && s[i + 1] == ZWJ) {
                out.append('\t')
                i += 2
                var spaces = 0
                while (spaces < 2 && i < s.length && s[i] == ' ') {
                    i++
                    spaces++
                }
                continue
            }
            // Half a marker (a selection edge cut it): the file never held it.
            if (c == ZWNJ || c == ZWJ || (c == MARK && i == s.length - 1)) {
                i++
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** [delegate] (the safe copy) fed the [decode]d text. */
    class Clipboard(private val delegate: androidx.compose.ui.platform.Clipboard) : androidx.compose.ui.platform.Clipboard {
        override suspend fun getClipEntry(): androidx.compose.ui.platform.ClipEntry? = delegate.getClipEntry()

        override suspend fun setClipEntry(clipEntry: androidx.compose.ui.platform.ClipEntry?) {
            val data = clipEntry?.clipData
            val text = if (data != null && data.itemCount > 0) data.getItemAt(0).text else null
            if (text == null) return delegate.setClipEntry(clipEntry)
            val decoded = decode(text)
            if (decoded == text.toString()) return delegate.setClipEntry(clipEntry)
            delegate.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText(data!!.description?.label ?: "text", decoded)))
        }

        override val nativeClipboard get() = delegate.nativeClipboard
    }
}

private const val LINE_PIECE = 4_000

// ---------------------------------------------------------------------------------------------
// The small dialogs: per-item actions, name prompt, delete confirm (the `.confirm-dialog` chrome)
// ---------------------------------------------------------------------------------------------

/** The per-item "⋮" sheet: Rename / Copy to… / Move to… / (native) Save, Share / Delete. */
@Composable
fun ItemActionsContent(
    entry: WorkspaceFileEntry,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onSave: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    TetherDialogSurface(
        modifier = modifier,
        styledTitle = codeLabel(entry.name),
        footer = { TetherKey(onClick = onCancel, classes = KeyClasses.ButtonSecondary, label = "Cancel") },
    ) {
        Column(Modifier.padding(top = t.css.spaceMd), verticalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
            ActionItem(TetherIcons.Pencil, "Rename", onRename)
            ActionItem(TetherIcons.Copy, "Copy to…", onCopy)
            ActionItem(TetherIcons.FolderInput, "Move to…", onMove)
            if (onSave != null) ActionItem(Lucide.Download, "Save to device…", onSave)
            if (onShare != null) ActionItem(Lucide.Share2, "Share…", onShare)
            // `.file-browser-action-danger` loses the cascade to `:root .button-secondary` (color),
            // so the web draws Delete in the key's own ink; the glyph and the confirm step carry it.
            ActionItem(TetherIcons.Trash2, "Delete", onDelete)
        }
    }
}

@Composable
private fun ActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    TetherKey(
        onClick = onClick,
        classes = KeyClasses.ButtonSecondary,
        label = label,
        icon = icon,
        iconSize = 16.dp,
        fixedVerb = false,
        contentArrangement = Arrangement.spacedBy(t.css.spaceSm, Alignment.Start),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun NamePromptContent(
    prompt: NamePrompt,
    error: String,
    submitting: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = tokenStyle(LocalTetherTokens.current)
    val title = when (prompt.mode) {
        NamePromptMode.NewFolder -> AnnotatedString("New folder")
        NamePromptMode.NewFile -> AnnotatedString("New file")
        NamePromptMode.Rename -> nameTitle("Rename ", prompt.entry?.name.orEmpty(), "", tokens)
    }
    TetherDialogSurface(
        modifier = modifier,
        styledTitle = title,
        footer = {
            TetherKey(onClick = onCancel, classes = KeyClasses.ButtonSecondary, label = "Cancel")
            TetherKey(
                onClick = onSubmit,
                classes = KeyClasses.ButtonPrimary,
                label = if (prompt.mode == NamePromptMode.Rename) "Rename" else "Create",
                enabled = prompt.value.isNotBlank() && !submitting,
            )
        },
    ) {
        TetherInputWell(
            value = prompt.value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = "Name",
            modifier = Modifier.fillMaxWidth().padding(top = LocalTetherTokens.current.css.spaceMd),
        )
        if (prompt.hiddenRemoved) {
            // r2: the field shows the name without its hidden characters; say so, once.
            TetherDialogText(
                "Hidden characters were removed from this name. Renaming saves it without them.",
                modifier = Modifier.padding(top = LocalTetherTokens.current.css.spaceSm).testTag(FileBrowserTags.HiddenRemoved),
            )
        }
        if (error.isNotEmpty()) AlertText(error)
    }
}

@Composable
fun DeleteConfirmContent(
    entry: WorkspaceFileEntry,
    error: String,
    submitting: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ta-28i: the name the reader confirms is the name that is deleted: code rule, never reordered.
    val tokens = tokenStyle(LocalTetherTokens.current)
    TetherDialogSurface(
        modifier = modifier,
        styledTitle = remember(entry.name, tokens) { nameTitle("Delete ", entry.name, "?", tokens) },
        footer = {
            TetherKey(onClick = onCancel, classes = KeyClasses.ButtonSecondary, label = "Cancel")
            TetherKey(onClick = onConfirm, classes = KeyClasses.ButtonDanger, label = "Delete", enabled = !submitting)
        },
    ) {
        TetherDialogText(
            if (entry.isDirectory) "This folder and everything inside it will be permanently deleted." else "This file will be permanently deleted.",
        )
        if (error.isNotEmpty()) AlertText(error)
    }
}

/** A `<p role="alert">` inside `.confirm-dialog` (the dialog's muted paragraph style, announced). */
@Composable
private fun ColumnScope.AlertText(message: String) {
    val t = LocalTetherTokens.current
    Row(
        Modifier
            .padding(top = t.css.spaceSm)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(TetherIcons.CircleAlert, contentDescription = "Error", tint = t.muted, modifier = Modifier.padding(top = 3.dp).size(14.dp))
        TetherDialogText(proseText(message))
    }
}

/**
 * A dialog title around a file name: the name by the code rule (tokens styled) between our own
 * words. ta-28i: the name holds no raw bidi control or invisible, so nothing in it can move the
 * words around it; its own RTL letters order as letters do.
 */
internal fun nameTitle(before: String, name: String, after: String, tokens: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(before)
    appendSafe(name, SafeText.Rule.Line, tokens)
    append(after)
}

// ---------------------------------------------------------------------------------------------
// Move to… / Copy to…: the directory-only picker (the `.folder-dialog` chrome)
// ---------------------------------------------------------------------------------------------

@Composable
fun DestinationPickerFrame(
    picker: DestinationPicker,
    submitting: Boolean,
    onBrowse: (String) -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    BoxWithConstraints(modifier.fillMaxSize().background(dialogScrim(t)), contentAlignment = Alignment.Center) {
        val studioPhone = maxWidth <= FileBrowserBreakpoints.StudioPhone
        val width = if (studioPhone) maxWidth - 24.dp else minOf(608.dp, maxWidth - 24.dp)
        val maxH = maxHeight - when {
            studioPhone -> 32.dp
            else -> 48.dp
        }
        val shape = RoundedCornerShape((if (studioPhone) 14.dp else StudioDialog.radius))
        Column(
            Modifier
                .width(width)
                .heightIn(max = maxH)
                .cssSurface(
                    shape, t.graphite,
                    null,
                    StudioDialog.shadows,
                )
                .clip(shape)
                .swallowTaps(),
        ) {
            val inlinePad = (if (studioPhone) 20.dp else 28.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = (if (studioPhone) 76.dp else 88.dp))
                    .padding(horizontal = inlinePad, vertical = (if (studioPhone) 18.dp else 22.dp)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceLg),
            ) {
                Column(Modifier.weight(1f)) {
                    val mode = if (picker.mode == DestinationMode.Move) "Move" else "Copy"
                    Text(
                        codeLabel(picker.entry.name),
                        color = t.white,
                        style = ui(if (studioPhone) 20f else 22f, 700, -0.025f, 1.3f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading(); contentDescription = "$mode ${SafeText.line(picker.entry.name)}" },
                    )
                }
                IconKey(TetherIcons.X, "Close", t.ink, onClose, iconSize = 19.dp)
            }
            Seam(t)
            // .folder-current
            Row(
                Modifier
                    .padding(start = if (studioPhone) 20.dp else t.css.spaceLg, end = if (studioPhone) 20.dp else t.css.spaceLg, top = t.css.spaceLg)
                    .fillMaxWidth()
                    .border(1.dp, t.line)
                    .padding(t.css.spaceMd)
                    .semantics(mergeDescendants = true) { contentDescription = "Destination: ${SafeText.line(picker.path)}" },
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TetherIcons.FolderOpen, contentDescription = null, tint = t.muted, modifier = Modifier.size(17.dp))
                Text(codeLabel(picker.path), color = t.muted, style = ui(rem(0.7f), mono = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // .folder-list
            val listPad = if (studioPhone) 12.dp else t.css.spaceLg
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 192.dp)
                    .padding(start = listPad, end = listPad, top = t.css.spaceMd, bottom = t.css.spaceLg),
            ) {
                when {
                    picker.loading -> item { StateBlock(null, "Reading workspace…", spinner = true) }
                    picker.error.isNotEmpty() -> item { StateBlock(null, picker.error, alert = true) }
                    else -> {
                        picker.listing?.parent?.let { parent ->
                            item { FolderButton(TetherIcons.ArrowUp, "Parent folder", "Go up one level") { onBrowse(parent) } }
                        }
                        items(picker.listing?.entries.orEmpty().filter { it.isDirectory }, key = { it.path }) { dir ->
                            FolderButton(TetherIcons.Folder, dir.name, dir.path, code = true) { onBrowse(dir.path) }
                        }
                    }
                }
            }
            Seam(t)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = inlinePad, vertical = (if (studioPhone) 16.dp else 18.dp)),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TetherKey(onClick = onClose, classes = KeyClasses.ButtonSecondary, label = "Cancel")
                TetherKey(
                    onClick = onConfirm,
                    classes = KeyClasses.ButtonPrimary,
                    label = if (picker.mode == DestinationMode.Move) "Move here" else "Copy here",
                    icon = TetherIcons.Check,
                    iconSize = 17.dp,
                    enabled = !submitting,
                )
            }
        }
    }
}

/** A `--line-strong` rule with the lit lip beneath (`box-shadow: 0 1px 0 var(--seam-lip)`). */
@Composable
private fun Seam(t: TetherTokens) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
}

@Composable
private fun FolderButton(icon: ImageVector, title: String, detail: String, code: Boolean = false, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(t.radiusSm))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(t.css.spaceMd)
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(24.dp)) { Icon(icon, contentDescription = null, tint = t.muted, modifier = Modifier.size(17.dp)) }
            Column(verticalArrangement = Arrangement.spacedBy(3.2.dp)) {
                // ta-28i: a folder's name and path are code (server text); "Parent folder" is ours.
                Text(if (code) codeLabel(title) else AnnotatedString(title), color = t.muted, style = ui(rem(0.8f), 700), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (code) codeLabel(detail) else AnnotatedString(detail), color = t.faint, style = ui(rem(0.64f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}
