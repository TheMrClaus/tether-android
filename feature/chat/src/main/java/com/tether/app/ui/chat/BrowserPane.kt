package com.tether.app.ui.chat

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Image
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.buildAnnotatedString
import com.composables.icons.lucide.Hand
import com.composables.icons.lucide.MousePointer2
import com.tether.app.client.BrowserProfilesReply
import com.tether.app.client.BrowserProfilesRequest
import com.tether.app.protocol.BrowserProfileInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.semantics.testTag as semanticsTestTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.client.AgentCaption
import com.tether.app.client.AgentMarker
import com.tether.app.client.AgentUiState
import com.tether.app.client.BrowserAgentUi
import com.tether.app.client.BrowserChannel
import com.tether.app.client.BrowserInput
import com.tether.app.client.BrowserPick
import com.tether.app.client.HoverBox
import com.tether.app.client.BrowserSocketOpener
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MonitorX
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherWeights
import kotlin.math.roundToInt
import kotlin.math.floor
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
    const val TabIndicator = "browser-tab-indicator"
    const val StatusError = "browser-status-error"
    const val UrlNotice = "browser-url-notice"
    const val SelectElements = "browser-select-elements"
    const val ShotOnPick = "browser-shot-on-pick"
    const val Highlight = "browser-highlight"
    const val Segmented = "browser-viewport-size"
    const val LoadingBar = "browser-loading-bar"
    const val NoticeRow = "browser-notice-row"
    const val ErrorRow = "browser-error-row"
    const val Dead = "browser-dead"
    const val Blank = "browser-blank"
    const val PickHint = "browser-pick-hint"
    const val StatusStart = "browser-status-start"
    const val StatusUrl = "browser-status-url"
    const val Marker = "browser-agent-marker"
    const val AgentPill = "browser-agent-caption"
    const val TabPicker = "browser-tab-select"
    const val LiveAgent = "browser-live-region"
    const val LiveTab = "browser-tab-live-region"
    const val ProfileSelect = "browser-profile-select"
    const val ProfileHint = "browser-profile-hint"
    const val ProfileRefusal = "browser-profile-refusal"
    fun tabOption(id: String) = "browser-tab-option-$id"
    fun profileOption(id: String?) = "browser-profile-option-${id ?: "default"}"
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
    /**
     * browser-pane.tsx :36-48 `onPicked(pick, pageUrl)`: an element the operator picked, and the page
     * it was on (read at the pick, as the web's `urlRef`).
     */
    onPicked: (BrowserPick, String) -> Unit = { _, _ -> },
    /**
     * browser-pane.tsx :36-40 `onBrowserProfiles`: the owner-grade profile list request on the main
     * socket. Null (a server below protocol 171) draws no profile row at all.
     */
    browserProfiles: (suspend (BrowserProfilesRequest) -> BrowserProfilesReply)? = null,
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
                BrowserPane(channel, onClose, Modifier.fillMaxSize(), onPicked = onPicked, browserProfiles = browserProfiles)
            }
        } else {
            BrowserSidePanel(modifier) { BrowserPane(channel, onClose, Modifier.fillMaxSize(), onPicked = onPicked, browserProfiles = browserProfiles) }
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
 * The in-console browser pane (browser-pane.tsx 0bd07fdd :203-463, design/pane-spec.md): one surface
 * with a toolbar (Reload, the address with Go inside, Close; then the tools: the viewport presets
 * and Fit as one segmented control, a custom W × H, Select elements, Screenshot on pick), the live
 * page (the server's JPEG screencast frames, never a web view of the page) framed on the floor
 * colour and taking taps, drags, wheel and hardware keys as CDP-style input, the notice rows, and
 * the status line (the live state, the tab, the true emulated size and the address).
 *
 * T8.6 part 2: "Select elements" and "Screenshot on pick" (browser-pane.tsx :231-248) between the
 * viewport row and the page. In pick mode the page takes no input: a drag sends `hover` (~30fps,
 * :103-107) and the reply's box is drawn over the page (:250-254); a tap sends `pick {x,y,screenshot}`
 * (:108-112); the `picked` reply goes to [onPicked] (the composer's chips). `pick-empty` is, as on the
 * web (use-browser.ts :141-200 has no case for it), no feedback at all.
 */
@Composable
fun BrowserPane(
    channel: BrowserChannel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    defaultUrl: String? = null,
    onPicked: (BrowserPick, String) -> Unit = { _, _ -> },
    browserProfiles: (suspend (BrowserProfilesRequest) -> BrowserProfilesReply)? = null,
) {
    val ui by channel.ui.collectAsStateWithLifecycle()
    val agent by channel.agent.collectAsStateWithLifecycle()
    // lib/browser-agent-ui.ts nextAgentDeadline: the channel runs no timer, the pane lets go of the
    // marker and the caption when their time is up (use-browser.ts runs the same timeout).
    LaunchedEffect(channel, agent) {
        val wait = channel.agentDeadlineMs() ?: return@LaunchedEffect
        delay(wait)
        channel.expireAgent()
    }
    // browser-pane.tsx :58-71: the list is read when the pane opens and whenever the session's profile
    // changes, and again when the select opens. A failed list leaves what was there (an empty list).
    val currentProfiles by rememberUpdatedState(browserProfiles)
    var profileList by remember(channel) { mutableStateOf<List<BrowserProfileInfo>>(emptyList()) }
    val profileId = ui.state.profile?.id
    val profileName = ui.state.profile?.name
    LaunchedEffect(channel, browserProfiles != null, profileId, profileName) {
        currentProfiles?.invoke(BrowserProfilesRequest.List)?.let { if (it.ok) profileList = it.profiles }
    }
    val scope = rememberCoroutineScope()
    val hoverBox by channel.hoverBox.collectAsStateWithLifecycle()
    // A ref, as the web's `urlRef` (:44-47): the handler outlives the URL it was made with.
    val currentOnPicked by rememberUpdatedState(onPicked)
    LaunchedEffect(channel) {
        channel.picks.collect { pick -> currentOnPicked(pick, channel.ui.value.state.url) }
    }
    val frame by produceState<ImageBitmap?>(null, channel) {
        channel.frames.filterNotNull().collectLatest { f ->
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size)?.asImageBitmap() }?.let { value = it }
        }
    }
    BrowserPaneContent(
        ui = ui,
        frame = frame,
        defaultUrl = defaultUrl,
        hoverBox = hoverBox,
        agent = agent,
        profileList = if (browserProfiles != null) profileList else null,
        actions = remember(channel, onClose) {
            BrowserPaneActions(
                navigate = { channel.navigate(it) },
                setViewport = { w, h, mobile -> channel.setViewport(w, h, mobile) },
                input = { channel.sendInput(it) },
                setPickMode = { channel.setPickMode(it) },
                hover = { x, y -> channel.hover(x, y) },
                pick = { x, y, screenshot -> channel.pick(x, y, screenshot) },
                switchTab = { channel.switchTab(it) },
                pickProfile = { channel.pickProfile(it) },
                refreshProfiles = {
                    scope.launch { currentProfiles?.invoke(BrowserProfilesRequest.List)?.let { if (it.ok) profileList = it.profiles } }
                },
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
    val hover: (x: Int, y: Int) -> Unit = { _, _ -> },
    val pick: (x: Int, y: Int, screenshot: Boolean) -> Unit = { _, _, _ -> },
    /** use-browser.ts :314: a `tab` input naming the tab. */
    val switchTab: (id: String) -> Unit = {},
    /** use-browser.ts :315-320: the id, or null for the throwaway default. */
    val pickProfile: (id: String?) -> Unit = {},
    /** browser-pane.tsx :62-66: read the list again (the select was opened). */
    val refreshProfiles: () -> Unit = {},
)

/**
 * The pane's own sizes (browser-pane.module.css 0bd07fdd :14-64 `--pane-*`; design/pane-spec.md §2, §7).
 * Android is a touch surface: the coarse-pointer values, with every target at Android's 48dp
 * (spec §7) instead of the web's 44px.
 */
internal object BrowserPaneMetrics {
    /** `--pane-ctl` (coarse 2.75rem), raised to 48dp: every toolbar target's height. */
    val Ctl: Dp = 48.dp

    /** `--pane-pad` (coarse 0.5rem). */
    val Pad: Dp = 8.dp

    /** `@container pane (max-width: 29.99rem)` (:501, :910): narrower panes use the two-column tools. */
    val WideMin: Dp = 480.dp

    /** `.status` on a coarse pointer (:760-763): a 44dp row below its 1dp top line (the shipped web wins, spec rev 3). */
    val StatusMin: Dp = 44.dp

    /** `.stage` radius (:548-551). */
    val FrameRadius: Dp = 6.dp

    /** `.loadingBar` height (:287-295). */
    val LoadingBar: Dp = 2.dp

    /** browser-pane.tsx :112: the frame fits the area's height from a 48rem window. */
    val FitHeightWindowMin: Dp = 768.dp
}

/** `--pane-chip-text` / `--pane-field-text` on a coarse pointer (:53-63). */
private val ChipText = 13.sp
private val FieldText = 16.sp

/** `.status` / `.profileHint` text (:702-713). */
private val StatusText = 11.sp

private val StatusWeight = FontWeight(560)
private val BlankTitleWeight = FontWeight(650)

/** browser-pane.tsx :450: the status line's hint while Select elements is on. */
internal const val BROWSER_PICK_HINT = "Click an element to send it. Esc stops."

/** browser-pane.tsx :360-366: the quiet invitation on an empty page. */
internal const val BROWSER_BLANK_TITLE = "No page open yet"
internal const val BROWSER_BLANK_BODY = "Enter an address above to load a page."

/** browser-profiles-ui.ts :29-30: the default's name and the note under the select. */
internal const val PROFILE_DEFAULT_LABEL = "Throwaway profile (default)"
internal const val PROFILE_PICK_HINT = "Changing the profile restarts the open browser on it."

/** browser-profiles-ui.ts :108-110 `paneProfileLabel`: the session's profile name; none is the default. */
internal fun profileLabel(profile: BrowserChannel.PaneProfile?): String = profile?.name ?: PROFILE_DEFAULT_LABEL

/** browser-pane.tsx :87: the tab indicator's words ("N tabs" when no tab is marked active). */
internal fun browserTabLabel(tabs: List<BrowserChannel.Tab>): String {
    val active = tabs.firstOrNull { it.active }
    return if (active != null) "Tab ${active.index} of ${tabs.size}" + if (active.title.isNotEmpty()) " · ${active.title}" else ""
    else "${tabs.size} tabs"
}

/**
 * browser-pane.tsx :199-201: no page yet. Connected, no error, not loading and still on about:blank;
 * never while the socket is connecting and never over an error (the error row says why instead).
 */
internal fun browserBlankPage(ui: BrowserChannel.Ui): Boolean =
    ui.connected && ui.error == null && !ui.state.loading && (ui.state.url == "" || ui.state.url == "about:blank")

/** A CSS `box-shadow: 0 0 0 <width> <color>` ring, drawn outside the box. */
private fun ring(width: Dp, color: Color) = CssShadow(inset = false, offsetX = 0.dp, offsetY = 0.dp, blur = 0.dp, spread = width, color = color)

/**
 * `:focus-visible { outline: 2px solid var(--violet); outline-offset: <offset> }` (:178-185, :560-563):
 * a 2dp violet ring [offset] outside the control, drawn only while it holds keyboard focus.
 */
private fun Modifier.focusRing(focused: Boolean, color: Color, radius: Dp, offset: Dp = 2.dp): Modifier =
    if (!focused) this else drawWithContent {
        drawContent()
        val stroke = 2.dp.toPx()
        val o = offset.toPx() + stroke / 2
        drawRoundRect(
            color = color,
            topLeft = Offset(-o, -o),
            size = Size(size.width + 2 * o, size.height + 2 * o),
            cornerRadius = CornerRadius(radius.toPx() + o),
            style = Stroke(stroke),
        )
    }

/** A colour that eases over `--duration-fast` (:103), or snaps under reduced motion (:923-934). */
@Composable
private fun paneColor(target: Color): Color {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val value by animateColorAsState(target, if (reduced) snap() else tween(t.css.durationFast, easing = t.css.easeOut.toEasing()), label = "pane-colour")
    return value
}

/**
 * The address row is widened by the icon buttons' own inset (`.row` :80-88, `margin-inline:
 * calc(-1 * min(--pane-pad, (--pane-ctl - 1rem) / 2))`) so Reload and Close sit on the pane's edge
 * and their glyphs line up with the rows below; the row's box is widened, nothing overflows it.
 */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val wide = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra) else constraints
    val p = measurable.measure(wide)
    val w = (p.width - extra).coerceAtLeast(0)
    layout(w, p.height) { p.place(-by.roundToPx(), 0) }
}

@Composable
internal fun BrowserPaneContent(
    ui: BrowserChannel.Ui,
    frame: ImageBitmap?,
    actions: BrowserPaneActions,
    modifier: Modifier = Modifier,
    defaultUrl: String? = null,
    hoverBox: HoverBox? = null,
    /** What the agent is doing (browser-pane.tsx :83-85): the marker over the page, the pill in the status line. */
    agent: AgentUiState = BrowserAgentUi.INITIAL,
    /**
     * The profiles the server lists, for the tools' profile row (tsx :295-312). Null draws no row (a
     * server below protocol 171); an empty list (also a failed read) keeps the select with the default
     * and the session's current profile.
     */
    profileList: List<BrowserProfileInfo>? = null,
) {
    val t = LocalTetherTokens.current
    val state = ui.state
    val caption = BrowserAgentUi.caption(agent)
    // tsx :86-95: a tab change is said once to a screen reader, never on the first list. A tab with no
    // id (`""` here, `null` on the web) is not a change.
    val activeTab = state.tabs.firstOrNull { it.active }
    var tabAnnouncement by remember { mutableStateOf("") }
    var lastTab by remember { mutableStateOf<String?>(null) }
    val activeId = activeTab?.id?.takeIf { it.isNotEmpty() }
    LaunchedEffect(activeId, activeTab?.index, activeTab?.title, state.tabs.size) {
        val last = lastTab
        if (last != null && activeId != null && activeId != last) {
            tabAnnouncement = "Switched to tab ${activeTab?.index} of ${state.tabs.size}" + if (!activeTab?.title.isNullOrEmpty()) ": ${activeTab?.title}" else ""
        }
        if (activeId != null || state.tabs.isEmpty()) lastTab = activeId
    }
    // browser-pane.tsx :53: "Screenshot on pick" starts on.
    var shotOnPick by remember { mutableStateOf(true) }
    val vw = state.viewport.width
    val vh = state.viewport.height
    var urlDraft by remember { mutableStateOf(defaultUrl ?: "") }
    var customW by remember { mutableStateOf("") }
    var customH by remember { mutableStateOf("") }
    // The stage's width (`clientWidth - 16`, at least 240), measured by the page area; Fit reads it.
    var stageWidthDp by remember { mutableStateOf(480) }
    val stageHeightDp = if (vw > 0) (stageWidthDp * (vh.toFloat() / vw)).roundToInt() else 0
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

    BoxWithConstraints(
        modifier
            .background(t.graphite)
            .testTag(BrowserPaneTags.Pane)
            .semantics { paneTitle = "In-console browser" },
    ) {
        val wide = maxWidth >= BrowserPaneMetrics.WideMin
        Column(Modifier.fillMaxSize()) {
            // .header (module.css :66-78): the panel face, the rows 0.5rem apart.
            Column(
                Modifier.fillMaxWidth().background(t.graphite).padding(BrowserPaneMetrics.Pad),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Navigate: Reload → the address (Go inside) → Close (tsx :206-246).
                Row(
                    Modifier.fillMaxWidth().bleed(BrowserPaneMetrics.Pad),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PaneIconButton(TetherIcons.RotateCw, "Reload", { actions.navigate(state.url) }, Modifier.testTag(BrowserPaneTags.Reload))
                    AddressField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        onSubmit = { browserTarget(urlDraft)?.let(actions.navigate) },
                        modifier = Modifier.weight(1f),
                    )
                    PaneIconButton(TetherIcons.X, "Close browser", actions.close, Modifier.testTag(BrowserPaneTags.Close))
                }

                // .tools (:318-328 wide, :501-533 narrow): viewport, size, Select elements, screenshot, profile.
                val segmented: @Composable (Modifier) -> Unit = { m ->
                    ViewportSegments(
                        activeWidth = vw,
                        activeHeight = vh,
                        equal = !wide,
                        onPreset = { p -> actions.setViewport(p.width, p.height, p.mobile) },
                        onFit = { actions.setViewport(stageWidthDp, stageHeightDp, false) },
                        modifier = m,
                    )
                }
                val size: @Composable (Modifier) -> Unit = { m ->
                    SizeGroup(
                        width = customW,
                        height = customH,
                        onWidth = { customW = it },
                        onHeight = { customH = it },
                        fill = !wide,
                        onSet = {
                            val w = jsParseInt(customW)
                            val h = jsParseInt(customH)
                            if (w != null && h != null) actions.setViewport(w, h, false)
                        },
                        modifier = m,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (wide) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            segmented(Modifier)
                            Spacer(Modifier.weight(1f).widthIn(min = 6.dp))
                            size(Modifier)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SelectElementsChip(active = state.pickMode, onClick = { actions.setPickMode(!state.pickMode) })
                            ShotOnPickChip(checked = shotOnPick, onCheckedChange = { shotOnPick = it })
                            if (profileList != null) ProfileSelect(state.profile, profileList, actions, Modifier.weight(1f))
                        }
                    } else {
                        segmented(Modifier.fillMaxWidth())
                        size(Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SelectElementsChip(active = state.pickMode, onClick = { actions.setPickMode(!state.pickMode) }, modifier = Modifier.weight(1f))
                            ShotOnPickChip(checked = shotOnPick, onCheckedChange = { shotOnPick = it }, modifier = Modifier.weight(1f))
                        }
                        if (profileList != null) ProfileSelect(state.profile, profileList, actions, Modifier.fillMaxWidth())
                    }
                    if (profileList != null) {
                        ProfileHint(state.profile)
                        ui.profileError?.let { ProfileRefusal(it) }
                    }
                }
            }
            // The toolbar's 1dp bottom line, and the 2dp loading bar on it (:287-316).
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.line)) {
                if (state.loading) LoadingBar(Modifier.align(Alignment.Center))
            }

            // .stageWrap (:536-546): the page on the floor colour.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(t.mineral)) {
                val error = ui.error
                if (error != null && !ui.connected) {
                    DeadState(error, Modifier.fillMaxSize())
                } else {
                    val fitWidth = maxOf(240, (maxWidth.value - 16f).roundToInt())
                    SideEffect { stageWidthDp = fitWidth }
                    // tsx :102-112: from a 48rem window a tall frame is scaled to the area's height; a phone scrolls.
                    val room = if (windowWidth >= BrowserPaneMetrics.FitHeightWindowMin && maxHeight != Dp.Infinity) (maxHeight.value - 16f).toInt() else 0
                    val stageW = if (room > 0 && vh > 0) minOf(fitWidth, maxOf(120, floor(room * (vw.toDouble() / vh)).toInt())) else fitWidth
                    val stageH = (if (vw > 0) (stageW * (vh.toFloat() / vw)).roundToInt() else 0).coerceAtLeast(0)
                    Box(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        BrowserStage(
                            frame = frame,
                            pickMode = state.pickMode,
                            blank = browserBlankPage(ui),
                            viewportWidth = vw,
                            viewportHeight = vh,
                            actions = actions,
                            hoverBox = hoverBox,
                            stageWidth = stageW.dp,
                            stageHeight = stageH.dp,
                            marker = agent.marker,
                            onPick = { x, y -> actions.pick(x, y, shotOnPick) },
                            modifier = Modifier.size(stageW.dp, stageH.dp),
                        )
                    }
                }
            }

            // Notice rows above the status (tsx :387-393, module.css :666-700): full width, wrapping.
            state.urlNotice?.let { notice ->
                val blocked = notice.kind == BrowserChannel.UrlNoticeKind.ConsoleBlocked
                PaneNoticeRow(
                    icon = if (blocked) TetherIcons.ShieldOff else TetherIcons.Info,
                    text = urlNoticeCopy(notice),
                    edge = if (blocked) t.attentionBorder else t.line,
                    fill = if (blocked) t.attentionBg else t.graphiteRaised,
                    ink = if (blocked) t.attentionInk else t.muted,
                    iconTint = if (blocked) t.attentionInk else t.muted,
                    textTag = BrowserPaneTags.UrlNotice,
                )
            }
            val error = ui.error
            if (error != null && ui.connected) {
                PaneNoticeRow(
                    icon = TetherIcons.TriangleAlert,
                    text = error,
                    edge = t.dangerEdge,
                    fill = t.dangerWash,
                    ink = t.white,
                    iconTint = t.danger,
                    textTag = BrowserPaneTags.StatusError,
                    rowTag = BrowserPaneTags.ErrorRow,
                )
            }

            StatusLine(ui = ui, wide = wide, caption = caption, onSwitchTab = actions.switchTab)
        }
        // tsx :395-399: the two polite regions, said to a screen reader and never drawn.
        Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = agent.announce }.testTag(BrowserPaneTags.LiveAgent))
        Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = tabAnnouncement }.testTag(BrowserPaneTags.LiveTab))
    }
}

