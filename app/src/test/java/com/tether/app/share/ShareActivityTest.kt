package com.tether.app.share

import android.content.Intent
import com.tether.app.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** T11.2: the share target puts what it read in the inbox, brings Tether's task forward and finishes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareActivityTest {
    @Before
    @After
    fun clearInbox() {
        ShareInbox.pending.value?.let { ShareInbox.discard(it.id) }
    }

    @Test
    fun sharedTextWaitsInTheInboxAndTetherOpens() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello")
        val activity = Robolectric.buildActivity(ShareActivity::class.java, intent).create().get()
        assertEquals("hello", ShareInbox.pending.value?.text)
        assertEquals(emptyList<Any>(), ShareInbox.pending.value?.files)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertEquals(ShareActivity.ACTION_SHARED, started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun anUnsupportedIntentIsIgnored() {
        val activity = Robolectric.buildActivity(ShareActivity::class.java, Intent(Intent.ACTION_SEND).setType("text/plain")).create().get()
        assertNull(ShareInbox.pending.value)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }
}
