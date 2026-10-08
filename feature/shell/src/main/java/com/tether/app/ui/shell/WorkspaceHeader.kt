package com.tether.app.ui.shell

import androidx.compose.foundation.clickable
import com.tether.app.ui.components.softShadow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.tether.app.client.LabelText
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.text.proseDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.KeyState
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.FreshnessChip
import com.tether.app.ui.components.FreshnessCopy
import com.tether.app.ui.components.TetherStatusPill
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.hardShadow
import com.tether.app.ui.components.statusToneOf
import com.tether.app.ui.components.swallowTaps
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.CssShadow
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.util.compactPath
import com.tether.app.ui.util.statusCopy

/**
 * What the header asks of its context gauge. Below 100rem the gauge is the telemetry panel's
 * handle: [open] is its pressed state (`aria-pressed`) and [onToggle] opens / collapses the panel.
 * Where the inspector column owns the readings (≥ 100rem) both are null: the gauge is a quiet,
 * tooltip-only indicator (dashboard.tsx:74-82). [showLabel] prints "Telemetry" from 48rem.
 */
data class GaugeHost(val open: Boolean?, val onToggle: (() -> Unit)?, val showLabel: Boolean)

/** The context-gauge slot in the header's action cluster (T4.3's `ContextGauge`). */
typealias GaugeSlot = @Composable (host: GaugeHost) -> Unit

/**
 * ta-7njx (W23, ruling B1-B3): how far the expanded header's action cluster has yielded to keep End session whole in the
 * window at an Android font size above 1.0. The web never yields at 48rem and up (its cluster is `flex: 0 0 auto` and
 * overruns the edge), and its text does not grow with the Android font size, so this ladder is inert at font scale 1.0 and
 * is not a breakpoint: it keys on the one variable that makes the app's text wider than the web's. Each step is a state
 * the web itself ships below 48rem; the order gives up the least reach first.
 *
 *  * [STEP_FULL] (0): the web's desktop cluster.
 *  * [STEP_TELEMETRY_WORDLESS] (1): "Telemetry" stops printing its word (globals.css 1410-1413); the gauge stays the handle.
 *  * [STEP_PIN_IN_MENU] (2): Pin moves into the "Session links" menu (globals.css 11068-11070).
 *  * [STEP_END_WORDLESS] (3): End session stops printing its word (the brick key keeps its glyph and name).
 *
 * The header measures the cluster at each step in turn and keeps the first whose width fits [HeaderFit.room]; the menu
 * ([SessionLinksPopover]) reads [pinInMenu] from the same hoisted state.
 */
@Stable
class HeaderFit {
    var step: Int by mutableIntStateOf(STEP_FULL)
        internal set

    /** Whether the bar's Pin has moved into the "Session links" menu. */
    val pinInMenu: Boolean get() = step >= STEP_PIN_IN_MENU

    companion object {
        const val STEP_FULL = 0
        const val STEP_TELEMETRY_WORDLESS = 1
        const val STEP_PIN_IN_MENU = 2
        const val STEP_END_WORDLESS = 3
    }
}

/** The elapsed-dial slot, first in the expanded header's rail (T4.3's `SessionDial`). */
typealias DialSlot = @Composable (session: AgentSession) -> Unit

/**
 * The statusline slot inside the "Session links" popover (T4.3's `SessionStatusline`): right-aligned
 * on phones, left-aligned from 48rem ([expanded]).
 */
typealias StatuslineSlot = @Composable (expanded: Boolean) -> Unit

/** The header's host actions (workspace-header.tsx props). */
data class WorkspaceHeaderActions(
    val onRename: () -> Unit,
    val onEndSession: () -> Unit,
    val onTogglePinned: () -> Unit,
    val onCopyPath: () -> Unit,
    val onCopyTetherId: () -> Unit,
    /** workspace-header.tsx `onCopyResumeCommand` (v52): copies [AgentSession.resumeCommand]. */
    val onCopyResumeCommand: () -> Unit = {},
)

