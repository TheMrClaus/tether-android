package com.tether.app.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import com.tether.app.ui.sidebar.PhoneDrawer
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens

/**
 * Studio paints the drawer in a fixed ink-blue finish in both lightings (studio.css 295-301:
 * `background: #141d2e`, and the scope's own `--line: #2b374b`, no right border).
 */
internal object StudioDrawer {
    val background = Color(0xFF141D2E)
}

/**
 * The phone drawer's width: the whole window (ta-1jj7, owner-directed design: a full-screen panel).
 * It was the web's `min(21rem, 92vw)` clamp; the owner asked for the full width on a phone.
 */
internal fun drawerWidth(viewportWidth: Dp): Dp = viewportWidth

/**
 * The panel's slide, as a fraction of its width: 0 open, -1.02 closed. The panel enters from the
 * start edge, so in a right-to-left layout the closed side is the right one and the sign flips.
 */
internal fun drawerOffsetFraction(slide: Float, rtl: Boolean): Float = if (rtl) -slide else slide

/**
 * Whether the drawer is open, on the always-composed host ([ShellTags.DrawerHost]). A custom property,
 * so no accessibility service announces it: it is the tests' positive open/closed signal, where a
 * missing backdrop node could only say "not drawn".
 */
val DrawerOpenKey = SemanticsPropertyKey<Boolean>("DrawerOpen")

/**
 * The phone drawer container (ta-1jj7, owner-directed design: the compact full-screen drawer; ta-8znp:
 * the navigation-bar inset). An opaque full-window panel on Studio's ink-blue finish with no backdrop
 * (nothing is left uncovered to tap), sliding in from the start edge over `--duration` / `--ease-out`.
 * Closing is the panel's own "Close sessions" key, Back, and selecting a session.
 *
 * Each side is padded by the larger of the system bars, the display cutout and the design minimum
 * ([PhoneDrawer.Edge] at the top, start and end, [PhoneDrawer.Bottom] at the foot), as the web pads the
 * foot `max(space-md, safe-area-inset-bottom)` (globals.css 274). The keyboard is not part of it: the
 * shell puts the keyboard away while the drawer is open.
 *
 * [content] is the session list (T5.1, today's SessionDrawer). While closed the panel stays
 * composed (its state survives, like the web's always-mounted sidebar) but is hidden from touch
 * and accessibility, unlike the web's off-canvas transform which TalkBack could still reach.
 */
@Composable
fun SessionDrawerHost(
    open: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val slide by animateFloatAsState(
        targetValue = if (open) 0f else -1.02f,
        animationSpec = if (reduced) snap() else tween(t.css.duration, easing = t.css.easeOut.toEasing()),
        label = "drawerSlide",
    )
    val shown = open || slide > -1.02f
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .zIndex(10f)
            .semantics { this[DrawerOpenKey] = open }
            .testTag(ShellTags.DrawerHost),
    ) {
        val width = drawerWidth(maxWidth)
        Box(
            Modifier
                .width(width)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = drawerOffsetFraction(slide, rtl) * size.width
                    alpha = if (shown) 1f else 0f
                }
                .drawBehind {
                    drawRect(StudioDrawer.background)
                }
                .testTag(ShellTags.Drawer) // the whole panel, insets included
                .then(
                    if (open) {
                        Modifier
                            .pointerInput(Unit) { detectTapGestures { } } // the opaque panel keeps its taps off the chat behind it
                            .semantics { paneTitle = "Sessions" }
                    } else {
                        Modifier.clearAndSetSemantics { }
                    },
                )
                // max(design minimum, system bars, display cutout) per side; consuming the insets here
                // keeps the content's own inset padding from doubling them.
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .union(WindowInsets(left = PhoneDrawer.Edge, top = PhoneDrawer.Edge, right = PhoneDrawer.Edge, bottom = PhoneDrawer.Bottom)),
                ),
        ) {
            content()
        }
    }
}
