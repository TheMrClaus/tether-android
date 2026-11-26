package com.tether.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.tether.app.client.ProposalLocalState
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.theme.LocalTetherTokens
import kotlinx.serialization.json.JsonObject

/*
 * v175 (#293): the native card for an agent's new-session proposal (design/293-proposal-card.md, section 9): a header, the
 * title, a two-line preview, an X, and Options / Do it here / Start. The Options rows are the new-session composer's own
 * pickers, supplied by the host through ONE interface ([ProposalOptionsHost]), so :feature:chat holds no second picker.
 */

/** What the host hands the card's Options panel: the proposal's suggestions and where the operator's final settings go. */
class ProposalOptionsRequest(
    val proposalId: String,
    /** The PROPOSING session (its folder, harness, account, model and effort are the defaults for rows the agent left out). */
    val sessionId: String,
    val suggestions: JsObj,
    val replaced: List<ProposalReplacedView>,
    /** "folder" | "harness" | "account" while the last Start was refused for that row; else null. */
    val faultRow: String?,
    val enabled: Boolean,
    /** The create-shaped settings of the form as it stands, reported on every change and once on open. */
    val onSettings: (JsonObject) -> Unit,
)

/** The one seam: [com.tether.app.ui.chat]'s card draws the panel's frame and placement; the host draws the rows. */
fun interface ProposalOptionsHost {
    @Composable
    fun Rows(request: ProposalOptionsRequest)

    /**
     * Composed by the card for as long as its Start is available, panel open or not: keeps the form seeded and reports its
     * create-shaped settings through [ProposalOptionsRequest.onSettings], so a Start pressed without ever opening Options still
     * carries the final value of every row. Draws nothing.
     */
    @Composable
    fun Settle(request: ProposalOptionsRequest) {}
}

/** What the card may do (read through [LocalProposalActions]); the default does nothing and shows no Options. */
class ProposalActions(
    val sessionId: String? = null,
    val local: Map<String, ProposalLocalState> = emptyMap(),
    val onStart: (proposalId: String, settings: JsonObject?) -> Unit = { _, _ -> },
    val onHere: (proposalId: String) -> Unit = {},
    val onDismiss: (proposalId: String) -> Unit = {},
    val onOpenSession: (sessionId: String) -> Unit = {},
    val optionsHost: ProposalOptionsHost? = null,
    /** The settings the operator's Options choices ended on, per proposal id (kept while the screen lives, so a scrolled-away card still starts with them). */
    val chosen: MutableMap<String, JsonObject> = HashMap(),
    /** The server speaks protocol >= 175 ([com.tether.app.client.proposalCardSupported]): a proposal shows as its card; below it the tool row stays. */
    val cardShown: Boolean = false,
) {
    companion object {
        val Unavailable = ProposalActions()
    }
}

fun buildProposalActions(
    vm: com.tether.app.ui.TetherViewModel,
    sessionId: String?,
    local: Map<String, ProposalLocalState>,
    host: ProposalOptionsHost?,
    chosen: MutableMap<String, JsonObject>,
    cardShown: Boolean,
): ProposalActions {
    fun send(kind: String, proposalId: String, settings: JsonObject?) {
        if (sessionId != null) vm.client.sendProposal(com.tether.app.protocol.ClientMessage.ProposalFrame(kind, sessionId, proposalId, settings, java.util.UUID.randomUUID().toString()))
    }
    return ProposalActions(
        sessionId = sessionId,
        local = local,
        onStart = { id, settings -> send("start", id, settings) },
        onHere = { id -> send("here", id, null) },
        onDismiss = { id -> send("dismiss", id, null) },
        onOpenSession = { id -> vm.selectSession(id) },
        optionsHost = host,
        chosen = chosen,
        cardShown = cardShown,
    )
}

/** The card's actions for the shown session: the shell provides them around the chat screen ([LocalProposalActions]). */
@Composable
fun rememberProposalActions(vm: com.tether.app.ui.TetherViewModel, sessionId: String?, host: ProposalOptionsHost?): ProposalActions {
    val local by vm.client.proposalLocal.collectAsStateWithLifecycle()
    val protocol by vm.client.serverProtocolVersion.collectAsStateWithLifecycle()
    val chosen = remember { HashMap<String, JsonObject>() }
    val shown = com.tether.app.client.proposalCardSupported(protocol)
    return remember(vm.client, sessionId, local, host, shown) { buildProposalActions(vm, sessionId, local, host, chosen, shown) }
}

val LocalProposalActions = compositionLocalOf { ProposalActions.Unavailable }