/**
 * `<header className="workspace-header">` (components/workspace-header.tsx): one identity row —
 * name, rename pencil, status pill — and the action cluster.
 *
 * Phone ([expanded] false): the context gauge ([gauge], T4.3), the "Session links" trigger and
 * End session. Below 48rem the elapsed dial is hidden (`.session-elapsed { display: none }`), the
 * pin key moves into the links popover and End session drops its word; the meta strip only lives
 * in the popover ([SessionLinksPopover]).
 *
 * Expanded (≥ 48rem, T4.2): the rail gains the dial ([dial]), the gauge prints "Telemetry", the
 * pin key returns with its word and End session prints its legend (globals.css 4019-4021,
 * 4117-4121, 11882). The title is 1.05rem / 720 / -0.022em (11157 + 11859); the bar keeps
 * `space-sm space-lg` (11858 outranks 11156 by source order) and adds the nameplate's soft shade
 * into the bay (11142-11145; the phone override 11720 drops it). Studio: 1rem × 1.75rem padding,
 * 5rem tall, 1.12rem / 740 title (studio.css 351-352).
 *
 * Material: flat `--graphite`, `1px --line`, `padding: space-sm space-md` (studio.css 351, 450).
 */
@Composable
fun WorkspaceHeader(
    session: AgentSession,
    telemetryOpen: Boolean,
    linksOpen: Boolean,
    onToggleTelemetry: () -> Unit,
    onToggleLinks: () -> Unit,
    actions: WorkspaceHeaderActions,
    modifier: Modifier = Modifier,
    gauge: GaugeSlot = { host -> TelemetryHandlePlaceholder(host.open == true, host.onToggle ?: {}) },
    expanded: Boolean = false,
    /** Expanded only: whether the gauge is the panel's handle (false where the inspector column shows). */
    gaugeIsHandle: Boolean = true,
    dial: DialSlot? = null,
    /** T9.2 (workspace-header.tsx:113): the DeepSeek peak badge after the status pill (nothing when it does not apply). */
    badge: (@Composable () -> Unit)? = null,
    /** ta-7njx: the cluster's yield state, shared with the "Session links" menu (the shell hoists it). */
    fit: HeaderFit = remember { HeaderFit() },
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val edge = t.line
    val shadows = emptyList<CssShadow>()
    val padH = when {
        expanded -> 28.dp
        else -> t.css.spaceMd
    }
    val padV = if (expanded) 16.dp else t.css.spaceSm
    val freshness = LocalShellFreshness.current
    val sync = freshness.syncStates[session.id]
    Column(
        modifier
            .fillMaxWidth()
            .zIndex(1f) // `position: relative; z-index: 1` — the shade paints over the stage
            .then(if (expanded) Modifier.heightIn(min = 80.dp) else Modifier)
            .cssSurface(RectangleShape, t.graphite, shadows = shadows)
            .drawBehind { drawRect(edge, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
            .padding(bottom = 1.dp)
            // The side padding is the row's own (below): the action cluster may run over it, as on the web,
            // and a touch there must still reach the cluster.
            .padding(vertical = padV)
            .testTag(ShellTags.WorkspaceHeader),
    ) {
    // `.workspace-header` is a flex COLUMN: under Studio's 5rem min-height the row sits at the top.
    // `.workspace-header-main`: space-between, gap space-lg (Studio phone 0.5rem).
    // `.workspace-title-row`: gap space-md (Studio phone 0.4rem); the pencil pulls in by
    // `calc(var(--space-md) * -0.5)`.
    val titleGap = if (!expanded) 6.4.dp else t.css.spaceMd
    HeaderMainRow(
        sidePadding = padH,
        mainGap = if (!expanded) 8.dp else t.css.spaceLg,
        titleGap = titleGap,
        hasBadge = badge != null,
        yields = expanded && LocalDensity.current.fontScale > 1.0f,
        fit = fit,
        title = {
            // ta-28i: the session's title by the label rule, in its content's direction.
            SessionTitle(
                name = LabelText.title(session.name),
                color = t.white,
                style = when {
                    expanded -> cssText(type.ui, 1.12f, 740, trackingEm = -0.025f, lineHeight = 1.45f)
                    else -> cssText(type.ui, 0.925f, 740, trackingEm = -0.025f, lineHeight = 1.45f)
                }.copy(textDirection = proseDirection),
            )
        },
        rename = { RenameKey(actions.onRename) },
        pill = {
            // T13.2: from a list that is not live the pill says "Was running" on a faint still dot,
            // never the spinner or the violet waiting ping (SYNC_DESIGN §4.2). The age is the
            // freshness chip's, under the row: here it would crowd out the name at a large font.
            val (pillLabel, pillTone) = FreshnessCopy.statusPill(session.status, freshness.listLive, null, freshness.now)
            TetherStatusPill(label = pillLabel, tone = pillTone, modifier = Modifier.testTag(ShellTags.StatusPill))
        },
        badge = badge,
        cluster = { step ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
            ) {
                if (expanded && dial != null) Box(Modifier.testTag(ShellTags.Dial)) { dial(session) }
                val handle = !expanded || gaugeIsHandle
                gauge(
                    GaugeHost(
                        open = if (handle) telemetryOpen else null,
                        onToggle = if (handle) onToggleTelemetry else null,
                        showLabel = expanded && step < HeaderFit.STEP_TELEMETRY_WORDLESS,
                    ),
                )
                ChromeIconKey(
                    onClick = onToggleLinks,
                    icon = TetherIcons.Ellipsis,
                    contentDescription = "Session links",
                    stateDescription = if (linksOpen) "Open" else null,
                    look = rememberIconLook(t.ink, t.radiusSm),
                    width = 44.dp,
                    height = 44.dp,
                    iconSize = 18.dp,
                    modifier = Modifier.testTag(ShellTags.LinksKey),
                )
                if (expanded && step < HeaderFit.STEP_PIN_IN_MENU) PinKey(session.pinned, actions.onTogglePinned)
                // `.end-session`: the brick key, glyph only below 48rem; Studio's rail forces 2.75rem
                // (studio.css 362). Disabled once the session has exited, like the web. From 48rem it
                // prints "End session" at 0.66rem (4117-4121, 11196). ta-coik.22: only that, as on the web
                // (workspace-header.tsx 90fbb9f :133): live on a saved or catching-up copy too.
                val endable = session.status != "exited"
                TetherKey(
                    onClick = { if (endable) actions.onEndSession() },
                    classes = KeyClasses.EndSession,
                    label = if (expanded && step < HeaderFit.STEP_END_WORDLESS) "End session" else null,
                    icon = TetherIcons.CircleStop,
                    iconSize = 16.dp,
                    fontSize = if (expanded && step < HeaderFit.STEP_END_WORDLESS) 10.56.sp else TextUnit.Unspecified,
                    contentDescription = "End session",
                    enabled = endable,
                    minHeight = 44.dp,
                    modifier = Modifier.testTag(ShellTags.EndSessionKey),
                )
            }
        },
    )
    // T13.2: how current this session's copy is (nothing while Live).
    FreshnessChip(sync, freshness.now, Modifier.padding(start = padH, end = padH, top = t.css.spaceXs).testTag(ShellTags.FreshnessChip))
    }
}

/**
 * `.workspace-header-main` with the web's flex (ta-0af6; globals.css 1107-1149, 2635; the web at 29537e0, measured):
 * the session title is the only thing that shrinks, down to 0 (`h1 { min-width: 0; overflow: hidden }`). The pencil, the
 * status pill and the badge are `flex: 0 0 auto`, so they keep their size and run on past the title row, under the
 * action cluster (`.workspace-actions`, `flex: 0 0 auto`, intrinsic width), which is later in the DOM and draws over them.
 * The cluster starts at `max(contentLeft + gap, contentRight - clusterWidth)`; when it is wider than the room it
 * overruns the right padding (and, at large text, the window), as the web's does. Nothing is hidden, folded or moved.
 * The row spans the header's full width and applies [sidePadding] itself so that the overrun stays inside its bounds
 * (a touch there still reaches the cluster).
 */
@Composable
private fun HeaderMainRow(
    sidePadding: Dp,
    mainGap: Dp,
    titleGap: Dp,
    hasBadge: Boolean,
    yields: Boolean,
    fit: HeaderFit,
    title: @Composable () -> Unit,
    rename: @Composable () -> Unit,
    pill: @Composable () -> Unit,
    badge: (@Composable () -> Unit)?,
    cluster: @Composable (step: Int) -> Unit,
) {
    SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
        val unbounded = Constraints(0, Constraints.Infinity, 0, Constraints.Infinity)
        val padPx = sidePadding.roundToPx()
        val gapPx = mainGap.roundToPx()
        val titleGapPx = titleGap.roundToPx()
        // The pencil pulls in by half a space-md: the gap before it is titleGap - 6.
        val renameGapPx = (titleGap - 6.dp).roundToPx()
        val width = constraints.maxWidth
        val contentL = padPx
        val contentR = width - padPx
        // ta-7njx: the cluster at the first step whose width fits `room`, from its own floor to 8 dp inside the edge. Only
        // above font scale 1.0 (see [HeaderFit]); at 1.0 the web's one state, overrun and all.
        val room = width - 8.dp.roundToPx() - (contentL + gapPx)
        // The slots compose in DOM order (title, pencil, pill, badge, cluster), so reading and draw order match the web's.
        val titleM = subcompose(HeaderSlot.Title) { Box(Modifier.layoutId(HeaderSlot.Title)) { title() } }.first()
        val renameM = subcompose(HeaderSlot.Rename) { Box(Modifier.layoutId(HeaderSlot.Rename)) { rename() } }.first()
        val pillM = subcompose(HeaderSlot.Pill) { Box(Modifier.layoutId(HeaderSlot.Pill)) { pill() } }.first()
        val badgeM = if (badge != null) subcompose(HeaderSlot.Badge) { Box(Modifier.layoutId(HeaderSlot.Badge)) { badge() } }.first() else null
        // A step measured on the way to the kept one stays composed (never placed) until the next pass drops it; its
        // semantics are cleared meanwhile, so the tree only ever carries the kept step's keys.
        fun measureCluster(s: Int) = subcompose(s) {
            val kept = fit.step == s
            Box(Modifier.layoutId(HeaderSlot.Cluster).then(if (kept) Modifier else Modifier.clearAndSetSemantics {})) { cluster(s) }
        }.first().measure(unbounded)
        var step = HeaderFit.STEP_FULL
        var clusterP = measureCluster(step)
        while (yields && clusterP.width > room && step < HeaderFit.STEP_END_WORDLESS) {
            step++
            clusterP = measureCluster(step)
        }
        if (fit.step != step) fit.step = step
        val renameP = renameM.measure(unbounded)
        val pillP = pillM.measure(unbounded)
        val badgeP = badgeM?.measure(unbounded)
        val clusterX = maxOf(contentL + gapPx, contentR - clusterP.width)
        val rowW = maxOf(0, clusterX - gapPx - contentL)
        val kids = renameGapPx + renameP.width + titleGapPx + pillP.width + (if (badgeP != null) titleGapPx + badgeP.width else 0)
        val titleP = titleM.measure(Constraints(0, maxOf(0, rowW - kids), 0, Constraints.Infinity))
        // Two-level centring, as the web's nested flex rows: the title row inside the main row, its items inside it.
        val titleRowH = maxOf(titleP.height, renameP.height, pillP.height, badgeP?.height ?: 0)
        val height = maxOf(titleRowH, clusterP.height)
        val rowY = Alignment.CenterVertically.align(titleRowH, height)
        layout(width, height) {
            var x = contentL
            titleP.placeRelative(x, rowY + Alignment.CenterVertically.align(titleP.height, titleRowH))
            x += titleP.width + renameGapPx
            renameP.placeRelative(x, rowY + Alignment.CenterVertically.align(renameP.height, titleRowH))
            x += renameP.width + titleGapPx
            pillP.placeRelative(x, rowY + Alignment.CenterVertically.align(pillP.height, titleRowH))
            x += pillP.width
            if (badgeP != null) badgeP.placeRelative(x + titleGapPx, rowY + Alignment.CenterVertically.align(badgeP.height, titleRowH))
            clusterP.placeRelative(clusterX, Alignment.CenterVertically.align(clusterP.height, height))
        }
    }
}