/** `.loadingBar` (:287-316): a 2dp running-green segment sliding along the line; static under reduced motion. */
@Composable
private fun LoadingBar(modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    // `bottom: -1px` on the header: the bar covers the 1dp line and the 1dp above it.
    BoxWithConstraints(
        modifier.fillMaxWidth().requiredHeight(BrowserPaneMetrics.LoadingBar).offset(y = (-0.5).dp).clipToBounds()
            .clearAndSetSemantics { semanticsTestTag = BrowserPaneTags.LoadingBar },
    ) {
        val barWidth = maxWidth * 0.4f
        val shift = if (reduced) {
            0f
        } else {
            val run = rememberInfiniteTransition(label = "pane-loading")
            val f by run.animateFloat(
                initialValue = -1f,
                targetValue = 2.6f,
                animationSpec = infiniteRepeatable(tween(1100, easing = t.css.easeOut.toEasing()), RepeatMode.Restart),
                label = "pane-loading-x",
            )
            f
        }
        Box(Modifier.offset(x = barWidth * shift).width(barWidth).fillMaxHeight().background(t.running))
    }
}

/** `.iconButton` (:90-117, :172-185): transparent, a muted 16dp glyph, a 48dp square; `--line` while pressed. */
@Composable
private fun PaneIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(t.radiusSm)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .size(BrowserPaneMetrics.Ctl)
            .focusRing(focused, t.violet, t.radiusSm)
            .background(paneColor(if (pressed) t.line else Color.Transparent), shape)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = t.muted, modifier = Modifier.size(16.dp))
    }
}

