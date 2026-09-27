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
 * exported, so any app can send it these actions with any extras. A tap only
 * opens the app: no session is ever read from an intent (H1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushDeepLinkTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun ourOwnIntentRoundTrips() {
        val intent = PushDeepLink.intentFor(context, PushMessage.Visible(PushKind.TurnEnd, "T", "B", "tether-complete-x"))
        assertEquals(PushOpen(PushKind.TurnEnd), PushDeepLink.parse(intent))
    }

    @Test
    fun aSessionPlantedByAnotherAppIsIgnored() {
        val planted = Intent(PushDeepLink.ACTION_OPEN)
            .putExtra(PushDeepLink.EXTRA_KIND, "approval")
            .putExtra("tether.push.sessionId", "sess-1")
            .putExtra("url", "/?session=sess-1")
        assertEquals(PushOpen(PushKind.Approval), PushDeepLink.parse(planted))
        val viaFcmAction = Intent(PushDeepLink.ACTION_SDK_CLICK).putExtra("kind", "question").putExtra("url", "/?session=abc")
        assertEquals(PushOpen(PushKind.Question), PushDeepLink.parse(viaFcmAction))
    }

    @Test
    fun aNonStringExtraReadsAsAbsent() {
        val intent = Intent(PushDeepLink.ACTION_OPEN).putExtra(PushDeepLink.EXTRA_KIND, 7)
        assertEquals(PushOpen(PushKind.Other), PushDeepLink.parse(intent))
    }

    @Test
    fun fcmsOwnTapCarriesTheDataMap() {
        val today = Intent(PushDeepLink.ACTION_SDK_CLICK).putExtra("kind", "approval").putExtra("url", "/").putExtra("tag", "t")
        assertEquals(PushOpen(PushKind.Approval), PushDeepLink.parse(today))
        assertEquals(PushOpen(PushKind.Other), PushDeepLink.parse(Intent(PushDeepLink.ACTION_SDK_CLICK)))
    }

    @Test
    fun otherIntentsAreNotATap() {
        assertNull(PushDeepLink.parse(null))
        assertNull(PushDeepLink.parse(Intent(Intent.ACTION_MAIN).putExtra(PushDeepLink.EXTRA_KIND, "approval")))
        assertNull(PushDeepLink.parse(Intent().putExtra(PushDeepLink.EXTRA_KIND, "approval")))
    }

    @Test
    fun theSettingsFallbackOpensThisAppsNotificationPage() {
        val intent = notificationSettingsIntent(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
