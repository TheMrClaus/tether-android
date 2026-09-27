package com.tether.app.ui.shell

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyState
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.hardShadow
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

/**
 * `<header className="topbar">` from 48rem (components/topbar.tsx; globals.css 3957-4031): one
 * 3rem row (Studio 4rem) across both columns. No menu key (`.mobile-menu { display: none }`); the
 * brand is as wide as the rail minus the bar's inline padding so the wordmark shares the rail's
 * vertical (`width: calc(var(--rail-width) - var(--space-xl))`, auto while the rail is collapsed);
 * the right cluster adds the link readout (`.connection-state`), the tool keys print their words
 * from 80rem (11843-11847) and Lock prints "Lock".
 *
 * Instrument: `padding: 0 space-xl`, `gap: space-lg`, the graphite bar with its parting line
 * (10853-10858). Studio (studio.css 280-293, 425-433): flat, `padding-inline: 0 1rem`, `gap:
 * 1.5rem`; the brand is a rail-wide ink-blue block (`#141d2e`, 1.35rem inline padding, 1.85rem tile,
 * 1.24rem wordmark); the "Workspace" caption shows above 74rem; flat 2.75rem tool keys.
 */
@Composable
internal fun ExpandedTetherTopbar(
    actions: TopbarActions,
    layout: ExpandedTopbar,
    modifier: Modifier = Modifier,
    unseenWarnings: Int = 0,
    fileBrowserDisabled: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val studio = t.studio
    val edge = if (studio) t.line else t.lineStrong
    val shadows = if (studio) emptyList() else listOf(hardShadow(1.dp, t.seamLip), hardShadow(1.dp, t.litSoft, inset = true))
    val labels = layout.viewportWidth >= ExpandedBreakpoints.TOOL_LABELS
    Box(
        modifier
            .fillMaxWidth()
            .zIndex(2f)
            .cssSurface(RectangleShape, t.graphite, shadows = shadows)
            .drawBehind { drawRect(edge, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
            .windowInsetsPadding(WindowInsets.statusBars)
            .testTag(ShellTags.Topbar),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(if (studio) StudioExpandedTopbarHeight else ExpandedTopbarHeight)
                .padding(bottom = 1.dp)
                .padding(start = if (studio) 0.dp else t.css.spaceXl, end = if (studio) 16.dp else t.css.spaceXl),
            verticalAlignment = Alignment.CenterVertically,
            // `:root .topbar { gap: space-lg }`; Studio `gap: 1.5rem`.
            horizontalArrangement = Arrangement.spacedBy(if (studio) 24.dp else t.css.spaceLg),
        ) {
            val rail = layout.railWidth
            when {
                studio && rail != null -> TopbarBrand(
                    Modifier
                        .width(rail.dp)
                        .fillMaxHeight()
                        .drawBehind { drawRect(StudioDrawer.background) }
                        .padding(horizontal = 21.6.dp),
                    studioDesktop = true,
                    ink = StudioBrandInk,
                )
                // Collapsed rail: auto width, transparent, `--white` ink; the inline padding stays (428).
                studio -> TopbarBrand(Modifier.padding(horizontal = 21.6.dp), studioDesktop = true)
                rail != null -> TopbarBrand(Modifier.width((rail.dp - t.css.spaceXl).coerceAtLeast(0.dp)))
                else -> TopbarBrand()
            }
            if (studio && layout.viewportWidth > ExpandedBreakpoints.STUDIO_CAPTION_ABOVE) {
                // `.studio-topbar-title`: Layers2 15px + "Workspace", --muted 0.8rem, gap 0.65rem.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.4.dp),
                ) {
                    Icon(TetherIcons.Layers2, contentDescription = null, tint = t.muted, modifier = Modifier.size(15.dp))
                    Text("Workspace", color = t.muted, maxLines = 1, style = cssText(type.ui, 0.8f, 400))
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // `:root .topbar-actions { gap: space-md }`; Studio 0.75rem.
                horizontalArrangement = Arrangement.spacedBy(t.css.spaceMd),
            ) {
                ConnectionReadout(layout.link)
                ExpandedToolBank(actions, unseenWarnings, fileBrowserDisabled, labels)
                ExpandedLockKey(actions.onLogout)
            }
        }
    }
}

/** Studio's rail-block wordmark ink (studio.css 427: `color: #f0f4fc`). */
private val StudioBrandInk = Color(0xFFF0F4FC)

/**
 * `.connection-state` (role="status"): the link as an etched readout — 1.9rem, `--mineral-deep`
 * well, `1px --line-strong`, `--radius-key`, mono 0.6rem / 700 / 0.08em upper-case `--muted`,
 * `--running` while connected (globals.css 10892-10908). Studio drops the plate: 550 0.72rem UI
 * type (studio.css 291). The glyph turns once per 1.2s while (re)connecting (798), static under
 * reduced motion. The word always carries the state, never the colour alone.
 */
@Composable
private fun ConnectionReadout(link: LinkReadout) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val studio = t.studio
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
    val plate = if (studio) {
        Modifier
    } else {
        Modifier
            .cssSurface(RoundedCornerShape(t.radiusKey), t.mineralDeep, CssBorder(1.dp, t.lineStrong), t.css.well)
            .padding(start = 10.6.dp, end = 12.2.dp) // 0.6rem / 0.7rem + the 1px edge
    }
    Row(
        Modifier
            .height(30.4.dp)
            .then(plate)
            .semantics {
                contentDescription = link.word
                liveRegion = LiveRegionMode.Polite
            }
            .testTag(ShellTags.ConnectionReadout),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.2.dp),
    ) {
        Icon(
            if (link == LinkReadout.Connected) TetherIcons.ShieldCheck else TetherIcons.RotateCcw,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(14.dp).rotate(angle),
        )
        Text(
            if (studio) link.word else link.word.uppercase(),
            color = ink,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics { },
            style = if (studio) cssText(type.ui, 0.72f, 550) else cssText(type.mono, 0.6f, 700, trackingEm = 0.08f),
        )
    }
}