/**
 * `h1 { overflow: hidden; text-overflow: ellipsis }` (ta-m5sy): CSS Overflow 3 never ellipses away the first character of a
 * line ("the first character or atomic inline-level element on a line must be clipped rather than ellipsed"), so a box
 * narrower than "A…" shows the first letter and a clipped "…", never a bare "…" (the web at 914 x 411 draws "A" and the
 * start of "…" in 19.75 px). Wider boxes keep the ordinary end ellipsis. The heading's text stays the full name, as the
 * h1's textContent does; the first grapheme is a grapheme cluster (a decomposed "é" or a ZWJ emoji is one), from the
 * start edge, so right-to-left names follow the same rule.
 */
@Composable
internal fun SessionTitle(name: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val available = constraints.maxWidth
        val lead = remember(name) { firstGrapheme(name) }
        val clipped = remember(name, style) { lead + "…" }
        val needsClip = available in 1 until Constraints.Infinity && lead.length < name.length &&
            measurer.measure(name, style, softWrap = false, maxLines = 1).size.width > available &&
            measurer.measure(clipped, style, softWrap = false, maxLines = 1).size.width > available
        if (needsClip) {
            Box(Modifier.semantics { heading(); text = AnnotatedString(name) }) {
                Text(
                    clipped,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    style = style,
                    modifier = Modifier.semantics { invisibleToUser() }.testTag(HeaderTitleTextTag),
                )
            }
        } else {
            Text(
                name,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = style,
                modifier = Modifier.semantics { heading() }.testTag(HeaderTitleTextTag),
            )
        }
    }
}

