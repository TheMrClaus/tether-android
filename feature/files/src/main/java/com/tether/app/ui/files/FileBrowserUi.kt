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
import com.tether.app.ui.theme.ThemeFamily

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

private val TetherTokens.studio: Boolean get() = skin.family == ThemeFamily.Studio

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
        val studioPhone = t.studio && viewport <= FileBrowserBreakpoints.StudioPhone
        val full = state.previewFullscreen
        // globals.css 3348-3372 / 3553; studio.css 654, 954, 986.
        val (width, height) = when {
            full -> maxWidth to maxHeight
            t.studio && studioPhone -> (maxWidth - 24.dp) to (maxHeight - 32.dp)
            t.studio -> minOf(1400.dp, maxWidth - 48.dp) to minOf(820.dp, maxHeight - 48.dp)
            masterDetail -> (maxWidth - 8.dp) to (maxHeight - 8.dp)
            else -> minOf(1408.dp, maxWidth - 24.dp) to minOf(768.dp, maxHeight - 24.dp)
        }
        val radius = when {
            full -> 0.dp
            t.studio -> if (studioPhone) 14.dp else StudioDialog.radius
            else -> t.radiusLg
        }
        val shape = RoundedCornerShape(radius)
        Column(
            Modifier
                .testTag(FileBrowserTags.Dialog)
                .width(width)
                .height(height)
                .cssSurface(
                    shape, t.graphite,
                    if (t.studio || full) null else CssBorder(1.dp, t.keySide),
                    if (t.studio) StudioDialog.shadows else listOf(hardShadow(1.dp, t.litStrong, inset = true)) + t.css.shadowModal,
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
                .heightIn(min = if (t.studio) (if (studioPhone) 76.dp else 88.dp) else 68.dp)
                .padding(
                    horizontal = when {
                        t.studio -> if (studioPhone) 20.dp else 28.dp
                        narrow -> t.css.spaceMd
                        else -> t.css.spaceLg
                    },
                    vertical = if (t.studio) (if (studioPhone) 18.dp else 22.dp) else t.css.spaceMd,
                ),
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceLg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Workspace files",
                    color = t.white,
                    style = if (t.studio) {
                        ui(if (studioPhone) 20f else 22f, 700, -0.025f, 1.3f)
                    } else {
                        ui(rem(1.15f), 680, -0.02f)
                    },
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "Browse ${state.sessionName.ifEmpty { "the current session" }} without leaving the console.",
                    color = t.muted,
                    style = if (t.studio) ui(12f) else ui(rem(0.75f)),
                    maxLines = if (narrow) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = if (t.studio) 5.dp else 3.2.dp)
                        .then(if (narrow) Modifier.widthIn(max = 288.dp) else Modifier),
                )
            }
            IconKey(TetherIcons.X, "Close file browser", t.ink, onClose, iconSize = 19.dp)
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(t.line))
        // `.file-browser-dialog > header::after`: the perforation over the header's bottom edge.
        if (!t.studio) PerfDivider(Modifier.align(Alignment.BottomStart).offset(y = 1.dp))
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
                .background(if (t.studio) t.graphite else t.graphiteRaised)
                .heightIn(min = if (t.studio) 54.dp else 44.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = if (t.studio) 20.dp else t.css.spaceMd)
                .semantics { contentDescription = "Workspace path" },
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (listing == null) {
                Text(state.cwd, color = t.faint, style = ui(rem(0.68f), mono = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                listing.breadcrumbs.forEachIndexed { index, crumb ->
                    if (index > 0) Icon(TetherIcons.ChevronRight, contentDescription = null, tint = t.ink, modifier = Modifier.size(14.dp))
                    val current = index == listing.breadcrumbs.lastIndex
                    Box(
                        Modifier
                            .heightIn(min = if (t.studio) 44.dp else 32.dp)
                            .clip(RoundedCornerShape(t.radiusSm))
                            .semantics { if (current) stateDescription = "Current folder" }
                            .clickable(role = Role.Button) { state.loadDirectory(crumb.path) }
                            .padding(horizontal = if (t.studio) 10.dp else t.css.spaceSm),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            crumb.name,
                            color = if (current) t.white else t.muted,
                            style = ui(if (t.studio) 12f else rem(0.68f), if (current) 650 else 400, mono = true),
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
    val inline = if (t.studio) (if (studioPhone) 14.dp else 20.dp) else t.css.spaceMd
    Column(modifier.testTag(FileBrowserTags.ListPane).semantics { contentDescription = "Files and folders" }) {
        // Toolbar: Parent folder | n items + New folder, New file, Upload.
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (t.studio) 56.dp else 44.dp)
                .padding(horizontal = inline),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (studioPhone) 6.dp else t.css.spaceMd),
        ) {
            val upEnabled = listing?.parent != null && !state.loading
            Row(
                Modifier
                    .heightIn(min = if (t.studio) 44.dp else 36.dp)
                    .graphicsLayer { alpha = if (upEnabled) 1f else 0.48f }
                    .clip(RoundedCornerShape(t.radiusSm))
                    .clickable(enabled = upEnabled, role = Role.Button) { state.openParent() }
                    .padding(horizontal = t.css.spaceSm),
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TetherIcons.ArrowUp, contentDescription = null, tint = t.muted, modifier = Modifier.size(16.dp))
                Text("Parent folder", color = t.muted, style = if (t.studio) ui(13f, 630) else ui(rem(0.7f), 630), maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Text("${listing?.entries?.size ?: 0} items", color = t.faint, style = if (t.studio) ui(12f) else ui(rem(0.67f)), maxLines = 1)
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
                .background(if (t.studio) t.mineral else Color.Transparent)
                .heightIn(min = if (t.studio) 38.dp else 32.dp)
                .padding(horizontal = inline)
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val style = if (t.studio) ui(11f, 700) else ui(rem(0.61f), 700, 0.07f)
            val color = if (t.studio) t.muted else t.faint
            fun head(label: String) = if (t.studio) label else label.uppercase()
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
                Text("Uploading “$name”…", color = t.muted, style = ui(rem(0.7f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (state.mutationError.isNotEmpty()) {
            Banner(state.mutationError, danger = true, modifier = Modifier.testTag(FileBrowserTags.MutationBanner))
        } else if (state.notice.isNotEmpty()) {
            Banner(state.notice, danger = false, modifier = Modifier.testTag(FileBrowserTags.MutationBanner))
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
    val meta = if (t.studio) ui(11f) else ui(rem(0.65f))
    Column(Modifier.testTag(FileBrowserTags.row(entry.path))) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) t.violetWash else Color.Transparent)
                .heightIn(min = if (t.studio) 54.dp else 44.dp)
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
                Text(
                    entry.name,
                    color = ink,
                    style = if (t.studio) ui(13f, 620) else ui(rem(0.76f), 620),
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
                IconKey(TetherIcons.EllipsisVertical, "Actions for ${entry.name}", ink, { state.openItemActions(entry) }, iconSize = 16.dp)
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
private fun Banner(message: String, danger: Boolean, modifier: Modifier = Modifier) {
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
                style = if (t.studio) ui(16f, 650) else ui(rem(0.9f), 650),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = t.css.spaceXs).semantics { heading() },
            )
        }
        Text(
            message,
            color = t.faint,
            style = if (t.studio) ui(13f, lineHeight = 1.55f) else ui(rem(0.72f), lineHeight = 1.55f),
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
            .background(if (t.studio) t.mineral else t.mineralDeep)
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
                .heightIn(min = if (t.studio) 64.dp else 0.dp)
                .padding(
                    horizontal = if (t.studio) 20.dp else t.css.spaceLg,
                    vertical = if (t.studio) 14.dp else t.css.spaceMd,
                ),
            horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (masterDetail) IconKey(TetherIcons.ArrowLeft, "Back to file list", t.ink, state::clearSelection, iconSize = 18.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    selected.name,
                    color = t.white,
                    style = if (t.studio) ui(14f, 650) else ui(rem(0.84f), 650),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "${FileFormat.size(selected.size)} · Modified ${FileFormat.modified(selected.mtime, env)}",
                    color = t.faint,
                    style = if (t.studio) ui(12f) else ui(rem(0.65f)),
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
        val pad = when {
            t.studio -> if (studioPhone) 16.dp else 24.dp
            masterDetail -> t.css.spaceMd
            else -> t.css.spaceLg
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(pad), contentAlignment = Alignment.Center) {
            PreviewContent(state, selected)
        }
        if (state.previewError.isNotEmpty()) Banner(state.previewError, danger = true, modifier = Modifier.testTag(FileBrowserTags.PreviewError))
    }
}

@Composable
private fun PreviewContent(state: FileBrowserState, entry: WorkspaceFileEntry) {
    val t = LocalTetherTokens.current
    val kind = FileKinds.previewKind(entry)
    val tooLargeText = kind == PreviewKind.Text && entry.size > WorkspaceFiles.MAX_TEXT_PREVIEW_BYTES
    when {
        kind == PreviewKind.Image && FileKinds.nativeImage(entry.name) -> {
            val image = state.image
            when {
                image != null -> Image(
                    image,
                    contentDescription = "Preview of ${entry.name}",
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
        kind == PreviewKind.Text && state.text != null -> TextPreview(state.text!!)
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
 */
@Composable
private fun TextPreview(text: String) {
    val t = LocalTetherTokens.current
    val lines = remember(text) { previewLines(text) }
    val shape = RoundedCornerShape(if (t.studio) 8.dp else t.radiusMd)
    val style = if (t.studio) ui(13f, lineHeight = 1.75f, mono = true) else ui(rem(0.72f), lineHeight = 1.55f, mono = true)
    SelectionContainer {
        LazyColumn(
            Modifier
                .testTag(FileBrowserTags.Text)
                .fillMaxSize()
                .clip(shape)
                .background(t.graphite)
                .then(if (t.studio) Modifier else Modifier.border(1.dp, t.line, shape))
                .padding(if (t.studio) 20.dp else t.css.spaceLg),
        ) {
            itemsIndexed(lines) { _, line -> Text(line, color = t.ink, style = style) }
        }
    }
}

/** Split on newlines (tabs at two columns); a pathological single line is cut into pieces. */
internal fun previewLines(text: String): List<String> {
    val out = ArrayList<String>()
    for (line in text.replace("\t", "  ").split('\n')) {
        if (line.length <= LINE_PIECE) {
            out += line
        } else {
            var start = 0
            while (start < line.length) {
                out += line.substring(start, minOf(line.length, start + LINE_PIECE))
                start += LINE_PIECE
            }
        }
    }
    return out
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
        title = entry.name,
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
    val title = when (prompt.mode) {
        NamePromptMode.NewFolder -> "New folder"
        NamePromptMode.NewFile -> "New file"
        NamePromptMode.Rename -> "Rename ${prompt.entry?.name ?: ""}"
    }
    TetherDialogSurface(
        modifier = modifier,
        title = title,
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
    TetherDialogSurface(
        modifier = modifier,
        title = "Delete ${entry.name}?",
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
        TetherDialogText(message)
    }
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
        val studioPhone = t.studio && maxWidth <= FileBrowserBreakpoints.StudioPhone
        val width = if (studioPhone) maxWidth - 24.dp else minOf(608.dp, maxWidth - 24.dp)
        val maxH = maxHeight - when {
            studioPhone -> 32.dp
            t.studio -> 48.dp
            else -> 24.dp
        }
        val shape = RoundedCornerShape(if (t.studio) (if (studioPhone) 14.dp else StudioDialog.radius) else t.radiusLg)
        Column(
            Modifier
                .width(width)
                .heightIn(max = maxH)
                .cssSurface(
                    shape, t.graphite,
                    if (t.studio) null else CssBorder(1.dp, t.keySide),
                    if (t.studio) StudioDialog.shadows else listOf(hardShadow(1.dp, t.litStrong, inset = true)) + t.css.shadowModal,
                )
                .clip(shape)
                .swallowTaps(),
        ) {
            val inlinePad = if (t.studio) (if (studioPhone) 20.dp else 28.dp) else t.css.spaceXl
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (t.studio) (if (studioPhone) 76.dp else 88.dp) else 0.dp)
                    .padding(horizontal = inlinePad, vertical = if (t.studio) (if (studioPhone) 18.dp else 22.dp) else t.css.spaceLg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceLg),
            ) {
                Column(Modifier.weight(1f)) {
                    val mode = if (picker.mode == DestinationMode.Move) "Move" else "Copy"
                    if (!t.studio) Text(type.sectionLabel.format(mode), color = t.faint, style = type.sectionLabel.style)
                    Text(
                        picker.entry.name,
                        color = t.white,
                        style = if (t.studio) ui(if (studioPhone) 20f else 22f, 700, -0.025f, 1.3f) else ui(rem(1.3f), 700, -0.025f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading(); contentDescription = "$mode ${picker.entry.name}" },
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
                    .semantics(mergeDescendants = true) { contentDescription = "Destination: ${picker.path}" },
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TetherIcons.FolderOpen, contentDescription = null, tint = t.muted, modifier = Modifier.size(17.dp))
                Text(picker.path, color = t.muted, style = ui(rem(0.7f), mono = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                            FolderButton(TetherIcons.Folder, dir.name, dir.path) { onBrowse(dir.path) }
                        }
                    }
                }
            }
            Seam(t)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = inlinePad, vertical = if (t.studio) (if (studioPhone) 16.dp else 18.dp) else t.css.spaceMd),
                horizontalArrangement = Arrangement.spacedBy(if (t.studio) 10.dp else t.css.spaceSm, Alignment.End),
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
    Box(Modifier.fillMaxWidth().height(1.dp).background(if (t.studio) t.line else t.lineStrong))
    if (!t.studio) Box(Modifier.fillMaxWidth().height(1.dp).background(t.seamLip))
}

@Composable
private fun FolderButton(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
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
                Text(title, color = t.muted, style = ui(rem(0.8f), 700), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, color = t.faint, style = ui(rem(0.64f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    }
}
