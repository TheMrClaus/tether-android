package com.tether.app.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.launch
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.tether.app.client.Handover
import com.tether.app.client.LabelText
import com.tether.app.protocol.helpers.SidebarRowModel
import com.tether.app.protocol.helpers.SidebarRowModel.RowMenuItemId
import com.tether.app.protocol.model.HistoryDigest
import com.tether.app.ui.components.SpinnerRing
import com.tether.app.ui.components.focusRing
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.LocalCopyNotices
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.copyExact
import com.tether.app.ui.text.proseDirection
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import kotlin.math.max
import kotlin.math.min

/** Tether `components/session-row-menu.tsx` RESUME_WARNING, verbatim. */
internal const val RESUME_WARNING = "End this session first — two live turns would share one transcript and working directory."

/** The copy flips for this long (RM COPIED_MS). */
internal const val COPIED_MS = 1500L

/** The phone menu's geometry (SL-spec-3 §a.2): width `min(288, window - 32)`, 4 gap below the kebab, 8 clamp inside the window. */
internal object RowMenuLook {
    val MaxWidth = 288.dp
    val WindowMargin = 8.dp
    val AnchorGap = 4.dp
    val ItemMinHeight = 48.dp
}

/**
 * RM:68-84 placement: under the trigger with the end edges aligned, [gap] below; above it when that would cross the bottom edge
 * minus [margin]; clamped [margin] inside the window horizontally. RTL aligns the start edges instead.
 */
internal class RowMenuPosition(private val gapPx: Int, private val marginPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        var top = anchorBounds.bottom + gapPx
        if (top + popupContentSize.height > windowSize.height - marginPx) top = max(marginPx, anchorBounds.top - gapPx - popupContentSize.height)
        val aligned = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val left = aligned.coerceIn(marginPx, max(marginPx, windowSize.width - marginPx - popupContentSize.width))
        return IntOffset(left, top)
    }
}

/**
 * ta-7ox8 B4 (session-row-menu.tsx:68-152): the kebab's half of the keyboard focus contract, provided by RowKebab around its menu.
 * KEYBOARD MODE ONLY ([keyboard], read where the kebab is drawn): a touch open focuses nothing. [returnFocus] puts focus back on
 * the kebab (the web's `onClose(true)`: Pin, armed End, Escape); with [then] it continues to the next/previous tabbable (Tab).
 */
internal class KebabFocus(
    val keyboard: Boolean,
    val requester: FocusRequester,
    private val focus: FocusManager,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    fun returnFocus(then: FocusDirection? = null) {
        if (!keyboard) return
        scope.launch {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { requester.requestFocus() }
            if (then != null) focus.moveFocus(then)
        }
    }
}

internal val LocalKebabFocus = staticCompositionLocalOf<KebabFocus?> { null }

/**
 * S3a (SL-spec-3 §a.2, §b.2): the web's row menu (`SessionRowMenu`) as an anchored popup under the kebab. Items, order, copy and
 * gates are the web's: [groups] comes from [SidebarRowModel.rowMenuGroups]. Copy stays open and flips its label only when the copy
 * landed; End is the row's two-step ([endArmed] drawn here, the arm itself owned by the sidebar).
 */
