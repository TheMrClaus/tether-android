package com.tether.app.client

import com.tether.app.protocol.BrowserProfileInfo
import com.tether.app.protocol.ClientMessage

// v171 named browser profiles (lib/browser-profiles-ui.ts, hooks/use-tether.ts `browserProfiles`): one owner-grade request on
// the main socket, answered to the asking socket only by `browser_profiles` carrying the same requestId.

/** The first server protocol that answers `browser-profiles`; a server below it is never offered profiles and never sent the frame. */
const val BROWSER_PROFILES_MIN_PROTOCOL = 171

fun browserProfilesSupported(serverProtocol: Int?): Boolean = serverProtocol != null && serverProtocol >= BROWSER_PROFILES_MIN_PROTOCOL

/** One action of the request (the web's `BrowserProfilesRequest`). */
sealed interface BrowserProfilesRequest {
    data object List : BrowserProfilesRequest
    data class Create(val name: String) : BrowserProfilesRequest
    data class Rename(val profileId: String, val name: String) : BrowserProfilesRequest
    data class Delete(val profileId: String) : BrowserProfilesRequest

    /** The wire frame for this action, paired with [requestId]. */
    fun frame(requestId: String): ClientMessage.BrowserProfiles = when (this) {
        List -> ClientMessage.BrowserProfiles("list", requestId)
        is Create -> ClientMessage.BrowserProfiles("create", requestId, name = name)
        is Rename -> ClientMessage.BrowserProfiles("rename", requestId, name = name, profileId = profileId)
        is Delete -> ClientMessage.BrowserProfiles("delete", requestId, profileId = profileId)
    }
}

/** The answer (or a refusal-shaped stand-in when nothing could be sent or no answer came). */
data class BrowserProfilesReply(
    val ok: Boolean,
    val code: String? = null,
    val error: String? = null,
    val profiles: kotlin.collections.List<BrowserProfileInfo> = emptyList(),
) {
    companion object {
        const val UNAVAILABLE_TEXT = "Profiles are not available right now."
        const val NOT_SENT_TEXT = "The secure link is reconnecting. Nothing was changed."

        val Unavailable = BrowserProfilesReply(ok = false, error = UNAVAILABLE_TEXT)
        val NotSent = BrowserProfilesReply(ok = false, error = NOT_SENT_TEXT)
    }
}

/** How long a request waits for its reply (use-tether.ts `browserProfiles`: 10 s). */
const val BROWSER_PROFILES_TIMEOUT_MS = 10_000L
