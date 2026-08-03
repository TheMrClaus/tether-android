package com.tether.app.push

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [TetherFcmService.onMessageReceived] posts a system notification on the
 * channel selected by `data.kind`, with the server-supplied tag so duplicate
 * events collapse, and a content intent that routes back to MainActivity.
 *
 * Robolectric is used so the real NotificationManager is exercisable without
 * an emulator. Firebase itself is never touched — [RemoteMessage] is a plain
 * data holder constructed directly. The SDK is pinned to 34 because the
 * project's JDK is 17 and Robolectric's SDK 36 sandbox requires Java 21.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TetherFcmServiceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val service: TetherFcmService by lazy {
        // Robolectric.buildService attaches the Service to the application
        // context so Intent(this, MainActivity::class.java) inside
        // onMessageReceived can resolve a package name. The service is not
        // started — we only need a fully-constructed instance.
        Robolectric.buildService(TetherFcmService::class.java).get()
    }

    @Before
    fun setUp() {
        // Channels must exist before posting; the service posts to
        // tether-events / tether-complete which TetherApp.onCreate registers.
        TetherFcmService.ensureChannels(context)
        // Reset foreground state between tests.
        ForegroundState.isForeground = false
        ForegroundState.activeTag = null
    }

    @After
    fun tearDown() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
    }

    private fun sendMessage(
        kind: String,
        tag: String? = "tether-$kind-abc",
        title: String = "Tether needs you",
        body: String = "A session is waiting.",
    ) {
        val builder = RemoteMessage.Builder("fcm-test@example")
            .setMessageId("msg-1")
            .addData("kind", kind)
            .addData("title", title)
            .addData("body", body)
            .addData("url", "/")
        if (tag != null) builder.addData("tag", tag)
        service.onMessageReceived(builder.build())
    }

    private fun postedNotifications(): List<android.app.Notification> {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return shadowOf(manager).allNotifications
    }

    @Test
    fun approvalPostsOnEventsChannel() {
        sendMessage("approval")
        val notifications = postedNotifications()
        assertTrue("expected one notification, got ${notifications.size}", notifications.isNotEmpty())
        assertEquals("tether-events", notifications.first().channelId)
    }

    @Test
    fun turnEndPostsOnCompleteChannel() {
        sendMessage("turn_end", tag = "tether-complete-xyz", title = "Tether turn complete", body = "A session finished its turn.")
        val notifications = postedNotifications()
        assertTrue(notifications.isNotEmpty())
        assertEquals("tether-complete", notifications.first().channelId)
    }

    @Test
    fun foregroundSuppressionDropsNotificationForActiveTag() {
        ForegroundState.isForeground = true
        ForegroundState.activeTag = "tether-approval-abc"
        sendMessage("approval", tag = "tether-approval-abc")
        assertEquals("foreground suppression dropped the push", 0, postedNotifications().size)
    }

    @Test
    fun foregroundSuppressionPostsWhenActiveTagDiffers() {
        ForegroundState.isForeground = true
        ForegroundState.activeTag = "tether-approval-other"
        sendMessage("approval", tag = "tether-approval-abc")
        val notifications = postedNotifications()
        assertTrue(notifications.isNotEmpty())
    }
}