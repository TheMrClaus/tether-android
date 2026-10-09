package com.tether.app.net

import android.net.ConnectivityManager
import android.net.Network
import com.tether.app.client.DefaultNetworkWatch
import com.tether.app.client.TetherClient

/**
 * ta-nl5m (C4): the default-network callback, process-scoped (registered once by the Application and
 * never unregistered), so a network change inside the 3-minute background grace, with no Activity
 * alive, still replaces the live socket: one that was opened on the previous network is dead.
 *
 * It was registered by the root composable, so it died with the Activity. The first-network dedup
 * ([DefaultNetworkWatch], registering reports the current network first) lives here with it: exactly
 * one registration reports, exactly one watch decides, so a network change replaces the socket once.
 *
 * [client] is the client if one exists yet; this never creates it (a process woken for a push has no
 * reason to connect because a network came up).
 */
class ProcessNetworkWatch(
    private val client: () -> TetherClient?,
) {
    private val watch = DefaultNetworkWatch<Network>()

    internal val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Always seen by the watch, so the first network (reported on registration) is not a change.
            val changed = watch.available(network)
            val target = client() ?: return
            if (changed) target.onDefaultNetworkChanged() else target.reconnectIfIdle()
        }

        override fun onLost(network: Network) {
            watch.lost(network)
        }
    }

    /** Registers the callback once; a restricted context only loses the early reconnect (resume covers it). */
    fun register(manager: ConnectivityManager?) {
        try {
            manager?.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            // Missing permission or restricted context: reconnect still happens on resume.
        }
    }
}
