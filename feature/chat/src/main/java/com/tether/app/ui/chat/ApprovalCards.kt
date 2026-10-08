package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.client.ConsentResult
import com.tether.app.client.consentKey
import com.tether.app.protocol.GrantedPermissions
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherInputWell
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.hardShadow
import com.tether.app.ui.components.maxWidthFraction
import com.tether.app.ui.components.originalWords
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.TetherTypography
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.codeDirection
import com.tether.app.ui.text.codeText
import com.tether.app.ui.text.appendSafe
import com.tether.app.ui.text.proseText
import com.tether.app.ui.text.tokenStyle

/*
 * T6.3: the attention cards (chat-view.tsx 458-498 PermissionDenialCard, 965-1129 QuestionCard,
 * 1131-1151 AnsweredQuestionCard, 1153-1325 ApprovalCard). Styles resolve globals.css 5334-5445
 * (denial), 6168-6318 (approval, grants, question), 8595-8765 + 9087-9110 (material layer) and
 * 11294-11299 (premium wash), studio.css 381-382 + 489-495.
 *
 * Consent (SYNC_DESIGN §5.1 I2/I3): a card only ever calls [ConsentActions] from a tap, sends at most
 * once (its own guard plus the client's ledger), and renders disabled with the reason in words
 * whenever [ConsentActions.lock] says it cannot be answered here. ta-coik.26: the chat screen sets
 * no lock (the web draws the cards live on a read-only or handed-off session too).
 */

private fun rem(r: Float): TextUnit = (r * TetherTypography.SP_PER_REM).sp

/**
 * A `<p>`'s UA block margin (1em of its own font size): globals.css resets it only where a rule says
 * `margin: 0` (the approval reason/context lines, the denial's paragraphs), so the card spacing the
 * web shows includes it. In a flex column, sibling margins do not collapse.
 */
private fun pMargin(r: Float) = (r * TetherTypography.SP_PER_REM).dp

/** Why no card of this session can be answered right now; the copy is the visible reason. */
enum class ConsentLock(val copy: String) {
    /**
     * No connection. ta-coik.24: the chat screen no longer applies this to the cards or the session
     * controls ([commandKeyLock]), as the web draws them live whatever the link; it remains the
     * fail-closed default of a screen with no session ([ConsentActions.Unavailable]). ta-coik.57: there
     * is no "catching up" lock at all: the web locks nothing while a session is catching up.
     */
    Offline("Connect to answer. This is a saved copy."),

    /** `session.readOnly`: an imported replay Tether does not drive (the session controls and Stop keys; ta-coik.26: never the cards). */
    ReadOnly("Read-only: Tether isn’t driving this conversation, so it can’t answer here."),

    /** `session.handedOffTo`: the work continues in another session (ta-coik.26: never the cards). */
    HandedOff("This session was handed off. Answer in the session it continued in."),
}

/**
 * The lock for [session] (pure; tested; the session controls and Stop keys use it, ta-coik.26: the
 * approval and question cards no longer do): read-only and handed-off first (they hold whatever the
 * link does), then no connection. ta-coik.57: whether the projection is live yet locks nothing.
 */
fun consentLock(connected: Boolean, session: AgentSession?): ConsentLock? = when {
    session?.readOnly == true -> ConsentLock.ReadOnly
    !session?.handedOffTo.isNullOrEmpty() -> ConsentLock.HandedOff
    !connected -> ConsentLock.Offline
    else -> null
}

/**
 * What the cards of one session may do. [onApproval] / [onAnswer] are the ONLY ways a card reaches
 * the wire, and a card calls them from a tap handler and nowhere else.
 */
@Immutable
class ConsentActions(
    val sessionId: String?,
    /** The live socket's server origin: the cards' fingerprints are computed for it (null: none). */
    val origin: String?,
    val lock: ConsentLock?,
    /** [consentKey]s this process already decided (the client's ledger). */
    val decided: Set<String>,
    /** chat-view.tsx:3658 unavailableReason (a legacy OpenCode session), or null. */
    val questionUnavailable: String?,
    internal val onApproval: (requestId: String, fingerprint: String, choiceId: String?, decision: String?, granted: GrantedPermissions?) -> ConsentResult,
    internal val onAnswer: (requestId: String, fingerprint: String, picks: List<com.tether.app.client.ConsentGuard.QuestionPick>, skipped: Set<Int>) -> ConsentResult,
    val onOpenRun: (runId: String) -> Unit,
    /** L4: decided keys sent on an earlier socket (delivery unconfirmed). */
    val unconfirmed: Set<String> = emptySet(),
    /** T6.4: open [runId]'s tab AND land on its step [toolId] (chat-view.tsx focusSubagentCall). */
    val onFocusCall: (runId: String, toolId: String) -> Unit = { runId, _ -> onOpenRun(runId) },
) {
    fun isDecided(requestId: String, fingerprint: String): Boolean = sessionId != null && consentKey(sessionId, requestId, fingerprint) in decided

    fun isUnconfirmed(requestId: String, fingerprint: String): Boolean = sessionId != null && consentKey(sessionId, requestId, fingerprint) in unconfirmed

    companion object {
        /** The fail-closed default: nothing is actionable and nothing is sent. */
        val Unavailable = ConsentActions(
            sessionId = null,
            origin = null,
            lock = ConsentLock.Offline,
            decided = emptySet(),
            questionUnavailable = null,
            onApproval = { _, _, _, _, _ -> ConsentResult.NotConnected },
            onAnswer = { _, _, _, _ -> ConsentResult.NotConnected },
            onOpenRun = {},
        )

        /** chat-view.tsx:3658-3660. */
        const val LEGACY_OPENCODE_QUESTION =
            "This legacy OpenCode session cannot deliver interactive answers. Interrupt this turn and start a new OpenCode chat to continue with question support."
    }
}

/** L4: a decision this process sent on a socket that dropped before the request was seen resolved. */
internal const val UNCONFIRMED_COPY = "Sent before the connection dropped — delivery unconfirmed. It will not be sent again."

/** The cards read their session's consent state here (only the cards recompose when it changes). */
val LocalConsent = compositionLocalOf { ConsentActions.Unavailable }

/** A decision the client took or already had: the card stays in its sent state either way. */
private fun ConsentResult.settles(): Boolean = this == ConsentResult.Sent || this == ConsentResult.AlreadyDecided

/**
 * I3 (tapjacking): a touch that reached us through another window drawn over ours
 * (`FLAG_WINDOW_IS_OBSCURED` / `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`) is consumed before the control
 * sees it, so an overlay cannot trick the operator into a decision. Accessibility actions are not
 * touches and are unaffected. Stricter than the web (a browser has no such signal).
 */
@android.annotation.SuppressLint("InlinedApi") // the flag is simply never set below API 29
internal fun Modifier.refuseObscuredTouches(onBlocked: () -> Unit = {}): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (isObscured(event.motionEvent?.flags ?: 0)) {
                event.changes.forEach { it.consume() }
                onBlocked()
            }
        }
    }
}