/** The title's drawn text (ta-m5sy), apart from its full-name heading. */
internal const val HeaderTitleTextTag = "shell-header-title-text"

/** The name's first extended grapheme cluster (UAX #29), so a combining mark or a ZWJ sequence stays whole. */
internal fun firstGrapheme(name: String): String {
    if (name.isEmpty()) return name
    val it = android.icu.text.BreakIterator.getCharacterInstance()
    it.setText(name)
    val end = it.next()
    return if (end == android.icu.text.BreakIterator.DONE) name else name.substring(0, end)
}

private enum class HeaderSlot { Title, Rename, Pill, Badge, Cluster }

/**
 * `.pin-session` in the expanded header rail (hidden below 48rem, globals.css 11882): 2.75rem,
 * `--radius-key`, `--muted` glyph + word at 0.74rem, gap `space-sm` (1930-1945, 11193); pinned
 * takes the `--violet-wash` plate and `--violet` ink (8991-8995). Studio flattens the rail's
 * controls (studio.css 364-365), so pinned keeps only the violet ink. `aria-pressed` = pinned.
 */
@Composable
private fun PinKey(pinned: Boolean, onToggle: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val ink = if (pinned) t.violet else t.muted
    val face = Color.Transparent
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            .cssSurface(RoundedCornerShape(t.radiusKey), face)
            .toggleable(value = pinned, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onToggle() }
            .semantics { contentDescription = if (pinned) "Pinned" else "Pin" }
            .testTag(ShellTags.PinKey),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm, Alignment.CenterHorizontally),
    ) {
        Icon(TetherIcons.Pin, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        Text(if (pinned) "Pinned" else "Pin", color = ink, maxLines = 1, style = cssText(type.ui, 0.74f, 400))
    }
}

