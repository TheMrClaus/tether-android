package com.tether.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * PLAN D10: a phone renders the web's MOBILE layout (the web below its 47.9375rem breakpoint);
 * a window at WindowSizeClass "expanded" width or wider (≥ 840dp: tablets, unfolded foldables,
 * landscape) renders the web's DESKTOP layout. Primitives that change at the web's mobile
 * breakpoint (the docked sheet, the expand toggle) key off this one switch.
 */
enum class TetherLayoutClass { Phone, Expanded }

/** WindowSizeClass's expanded-width lower bound (androidx.window WIDTH_DP_EXPANDED_LOWER_BOUND). */
const val ExpandedWidthDp: Int = 840

fun layoutClassFor(widthDp: Int): TetherLayoutClass =
    if (widthDp >= ExpandedWidthDp) TetherLayoutClass.Expanded else TetherLayoutClass.Phone

/** The current window's layout class. */
@Composable
fun currentLayoutClass(): TetherLayoutClass = layoutClassFor(LocalConfiguration.current.screenWidthDp)
