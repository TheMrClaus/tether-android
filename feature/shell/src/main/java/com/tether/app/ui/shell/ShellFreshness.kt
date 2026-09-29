package com.tether.app.ui.shell

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.tether.app.client.ConnectionState
import com.tether.app.client.SessionSync
import com.tether.app.ui.components.LinkBanner

/**
 * T13.2 (SYNC_DESIGN §4.1-4.2, native-only: no web reference): what the shell shows about how
 * current things are. [banner] = the link banner under the topbar (null while connected);
 * [syncStates] = the client's per-session freshness; [listLive] = the session list is current (a
 * live connection), so the header's status pill may read as now; [now] = the clock for the ages.
 *
 * The default knows nothing: no banner, no marks, and the list taken as live (every existing
 * screen and golden renders exactly as before).
 */
@Immutable
data class ShellFreshness(
    val banner: LinkBanner? = null,
    val syncStates: Map<String, SessionSync> = emptyMap(),
    val listLive: Boolean = true,
    val now: Long = 0L,
) {
    companion object {
        val None = ShellFreshness()

        /** SYNC_DESIGN §4.1: Connecting reads "Reconnecting…", any other non-connected state "Offline". */
        fun bannerFor(connection: ConnectionState): LinkBanner? = when (connection) {
            ConnectionState.Connected -> null
            ConnectionState.Connecting -> LinkBanner.Reconnecting
            // Sign-in and version screens replace the shell; nothing to say about copies there.
            ConnectionState.AuthRequired, is ConnectionState.VersionMismatch -> null
            ConnectionState.Disconnected, ConnectionState.LocalNetworkBlocked -> LinkBanner.Offline
        }
    }
}

val LocalShellFreshness = staticCompositionLocalOf { ShellFreshness.None }