/**
 * `.urlField` / `.urlInput` / `.urlGo` (:195-285): one filled field holding the page icon, the
 * address and Go (a full 48dp target flush with the field's end). Focused, the field takes the
 * console's field focus (panel face, a 1dp violet ring and the 4dp glow) and Go turns cobalt.
 */
@Composable
private fun AddressField(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(t.radiusSm)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = TextStyle(fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = FieldText, color = t.white)
    Row(
        modifier
            .height(BrowserPaneMetrics.Ctl)
            .cssSurface(
                shape,
                paneColor(if (focused) t.graphite else t.graphiteRaised),
                shadows = if (focused) listOf(ring(1.dp, t.violet), ring(4.dp, t.focusGlow)) else emptyList(),
            )
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Lucide.Globe, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(t.violet),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            modifier = Modifier.weight(1f).fillMaxHeight().testTag(BrowserPaneTags.Url).semantics { contentDescription = "URL to open" },
            decorationBox = { inner ->
                Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    // --muted, not --faint (:236-239): 4.9:1 on the field fill in the light skin.
                    if (value.isEmpty()) Text(BROWSER_URL_PLACEHOLDER, style = style, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        val goInteraction = remember { MutableInteractionSource() }
        val goFocused by goInteraction.collectIsFocusedAsState()
        val goShape = RoundedCornerShape(t.radiusSm - 2.dp)
        Box(
            Modifier
                .size(BrowserPaneMetrics.Ctl)
                .focusRing(goFocused, t.violet, t.radiusSm - 2.dp)
                .background(paneColor(if (focused) t.accent else Color.Transparent), goShape)
                .clickable(goInteraction, indication = null, role = Role.Button, onClickLabel = "Go", onClick = onSubmit)
                .semantics { contentDescription = "Go" }
                .testTag(BrowserPaneTags.Go),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TetherIcons.ArrowRight, contentDescription = null, tint = paneColor(if (focused) t.accentInk else t.muted), modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * `.segmented` / `.segment` / `.segmentActive` (:330-365): the viewport presets and Fit as one
 * control. On touch the track has no inset, so every segment is a full target (:351-359). The
 * active preset is raised on the panel face with cobalt text and is `selected`; the others say
 * not selected (`aria-pressed`); Fit is an action and never carries a selection. [equal]: the
 * narrow pane's four equal segments (:512-516).
 */
@Composable
private fun ViewportSegments(
    activeWidth: Int,
    activeHeight: Int,
    equal: Boolean,
    onPreset: (BrowserPreset) -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    Row(
        // tsx :250 `role="group" aria-label="Viewport size"`: the group's name.
        modifier.background(t.graphiteRaised, RoundedCornerShape(t.radiusSm)).semantics { contentDescription = "Viewport size" }.testTag(BrowserPaneTags.Segmented),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val each = if (equal) Modifier.weight(1f) else Modifier
        BROWSER_PRESETS.forEach { p ->
            Segment(p.name, active = activeWidth == p.width && activeHeight == p.height, onClick = { onPreset(p) }, modifier = each.testTag(BrowserPaneTags.preset(p.name)))
        }
        Segment("Fit", active = null, onClick = onFit, modifier = each.testTag(BrowserPaneTags.Fit))
    }
}

/** One segment; [active] null = an action (Fit): no selection state at all. */
@Composable
private fun Segment(label: String, active: Boolean?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val radius = t.radiusSm - 2.dp
    val shape = RoundedCornerShape(radius)
    val on = active == true
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .heightIn(min = BrowserPaneMetrics.Ctl)
            .focusRing(focused, t.violet, radius)
            .cssSurface(
                shape,
                paneColor(if (on) t.graphite else if (pressed) t.line else Color.Transparent),
                shadows = if (on) listOf(ring(1.dp, t.line)) + t.css.shadowRaised else emptyList(),
            )
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { if (active != null) selected = active }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = paneColor(if (on) t.violet else t.muted), fontFamily = Manrope, fontWeight = TetherWeights.label, fontSize = ChipText, maxLines = 1)
    }
}

/**
 * `.sizeGroup` / `.sizeField` / `.times` + the Set chip (:367-410): two ruled mono fields (inputs,
 * not buttons) and Set. [fill]: the narrow pane's full-width row where W and H grow (:518-526).
 */
@Composable
private fun SizeGroup(
    width: String,
    height: String,
    onWidth: (String) -> Unit,
    onHeight: (String) -> Unit,
    fill: Boolean,
    onSet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val field = if (fill) Modifier.weight(1f) else Modifier.width(56.dp)
        SizeField(width, onWidth, "W", "Custom width", field.testTag(BrowserPaneTags.CustomWidth))
        Text("×", color = t.muted, fontFamily = Manrope, fontSize = 12.sp, modifier = Modifier.clearAndSetSemantics {})
        SizeField(height, onHeight, "H", "Custom height", field.testTag(BrowserPaneTags.CustomHeight))
        PaneChip(onClick = onSet, modifier = Modifier.testTag(BrowserPaneTags.SetCustom)) { tone ->
            Text("Set", color = tone, fontFamily = Manrope, fontWeight = TetherWeights.label, fontSize = ChipText, maxLines = 1)
        }
    }
}

@Composable
private fun SizeField(value: String, onValueChange: (String) -> Unit, placeholder: String, description: String, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(t.radiusSm)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = TextStyle(fontFamily = JetBrainsMono, fontWeight = TetherWeights.body, fontSize = FieldText, color = t.white, fontFeatureSettings = "tnum")
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(t.violet),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.height(BrowserPaneMetrics.Ctl).semantics { contentDescription = description },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .cssSurface(
                        shape,
                        t.graphite,
                        border = CssBorder(1.dp, if (focused) t.violet else t.lineStrong),
                        shadows = if (focused) listOf(ring(4.dp, t.focusGlow)) else emptyList(),
                    )
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, color = t.muted, fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = FieldText, maxLines = 1)
                }
                inner()
            }
        },
    )
}

