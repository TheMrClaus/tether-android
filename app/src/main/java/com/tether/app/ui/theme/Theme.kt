package com.tether.app.ui.theme

import android.app.Activity
import android.content.ContentResolver
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.core.view.WindowCompat

/**
 * True when the OS animator duration scale is zero (animations disabled).
 * Ambient motion (waiting pings, spinners, caret blink) checks this.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

private fun reducedMotion(resolver: ContentResolver): Boolean = try {
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
} catch (_: Exception) {
    false
}

/** Minimal Material3 interop mapping; components read [LocalTetherTokens] directly. */
private fun interopScheme(t: TetherTokens): ColorScheme {
    val base = if (t.family.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = t.violetStrong,
        onPrimary = t.accentInk,
        background = t.mineral,
        onBackground = t.ink,
        surface = t.graphite,
        onSurface = t.ink,
        surfaceVariant = t.graphiteRaised,
        onSurfaceVariant = t.muted,
        outline = t.line,
        outlineVariant = t.lineStrong,
        error = t.danger,
        surfaceContainer = t.graphite,
        surfaceContainerHigh = t.graphiteRaised,
        surfaceContainerHighest = t.graphiteRaised,
        surfaceContainerLow = t.graphite,
        scrim = t.scrim.copy(alpha = 1f),
    )
}

@Composable
fun TetherTheme(
    choice: ThemeChoice = ThemeChoice.System,
    content: @Composable () -> Unit,
) {
    val family = choice.resolve(isSystemInDarkTheme())
    val tokens = tokensFor(family)
    val view = LocalView.current
    val context = LocalContext.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Edge-to-edge: bars are transparent; our own graphite surfaces draw
            // behind them. We only steer icon contrast per family.
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !family.isDark
            controller.isAppearanceLightNavigationBars = !family.isDark
        }
    }

    val reduced = if (view.isInEditMode) false else remember { reducedMotion(context.contentResolver) }

    val defaults = Typography()
    val typography = Typography(
        bodyLarge = defaults.bodyLarge.copy(fontFamily = Manrope, fontWeight = TetherWeights.body),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = Manrope, fontWeight = TetherWeights.body),
        titleMedium = defaults.titleMedium.copy(fontFamily = Manrope, fontWeight = TetherWeights.strong),
        labelLarge = defaults.labelLarge.copy(fontFamily = Manrope, fontWeight = TetherWeights.label),
    )

    CompositionLocalProvider(
        LocalTetherTokens provides tokens,
        LocalReducedMotion provides reduced,
    ) {
        MaterialTheme(
            colorScheme = interopScheme(tokens),
            typography = typography,
            content = content,
        )
    }
}
