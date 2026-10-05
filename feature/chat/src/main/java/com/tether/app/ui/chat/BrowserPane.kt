package com.tether.app.ui.chat

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.client.BrowserChannel
import com.tether.app.client.BrowserInput
import com.tether.app.client.BrowserSocketOpener
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherWeights
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

object BrowserPaneTags {
    const val Pane = "browser-pane"
    const val Toggle = "browser-toggle"
    const val Url = "browser-url"
    const val Go = "browser-go"
    const val Reload = "browser-reload"
    const val Close = "browser-close"
    const val Stage = "browser-stage"
    const val CustomWidth = "browser-custom-width"
    const val CustomHeight = "browser-custom-height"
    const val SetCustom = "browser-set-custom"
    const val Fit = "browser-fit"
    const val Readout = "browser-readout"
    const val Status = "browser-status"
    fun preset(name: String) = "browser-preset-$name"
}

/** browser-pane.tsx (90fbb9f) :16-20: the viewport presets, in the web's order. */
internal data class BrowserPreset(val name: String, val width: Int, val height: Int, val mobile: Boolean)

internal val BROWSER_PRESETS = listOf(
    BrowserPreset("Mobile", 390, 844, mobile = true),
    BrowserPreset("Tablet", 768, 1024, mobile = true),
    BrowserPreset("Desktop", 1440, 900, mobile = false),
)

/** browser-pane.tsx :153: the URL field's placeholder. */
internal const val BROWSER_URL_PLACEHOLDER = "https://localhost:3000  —  the page to show"

/** browser-pane.module.css :143-147: below 33rem the panel is the whole viewport. */
internal val BrowserPhoneMax: Dp = 528.dp

/** browser-pane.module.css :11: `width: min(520px, 100vw)` from 33rem. */
internal val BrowserPanelWidth: Dp = 520.dp

/**
 * browser-pane.tsx :140-144 `submitUrl`: trimmed; an address without `http(s)://` is opened as
 * `https://`; an empty one sends nothing.
 */
internal fun browserTarget(draft: String): String? {
    val url = draft.trim()
    if (url.isEmpty()) return null
    return if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) url else "https://$url"
}

/** JS `parseInt(s, 10)`: leading space, a sign, then digits; null where JS gives NaN. */
internal fun jsParseInt(s: String): Int? =
    Regex("^\\s*([+-]?\\d+)").find(s)?.groupValues?.get(1)?.toBigInteger()?.let { n ->
        n.coerceIn(Int.MIN_VALUE.toBigInteger(), Int.MAX_VALUE.toBigInteger()).toInt()
    }

/**
 * browser-pane.tsx :70-79 `toPage`: a point on the drawn stage → the emulated page's CSS pixel,
 * clamped to the viewport and rounded.
 */
internal fun toPage(position: Offset, stage: IntSize, viewportWidth: Int, viewportHeight: Int): Pair<Int, Int> {
    if (stage.width == 0 || stage.height == 0) return 0 to 0
    val x = (position.x / stage.width * viewportWidth).coerceIn(0f, viewportWidth.toFloat().coerceAtLeast(0f))
    val y = (position.y / stage.height * viewportHeight).coerceIn(0f, viewportHeight.toFloat().coerceAtLeast(0f))
    return x.roundToInt() to y.roundToInt()
}

/** browser-pane.tsx :103 `onPointerMove`: ~30fps input throttle (phone-friendly). */
internal const val BROWSER_MOVE_THROTTLE_MS = 33L

/** A DOM `WheelEvent.deltaY` notch (Chrome, pixel mode) for one Android wheel detent. */
internal const val BROWSER_WHEEL_NOTCH_PX = 100.0

/**
 * The pane where the web places it: dashboard.tsx (90fbb9f) :1762-1772 mounts it over the
 * dashboard, keyed by session (switching sessions tears the old channel down). On a phone (below
 * 33rem) it is a full-screen sheet; wider, the 520dp right-hand overlay panel. The back gesture
 * closes the pane as the composer toggle does (no `close` frame: the page stays open on the server).
 */
