package com.tether.app.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-jt9 L-B / L-C: a sign-out is never undone by a start() that read the store before it, and a
 * push hook that throws a CancellationException of its own never skips the server revoke.
 */
class SignOutRaceTest {
    private val h = ConnectionHarness()

    @After fun tearDown() = h.close()

    /** The store with a seam right after [session] has read its snapshot. */
    private class GatedSettings(private val inner: InMemorySettings) : SettingsStore by inner {
        @Volatile var afterSessionRead: (suspend () -> Unit)? = null

        override suspend fun session(): Session = inner.session().also { afterSessionRead?.invoke() }
    }

    private fun take(): RecordedRequest = h.server.takeRequest(20, TimeUnit.SECONDS)!!

    private fun newClient(
        settings: SettingsStore,
        onLogout: suspend (String, Credential) -> Unit = { _, _ -> },
    ): RealTetherClient = RealTetherClient(
        settings = settings,
        httpClient = OkHttpClient(),
        scope = h.scope,
        clock = { h.now.get() },
        backoff = testBackoff(),
        sweepIntervalMs = 3_600_000,
        scheduler = h.scheduler,
        onLogout = onLogout,
    ).also { h.client = it }

    private fun connect(client: RealTetherClient) {
        h.enqueueConnect()
        client.start()
        h.handshake(h.nextSocket())
        assertEquals("/api/auth/session", take().path)
        assertEquals("/ws", take().path)
    }

    /**
     * L-B: start() runs again (an activity re-creation) and reads the store's snapshot while the
     * device is signed in; a logout lands before that start() acts on it. The start must not
     * re-adopt the forgotten device token: nothing may carry it afterwards, and a later start()
     * must not reconnect with it (a device-token logout is local only, so the token still works
     * server-side and a re-adoption would be a silent re-sign-in).
     */
    @Test
    fun aStartWhoseSnapshotPredatesALogoutNeverReadoptsTheForgottenCredential() {
        h.server.start()
        val base = h.server.url("/").toString().trimEnd('/')
        val settings = GatedSettings(InMemorySettings(initialBaseUrl = base, initialDeviceToken = "tthr_device"))
        val client = newClient(settings)
        connect(client)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var staleStart: Job? = null
        settings.afterSessionRead = {
            settings.afterSessionRead = null
            staleStart = currentCoroutineContext()[Job]
            entered.countDown()
            release.await(20, TimeUnit.SECONDS)
        }
        client.start()
        assertTrue("the second start() read its snapshot", entered.await(20, TimeUnit.SECONDS))
        assertEquals(LogoutResult.LocalOnly, runBlocking { client.logout() })
        release.countDown()
        runBlocking { withTimeout(20_000) { staleStart!!.join() } }

        val before = h.server.requestCount
        h.server.enqueue(MockResponse().setBody("""{"current":"/w","parent":null,"breadcrumbs":[],"entries":[]}"""))
        assertEquals(FilesResult.Failed(FilesCopy.NOT_SIGNED_IN), runBlocking { client.files.list("/w") })
        assertTrue("the stale start left no credential in force", runBlocking { client.fetchStats() } is StatsResult.Failed)
        assertEquals("nothing carried the forgotten token", before, h.server.requestCount)
        assertNull(runBlocking { settings.session().credential })

        // The next start() (the UI coming back) does not sign back in either.
        h.enqueueConnect()
        client.start()
        h.await(client.connection) { it == ConnectionState.AuthRequired }
        Thread.sleep(500)
        assertEquals("no probe, no socket", before, h.server.requestCount)
    }

    /**
     * L-C: logout runs NonCancellable, so a CancellationException out of the push hook is the
     * hook's own (a cancelled Firebase Task, say), never the logout's. It is best effort like any
     * other failure: the cookie is still revoked server-side and the logout returns normally.
     */
    @Test
    fun aPushHookThatThrowsItsOwnCancellationStillLetsTheLogoutRevokeTheCookie() {
        h.server.start()
        val base = h.server.url("/").toString().trimEnd('/')
        val settings = InMemorySettings(initialBaseUrl = base, initialCookie = "cookie-1")
        h.settings = settings
        val hookRan = CountDownLatch(1)
        val client = newClient(settings, onLogout = { _, _ ->
            hookRan.countDown()
            throw CancellationException("the push token task was cancelled")
        })
        connect(client)
        h.server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertEquals(LogoutResult.Revoked, runBlocking { client.logout() })
        assertTrue(hookRan.await(0, TimeUnit.SECONDS))
        var revoke: RecordedRequest? = null
        while (revoke == null) {
            val r = h.server.takeRequest(20, TimeUnit.SECONDS) ?: break
            if (r.path == "/api/auth/logout") revoke = r
        }
        assertNotNull("the cookie was revoked server-side", revoke)
        assertEquals("tether_session=cookie-1", revoke!!.getHeader("Cookie"))
        assertNull(runBlocking { settings.session().credential })
        assertEquals(ConnectionState.AuthRequired, client.connection.value)
    }
}
