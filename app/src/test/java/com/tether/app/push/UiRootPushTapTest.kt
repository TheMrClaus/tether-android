package com.tether.app.push

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tether.app.client.TetherClient
import com.tether.app.ui.UiRoot
import com.tether.app.ui.fake.FakeTetherClient
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Z3 / H1: a notification tap opens the app and does nothing else. The real
 * [UiRoot] renders with a tap intent that names a session the client lists
 * (planted, as another app could). No session may be selected. Selecting one
 * would attach it, so no attach may be sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiRootPushTapTest {

    /** The preview client, recording every attach (selectSession's side effect). */
    private class RecordingClient(private val inner: FakeTetherClient = FakeTetherClient()) : TetherClient by inner {
        val attached = CopyOnWriteArrayList<String>()
        override fun attach(sessionId: String) {
            attached += sessionId
            inner.attach(sessionId)
        }
        // ta-coik.39 r2: a chat view mount attaches too (delegated, it would bypass this record).
        override fun attachMounted(sessionId: String) {
            attached += sessionId
            inner.attachMounted(sessionId)
        }
    }

    private fun render(pushIntent: Intent?): RecordingClient {
        // "Remove animations" (the OS setting TetherTheme reads): the status
        // indicators then draw a static frame instead of animating forever, so
        // the looper can go idle.
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val client = RecordingClient()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { UiRoot(client = client, launchIntent = pushIntent) }
        repeat(5) { shadowOf(Looper.getMainLooper()).idle() }
        return client
    }

    private val planted = "s-approve" // a session FakeTetherClient lists

    @Test
    fun ourTapWithAPlantedSessionSelectsNothing() {
        val baseline = render(pushIntent = null).attached.toList()
        val tap = Intent(PushDeepLink.ACTION_OPEN)
            .putExtra(PushDeepLink.EXTRA_KIND, "approval")
            .putExtra(PushDeepLink.EXTRA_TAG, "tether-approval-x")
            .putExtra("tether.push.sessionId", planted)
        val attached = render(tap).attached.toList()
        assertFalse(attached.toString(), planted in attached)
        assertEquals(baseline, attached)
    }

    @Test
    fun fcmsBackgroundTapWithASessionUrlSelectsNothing() {
        val baseline = render(pushIntent = null).attached.toList()
        val tap = Intent(PushDeepLink.ACTION_SDK_CLICK)
            .putExtra("kind", "question")
            .putExtra("url", "/?session=$planted")
        val attached = render(tap).attached.toList()
        assertFalse(attached.toString(), planted in attached)
        assertEquals(baseline, attached)
    }
}