@Composable
fun BrowserPaneHost(
    opener: BrowserSocketOpener,
    sessionId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    key(sessionId) {
        val channel = remember(opener) { BrowserChannel(opener, sessionId) }
        DisposableEffect(channel) {
            channel.connect()
            onDispose { channel.dispose() }
        }
        val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
        if (windowWidth < BrowserPhoneMax) {
            Dialog(
                onDismissRequest = onClose,
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                BrowserPane(channel, onClose, Modifier.fillMaxSize())
            }
        } else {
            BrowserSidePanel(modifier) { BrowserPane(channel, onClose, Modifier.fillMaxSize()) }
        }
    }
}

/** `.panel` from 33rem (module.css :6-18): 520dp wide on the right, full height, a left rule, the floating shadow. */
@Composable
internal fun BrowserSidePanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    Row(modifier.fillMaxHeight().width(BrowserPanelWidth).cssSurface(RectangleShape, t.graphite, shadows = t.css.shadowFloating)) {
        Box(Modifier.fillMaxHeight().width(1.dp).background(t.line))
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}

/**
 * The in-console browser pane (browser-pane.tsx 90fbb9f :26-296): the URL row (Go, Reload, Close),
 * the viewport row (Mobile / Tablet / Desktop presets, Fit, a custom W × H and the true emulated
 * size), the live page (the server's JPEG screencast frames, never a web view of the page) taking
 * taps, drags, wheel and hardware keys as CDP-style input, and the status line.
 *
 * T8.6 part 2's seam: "Select elements" / "Screenshot on pick" (browser-pane.tsx :231-248) and the
 * hover box go between the viewport row and the stage, on [BrowserChannel.setPickMode] / `hover` /
 * `pick` and [BrowserChannel.pickEvents]; in pick mode the stage already forwards no input.
 */
@Composable
fun BrowserPane(
    channel: BrowserChannel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    defaultUrl: String? = null,
) {
    val ui by channel.ui.collectAsStateWithLifecycle()
    val frame by produceState<ImageBitmap?>(null, channel) {
        channel.frames.filterNotNull().collectLatest { f ->
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size)?.asImageBitmap() }?.let { value = it }
        }
    }
    BrowserPaneContent(
        ui = ui,
        frame = frame,
        defaultUrl = defaultUrl,
        actions = remember(channel, onClose) {
            BrowserPaneActions(
                navigate = { channel.navigate(it) },
                setViewport = { w, h, mobile -> channel.setViewport(w, h, mobile) },
                input = { channel.sendInput(it) },
                setPickMode = { channel.setPickMode(it) },
                close = {
                    channel.close()
                    onClose()
                },
            )
        },
        modifier = modifier,
    )
}