@Composable
internal fun RowMenu(
    name: String,
    digest: HistoryDigest?,
    groups: List<List<RowMenuItemId>>,
    pinned: Boolean,
    handover: Handover.MenuState?,
    endArmed: Boolean,
    liveId: String?,
    cwd: String,
    resumeCommand: String?,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onHandOver: () -> Unit,
    onStopHandover: () -> Unit,
    onEnd: () -> Unit,
    onDisarm: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val notices = LocalCopyNotices.current
    val windowDp = with(density) { LocalWindowInfo.current.containerSize.let { it.width.toDp() to it.height.toDp() } }
    val width = min(RowMenuLook.MaxWidth.value, windowDp.first.value - 32f).dp
    val maxHeight = (windowDp.second - RowMenuLook.WindowMargin * 2)
    var copied by remember { mutableStateOf<RowMenuItemId?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            kotlinx.coroutines.delay(COPIED_MS)
            copied = null
        }
    }
    val provider = remember(density) { RowMenuPosition(with(density) { RowMenuLook.AnchorGap.roundToPx() }, with(density) { RowMenuLook.WindowMargin.roundToPx() }) }
    val turns = digest?.newTurns ?: 0
    val snippet = digest?.let { LabelText.hint(it.snippet) }.orEmpty()
    val live = when {
        endArmed -> "Tap again to end"
        copied != null -> copyCopy(copied!!).copied
        else -> ""
    }

    // S3f (B2): a Popup is its own window and does not inherit the composition's layout direction: carry it in.
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    // B4: the kebab's contract (null = none: a menu not drawn from a kebab) and the first/last items' requesters.
    val kebab = LocalKebabFocus.current
    val keyboard = kebab?.keyboard == true
    val firstReq = remember { FocusRequester() }
    val lastReq = remember { FocusRequester() }
    val shown = groups.flatten().filter { it != RowMenuItemId.Handover || (handover != null && handover != Handover.MenuState.Hidden) }
    fun reqFor(id: RowMenuItemId): FocusRequester? = when {
        id == shown.firstOrNull() -> firstReq
        id == shown.lastOrNull() -> lastReq
        else -> null
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
      androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides direction) {
        val shape = RoundedCornerShape(t.radiusMd)
        val focus = LocalFocusManager.current
        // RM:68-88: keyboard open focuses the first item (touch: nothing; the web leaves rings to :focus-visible).
        // The popup is its own window with its own input mode: carry the kebab's keyboard mode in, or its items cannot take focus.
        val popupInput = androidx.compose.ui.platform.LocalInputModeManager.current
        LaunchedEffect(Unit) {
            if (keyboard) {
                popupInput.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                androidx.compose.runtime.withFrameNanos { }
                runCatching { firstReq.requestFocus() }
            }
        }
        Column(
            Modifier
                // RM:105-125: Tab closes, returns to the kebab and continues from it; Escape closes and returns; arrows wrap, Home/End.
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Escape -> { onDismiss(); kebab?.returnFocus(); true }
                        Key.Tab -> { onDismiss(); kebab?.returnFocus(if (e.isShiftPressed) FocusDirection.Previous else FocusDirection.Next); true }
                        Key.DirectionDown -> { if (!focus.moveFocus(FocusDirection.Down)) runCatching { firstReq.requestFocus() }; true }
                        Key.DirectionUp -> { if (!focus.moveFocus(FocusDirection.Up)) runCatching { (if (shown.size > 1) lastReq else firstReq).requestFocus() }; true }
                        Key.MoveHome -> { runCatching { firstReq.requestFocus() }; true }
                        Key.MoveEnd -> { runCatching { (if (shown.size > 1) lastReq else firstReq).requestFocus() }; true }
                        else -> false
                    }
                }
                .width(width)
                .heightIn(max = maxHeight)
                .shadow(8.dp, shape)
                .background(t.graphiteRaised, shape)
                .border(1.dp, t.lineStrong, shape)
                .verticalScroll(rememberScrollState())
                .padding(4.dp)
                .semantics { paneTitle = "Actions for $name" }
                .testTag(SidebarTags.RowMenu),
        ) {
            if (digest != null && (turns > 0 || snippet.isNotEmpty())) {
                DigestBlockInMenu(turns, snippet)
                MenuSeparator()
            }
            groups.forEachIndexed { index, group ->
                if (index > 0) MenuSeparator()
                group.forEach { id ->
                    when (id) {
                        RowMenuItemId.Rename -> MenuItem(id, TetherIcons.Pencil, "Rename…", focusRequester = reqFor(id)) { onDisarm(); onDismiss(); onRename() }
                        RowMenuItemId.Pin -> MenuItem(id, TetherIcons.Pin, if (pinned) "Unpin session" else "Pin session", focusRequester = reqFor(id)) { onDisarm(); onDismiss(); onTogglePin(); kebab?.returnFocus() }
                        RowMenuItemId.Handover -> HandoverItem(handover, reqFor(id), onDisarm, onDismiss, onHandOver, onStopHandover)
                        RowMenuItemId.CopyId, RowMenuItemId.CopyPath, RowMenuItemId.CopyResume -> {
                            val copy = copyCopy(id)
                            val done = copied == id
                            MenuItem(
                                id, if (done) TetherIcons.Check else copy.icon, if (done) copy.copied else copy.label,
                                desc = if (id == RowMenuItemId.CopyResume) RESUME_WARNING else null,
                                focusRequester = reqFor(id),
                            ) {
                                onDisarm()
                                val text = when (id) { RowMenuItemId.CopyId -> liveId; RowMenuItemId.CopyPath -> cwd; else -> resumeCommand }.orEmpty()
                                if (text.isNotEmpty()) {
                                    val display = if (id == RowMenuItemId.CopyResume) SafeText.exact(text) else SafeText.line(text)
                                    // The label flips only when the copy landed; on a refusal nothing claims one.
                                    if (copyExact(context, display, notices, raw = text, label = copy.noticeLabel, strict = true)) copied = id
                                }
                            }
                        }
                        RowMenuItemId.End -> MenuItem(
                            id, TetherIcons.CircleStop,
                            if (endArmed) "Tap again to end" else "End session",
                            danger = true, armed = endArmed,
                            onBlurDisarm = onDisarm,
                            focusRequester = reqFor(id),
                        ) { if (endArmed) kebab?.returnFocus(); onEnd() }
                    }
                }
            }
            // RM `aria-live="polite"`: the armed End and a copy are announced; sighted users read the label itself.
            Box(Modifier.size(1.dp).clearAndSetSemantics { liveRegion = LiveRegionMode.Polite; contentDescription = live })
        }
      }
    }
}

private class CopyCopy(val label: String, val copied: String, val icon: ImageVector, val noticeLabel: String)

/** RM COPY_LABELS + the header's notice labels. */
private fun copyCopy(id: RowMenuItemId): CopyCopy = when (id) {
    RowMenuItemId.CopyId -> CopyCopy("Copy Tether id", "Copied Tether id", TetherIcons.Hash, "Tether session id")
    RowMenuItemId.CopyPath -> CopyCopy("Copy path", "Copied path", TetherIcons.Folder, "Working directory")
    else -> CopyCopy("Copy resume command", "Copied resume command", TetherIcons.Terminal, "Resume command")
}