/** The transcript list's visible box in window coordinates, and a way to scroll it; set by the transcript, read by the Options panel. */
class TranscriptViewport {
    var bounds: Rect? by mutableStateOf(null)
    var scrollBy: suspend (Float) -> Float = { 0f }
}

val LocalTranscriptViewport = compositionLocalOf { TranscriptViewport() }

/**
 * Where the Options panel goes (spec 3.3): inside [bounds] (the list's visible box minus the keyboard), below the card when it
 * fits, else above, else on the side with more room, never outside. [capHeight] is the height it may take.
 */
object ProposalPlacement {
    /** The box the panel may use: the list's visible box (the window only when the list has not reported yet) minus the keyboard. */
    fun panelBounds(list: IntRect?, windowWidth: Int, windowHeight: Int, ime: Int): IntRect {
        val box = list ?: IntRect(0, 0, windowWidth, windowHeight)
        return IntRect(box.left, box.top, box.right, minOf(box.bottom, windowHeight - ime))
    }

    fun capHeight(card: IntRect, bounds: IntRect, gap: Int, floor: Int): Int =
        maxOf(floor, maxOf(bounds.bottom - card.bottom - gap, card.top - bounds.top - gap))

    /**
     * Scroll to fit, once on open (spec 3.3): how far to scroll the list (positive = further down the transcript) so the card is in
     * view and the panel of [content] height fits below it (else above). Null when the card is in view and the panel already fits
     * on a side, or when no scroll can make room (the panel then caps and scrolls itself). [actionRow] is the card's action row,
     * which must stay inside [bounds] when the scroll is to make room below.
     */
    fun scrollToFit(card: IntRect, content: Int, bounds: IntRect, gap: Int, actionRow: Int): Float? {
        val nearest = when {
            card.bottom > bounds.bottom -> card.bottom - bounds.bottom
            card.top < bounds.top -> card.top - bounds.top
            else -> 0
        }
        val moved = IntRect(card.left, card.top - nearest, card.right, card.bottom - nearest)
        val below = bounds.bottom - moved.bottom - gap
        val above = moved.top - bounds.top - gap
        if (content <= below || content <= above) return nearest.takeIf { it != 0 }?.toFloat()
        val down = content - below
        if (down <= moved.bottom - bounds.top - actionRow) return (nearest + down).toFloat()
        val up = content - above
        if (up <= bounds.bottom - moved.bottom) return (nearest - up).toFloat()
        return nearest.takeIf { it != 0 }?.toFloat()
    }

    fun place(card: IntRect, contentHeight: Int, bounds: IntRect, gap: Int): Int {
        val below = bounds.bottom - card.bottom - gap
        val above = card.top - bounds.top - gap
        return when {
            contentHeight <= below -> card.bottom + gap
            contentHeight <= above -> card.top - gap - contentHeight
            below >= above -> card.bottom + gap
            else -> card.top - gap - contentHeight
        }
    }
}

private class ProposalPositionProvider(private val card: IntRect, private val bounds: IntRect, private val gap: Int, private val inset: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (card.left + inset).coerceIn(bounds.left, maxOf(bounds.left, bounds.right - popupContentSize.width))
        val y = ProposalPlacement.place(card, popupContentSize.height, bounds, gap)
        return IntOffset(x, y.coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - popupContentSize.height)))
    }
}

private fun Rect.toIntRect() = IntRect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