internal class BrowserPaneActions(
    val navigate: (String) -> Unit,
    val setViewport: (width: Int, height: Int, mobile: Boolean) -> Unit,
    val input: (BrowserInput) -> Unit,
    val setPickMode: (Boolean) -> Unit,
    val close: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BrowserPaneContent(
    ui: BrowserChannel.Ui,
    frame: ImageBitmap?,
    actions: BrowserPaneActions,
    modifier: Modifier = Modifier,
    defaultUrl: String? = null,
) {
    val t = LocalTetherTokens.current
    val state = ui.state
    val vw = state.viewport.width
    val vh = state.viewport.height
    var urlDraft by remember { mutableStateOf(defaultUrl ?: "") }
    var customW by remember { mutableStateOf("") }
    var customH by remember { mutableStateOf("") }
    val sm = RoundedCornerShape(t.radiusSm)
    // The stage's width (`clientWidth - 16`, at least 240), measured by the stage wrap; Fit reads it.
    var stageWidthDp by remember { mutableStateOf(480) }
    val stageHeightDp = if (vw > 0) (stageWidthDp * (vh.toFloat() / vw)).roundToInt() else 0

    Column(
        modifier
            .background(t.graphite)
            .testTag(BrowserPaneTags.Pane)
            .semantics { paneTitle = "In-console browser" },
    ) {
        // .header (module.css :20-27)
        Column(
            Modifier.fillMaxWidth().background(t.graphiteRaised).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(44.dp).border(1.dp, t.line, sm).background(t.graphite, sm), contentAlignment = Alignment.Center) {
                    Icon(TetherIcons.AppWindow, contentDescription = null, tint = t.ink, modifier = Modifier.size(16.dp))
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val submit = { browserTarget(urlDraft)?.let(actions.navigate) }
                    BrowserField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        placeholder = BROWSER_URL_PLACEHOLDER,
                        description = "URL to open",
                        fontSize = 13.sp,
                        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        onGo = { submit() },
                        modifier = Modifier.weight(1f).testTag(BrowserPaneTags.Url),
                    )
                    SmallButton("Go", onClick = { submit() }, modifier = Modifier.testTag(BrowserPaneTags.Go))
                }
                IconButton(TetherIcons.RotateCw, "Reload", { actions.navigate(state.url) }, Modifier.testTag(BrowserPaneTags.Reload))
                IconButton(TetherIcons.X, "Close browser", actions.close, Modifier.testTag(BrowserPaneTags.Close))
            }

            // .viewportRow (module.css :104-109): wraps.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                BROWSER_PRESETS.forEach { p ->
                    SmallButton(
                        p.name,
                        onClick = { actions.setViewport(p.width, p.height, p.mobile) },
                        active = vw == p.width && vh == p.height,
                        modifier = Modifier.testTag(BrowserPaneTags.preset(p.name)),
                    )
                }
                SmallButton("Fit", onClick = { actions.setViewport(stageWidthDp, stageHeightDp, false) }, modifier = Modifier.testTag(BrowserPaneTags.Fit))
                BrowserField(customW, { customW = it }, "W", "Custom width", 12.sp, numeric, {}, Modifier.width(68.dp).testTag(BrowserPaneTags.CustomWidth))
                Text("×", color = t.muted, fontFamily = Manrope, fontSize = 12.sp, modifier = Modifier.clearAndSetSemantics {})
                BrowserField(customH, { customH = it }, "H", "Custom height", 12.sp, numeric, {}, Modifier.width(68.dp).testTag(BrowserPaneTags.CustomHeight))
                SmallButton(
                    "Set",
                    onClick = {
                        val w = jsParseInt(customW)
                        val h = jsParseInt(customH)
                        if (w != null && h != null) actions.setViewport(w, h, false)
                    },
                    modifier = Modifier.testTag(BrowserPaneTags.SetCustom),
                )
                Text(
                    "${vw}×${vh}" + if (state.viewport.mobile) " · mobile" else "",
                    color = t.muted,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.body,
                    fontSize = 11.sp,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp).testTag(BrowserPaneTags.Readout),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))

        // .stageWrap (module.css :124-132)
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().background(StageWrapColor).verticalScroll(rememberScrollState()).padding(8.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val measured = maxOf(240, maxWidth.value.roundToInt())
            SideEffect { stageWidthDp = measured }
            val stageW = measured.dp
            val stageH = (if (vw > 0) (measured * (vh.toFloat() / vw)).roundToInt() else 0).coerceAtLeast(0).dp
            val error = ui.error
            if (error != null && !ui.connected) {
                Column(Modifier.widthIn(max = 384.dp).padding(horizontal = 16.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("The browser is not available.", color = t.muted, fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = 13.sp)
                    Spacer(Modifier.size(12.dp))
                    Text(error, color = t.muted, fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = 12.sp)
                }
            } else {
                BrowserStage(
                    frame = frame,
                    pickMode = state.pickMode,
                    viewportWidth = vw,
                    viewportHeight = vh,
                    actions = actions,
                    modifier = Modifier.size(stageW, stageH),
                )
            }
        }

        // .status (module.css :160-170)
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 10.dp, vertical = 6.dp).testTag(BrowserPaneTags.Status),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val dot = when {
                state.loading -> t.violet
                ui.connected -> t.accent
                else -> t.muted
            }
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            val small = TextStyle(fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = 11.sp)
            Text(if (state.loading) "Loading…" else if (ui.connected) "Live" else "Connecting…", color = t.muted, style = small, maxLines = 1)
            Spacer(Modifier.weight(1f))
            val error = ui.error
            if (error != null && ui.connected) {
                Text(error, color = t.danger, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Text(state.url, color = t.muted, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 256.dp))
        }
    }
}

/** `var(--bg, #0b0e0f)`: no skin defines `--bg`, so every skin draws the fallback. */
private val StageWrapColor = Color(0xFF0B0E0F)

