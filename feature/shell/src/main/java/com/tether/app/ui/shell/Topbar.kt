package com.tether.app.ui.shell

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyState
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.focusRing
import com.tether.app.ui.components.hardShadow
import com.tether.app.ui.components.softShadow
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTokens

/** topbar.module.css `.bar` below 48rem: `min-height: 3.5rem` (the status-bar inset on top). */
val TopbarHeight: Dp = 56.dp

/** topbar.module.css `.bar`: `min-height: 4rem` from 48rem (the expanded layout). */
val WideTopbarHeight: Dp = 64.dp

/** The topbar's link readout (`.connection-state`, topbar.tsx:36-39): its word and whether it spins. */
enum class LinkReadout(val word: String) {
    Connected("Secure link"),
    Connecting("Connecting"),
    Reconnecting("Reconnecting"),
}

/** Why a top-bar control is unavailable (topbar.tsx `fileBrowserDisabledReason` and the app's own gaps). */
object TopbarReasons {
    /** topbar.tsx's default `fileBrowserDisabledReason`. */
    const val FILES = "Open a session to browse its files"

    /**
     * App-only: a destination the web has and this app does not yet (Scheduled T9.3, Usage and
     * Accounts T9.2). Shown the way the web shows an unavailable control: `aria-disabled`, dimmed,
     * with its reason.
     */
    const val NOT_YET = "Not available in the app yet"
}

/**
 * The top bar's hosts (topbar.tsx props). A null host has nothing to open yet: its control is
 * shown unavailable with its reason, never hidden.
 */
data class TopbarActions(
    /** `onOpenDrawer`: the Sessions rail's drawer on the phone layout. */
    val onOpenDrawer: () -> Unit,
    /** `onOpenFiles`: the workspace file browser (unavailable without a session). */
    val onOpenFiles: (() -> Unit)? = null,
    /** `onOpenUsage`: the "Accounts" control (the web's usage accounts dialog, T9.2). */
    val onOpenUsage: (() -> Unit)? = null,
    /** The "Usage" destination (the web's `/usage` page, T9.2). */
    val onOpenUsageAnalytics: (() -> Unit)? = null,
    /** `onOpenLog`: Health & event log. */
    val onOpenLog: (() -> Unit)? = null,
    /** `onLogout`: Lock. */
    val onLogout: () -> Unit,
    /** `onOpenSettings`. */
    val onOpenSettings: (() -> Unit)? = null,
    /** `onNavigate`: in-app navigation between the console's views. */
    val onNavigate: ((DashboardView) -> Unit)? = null,
    /** The views this app can show (Scheduled is T9.3's): any other is shown unavailable. */
    val views: Set<DashboardView> = setOf(DashboardView.Overview, DashboardView.Sessions),
) {
    internal fun hostOf(destination: TopBarDestination): (() -> Unit)? {
        val view = destination.view ?: return onOpenUsageAnalytics
        val navigate = onNavigate ?: return null
        return if (view in views) ({ navigate(view) }) else null
    }
}

/**
 * The bar's inputs besides its hosts: the destination on screen ([current], null while the
 * console has not resolved its view), the link state, the layout ([wide] = the expanded shell,
 * the web's ≥ 48rem), whether the rail's drawer key shows ([drawerKey]: narrow, where the rail
 * exists), whether the utility menu is open, and the window width for the web's finer breakpoints.
 */
data class TopbarState(
    val current: TopBarDestination?,
    val link: LinkReadout,
    val wide: Boolean,
    val drawerKey: Boolean = !wide,
    val menuOpen: Boolean = false,
    val viewportWidth: Int = if (wide) 1280 else 412,
    val unseenWarnings: Int = 0,
    val fileBrowserDisabled: Boolean = false,
)

/** topbar.module.css `@media (max-width: 74rem)`: the brand's margin and the nav's gap tighten. */
internal const val TOPBAR_TIGHT_MAX = 1184

/** The expanded bar's own items, in order: the four destinations, then Files and Accounts. */
enum class BarItem(val destination: TopBarDestination?) {
    Overview(TopBarDestination.Overview),
    Sessions(TopBarDestination.Sessions),
    Scheduled(TopBarDestination.Scheduled),
    Usage(TopBarDestination.Usage),
    Files(null),
    Accounts(null),
}

