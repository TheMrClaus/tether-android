package com.tether.app.ui

import com.tether.app.client.LogoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ta-coik.5: the sign-in screen's words after a sign-out. Since tether #236 a device and a session
 * are revoked from Settings in the app as in the web, so no notice sends the operator to a browser.
 */
class LogoutNoticeTest {
    @Test fun theNoticesNameTheAppsOwnSettingsAndNeverABrowser() {
        assertNull(logoutNoticeFor(LogoutResult.Revoked))
        assertEquals(
            "Signed out on this phone. It stays paired with the server until it is revoked in Settings → Paired devices.",
            logoutNoticeFor(LogoutResult.LocalOnly),
        )
        assertEquals(
            "Signed out on this phone, but the server could not be reached. That session stays valid until it expires or is signed out in Settings → Signed-in sessions.",
            logoutNoticeFor(LogoutResult.ServerNotReached),
        )
        for (r in listOf(LogoutResult.LocalOnly, LogoutResult.ServerNotReached)) {
            val text = logoutNoticeFor(r)!!.lowercase()
            assertFalse(text, text.contains("browser"))
            assertFalse(text, text.contains("web console"))
        }
    }
}
