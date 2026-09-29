package com.tether.app.ui.shell

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.tether.app.client.ConnectionState
import com.tether.app.client.LiveCopy
import com.tether.app.client.SessionSync
import com.tether.app.ui.components.FreshnessCopy
import com.tether.app.ui.components.LinkBanner

/**
 * T13.2 (SYNC_DESIGN §4.1-4.2, native-only: no web reference): what the shell shows about how
 * current things are. [banner] = the link banner under the topbar (null while connected);
 * [syncStates] = the client's per-session freshness; [listLive] = the session list is current (a
 * live connection), so the header's status pill may read as now; [liveSessions] = the sessions the
 * client confirmed on this connection; [reportsFreshness] = the client reports freshness at all
 * (a missing entry is then not live); [now] = the clock for the ages.
 *
 * r2: the default knows nothing, so it claims nothing: the list is NOT taken as live (a pill reads
 * "Was running"), and no session is live (End session is disabled). A host that knows better says
 * so ([MainShell] from the client; tests of the live shell explicitly).
 */
@Immutable
data class ShellFreshness(
    val banner: LinkBanner? = null,
    val syncStates: Map<String, SessionSync> = emptyMap(),
    val listLive: Boolean = false,
    val now: Long = 0L,
    val liveSessions: Set<String> = emptySet(),
    val reportsFreshness: Boolean = true,
) {
    /**
     * r2: may [sessionId]'s own controls (the header's End session) act: the list is live AND the
     * session's copy is live ([LiveCopy.isLive]).
     */
    fun sessionLive(sessionId: String): Boolean =
        listLive && LiveCopy.isLive(sessionId, liveSessions, syncStates[sessionId], reportsFreshness)

    /**
     * r2 (SYNC_DESIGN §4.2): the words qualifying a reading taken from [sessionId]'s copy (the
     * context gauge, the statusline) while it is not live; null while it is. A missing entry reads
     * as a saved copy.
     */
    fun staleLabel(sessionId: String): String? =
        if (sessionLive(sessionId)) null else FreshnessCopy.sessionLabel(syncStates[sessionId], now) ?: FreshnessCopy.SAVED

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
