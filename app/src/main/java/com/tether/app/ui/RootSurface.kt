package com.tether.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import com.tether.app.client.ConnectionState

/** What [UiRoot] shows in place of the shell (ta-coik.36). */
internal enum class RootSurface {
    /** The stored settings have not been read yet: neither a sign-in nor a shell can be told. */
    Loading,

    /** Signed out, or the server said the sign-in is gone: the setup wizard / sign-in screen. */
    Setup,

    /** Signed in: the console. */
    Shell,
}

internal const val ROOT_LOADING_TAG = "root-loading"

/** The web's accessible name for its boot surface (dashboard.tsx: `aria-label="Loading Tether"`). */
internal const val ROOT_LOADING_LABEL = "Loading Tether"

/**
 * The cold-start surface: neutral to the eye, a named busy region to a screen reader. The web's
 * `<section aria-busy="true" aria-label="Loading Tether">`; Compose has no aria-busy, and the nearest
 * semantic is an indeterminate progress range, which TalkBack announces as "in progress".
 */
@Composable
internal fun RootLoadingSurface() {
    Box(
        Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = ROOT_LOADING_LABEL
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            }
            .testTag(ROOT_LOADING_TAG),
    )
}

/**
 * Before the store's first read the client says "signed out, no server" ([configured] false), which
 * is not the truth for a signed-in install: reading it as one flashed the sign-in screen at every
 * cold start. Until [settingsLoaded] nothing that depends on it is shown. A store that cannot be
 * read also sets [settingsLoaded] (with [configured] false: the client fails closed), so this never
 * waits forever and never shows a signed-in UI the store cannot back.
 */
internal fun rootSurfaceFor(settingsLoaded: Boolean, configured: Boolean, connection: ConnectionState): RootSurface = when {
    !settingsLoaded -> RootSurface.Loading
    !configured || connection is ConnectionState.AuthRequired -> RootSurface.Setup
    else -> RootSurface.Shell
}