@Composable
fun ProposalCard(proposal: ProposalView, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val actions = LocalProposalActions.current
    val face = proposalFace(proposal, actions.local[proposal.proposalId])
    val state = face.state
    val refusalText = face.refusalText
    val view = face.view
    val busy = face.busy
    var optionsOpen by remember(proposal.proposalId) { mutableStateOf(false) }
    var previewOpen by remember(proposal.proposalId) { mutableStateOf(false) }
    var cardBounds by remember { mutableStateOf<Rect?>(null) }
    val shape = RoundedCornerShape(14.dp)
    val title = remember(proposal.title) { SafeText.line(proposal.title) }
    val promptText = remember(proposal.prompt) { SafeText.prose(proposal.prompt) }
    val host = actions.optionsHost
    val headerHeight = 44.dp
    val titleLine = 20.sp
    Box(
        modifier.fillMaxWidth().border(1.dp, t.line, shape).background(t.graphite, shape)
            .onGloballyPositioned { cardBounds = it.boundsInWindow() }
            .semantics { contentDescription = "New session: $title" }
            .testTag("proposal-card"),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().height(headerHeight).background(t.graphiteRaised, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .drawBehind { drawRect(t.line, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx())) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("New session", color = t.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                ProposalStatus(view)
            }
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = if (state == ProposalState.Started && proposal.startedSessionId != null) 0.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title, color = t.ink, fontSize = 15.sp, lineHeight = titleLine, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(end = if (state.resolved) 0.dp else 24.dp).testTag("proposal-title"),
                )
                Text(
                    promptText, color = t.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Read the full prompt") { previewOpen = true }.testTag("proposal-preview"),
                )
                when (state) {
                    ProposalState.Started -> {
                        Text("Running in a new session.", color = t.muted, fontSize = 13.sp)
                        val id = proposal.startedSessionId
                        if (id != null) {
                            Text(
                                "Open “${SafeText.line(proposal.startedSessionName ?: proposal.title)}” →", color = t.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).clickable(role = Role.Button) { actions.onOpenSession(id) }.testTag("proposal-open"),
                            )
                        }
                    }
                    ProposalState.Here -> Text("The prompt was sent to this session.", color = t.muted, fontSize = 13.sp)
                    ProposalState.Expired -> Text("This proposal can no longer be started.", color = t.muted, fontSize = 13.sp)
                    else -> Unit
                }
                if (!state.resolved && refusalText != null) {
                    Text(
                        "Couldn’t start. $refusalText", color = t.danger, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().background(t.dangerWash, RoundedCornerShape(8.dp)).border(BorderStroke(1.dp, t.dangerEdge), RoundedCornerShape(8.dp)).padding(8.dp)
                            .semantics { liveRegion = LiveRegionMode.Assertive }.testTag("proposal-error"),
                    )
                }
                if (!state.resolved) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (host != null) {
                            val replacedCount = proposal.replaced.size
                            ProposalButton(
                                "Options", primary = false, enabled = !busy, expanded = optionsOpen,
                                description = if (replacedCount > 0) "Options, $replacedCount suggestion${if (replacedCount == 1) "" else "s"} replaced" else "Options",
                                tag = "proposal-options",
                                trailing = if (replacedCount > 0) {
                                    { Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.warning, modifier = Modifier.padding(start = 4.dp).size(14.dp).testTag("proposal-warning")) }
                                } else null,
                            ) { optionsOpen = !optionsOpen }
                        }
                        Box(Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProposalButton("Do it here", primary = false, bare = true, enabled = !busy, tag = "proposal-here") { actions.onHere(proposal.proposalId) }
                            ProposalButton(if (state == ProposalState.Starting) "Starting…" else "Start", primary = true, enabled = !busy, tag = "proposal-start") { actions.onStart(proposal.proposalId, actions.chosen[proposal.proposalId]) }
                        }
                    }
                }
            }
        }
        if (!state.resolved) {
            // The X sits level with the title's first line: a 48dp hit box over the card, so it adds no height to the title row.
            Box(
                Modifier.align(Alignment.TopEnd).offset(y = headerHeight + 8.dp + 10.dp - 24.dp).size(48.dp)
                    .clickable(enabled = !busy, role = Role.Button, onClickLabel = "Dismiss") { actions.onDismiss(proposal.proposalId) }.semantics { contentDescription = "Dismiss" }.testTag("proposal-dismiss"),
                contentAlignment = Alignment.Center,
            ) { Icon(TetherIcons.X, contentDescription = null, tint = t.muted, modifier = Modifier.size(16.dp)) }
        }
    }
    if (!state.resolved && host != null) {
        val request = ProposalOptionsRequest(proposal.proposalId, actions.sessionId.orEmpty(), proposal.suggestions, proposal.replaced, view.faultRow, !busy) { actions.chosen[proposal.proposalId] = it }
        host.Settle(request)
    }
    val box = cardBounds
    if (optionsOpen && !state.resolved && host != null && box != null) ProposalOptionsPanel(proposal, view, box, host, busy) { actions.chosen[proposal.proposalId] = it }
    if (previewOpen) ProposalPreviewDialog(proposal) { previewOpen = false }
}