private val numeric = KeyboardOptions(keyboardType = KeyboardType.Number)

/**
 * The live page (browser-pane.tsx :204-224): the frame drawn to the stage's size; a press, a drag
 * (throttled ~30fps) and a release go as `mousePressed` / `mouseMoved` / `mouseReleased` at the
 * mapped page pixel, a wheel as `mouseWheel`, and a hardware key as `keyDown` / `keyUp` (:111-118).
 * In pick mode none of them is forwarded (part 2 handles hover / pick there).
 */
@Composable
private fun BrowserStage(
    frame: ImageBitmap?,
    pickMode: Boolean,
    viewportWidth: Int,
    viewportHeight: Int,
    actions: BrowserPaneActions,
    modifier: Modifier,
) {
    val focus = remember { FocusRequester() }
    val pick by rememberUpdatedState(pickMode)
    Box(
        modifier
            .background(Color.White)
            .testTag(BrowserPaneTags.Stage)
            .semantics {
                contentDescription = "Live page — click to interact, or use Select elements"
                role = Role.Image
            }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                val type = when (event.type) {
                    KeyEventType.KeyDown -> "keyDown"
                    KeyEventType.KeyUp -> "keyUp"
                    else -> return@onKeyEvent false
                }
                if (pick) {
                    // browser-pane.tsx :213-216: Escape stops picking; nothing reaches the page.
                    if (type == "keyDown" && event.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_ESCAPE) actions.setPickMode(false)
                    return@onKeyEvent pick
                }
                actions.input(keyInput(event, type))
                true
            }
            .pointerInput(viewportWidth, viewportHeight) {
                var lastMove = 0L
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val km = event.keyboardModifiers
                        val modifiers = BrowserInput.modifiers(km.isAltPressed, km.isCtrlPressed, km.isMetaPressed, km.isShiftPressed)
                        val (x, y) = toPage(change.position, size, viewportWidth, viewportHeight)
                        when (event.type) {
                            PointerEventType.Press -> {
                                runCatching { focus.requestFocus() }
                                if (!pick) {
                                    change.consume()
                                    actions.input(BrowserInput("mousePressed", x, y, button = "left", buttons = 1, clickCount = 1, modifiers = modifiers))
                                }
                            }
                            PointerEventType.Release -> if (!pick) {
                                change.consume()
                                actions.input(BrowserInput("mouseReleased", x, y, button = "left", buttons = 0, clickCount = 1, modifiers = modifiers))
                            }
                            PointerEventType.Move -> {
                                // `touch-action: none`: a drag on the page is the page's, never the wrap's scroll.
                                if (!pick) change.consume()
                                val now = SystemClock.uptimeMillis()
                                if (now - lastMove >= BROWSER_MOVE_THROTTLE_MS) {
                                    lastMove = now
                                    if (!pick) {
                                        actions.input(BrowserInput("mouseMoved", x, y, buttons = if (change.pressed) 1 else 0, modifiers = modifiers))
                                    }
                                }
                            }
                            PointerEventType.Scroll -> if (!pick) {
                                change.consume()
                                val d = change.scrollDelta
                                actions.input(
                                    BrowserInput("mouseWheel", x, y, deltaX = d.x * BROWSER_WHEEL_NOTCH_PX, deltaY = d.y * BROWSER_WHEEL_NOTCH_PX, modifiers = modifiers),
                                )
                            }
                            else -> Unit
                        }
                    }
                }
            },
    ) {
        if (frame != null) {
            Image(frame, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun keyInput(event: KeyEvent, type: String): BrowserInput {
    val modifiers = BrowserInput.modifiers(event.isAltPressed, event.isCtrlPressed, event.isMetaPressed, event.isShiftPressed)
    val key = BrowserKeys.domKey(event.key.nativeKeyCode, event.utf16CodePoint)
    return BrowserInput(type, key = key, code = BrowserKeys.domCode(event.key.nativeKeyCode), text = if (key.length == 1) key else null, modifiers = modifiers)
}

/**
 * A hardware key as the DOM names it (`KeyboardEvent.key` / `.code`), which is what the web pane
 * forwards. A printable key is its character; the rest by name ("Enter", "ArrowLeft", …).
 */
internal object BrowserKeys {
    private val named = mapOf(
        AndroidKeyEvent.KEYCODE_ENTER to "Enter",
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER to "Enter",
        AndroidKeyEvent.KEYCODE_DEL to "Backspace",
        AndroidKeyEvent.KEYCODE_FORWARD_DEL to "Delete",
        AndroidKeyEvent.KEYCODE_TAB to "Tab",
        AndroidKeyEvent.KEYCODE_ESCAPE to "Escape",
        AndroidKeyEvent.KEYCODE_DPAD_LEFT to "ArrowLeft",
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT to "ArrowRight",
        AndroidKeyEvent.KEYCODE_DPAD_UP to "ArrowUp",
        AndroidKeyEvent.KEYCODE_DPAD_DOWN to "ArrowDown",
        AndroidKeyEvent.KEYCODE_MOVE_HOME to "Home",
        AndroidKeyEvent.KEYCODE_MOVE_END to "End",
        AndroidKeyEvent.KEYCODE_PAGE_UP to "PageUp",
        AndroidKeyEvent.KEYCODE_PAGE_DOWN to "PageDown",
        AndroidKeyEvent.KEYCODE_INSERT to "Insert",
        AndroidKeyEvent.KEYCODE_SHIFT_LEFT to "Shift",
        AndroidKeyEvent.KEYCODE_SHIFT_RIGHT to "Shift",
        AndroidKeyEvent.KEYCODE_CTRL_LEFT to "Control",
        AndroidKeyEvent.KEYCODE_CTRL_RIGHT to "Control",
        AndroidKeyEvent.KEYCODE_ALT_LEFT to "Alt",
        AndroidKeyEvent.KEYCODE_ALT_RIGHT to "Alt",
        AndroidKeyEvent.KEYCODE_META_LEFT to "Meta",
        AndroidKeyEvent.KEYCODE_META_RIGHT to "Meta",
        AndroidKeyEvent.KEYCODE_CAPS_LOCK to "CapsLock",
    ) + (1..12).associate { (AndroidKeyEvent.KEYCODE_F1 + it - 1) to "F$it" }

    private val codes = mapOf(
        AndroidKeyEvent.KEYCODE_ENTER to "Enter",
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER to "NumpadEnter",
        AndroidKeyEvent.KEYCODE_DEL to "Backspace",
        AndroidKeyEvent.KEYCODE_FORWARD_DEL to "Delete",
        AndroidKeyEvent.KEYCODE_TAB to "Tab",
        AndroidKeyEvent.KEYCODE_ESCAPE to "Escape",
        AndroidKeyEvent.KEYCODE_SPACE to "Space",
        AndroidKeyEvent.KEYCODE_DPAD_LEFT to "ArrowLeft",
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT to "ArrowRight",
        AndroidKeyEvent.KEYCODE_DPAD_UP to "ArrowUp",
        AndroidKeyEvent.KEYCODE_DPAD_DOWN to "ArrowDown",
        AndroidKeyEvent.KEYCODE_MOVE_HOME to "Home",
        AndroidKeyEvent.KEYCODE_MOVE_END to "End",
        AndroidKeyEvent.KEYCODE_PAGE_UP to "PageUp",
        AndroidKeyEvent.KEYCODE_PAGE_DOWN to "PageDown",
        AndroidKeyEvent.KEYCODE_INSERT to "Insert",
        AndroidKeyEvent.KEYCODE_SHIFT_LEFT to "ShiftLeft",
        AndroidKeyEvent.KEYCODE_SHIFT_RIGHT to "ShiftRight",
        AndroidKeyEvent.KEYCODE_CTRL_LEFT to "ControlLeft",
        AndroidKeyEvent.KEYCODE_CTRL_RIGHT to "ControlRight",
        AndroidKeyEvent.KEYCODE_ALT_LEFT to "AltLeft",
        AndroidKeyEvent.KEYCODE_ALT_RIGHT to "AltRight",
        AndroidKeyEvent.KEYCODE_META_LEFT to "MetaLeft",
        AndroidKeyEvent.KEYCODE_META_RIGHT to "MetaRight",
        AndroidKeyEvent.KEYCODE_CAPS_LOCK to "CapsLock",
        AndroidKeyEvent.KEYCODE_MINUS to "Minus",
        AndroidKeyEvent.KEYCODE_EQUALS to "Equal",
        AndroidKeyEvent.KEYCODE_LEFT_BRACKET to "BracketLeft",
        AndroidKeyEvent.KEYCODE_RIGHT_BRACKET to "BracketRight",
        AndroidKeyEvent.KEYCODE_BACKSLASH to "Backslash",
        AndroidKeyEvent.KEYCODE_SEMICOLON to "Semicolon",
        AndroidKeyEvent.KEYCODE_APOSTROPHE to "Quote",
        AndroidKeyEvent.KEYCODE_GRAVE to "Backquote",
        AndroidKeyEvent.KEYCODE_COMMA to "Comma",
        AndroidKeyEvent.KEYCODE_PERIOD to "Period",
        AndroidKeyEvent.KEYCODE_SLASH to "Slash",
    ) + (1..12).associate { (AndroidKeyEvent.KEYCODE_F1 + it - 1) to "F$it" }

    fun domKey(keyCode: Int, codePoint: Int): String {
        named[keyCode]?.let { return it }
        if (codePoint > 0 && !Character.isISOControl(codePoint)) return String(Character.toChars(codePoint))
        return "Unidentified"
    }

    fun domCode(keyCode: Int): String = when (keyCode) {
        in AndroidKeyEvent.KEYCODE_A..AndroidKeyEvent.KEYCODE_Z -> "Key" + ('A' + (keyCode - AndroidKeyEvent.KEYCODE_A))
        in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> "Digit" + (keyCode - AndroidKeyEvent.KEYCODE_0)
        else -> codes[keyCode] ?: ""
    }
}

/** `.smallButton` / `.smallButtonActive` (module.css :88-102): 36dp, `radius-sm`, 0.75rem. */
@Composable
private fun SmallButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(t.radiusSm)
    Box(
        modifier
            .heightIn(min = 36.dp)
            .border(1.dp, if (active) t.violet else t.line, shape)
            .background(if (active) t.violetWash else t.graphite, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (active) selected = true }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) t.violet else t.ink, fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = 12.sp, maxLines = 1)
    }
}

