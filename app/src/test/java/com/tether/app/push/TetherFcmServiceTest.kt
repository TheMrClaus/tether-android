package com.tether.app.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import com.tether.app.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [TetherFcmService] end to end on Robolectric's NotificationManager: payload →
 * channel, the sync hint and title-less messages post nothing, collapsing,
 * no actions, and the explicit immutable tap intent.
 *
 * Messages are built the way FCM hands them to the service: a [RemoteMessage]
 * over a Bundle, with the `notification` block as `gcm.n.*` keys. Firebase
 * itself is never initialised. SDK 34 (the project's JDK is 17, and
 * Robolectric's newer sandboxes need Java 21).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TetherFcmServiceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val service: TetherFcmService by lazy {
        Robolectric.buildService(TetherFcmService::class.java).get()
    }

    @Before
    fun setUp() {
        PushChannels.ensure(context)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ForegroundState.isForeground = false
        ForegroundState.activeTag = null
    }

    @After
    fun tearDown() {
        manager.cancelAll()
    }

    /** The server's visible message (lib/fcm-push.mjs buildFcmMessage). */
    private fun serverMessage(kind: String, tag: String, title: String, body: String, url: String = "/"): RemoteMessage =
        RemoteMessage(
            Bundle().apply {
                putString("gcm.n.e", "1")
                putString("gcm.n.title", title)
                putString("gcm.n.body", body)
                putString("gcm.n.tag", tag)
                putString("gcm.n.click_action", "OPEN_TETHER")
                putString("url", url)
                putString("kind", kind)
                putString("tag", tag)
                putString("google.message_id", "fake-message-id")
            },
        )

    /** A data-only message (no `notification` block). */
    private fun dataMessage(vararg data: Pair<String, String>): RemoteMessage =
        RemoteMessage(Bundle().apply { data.forEach { (k, v) -> putString(k, v) } })

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    @Test
    fun eachServerKindPostsOnItsOwnChannel() {
        val cases = listOf(
            "approval" to PushChannels.APPROVAL,
            "question" to PushChannels.QUESTION,
            "turn_end" to PushChannels.TURN_DONE,
            "resume_choice" to PushChannels.RATE_LIMIT,
            "brand_new_kind" to PushChannels.GENERAL,
        )
        for ((kind, channel) in cases) {
            manager.cancelAll()
            service.onMessageReceived(serverMessage(kind, "tether-$kind-abc", "Tether needs you", "A Claude session needs you."))
            val notifications = posted()
            assertEquals("kind $kind", 1, notifications.size)
            assertEquals("kind $kind", channel, notifications.single().channelId)
        }
    }

    @Test
    fun theNotificationCarriesOnlyTheServerTextAndNoActions() {
        service.onMessageReceived(
            serverMessage("approval", "tether-approval-abc", "Tether needs you", "A Claude session is waiting for approval."),
        )
        val notification = posted().single()
        val extras = notification.extras
        assertEquals("Tether needs you", extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("A Claude session is waiting for approval.", extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        // No approve/deny (T12.3 is deferred): a notification never resolves anything.
        assertTrue(notification.actions.isNullOrEmpty())
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun approvalsAndQuestionsArePrivateWithAGenericPublicVersion() {
        for (kind in listOf("approval", "question")) {
            manager.cancelAll()
            service.onMessageReceived(serverMessage(kind, "tether-$kind-abc", "Tether needs you", "A Claude session has a question."))
            val notification = posted().single()
            assertEquals(kind, Notification.VISIBILITY_PRIVATE, notification.visibility)
            val public = notification.publicVersion
            assertNotNull(kind, public)
            assertEquals("Tether needs you", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            val publicText = public.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            assertEquals("A session is waiting for you.", publicText)
            assertTrue(!publicText.contains("Claude"))
        }
    }

    @Test
    fun otherKindsArePrivateToo() {
        for (kind in listOf("turn_end", "resume_choice", "brand_new_kind")) {
            manager.cancelAll()
            service.onMessageReceived(serverMessage(kind, "tether-$kind-abc", "Tether turn complete", "A session finished its turn."))
            assertEquals(kind, Notification.VISIBILITY_PRIVATE, posted().single().visibility)
        }
    }

    @Test
    fun theSyncHintPostsNothing() {
        service.onMessageReceived(dataMessage("kind" to "sync", "v" to "1"))
        assertEquals(0, posted().size)
    }

    @Test
    fun aSyncHintWithStrayTextStillPostsNothing() {
        service.onMessageReceived(dataMessage("kind" to "sync", "v" to "1", "title" to "x", "body" to "y"))
        assertEquals(0, posted().size)
    }

    @Test
    fun aTitleLessDataMessageNeverPostsANotification() {
        service.onMessageReceived(dataMessage("kind" to "approval", "tag" to "tether-approval-x", "url" to "/"))
        service.onMessageReceived(dataMessage("kind" to "turn_end", "title" to "", "body" to "Finished."))
        service.onMessageReceived(dataMessage("kind" to "question", "title" to "Tether needs you"))
        service.onMessageReceived(dataMessage())
        assertEquals(0, posted().size)
    }

    @Test
    fun aDataOnlyMessageWithTextIsShown() {
        service.onMessageReceived(
            dataMessage("kind" to "turn_end", "title" to "Tether turn complete", "body" to "A session finished its turn.", "tag" to "tether-complete-x"),
        )
        assertEquals(PushChannels.TURN_DONE, posted().single().channelId)
    }

    @Test
    fun theSameTagCollapsesWithFcmsOwnIdZero() {
        service.onMessageReceived(serverMessage("approval", "tether-approval-same", "Tether needs you", "First."))
        service.onMessageReceived(serverMessage("approval", "tether-approval-same", "Tether needs you", "Second."))
        assertEquals(1, posted().size)
        assertNotNull(shadowOf(manager).getNotification("tether-approval-same", PushNotifier.NOTIFICATION_ID))
        assertEquals(0, PushNotifier.NOTIFICATION_ID)
    }

    @Test
    fun differentTagsStack() {
        service.onMessageReceived(serverMessage("approval", "tether-approval-a", "Tether needs you", "A."))
        service.onMessageReceived(serverMessage("question", "tether-question-b", "Tether needs you", "B."))
        assertEquals(2, posted().size)
    }

    @Test
    fun withoutThePermissionNothingIsPosted() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        service.onMessageReceived(serverMessage("approval", "tether-approval-abc", "Tether needs you", "Waiting."))
        assertEquals(0, posted().size)
    }

    @Test
    fun foregroundSuppressionDropsTheActiveTag() {
        ForegroundState.isForeground = true
        ForegroundState.activeTag = "tether-approval-abc"
        service.onMessageReceived(serverMessage("approval", "tether-approval-abc", "Tether needs you", "Waiting."))
        assertEquals(0, posted().size)
    }

    @Test
    fun foregroundSuppressionPostsWhenTheTagDiffers() {
        ForegroundState.isForeground = true
        ForegroundState.activeTag = "tether-approval-other"
        service.onMessageReceived(serverMessage("approval", "tether-approval-abc", "Tether needs you", "Waiting."))
        assertEquals(1, posted().size)
    }

    // ── Tap intent ──────────────────────────────────────────────────────────

    @Test
    fun theTapIsAnExplicitImmutableActivityIntent() {
        service.onMessageReceived(serverMessage("question", "tether-question-abc", "Tether needs you", "A question."))
        val pending = posted().single().contentIntent
        val shadow = shadowOf(pending)
        assertTrue(shadow.isActivityIntent)
        assertTrue("FLAG_IMMUTABLE", shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val intent = shadow.savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals(PushDeepLink.ACTION_OPEN, intent.action)
        assertEquals("question", intent.getStringExtra(PushDeepLink.EXTRA_KIND))
        assertEquals("tether-question-abc", intent.getStringExtra(PushDeepLink.EXTRA_TAG))
        assertEquals(PushOpen(PushKind.Question), PushDeepLink.parse(intent))
    }

    @Test
    fun aSessionInTheUrlNeverRidesTheTapIntent() {
        // H1: a tap only opens the app, whatever the url says.
        service.onMessageReceived(serverMessage("approval", "tether-approval-a", "T", "B", url = "/?session=sess-1"))
        val intent = shadowOf(posted().single().contentIntent).savedIntent
        assertEquals(setOf(PushDeepLink.EXTRA_KIND, PushDeepLink.EXTRA_TAG), intent.extras!!.keySet())
        assertEquals(PushOpen(PushKind.Approval), PushDeepLink.parse(intent))
    }

    @Test
    fun twoLiveNotificationsNeverShareAPendingIntent() {
        service.onMessageReceived(serverMessage("approval", "tether-approval-a", "T", "B"))
        service.onMessageReceived(serverMessage("question", "tether-question-b", "T", "B"))
        val kinds = posted().map { shadowOf(it.contentIntent).savedIntent.getStringExtra(PushDeepLink.EXTRA_KIND) }.toSet()
        // With one shared request code, FLAG_UPDATE_CURRENT would give both "question".
        assertEquals(setOf("approval", "question"), kinds)
        val codes = posted().map { shadowOf(it.contentIntent).requestCode }
        assertNotEquals(codes[0], codes[1])
    }

    // ── Channels ────────────────────────────────────────────────────────────

    @Test
    fun channelsAreCreatedWithTheirImportance() {
        val expected = mapOf(
            PushChannels.APPROVAL to NotificationManager.IMPORTANCE_HIGH,
            PushChannels.QUESTION to NotificationManager.IMPORTANCE_HIGH,
            PushChannels.TURN_DONE to NotificationManager.IMPORTANCE_DEFAULT,
            PushChannels.RATE_LIMIT to NotificationManager.IMPORTANCE_HIGH,
            PushChannels.GENERAL to NotificationManager.IMPORTANCE_HIGH,
        )
        assertEquals(expected.keys, PushChannels.all.toSet())
        for ((id, importance) in expected) {
            val channel = manager.getNotificationChannel(id)
            assertNotNull(id, channel)
            assertEquals(id, importance, channel.importance)
            assertTrue(id, channel.name.isNotBlank())
            assertTrue(id, !channel.description.isNullOrBlank())
        }
    }

    @Test
    fun ensureIsIdempotent() {
        PushChannels.ensure(context)
        PushChannels.ensure(context)
        assertEquals(PushChannels.all.toSet(), manager.notificationChannels.map { it.id }.toSet())
    }

    @Test
    fun theLegacyChannelsAreRemovedAndABlockCarriesOver() {
        // The pre-T12.1 install state: the two old channels, one blocked.
        PushChannels.all.forEach(manager::deleteNotificationChannel)
        manager.createNotificationChannel(NotificationChannel(PushChannels.LEGACY_EVENTS, "Tether events", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(PushChannels.LEGACY_COMPLETE, "Tether turn complete", NotificationManager.IMPORTANCE_NONE))

        PushChannels.ensure(context)

        assertNull(manager.getNotificationChannel(PushChannels.LEGACY_EVENTS))
        assertNull(manager.getNotificationChannel(PushChannels.LEGACY_COMPLETE))
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(PushChannels.TURN_DONE).importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(PushChannels.APPROVAL).importance)
    }

    // ── Manifest wiring ─────────────────────────────────────────────────────

    @Test
    fun fcmsBackgroundTapResolvesToMainActivity() {
        // The server's click_action. Without the filter, FCM finds no activity and
        // the tap on a background notification does nothing.
        val intent = Intent(PushDeepLink.ACTION_SDK_CLICK).setPackage(context.packageName)
        val resolved = context.packageManager.resolveActivity(intent, 0)
        assertEquals(MainActivity::class.java.name, resolved?.activityInfo?.name)
    }

    @Test
    fun fcmsBackgroundNotificationsUseTheGeneralChannel() {
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertEquals(
            PushChannels.GENERAL,
            info.metaData.getString("com.google.firebase.messaging.default_notification_channel_id"),
        )
    }
}
