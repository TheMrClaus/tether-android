package com.tether.app.nav

import androidx.lifecycle.ViewModel
import com.tether.app.client.serverOrigin

/** What the app knows when it decides where a link goes. */
data class NavContext(
    /** A credential is stored and the server has not ended the sign-in (the shell is showing). */
    val signedIn: Boolean,
    /** The stored server URL, kept across logout to prefill sign-in. */
    val serverUrl: String?,
    /** The `ready` snapshot is in: the client publishes its session list before it reports Connected. */
    val connected: Boolean,
    val sessionIds: Set<String>,
) {
    val origin: String? get() = serverOrigin(serverUrl)
}

sealed interface NavEffect {
    /** Open this session (select it, as a sidebar pick does). */
    data class Open(val sessionId: String) : NavEffect

    /** Tell the user, in the app's toast, why the link went nowhere. */
    data class Notice(val text: String) : NavEffect
}

/**
 * The one navigation entry point (T4.4). Every source — the `tether://` filter, an http(s) link to
 * the paired server, a notification tap, a same-origin link tapped in a chat — ends up in [offer].
 *
 * A session link is held as the web holds `pendingSessionId` (components/dashboard.tsx): it waits
 * for the session list, is retired by any explicit selection ([onUserSelection]), and is fulfilled
 * once. Unlike the web, which waits indefinitely, a session the connected server does not list
 * lands on a notice and the current screen.
 *
 * Signed out, the link waits behind the login screen, bound to the server it was meant for: the
 * link's own origin (http(s)), else the stored server URL at the time it arrived. It is fulfilled
 * only after a sign-in to that same origin. The navigator never signs in, never changes the stored
 * server, and never touches the login form. A link that names no server while none is stored is
 * dropped after sign-in with a notice.
 */
class DeepLinkNavigator {
    private data class Pending(val sessionId: String, val origin: String?)

    private var pending: Pending? = null

    /** The session a link is waiting to open, if any (tests and diagnostics; never logged). */
    val pendingSessionId: String? get() = pending?.sessionId

    fun offer(link: ParsedLink?, context: NavContext): NavEffect? {
        when (link) {
            null -> return null
            is ParsedLink.Rejected -> {
                // Signed out, the login screen is the whole answer; nothing may be prefilled.
                return if (context.signedIn) NavEffect.Notice(noticeFor(link.reason)) else null
            }
            is ParsedLink.Open -> when (val destination = link.destination) {
                // Opening the app is all a Home link does. A waiting session link stays waiting,
                // as the web's notification click with url "/" leaves pendingSessionId alone.
                Destination.Home -> return null
                is Destination.Session -> {
                    pending = Pending(destination.id, link.origin ?: context.origin)
                    return step(context)
                }
            }
        }
    }

    /** Re-evaluate after the sign-in, the server, the connection or the session list changed. */
    fun step(context: NavContext): NavEffect? {
        val waiting = pending ?: return null
        if (!context.signedIn) return null
        if (waiting.origin == null) {
            pending = null
            return NavEffect.Notice(SIGNED_IN_OPEN_AGAIN)
        }
        if (waiting.origin != context.origin) {
            pending = null
            return NavEffect.Notice(OTHER_SERVER)
        }
        if (waiting.sessionId in context.sessionIds) {
            pending = null
            return NavEffect.Open(waiting.sessionId)
        }
        if (context.connected) {
            pending = null
            return NavEffect.Notice(SESSION_GONE)
        }
        return null
    }

    /** dashboard.tsx `selectActiveId`: an explicit selection retires the link's override. */
    fun onUserSelection() {
        pending = null
    }

    companion object {
        const val OTHER_SERVER = "That link is for a different Tether server."
        const val CANNOT_OPEN = "That link can't be opened in Tether."
        const val SESSION_GONE = "That session isn't on this server any more."
        const val SIGNED_IN_OPEN_AGAIN = "Signed in. Open the link again to go to that session."

        fun noticeFor(reason: LinkRejection): String = when (reason) {
            LinkRejection.OtherOrigin, LinkRejection.NoServer -> OTHER_SERVER
            LinkRejection.Malformed,
            LinkRejection.UnsupportedScheme,
            LinkRejection.UnknownRoute,
            LinkRejection.InvalidSessionId,
            -> CANNOT_OPEN
        }
    }
}

/** Holds the navigator across configuration changes, so a link waiting behind sign-in survives rotation. */
class NavigationViewModel : ViewModel() {
    val navigator = DeepLinkNavigator()
}
