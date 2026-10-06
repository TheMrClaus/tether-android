package com.tether.app.ui

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
