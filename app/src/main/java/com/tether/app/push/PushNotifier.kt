package com.tether.app.push

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Posts a [PushMessage.Visible] as a system notification.
 *
 * - Channel: per kind ([PushChannels.forKind]).
 * - Collapse: `notify(tag, 0)`, the same (tag, id) FCM uses when it shows the
 *   server's notification itself. A notification the app posts and one FCM posts
 *   for the same event therefore replace each other instead of stacking. Tagless
 *   messages share [FALLBACK_TAG].
 * - Tap: [PushDeepLink.pendingIntentFor] (explicit, immutable).
 * - No actions. Approve/deny from a notification is T12.3 (deferred, owner
 *   opt-in), and a notification never resolves anything by itself.
 * - Only the server's text is shown. It is generic by design ("A Claude session
 *   is waiting for approval."). Nothing is logged.
 */
object PushNotifier {
    const val FALLBACK_TAG = "tether-push"

    /** FCM's own display path uses id 0; matching it lets the two collapse. */
    const val NOTIFICATION_ID = 0

    /** Returns true when a notification was handed to the system. */
    fun post(context: Context, message: PushMessage.Visible): Boolean {
        // Android 13+: without POST_NOTIFICATIONS the system drops the post anyway.
        // Checking first keeps that explicit (and lint-clean).
        if (!canPost(context)) return false
        val channelId = PushChannels.forKind(message.kind)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setAutoCancel(true)
            .setPriority(
                if (PushChannels.isHighImportance(channelId)) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .setContentIntent(PushDeepLink.pendingIntentFor(context, message))
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(message.tag ?: FALLBACK_TAG, NOTIFICATION_ID, notification)
        return true
    }

    fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
