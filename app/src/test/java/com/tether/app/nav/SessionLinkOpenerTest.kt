package com.tether.app.nav

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.chat.LinkOpener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T4.4: a chat link naming a session on the paired server opens it in the app; every other link
 * (other hosts, the server's other pages, look-alikes) still leaves through the Custom Tab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionLinkOpenerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val external = mutableListOf<String>()
    private val inApp = mutableListOf<ParsedLink.Open>()
    private val opener = SessionLinkOpener(
        delegate = LinkOpener { _, href, _ -> external += href },
        pairedBaseUrl = { NavTestClient.PAIRED },
        openInApp = { inApp += it },
    )

    private fun open(href: String) = opener.open(context, href, Color.Black)

    @Test
    fun aSessionLinkOnThePairedServerOpensInTheApp() {
        open("https://tether.example.com/?session=s1")
        assertEquals(listOf(ParsedLink.Open(Destination.Session("s1"), "https://tether.example.com:443")), inApp)
        assertEquals(emptyList<String>(), external)
    }

    @Test
    fun everythingElseStaysExternal() {
        val links = listOf(
            "https://evil.example/?session=s1",
            "https://user@tether.example.com/?session=s1",
            "https://tether.example.com/usage",
            "https://tether.example.com/",
            "https://tether.example.com/?session=..%2Fx",
            "http://tether.example.com/?session=s1",
            "mailto:someone@example.com",
        )
        links.forEach(::open)
        assertEquals(emptyList<ParsedLink.Open>(), inApp)
        assertEquals(links, external)
    }
}
