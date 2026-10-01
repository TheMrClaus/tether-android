package com.tether.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ta-abm (T8.1 slice 2): hooks/use-keyboard-inset.ts. How much of the window's bottom the on-screen
 * keyboard covers, 0 when none is up. The web measures it from the visual viewport (it shrinks when
 * the keyboard opens); the app reads [WindowInsets.ime], whose bottom is that height measured from
 * the window's bottom edge (so it includes the navigation bar the keyboard sits over, which an
 * edge-to-edge window also has to clear).
 *
 * The draft composer lifts its bottom controls by it and shrinks its text box while it is up
 * (globals.css `.chat-composer.is-keyboard-open`). A seam ([LocalKeyboardInset]) so a test or a
 * golden can open "the keyboard" without an IME.
 */
interface KeyboardInset {
    /** The keyboard's height over the window's bottom edge now; 0.dp when it is closed. */
    @Composable
    fun current(): Dp
}

/** The device's keyboard: [WindowInsets.ime]'s bottom, in dp. */
object ImeKeyboardInset : KeyboardInset {
    @Composable
    override fun current(): Dp {
        val density = LocalDensity.current
        val px = WindowInsets.ime.getBottom(density)
        return if (px > 0) with(density) { px.toDp() } else 0.dp
    }
}

/** A keyboard of a fixed height (tests and goldens; 0.dp = closed). */
class FixedKeyboardInset(private val height: Dp) : KeyboardInset {
    @Composable
    override fun current(): Dp = height
}

/** The keyboard inset the draft composer reads (the device's, unless a test provides another). */
val LocalKeyboardInset = staticCompositionLocalOf<KeyboardInset> { ImeKeyboardInset }