@Composable
private fun MenuSeparator() {
    val t = LocalTetherTokens.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(1.dp).background(t.line))
}

/** RM:204-212: "N new turns since you left" (13/650 white) over the snippet (13/400 muted, 3 lines). One merged, non-actionable node. */
@Composable
private fun DigestBlockInMenu(turns: Int, snippet: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val head = if (turns > 0) SidebarRowModel.newTurnsText(turns) + " since you left" else null
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(head, snippet.takeIf { it.isNotEmpty() }).joinToString(". ") }
            .testTag(SidebarTags.RowMenuDigest),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (head != null) Text(head, style = css(type.ui, 0.8125f, 650, lineHeight = 1.3846f), color = t.white, modifier = Modifier.clearAndSetSemantics { })
        if (snippet.isNotEmpty()) {
            Text(
                snippet,
                style = css(type.ui, 0.8125f, 400, lineHeight = 1.3846f).copy(textDirection = proseDirection),
                color = t.muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

@Composable
private fun HandoverItem(
    state: Handover.MenuState?,
    req: FocusRequester?,
    onDisarm: () -> Unit,
    onDismiss: () -> Unit,
    onHandOver: () -> Unit,
    onStopHandover: () -> Unit,
) {
    when (state) {
        null, Handover.MenuState.Hidden -> Unit
        Handover.MenuState.Stopping ->
            MenuItem(RowMenuItemId.Handover, null, Handover.STOPPING_LABEL, spinner = true, disabled = true, focusRequester = req) { }
        is Handover.MenuState.Stop ->
            MenuItem(RowMenuItemId.Handover, TetherIcons.CircleX, Handover.STOP_LABEL, desc = listOf(state.stageLine, Handover.STOP_EFFECT).filter { it.isNotEmpty() }.joinToString(" "), focusRequester = req) {
                onDisarm(); onDismiss(); onStopHandover()
            }
        is Handover.MenuState.Blocked ->
            MenuItem(RowMenuItemId.Handover, TetherIcons.ArrowRightLeft, "Hand over to a new lead", desc = state.reason, disabled = true, focusRequester = req) { }
        Handover.MenuState.Enabled ->
            MenuItem(RowMenuItemId.Handover, TetherIcons.ArrowRightLeft, "Hand over to a new lead", focusRequester = req) { onDisarm(); onDismiss(); onHandOver() }
    }
}

/**
 * RM:155-200 `renderItem`: glyph 18 `muted` in a 20 slot, 12 gap, label 14/550 `ink`, the description under it 12/500 `faint`
 * (wraps, never clamped). Pressed / focused: `keyFaceHover` and a `white` label; disabled stays focusable and does nothing and reads
 * `faint`; danger = `danger`; armed = `dangerWash` fill with a 700 label.
 */
@Composable
private fun MenuItem(
    id: RowMenuItemId,
    icon: ImageVector?,
    label: String,
    desc: String? = null,
    disabled: Boolean = false,
    danger: Boolean = false,
    armed: Boolean = false,
    spinner: Boolean = false,
    onBlurDisarm: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    var hadFocus by remember { mutableStateOf(false) }
    val face = when {
        armed -> t.dangerWash
        (pressed || focused) && !disabled -> t.keyFaceHover
        else -> Color.Transparent
    }
    val ink = when {
        disabled -> t.faint
        danger -> t.danger
        pressed || focused -> t.white
        else -> t.ink
    }
    val glyph = when {
        disabled -> t.faint
        danger -> t.danger
        else -> t.muted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowMenuLook.ItemMinHeight)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusRing(focused, shape, t.violet)
            .background(face, shape)
            .onFocusChanged { state ->
                // RM `onEndBlur`: focus leaving the End item disarms it.
                if (onBlurDisarm != null) {
                    if (state.isFocused) hadFocus = true else if (hadFocus) { hadFocus = false; onBlurDisarm() }
                }
            }
            .clickable(interaction, indication = null, role = Role.Button) { if (!disabled) onClick() } // aria-disabled: focusable, does nothing
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (disabled) disabled()
            }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(SidebarTags.menuItem(id)),
        // ta-7ox8 B2 (studio.css:1440-1444): with a description the glyph sits in row 1 of the web's grid, centred on the LABEL line
        // (14sp x 1.2857 = 18 dp), not on the whole block; a one-line item stays centred in the item's minimum height.
        verticalAlignment = if (desc != null) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(if (desc != null) Modifier.size(20.dp, 18.dp) else Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (spinner) SpinnerRing(t.muted, 14.dp) else if (icon != null) SmallIcon(icon, glyph, 18.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = css(type.ui, 0.875f, if (armed) 700 else 550, lineHeight = 1.2857f), color = ink)
            if (desc != null) Text(desc, style = css(type.ui, 0.75f, 500, lineHeight = 1.3333f), color = t.faint)
        }
    }
}
