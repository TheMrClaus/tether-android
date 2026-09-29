package com.tether.app.client

/**
 * T13.2 (SYNC_DESIGN §2.5, §4.1): how current the copy of one session the app shows is.
 *
 * Derived by the client from its connection, attach and verify state, never stored (only the
 * mirror's `last_verified_at` is). Here in core:data, next to the mirror, so the design system's
 * indicator primitive can read it without depending on the network layer.
 */
enum class Freshness {
    /** Connected, attached on this connection, and a snapshot on it confirmed the copy. The unmarked default. */
    Live,

    /** Connected and attached (or re-syncing after a gap), but the snapshot is not in yet. */
    CatchingUp,

    /** Not verified on the current connection (offline, or not re-attached): a saved copy. */
    Saved,

    /** Nothing of the session's transcript is on the device. */
    NotDownloaded,
}

/**
 * One session's [freshness]. [lastVerifiedAt] (epoch ms) is the last time the copy was known to
 * match the server: the snapshot that confirmed it, or the moment a live copy stopped being
 * live. Null = never (or unknown). [partial] = a bounded snapshot's older turns are not on the
 * device ("Older turns not downloaded").
 *
 * A saved copy must never claim the agent is doing anything NOW: only [Freshness.Live] may.
 */
data class SessionSync(val freshness: Freshness, val lastVerifiedAt: Long?, val partial: Boolean = false)

/**
 * T13.2 r2 (SYNC_DESIGN §4.2 wired into T6.3's lock): the ONE rule for whether a session's shown
 * copy may drive a live action (an approval or question card, a Stop or Interrupt key, a session
 * control, End session). Every screen asks this; the client re-checks its own live set under its
 * lock before any frame leaves, so this only decides what is enabled.
 */
object LiveCopy {
    /**
     * Live only when the client confirmed [sessionId] on this connection ([liveSessions]) AND its
     * freshness says so:
     * - an entry ([sync]) must be [Freshness.Live]; every other value (Saved, CatchingUp,
     *   NotDownloaded, and any added later) is not live;
     * - a MISSING entry is not live whenever the client reports freshness at all
     *   ([reportsFreshness]): "nothing known" never unlocks a control;
     * - a client that reports no freshness ([reportsFreshness] false, the interface default) keeps
     *   the T6.3 rule: [liveSessions] alone decides.
     */
    fun isLive(sessionId: String?, liveSessions: Set<String>, sync: SessionSync?, reportsFreshness: Boolean): Boolean {
        if (sessionId == null || sessionId !in liveSessions) return false
        if (sync != null) return sync.freshness == Freshness.Live
        return !reportsFreshness
    }
}