/**
 * T15.4 r2: which of the expanded bar's items did not fit on it, measured, not guessed from the
 * width: the room between the brand and the actions depends on the window AND the font scale. The
 * bar keeps the longest prefix of [BarItem] that fits whole and folds the rest (from the end:
 * Accounts first) into the utility menu, which lists exactly [folded]. So every destination and
 * tool is always either fully on the bar or in the menu, never clipped or hidden. (The web keeps
 * them all on its bar from 48rem; this layout starts at 840dp and honours the system font scale.)
 * The phone bar folds everything, so it does not use this.
 */
@androidx.compose.runtime.Stable
class TopbarFold {
    var folded: Set<BarItem> by androidx.compose.runtime.mutableStateOf(emptySet())
        internal set
}

/** topbar.module.css `@media (max-width: 23rem)`: the link word is visually hidden (still read). */
internal const val TOPBAR_LINK_WORD_MIN = 368

/**
 * T15.4: the console's shared top bar (components/topbar.tsx, topbar.module.css; the approved
 * Studio concept, OVERVIEW_STUDIO_PLAN.md §4): brand · Overview / Sessions / Scheduled / Usage ·
 * Files / Accounts · link state · Settings · the utility menu (Health, Lock). On the phone layout
 * the navigation, Files and Accounts fold into the same menu; the drawer key (where the rail
 * exists), the brand, the link state and Settings stay on the bar.
 *
 * `--graphite` bar, `1px --line` bottom edge, the status-bar inset as its top padding. The
 * redesign is Studio's; the skins T15.5 retires draw the same bar in their own tokens.
 */