/**
 * `.chip` / `.chipActive` (:90-141): a borderless filled chip on `--graphite-raised`, 48dp tall,
 * `--line` while pressed; cobalt wash and cobalt text when [active].
 */
@Composable
private fun PaneChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    toggle: Pair<Boolean, Role>? = null,
    content: @Composable (tone: Color) -> Unit,
) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(t.radiusSm)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val fill = when {
        active -> t.violetWash
        pressed -> t.line
        else -> t.graphiteRaised
    }
    val action = if (toggle != null) {
        Modifier.toggleable(value = toggle.first, interactionSource = interaction, indication = null, role = toggle.second, onValueChange = { onClick() })
    } else {
        Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
    }
    Row(
        modifier
            .heightIn(min = BrowserPaneMetrics.Ctl)
            .widthIn(min = BrowserPaneMetrics.Ctl)
            .focusRing(focused, t.violet, t.radiusSm)
            .background(paneColor(fill), shape)
            .then(action)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        content(paneColor(if (active) t.violet else t.ink))
    }
}

/** The Select elements toggle (tsx :280-289, `.pickChip`): cobalt wash and cobalt text while picking. */
@Composable
private fun SelectElementsChip(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PaneChip(onClick = onClick, modifier = modifier.testTag(BrowserPaneTags.SelectElements), active = active, toggle = active to Role.Button) { tone ->
        Icon(TetherIcons.SquareDashedMousePointer, contentDescription = null, tint = tone, modifier = Modifier.size(14.dp))
        Text("Select elements", color = tone, fontFamily = Manrope, fontWeight = TetherWeights.label, fontSize = ChipText, maxLines = 1)
    }
}

