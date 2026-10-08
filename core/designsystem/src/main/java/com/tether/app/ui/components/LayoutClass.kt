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
