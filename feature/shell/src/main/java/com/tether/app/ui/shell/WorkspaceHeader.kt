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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.KeyState
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.components.TetherStatusPill
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.hardShadow
import com.tether.app.ui.components.statusToneOf
import com.tether.app.ui.icons.TetherIcons
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
 * Material: instrument — `--graphite`, `1px --line-strong`, a lit top lip and the `--seam-lip`
 * shade (globals.css 11136-11146 with the phone override 11720), `padding: space-sm space-lg`
 * (11858); Studio — flat `--graphite`, `1px --line`, `padding: space-sm space-md` (studio.css 351, 450).
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
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val studio = t.studio
    val edge = if (studio) t.line else t.lineStrong
    val shadows = when {
        studio -> emptyList()
        expanded -> listOf(
            hardShadow(1.dp, t.seamLip),
            softShadow(6.dp, 14.dp, t.contact.copy(alpha = 0.32f), spread = (-8).dp),
            hardShadow(1.dp, t.litSoft, inset = true),
        )
        else -> listOf(hardShadow(1.dp, t.seamLip), hardShadow(1.dp, t.litSoft, inset = true))
    }
    val padH = when {
        studio && expanded -> 28.dp
        studio -> t.css.spaceMd
        else -> t.css.spaceLg
    }
    val padV = if (studio && expanded) 16.dp else t.css.spaceSm
    Column(
        modifier
            .fillMaxWidth()
            .zIndex(1f) // `position: relative; z-index: 1` — the shade paints over the stage
            .then(if (studio && expanded) Modifier.heightIn(min = 80.dp) else Modifier)
            .cssSurface(RectangleShape, t.graphite, shadows = shadows)
            .drawBehind { drawRect(edge, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
            .padding(bottom = 1.dp)
            .padding(horizontal = padH, vertical = padV)
            .testTag(ShellTags.WorkspaceHeader),
    ) {
    // `.workspace-header` is a flex COLUMN: under Studio's 5rem min-height the row sits at the top.
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        // `.workspace-header-main`: space-between, gap space-lg (Studio phone 0.5rem).
        horizontalArrangement = Arrangement.spacedBy(if (studio && !expanded) 8.dp else t.css.spaceLg),
    ) {
        // `.workspace-title-row`: gap space-md (Studio phone 0.4rem); the pencil pulls in by
        // `calc(var(--space-md) * -0.5)`.
        val titleGap = if (studio && !expanded) 6.4.dp else t.css.spaceMd
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                session.name,
                color = t.white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = when {
                    studio && expanded -> cssText(type.ui, 1.12f, 740, trackingEm = -0.025f, lineHeight = 1.45f)
                    studio -> cssText(type.ui, 0.925f, 740, trackingEm = -0.025f, lineHeight = 1.45f)
                    expanded -> cssText(type.ui, 1.05f, 720, trackingEm = -0.022f, lineHeight = 1.45f)
                    else -> cssText(type.ui, 1.05f, 680, trackingEm = -0.01f, lineHeight = 1.45f)
                },
                modifier = Modifier.weight(1f, fill = false).semantics { heading() },
            )
            Spacer(Modifier.width(titleGap - 6.dp))
            RenameKey(actions.onRename)
            Spacer(Modifier.width(titleGap))
            TetherStatusPill(label = statusCopy(session.status), tone = statusToneOf(session.status))
        }
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
                    showLabel = expanded,
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
            if (expanded) PinKey(session.pinned, actions.onTogglePinned)
            // `.end-session`: the brick key, glyph only below 48rem. 2.35rem tall in the instrument
            // skins (`:root .end-session`, globals.css 11196); Studio's rail forces 2.75rem
            // (studio.css 362). Disabled once the session has exited, like the web. From 48rem it
            // prints "End session" at 0.66rem (4117-4121, 11196).
            TetherKey(
                onClick = actions.onEndSession,
                classes = KeyClasses.EndSession,
                label = if (expanded) "End session" else null,
                icon = TetherIcons.CircleStop,
                iconSize = 16.dp,
                fontSize = if (expanded) 10.56.sp else TextUnit.Unspecified,
                contentDescription = "End session",
                enabled = session.status != "exited",
                minHeight = if (studio) 44.dp else 37.6.dp,
                modifier = Modifier.testTag(ShellTags.EndSessionKey),
            )
        }
    }
    }
}

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
    val face = if (pinned && !t.studio) t.violetWash else Color.Transparent
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
        { ChromeLook(Color.Transparent, Color.Transparent, t.muted, emptyList(), 0.dp, t.radiusSm) }
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
    val studio = t.studio
    val base = rememberIconLook(t.muted, t.radiusKey)
    val look: (KeyState) -> ChromeLook = { state ->
        val l = base(state)
        when {
            studio -> l.copy(face = Color.Transparent, shadows = emptyList(), ink = if (open) t.violet else t.muted)
            open && state == KeyState.Rest -> l.copy(face = t.violetWash, ink = t.violet)
            open -> l.copy(ink = t.violet)
            else -> l
        }
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
 * session's copy controls (the directory — shown because the app has no resume command to stand in
 * for it — and the Tether id), the statusline ([statusline], T4.3) and, on phones, the pin key.
 *
 * Expanded (≥ 48rem): the Tether id reads "Copy Tether id" (4123-4131), the pin key stays in the
 * header (11880), and where the inspector column exists the card clears it
 * (`right: calc(var(--inspector-width) + var(--space-lg))`, 11886-11888: pass [endInset]) and the
 * statusline is left out, as the column shows the readings ([showStatusline]).
 */
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
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                .semantics { paneTitle = "Session links" }
                .testTag(ShellTags.LinksPopover)
                .padding(1.dp) // the border
                .verticalScroll(rememberScrollState())
                .padding(t.css.spaceLg - 1.dp),
        ) {
            Text("Session links", color = t.ink, style = cssText(type.ui, 0.8f, 700))
            Spacer(Modifier.height(t.css.spaceSm))
            Column(verticalArrangement = Arrangement.spacedBy(t.css.spaceSm)) {
                LinkRow(
                    text = compactPath(session.cwd, workspaceRoot),
                    leading = null,
                    trailing = if (copiedPath) TetherIcons.Check else TetherIcons.Copy,
                    trailingSize = 14.dp,
                    description = if (copiedPath) "Copied working directory" else "Copy working directory",
                    onClick = actions.onCopyPath,
                    mono = true,
                    studioSize = true,
                )
                LinkRow(
                    text = when {
                        copiedTetherId -> "Copied Tether id"
                        expanded -> "Copy Tether id"
                        else -> "Tap to copy Tether id"
                    },
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
            if (!expanded) {
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
 * One copy control of the popover's meta strip (`.workspace-path` / `.workspace-tether-id`):
 * 2.75rem row, `0 0.5rem` padding, transparent `1px` edge, `--radius-key - 2px`, mono `--faint`
 * legend at 0.66rem (globals.css 11200-11210, 11877-11878; Studio path: `--muted`, 0.68rem).
 */
@Composable
private fun LinkRow(
    text: String,
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
    val studioPath = t.studio && studioSize
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