@Composable
fun TetherTopbar(
    actions: TopbarActions,
    state: TopbarState,
    onToggleMenu: () -> Unit,
    modifier: Modifier = Modifier,
    /** r2: shared with [TopbarMenu], which lists what the bar folded. */
    fold: TopbarFold = remember { TopbarFold() },
) {
    val t = LocalTetherTokens.current
    val wide = state.wide
    val tight = state.viewportWidth <= TOPBAR_TIGHT_MAX
    Box(
        modifier
            .fillMaxWidth()
            .zIndex(2f) // z-sticky
            .cssSurface(RectangleShape, t.graphite)
            .drawBehind { drawRect(t.line, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
            .windowInsetsPadding(WindowInsets.statusBars)
            .testTag(ShellTags.Topbar),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(if (wide) WideTopbarHeight else TopbarHeight)
                .padding(bottom = 1.dp) // the border-bottom
                // `padding-inline: 1.5rem`; narrow `0.625rem`.
                .padding(horizontal = if (wide) 24.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            // `gap: 1rem`; narrow `0.4rem`.
            horizontalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 6.4.dp),
        ) {
            // `.drawerButton { margin-right: -0.25rem }`: it sits 0.15rem from the brand.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.4.dp)) {
                if (state.drawerKey) {
                    TopbarIconKey(
                        onClick = actions.onOpenDrawer,
                        icon = TetherIcons.PanelLeft,
                        iconSize = 19.dp,
                        contentDescription = "Open sessions",
                        modifier = Modifier.testTag(ShellTags.MenuKey),
                    )
                }
                BrandLink(
                    wide = wide,
                    onClick = actions.onNavigate?.let { navigate -> { navigate(DashboardView.Overview) } },
                    // `.brand { margin-right: 2.5rem }` (1rem at ≤ 74rem, 0 narrow); the Row's gap is added.
                    modifier = Modifier.padding(end = if (!wide) 0.dp else if (tight) 16.dp else 40.dp),
                )
            }
            if (wide) {
                // `<nav aria-label="Primary">`: takes the room left between the brand and the
                // actions (never more), so a tight window cannot push Settings off the bar.
                FoldingNav(
                    gap = if (tight) 0.dp else 8.dp,
                    fold = fold,
                    modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds().semantics { paneTitle = "Primary" },
                ) { item, probe ->
                    val destination = item.destination
                    val files = actions.onOpenFiles.takeIf { !state.fileBrowserDisabled }
                    val (label, host, reason, tag) = when {
                        destination != null -> Quad(destination.label, actions.hostOf(destination), TopbarReasons.NOT_YET, ShellTags.nav(destination))
                        item == BarItem.Files -> Quad("Files", files, TopbarReasons.FILES, ShellTags.FilesKey)
                        else -> Quad("Accounts", actions.onOpenUsage, TopbarReasons.NOT_YET, ShellTags.AccountsKey)
                    }
                    NavLink(
                        label = label,
                        active = destination != null && destination == state.current,
                        host = host,
                        reason = reason,
                        // A measuring probe is never placed; it carries no tag and no semantics.
                        modifier = if (probe) Modifier.clearAndSetSemantics { } else Modifier.testTag(tag),
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // `.actions { gap: 0.5rem }`; narrow `0.15rem`.
                horizontalArrangement = Arrangement.spacedBy(if (wide) 8.dp else 2.4.dp),
            ) {
                ConnectionReadout(state.link, showWord = wide || state.viewportWidth >= TOPBAR_LINK_WORD_MIN)
                if (wide) {
                    // `.divider`: 1px × 1.75rem `--line`, `margin: 0 0.25rem`; hidden narrow.
                    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(28.dp).drawBehind { drawRect(t.line) })
                }
                TopbarIconKey(
                    onClick = { actions.onOpenSettings?.invoke() },
                    icon = TetherIcons.Settings,
                    iconSize = 18.dp,
                    contentDescription = "Settings",
                    enabled = actions.onOpenSettings != null,
                    modifier = Modifier.testTag(ShellTags.SettingsKey),
                )
                val warnings = state.unseenWarnings
                TopbarIconKey(
                    onClick = onToggleMenu,
                    icon = if (wide) TetherIcons.Ellipsis else TetherIcons.Menu,
                    iconSize = 19.dp,
                    // aria-label, plus the visually hidden ", N unseen warnings".
                    contentDescription = (if (wide) "More tools" else "Menu: navigation and tools") +
                        if (warnings > 0) ", $warnings unseen warnings" else "",
                    stateDescription = if (state.menuOpen) "Expanded" else "Collapsed",
                    lit = state.menuOpen,
                    modifier = Modifier.testTag(ShellTags.ToolsMenuKey),
                    overlay = if (warnings > 0) {
                        { WarningBadge(warnings, Modifier.align(Alignment.TopEnd)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/**
 * The utility menu (topbar.tsx `role="menu"`, `.menu`): under the bar at its end edge
 * (`top: calc(100% + 0.4rem); right: 0` of the trigger's root, which ends at the bar's inline
 * padding), `min-width: 15rem`, `max-width: calc(100vw - 1.5rem)`, `0.4rem` padding, a
 * `--graphite` card with `1px --line`, `--radius-md` and `--shadow-menu`. Light dismiss: a tap
 * outside closes it (the web's outside `pointerdown`); an item closes it and then acts.
 *
 * Narrow: "Navigate" and the four destinations, a separator, Files and Accounts; then, on every
 * width, Health & event log (with the warning count) and, after a separator, Lock. An unavailable
 * item stays in the menu, dimmed, with its reason under its label (`aria-disabled`, focusable).
 */
@Composable
fun TopbarMenu(
    actions: TopbarActions,
    state: TopbarState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** r2: what the bar folded ([TetherTopbar]'s); the phone bar folds everything. */
    fold: TopbarFold = remember { TopbarFold() },
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val wide = state.wide
    val inMenu: (BarItem) -> Boolean = { !wide || it in fold.folded }
    val menuDestinations = BarItem.entries.filter { it.destination != null && inMenu(it) }.mapNotNull { it.destination }
    BoxWithConstraints(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close menu", onClick = onDismiss),
        )
        val run: ((() -> Unit)?) -> (() -> Unit)? = { host -> host?.let { { onDismiss(); it() } } }
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .offset(x = -(if (wide) 24.dp else 10.dp), y = (if (wide) WideTopbarHeight else TopbarHeight) + 6.4.dp)
                .widthIn(min = 240.dp, max = (maxWidth - 24.dp).coerceAtLeast(0.dp))
                .width(androidx.compose.foundation.layout.IntrinsicSize.Max)
                .cssSurface(RoundedCornerShape(t.radiusMd), t.graphite, CssBorder(1.dp, t.line), t.css.shadowMenu)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                .semantics { paneTitle = if (wide) "Tools" else "Navigation and tools" }
                .testTag(ShellTags.ToolsMenu)
                .heightIn(max = (maxHeight - (if (wide) WideTopbarHeight else TopbarHeight) - 16.dp).coerceAtLeast(120.dp))
                .padding(1.dp)
                // r2: at a large font the menu scrolls rather than run off the screen.
                .verticalScroll(rememberScrollState())
                .padding(6.4.dp),
        ) {
            if (menuDestinations.isNotEmpty()) {
                Text(
                    "NAVIGATE",
                    color = t.faint,
                    maxLines = 1,
                    style = cssText(type.ui, 0.68f, 650, trackingEm = 0.06f),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp).clearAndSetSemantics { },
                )
                menuDestinations.forEach { destination ->
                    MenuItem(
                        label = destination.label,
                        icon = null,
                        host = run(actions.hostOf(destination)),
                        reason = TopbarReasons.NOT_YET,
                        active = destination == state.current,
                        modifier = Modifier.testTag(ShellTags.menuNav(destination)),
                    )
                }
                MenuSeparator()
            }
            if (inMenu(BarItem.Files)) {
                MenuItem(
                    label = "Files",
                    icon = TetherIcons.FolderOpen,
                    host = run(actions.onOpenFiles.takeIf { !state.fileBrowserDisabled }),
                    reason = TopbarReasons.FILES,
                    modifier = Modifier.testTag(ShellTags.MenuFiles),
                )
            }
            if (inMenu(BarItem.Accounts)) {
                MenuItem(
                    label = "Accounts",
                    icon = TetherIcons.Gauge,
                    host = run(actions.onOpenUsage),
                    reason = TopbarReasons.NOT_YET,
                    modifier = Modifier.testTag(ShellTags.MenuAccounts),
                )
            }
            val warnings = state.unseenWarnings
            MenuItem(
                label = "Health & event log",
                icon = TetherIcons.Activity,
                host = run(actions.onOpenLog),
                reason = TopbarReasons.NOT_YET,
                stateDescription = if (warnings > 0) "$warnings warnings" else null,
                trailing = if (warnings > 0) {
                    { ItemBadge(warnings) }
                } else {
                    null
                },
                modifier = Modifier.testTag(ShellTags.LogKey),
            )
            MenuSeparator()
            MenuItem(
                label = "Lock",
                icon = TetherIcons.LogOut,
                host = run(actions.onLogout),
                reason = null,
                modifier = Modifier.testTag(ShellTags.LockKey),
            )
        }
    }
}

// ── Bar parts ────────────────────────────────────────────────────────────────

/**
 * `.brand` as the Overview link (aria-label "Tether — Overview"): the Studio mark and lowercase
 * wordmark at 1.24rem (1.1rem with a 1.6rem mark narrow), `--white`, a 2.75rem target.
 */
@Composable
private fun BrandLink(wide: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .heightIn(min = 44.dp)
            .testTag(ShellTags.Brand)
            .clickable(interaction, indication = null, enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
            .clearAndSetSemantics {
                contentDescription = "Tether — Overview"
                role = Role.Button
            }
            .focusRing(focused, RoundedCornerShape(t.radiusSm), t.violet),
        contentAlignment = Alignment.Center,
    ) {
        TopbarBrand(studioDesktop = wide)
    }
}

/**
 * `.navLink`: 2.75rem tall, `0 0.85rem`, `--muted` 0.875rem / 600; the current destination is
 * `--violet` with a 2px underline seated on the bar's bottom edge (`bottom: -1px`, inset 0.85rem,
 * top corners 2px). `aria-current` is its selected state, so colour is not the only cue. An
 * unavailable one (`aria-disabled`) is at 0.5 opacity and says why.
 */
@Composable
private fun RowScope.NavLink(
    label: String,
    active: Boolean,
    host: (() -> Unit)?,
    reason: String,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val enabled = host != null
    val ink = when {
        active -> t.violet
        enabled && pressed -> t.white
        else -> t.muted
    }
    Box(
        modifier
            .fillMaxHeight()
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) { host?.invoke() }
            .semantics {
                if (active) selected = true
                if (!enabled) stateDescription = reason
            }
            .focusRing(focused, RoundedCornerShape(t.radiusSm), t.violet)
            .drawBehind {
                if (!active) return@drawBehind
                val inset = 13.6.dp.toPx()
                val h = 2.dp.toPx()
                val r = CornerRadius(2.dp.toPx())
                val top = size.height - 1.dp.toPx()
                val rect = RoundRect(inset, top, size.width - inset, top + h, r, r, CornerRadius.Zero, CornerRadius.Zero)
                drawPath(Path().apply { addRoundRect(rect) }, t.violet)
            }
            .padding(horizontal = 13.6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = ink,
            maxLines = 1,
            style = cssText(type.ui, 0.875f, 600),
            modifier = if (enabled) Modifier else Modifier.alpha(0.5f),
        )
    }
}

/**
 * `.iconButton`: a 2.75rem square, `--radius-sm`, transparent with `--muted` ink; held (the web's
 * hover) or [lit] (the open menu's trigger, `aria-expanded`) it takes `--graphite-raised` and
 * `--white`.
 */
@Composable
private fun TopbarIconKey(
    onClick: () -> Unit,
    icon: ImageVector,
    iconSize: Dp,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    lit: Boolean = false,
    stateDescription: String? = null,
    overlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
) {
    val t = LocalTetherTokens.current
    val look: (KeyState) -> ChromeLook = { state ->
        when {
            state == KeyState.Disabled -> ChromeLook(Color.Transparent, Color.Transparent, t.muted, emptyList(), t.radiusSm, alpha = 0.5f)
            state == KeyState.Pressed || lit -> ChromeLook(t.graphiteRaised, Color.Transparent, t.white, emptyList(), t.radiusSm)
            else -> ChromeLook(Color.Transparent, Color.Transparent, t.muted, emptyList(), t.radiusSm)
        }
    }
    ChromeIconKey(
        onClick = onClick,
        icon = icon,
        contentDescription = contentDescription,
        look = look,
        width = 44.dp,
        height = 44.dp,
        iconSize = iconSize,
        enabled = enabled,
        stateDescription = stateDescription,
        modifier = modifier,
        overlay = overlay,
    )
}

/**
 * `.connection` (role="status"): ShieldCheck while connected, else RotateCcw turning once per
 * 1.2s (static under reduced motion), and the word at 600 0.8rem; `--running` while connected,
 * else `--muted`. Below 23rem the word is visually hidden but still read ([showWord]). The words
 * are the app's own constants: no server text reaches the bar.
 */
@Composable
private fun ConnectionReadout(link: LinkReadout, showWord: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val ink = if (link == LinkReadout.Connected) t.css.running else t.muted
    val reduced = LocalReducedMotion.current
    val angle by if (link == LinkReadout.Connected || reduced) {
        remember { mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "link").animateFloat(
            0f,
            360f,
            infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
            label = "linkSpin",
        )
    }
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .widthIn(min = if (showWord) 0.dp else 36.dp)
            .padding(horizontal = 4.dp)
            .semantics {
                contentDescription = link.word
                liveRegion = LiveRegionMode.Polite
            }
            .testTag(ShellTags.ConnectionReadout),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.2.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            if (link == LinkReadout.Connected) TetherIcons.ShieldCheck else TetherIcons.RotateCcw,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(15.dp).rotate(angle),
        )
        if (showWord) {
            Text(
                link.word,
                color = ink,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { },
                style = cssText(type.ui, 0.8f, 600),
            )
        }
    }
}

// ── Menu parts ───────────────────────────────────────────────────────────────

/**
 * `.menuItem`: 2.75rem, `0.45rem 0.75rem`, gap 0.7rem, `--radius-sm`, `--ink` 600 0.84rem, the
 * glyph 16px `--muted`; held (hover) `--graphite-raised` / `--white`. The current destination is
 * `--violet` behind a 3px × 1.1rem bar (`.menuItemActive::before`). Unavailable: `--faint`, the
 * reason in a 0.7rem / 500 hint under the label (`.menuHint`).
 */
@Composable
private fun MenuItem(
    label: String,
    icon: ImageVector?,
    host: (() -> Unit)?,
    reason: String?,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    stateDescription: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val enabled = host != null
    val shape = RoundedCornerShape(t.radiusSm)
    val ink = when {
        !enabled -> t.faint
        active -> t.violet
        pressed || focused -> t.white
        else -> t.ink
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .cssSurface(shape, if (enabled && (pressed || focused)) t.graphiteRaised else Color.Transparent)
            // aria-disabled rather than disabled: the item stays reachable so its reason is read.
            .clickable(interaction, indication = null, role = Role.Button) { host?.invoke() }
            .semantics {
                if (active) selected = true
                if (!enabled) disabled()
                stateDescription?.let { this.stateDescription = it }
            }
            .padding(horizontal = 12.dp, vertical = 7.2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.2.dp),
    ) {
        if (active) {
            // `::before`: 3px × 1.1rem, radius 2px, `margin-right: -0.2rem` (0.5rem from the label).
            Box(Modifier.width(3.dp).height(17.6.dp).cssSurface(RoundedCornerShape(2.dp), t.violet))
        }
        if (icon != null) Icon(icon, contentDescription = null, tint = if (enabled) t.muted else t.faint, modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.6.dp)) {
            Text(label, color = ink, maxLines = 1, style = cssText(type.ui, 0.84f, 600))
            if (!enabled && reason != null) {
                Text(reason, color = t.faint, style = cssText(type.ui, 0.7f, 500))
            }
        }
        trailing?.invoke()
    }
}

/** `.menuSeparator`: 1px `--line`, `margin: 0.3rem 0.4rem`. */
@Composable
private fun MenuSeparator() {
    val t = LocalTetherTokens.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.4.dp, vertical = 4.8.dp)
            .height(1.dp)
            .drawBehind { drawRect(t.line) },
    )
}

/**
 * `.itemBadge`: the warning count at the item's end, a 1.1rem pill, `--brick` with `--accent-ink`
 * 700 0.62rem tabular figures ("9+" past nine). Its count is also the item's state description.
 */
@Composable
private fun ItemBadge(count: Int) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Box(
        Modifier
            .heightIn(min = 17.6.dp)
            .widthIn(min = 17.6.dp)
            .cssSurface(RoundedCornerShape(percent = 50), t.brick)
            .padding(horizontal = 4.8.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 9) "9+" else "$count", color = t.accentInk, textAlign = TextAlign.Center, style = cssText(type.ui, 0.62f, 700, lineHeight = 1f))
    }
}

