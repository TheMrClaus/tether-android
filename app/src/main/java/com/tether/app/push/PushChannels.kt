package com.tether.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import com.tether.app.R

/**
 * The notification channels, one per server push kind (tether
 * `lib/push-notifications.mjs`: approval, question, turn_end, resume_choice),
 * plus [GENERAL].
 *
 * [GENERAL] is also the manifest's
 * `com.google.firebase.messaging.default_notification_channel_id`. While the app
 * is not in the foreground, FCM shows a message that has a `notification` block
 * itself, without calling [TetherFcmService.onMessageReceived]. The server sends
 * no `android.notification.channel_id` today, so those notifications use the
 * manifest default. The per-kind channels therefore apply to notifications the
 * app posts itself (in the foreground, or data-only). Routing background
 * notifications per kind needs the server to send `channel_id`.
 */
object PushChannels {
    const val APPROVAL = "tether-approval"
    const val QUESTION = "tether-question"
    const val TURN_DONE = "tether-turn-done"
    const val RATE_LIMIT = "tether-rate-limit"
    const val GENERAL = "tether-general"

    /** Pre-T12.1 channels: approvals + questions + anything else, and turn complete. */
    internal const val LEGACY_EVENTS = "tether-events"
    internal const val LEGACY_COMPLETE = "tether-complete"

    private data class Spec(
        val id: String,
        @StringRes val name: Int,
        @StringRes val description: Int,
        val importance: Int,
        /** The pre-T12.1 channel this one replaces; a block there carries over. */
        val legacy: String,
    )

    private val specs = listOf(
        Spec(APPROVAL, R.string.push_channel_approval_name, R.string.push_channel_approval_description, NotificationManager.IMPORTANCE_HIGH, LEGACY_EVENTS),
        Spec(QUESTION, R.string.push_channel_question_name, R.string.push_channel_question_description, NotificationManager.IMPORTANCE_HIGH, LEGACY_EVENTS),
        Spec(TURN_DONE, R.string.push_channel_turn_done_name, R.string.push_channel_turn_done_description, NotificationManager.IMPORTANCE_DEFAULT, LEGACY_COMPLETE),
        Spec(RATE_LIMIT, R.string.push_channel_rate_limit_name, R.string.push_channel_rate_limit_description, NotificationManager.IMPORTANCE_HIGH, LEGACY_EVENTS),
        Spec(GENERAL, R.string.push_channel_general_name, R.string.push_channel_general_description, NotificationManager.IMPORTANCE_HIGH, LEGACY_EVENTS),
    )

    val all: List<String> get() = specs.map { it.id }

    fun forKind(kind: PushKind): String = when (kind) {
        PushKind.Approval -> APPROVAL
        PushKind.Question -> QUESTION
        PushKind.TurnEnd -> TURN_DONE
        PushKind.ResumeChoice -> RATE_LIMIT
        PushKind.Other -> GENERAL
    }

    /** Channels whose importance drives a heads-up; the rest post quietly. */
    fun isHighImportance(channelId: String): Boolean =
        specs.firstOrNull { it.id == channelId }?.importance == NotificationManager.IMPORTANCE_HIGH

    /**
     * Creates the channels. Called from [com.tether.app.TetherApp.onCreate] so
     * they exist before any FCM message arrives. Safe to call on every start:
     * re-creating a channel only refreshes its name and description, and never
     * overrides what the user changed.
     *
     * Upgrading from the two pre-T12.1 channels: a new channel inherits a
     * **block** (importance NONE) from the channel it replaces, so a user who
     * silenced "Tether turn complete" is not alerted again by "Turn done". Then
     * the old channels are deleted, so the system settings list only live ones.
     */
    fun ensure(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val blockedLegacy = listOf(LEGACY_EVENTS, LEGACY_COMPLETE).filterTo(HashSet()) {
            manager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE
        }
        val channels = specs.map { spec ->
            val firstCreation = manager.getNotificationChannel(spec.id) == null
            val importance = if (firstCreation && spec.legacy in blockedLegacy) {
                NotificationManager.IMPORTANCE_NONE
            } else {
                spec.importance
            }
            NotificationChannel(spec.id, context.getString(spec.name), importance).apply {
                description = context.getString(spec.description)
                if (spec.importance == NotificationManager.IMPORTANCE_HIGH) enableVibration(true)
            }
        }
        manager.createNotificationChannels(channels)
        manager.deleteNotificationChannel(LEGACY_EVENTS)
        manager.deleteNotificationChannel(LEGACY_COMPLETE)
    }
}
