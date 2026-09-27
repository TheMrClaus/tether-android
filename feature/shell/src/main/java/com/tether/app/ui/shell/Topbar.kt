package com.tether.app.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.KeyState
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.hardShadow
import com.tether.app.ui.components.softShadow
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTokens

/** `.dashboard-shell` phone row: `grid-template-rows: calc(3.5rem + env(safe-area-inset-top))` (both families). */
val TopbarHeight: Dp = 56.dp

/**
 * `.dashboard-shell` desktop row (≥ 48rem): `grid-template-rows: 3rem …` (globals.css 3960);
 * Studio keeps `calc(4rem + safe-top)` (studio.css 280).
 */
val ExpandedTopbarHeight: Dp = 48.dp
val StudioExpandedTopbarHeight: Dp = 64.dp

/** The topbar's link readout (`.connection-state`, topbar.tsx:36-39): its word and whether it spins. */
enum class LinkReadout(val word: String) {
    Connected("Secure link"),
    Connecting("Connecting"),
    Reconnecting("Reconnecting"),
}

/**
 * The expanded layout's topbar inputs: the rail's rendered width (the brand shares its vertical,
 * globals.css 3978-3987; null while the rail is collapsed), the viewport width for the 74rem /
 * 80rem breakpoints, and the link state the readout prints.
 */
data class ExpandedTopbar(val railWidth: Int?, val viewportWidth: Int, val link: LinkReadout)

/** The topbar's host actions (topbar.tsx props). A null action has no host yet: its key renders disabled. */
data class TopbarActions(
    val onOpenDrawer: () -> Unit,
    val onOpenFiles: (() -> Unit)? = null,
    val onOpenUsage: (() -> Unit)? = null,
    val onOpenUsageAnalytics: (() -> Unit)? = null,
    val onOpenLog: (() -> Unit)? = null,
    val onLogout: () -> Unit,
)

/**
 * `<header className="topbar">` at the phone layout (components/topbar.tsx): menu key, brand, and
 * the right-anchored action cluster — the four-key tool bank and the Lock key. The connection
 * readout (`.connection-state`) is `display: none` below 48rem and the Studio caption is hidden
 * below 74rem, so neither renders on a phone.
 *
 * Material: instrument skins — `--graphite` bar, `1px --line-strong` parting line, a lit top lip
 * and a `--seam-lip` shade below (globals.css 10853-10858); Studio — flat, `1px --line`
 * (studio.css 281, 436). The status-bar inset is the bar's own top padding (globals.css 684).
 */
@Composable
fun TetherTopbar(
    actions: TopbarActions,
    modifier: Modifier = Modifier,
    unseenWarnings: Int = 0,
    fileBrowserDisabled: Boolean = false,
    /** Non-null: the desktop topbar ([ExpandedTetherTopbar]). */
    expanded: ExpandedTopbar? = null,
) {
    if (expanded != null) {
        ExpandedTetherTopbar(actions, expanded, modifier, unseenWarnings, fileBrowserDisabled)
        return
    }
    val t = LocalTetherTokens.current
    val studio = t.studio
    val edge = if (studio) t.line else t.lineStrong
    val shadows = if (studio) emptyList() else listOf(hardShadow(1.dp, t.seamLip), hardShadow(1.dp, t.litSoft, inset = true))
    Box(
        modifier
            .fillMaxWidth()
            .zIndex(2f) // z-sticky: the seam shade paints over the workspace below
            .cssSurface(RectangleShape, t.graphite, shadows = shadows)
            .drawBehind { drawRect(edge, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
            .windowInsetsPadding(WindowInsets.statusBars)
            .testTag(ShellTags.Topbar),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(TopbarHeight)
                .padding(bottom = 1.dp) // the border-bottom is inside the 3.5rem row
                .padding(horizontal = if (studio) 10.dp else t.css.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            // `:root .topbar { gap: var(--space-lg) }`; Studio phone `gap: 0.4rem`.
            horizontalArrangement = Arrangement.spacedBy(if (studio) 6.4.dp else t.css.spaceLg),
        ) {
            ChromeIconKey(
                onClick = actions.onOpenDrawer,
                icon = TetherIcons.Menu,
                contentDescription = "Open sessions",
                look = rememberIconLook(t.ink, t.radiusSm),
                width = 44.dp,
                height = 44.dp,
                iconSize = 20.dp,
                modifier = Modifier.testTag(ShellTags.MenuKey),
            )
            TopbarBrand()
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (studio) 0.dp else t.css.spaceMd),
            ) {
                ToolBank(actions, unseenWarnings, fileBrowserDisabled)
                LockKey(actions.onLogout)
            }
        }
    }
}

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
    val studio = t.studio
    Row(
        modifier.clearAndSetSemantics {
            contentDescription = "Tether"
            heading()
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (studio && !studioDesktop) 8.dp else 11.2.dp),
    ) {
        BrandMark(t, if (studioDesktop) 29.6.dp else 25.6.dp)
        Text(
            if (studio) "tether" else "TETHER",
            color = ink ?: t.white,
            maxLines = 1,
            style = when {
                studioDesktop -> cssText(type.ui, 1.24f, 770, trackingEm = -0.04f)
                studio -> cssText(type.ui, 1.1f, 770, trackingEm = -0.04f)
                else -> cssText(type.ui, 0.74f, 740, trackingEm = 0.22f)
            },
        )
    }
}

