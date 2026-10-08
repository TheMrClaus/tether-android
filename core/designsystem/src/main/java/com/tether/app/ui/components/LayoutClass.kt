package com.tether.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * PLAN D10 (ta-09ca, W20): the app switches exactly where the web does. A window narrower than the
 * web's 48rem breakpoint (768 dp: `(max-width: 47.9375rem)` mobile, session-sidebar.tsx:52 and
 * topbar.tsx:28; `(min-width: 48rem)` desktop, panel-resize-handle.tsx:33, tether 29537e0) renders
 * the web's MOBILE layout; 768 dp or wider renders the web's DESKTOP layout. Width only: the web
 * has no height, orientation or device condition, so neither does this. Primitives that change at
 * the web's mobile breakpoint (the docked sheet, the expand toggle) key off this one switch.
 *
 * Font scale (ta-7njx, W23): no breakpoint in the app has a font-scale term, as on the web. Verified against Chrome
 * Android 156.0.8078.25 (stable, 2026-10; also 155.0.8059.40): Chrome hands the Android font size
 * (Configuration.fontScale) to Blink only as accessibilityFontScaleFactor; HostZoomMapImpl.shouldAdjustForOSLevel() is
 * false (no page zoom), the text autosizer is gone, and tether 29537e0 declares no <meta name="text-scale">. So the
 * web console lays out at fontScale 2.0 exactly as at 1.0: innerWidth, rem and every px/rem @media stay put. Android
 * Display size (density) is the page-zoom analogue and already moves every dp breakpoint here.
 * Re-open ta-7njx if (1) the web adds <meta name="text-scale" content="scale">: rem breakpoints would then scale,
 * 48rem = 768 x fontScale, px ones would not; or (2) Chrome ships AdjustForOSLevel=true: page zoom, so px and rem
 * would both scale.
 */
enum class TetherLayoutClass { Phone, Expanded }

/** The web's 48rem breakpoint in dp: Expanded iff the (truncated) window width is at least this. */
const val ExpandedWidthDp: Int = 768

fun layoutClassFor(widthDp: Int): TetherLayoutClass =
    if (widthDp >= ExpandedWidthDp) TetherLayoutClass.Expanded else TetherLayoutClass.Phone

/**
 * The window width MainShell measured, handed down to every component it hosts. A Dialog or Popup
 * has its own LocalWindowInfo (sized to the dialog, or to its content), so reading the container
 * there would disagree with the shell; a composition local crosses that boundary. Null outside
 * MainShell (setup, login), where [windowWidthDp] falls back to the container read.
 */
val LocalWindowWidthDp: ProvidableCompositionLocal<Int?> = compositionLocalOf { null }

/**
 * The ONE width source (ta-09ca): the window's width in dp, truncated (Expanded iff the fractional
 * width is at least 768.0 dp). Inside MainShell it is the value MainShell measured; elsewhere the
 * window's own container size.
 */
@Composable
fun windowWidthDp(): Int =
    LocalWindowWidthDp.current
        ?: with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value.toInt() }

/** The current window's layout class. */
@Composable
fun currentLayoutClass(): TetherLayoutClass = layoutClassFor(windowWidthDp())

/**
 * Measures the window once and provides the width to everything under [content] (see
 * [LocalWindowWidthDp]). MainShell wraps itself in this; the read is taken outside the provider, so
 * it is the window's own container size.
 */
@Composable
fun ProvideWindowWidthDp(content: @Composable () -> Unit) {
    val measured = windowWidthDp()
    CompositionLocalProvider(LocalWindowWidthDp provides measured, content = content)
}
