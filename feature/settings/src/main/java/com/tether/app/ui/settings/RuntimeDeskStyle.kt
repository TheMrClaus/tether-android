package com.tether.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.SkinTokens
import com.tether.app.ui.theme.TokenScope

internal object RuntimeDeskTags {
    const val TopBar = "runtime-desk-top-bar"
}

internal object RuntimeDeskStyle {
    val ExpandedBreakpoint = 768.dp
    val DestinationWidth = 192.dp
    val EntityWidth = 220.dp
    val HeaderHeight = 54.dp
    val TouchTarget = 48.dp
    val PanePadding = 12.dp
    val ItemRadius = 8.dp
}

/** The Vector rail palette (`--runtime-nav: var(--bg-rail)`), locally scoped so Settings does not depend on feature:sidebar. */
internal val RuntimeDeskRailScope = TokenScope(".runtime-desk-destinations") { t: SkinTokens ->
    t.copy(
        mineral = t.bgRail,
        mineralDeep = t.bgPanel,
        graphite = t.bgRail,
        graphiteRaised = t.bgRaised,
        line = t.border,
        lineStrong = t.borderStrong,
        white = t.inkStrong,
        muted = t.inkMuted,
        faint = t.inkMuted,
        violet = t.accent,
        violetStrong = t.accent,
        violetWash = t.accentSoft,
        keyFace = t.bgRaised,
        keyFaceHover = t.bgPanel,
        keyFaceDeep = t.bgSubtle,
        keySide = t.borderStrong,
    )
}

@Composable
internal fun runtimeDeskTitleStyle(): TextStyle =
    settingsText(LocalTetherTypography.current.ui, 20f, 700, lineHeight = 1.3f, trackingEm = -0.025f)

@Composable
internal fun runtimeDeskPaneTitleStyle(): TextStyle =
    settingsText(LocalTetherTypography.current.ui, 16f, 700, lineHeight = 1.35f, trackingEm = -0.015f)

@Composable
internal fun runtimeDeskItemStyle(): TextStyle =
    settingsText(LocalTetherTypography.current.ui, 14f, 650, lineHeight = 1.45f)

@Composable
internal fun runtimeDeskSupportingStyle(): TextStyle =
    settingsText(LocalTetherTypography.current.ui, 12f, 400, lineHeight = 1.55f)

@Composable
internal fun runtimeDeskStateStyle(): TextStyle =
    settingsText(LocalTetherTypography.current.ui, 11f, 600, lineHeight = 1.45f)