/** Info: why a card ignored a touch (an overlay drawn over the app). */
internal const val OVERLAY_COPY = "A screen overlay is blocking this card. Close the other app's overlay to answer."

/** The two "a window covered this touch" flags. */
internal fun isObscured(flags: Int): Boolean =
    flags and (android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED or FLAG_PARTIALLY_OBSCURED) != 0

/** `MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED` (API 29), by value: minSdk is 26. */
internal const val FLAG_PARTIALLY_OBSCURED = 0x2

@Composable
private fun cardShape(t: TetherTokens): RoundedCornerShape = RoundedCornerShape(14.dp)

/** A status line in words (never colour alone), announced politely when it appears. */
@Composable
private fun StatusLine(text: String, color: Color, tag: String) {
    Text(
        text,
        style = TextStyle(fontFamily = LocalTetherTypography.current.body.fontFamily, fontSize = rem(0.8f), lineHeight = rem(0.8f) * 1.5f),
        color = color,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag),
    )
}

// --- ApprovalCard -----------------------------------------------------------------------------------

/**
 * `.chat-approval`: `--attention-bg` under `--attention-border`, `--radius-md`, `space-lg` padding
 * with the lit edge and the floating shadow (the premium wash, globals.css:11294); Studio a flat
 * 0.875rem card padded 1.25rem. Head: the warning triangle in `--attention-ink`, "Approval needed"
 * white 0.98rem/700. Then the tool, the reason and context lines, the call's input, the requested
 * permission expansion (T6.3 "permission paths") and the choice keys.
 *
 * ta-4za3: the card has no height of its own, as on the web (no `max-height`, no inner scroll box, no fade):
 * every path row is part of the card and the card grows. It is drawn as contiguous SEGMENTS the host list
 * emits as items of its own (a lazy list inside a lazy item cannot take unbounded height): the [ApprovalPart.Head]
 * (card top, tool, reason, input, the fieldset's top edge and legend), one [ApprovalPart.Entry] per
 * [GrantEntry] (a path, or a later piece of a long path) and the [ApprovalPart.Tail] (network, confirmation,
 * the fieldset's bottom edge, status, the keys, the card's bottom edge). Each segment paints the card's and
 * the fieldset's side strokes itself, so the seams show nothing; the host adds no spacing between them.
 */
internal enum class ApprovalPart { Head, Entry, Tail }

/** One host-list item of a card. [gapBefore]: the `space-xs` gap above a path's first piece (not the first path). */
@Immutable
internal class ApprovalSegment(val part: ApprovalPart, val key: String, val entry: GrantEntry? = null, val gapBefore: Boolean = false) {
    val contentType: String get() = "approval-" + part.name.lowercase()
}

/** What the segments of one request are made of, computed once per request (see [GrantLayouts]). */
@Immutable
internal class GrantLayout(
    val requested: RequestedPermissionsView?,
    val entries: List<GrantEntry>,
    val segments: List<ApprovalSegment>,
    /** A path's canonical state index (M1): its first occurrence in the list. */
    val firstRead: Map<String, Int>,
    val firstWrite: Map<String, Int>,
)

internal fun grantLayout(view: ApprovalView): GrantLayout {
    val requested = view.requested
    val rows = requested?.let(::grantRows)
    val entries = if (requested != null && rows != null) grantEntries(rows, requested.read, requested.write) else emptyList()
    val firstRead = HashMap<String, Int>().also { m -> requested?.read?.forEachIndexed { i, p -> m.putIfAbsent(p, i) } }
    val firstWrite = HashMap<String, Int>().also { m -> requested?.write?.forEachIndexed { i, p -> m.putIfAbsent(p, i) } }
    val segments = buildList {
        add(ApprovalSegment(ApprovalPart.Head, "head"))
        entries.forEachIndexed { i, e -> add(ApprovalSegment(ApprovalPart.Entry, "e/${e.key}", e, gapBefore = e.piece == 0 && i > 0)) }
        add(ApprovalSegment(ApprovalPart.Tail, "tail"))
    }
    return GrantLayout(requested, entries, segments, firstRead, firstWrite)
}

/**
 * The layout cache: keyed by the request OBJECT (the reducer keeps an untouched request's identity), so the
 * display escaping of up to 128 paths of 4096 code points runs once per request (the derivation warms it off
 * the main thread), not per recomposition or per segment.
 */
internal object GrantLayouts {
    private const val SIZE = 16
    private val cache = object : LinkedHashMap<Any, GrantLayout>(SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, GrantLayout>?): Boolean = size > SIZE
    }

    private class Key(val request: JsObj) {
        override fun equals(other: Any?): Boolean = other is Key && other.request === request
        override fun hashCode(): Int = System.identityHashCode(request)
    }

    fun of(view: ApprovalView): GrantLayout {
        val key = Key(view.request)
        synchronized(cache) { cache[key]?.takeIf { it.requested == view.requested }?.let { return it } }
        val built = grantLayout(view)
        synchronized(cache) { cache[key] = built }
        return built
    }
}

/**
 * ta-4za3: a card's local, never-saved state, shared by all its segments: the web's "exact" confirmation tick,
 * the send latch (closes the double-tap window; "sent" itself comes from the client's ledger) and the overlay
 * notice. The host list holds one per card in an [ApprovalLocals], keyed by [ApprovalView.contentFp], so a segment
 * scrolling out of composition clears nothing; the holder is the host's own composition (a layout switch or a
 * server switch builds a new one, as it always did), so the state is never saved and after process death the
 * operator may tap again.
 */
@Stable
internal class ApprovalLocal {
    var confirmed by mutableStateOf(false)
    var latched by mutableStateOf(false)
    var overlayBlocked by mutableStateOf(false)

    private var fpValue: String? = null
    private var fpOrigin: String? = null
    private var fpTurn: String? = null
    private var fpRequest: JsObj? = null

    /** The origin-bound fingerprint the client checks, computed once per (origin, turn, request). */
    fun fingerprint(origin: String?, activeTurnId: String, request: JsObj): String = synchronized(this) {
        val have = fpValue
        if (have != null && fpOrigin == origin && fpTurn == activeTurnId && (fpRequest === request || fpRequest == request)) return have
        wireFingerprint(origin, activeTurnId, request).also { fpValue = it; fpOrigin = origin; fpTurn = activeTurnId; fpRequest = request }
    }
}

/** The [ApprovalLocal] of each card a host list draws, by contentFp (newest use last; the oldest go past 64). */
@Stable
internal class ApprovalLocals {
    private val held = LinkedHashMap<String, ApprovalLocal>(16, 0.75f, true)

    fun of(contentFp: String): ApprovalLocal = synchronized(held) {
        val local = held.getOrPut(contentFp) { ApprovalLocal() }
        while (held.size > CardStateStore.MAX_RECORDS) held.remove(held.keys.first())
        local
    }
}