/** `.iconButton` (module.css :55-80): 44dp, `radius-sm`, a 16dp glyph; the label is its name. */
@Composable
private fun IconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(t.radiusSm)
    Box(
        modifier
            .size(44.dp)
            .border(1.dp, t.line, shape)
            .background(t.graphite, shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = t.ink, modifier = Modifier.size(16.dp))
    }
}

/** `.urlInput` / `.dim` (module.css :35-53, :111-120): 36dp, `radius-sm`, a 1px rule, ink on graphite. */
@Composable
private fun BrowserField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    description: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    keyboard: KeyboardOptions,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(t.radiusSm)
    val style = TextStyle(fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = fontSize, color = t.ink)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(t.violet),
        keyboardOptions = keyboard,
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = modifier.semantics { contentDescription = description },
        decorationBox = { inner ->
            Box(
                Modifier.heightIn(min = 36.dp).border(1.dp, t.line, shape).background(t.graphite, shape).padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = style, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        },
    )
}

/** T8.6: the composer's browser toggle (chat-view.tsx 90fbb9f :4510-4524): [open] latches it. */
class ComposerBrowser(val open: Boolean, val onToggle: () -> Unit)

/**
 * `.chat-attach-btn` with the AppWindow glyph, beside the paperclip; violet while the pane is open
 * (the web's inline `color` / `border-color: var(--violet)`, `aria-pressed`).
 */
@Composable
internal fun ComposerBrowserKey(browser: ComposerBrowser, size: Dp) {
    com.tether.app.ui.components.TetherKey(
        onClick = browser.onToggle,
        classes = com.tether.app.ui.components.KeyClasses.Attach,
        icon = TetherIcons.AppWindow,
        iconSize = 16.dp,
        minHeight = size,
        selected = browser.open,
        modifier = Modifier.size(size).testTag(BrowserPaneTags.Toggle),
        contentDescription = "Open the in-console browser",
    )
}