/**
 * `.rename-session`: a 15px pencil in 0.28rem of padding, `--muted`, always visible on touch
 * (`@media (hover: none)`). It has no pressed material on the web (hover/focus only).
 */
@Composable
private fun RenameKey(onRename: () -> Unit) {
    val t = LocalTetherTokens.current
    val look: (KeyState) -> ChromeLook = remember(t) {
        { ChromeLook(Color.Transparent, Color.Transparent, t.muted, emptyList(), t.radiusSm) }
    }
    ChromeIconKey(
        onClick = onRename,
        icon = TetherIcons.Pencil,
        contentDescription = "Rename session",
        look = look,
        width = 23.96.dp,
        height = 23.96.dp,
        iconSize = 15.dp,
        modifier = Modifier.testTag(ShellTags.RenameKey),
    )
}

/**
 * Stand-in for T4.3's `ContextGauge` until it lands: the `.telemetry-button` it renders into
 * (2.75rem square, `--radius-key`, `--muted`; open = `.context-gauge.is-open` violet wash, which
 * Studio's flat rail overrides back to transparent, studio.css 364) around the static lucide
 * gauge the live arc replaced. Name and pressed state follow the web (`aria-pressed`).
 */
@Composable
fun TelemetryHandlePlaceholder(open: Boolean, onToggle: () -> Unit) {
    val t = LocalTetherTokens.current
    val base = rememberIconLook(t.muted, t.radiusKey)
    val look: (KeyState) -> ChromeLook = { state ->
        val l = base(state)
        l.copy(face = Color.Transparent, shadows = emptyList(), ink = if (open) t.violet else t.muted)
    }
    ChromeIconKey(
        onClick = onToggle,
        icon = TetherIcons.Gauge,
        contentDescription = "Session telemetry",
        look = look,
        width = 44.dp,
        height = 44.dp,
        iconSize = 18.dp,
        modifier = Modifier
            .testTag(ShellTags.TelemetryHandle)
            .semantics { toggleableState = ToggleableState(open) },
    )
}