internal val LocalApprovalLocals = compositionLocalOf<ApprovalLocals?> { null }

/** What a host list provides around its lazy list: the card store and one [ApprovalLocals], so every segment of a card shares both. */
@Composable
internal fun ProvideApprovalState(content: @Composable () -> Unit) {
    val store = rememberCardStates()
    val locals = remember(store) { ApprovalLocals() }
    androidx.compose.runtime.CompositionLocalProvider(LocalCardStates provides store, LocalApprovalLocals provides locals, content = content)
}

/** Everything a segment reads and does for its card; built per composition from the shared holders. */
@Stable
internal class ApprovalController(
    val view: ApprovalView,
    val layout: GrantLayout,
    val store: CardStateStore,
    val local: ApprovalLocal,
    val consent: ConsentActions,
) {
    val id = view.requestId
    val cfp = view.contentFp
    val requested = view.requested
    val readList = requested?.read.orEmpty()
    val writeList = requested?.write.orEmpty()
    val selection: GrantSelection = store.grant(cfp)
    val fp = local.fingerprint(consent.origin, view.activeTurnId, view.request)

    // L3: "sent" comes from the client's ledger; the latch only closes the double-tap window.
    val sent = local.latched || consent.isDecided(id, fp)
    val lock = consent.lock
    // ta-coik.13: answerable on the first tap, as on the web (chat-view.tsx 90fbb9f :1291-1326, no
    // arm delay); a press across a change of request is dropped ([StaleTapGuard] on [cfp]).
    val armed = !sent && lock == null
    val frozen = !armed
    val blocked: () -> Unit = { local.overlayBlocked = true }

    // M1: a path's state is its CANONICAL index (its first occurrence): a path listed twice is one permission.
    private fun off(read: Boolean, path: String, now: GrantSelection): Boolean =
        if (read) layout.firstRead[path] in now.offRead else layout.firstWrite[path] in now.offWrite

    fun ticked(read: Boolean, path: String): Boolean = !off(read, path, selection)

    val readPaths: List<String> by lazy(LazyThreadSafetyMode.NONE) { readList.filter { !off(true, it, selection) }.distinct() }
    val writePaths: List<String> by lazy(LazyThreadSafetyMode.NONE) { writeList.filter { !off(false, it, selection) }.distinct() }
    val network: Boolean get() = requested?.network == true && !selection.networkOff
    val subset: GrantedPermissions? by lazy(LazyThreadSafetyMode.NONE) { subsetGrant(readPaths, writePaths, network) }

    fun send(choiceId: String?, decision: String?, granted: GrantedPermissions?) {
        // One decision per card: a second tap (or a tap after a lock) never reaches the client.
        if (local.latched || !armed || consent.lock != null || consent.isDecided(id, fp)) return
        local.latched = true
        if (!consent.onApproval(id, fp, choiceId, decision, granted).settles()) local.latched = false
    }

    /**
     * F1: a provider choice, decided from the store AS IT IS NOW (never the values captured when the
     * key was drawn): "exact" needs its tick (the web's), "subset" grants exactly the ticks the store
     * holds at this moment.
     */
    fun choose(choice: ApprovalChoiceView) {
        if (choice.permissionGrant == null) return send(choice.choiceId, null, null)
        val live = store.grant(cfp)
        val liveRead = readList.filter { !off(true, it, live) }.distinct()
        val liveWrite = writeList.filter { !off(false, it, live) }.distinct()
        val liveNetwork = requested?.network == true && !live.networkOff
        val pick = pickFor(view, choice, confirmed = local.confirmed, subset = subsetGrant(liveRead, liveWrite, liveNetwork)) ?: return
        send(pick.choiceId, null, pick.granted)
    }

    fun toggle(read: Boolean, path: String) {
        val index = (if (read) layout.firstRead[path] else layout.firstWrite[path]) ?: return
        val now = store.grant(cfp)
        store.setGrant(
            cfp,
            if (read) now.copy(offRead = if (index in now.offRead) now.offRead - index else now.offRead + index)
            else now.copy(offWrite = if (index in now.offWrite) now.offWrite - index else now.offWrite + index),
        )
    }

    fun toggleNetwork() = store.setGrant(cfp, store.grant(cfp).let { it.copy(networkOff = !it.networkOff) })
}

@Composable
private fun rememberApprovalController(view: ApprovalView, layout: GrantLayout): ApprovalController {
    val consent = LocalConsent.current
    // The ticks live in the shell's CardStateStore under [ApprovalView.contentFp] (session + turn + request, no
    // origin); they survive a scroll, a tab switch, a layout switch, a drop and reconnect, backgrounding; a
    // re-raised request is another identity and starts fully ticked.
    val store = rememberCardStates()
    val locals = LocalApprovalLocals.current ?: error("an approval segment is drawn inside ProvideApprovalState (its host list)")
    val local = locals.of(view.contentFp)
    return ApprovalController(view, layout, store, local, consent)
}

/**
 * One segment of the card [view]: the host list emits [GrantLayouts.of]`(view).segments` as consecutive items
 * and draws each through this (every interactive one under its own [StaleTapGuard] on the card's identity).
 */
@Composable
internal fun ApprovalSegmentView(view: ApprovalView, segment: ApprovalSegment, modifier: Modifier = Modifier) {
    val layout = GrantLayouts.of(view)
    val c = rememberApprovalController(view, layout)
    StaleTapGuard(c.cfp) { _ ->
        when (segment.part) {
            ApprovalPart.Head -> ApprovalHead(c, modifier)
            ApprovalPart.Entry -> ApprovalEntry(c, segment, modifier)
            ApprovalPart.Tail -> ApprovalTail(c, modifier)
        }
    }
}

/** The whole card as one column (previews and tests; the hosts emit the segments as list items instead). */
@Composable
internal fun ApprovalCard(view: ApprovalView, modifier: Modifier = Modifier) {
    val layout = GrantLayouts.of(view)
    ProvideApprovalState { Column(modifier.fillMaxWidth()) { layout.segments.forEach { ApprovalSegmentView(view, it) } } }
}

private val CARD_PADDING = 20.dp

/**
 * `.chat-approval` for one segment: the card's fill and 1dp `--attention-border`, the corners rounded only
 * where the card really starts or ends. The outline is drawn taller than the segment on a side that continues
 * and clipped to the segment, so the side strokes run on and no horizontal stroke shows at a seam.
 */
@Composable
private fun CardSegment(top: Boolean, bottom: Boolean, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTetherTokens.current
    Column(
        modifier
            .fillMaxWidth()
            .framed(top, bottom, 14.dp, t.attentionBorder, fill = t.attentionBg)
            .padding(start = CARD_PADDING, end = CARD_PADDING, top = if (top) CARD_PADDING else 0.dp, bottom = if (bottom) CARD_PADDING else 0.dp),
        content = content,
    )
}

