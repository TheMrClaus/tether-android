package com.tether.app.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tether.app.MainActivity
import com.tether.app.push.PushDeepLink
import com.tether.app.push.PushKind
import com.tether.app.push.PushMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T4.4: which field of an intent is read. MainActivity is exported, so each channel reads only
 * its own field: a notification tap reads nothing that names a session, a VIEW reads its data URI
 * and never its extras.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepLinkIntentsTest {

    private val paired = NavTestClient.PAIRED
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun view(uri: String?) = Intent(Intent.ACTION_VIEW).apply { uri?.let { data = Uri.parse(it) } }

    @Test
    fun aViewReadsItsDataUri() {
        assertEquals(ParsedLink.Open(Destination.Session("s1")), DeepLinkIntents.parse(view("tether://session/s1"), paired))
        assertEquals(
            ParsedLink.Open(Destination.Session("s1"), "https://tether.example.com:443"),
            DeepLinkIntents.parse(view("https://tether.example.com/?session=s1"), paired),
        )
        assertEquals(ParsedLink.Rejected(LinkRejection.OtherOrigin), DeepLinkIntents.parse(view("https://evil.example/?session=s1"), paired))
    }

    @Test
    fun aViewNeverReadsItsExtras() {
        val planted = view("tether://session/s1")
            .putExtra("session", "s2")
            .putExtra("url", "/?session=s2")
            .putExtra(PushDeepLink.EXTRA_KIND, "approval")
        assertEquals(ParsedLink.Open(Destination.Session("s1")), DeepLinkIntents.parse(planted, paired))
        val noData = view(null).putExtra("session", "s2").putExtra("url", "tether://session/s2")
        assertEquals(ParsedLink.Rejected(LinkRejection.Malformed), DeepLinkIntents.parse(noData, paired))
    }

    @Test
    fun aNotificationTapOnlyOpensTheApp() {
        val ours = PushDeepLink.intentFor(context, PushMessage.Visible(PushKind.Approval, "T", "B", "tether-approval-x"))
        assertEquals(ParsedLink.Open(Destination.Home), DeepLinkIntents.parse(ours, paired))
        // Planted by another app: a session extra, a url extra, even a data URI.
        val planted = Intent(PushDeepLink.ACTION_OPEN)
            .setData(Uri.parse("tether://session/s1"))
            .putExtra(PushDeepLink.EXTRA_KIND, "approval")
            .putExtra("tether.push.sessionId", "s1")
        assertEquals(ParsedLink.Open(Destination.Home), DeepLinkIntents.parse(planted, paired))
        val fcm = Intent(PushDeepLink.ACTION_SDK_CLICK)
            .setData(Uri.parse("https://tether.example.com/?session=s1"))
            .putExtra("kind", "question")
            .putExtra("url", "/?session=s1")
        assertEquals(ParsedLink.Open(Destination.Home), DeepLinkIntents.parse(fcm, paired))
    }

    @Test
    fun otherIntentsAskForNothing() {
        assertNull(DeepLinkIntents.parse(null, paired))
        assertNull(DeepLinkIntents.parse(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), paired))
        assertNull(DeepLinkIntents.parse(Intent(context, MainActivity::class.java), paired))
        assertNull(DeepLinkIntents.parse(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "tether://session/s1"), paired))
        assertNull(DeepLinkIntents.parse(Intent(Intent.ACTION_EDIT, Uri.parse("tether://session/s1")), paired))
    }
}