/** "Screenshot on pick" (tsx :290-293, `.shotChip` :416-420): a quiet chip holding a cobalt checkbox. */
@Composable
private fun ShotOnPickChip(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    PaneChip(onClick = { onCheckedChange(!checked) }, modifier = modifier.testTag(BrowserPaneTags.ShotOnPick), toggle = checked to Role.Checkbox) {
        val boxShape = RoundedCornerShape(3.dp)
        Box(
            Modifier.size(14.dp).cssSurface(boxShape, if (checked) t.violet else t.graphite, border = if (checked) null else CssBorder(1.dp, t.lineStrong)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(TetherIcons.Check, contentDescription = null, tint = t.graphite, modifier = Modifier.size(11.dp))
        }
        Text("Screenshot on pick", color = t.muted, fontFamily = Manrope, fontWeight = StatusWeight, fontSize = ChipText, maxLines = 1)
    }
}

/**
 * `.deadState` (:641-664, tsx :328-335): the socket could not open. A danger icon in a circle, the
 * sentence and why, centred on the page area.
 */
@Composable
private fun DeadState(error: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 384.dp).padding(horizontal = 16.dp, vertical = 32.dp).testTag(BrowserPaneTags.Dead),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StateBadge(Lucide.MonitorX, fill = t.dangerWash, tint = t.danger)
            Text("The browser is not available.", color = t.white, fontFamily = Manrope, fontWeight = BlankTitleWeight, fontSize = 14.sp, textAlign = TextAlign.Center)
            Text(error, color = t.muted, fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

/** `.blankIcon` / `.deadIcon` (:608-619): a 40dp circle holding an 18dp glyph, 6dp above the title. */
@Composable
private fun StateBadge(icon: ImageVector, fill: Color, tint: Color) {
    Box(Modifier.padding(bottom = 6.dp).size(40.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * `.notice` / `.noticeBlocked` / `.alert` (:666-700): a full-width row above the status line with a
 * 1dp top edge, an icon and the whole sentence, wrapping.
 */
@Composable
private fun PaneNoticeRow(
    icon: ImageVector,
    text: String,
    edge: Color,
    fill: Color,
    ink: Color,
    iconTint: Color,
    textTag: String,
    rowTag: String = BrowserPaneTags.NoticeRow,
) {
    Column(Modifier.fillMaxWidth().background(fill).testTag(rowTag)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(edge))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = BrowserPaneMetrics.Pad, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.padding(top = 2.dp).size(14.dp))
            Text(
                text,
                color = ink,
                style = TextStyle(fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = 12.sp, lineHeight = 16.8.sp),
                // tsx :387 (the notice row is `role="status"`): a polite live region (the error row too).
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(textTag),
            )
        }
    }
}

/** `.sep` (:871-876): a 1dp × 12dp rule between the status line's parts. */
@Composable
private fun StatusSep() {
    val t = LocalTetherTokens.current
    Box(Modifier.width(1.dp).height(12.dp).background(t.lineStrong).clearAndSetSemantics {})
}

/**
 * `.status` (:702-723, :850-921; tsx :401-462): the left cluster (live dot and word, then the slot
 * for who is driving the page), the tab indicator, the pick hint; on the right the true emulated
 * size in mono and the page address.
 */
@Composable
private fun StatusLine(ui: BrowserChannel.Ui, wide: Boolean, caption: AgentCaption?, onSwitchTab: (String) -> Unit) {
    val t = LocalTetherTokens.current
    val state = ui.state
    val small = TextStyle(fontFamily = Manrope, fontWeight = StatusWeight, fontSize = StatusText)
    Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
    Row(
        Modifier
            .fillMaxWidth()
            .background(t.graphite)
            .heightIn(min = BrowserPaneMetrics.StatusMin)
            .padding(horizontal = BrowserPaneMetrics.Pad)
            .testTag(BrowserPaneTags.Status),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.testTag(BrowserPaneTags.StatusStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LiveDot(connected = ui.connected, loading = state.loading)
                Text(if (state.loading) "Loading…" else if (ui.connected) "Live" else "Connecting…", color = t.ink, style = small, maxLines = 1)
                if (caption != null) {
                    StatusSep()
                    AgentPill(caption)
                }
            }
            if (state.tabs.size > 1) {
                StatusSep()
                TabPicker(state.tabs, wide, onSwitchTab)
            }
            if (state.pickMode) {
                StatusSep()
                Text(
                    BROWSER_PICK_HINT,
                    color = t.violet, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag(BrowserPaneTags.PickHint),
                )
            }
        }
        Text(
            "${state.viewport.width}×${state.viewport.height}" + if (state.viewport.mobile) " · mobile" else "",
            color = t.muted,
            style = small.copy(fontFamily = JetBrainsMono, fontWeight = TetherWeights.body, fontFeatureSettings = "tnum"),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag(BrowserPaneTags.Readout),
        )
        StatusSep()
        Text(
            state.url,
            color = t.muted, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis,
            // module.css :909-920: the address gives way first while the pill or the tab label is up.
            modifier = Modifier.widthIn(min = if (wide) 32.dp else 0.dp, max = if (wide) 256.dp else if (caption != null && state.tabs.size > 1) 72.dp else if (caption != null || state.tabs.size > 1) 104.dp else 128.dp).testTag(BrowserPaneTags.StatusUrl),
        )
    }
}

/**
 * `.dot` / `.dotLive` / `.dotWaiting` (:878-903): faint while connecting, running green when live,
 * green with a soft ring while a page loads (the ring breathes only without reduced motion).
 */
@Composable
private fun LiveDot(connected: Boolean, loading: Boolean) {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val color = if (loading || connected) t.running else t.faint
    val ringWidth = if (!loading) {
        0f
    } else if (reduced) {
        3f
    } else {
        val pulse = rememberInfiniteTransition(label = "pane-dot")
        val w by pulse.animateFloat(
            initialValue = 3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pane-dot-ring",
        )
        w
    }
    val ringAlpha = if (ringWidth >= 3f || reduced) 0.25f else 0.10f + 0.15f * ((ringWidth - 1f) / 2f)
    Box(
        Modifier.size(7.dp).drawBehind {
            if (ringWidth > 0f) drawCircle(color.copy(alpha = ringAlpha), radius = size.minDimension / 2 + ringWidth.dp.toPx())
            drawCircle(color)
        },
    )
}

/** browser-notice.ts urlNoticeCopy, word for word; [BrowserChannel.UrlNotice.indirect] = a redirect hop or sub-request. */
internal fun urlNoticeCopy(notice: BrowserChannel.UrlNotice): String {
    if (notice.kind == BrowserChannel.UrlNoticeKind.ConsoleBlocked) {
        return "Tether's own console is not opened in this browser; a request to it was stopped."
    }
    val where = if (notice.host.isNotEmpty()) " (${notice.host})" else ""
    return if (notice.indirect) "This page loaded something from a private or local address$where."
    else "This page is on a private or local address$where."
}

/**
 * The live page (browser-pane.tsx :204-224): the frame drawn to the stage's size; a press, a drag
 * (throttled ~30fps) and a release go as `mousePressed` / `mouseMoved` / `mouseReleased` at the
 * mapped page pixel, a wheel as `mouseWheel`, and a hardware key as `keyDown` / `keyUp` (:111-118).
 * In pick mode none of them is forwarded: a drag sends `hover` at the same throttle and the box the
 * server answers with is drawn (:250-254); a tap (a touch within the slop, or any mouse click) is the
 * pick (:108-112 `onClick`).
 *
 * Its look (module.css 0bd07fdd :548-619): a 6dp frame on the panel face (never a white glare before
 * the first frame) with a 1dp `--line-strong` ring and the floating shadow; a 2dp cobalt ring while
 * Select elements is on; on an empty page ([blank]) the quiet invitation, decorative only.
 */
@Composable
private fun BrowserStage(
    frame: ImageBitmap?,
    pickMode: Boolean,
    blank: Boolean,
    viewportWidth: Int,
    viewportHeight: Int,
    actions: BrowserPaneActions,
    hoverBox: HoverBox?,
    stageWidth: Dp,
    stageHeight: Dp,
    marker: AgentMarker?,
    onPick: (x: Int, y: Int) -> Unit,
    modifier: Modifier,
) {
    val t = LocalTetherTokens.current
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pick by rememberUpdatedState(pickMode)
    val currentOnPick by rememberUpdatedState(onPick)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val shape = RoundedCornerShape(BrowserPaneMetrics.FrameRadius)
    Box(
        modifier
            .focusRing(focused, t.violet, BrowserPaneMetrics.FrameRadius, offset = 3.dp)
            .cssSurface(
                shape,
                t.graphite,
                shadows = listOf(if (pickMode) ring(2.dp, t.violet) else ring(1.dp, t.lineStrong)) + t.css.shadowFloating,
            )
            .testTag(BrowserPaneTags.Stage)
            .semantics {
                contentDescription = "Live page — click to interact, or use Select elements"
                role = Role.Image
            }
            .focusRequester(focus)
            .focusable(interactionSource = interaction)
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
                var lastMove = -BROWSER_MOVE_THROTTLE_MS
                var pressedAt: Offset? = null
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
                                    pressedAt = null
                                    change.consume()
                                    actions.input(BrowserInput("mousePressed", x, y, button = "left", buttons = 1, clickCount = 1, modifiers = modifiers))
                                } else {
                                    pressedAt = change.position
                                }
                            }
                            PointerEventType.Release -> if (!pick) {
                                change.consume()
                                actions.input(BrowserInput("mouseReleased", x, y, button = "left", buttons = 0, clickCount = 1, modifiers = modifiers))
                            } else {
                                // The web's `click`: a mouse press-release on the stage, a touch that stayed a tap.
                                val down = pressedAt
                                pressedAt = null
                                if (down != null && (change.type == PointerType.Mouse || (change.position - down).getDistance() <= touchSlop)) {
                                    currentOnPick(x, y)
                                }
                            }
                            PointerEventType.Move -> {
                                // `touch-action: none`: a drag on the page is the page's, never the wrap's scroll.
                                if (!pick) change.consume()
                                val now = SystemClock.uptimeMillis()
                                if (now - lastMove >= BROWSER_MOVE_THROTTLE_MS) {
                                    lastMove = now
                                    if (pick) {
                                        actions.hover(x, y)
                                    } else {
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
            Image(frame, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize().clip(shape))
        }
        if (blank) BlankInvitation(shape)
        if (marker != null && viewportWidth > 0 && viewportHeight > 0) {
            key(marker.seq) { AgentMarkerBox(marker, viewportWidth, viewportHeight, stageWidth, stageHeight) }
        }
        if (pickMode && hoverBox != null && viewportWidth > 0 && viewportHeight > 0) {
            HoverHighlight(hoverBox, viewportWidth, viewportHeight, stageWidth, stageHeight)
        }
    }
}

/**
 * `.agentMarker` (module.css :818-860, tsx :371-381): where the agent's action lands, over the page
 * frame only (never part of the page, so never in a screenshot): a cobalt ring on a faint wash, a
 * point drawn as a 20dp circle on it. It fades in and out over its 1.6 s (static under reduced
 * motion), takes no touch and says nothing to a screen reader (the pill and the live region do).
 */
@Composable
private fun AgentMarkerBox(marker: AgentMarker, vw: Int, vh: Int, stageWidth: Dp, stageHeight: Dp) {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val p = BrowserAgentUi.placement(marker.box, vw, vh)
    val left = stageWidth * (p.left / 100.0).toFloat()
    val top = stageHeight * (p.top / 100.0).toFloat()
    val alpha = remember(marker.seq) { Animatable(if (reduced) 1f else 0f) }
    if (!reduced) {
        LaunchedEffect(marker.seq) {
            alpha.animateTo(1f, tween(192))
            alpha.animateTo(1f, tween(1008))
            alpha.animateTo(0f, tween(400))
        }
    }
    val shape = if (p.point) CircleShape else RoundedCornerShape(4.dp)
    val boxModifier = if (p.point) {
        Modifier.offset(left - 10.dp, top - 10.dp).size(20.dp)
    } else {
        Modifier.offset(left, top).size(stageWidth * (p.width / 100.0).toFloat(), stageHeight * (p.height / 100.0).toFloat())
    }
    Box(
        boxModifier
            .graphicsLayer { this.alpha = alpha.value }
            .cssSurface(shape, t.violet.copy(alpha = 0.14f), border = CssBorder(2.dp, t.violet), shadows = listOf(ring(1.dp, t.graphite)))
            .testTag(BrowserPaneTags.Marker),
    )
}

/**
 * `.agentPill` (:786-816, tsx :431-446): "Agent" while the agent uses the browser, "You took over"
 * in attention colours when the operator acts. The words are drawn; a screen reader gets the whole
 * sentence instead (the caption's `sentence`).
 */
@Composable
private fun AgentPill(caption: AgentCaption) {
    val t = LocalTetherTokens.current
    val shape = CircleShape
    val tone = if (caption.tookOver) t.attentionInk else t.violet
    Row(
        Modifier
            .cssSurface(
                shape,
                if (caption.tookOver) t.attentionBg else t.violetWash,
                shadows = if (caption.tookOver) listOf(CssShadow(inset = true, offsetX = 0.dp, offsetY = 0.dp, blur = 0.dp, spread = 1.dp, color = t.attentionBorder)) else emptyList(),
            )
            .padding(horizontal = 7.dp, vertical = 1.dp)
            .testTag(BrowserPaneTags.AgentPill)
            .clearAndSetSemantics { contentDescription = caption.sentence },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(if (caption.tookOver) Lucide.Hand else Lucide.MousePointer2, contentDescription = null, tint = tone, modifier = Modifier.size(12.dp))
        Text(caption.text, color = tone, fontFamily = Manrope, fontWeight = TetherWeights.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * `.tabIndicator` / `.tabPick` (:725-775, tsx :408-433): the tab label with a chevron; touching it
 * lists the open tabs ("1. title") and choosing one sends the tab switch. The hit area is 48dp.
 */
@Composable
private fun TabPicker(tabs: List<BrowserChannel.Tab>, wide: Boolean, onSwitchTab: (String) -> Unit) {
    val t = LocalTetherTokens.current
    val small = TextStyle(fontFamily = Manrope, fontWeight = StatusWeight, fontSize = StatusText)
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val activeTab = tabs.firstOrNull { it.active }
    Box {
        Row(
            Modifier
                .widthIn(max = if (wide) 192.dp else 144.dp)
                .heightIn(min = 48.dp)
                .focusRing(focused, t.violet, 4.dp)
                .clickable(interaction, indication = null, role = Role.DropdownList, onClickLabel = "Switch tab") { open = true }
                .testTag(BrowserPaneTags.TabPicker),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                browserTabLabel(tabs),
                color = t.muted, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).testTag(BrowserPaneTags.TabIndicator),
            )
            Icon(TetherIcons.ChevronDown, contentDescription = null, tint = t.faint, modifier = Modifier.size(11.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = t.graphiteRaised) {
            if (activeTab == null) {
                DropdownMenuItem(text = { Text("Choose a tab", color = t.faint) }, onClick = {}, enabled = false)
            }
            tabs.forEach { tab ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${tab.index}. ${tab.title.ifEmpty { tab.url.ifEmpty { "New tab" } }}",
                            color = if (tab.active) t.violet else t.ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        open = false
                        // A tab with no id cannot be named: nothing to send (the web's option has no value to send either).
                        if (tab.id.isNotEmpty()) onSwitchTab(tab.id)
                    },
                    modifier = Modifier.semantics { selected = tab.active }.testTag(BrowserPaneTags.tabOption(tab.id.ifEmpty { "none-${tab.index}" })),
                )
            }
        }
    }
}

/**
 * `.profile` / `.select` (module.css :422-477, tsx :296-312): the session's browser profile. The
 * face is the toolbar's filled field with the chevron; the list is the default throwaway profile,
 * the session's own profile when the server's list does not carry it, then every named profile
 * ("(in use by <holder>)" when another session holds it). Opening it reads the list again.
 */
@Composable
private fun ProfileSelect(profile: BrowserChannel.PaneProfile?, list: List<BrowserProfileInfo>, actions: BrowserPaneActions, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(t.radiusSm)
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val style = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = FieldText, color = t.ink)
    val label = profileLabel(profile)
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(BrowserPaneMetrics.Ctl)
                .cssSurface(
                    shape,
                    paneColor(if (focused) t.graphite else if (pressed) t.line else t.graphiteRaised),
                    shadows = if (focused) listOf(ring(1.dp, t.violet), ring(4.dp, t.focusGlow)) else emptyList(),
                )
                .clickable(interaction, indication = null, role = Role.DropdownList, onClickLabel = "Profile") {
                    open = true
                    actions.refreshProfiles()
                }
                .padding(start = 10.dp, end = 8.dp)
                .testTag(BrowserPaneTags.ProfileSelect)
                .semantics { contentDescription = "Profile"; stateDescription = label },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clearAndSetSemantics {})
            Icon(TetherIcons.ChevronDown, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = t.graphiteRaised) {
            @Composable fun item(id: String?, text: String) {
                DropdownMenuItem(
                    text = { Text(text, color = if (id == profile?.id) t.violet else t.ink, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        actions.pickProfile(id)
                    },
                    modifier = Modifier.semantics { selected = id == profile?.id }.testTag(BrowserPaneTags.profileOption(id)),
                )
            }
            item(null, PROFILE_DEFAULT_LABEL)
            if (profile != null && list.none { it.id == profile.id }) item(profile.id, profile.name)
            list.forEach { p ->
                item(p.id, p.name + if (p.heldBy != null && p.id != profile?.id) " (in use by ${p.heldBy})" else "")
            }
        }
    }
}

