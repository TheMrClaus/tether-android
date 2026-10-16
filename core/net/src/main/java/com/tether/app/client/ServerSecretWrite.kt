package com.tether.app.client

/**
 * v144 (use-tether.ts:1208-1213, 1940-1963; lib/console-credentials.ts): what became of one credential write
 * (`set-server-settings` with `currentPassword` + `requestId`). The server answers with `server-settings-result`;
 * the web's promise settles on the correlated one, or after [NO_REPLY_TIMEOUT_MS] with no reply, or at once when
 * the socket is not open.
 */
sealed interface ServerSecretOutcome {
    /** `ok: true`. */
    data object Saved : ServerSecretOutcome

    /** `ok: false`: [reason] (the web's fallback `weak`) and the server's own [message] (fallback "The change was refused."). */
    data class Refused(val reason: String, val message: String) : ServerSecretOutcome

    /** Not sent (no live socket for the server), or no result inside the window: the change may or may not have landed. */
    data object NoReply : ServerSecretOutcome

    companion object {
        /** The web's 20 s no-reply window (use-tether.ts:1950). */
        const val NO_REPLY_TIMEOUT_MS = 20_000L

        const val REFUSED_FALLBACK_REASON = "weak"
        const val REFUSED_FALLBACK_MESSAGE = "The change was refused."

        /** The client's own words for [NoReply] (use-tether.ts, verbatim). */
        const val NO_REPLY_MESSAGE = "No reply from the server — the change may not have been saved. Reopen Settings to check."
    }
}
