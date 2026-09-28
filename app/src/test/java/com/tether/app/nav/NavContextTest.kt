package com.tether.app.nav

import com.tether.app.client.ConnectionState
import com.tether.app.nav.NavTestClient.Companion.LISTED
import com.tether.app.nav.NavTestClient.Companion.PAIRED
import com.tether.app.ui.navContextOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T4.4: how UiRoot reads the client for the navigator. */
class NavContextTest {

    @Test
    fun aStoredCredentialTheServerRejectedIsNotSignedIn() {
        val context = navContextOf(NavTestClient(configured = true, connection = ConnectionState.AuthRequired))
        assertFalse(context.signedIn)
        assertFalse(context.connected)
    }

    @Test
    fun noStoredCredentialIsNotSignedIn() {
        assertFalse(navContextOf(NavTestClient(configured = false, connection = ConnectionState.Disconnected)).signedIn)
    }

    @Test
    fun aStoredCredentialWhileConnectingIsSignedInButNotConnected() {
        val context = navContextOf(NavTestClient(configured = true, connection = ConnectionState.Connecting))
        assertTrue(context.signedIn)
        assertFalse(context.connected)
    }

    @Test
    fun connectedCarriesTheServerAndTheListedSessions() {
        val context = navContextOf(NavTestClient())
        assertTrue(context.signedIn)
        assertTrue(context.connected)
        assertEquals(PAIRED, context.serverUrl)
        assertTrue(LISTED in context.sessionIds)
    }
}