/**
 * A rounded outline (and optional fill) of which only the sides [top] / [bottom] close; see [CardSegment]. It is the
 * same [cssSurface] the whole card used, over a shape taller than the segment on a continuing side.
 */
private fun Modifier.framed(top: Boolean, bottom: Boolean, radius: Dp, stroke: Color, width: Dp = 1.dp, fill: Color? = null): Modifier {
    val shape = object : androidx.compose.ui.graphics.Shape {
        override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
            val r = with(density) { radius.toPx() }
            val over = 2f * r + with(density) { width.toPx() }
            val y0 = if (top) 0f else -over
            val y1 = size.height + if (bottom) 0f else over
            return androidx.compose.ui.graphics.Outline.Rounded(RoundRect(0f, y0, size.width, y1, CornerRadius(r)))
        }
    }
    return clipToBounds().cssSurface(shape, background = fill ?: Color.Transparent, border = CssBorder(width, stroke), shadows = emptyList())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApprovalHead(c: ApprovalController, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val view = c.view
    CardSegment(top = true, bottom = false, modifier = modifier.semantics { paneTitle = "Tool approval required" }.testTag("approval-card")) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
                Icon(TetherIcons.TriangleAlert, contentDescription = null, tint = t.attentionInk, modifier = Modifier.size(15.dp))
                Text(
                    "Approval needed",
                    style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.98f), fontWeight = FontWeight(700), letterSpacing = (-0.01).em),
                    color = t.white,
                    modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                )
            }
            Text(
                buildAnnotatedString {
                    append("The agent wants to run ")
                    // ta-28i: the tool's name is code (every bidi / invisible code point a token).
                    val tokens = tokenStyle(t)
                    withStyle(SpanStyle(fontFamily = type.mono, background = t.tintMd)) {
                        append(" ")
                        appendSafe(displayName(view.name), SafeText.Rule.Line, tokens)
                        append(" ")
                    }
                    append(".")
                },
                style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.85f)),
                color = t.ink,
                modifier = Modifier.padding(vertical = pMargin(0.85f)),
            )
            // Round 7: server text on the card goes through the same display escaping as the paths.
            view.reason?.let { ContextLine(null, displayText(it)) }
            // The directory is shown whole like the web's (no server length limit): escaped, then drawn in pieces
            // of at most GRANT_CHUNK_CHARS so a huge one stays composable; one piece is the line it always was.
            view.cwd?.let { cwd -> ContextLine("Working directory", displayPathChunks(cwd)) }
            view.network?.let { ContextLine("Network", displayText(it)) }
            if (view.input != null) {
                Column(Modifier.fillMaxWidth()) { ToolInputView(view.name, view.input) }
            }
            if (c.requested != null) GrantFieldsetTop()
        }
    }
}

/** One path row (or a later piece of a long path) inside the fieldset's side strokes. */
@Composable
private fun ApprovalEntry(c: ApprovalController, segment: ApprovalSegment, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val e = checkNotNull(segment.entry)
    CardSegment(top = false, bottom = false, modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .framed(top = false, bottom = false, radius = t.radiusSm, stroke = t.line)
                .padding(start = t.css.spaceSm, end = t.css.spaceSm, top = if (segment.gapBefore) t.css.spaceXs else 0.dp),
        ) {
            when {
                e.piece > 0 -> GrantPathPiece(e.text)
                e.read -> GrantCheckbox(
                    checked = c.ticked(read = true, path = e.path),
                    enabled = !c.frozen && c.view.allowsSubset,
                    onChange = { c.toggle(read = true, path = e.path) },
                    tag = "grant-read",
                    onBlocked = c.blocked,
                ) { GrantPathText("Read", e.text) }
                else -> GrantCheckbox(
                    checked = c.ticked(read = false, path = e.path),
                    enabled = !c.frozen && c.view.allowsSubset,
                    onChange = { c.toggle(read = false, path = e.path) },
                    tag = "grant-write",
                    onBlocked = c.blocked,
                ) { GrantPathText("Write", e.text) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApprovalTail(c: ApprovalController, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val view = c.view
    val requested = c.requested
    val lock = c.lock
    val sent = c.sent
    val id = c.id
    CardSegment(top = false, bottom = true, modifier = modifier) {
        // No fieldset: the card's own `space-sm` gap after the last line of the head.
        Column(
            Modifier.fillMaxWidth().padding(top = if (requested == null) t.css.spaceSm else 0.dp),
            verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            if (requested != null) {
                GrantFieldsetBottom(gapAbove = c.layout.entries.isNotEmpty(), hasRows = requested.network || view.needsConfirm) {
                    if (requested.network) {
                        GrantCheckbox(
                            checked = c.network,
                            enabled = !c.frozen && view.allowsSubset,
                            onChange = { c.toggleNetwork() },
                            tag = "grant-network",
                            onBlocked = c.blocked,
                        ) {
                            Text("Network access", style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.8f)), color = t.muted)
                        }
                    }
                    if (view.needsConfirm) {
                        Box(Modifier.fillMaxWidth().padding(top = t.css.spaceXs).topRule(t.line)) {
                            GrantCheckbox(
                                checked = c.local.confirmed,
                                enabled = !c.frozen,
                                onChange = { c.local.confirmed = !c.local.confirmed },
                                tag = "grant-confirm",
                                onBlocked = c.blocked,
                            ) {
                                Text(
                                    EXACT_CONFIRM_COPY,
                                    style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.8f)),
                                    color = t.warning,
                                )
                            }
                        }
                    }
                }
            }

            when {
                lock != null && !sent -> StatusLine(lock.copy, t.muted, "consent-lock")
                c.local.overlayBlocked && !sent -> StatusLine(OVERLAY_COPY, t.ink, "consent-overlay")
                sent && c.consent.isUnconfirmed(id, c.fp) -> StatusLine(UNCONFIRMED_COPY, t.muted, "consent-unconfirmed")
                sent -> StatusLine("Decision sent. Waiting for the agent.", t.muted, "consent-sent")
            }

            FlowRow(
                Modifier.fillMaxWidth().padding(top = t.css.spaceXs),
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
                verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            ) {
                if (view.choices.isNotEmpty()) {
                    view.choices.forEach { choice ->
                        val pick = pickFor(view, choice, c.local.confirmed, c.subset)
                        TetherKey(
                            // The captured [pick] only draws the key; the tap re-reads the store (F1).
                            onClick = { if (pick != null) c.choose(choice) },
                            classes = if (choice.permissionGrant != null) KeyClasses.ButtonPrimary else KeyClasses.ButtonSecondary,
                            label = choice.label,
                            icon = if (choice.permissionGrant != null) TetherIcons.Check else TetherIcons.Ban,
                            enabled = c.armed && pick != null,
                            // The web's `title` hover text; spoken with the label here.
                            contentDescription = choice.description?.let { "${choice.label}. $it" },
                            modifier = Modifier.refuseObscuredTouches(c.blocked).testTag("approval-choice"),
                        )
                    }
                } else {
                    TetherKey(
                        onClick = { c.send(null, "allow", null) },
                        classes = KeyClasses.ButtonPrimary,
                        label = "Approve",
                        icon = TetherIcons.Check,
                        enabled = c.armed,
                        modifier = Modifier.refuseObscuredTouches(c.blocked).testTag("approval-allow"),
                    )
                    TetherKey(
                        onClick = { c.send(null, "deny", null) },
                        classes = KeyClasses.ApprovalDeny,
                        label = "Deny",
                        icon = TetherIcons.Ban,
                        enabled = c.armed,
                        modifier = Modifier.refuseObscuredTouches(c.blocked).testTag("approval-deny"),
                    )
                }
            }
        }
    }
}


