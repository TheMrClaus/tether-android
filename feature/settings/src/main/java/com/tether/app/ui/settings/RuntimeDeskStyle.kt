package com.tether.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.SkinTokens
import com.tether.app.ui.theme.TokenScope

internal object RuntimeDeskStyle {
    val ExpandedBreakpoint = 768.dp
    val DestinationWidth = 192.dp
    val EntityWidth = 220.dp
    val HeaderHeight = 72.dp
    val TouchTarget = 48.dp
    val PanePadding = 12.dp
    val ItemRadius = 8.dp
}

/** The existing Studio ink-rail palette, locally scoped so Settings does not depend on feature:sidebar. */
internal val RuntimeDeskRailScope = TokenScope(".runtime-desk-destinations") { t: SkinTokens ->
    t.copy(
        mineral = Color(0xFF141D2E),
        mineralDeep = Color(0xFF111A2B),
        graphite = Color(0xFF141D2E),
        graphiteRaised = Color(0xFF1D2940),
        line = Color(0xFF2B374B),
        lineStrong = Color(0xFF3B4861),
        white = Color(0xFFEFF3FC),
        ink = Color(0xFFD7DFEF),
        muted = Color(0xFFA9B7CE),
        faint = Color(0xFF9CACC6),
        violet = Color(0xFFA7BDFF),
        violetStrong = Color(0xFF8EAAFF),
        violetWash = Color(0xFF263B66),
        keyFace = Color(0xFF1D2940),
        keyFaceHover = Color(0xFF293851),
        keyFaceDeep = Color(0xFF111A2B),
        keySide = Color(0xFF34425A),
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