// ── Brand ────────────────────────────────────────────────────────────────────

/**
 * `.brand`: the molded round cap with the violet needle and the etched TETHER wordmark
 * (globals.css 10877-10886); Studio: the accent tile with a white slash and a lower-cased
 * "tether" (studio.css 250, 282-285, 437-438). The accessible name is "Tether" (`aria-label`).
 */
@Composable
internal fun TopbarBrand(
    modifier: Modifier = Modifier,
    /** Studio from 48rem: the 1.24rem wordmark and 1.85rem tile (studio.css 282-283), not the phone's. */
    studioDesktop: Boolean = false,
    ink: Color? = null,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Row(
        modifier.clearAndSetSemantics {
            contentDescription = "Tether"
            heading()
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (!studioDesktop) 8.dp else 11.2.dp),
    ) {
        BrandMark(t, if (studioDesktop) 29.6.dp else 25.6.dp)
        Text(
            "tether",
            color = ink ?: t.white,
            maxLines = 1,
            style = when {
                // r2: each skin its own wordmark; only Studio has a desktop size.
                studioDesktop -> cssText(type.ui, 1.24f, 770, trackingEm = -0.04f)
                else -> cssText(type.ui, 1.1f, 770, trackingEm = -0.04f)
            },
        )
    }
}