/** `.chat-approval-reason` / `.chat-approval-context`: muted 0.8rem/1.5, the value in ink mono 0.76rem. */
@Composable
private fun ContextLine(label: String?, value: String) = ContextLine(label, listOf(value))

/** [values] are drawn one [Text] each (the first carries the [label]); a single value is the one-line form. */
@Composable
private fun ContextLine(label: String?, values: List<String>) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.8f), lineHeight = rem(0.8f) * 1.5f)
    Column(Modifier.fillMaxWidth()) { values.forEachIndexed { i, value ->
        Text(
            if (label == null) {
                buildAnnotatedString { append(value) }
            } else {
                buildAnnotatedString {
                    if (i == 0) append("$label · ")
                    withStyle(SpanStyle(fontFamily = type.mono, fontSize = rem(0.76f), color = t.ink)) { append(value.breakAnywhere()) }
                }
            },
            style = style,
            color = t.muted,
            modifier = if (label == null) Modifier else Modifier.testTag("approval-context-piece"),
        )
    } }
}

/**
 * `.chat-permission-grants`, top edge: a `--line` fieldset (`--radius-sm`, `space-sm` padding) whose legend,
 * "Requested permission expansion" (ink 0.78rem/650), sits on its top edge. The rows follow as the host's items.
 */
@Composable
private fun GrantFieldsetTop() {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val legendSize = rem(0.78f)
    Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .framed(top = true, bottom = false, radius = t.radiusSm, stroke = t.line)
                .padding(start = t.css.spaceSm, end = t.css.spaceSm, top = t.css.spaceSm + 4.dp),
        )
        Text(
            "Requested permission expansion",
            style = TextStyle(fontFamily = type.body.fontFamily, fontSize = legendSize, fontWeight = FontWeight(650)),
            color = t.ink,
            modifier = Modifier
                // The legend takes no height of its own: the fieldset's padding sets where the rows start.
                .layout { m, c -> m.measure(c).let { pl -> layout(pl.width, 0) { pl.place(0, 0) } } }
                .offset(x = t.css.spaceSm, y = (-8).dp)
                .background(t.attentionBg)
                .padding(horizontal = t.css.spaceXs)
                .semantics { heading() },
        )
    }
}

/** The fieldset's bottom edge around the network row and the confirmation ([gapAbove]: a path row precedes, `space-xs` gaps). */
@Composable
private fun GrantFieldsetBottom(gapAbove: Boolean, hasRows: Boolean, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    Box(
        Modifier
            .fillMaxWidth()
            .framed(top = false, bottom = true, radius = t.radiusSm, stroke = t.line)
            .padding(start = t.css.spaceSm, end = t.css.spaceSm, bottom = t.css.spaceSm),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = if (gapAbove && hasRows) t.css.spaceXs else 0.dp),
            verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
        ) { content() }
    }
}

/** One lazy-list entry: a path's first piece (with its checkbox) or a later piece of a long path. */
internal data class GrantEntry(val read: Boolean, val path: String, val piece: Int, val text: String, val key: String)

/** The flat lazy-list entries for [rows]: reads then writes, a path's pieces consecutive. */
internal fun grantEntries(rows: GrantRows, readList: List<String>, writeList: List<String>): List<GrantEntry> = buildList {
    rows.read.forEachIndexed { i, pieces -> pieces.forEachIndexed { p, text -> add(GrantEntry(true, readList[i], p, text, "read:$i:$p")) } }
    rows.write.forEachIndexed { i, pieces -> pieces.forEachIndexed { p, text -> add(GrantEntry(false, writeList[i], p, text, "write:$i:$p")) } }
}

@Composable
private fun GrantPathText(verb: String, shown: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val wrapped = remember(shown) { shown.breakAnywhere() }
    Text(
        buildAnnotatedString {
            append("$verb ")
            // L-3: escaped, quoted and isolated for display ([displayPathChunks]); the grant carries the raw path.
            withStyle(SpanStyle(fontFamily = type.mono, fontSize = rem(0.76f), color = t.ink)) { append(wrapped) }
        },
        style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.8f)),
        color = t.muted,
    )
}

/** A later piece of a long path: the same mono text, indented under the row's label (past the 16dp box and its gap). */
@Composable
private fun GrantPathPiece(shown: String) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val wrapped = remember(shown) { shown.breakAnywhere() }
    Text(
        buildAnnotatedString { withStyle(SpanStyle(fontFamily = type.mono, fontSize = rem(0.76f), color = t.ink)) { append(wrapped) } },
        style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.8f)),
        color = t.muted,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp + t.css.spaceSm).testTag("grant-path-piece"),
    )
}

/**
 * A `<label><input type="checkbox">…</label>` row: at least 2.75rem (44dp) tall, `space-sm` gap.
 * The box is drawn with tokens (`--line-strong` edge, `--accent` fill + check when ticked); the whole
 * row is the toggle, announced as a checkbox with its words.
 */
@Composable
private fun GrantCheckbox(checked: Boolean, enabled: Boolean, onChange: () -> Unit, tag: String, onBlocked: () -> Unit = {}, label: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TetherDimens.touchTargetDp)
            .refuseObscuredTouches(onBlocked)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onChange() })
            .alpha(if (enabled) 1f else 0.65f)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        val box = RoundedCornerShape(3.dp)
        Box(
            Modifier
                .size(16.dp)
                .background(if (checked) t.accent else t.mineralDeep, box)
                .border(1.dp, if (checked) t.accentSide else t.lineStrong, box),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(TetherIcons.Check, contentDescription = null, tint = t.accentInk, modifier = Modifier.size(12.dp))
        }
        // ta-coik.22: a control in a selectable card: its words never join a selection.
        Box(Modifier.weight(1f)) { androidx.compose.foundation.text.selection.DisableSelection { label() } }
    }
}

// --- QuestionCard -----------------------------------------------------------------------------------

