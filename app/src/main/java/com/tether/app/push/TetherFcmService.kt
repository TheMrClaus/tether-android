package com.tether.app.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM messages (T12.1: parity with tether `lib/fcm-push.mjs` and
 * `lib/push-notifications.mjs` at protocol v130).
 *
 * The server sends two shapes:
 * - **Visible**: a `notification {title, body}` plus `data {url, kind, tag}`.
 *   The kinds are approval, question, turn_end and resume_choice, and the copy
 *   is generic. `url` is always the id-free `/` (the FCM privacy floor: Google
 *   can read the payload, so it never names a session). While the app is in the
 *   background, FCM shows these itself, on the manifest's default channel and
 *   with the payload's visibility (none today, so Android's private default),
 *   and this method is not called. In the foreground it is called, and the app
 *   posts on the per-kind channel, with its own lock-screen rules
 *   ([PushNotifier]).
 * - **Sync hint** (v130, S13.1): data-only `{kind:"sync", v:"1"}`, sent only to
 *   devices that opted in with `syncHints`. It carries no content and never
 *   posts a notification. T13.4 turns it into the background catch-up; until
 *   then it is ignored.
 *
 * Nothing from a message is logged: neither the text nor the tag.
 *
 * Foreground suppression: when the app is in the foreground and the message's
 * tag matches [ForegroundState.activeTag], nothing is posted. This mirrors what
 * the web service worker gets from `clients.matchAll`.
 */
class TetherFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val notification = remoteMessage.notification
        handle(this, PushMessageParser.parse(remoteMessage.data, notification?.title, notification?.body))
    }

    // Deprecated in firebase-messaging 25.1.0 in favour of onRegistered(). The
    // migration is blocked on a server change (T12.1 research: register() yields
    // a Firebase Installation ID, not the FCM registration token the server's FCM
    // v1 `message.token` gets today, and it needs a manifest flag that turns
    // getToken() off). Kept, narrowly suppressed, until that S-task lands.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        // The token itself is not passed on or logged: the registrar reads the
        // current one from Firebase when it re-registers.
        PushController.handleNewToken()
    }

    companion object {
        /**
         * Acts on a parsed message. Split out of [onMessageReceived] so the
         * routing is testable without a Firebase-built [RemoteMessage].
         * Returns true when a notification was posted.
         */
        internal fun handle(context: Context, message: PushMessage): Boolean = when (message) {
            // T13.4 hook: enqueue the unique catch-up work here. It must stay
            // notification-free (SYNC_DESIGN §6.2) and read nothing but `kind`.
            PushMessage.SyncHint -> false
            PushMessage.Ignored -> false
            is PushMessage.Visible -> {
                val suppressed = ForegroundState.isForeground &&
                    message.tag != null &&
                    ForegroundState.activeTag == message.tag
                !suppressed && PushNotifier.post(context, message)
            }
        }
    }
}

/**
 * Process-wide foreground signal read by [TetherFcmService.handle] and written
 * by [PushController] (which observes `ProcessLifecycleOwner`). `activeTag` is
 * the notification tag the selected session would raise, so a push for the
 * session the user is already looking at is dropped.
 */
object ForegroundState {
    @Volatile var isForeground: Boolean = false
    @Volatile var activeTag: String? = null
}
