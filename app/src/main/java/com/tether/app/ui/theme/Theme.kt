package com.tether.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Tether is dark-only by design; there is deliberately no light color scheme.
 */
private val TetherDarkColorScheme = darkColorScheme()

@Composable
fun TetherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TetherDarkColorScheme,
        content = content,
    )
}
