package com.tether.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tether.app.MainActivity
import com.tether.app.ui.TetherViewModel

/**
 * Receives FCM messages and posts them as system notifications. The server
 * sends both a `notification` field (title/body for the system UI) and a
 * `data` payload with `kind`, `url`, and `tag`. The `kind` discriminator picks
 * the notification channel:
 *
 *  - `approval` | `question` → [CHANNEL_EVENTS] (IMPORTANCE_HIGH)
 *  - `turn_end`              → [CHANNEL_COMPLETE] (IMPORTANCE_DEFAULT)
 *
 * The server-supplied `tag` is forwarded to `NotificationCompat` so duplicate
 * events collapse the same way the Web Push service worker collapses them.
 *
 * Foreground suppression: if the app is foregrounded AND the referenced session
 * is the one the user is currently looking at, the notification is suppressed
 * — mirroring what the web service worker gets for free via `clients.matchAll`.
 * The foreground signal is set by [PushController] / [TetherViewModel]; this
 * service only reads it.
 */
class TetherFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // The server sends both a `notification` field (title/body for the
        // system UI) and a `data` payload (kind/url/tag). Some FCM messages
        // arrive data-only (high-priority data messages), so fall back to the
        // data title/body when the notification field is absent.
        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"]?.takeIf { it.isNotEmpty() }
            ?: return
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"]?.takeIf { it.isNotEmpty() }
            ?: return
        val kind = remoteMessage.data["kind"] ?: ""
        val tag = remoteMessage.data["tag"]
        val url = remoteMessage.data["url"] ?: "/"

        // Foreground suppression: if the app is foregrounded and the
        // referenced session is the one the user is currently looking at, the
        // notification is suppressed — mirroring what the web service worker
        // gets for free via `clients.matchAll`.
        if (ForegroundState.isForeground && tag != null && ForegroundState.activeTag == tag) {
            return
        }

        val channelId = channelIdForKind(kind)
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(if (channelId == CHANNEL_EVENTS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        // The server-supplied tag is passed to NotificationManager.notify so
        // duplicate events for the same (kind, id) collapse to one notification.
        if (tag != null) builder.setGroup(tag)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // The deep link: the server sends url="/" for now; a future
            // session-specific url carries the session id here.
            putExtra(EXTRA_PUSH_URL, url)
            putExtra(EXTRA_PUSH_KIND, kind)
            if (tag != null) putExtra(EXTRA_PUSH_TAG, tag)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        builder.setContentIntent(pendingIntent)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(tag ?: DEFAULT_TAG, NOTIFICATION_ID, builder.build())
    }

    override fun onNewToken(token: String) {
        // Hand off to the controller, which re-syncs the registration with the
        // current prefs-derived scope + sets. The controller is wired from
        // TetherApp; if it is not yet up (process cold start before
        // Application.onCreate completes), FCM will retry onNewToken on the next
        // token refresh, so a dropped call here is recoverable.
        PushController.handleNewToken(this, token)
    }

    private fun channelIdForKind(kind: String): String = when (kind) {
        "approval", "question" -> CHANNEL_EVENTS
        "turn_end" -> CHANNEL_COMPLETE
        else -> CHANNEL_EVENTS
    }

    companion object {
        const val CHANNEL_EVENTS = "tether-events"
        const val CHANNEL_COMPLETE = "tether-complete"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_TAG = "tether-push"

        const val EXTRA_PUSH_URL = "tether.push.url"
        const val EXTRA_PUSH_KIND = "tether.push.kind"
        const val EXTRA_PUSH_TAG = "tether.push.tag"
        const val EXTRA_SESSION_ID = "tether.push.sessionId"

        /**
         * Register the two notification channels. Called from
         * [com.tether.app.TetherApp.onCreate] so the channels exist before any
         * FCM message can arrive. Safe to call multiple times — creating an
         * existing channel is a no-op.
         */
        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val events = NotificationChannel(
                CHANNEL_EVENTS,
                "Tether events",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Approvals and questions from agent sessions."
                enableVibration(true)
            }
            val complete = NotificationChannel(
                CHANNEL_COMPLETE,
                "Tether turn complete",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "A turn finished while you were away."
            }
            manager.createNotificationChannels(listOf(events, complete))
        }
    }
}

/**
 * Process-wide foreground signal read by [TetherFcmService.onMessageReceived]
 * and written by [PushController] (which observes `ProcessLifecycleOwner`).
 * `activeTag` is the notification tag the currently-selected session would
 * surface, so a push for the session the user is already looking at is dropped.
 */
object ForegroundState {
    @Volatile var isForeground: Boolean = false
    @Volatile var activeTag: String? = null
}