/**
 * `.workspace-links-popover` (`popover="auto"`): fixed at `inset: 6.5rem space-lg auto auto`,
 * `min(24rem, 100vw - 2rem)` wide and at most `calc(100dvh - 8rem)` tall, scrolling past that
 * (`overflow-y: auto`), a `--graphite` card with `1px --line-strong`, `--radius-md` and
 * `--shadow-floating` (globals.css 11861-11885). Light-dismiss: a tap outside closes it. Holds the
 * session's copy controls (the directory, the terminal resume command when the session carries one
 * (v52, between the two as on the web) and the Tether id), the statusline ([statusline], T4.3) and, on phones, the pin key.
 *
 * Expanded (≥ 48rem): the Tether id reads "Copy Tether id" (4123-4131), the pin key stays in the
 * header (11880), and where the inspector column exists the card clears it
 * (`right: calc(var(--inspector-width) + var(--space-lg))`, 11886-11888: pass [endInset]) and the
 * statusline is left out, as the column shows the readings ([showStatusline]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionLinksPopover(
    session: AgentSession,
    workspaceRoot: String?,
    copiedPath: Boolean,
    copiedTetherId: Boolean,
    actions: WorkspaceHeaderActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    statusline: StatuslineSlot = {},
    expanded: Boolean = false,
    endInset: Dp? = null,
    showStatusline: Boolean = true,
    /** The resume-command control's confirmation: "Copied resume command" for 1.5 s after a copy. */
    copiedResumeCommand: Boolean = false,
    /** ta-7njx: the expanded header's bar Pin has yielded into this menu ([HeaderFit.pinInMenu]). */
    pinInMenu: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val density = LocalDensity.current
    // The web's viewport is the window without the system bars.
    val bars = WindowInsets.systemBars
    val barsTop = with(density) { bars.getTop(this).toDp() }
    val barsBottom = with(density) { bars.getBottom(this).toDp() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Light dismiss: no scrim, the whole viewport outside the card closes it.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close session links", onClick = onDismiss),
        )
        val width: Dp = minOf(384.dp, maxWidth - 32.dp)
        val maxCardHeight: Dp = (maxHeight - barsTop - barsBottom - 128.dp).coerceAtLeast(0.dp)
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = -(endInset ?: t.css.spaceLg), y = 104.dp)
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                .width(width)
                .heightIn(max = maxCardHeight)
                .cssSurface(RoundedCornerShape(t.radiusMd), t.graphite, CssBorder(1.dp, t.lineStrong), t.css.shadowFloating)
                .swallowTaps() // T14.2: pointer-only, so the menu items stay separate nodes
                .semantics { paneTitle = "Session links" }
                .testTag(ShellTags.LinksPopover)
                .padding(1.dp) // the border
                .verticalScroll(rememberScrollState())
                .padding(t.css.spaceLg - 1.dp),
        ) {
            Text("Session links", color = t.ink, style = cssText(type.ui, 0.8f, 700))
            Spacer(Modifier.height(t.css.spaceSm))
            Column(verticalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
                // ta-28i: the working directory is a path: code (every control a token), LTR.
                LinkRow(
                    text = codeLabel(compactPath(session.cwd, workspaceRoot)),
                    leading = null,
                    trailing = if (copiedPath) TetherIcons.Check else TetherIcons.Copy,
                    trailingSize = 14.dp,
                    description = if (copiedPath) "Copied working directory" else "Copy working directory",
                    onClick = actions.onCopyPath,
                    mono = true,
                    studioSize = true,
                )
                // workspace-header.tsx:56-70 (v52): the terminal resume command, only when the server built
                // one (the fake engine and a fresh chat have none). Same keys at both widths; only the
                // legend differs ("Tap to copy" on phones, "Copy" from 48rem). The web's `title` (the exact
                // command and the end-the-session-first note) is a long-press tooltip here.
                val resumeCommand = session.resumeCommand
                if (resumeCommand != null) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(resumeCommandTitle(resumeCommand)) } },
                        state = rememberTooltipState(),
                        modifier = Modifier.testTag(ShellTags.ResumeCommandKey),
                    ) {
                        LinkRow(
                            text = AnnotatedString(
                                when {
                                    copiedResumeCommand -> "Copied resume command"
                                    expanded -> "Copy resume command"
                                    else -> "Tap to copy resume command"
                                },
                            ),
                            leading = TetherIcons.Terminal,
                            trailing = if (copiedResumeCommand) TetherIcons.Check else TetherIcons.Copy,
                            trailingSize = 13.dp,
                            description = if (copiedResumeCommand) "Copied resume command" else "Copy the command to resume this session in a terminal",
                            onClick = actions.onCopyResumeCommand,
                            mono = true,
                            studioSize = false,
                        )
                    }
                }
                LinkRow(
                    text = AnnotatedString(
                        when {
                            copiedTetherId -> "Copied Tether id"
                            expanded -> "Copy Tether id"
                            else -> "Tap to copy Tether id"
                        },
                    ),
                    leading = TetherIcons.Hash,
                    trailing = if (copiedTetherId) TetherIcons.Check else TetherIcons.Copy,
                    trailingSize = 13.dp,
                    description = if (copiedTetherId) "Copied Tether id" else "Copy this session's Tether id",
                    onClick = actions.onCopyTetherId,
                    mono = true,
                    studioSize = false,
                )
                if (showStatusline) statusline(expanded)
            }
            if (!expanded || pinInMenu) {
                // `.workspace-pin-mobile`: flex, 2.75rem, margin-top space-sm; pinned = violet wash.
                val pinned = session.pinned
                Row(
                    Modifier
                        .padding(top = t.css.spaceSm)
                        .heightIn(min = 44.dp)
                        .cssSurface(RoundedCornerShape(t.radiusKey), if (pinned) t.violetWash else Color.Transparent)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = actions.onTogglePinned)
                        .clearAndSetSemantics {
                            contentDescription = if (pinned) "Unpin session" else "Pin session"
                            role = Role.Button
                            stateDescription = if (pinned) "Pinned" else "Not pinned"
                        }
                        .testTag(ShellTags.PinKey),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
                ) {
                    val ink = if (pinned) t.violet else t.muted
                    Icon(TetherIcons.Pin, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
                    Text(if (pinned) "Unpin session" else "Pin session", color = ink, style = cssText(type.ui, 0.74f, 400))
                }
            }
        }
    }
}

