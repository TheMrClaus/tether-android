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
