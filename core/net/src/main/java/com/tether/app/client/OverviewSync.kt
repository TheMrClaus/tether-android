package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.overview.OverviewClient
import com.tether.app.protocol.overview.OverviewClientState
import com.tether.app.protocol.overview.OverviewSubscription
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * T15.1: the v131 Overview feed's client state, apart from the connection code (like
 * [SearchSync]): hooks/use-tether.ts 325-335 (`overview`, `overviewSubRef`), 802-807 (the `ready`
 * re-subscribe), 1010-1017 (the frames), 1212 (socket close) and 1547-1564 (`subscribeOverview`,
 * `unsubscribeOverview`), folded by [OverviewClient].
 *
 * READ-ONLY: the only frames this class ever produces are `overview-subscribe` and
 * `overview-unsubscribe`. Nothing here attaches a session, marks one seen, answers or sends a turn.
 *
 * Not thread-safe by itself: RealTetherClient calls every method under its own lock, and [send]
 * puts a frame on the live, handshaken socket of that moment (or returns false), also under that
 * lock, so a wish and the frame that carries it can never be split by a socket change.
 */
internal class OverviewSync {
    val state = MutableStateFlow(OverviewClient.initial())

    /** use-tether.ts `overviewSubRef`: the live subscription wish (null = none), kept across a reconnect. */
    var wish: OverviewSubscription? = null
        private set

    private fun commit(next: OverviewClientState) {
        if (next != state.value) state.value = next
    }

    /**
     * use-tether.ts:1551 — record the wish; on a live, handshaken socket send it and wait for its
     * snapshot. Anything else (no socket, not handshaken yet) only records it: the `ready`
     * handler ([onReady]) sends it. Returns whether a frame went out.
     */
    fun subscribe(subscription: OverviewSubscription, send: (ClientMessage) -> Boolean): Boolean {
        wish = subscription
        if (!send(frameFor(subscription))) return false
        commit(OverviewClient.requested(state.value))
        return true
    }

    /** use-tether.ts:1557 — drop the wish; tell a live socket; keep the data for a quick return. */
    fun unsubscribe(send: (ClientMessage) -> Boolean) {
        if (wish == null) return
        wish = null
        send(ClientMessage.OverviewUnsubscribe)
        commit(OverviewClient.unsubscribed(state.value))
    }

    /** use-tether.ts:802-807 — a new socket starts unsubscribed: replace from a fresh snapshot first. */
    fun onReady(send: (ClientMessage) -> Boolean) {
        val subscription = wish ?: return
        if (send(frameFor(subscription))) commit(OverviewClient.requested(state.value))
    }

    /** use-tether.ts:1010-1017 — fold one frame; out of step re-subscribes (only with a wish, on a live socket). */
    fun onFrame(message: ServerMessage, now: Long, send: (ClientMessage) -> Boolean): Boolean {
        if (message !is ServerMessage.OverviewSnapshot && message !is ServerMessage.OverviewDelta) return false
        val fold = OverviewClient.applyFrame(state.value, message, now)
        val subscription = wish
        if (fold.resubscribe && subscription != null && send(frameFor(subscription))) {
            commit(OverviewClient.requested(fold.state))
        } else {
            commit(fold.state)
        }
        return true
    }

    /** use-tether.ts:1212 — the socket went: the data stays, marked stale. The wish survives for the next `ready`. */
    fun onSocketGone() = commit(OverviewClient.disconnected(state.value))

    /**
     * Another server, or signed out: that server's overview must never show, and no subscription
     * wish carries over to the next one (the screen subscribes again if it is still showing).
     */
    fun clear() {
        wish = null
        commit(OverviewClient.initial())
    }

    companion object {
        fun frameFor(subscription: OverviewSubscription) =
            ClientMessage.OverviewSubscribe(subscription.filters, subscription.page, subscription.pageSize)
    }
}