/**
 * workspace-header.tsx:66 `title`: the exact command (it differs per provider, so a paraphrase is the
 * one thing not worth trusting) and why to end the session first. The command is drawn the confirm-
 * before-run way ([SafeText.exact]: a lookalike space or a control character shows as a token).
 */
internal fun resumeCommandTitle(command: String): String =
    "Copies:\n${SafeText.exact(command)}\n\nEnd this session first — two live turns would share one transcript and working directory."

/**
 * One copy control of the popover's meta strip (`.workspace-path` / `.workspace-tether-id`):
 * 2.75rem row, `0 0.5rem` padding, transparent `1px` edge, `--radius-key - 2px`, mono `--faint`
 * legend at 0.66rem (globals.css 11200-11210, 11877-11878; Studio path: `--muted`, 0.68rem).
 */
@Composable
private fun LinkRow(
    text: AnnotatedString,
    leading: ImageVector?,
    trailing: ImageVector,
    trailingSize: Dp,
    description: String,
    onClick: () -> Unit,
    mono: Boolean,
    studioSize: Boolean,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val studioPath = studioSize
    val ink = if (studioPath) t.muted else t.faint
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            }
            .padding(horizontal = 9.dp), // 0.5rem + the 1px transparent edge
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        if (leading != null) Icon(leading, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp))
        Text(
            text,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = cssText(if (mono) type.mono else type.ui, if (studioPath) 0.68f else 0.66f, 400),
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(trailing, contentDescription = null, tint = ink, modifier = Modifier.size(trailingSize))
    }
}