/** tsx :313-316: "Using: <profile>. Changing the profile restarts the open browser on it." */
@Composable
private fun ProfileHint(profile: BrowserChannel.PaneProfile?) {
    val t = LocalTetherTokens.current
    Text(
        "Using: ${profileLabel(profile)}. $PROFILE_PICK_HINT",
        color = t.muted,
        style = TextStyle(fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = 11.sp, lineHeight = 15.4.sp),
        modifier = Modifier.fillMaxWidth().testTag(BrowserPaneTags.ProfileHint),
    )
}

/** tsx :317 `.profileRefusal` (module.css :487-497): the server's sentence for a refused pick, an alert. */
@Composable
private fun ProfileRefusal(text: String) {
    val t = LocalTetherTokens.current
    Text(
        text,
        color = t.attentionInk,
        style = TextStyle(fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = 12.sp, lineHeight = 16.8.sp),
        modifier = Modifier
            .fillMaxWidth()
            .background(t.attentionBg, RoundedCornerShape(t.radiusSm))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .testTag(BrowserPaneTags.ProfileRefusal),
    )
}

/**
 * `.blank` (:583-606, tsx :359-367): no page yet. On the panel face, a globe in a circle, "No page
 * open yet" and the hint; decorative (no semantics), and taps still reach the page underneath.
 */