@Composable
private fun BrandMark(t: TetherTokens, studioSize: Dp = 25.6.dp) {
    if (t.studio) {
        // 1.6rem tile (1.85rem from 48rem), 0.58rem radius, --accent; bars 0.19×0.72rem, 3px radius, white, both opaque.
        val shape = RoundedCornerShape(9.28.dp)
        Canvas(Modifier.size(studioSize).cssSurface(shape, t.accent)) {
            drawNeedle(Color.White, 3.04.dp.toPx(), 11.52.dp.toPx(), 3.dp.toPx(), -3.52.dp.toPx(), 5.12.dp.toPx(), 1f)
        }
    } else {
        // 1.55rem cap: 1px --key-side edge, --key-face, lit top bevel, side wall, contact shade.
        val shadows = listOf(
            hardShadow(1.dp, t.litStrong, inset = true),
            hardShadow(1.dp, t.keySide),
            softShadow(2.dp, 3.dp, t.contact.copy(alpha = 0.25f), spread = (-1).dp),
        )
        Canvas(Modifier.size(24.8.dp).cssSurface(CircleShape, t.keyFace, CssBorder(1.dp, t.keySide), shadows)) {
            drawNeedle(t.violet, 2.24.dp.toPx(), 8.dp.toPx(), 999f, -2.56.dp.toPx(), 4.8.dp.toPx(), 0.4f)
        }
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
 * `.topbar-keys`: one recessed strip holding four flush keys (globals.css 10912-10938) —
 * `--key-face-deep` floor, `1px --line-strong`, `--well` shadows, 2px padding and gap; each key
 * 2.15rem, `--radius-key - 3px`, `--muted`; pressed seats it (`translateY(1px)`, `--key-face-deep`,
 * `--bevel-pressed`); disabled 0.4. Studio drops the strip: flat 2.5rem × 2.75rem keys at 0.5rem
 * radius (studio.css 288-289, 440-441). `role="group"` "Console tools".
 */
@Composable
private fun ToolBank(actions: TopbarActions, unseenWarnings: Int, fileBrowserDisabled: Boolean) {
    val t = LocalTetherTokens.current
    val studio = t.studio
    val keyRadius = if (studio) 8.dp else t.radiusKey - 3.dp
    val look: (KeyState) -> ChromeLook = { state ->
        when (state) {
            KeyState.Pressed -> ChromeLook(t.keyFaceDeep, Color.Transparent, t.muted, t.css.bevelPressed, 1.dp, keyRadius)
            KeyState.Disabled -> ChromeLook(Color.Transparent, Color.Transparent, t.muted, emptyList(), 0.dp, keyRadius, alpha = 0.4f)
            KeyState.Rest -> ChromeLook(Color.Transparent, Color.Transparent, t.muted, emptyList(), 0.dp, keyRadius)
        }
    }
    val w = if (studio) 40.dp else 34.4.dp
    val h = if (studio) 44.dp else 34.4.dp
    val strip = if (studio) {
        Modifier
    } else {
        Modifier
            .cssSurface(RoundedCornerShape(t.radiusKey), t.keyFaceDeep, CssBorder(1.dp, t.lineStrong), t.css.well)
            .padding(3.dp) // 1px border + 2px padding
    }
    Row(
        Modifier
            .semantics { contentDescription = "Console tools" }
            .then(strip),
        horizontalArrangement = Arrangement.spacedBy(if (studio) 0.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val filesHost = actions.onOpenFiles
        ChromeIconKey(
            onClick = { filesHost?.invoke() },
            icon = TetherIcons.FolderOpen,
            contentDescription = "Browse workspace files",
            look = look, width = w, height = h, iconSize = 16.dp,
            enabled = filesHost != null && !fileBrowserDisabled,
            modifier = Modifier.testTag(ShellTags.FilesKey),
        )
        ChromeIconKey(
            onClick = { actions.onOpenUsage?.invoke() },
            icon = TetherIcons.Gauge,
            contentDescription = "Account usage",
            look = look, width = w, height = h, iconSize = 16.dp,
            enabled = actions.onOpenUsage != null,
        )
        ChromeIconKey(
            onClick = { actions.onOpenUsageAnalytics?.invoke() },
            icon = TetherIcons.ChartColumn,
            contentDescription = "Usage analytics",
            look = look, width = w, height = h, iconSize = 16.dp,
            enabled = actions.onOpenUsageAnalytics != null,
        )
        ChromeIconKey(
            onClick = { actions.onOpenLog?.invoke() },
            icon = TetherIcons.Activity,
            contentDescription = "Health & event log",
            stateDescription = if (unseenWarnings > 0) "$unseenWarnings warnings" else null,
            look = look, width = w, height = h, iconSize = 16.dp,
            enabled = actions.onOpenLog != null,
            modifier = Modifier.testTag(ShellTags.LogKey),
            overlay = if (unseenWarnings > 0) {
                { WarningBadge(unseenWarnings, Modifier.align(Alignment.TopEnd)) }
            } else {
                null
            },
        )
    }
}

/**
 * `.topbar-badge`: absolute at 0.125rem from the key's top-right, 0.9rem pill, `--danger` with
 * `--accent-ink` legend and a `--brick-side` lip (globals.css 4624-4630, 9142). The count is also
 * the log key's state description, so it is never carried by the red dot alone.
 */
@Composable
internal fun WarningBadge(count: Int, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Box(
        modifier
            .offset(x = (-2).dp, y = 2.dp)
            .heightIn(min = 14.4.dp)
            .widthIn(min = 14.4.dp)
            .cssSurface(RoundedCornerShape(percent = 50), t.danger, shadows = listOf(hardShadow(1.dp, t.brickSide)))
            .padding(horizontal = 3.2.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 9) "9+" else "$count",
            color = t.accentInk,
            textAlign = TextAlign.Center,
            style = cssText(type.ui, 0.54f, 700, lineHeight = 1f),
        )
    }
}

/**
 * `.logout-button` — "Lock". Its word is `display: none` below 48rem, so the phone shows the
 * glyph alone; the app still names it "Lock" for TalkBack (the web's hidden span leaves the
 * button nameless). Instrument: 2.15rem tall, `--radius-key` (globals.css 10941-10948);
 * Studio: 2.5rem × 2.75rem (studio.css 293, 442).
 */
@Composable
private fun LockKey(onLogout: () -> Unit) {
    val t = LocalTetherTokens.current
    val studio = t.studio
    ChromeIconKey(
        onClick = onLogout,
        icon = TetherIcons.LogOut,
        contentDescription = "Lock",
        look = rememberIconLook(t.muted, if (studio) t.radiusSm else t.radiusKey),
        width = if (studio) 40.dp else 44.dp,
        height = if (studio) 44.dp else 34.4.dp,
        iconSize = 16.dp,
        modifier = Modifier.testTag(ShellTags.LockKey),
    )
}