@Composable
private fun BrandMark(t: TetherTokens, studioSize: Dp = 25.6.dp) {
    // 1.6rem tile (1.85rem from 48rem), 0.58rem radius, --accent; bars 0.19×0.72rem, 3px radius, white, both opaque.
    val shape = RoundedCornerShape(9.28.dp)
    Canvas(Modifier.size(studioSize).cssSurface(shape, t.accent)) {
        drawNeedle(Color.White, 3.04.dp.toPx(), 11.52.dp.toPx(), 3.dp.toPx(), -3.52.dp.toPx(), 5.12.dp.toPx(), 1f)
    }
}

/**
 * The two `<i>` bars: centred, `transform: rotate(32deg) translateY(d)` — the translation runs
 * along the rotated axis, so the bars are drawn in the rotated frame.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNeedle(
    color: Color,
    w: Float,
    h: Float,
    r: Float,
    d1: Float,
    d2: Float,
    secondAlpha: Float,
) {
    val c = center
    val radius = CornerRadius(minOf(r, w / 2f))
    rotate(32f, c) {
        drawRoundRect(color, Offset(c.x - w / 2f, c.y - h / 2f + d1), Size(w, h), radius)
        drawRoundRect(color.copy(alpha = color.alpha * secondAlpha), Offset(c.x - w / 2f, c.y - h / 2f + d2), Size(w, h), radius)
    }
}

/**
 * `.badge` on the menu trigger: a 1.1rem pill at `top: 0.35rem; right: 0.3rem`, `--brick` with
 * `--accent-ink` 700 0.62rem ("9+" past nine). The count is also in the trigger's name, so it is
 * never carried by the red dot alone. r2: drawn at its 1× size at every font scale (its legend in
 * dp, not scaled sp), so at a large font it cannot grow over the trigger's glyph; the scaled
 * count is read in the trigger's name and printed in the menu's Health item.
 */
