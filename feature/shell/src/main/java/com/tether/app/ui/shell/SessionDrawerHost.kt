package com.tether.app.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens

/**
 * Studio paints the drawer in a fixed ink-blue finish in both lightings (studio.css 295-301:
 * `background: #141d2e`, and the scope's own `--line: #2b374b`, no right border).
 */
internal object StudioDrawer {
    val background = Color(0xFF141D2E)
}

/** `.session-sidebar` phone width: `min(20rem, 88vw)`; Studio `min(21rem, 92vw)` (studio.css 443). */
internal fun drawerWidth(studio: Boolean, viewportWidth: Dp): Dp =
    if (studio) minOf(336.dp, viewportWidth * 0.92f) else minOf(320.dp, viewportWidth * 0.88f)

/**
 * The phone drawer container: `.session-sidebar` below 48rem plus `.drawer-backdrop`
 * (globals.css 822-846; Studio studio.css 295-301, 443). A fixed left panel on `--graphite` with a
 * `1px --line` right edge, padded `max(space-md, safe-top) space-md max(space-md, safe-bottom)`
 * (Studio: `max(1rem, safe-top) 0.875rem 0.75rem`), sliding from `translateX(-102%)` over
 * `--duration` / `--ease-out`; the open drawer sits over a full-screen `--scrim` backdrop that
 * closes it ("Close sessions"). Both cover the topbar (z-backdrop / z-modal above z-sticky).
 *
 * [content] is the session list (T5.1, today's SessionDrawer). While closed the panel stays
 * composed (its state survives, like the web's always-mounted sidebar) but is hidden from touch
 * and accessibility, unlike the web's off-canvas transform which TalkBack could still reach.
 */
@Composable
fun SessionDrawerHost(
    open: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val studio = t.studio
    val slide by animateFloatAsState(
        targetValue = if (open) 0f else -1.02f,
        animationSpec = if (reduced) snap() else tween(t.css.duration, easing = t.css.easeOut.toEasing()),
        label = "drawerSlide",
    )
    val shown = open || slide > -1.02f
    BoxWithConstraints(modifier.fillMaxSize().zIndex(10f)) {
        if (open) {
            // `.drawer-backdrop`: rendered only while open (dashboard.tsx:1362), so it appears at once.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(t.scrim)
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)
                    .clearAndSetSemantics {
                        contentDescription = "Close sessions"
                        role = Role.Button
                    }
                    .testTag(ShellTags.DrawerBackdrop),
            )
        }
        val width = drawerWidth(studio, maxWidth)
        val edge = t.line
        val insetTop = if (studio) 16.dp else t.css.spaceMd
        val insetSide = if (studio) 14.dp else t.css.spaceMd
        val insetBottom = t.css.spaceMd
        Box(
            Modifier
                .width(width)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = slide * size.width
                    alpha = if (shown) 1f else 0f
                }
                .drawBehind {
                    drawRect(if (studio) StudioDrawer.background else t.graphite)
                    if (!studio) drawRect(edge, Offset(size.width - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height))
                }
                .then(
                    if (open) {
                        Modifier
                            .pointerInput(Unit) { detectTapGestures { } } // taps on the panel never reach the backdrop
                            .semantics { paneTitle = "Sessions" }
                    } else {
                        Modifier.clearAndSetSemantics { }
                    },
                )
                .padding(end = if (studio) 0.dp else 1.dp)
                // max(padding, safe area) per side; consuming the insets here keeps the content's
                // own statusBars/navigationBars padding from doubling them.
                .windowInsetsPadding(
                    WindowInsets.statusBars.only(WindowInsetsSides.Top).union(WindowInsets(top = insetTop))
                        .union(
                            if (studio) {
                                WindowInsets(bottom = insetBottom)
                            } else {
                                WindowInsets.navigationBars.only(WindowInsetsSides.Bottom).union(WindowInsets(bottom = insetBottom))
                            },
                        ),
                )
                .padding(horizontal = insetSide)
                .testTag(ShellTags.Drawer),
        ) {
            content()
        }
    }
}
