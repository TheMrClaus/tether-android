package com.tether.app.push

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PushDeepLink.parse] treats every intent as untrusted. MainActivity is
 * exported, so any app can send it these actions with any extras.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushDeepLinkTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun ourOwnIntentRoundTrips() {
        val message = PushMessage.Visible(PushKind.TurnEnd, "T", "B", "tether-complete-x", "sess-1")
        val intent = PushDeepLink.intentFor(context, message)
        assertEquals(PushOpen(PushKind.TurnEnd, "sess-1"), PushDeepLink.parse(intent))
    }

    @Test
    fun anInvalidSessionExtraIsDropped() {
        for (bad in listOf("", "../x", "a b", "a".repeat(129), "https://evil.example")) {
            val intent = Intent(PushDeepLink.ACTION_OPEN)
                .putExtra(PushDeepLink.EXTRA_KIND, "approval")
                .putExtra(PushDeepLink.EXTRA_SESSION_ID, bad)
            assertEquals("id '$bad'", PushOpen(PushKind.Approval, null), PushDeepLink.parse(intent))
        }
    }

    @Test
    fun aNonStringExtraReadsAsAbsent() {
        val intent = Intent(PushDeepLink.ACTION_OPEN)
            .putExtra(PushDeepLink.EXTRA_KIND, 7)
            .putExtra(PushDeepLink.EXTRA_SESSION_ID, 42)
        assertEquals(PushOpen(PushKind.Other, null), PushDeepLink.parse(intent))
    }

    @Test
    fun fcmsOwnTapCarriesTheDataMap() {
        val today = Intent(PushDeepLink.ACTION_SDK_CLICK).putExtra("kind", "approval").putExtra("url", "/").putExtra("tag", "t")
        assertEquals(PushOpen(PushKind.Approval, null), PushDeepLink.parse(today))
        val withSession = Intent(PushDeepLink.ACTION_SDK_CLICK).putExtra("kind", "question").putExtra("url", "/?session=abc")
        assertEquals(PushOpen(PushKind.Question, "abc"), PushDeepLink.parse(withSession))
        val offOrigin = Intent(PushDeepLink.ACTION_SDK_CLICK).putExtra("url", "https://evil.example/?session=abc")
        assertEquals(PushOpen(PushKind.Other, null), PushDeepLink.parse(offOrigin))
    }

    @Test
    fun otherIntentsAreNotATap() {
        assertNull(PushDeepLink.parse(null))
        assertNull(PushDeepLink.parse(Intent(Intent.ACTION_MAIN).putExtra(PushDeepLink.EXTRA_SESSION_ID, "s1")))
        assertNull(PushDeepLink.parse(Intent().putExtra(PushDeepLink.EXTRA_SESSION_ID, "s1")))
    }

    @Test
    fun onlyAListedSessionIsSelected() {
        assertEquals("s1", PushDeepLink.resolve("s1", listOf("s0", "s1")))
        assertNull(PushDeepLink.resolve("s9", listOf("s0", "s1")))
        assertNull(PushDeepLink.resolve(null, listOf("s0")))
        assertNull(PushDeepLink.resolve("a b", listOf("a b")))
    }

    @Test
    fun theSettingsFallbackOpensThisAppsNotificationPage() {
        val intent = notificationSettingsIntent(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