@Composable
internal fun WarningBadge(count: Int, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val legend = with(androidx.compose.ui.platform.LocalDensity.current) { (0.62f * 16f).dp.toSp() }
    Box(
        modifier
            .testTag(ShellTags.WarningBadge)
            .offset(x = (-4.8).dp, y = 5.6.dp)
            .heightIn(min = 17.6.dp)
            .widthIn(min = 17.6.dp)
            .cssSurface(RoundedCornerShape(percent = 50), t.brick)
            .padding(horizontal = 4.8.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 9) "9+" else "$count",
            color = t.accentInk,
            textAlign = TextAlign.Center,
            style = cssText(type.ui, 0.62f, 700, lineHeight = 1f).copy(fontSize = legend),
        )
    }
}

private data class Quad(val label: String, val host: (() -> Unit)?, val reason: String, val tag: String)

/**
 * T15.4 r2: the expanded bar's `<nav>`: every [BarItem] is first measured as an unplaced probe at
 * its natural width; the longest prefix that fits whole in the room given (with [gap] between)
 * is composed and placed; the rest is reported to [fold] for the menu. Nothing is ever drawn cut.
 */
@Composable
private fun FoldingNav(
    gap: Dp,
    fold: TopbarFold,
    modifier: Modifier = Modifier,
    item: @Composable (BarItem, probe: Boolean) -> Unit,
) {
    androidx.compose.ui.layout.SubcomposeLayout(modifier) { constraints ->
        val gapPx = gap.roundToPx()
        val height = constraints.maxHeight
        val probe = androidx.compose.ui.unit.Constraints(minHeight = height, maxHeight = height)
        val widths = BarItem.entries.map { entry ->
            subcompose("probe-${entry.name}") { item(entry, true) }.sumOf { it.measure(probe).width }
        }
        var used = 0
        var fits = 0
        for (w in widths) {
            val next = used + (if (fits == 0) 0 else gapPx) + w
            if (next > constraints.maxWidth) break
            used = next
            fits++
        }
        val folded = BarItem.entries.drop(fits).toSet()
        if (fold.folded != folded) fold.folded = folded
        val placeables = BarItem.entries.take(fits).mapIndexed { index, entry ->
            subcompose("item-${entry.name}") { item(entry, false) }
                .map { it.measure(androidx.compose.ui.unit.Constraints.fixed(widths[index], height)) }
        }
        layout(constraints.maxWidth, height) {
            var x = 0
            placeables.forEach { parts ->
                parts.forEach { it.placeRelative(x, 0) }
                x += (parts.maxOfOrNull { it.width } ?: 0) + gapPx
            }
        }
    }
}