/**
 * `.topbar-keys` from 48rem. Instrument: the recessed four-key strip of the phone
 * (10912-10938); from 80rem each key prints its word — `width: auto; min-width: 2.75rem; gap:
 * 0.4rem; padding-inline: 0.7rem`, the word at 0.72rem / 600 (11844-11847). The Usage key is a
 * link (`<Link>`), so its word keeps the browser's underline in the instrument skins; Studio
 * removes it (studio.css 480). Studio: no strip, flat 2.75rem keys at 0.5rem radius, 0.2rem apart,
 * `padding-inline: 0.625rem` (288-289).
 */
@Composable
private fun ExpandedToolBank(actions: TopbarActions, unseenWarnings: Int, fileBrowserDisabled: Boolean, labels: Boolean) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val studio = t.studio
    val keyRadius = if (studio) 8.dp else t.radiusKey - 3.dp
    val look: (KeyState) -> ChromeLook = { state ->
        when (state) {
            KeyState.Pressed -> ChromeLook(t.keyFaceDeep, Color.Transparent, t.muted, t.css.bevelPressed, 1.dp, keyRadius)
            KeyState.Disabled -> ChromeLook(Color.Transparent, Color.Transparent, t.muted, emptyList(), 0.dp, keyRadius, alpha = 0.4f)
            KeyState.Rest -> ChromeLook(Color.Transparent, Color.Transparent, t.muted, emptyList(), 0.dp, keyRadius)
        }
    }
    val minWidth: Dp = when {
        studio || labels -> 44.dp
        else -> 34.4.dp
    }
    val height: Dp = if (studio) 44.dp else 34.4.dp
    val pad: Dp = when {
        studio -> 10.dp
        labels -> 11.2.dp
        else -> 0.dp
    }
    val labelStyle = cssText(type.ui, 0.72f, 600)
    val strip = if (studio) {
        Modifier
    } else {
        Modifier
            .cssSurface(RoundedCornerShape(t.radiusKey), t.keyFaceDeep, CssBorder(1.dp, t.lineStrong), t.css.well)
            .padding(3.dp)
    }
    Row(
        Modifier
            .semantics { contentDescription = "Console tools" }
            .then(strip),
        horizontalArrangement = Arrangement.spacedBy(if (studio) 3.2.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun key(
            icon: androidx.compose.ui.graphics.vector.ImageVector,
            word: String,
            name: String,
            host: (() -> Unit)?,
            enabled: Boolean = host != null,
            modifier: Modifier = Modifier,
            underline: Boolean = false,
            stateDescription: String? = null,
            overlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
        ) = ChromeLabelKey(
            onClick = { host?.invoke() },
            icon = icon,
            label = if (labels) word else null,
            contentDescription = name,
            look = look,
            minWidth = minWidth,
            height = height,
            iconSize = 16.dp,
            labelStyle = labelStyle,
            padStart = pad,
            padEnd = pad,
            gap = 6.4.dp,
            underline = underline && !studio,
            enabled = enabled,
            stateDescription = stateDescription,
            modifier = modifier,
            overlay = overlay,
        )
        key(TetherIcons.FolderOpen, "Files", "Browse workspace files", actions.onOpenFiles,
            enabled = actions.onOpenFiles != null && !fileBrowserDisabled, modifier = Modifier.testTag(ShellTags.FilesKey))
        key(TetherIcons.Gauge, "Accounts", "Account usage", actions.onOpenUsage)
        key(TetherIcons.ChartColumn, "Usage", "Usage analytics", actions.onOpenUsageAnalytics, underline = true)
        key(
            TetherIcons.Activity, "Health", "Health & event log", actions.onOpenLog,
            modifier = Modifier.testTag(ShellTags.LogKey),
            stateDescription = if (unseenWarnings > 0) "$unseenWarnings warnings" else null,
            overlay = if (unseenWarnings > 0) {
                { WarningBadge(unseenWarnings, Modifier.align(Alignment.TopEnd)) }
            } else {
                null
            },
        )
    }
}

/**
 * `.logout-button` from 48rem: the glyph and "Lock" (4029-4031). Instrument: 2.15rem tall,
 * `padding: 0 0.7rem 0 0.6rem`, `--radius-key`, 0.72rem / 650, gap `space-sm` (801-805,
 * 10941-10948); Studio: 2.75rem square minimum with 0.5rem padding (studio.css 293). Pressed seats
 * it like every icon control (8981-8989).
 */
@Composable
private fun ExpandedLockKey(onLogout: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val studio = t.studio
    ChromeLabelKey(
        onClick = onLogout,
        icon = TetherIcons.LogOut,
        label = "Lock",
        contentDescription = "Lock",
        look = rememberIconLook(t.muted, t.radiusKey),
        minWidth = 44.dp,
        height = if (studio) 44.dp else 34.4.dp,
        iconSize = 16.dp,
        labelStyle = cssText(type.ui, 0.72f, 650),
        padStart = if (studio) 8.dp else 9.6.dp,
        padEnd = if (studio) 8.dp else 11.2.dp,
        gap = t.css.spaceSm,
        modifier = Modifier.testTag(ShellTags.LockKey),
    )
}