/**
 * `.chat-question`: `--question-bg` under `--question-border`, `space-md` padding and gaps, the seam
 * lip + raised shadow (Studio: flat, 0.875rem, 1.25rem). One question per page (issue #160): Next
 * once the page is answered, Skip to leave it out; the last page's primary key submits the whole
 * request in one `question` message.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuestionCard(view: QuestionRequestView, answered: Boolean, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val consent = LocalConsent.current
    // As the approval card: the page, picks, "Other" text and skips live in the shell's
    // CardStateStore under [QuestionRequestView.contentFp], by answer SLOT and by index into the
    // slot's label union (never the server's text, L3; a pick is a LABEL whichever page it was made
    // on, L1); only the send latch is local and unsaved.
    val id = view.requestId
    val cfp = view.contentFp
    val store = rememberCardStates()
    val sel = store.question(cfp)
    val fp = remember(view.request, view.activeTurnId, consent.origin) { wireFingerprint(consent.origin, view.activeTurnId, view.request) }
    var latched by remember(cfp) { mutableStateOf(false) }
    var overlayBlocked by remember(cfp) { mutableStateOf(false) }
    val blocked = { overlayBlocked = true }
    // The web keys picks by question TEXT: prompts repeating a text share a slot, and a slot's
    // options are the union of their labels (ConsentGuard.questionSlots, the builder's own map).
    val slots = remember(view.request) { com.tether.app.client.ConsentGuard.questionSlots(view.request) }
    fun slotOf(q: QuestionPromptView): Int = slots.slotOf.getOrElse(q.index) { q.index }
    fun labelIndex(q: QuestionPromptView, label: String): Int = slots.labels[slotOf(q)].orEmpty().indexOf(label)
    fun answeredIn(state: QuestionSelection, q: QuestionPromptView) =
        state.picks[slotOf(q)].orEmpty().isNotEmpty() || jsTrim(state.other[slotOf(q)].orEmpty()).isNotEmpty()
    fun isAnswered(q: QuestionPromptView) = answeredIn(sel, q)
    val submitAttempted = sel.attempted
    val pageIndex = sel.page.coerceIn(0, (view.prompts.size - 1).coerceAtLeast(0))
    fun update(change: (QuestionSelection) -> QuestionSelection) = store.setQuestion(cfp, change(store.question(cfp)))

    val sent = latched || consent.isDecided(id, fp)
    // Not answerable here, in words: the session's lock, the web's legacy-OpenCode reason, or an
    // answer already on record (question_answered landed before question_resolved).
    val unavailable: String? = when {
        sent -> null
        consent.lock != null -> consent.lock.copy
        consent.questionUnavailable != null -> consent.questionUnavailable
        answered -> "Already answered."
        else -> null
    }
    val total = view.prompts.size
    val question = view.prompts.getOrNull(pageIndex)
    val isLastPage = pageIndex >= total - 1
    val allAnswered = view.prompts.all { slotOf(it) in sel.skipped || isAnswered(it) }

    // ta-coik.13: answerable on the first tap, as on the web (chat-view.tsx 90fbb9f :1073-1120, no
    // arm delay); a press that began on another page or request is dropped ([StaleTapGuard] below).
    val armed = !sent && unavailable == null

    // F1: every key reads the store AT TAP TIME (never the values captured when it was drawn), and a
    // tap aimed at a page that is no longer the store's page (two taps in one frame) does nothing.
    fun onThisPage(): Boolean = store.question(cfp).page.coerceIn(0, (view.prompts.size - 1).coerceAtLeast(0)) == pageIndex

    // Round 6 (L-A): a key decides only on the selection that was DRAWN. If anything changed in the
    // store since this card was composed (an option or Other text in the same frame, a second
    // finger), the tap is refused: the operator sees the change first and taps again.
    fun isDrawn(): Boolean = store.question(cfp) == sel

    fun submit(extraSkipped: Int? = null, drawnChecked: Boolean = false) {
        if (latched || !armed || unavailable != null || consent.isDecided(id, fp) || !onThisPage()) return
        if (!drawnChecked && !isDrawn()) return
        update { it.copy(attempted = true) }
        val now = store.question(cfp)
        val effectiveSkipped = now.skipped + listOfNotNull(extraSkipped)
        if (!view.prompts.all { slotOf(it) in effectiveSkipped || answeredIn(now, it) }) return
        // L2: indices and the operator's own text; the guard builds the answer strings from the request.
        val picks = (now.picks.keys + now.other.keys).distinct().sorted().map { slot ->
            com.tether.app.client.ConsentGuard.QuestionPick(slot, now.picks[slot].orEmpty(), now.other[slot].orEmpty())
        }
        latched = true
        if (!consent.onAnswer(id, fp, picks, effectiveSkipped).settles()) latched = false
    }

    fun next() {
        if (!armed || question == null || !onThisPage() || !isDrawn() || !answeredIn(store.question(cfp), question)) return
        update { it.copy(page = pageIndex + 1) }
    }

    fun skip() {
        if (!armed || question == null || !onThisPage() || !isDrawn()) return
        update { it.copy(skipped = it.skipped + slotOf(question)) }
        // The drawn check was made above, before this skip's own write.
        if (isLastPage) submit(extraSkipped = slotOf(question), drawnChecked = true) else update { it.copy(page = pageIndex + 1) }
    }

    val frozen = !armed
    StaleTapGuard(cfp to pageIndex) { _ ->
        Column(
            modifier
                .fillMaxWidth()
                .cssSurface(
                    cardShape(t),
                    background = t.questionBg,
                    border = CssBorder(1.dp, t.questionBorder),
                    shadows = emptyList(),
                )
                .padding(20.dp)
                .semantics { paneTitle = "The agent is asking a question" }
                .testTag("question-card"),
            verticalArrangement = Arrangement.spacedBy(t.css.spaceMd),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
                Icon(TetherIcons.CircleHelp, contentDescription = null, tint = t.questionInk, modifier = Modifier.size(15.dp))
                Text(
                    "The agent needs your input",
                    style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.92f), fontWeight = FontWeight(700)),
                    color = t.white,
                    modifier = Modifier.weight(1f).semantics { heading(); liveRegion = LiveRegionMode.Polite },
                )
                if (total > 1) {
                    // `.chat-question-page { margin-left: auto }`: pushed to the row's end.
                    Text(
                        "Question ${pageIndex + 1} of $total",
                        style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.72f), fontWeight = FontWeight(500)),
                        color = t.muted,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("question-page"),
                    )
                }
            }
            if (question != null) {
                val highlight = submitAttempted && !isAnswered(question) && slotOf(question) !in sel.skipped
                // `.is-unanswered` pads the page in; its `--attention` rule names a token no skin defines,
                // so (as on the web) no rule is drawn — the validation sentence below carries it.
                Column(
                    Modifier.fillMaxWidth().padding(start = if (highlight) t.css.spaceSm else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
                ) {
                    // ta-28i: the agent's question, header and options are prose (SafeText), as on the answered card.
                    question.header?.let {
                        Text(
                            proseText(cut4k(it).uppercase()),
                            style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.72f), letterSpacing = 0.06.em),
                            color = t.muted,
                            modifier = Modifier.padding(vertical = pMargin(0.72f)).originalWords(proseText(cut4k(it)).text), // server text: the SafeText-drawn words, never the raw string
                        )
                    }
                    Text(
                        proseText(cut4k(question.question)),
                        style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.9f)),
                        color = t.ink,
                        modifier = Modifier.padding(vertical = pMargin(0.9f)),
                    )
                    Column(Modifier.fillMaxWidth().padding(top = t.css.spaceXs), verticalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
                        val slot = slotOf(question)
                        val multi = slots.single[slot] != true
                        question.options.forEach { option ->
                            val labelAt = labelIndex(question, option.label)
                            QuestionOption(
                                option = option,
                                active = labelAt in sel.picks[slot].orEmpty(),
                                multi = multi,
                                enabled = !frozen,
                                // L-A: an option of a page that is no longer shown (a second finger after Next) is ignored.
                                onToggle = {
                                    if (onThisPage()) update { it.copy(picks = it.picks + (slot to togglePick(it.picks[slot].orEmpty(), multi, labelAt))) }
                                },
                                onBlocked = blocked,
                            )
                        }
                    }
                    TetherInputWell(
                        value = sel.other[slotOf(question)].orEmpty(),
                        // An HTML text input drops line breaks; so does this one. Capped at the guard's limit.
                        onValueChange = { text ->
                            if (onThisPage()) {
                                val clean = cutCodePoints(text.replace("\r", "").replace("\n", ""), com.tether.app.client.ConsentGuard.MAX_OTHER_CHARS)
                                update { it.copy(other = it.other + (slotOf(question) to clean)) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = t.css.spaceXs).testTag("question-other"),
                        placeholder = "Other (type your own answer)…",
                        singleLine = true,
                        enabled = !frozen,
                    )
                    if (slots.single[slotOf(question)] == false) {
                        Text("Select all that apply.", style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.74f)), color = t.muted)
                    }
                }
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = t.css.spaceXs),
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    unavailable != null -> Box(Modifier.fillMaxWidth()) { StatusLine(unavailable, t.ink, "consent-lock") }
                    overlayBlocked && !sent -> Box(Modifier.fillMaxWidth()) { StatusLine(OVERLAY_COPY, t.ink, "consent-overlay") }
                    sent && consent.isUnconfirmed(id, fp) -> Box(Modifier.fillMaxWidth()) { StatusLine(UNCONFIRMED_COPY, t.muted, "consent-unconfirmed") }
                    sent -> Box(Modifier.fillMaxWidth()) { StatusLine("Answer sent. Waiting for the agent.", t.muted, "consent-sent") }
                    submitAttempted && !allAnswered -> Box(Modifier.fillMaxWidth().padding(vertical = pMargin(0.78f))) {
                        StatusLine("Answer each highlighted question, or choose Skip to leave it unanswered.", t.ink, "question-validation")
                    }
                }
                if (unavailable == null && question != null) {
                    Box(
                        Modifier
                            .heightIn(min = TetherDimens.touchTargetDp)
                            .refuseObscuredTouches(blocked)
                            .clickable(enabled = armed, role = Role.Button, onClick = ::skip)
                            .alpha(if (armed) 1f else 0.65f)
                            .padding(horizontal = t.css.spaceSm)
                            .testTag("question-skip"),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.text.selection.DisableSelection {
                            Text("Skip", style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.78f), fontWeight = FontWeight(560)), color = t.muted)
                        }
                    }
                }
                if (isLastPage || unavailable != null) {
                    TetherKey(
                        onClick = { submit() },
                        classes = KeyClasses.ButtonPrimary,
                        label = when {
                            unavailable != null -> "Answer unavailable"
                            sent -> "Answer sent"
                            else -> "Submit answer"
                        },
                        icon = TetherIcons.Check,
                        enabled = armed,
                        modifier = Modifier.refuseObscuredTouches(blocked).testTag("question-submit"),
                    )
                } else {
                    TetherKey(
                        onClick = ::next,
                        classes = KeyClasses.ButtonPrimary,
                        label = "Next",
                        enabled = armed && question != null && isAnswered(question),
                        modifier = Modifier.refuseObscuredTouches(blocked).testTag("question-next"),
                    )
                }
            }
        }
    }
}

/**
 * The card's display bound. ta-28i: cut at a character-cluster boundary ([TextCut]: never inside a
 * surrogate pair or a combining sequence), and always on the SOURCE, before the prose rule draws it,
 * so a token is never cut in half. r2: a single cluster longer than the bound (a flood of marks) is
 * cut at a code point instead of showing only "…" ([TextCut]'s bounded back-off).
 */
