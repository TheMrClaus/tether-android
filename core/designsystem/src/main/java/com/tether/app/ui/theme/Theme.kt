package com.tether.app.ui.theme

import android.app.Activity
import android.content.ContentResolver
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsEndWidth
import androidx.compose.foundation.layout.windowInsetsStartWidth
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * True when the OS animator duration scale is zero (animations disabled).
 * Ambient motion (waiting pings, spinners, caret blink) checks this.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * A surface that fills the whole window, bars included (ta-1jj7: the phone's full-screen session drawer),
 * puts its colour here while it is shown: [SystemBarBackdrop] then paints the bars in it instead of the
 * skin's `chrome.graphite`, and the bar icons follow its luminance. Null (the default) is the skin's own.
 * [TetherTheme] provides one holder for the whole window; the surface clears it when it closes or leaves.
 */
val LocalSystemBarOverride = staticCompositionLocalOf<MutableState<Color?>> { mutableStateOf(null) }

/**
 * The web's `prefers-reduced-motion: reduce` (globals.css 4530-4539 collapses every animation and
 * transition to 0.01ms, one iteration). Android's equivalent is "Remove animations", which sets
 * the animator duration scale to 0; any other scale (including an unreadable setting) animates.
 */
fun isReducedMotion(animatorDurationScale: Float?): Boolean = animatorDurationScale == 0f

private fun reducedMotion(resolver: ContentResolver): Boolean = isReducedMotion(
    try {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (_: Exception) {
        null
    },
)

/** Minimal Material3 interop mapping; components read [LocalTetherTokens] directly. */
private fun interopScheme(t: TetherTokens): ColorScheme {
    val base = if (t.skin.isDark) darkColorScheme() else lightColorScheme()
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

/**
 * Material3 interop typography (dialogs, text fields, menus that read MaterialTheme): the web
 * roles, not Material's defaults. Tether components read [LocalTetherTypography] directly.
 */
internal fun materialTypography(t: TetherTypography): Typography = Typography(
    bodyLarge = t.body,
    bodyMedium = t.chatBody,
    titleMedium = t.screenTitle,
    labelLarge = t.keyLabel.style,
)

@Composable
fun TetherTheme(
    mode: ThemeMode = ThemeMode.Default,
    content: @Composable () -> Unit,
) {
    // `system` mode follows the device's dark setting, like the web's prefers-color-scheme.
    val skin = mode.resolve(isSystemInDarkTheme())
    val tokens = tokensFor(skin)
    val view = LocalView.current
    val context = LocalContext.current

    val barOverride = remember { mutableStateOf<Color?>(null) }
    if (!view.isInEditMode) SystemBarAppearance(skin, barOverride)

    val reduced = if (view.isInEditMode) false else remember { reducedMotion(context.contentResolver) }

    val tetherType = typographyFor(skin)

    CompositionLocalProvider(
        LocalTetherTokens provides tokens,
        LocalTetherTypography provides tetherType,
        LocalReducedMotion provides reduced,
        LocalSystemBarOverride provides barOverride,
    ) {
        MaterialTheme(
            colorScheme = interopScheme(tokens),
            typography = materialTypography(tetherType),
        ) {
            if (view.isInEditMode) content() else SystemBarBackdrop(skin.systemBarColor, barOverride, content)
        }
    }
}

/**
 * Edge-to-edge (targetSdk 35+ ignores window bar colours): the bar colour is painted by
 * [SystemBarBackdrop]; here we only steer icon contrast (from the skin's CSS color-scheme, or from the
 * luminance of a [LocalSystemBarOverride]), and keep the system from scrimming over our colour.
 * Its own scope, so a change of the override recomposes only this.
 */
@Composable
private fun SystemBarAppearance(skin: TetherSkin, barOverride: MutableState<Color?>) {
    val view = LocalView.current
    val override = barOverride.value
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val lightIcons = if (override != null) override.luminance() > 0.5f else !skin.isDark
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = lightIcons
        controller.isAppearanceLightNavigationBars = lightIcons
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
}

/**
 * Paints the status and navigation bar areas in the skin's `chrome.graphite` (the web's
 * theme-color: each skin's panel colour), over the edge-to-edge content, or in the
 * [LocalSystemBarOverride] colour while a full-window surface holds it.
 */
@Composable
private fun SystemBarBackdrop(skinColor: Color, barOverride: MutableState<Color?>, content: @Composable () -> Unit) {
    val color = barOverride.value ?: skinColor
    Box {
        content()
        Box(Modifier.align(Alignment.TopStart).fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(color))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars).background(color))
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().windowInsetsStartWidth(WindowInsets.navigationBars).background(color))
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().windowInsetsEndWidth(WindowInsets.navigationBars).background(color))
    }
}
