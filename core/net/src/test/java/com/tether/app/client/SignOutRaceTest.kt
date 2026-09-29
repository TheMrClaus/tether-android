package com.tether.app.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val extraScopes = mutableListOf<CoroutineScope>()

    @After fun tearDown() {
        h.close()
        extraScopes.forEach { it.cancel() }
    }

    /**
     * The store with seams: right after [session] has read its snapshot, and around every clear
     * ([clearCredential], [clearCredentialIf], [clear]), before and after it reaches the store.
     */
    private class GatedSettings(private val inner: InMemorySettings) : SettingsStore by inner {
        @Volatile var afterSessionRead: (suspend () -> Unit)? = null
        @Volatile var beforeClear: (suspend () -> Unit)? = null
        @Volatile var afterClear: (() -> Unit)? = null

        override suspend fun session(): Session = inner.session().also { afterSessionRead?.invoke() }

        override suspend fun clearCredential() {
            beforeClear?.invoke()
            inner.clearCredential()
            afterClear?.invoke()
        }

        override suspend fun clearCredentialIf(expected: Credential): Boolean {
            beforeClear?.invoke()
            return inner.clearCredentialIf(expected).also { afterClear?.invoke() }
        }

        override suspend fun clear() {
            beforeClear?.invoke()
            inner.clear()
            afterClear?.invoke()
        }
    }

    /** A one-shot hold for [GatedSettings.beforeClear]: [entered] once a clear is held. */
    private class ClearHold(settings: GatedSettings) {
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()

        init {
            settings.beforeClear = {
                settings.beforeClear = null
                entered.countDown()
                withTimeout(20_000) { release.await() }
            }
        }
    }

    /** Runs nothing while held: a coroutine launched then is dispatched only on [release]. */
    private class HoldingDispatcher : CoroutineDispatcher() {
        private val queued = ConcurrentLinkedQueue<Runnable>()
        @Volatile private var holding = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (!holding) return Dispatchers.Default.dispatch(context, block)
            queued.add(block)
            if (!holding) drain()
        }

        fun hold() {
            holding = true
        }

        fun release() {
            holding = false
            drain()
        }

        private fun drain() {
            while (true) Dispatchers.Default.dispatch(EmptyCoroutineContext, queued.poll() ?: return)
        }
    }

    private fun take(): RecordedRequest = h.server.takeRequest(20, TimeUnit.SECONDS)!!

    private fun newClient(
        settings: SettingsStore,
        onLogout: suspend (String, Credential) -> Unit = { _, _ -> },
        scope: CoroutineScope = h.scope,
    ): RealTetherClient = RealTetherClient(
        settings = settings,
        httpClient = OkHttpClient(),
        scope = scope,
        clock = { h.now.get() },
        backoff = testBackoff(),
        sweepIntervalMs = 3_600_000,
        scheduler = h.scheduler,
        onLogout = onLogout,
    ).also { h.client = it }

    private fun connect(client: RealTetherClient): okhttp3.WebSocket {
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        assertEquals("/api/auth/session", take().path)
        assertEquals("/ws", take().path)
        return ws
    }

    private fun awaitNoStoredCredential(settings: SettingsStore) {
        val deadline = System.currentTimeMillis() + 20_000
        while (runBlocking { settings.session().credential } != null) {
            assertTrue("the store still holds the credential", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }

    /**
     * L-B: start() runs again (an activity re-creation) and reads the store's snapshot while the
     * device is signed in; [signOut] lands before that start() acts on it. The start must not
     * re-adopt the forgotten device token: nothing may carry it afterwards, and a later start()
     * must not reconnect with it (a device-token logout is local only, so the token still works
     * server-side and a re-adoption would be a silent re-sign-in).
     */
    private fun aStaleStartNeverUndoes(signOut: (RealTetherClient, okhttp3.WebSocket) -> Unit) {
        h.server.start()
        val base = h.server.url("/").toString().trimEnd('/')
        val settings = GatedSettings(InMemorySettings(initialBaseUrl = base, initialDeviceToken = "tthr_device"))
        val client = newClient(settings)
        val ws = connect(client)

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
        signOut(client, ws)
        awaitNoStoredCredential(settings)
        release.countDown()
        runBlocking { withTimeout(20_000) { staleStart!!.join() } }

        val before = h.server.requestCount
        h.server.enqueue(MockResponse().setBody("""{"current":"/w","parent":null,"breadcrumbs":[],"entries":[]}"""))
        assertEquals(FilesResult.Failed(FilesCopy.NOT_SIGNED_IN), runBlocking { client.files.list("/w") })
        assertTrue("the stale start left no credential in force", runBlocking { client.fetchStats() } is StatsResult.Failed)
        assertEquals("nothing carried the forgotten token", before, h.server.requestCount)

        // The next start() (the UI coming back) does not sign back in either.
        h.enqueueConnect()
        client.start()
        h.await(client.connection) { it == ConnectionState.AuthRequired }
        Thread.sleep(500)
        assertEquals("no probe, no socket", before, h.server.requestCount)
    }

    @Test
    fun aStartWhoseSnapshotPredatesALogoutNeverReadoptsTheForgottenCredential() = aStaleStartNeverUndoes { client, _ ->
        assertEquals(LogoutResult.LocalOnly, runBlocking { client.logout() })
    }

    @Test
    fun aStartWhoseSnapshotPredatesAStopNeverReadoptsTheClearedServerOrCredential() = aStaleStartNeverUndoes { client, _ ->
        client.stop()
    }

    @Test
    fun aStartWhoseSnapshotPredatesARevocationNeverReadoptsTheRevokedCredential() = aStaleStartNeverUndoes { client, ws ->
        ws.close(4001, "device revoked")
        h.await(client.signedOutReason) { it == SignedOutReason.DeviceUnpaired }
    }

    /** Nothing the client holds may carry a credential now, and a later start() does not connect. */
    private fun assertSignedOutForGood(client: RealTetherClient) {
        val before = h.server.requestCount
        h.server.enqueue(MockResponse().setBody("""{"current":"/w","parent":null,"breadcrumbs":[],"entries":[]}"""))
        assertEquals(FilesResult.Failed(FilesCopy.NOT_SIGNED_IN), runBlocking { client.files.list("/w") })
        assertTrue("no credential in force", runBlocking { client.fetchStats() } is StatsResult.Failed)
        assertEquals("nothing carried the forgotten token", before, h.server.requestCount)
        h.enqueueConnect()
        client.start()
        h.await(client.connection) { it == ConnectionState.AuthRequired }
        Thread.sleep(500)
        assertEquals("no probe, no socket", before, h.server.requestCount)
    }

    /**
     * L-B1 (verifier R2): a start() that begins AFTER the sign-out moved the epoch, but reads the
     * store BEFORE its clear lands, records the new epoch and sees the forgotten credential in
     * its snapshot. It must still not adopt it (the logout's clear, the revocation's
     * compare-and-clear, the stop's clear are each held here while the start runs).
     */
    private fun aStartInsideTheClearWindowNeverAdopts(signOut: (RealTetherClient, okhttp3.WebSocket) -> Unit) {
        h.server.start()
        val base = h.server.url("/").toString().trimEnd('/')
        val settings = GatedSettings(InMemorySettings(initialBaseUrl = base, initialDeviceToken = "tthr_device"))
        val client = newClient(settings)
        val ws = connect(client)
        val hold = ClearHold(settings)
        val signingOut = Thread { signOut(client, ws) }
        try {
            signingOut.start()
            assertTrue("the sign-out's clear is held", hold.entered.await(20, TimeUnit.SECONDS))
            val snapshotRead = CountDownLatch(1)
            var windowStart: Job? = null
            settings.afterSessionRead = {
                settings.afterSessionRead = null
                windowStart = currentCoroutineContext()[Job]
                snapshotRead.countDown()
            }
            client.start()
            assertTrue(snapshotRead.await(20, TimeUnit.SECONDS))
            runBlocking { withTimeout(20_000) { windowStart!!.join() } }
            assertEquals("the snapshot still held the token", Credential.DeviceToken("tthr_device"), runBlocking { settings.session().credential })
        } finally {
            hold.release.complete(Unit)
        }
        signingOut.join(20_000)
        val deadline = System.currentTimeMillis() + 20_000
        while (runBlocking { settings.session().credential } != null) {
            assertTrue("the store was never cleared", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
        assertSignedOutForGood(client)
    }

    @Test
    fun aStartInsideTheLogoutsClearWindowNeverAdoptsTheForgottenCredential() = aStartInsideTheClearWindowNeverAdopts { client, _ ->
        assertEquals(LogoutResult.LocalOnly, runBlocking { client.logout() })
    }

    @Test
    fun aStartInsideTheRevocationsClearWindowNeverAdoptsTheRevokedCredential() = aStartInsideTheClearWindowNeverAdopts { client, ws ->
        ws.close(4001, "device revoked")
        h.await(client.signedOutReason) { it == SignedOutReason.DeviceUnpaired }
    }

    @Test
    fun aStartInsideTheStopsClearWindowNeverAdoptsTheClearedCredential() = aStartInsideTheClearWindowNeverAdopts { client, _ ->
        client.stop()
    }

    /**
     * L-B1: a sign-out with nothing in memory yet (the client never started: nothing to remember
     * as forgotten), then a start() that reads the store before the sign-out's clear lands and
     * takes the lock only after it has landed. The epoch moved again once the store was clear,
     * so the stale snapshot is refused.
     */
    private fun aStartStraddlingTheClearOfASignOutWithNothingInMemory(signOut: (RealTetherClient) -> Unit) {
        h.server.start()
        val base = h.server.url("/").toString().trimEnd('/')
        val settings = GatedSettings(InMemorySettings(initialBaseUrl = base, initialDeviceToken = "tthr_device"))
        val client = newClient(settings)
        val hold = ClearHold(settings)
        val clearLanded = CountDownLatch(1)
        settings.afterClear = { clearLanded.countDown() }
        val signingOut = Thread { signOut(client) }
        val snapshotRead = CountDownLatch(1)
        val resume = CompletableDeferred<Unit>()
        var start: Job? = null
        try {
            signingOut.start()
            assertTrue("the sign-out's clear is held", hold.entered.await(20, TimeUnit.SECONDS))
            settings.afterSessionRead = {
                settings.afterSessionRead = null
                start = currentCoroutineContext()[Job]
                snapshotRead.countDown()
                withTimeout(20_000) { resume.await() }
            }
            client.start()
            assertTrue(snapshotRead.await(20, TimeUnit.SECONDS))
            hold.release.complete(Unit)
            assertTrue(clearLanded.await(20, TimeUnit.SECONDS))
            signingOut.join(20_000)
            Thread.sleep(300) // the sign-out's own coroutine moves the epoch right after the clear
        } finally {
            hold.release.complete(Unit)
            resume.complete(Unit)
        }
        runBlocking { withTimeout(20_000) { start!!.join() } }
        assertSignedOutForGood(client)
    }

    @Test
    fun aStartStraddlingALogoutsClearNeverAdoptsTheStoredCredential() = aStartStraddlingTheClearOfASignOutWithNothingInMemory { client ->
        assertEquals(LogoutResult.LocalOnly, runBlocking { client.logout() })
    }

    @Test
    fun aStartStraddlingAStopsClearNeverAdoptsTheStoredCredential() = aStartStraddlingTheClearOfASignOutWithNothingInMemory { client ->
        client.stop()
    }

    /**
     * Verifier test gap: start() is called while signed out in memory (its coroutine has not run
     * yet) and a stop() lands before that coroutine is even dispatched. The epoch start() recorded
     * is the one before the stop, so the snapshot it then reads (the stop's clear still held) is
     * refused, although nothing was in memory to remember as forgotten.
     */
    @Test
    fun aStartWhoseCoroutineIsDispatchedOnlyAfterAStopNeverAdoptsTheStoredCredential() {
        h.server.start()
        val base = h.server.url("/").toString().trimEnd('/')
        val settings = GatedSettings(InMemorySettings(initialBaseUrl = base, initialDeviceToken = "tthr_device"))
        val held = HoldingDispatcher()
        val scope = CoroutineScope(SupervisorJob() + held).also { extraScopes += it }
        val client = newClient(settings, scope = scope)
        val hold = ClearHold(settings)
        var staleStart: Job? = null
        val snapshotRead = CountDownLatch(1)
        try {
            held.hold()
            client.start()
            client.stop()
            assertTrue("the stop's clear is held", hold.entered.await(20, TimeUnit.SECONDS))
            settings.afterSessionRead = {
                settings.afterSessionRead = null
                staleStart = currentCoroutineContext()[Job]
                snapshotRead.countDown()
            }
            held.release()
            assertTrue("start()'s coroutine ran", snapshotRead.await(20, TimeUnit.SECONDS))
            runBlocking { withTimeout(20_000) { staleStart!!.join() } }
            val before = h.server.requestCount
            assertEquals(FilesResult.Failed(FilesCopy.NOT_SIGNED_IN), runBlocking { client.files.list("/w") })
            assertEquals("nothing carried the stored token", before, h.server.requestCount)
        } finally {
            held.release()
            hold.release.complete(Unit)
        }
    }

    /**
     * L-X (pre-existing): a revocation verdict on the OLD cookie clears the store while a NEW
     * sign-in lands. The clear is a compare-and-clear under the store's lock, so it never
     * deletes the new sign-in's credential (which would flip configured and sign the next boot out).
     */
    @Test
    fun aLateRejectionNeverDeletesTheCredentialOfANewerSignIn() {
        h.server.start()
        val base = h.server.url("/").toString().trimEnd('/')
        val settings = GatedSettings(InMemorySettings(initialBaseUrl = base, initialCookie = "cookie-a"))
        val client = newClient(settings)
        val ws = connect(client)
        val hold = ClearHold(settings)
        val clearReturned = CountDownLatch(1)
        settings.afterClear = { clearReturned.countDown() }
        try {
            ws.close(4002, "session revoked")
            assertTrue("the rejection's clear is held", hold.entered.await(20, TimeUnit.SECONDS))
            h.await(client.signedOutReason) { it == SignedOutReason.SessionExpired }
            h.server.enqueue(MockResponse().setResponseCode(200).setBody(HEALTH_132))
            h.server.enqueue(MockResponse().setResponseCode(200).addHeader("Set-Cookie", "tether_session=cookie-b; Path=/").setBody("{}"))
            h.enqueueConnect()
            assertEquals(LoginResult.Success, runBlocking { client.login(base, "pw") })
            assertEquals(Credential.Cookie("cookie-b"), runBlocking { settings.session().credential })
        } finally {
            hold.release.complete(Unit)
        }
        assertTrue("the rejection's clear ran", clearReturned.await(20, TimeUnit.SECONDS))
        assertEquals("the new sign-in's credential survives", Credential.Cookie("cookie-b"), runBlocking { settings.session().credential })
        h.await(client.configured) { it }
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