internal fun cut4k(s: String): String = if (s.length > CARD_TEXT_MAX) com.tether.app.client.TextCut.cut(s, CARD_TEXT_MAX) + "…" else s

/**
 * `.chat-question-option` on the material layer: a key face (`--key-face` on `--key-side`, the lit
 * top and left edges, `--shadow-key`), `space-sm space-md`, `--radius-sm`; selected: `--violet-wash`
 * on `--violet-strong`, pressed bevel; disabled: flat on `--line-strong`, muted, 0.65 opacity.
 * The label is provider content (never uppercased).
 */
@Composable
private fun QuestionOption(option: QuestionOptionView, active: Boolean, multi: Boolean, enabled: Boolean, onToggle: () -> Unit, onBlocked: () -> Unit = {}) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusSm)
    val border = when {
        active -> t.violetStrong
        !enabled -> t.lineStrong
        else -> t.keySide
    }
    // The rest / active bevels (--lit-*, --shadow-key, --bevel-pressed) were retired at tether
    // 887c222; all were transparent in Studio, so only the disabled side-wall remains.
    val shadows = if (!enabled && !active) listOf(hardShadow(1.dp, t.keySide)) else emptyList()
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TetherDimens.touchTargetDp)
            .alpha(if (enabled) 1f else 0.65f)
            .cssSurface(shape, background = if (active) t.violetWash else t.keyFace, border = CssBorder(1.dp, border), shadows = shadows)
            .refuseObscuredTouches(onBlocked)
            .toggleable(value = active, enabled = enabled, role = if (multi) Role.Checkbox else Role.RadioButton, onValueChange = { onToggle() })
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm)
            .testTag("question-option"),
        verticalArrangement = Arrangement.spacedBy(1.6.dp),
    ) {
        // ta-coik.22: an option is a control in a selectable card: its words never join a selection.
        androidx.compose.foundation.text.selection.DisableSelection {
            Text(proseText(cut4k(option.label)), style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.86f), fontWeight = FontWeight(500)), color = if (enabled) t.white else t.muted)
            option.description?.let {
                Text(proseText(cut4k(it)), style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.78f)), color = t.muted)
            }
        }
    }
}

// --- AnsweredQuestionCard ---------------------------------------------------------------------------

/**
 * The v104 settled record in the AskUserQuestion slot: a closed fact, so a quiet neutral surface
 * (`--border` on `--tint-sm`, `space-sm` gaps, the head muted), sized to its content inside an
 * agent row. Read-only: nothing here can send.
 */
