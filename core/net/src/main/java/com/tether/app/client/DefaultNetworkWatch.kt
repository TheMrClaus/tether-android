package com.tether.app.client

/**
 * ta-coik.32 (R1): tells a CHANGE of the default network apart from the first one a callback
 * reports. [N] is the platform's network handle (android.net.Network, compared by equality).
 * The first network seen is not a change (registering the callback reports the current one);
 * another network becoming the default is, and so is the same one coming back after it was lost.
 */
class DefaultNetworkWatch<N : Any> {
    private var current: N? = null
    private var lostSinceAvailable = false

    /** `onAvailable`: true when [network] replaces a default network seen before. */
    @Synchronized
    fun available(network: N): Boolean {
        val previous = current
        val changed = previous != null && (previous != network || lostSinceAvailable)
        current = network
        lostSinceAvailable = false
        return changed
    }

    /** `onLost`: the default network [network] went away. */
    @Synchronized
    fun lost(network: N) {
        if (network == current) lostSinceAvailable = true
    }
}