@Composable
private fun BlankInvitation(shape: androidx.compose.ui.graphics.Shape) {
    val t = LocalTetherTokens.current
    Column(
        Modifier.fillMaxSize().background(t.graphite, shape).padding(16.dp).clearAndSetSemantics { semanticsTestTag = BrowserPaneTags.Blank },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        StateBadge(Lucide.Globe, fill = t.graphiteRaised, tint = t.muted)
        Text(BROWSER_BLANK_TITLE, color = t.white, fontFamily = Manrope, fontWeight = BlankTitleWeight, fontSize = 14.sp, textAlign = TextAlign.Center)
        Text(BROWSER_BLANK_BODY, color = t.muted, fontFamily = Manrope, fontWeight = TetherWeights.body, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

/**
 * `.highlight` / `.highlightLabel` (browser-pane.module.css 0bd07fdd :621-639): the picked-to
 * element's box scaled from page pixels to the stage, a 2dp cobalt border over an 18% cobalt wash,
 * its short label (tag#id.class) in mono, `--accent-ink` on `--accent` (AA in both skins, spec §6),
 * on a tab sitting on the box's top edge.
 */
@Composable
private fun HoverHighlight(hover: HoverBox, vw: Int, vh: Int, stageWidth: Dp, stageHeight: Dp) {
    val t = LocalTetherTokens.current
    val left = (hover.box.x / vw * stageWidth.value).dp
    val top = (hover.box.y / vh * stageHeight.value).dp
    val width = (hover.box.width / vw * stageWidth.value).dp
    val height = (hover.box.height / vh * stageHeight.value).dp
    Box(
        Modifier
            .offset(left, top)
            .size(width, height)
            .background(t.violet.copy(alpha = 0.18f))
            .border(2.dp, t.violet)
            .testTag(BrowserPaneTags.Highlight),
    ) {
        Text(
            hover.label,
            color = t.accentInk,
            fontFamily = JetBrainsMono,
            fontWeight = TetherWeights.body,
            fontSize = 10.sp,
            maxLines = 1,
            modifier = Modifier
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints.copy(maxWidth = Int.MAX_VALUE))
                    layout(p.width, p.height) { p.place(0, -p.height) }
                }
                .background(t.accent, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
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