@Composable
private fun ProposalStatus(view: ProposalView) {
    val t = LocalTetherTokens.current
    val (icon, word) = when (view.state) {
        ProposalState.Started -> TetherIcons.Check to "Started"
        ProposalState.Here -> TetherIcons.Undo2 to "Continued here"
        ProposalState.Dismissed -> TetherIcons.Ban to "Dismissed"
        ProposalState.Expired -> TetherIcons.Clock to "Expired"
        else -> return
    }
    Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("proposal-status"), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (view.state == ProposalState.Started) t.running else t.muted, modifier = Modifier.size(14.dp))
        Text(word, color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun ProposalButton(
    label: String, primary: Boolean, enabled: Boolean, tag: String, description: String = label, expanded: Boolean = false, bare: Boolean = false,
    trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(10.dp)
    val fill = when {
        primary -> t.accent
        bare -> Color.Transparent
        expanded -> t.lineStrong
        else -> t.slate
    }
    Row(
        Modifier.heightIn(min = 48.dp).background(fill, shape).clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp).semantics { contentDescription = description }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (primary) t.accentInk else t.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        trailing?.invoke()
    }
}

/** The anchored Options panel: bounded by the transcript's visible box minus the keyboard, scrolled to fit once, capped and scrolling past that. */
@Composable
private fun ProposalOptionsPanel(proposal: ProposalView, view: ProposalView, card: Rect, host: ProposalOptionsHost, busy: Boolean, onSettings: (JsonObject) -> Unit) {
    val viewport = LocalTranscriptViewport.current
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val ime = WindowInsets.ime.getBottom(density)
    val bounds = ProposalPlacement.panelBounds(viewport.bounds?.toIntRect(), LocalWindowInfo.current.containerSize.width, windowHeight, ime)
    val gap = with(density) { 4.dp.roundToPx() }
    val cap = ProposalPlacement.capHeight(card.toIntRect(), bounds, gap, with(density) { 96.dp.roundToPx() })
    val actions = LocalProposalActions.current
    var natural by remember { mutableStateOf(0) }
    var scrolled by remember { mutableStateOf(false) }
    LaunchedEffect(natural) {
        if (scrolled || natural == 0) return@LaunchedEffect
        scrolled = true
        val delta = ProposalPlacement.scrollToFit(card.toIntRect(), natural, bounds, gap, with(density) { 56.dp.roundToPx() })
        if (delta != null) viewport.scrollBy(delta)
    }
    Popup(
        popupPositionProvider = remember(card, bounds) { ProposalPositionProvider(card.toIntRect(), bounds, gap, with(density) { 8.dp.roundToPx() }) },
        properties = PopupProperties(focusable = false),
    ) {
        ProposalOptionsPanelChrome(with(density) { cap.toDp() }, onNaturalHeight = { natural = it }) {
            host.Rows(ProposalOptionsRequest(proposal.proposalId, actions.sessionId.orEmpty(), proposal.suggestions, proposal.replaced, view.faultRow, !busy, onSettings))
        }
    }
}

/** The Options panel's frame: a bordered, scrolling box no taller than [maxHeight]; [onNaturalHeight] reports the rows' own height. */
@Composable
fun ProposalOptionsPanelChrome(maxHeight: Dp, modifier: Modifier = Modifier, onNaturalHeight: (Int) -> Unit = {}, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    Column(
        modifier.padding(horizontal = 4.dp).heightIn(max = maxHeight).cssSurface(RoundedCornerShape(16.dp), t.graphite, CssBorder(1.dp, t.lineStrong), t.css.shadowFloating)
            .verticalScroll(rememberScrollState()).padding(6.dp).testTag("proposal-options-panel"),
    ) {
        Column(Modifier.onSizeChanged { onNaturalHeight(it.height) }) { content() }
    }
}

/** The full prompt, read only, with Copy (D5(9)). Opens in every state. */
@Composable
internal fun ProposalPreviewDialog(proposal: ProposalView, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ProposalPreviewContent(proposal, onClose)
    }
}

/** The preview's body (the dialog's content; the goldens draw it in place). */
@Composable
internal fun ProposalPreviewContent(proposal: ProposalView, onClose: () -> Unit) {
    val t = LocalTetherTokens.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    run {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(12.dp).background(t.graphite, RoundedCornerShape(16.dp)).border(1.dp, t.line, RoundedCornerShape(16.dp)).testTag("proposal-prompt")) {
            Column(Modifier.padding(16.dp)) {
                Text(SafeText.line(proposal.title), color = t.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Prompt for the new session · read only", color = t.muted, fontSize = 12.sp)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                androidx.compose.foundation.text.selection.SelectionContainer { Text(SafeText.prose(proposal.prompt), color = t.ink, fontSize = 14.sp, lineHeight = 22.sp) }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                ProposalButton(if (copied) "Copied" else "Copy prompt", primary = false, enabled = true, tag = "proposal-copy") {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("prompt", proposal.prompt))
                    copied = true
                }
                ProposalButton("Close", primary = false, enabled = true, tag = "proposal-close", onClick = onClose)
            }
        }
    }
}
