package com.tether.app.client

import com.tether.app.protocol.overview.OverviewSubscription

/**
 * T15.1: when the Overview feed is subscribed — components/overview/overview.tsx 111-126 as a
 * small state machine the screen feeds, so the rule is testable without a UI:
 *
 * - subscribed only while the Overview is on screen ([visible]) AND the app is started
 *   (lifecycle ON_START..ON_STOP, the native twin of the web's `document.hidden`);
 * - a new filter / page / server re-sends the subscription (re-sending replaces it; a server
 *   switch empties the client's wish, so the new server is asked afresh);
 * - leaving, or ON_STOP, sends one unsubscribe.
 *
 * Whether the socket is live and handshaken is the client's concern: [TetherClient.subscribeOverview]
 * sends only on such a socket and otherwise records the wish for the next `ready`.
 * Main-thread only (the screen's effects call it).
 */
class OverviewFeedGate(private val client: TetherClient) {

    private data class Wanted(val subscription: OverviewSubscription, val server: String?)

    /** What was last asked of the client; null = unsubscribed (or never subscribed). */
    private var active: Wanted? = null

    /** Whether the client currently holds a subscription this gate asked for. */
    val subscribed: Boolean get() = active != null

    fun update(visible: Boolean, started: Boolean, subscription: OverviewSubscription, server: String?) {
        if (visible && started) {
            val wanted = Wanted(subscription, server)
            if (wanted == active) return
            active = wanted
            client.subscribeOverview(subscription)
        } else {
            leave()
        }
    }

    /** The Overview left the screen (or the app stopped): stop its pushes. */
    fun leave() {
        if (active == null) return
        active = null
        client.unsubscribeOverview()
    }
}
