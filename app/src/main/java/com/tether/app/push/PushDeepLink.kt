package com.tether.app.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.tether.app.MainActivity

/**
 * A notification tap. It only opens the app. It never names a session: the
 * server's FCM payload is id-free, so only another app could put a session into
 * the tap intent (T12.1 round 2, security review H1).
 */
data class PushOpen(val kind: PushKind)

/**
 * The notification tap → app route (T12.1). This is the one hook the UI
 * consumes. T4.4 owns full deep-link routing and can build on [parse] without
 * touching the push code.
 *
 * Two intents reach [MainActivity] from a notification:
 * - [ACTION_OPEN]: built by [intentFor] for notifications the app posts. It is
 *   explicit (component = MainActivity) and wrapped in an immutable
 *   PendingIntent.
 * - [ACTION_SDK_CLICK] (`OPEN_TETHER`): FCM builds this one for notifications it
 *   shows itself while the app is in the background, from the server's
 *   `android.notification.click_action` (tether `lib/fcm-push.mjs`). Its extras
 *   are the FCM `data` map (`url`, `kind`, `tag`).
 *
 * MainActivity is exported (it is the launcher), so any app can send it either
 * action with any extras. [parse] therefore reads only the kind. It ignores
 * every other extra, including any session id or url. A tap only opens the app:
 * it selects no session, attaches nothing, and never answers an approval or a
 * question (T12.3 is deferred and needs an explicit owner opt-in). T4.4 re-adds
 * routing to a session only with a verified sender.
 */
object PushDeepLink {
    const val ACTION_OPEN = "com.tether.app.action.OPEN_FROM_PUSH"
    const val ACTION_SDK_CLICK = "OPEN_TETHER"

    const val EXTRA_KIND = "tether.push.kind"
    const val EXTRA_TAG = "tether.push.tag"

    /** The explicit intent behind a notification the app posts. */
    fun intentFor(context: Context, message: PushMessage.Visible): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_KIND, message.kind.wire)
            message.tag?.let { putExtra(EXTRA_TAG, it) }
        }

    /**
     * Immutable, so the notification's holder cannot rewrite the target or the
     * extras. The request code comes from the collapse tag, so two live
     * notifications never share one PendingIntent. Without that,
     * FLAG_UPDATE_CURRENT would rewrite an older notification's extras.
     */
    fun pendingIntentFor(context: Context, message: PushMessage.Visible): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCodeFor(message),
            intentFor(context, message),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    internal fun requestCodeFor(message: PushMessage.Visible): Int =
        (message.tag ?: PushNotifier.FALLBACK_TAG).hashCode()

    /** A notification tap carried by [intent], or null when it is not one. */
    fun parse(intent: Intent?): PushOpen? {
        intent ?: return null
        return when (intent.action) {
            ACTION_OPEN -> PushOpen(PushKind.fromWire(intent.stringExtra(EXTRA_KIND)))
            ACTION_SDK_CLICK -> PushOpen(PushKind.fromWire(intent.stringExtra("kind")))
            else -> null
        }
    }

    /** A non-String extra (a hostile sender can put anything there) reads as absent. */
    private fun Intent.stringExtra(name: String): String? = try {
        getStringExtra(name)
    } catch (_: RuntimeException) {
        null
    }
}