@Composable
internal fun AnsweredQuestionCard(view: AnsweredView, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .width(IntrinsicSize.Max)
                .widthIn(max = 10_000.dp)
                .cssSurface(
                    cardShape(t),
                    background = t.tintSm,
                    border = CssBorder(1.dp, t.css.border),
                    shadows = emptyList(),
                )
                .padding(20.dp)
                .semantics { contentDescription = "Your answer to the agent's question" }
                .testTag("answered-card"),
            verticalArrangement = Arrangement.spacedBy(t.css.spaceSm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
                Icon(TetherIcons.Check, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp))
                Text("You answered", style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.92f), fontWeight = FontWeight(700)), color = t.white)
            }
            view.items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // ta-blf r2: the agent's question and the operator's answer are prose (SafeText).
                    item.header?.let {
                        Text(
                            proseText(it.uppercase()),
                            style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.72f), letterSpacing = 0.06.em),
                            color = t.muted,
                            modifier = Modifier.padding(vertical = pMargin(0.72f)).originalWords(proseText(it).text), // server text: the SafeText-drawn words, never the raw string
                        )
                    }
                    Text(proseText(item.question), style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.9f)), color = t.ink, modifier = Modifier.padding(vertical = pMargin(0.9f)))
                    val value = Modifier.padding(vertical = pMargin(0.9f))
                    if (item.answer != null) {
                        Text(proseText(item.answer), style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.9f), fontWeight = FontWeight(500)), color = t.white, modifier = value)
                    } else {
                        Text("(no selection)", style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.9f), fontStyle = FontStyle.Italic), color = t.muted, modifier = value)
                    }
                }
            }
            view.response?.let {
                Text(
                    proseText(it),
                    style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.9f), fontWeight = FontWeight(500)),
                    color = t.white,
                    modifier = Modifier.padding(vertical = pMargin(0.9f)),
                )
            }
        }
    }
}

// --- PermissionDenialCard ---------------------------------------------------------------------------

/** A denied call's owner: a resolved sub-agent run (a real link to its tab), or none. */
@Immutable
internal data class RunRef(val runId: String, val title: String, val toolId: String = "")

/**
 * `.chat-denial`: already closed, so no controls and no waiting styling. `--mineral-deep` inside a
 * 1.5px `--danger-edge` frame (the material layer's colour over the base 1.5px width) and a 1px
 * `--danger-edge` ring, `--radius-md`, `space-sm space-md`. Head: the ban glyph and "denied" in
 * `--danger` mono 0.8rem, the tool name ink 600. Then who ran it, the copy, the refused target and
 * the quotable identity (reason code · tool call id).
 */
@Composable
internal fun PermissionDenialCard(denial: DenialView, target: DenialTarget?, run: RunRef?, modifier: Modifier = Modifier, nested: Boolean = false) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val consent = LocalConsent.current
    val shape = RoundedCornerShape(t.radiusMd)
    val indent = 22.4.dp // 1.4rem: under the head's text, past the glyph
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .maxWidthFraction(cardFraction(nested))
                .fillMaxWidth()
                .cssSurface(shape, background = t.mineralDeep, border = CssBorder(1.5.dp, t.dangerEdge), shadows = listOf(com.tether.app.ui.components.softShadow(0.dp, 0.dp, t.dangerEdge, spread = 1.dp)))
                .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("denial-card"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
                Icon(TetherIcons.Ban, contentDescription = null, tint = t.danger, modifier = Modifier.size(14.dp))
                // ta-blf r2: names, ids, the error and the refused target are code (SafeText), LTR.
                Text(
                    codeText(denial.name),
                    style = TextStyle(fontFamily = type.mono, fontSize = rem(0.8f), fontWeight = FontWeight(600)),
                    color = t.ink,
                    modifier = Modifier.weight(1f),
                )
                Text("DENIED", style = TextStyle(fontFamily = type.mono, fontSize = rem(0.72f), letterSpacing = 0.04.em), color = t.danger, modifier = Modifier.originalWords("denied"))
            }
            Box(Modifier.padding(start = indent, top = t.css.spaceXs)) {
                when {
                    denial.subagent && run != null -> Row(
                        Modifier
                            .heightIn(min = TetherDimens.touchTargetDp)
                            .clickable(role = Role.Button, onClickLabel = "Open “${SafeText.code(run.title)}”") { if (run.toolId.isNotEmpty()) consent.onFocusCall(run.runId, run.toolId) else consent.onOpenRun(run.runId) }
                            .testTag("denial-origin-link"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.8.dp),
                    ) {
                        Icon(TetherIcons.Bot, contentDescription = null, tint = t.danger, modifier = Modifier.size(12.dp))
                        Text(
                            codeText(run.title),
                            style = TextStyle(
                                fontFamily = type.body.fontFamily,
                                fontSize = rem(0.76f),
                                fontWeight = FontWeight(600),
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            ),
                            color = t.danger,
                        )
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.8.dp)) {
                        Icon(if (denial.subagent) TetherIcons.Bot else TetherIcons.Terminal, contentDescription = null, tint = t.faint, modifier = Modifier.size(12.dp))
                        Text(
                            if (denial.subagent) "Sub-agent (run no longer in view)" else "Main agent",
                            style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.76f)),
                            color = t.faint,
                        )
                    }
                }
            }
            Text(
                codeText(denialCopy(denial)),
                style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.8f), lineHeight = rem(0.8f) * 1.45f),
                color = t.muted,
                modifier = Modifier.padding(start = indent, top = t.css.spaceXs),
            )
            if (target != null) {
                Row(
                    Modifier.padding(start = indent, top = t.css.spaceXs),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
                ) {
                    Text(
                        target.label.uppercase(),
                        style = TextStyle(fontFamily = type.body.fontFamily, fontSize = rem(0.68f), letterSpacing = 0.04.em),
                        color = t.faint,
                        modifier = Modifier.padding(top = 2.dp).originalWords(target.label),
                    )
                    Text(
                        codeText(target.value, breakAnywhere = true),
                        style = TextStyle(fontFamily = type.mono, fontSize = rem(0.76f), lineHeight = rem(0.76f) * 1.4f, textDirection = codeDirection),
                        color = t.ink,
                        maxLines = 4,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .background(t.tintSm, RoundedCornerShape(t.radiusSm))
                            .padding(horizontal = 5.6.dp, vertical = 1.6.dp),
                    )
                }
            }
            Row(
                Modifier.padding(start = indent, top = t.css.spaceXs),
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
            ) {
                val meta = TextStyle(fontFamily = type.mono, fontSize = rem(0.72f))
                Text(codeText(denial.reasonCode ?: denial.reason), style = meta, color = t.faint)
                Text("·", style = meta, color = t.faint, modifier = Modifier.semantics { contentDescription = "" })
                Text(codeText(denial.toolId, breakAnywhere = true), style = meta.copy(textDirection = codeDirection), color = t.faint)
            }
        }
    }
}
