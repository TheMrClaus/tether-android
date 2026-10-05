package com.tether.app.client

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Takes one of the remaining failures: true = this call fails. */
private fun AtomicInteger.takeOne(): Boolean = getAndUpdate { if (it > 0) it - 1 else 0 } > 0

/**
 * ta-exi: the client's reads of the settings store on its own scope. That scope is the app scope
 * in production (fail-fast, no handler), so whatever escapes it is a crash. A store that does not
 * read is never fatal, reads as signed out (fail closed), recovers once it reads again, and never
 * undoes a sign-out.
 */
class StoreReadFailureTest {
    private val h = ConnectionHarness()
    private val extraScopes = mutableListOf<CoroutineScope>()

    @After fun tearDown() {
        h.close()
        extraScopes.forEach { it.cancel() }
    }

    /**
     * The store with failure seams: [session] throws for its next [sessionFailures] reads, a
     * [baseUrl] subscription throws at once for the next [flowFailures] ones, and every live
     * subscription fails as soon as [breaks] moves. Clears can run a hook first.
     */
    private class FailingSettings(
        private val inner: InMemorySettings,
        sessionFailures: Int = 0,
        flowFailures: Int = 0,
    ) : SettingsStore by inner {
        val sessionFailures = AtomicInteger(sessionFailures)
        val sessionReads = AtomicInteger()
        val flowFailures = AtomicInteger(flowFailures)
        val subscriptions = AtomicInteger()
        val breaks = MutableStateFlow(0)
        @Volatile var beforeClearIf: (suspend () -> Unit)? = null
        @Volatile var beforeClearCredential: (suspend () -> Unit)? = null

        override suspend fun session(): Session {
            sessionReads.incrementAndGet()
            if (sessionFailures.takeOne()) throw IOException("unreadable")
            return inner.session()
        }

        override val baseUrl: Flow<String?> = flow {
            subscriptions.incrementAndGet()
            if (this@FailingSettings.flowFailures.takeOne()) throw IOException("unreadable")
            val at = breaks.value
            combine(inner.baseUrl, breaks) { base, k -> base to k }.collect { (base, k) ->
                if (k != at) throw IOException("unreadable")
                emit(base)
            }
        }

        override suspend fun clearCredentialIf(expected: Credential): Boolean {
            beforeClearIf?.invoke()
            return inner.clearCredentialIf(expected)
        }

        override suspend fun clearCredential() {
            beforeClearCredential?.invoke()
            inner.clearCredential()
        }
    }

    /**
     * A client scope standing in for the app scope, whose handler records whatever reaches it (as
     * in SignOutRaceTest): anything recorded here is a crash in production.
     */
    private class RecordingScope {
        val escaped = CopyOnWriteArrayList<Throwable>()
        private val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default + CoroutineExceptionHandler { _, e -> escaped += e })

        /** Ends every coroutine of the scope; whatever failed has reached the handler once this returns. */
        fun drain(): List<String> {
            job.cancel()
            runBlocking { withTimeout(20_000) { job.join() } }
            return escaped.map { it.javaClass.name }
        }
    }

    private val recording = RecordingScope().also { extraScopes += it.scope }

    private fun base(): String {
        h.server.start()
        return h.server.url("/").toString().trimEnd('/')
    }

    private fun newClient(
        settings: SettingsStore,
        settingsBackoff: Backoff = Backoff(baseMs = 20, capMs = 20, random = { 1.0 }),
    ): RealTetherClient = RealTetherClient(
        settings = settings,
        httpClient = OkHttpClient(),
        scope = recording.scope,
        clock = { h.now.get() },
        backoff = testBackoff(),
        sweepIntervalMs = 3_600_000,
        scheduler = h.scheduler,
        settingsBackoff = settingsBackoff,
    ).also {
        it.settingsReadRetryMs = 10
        h.client = it
    }

    private fun take(): RecordedRequest = h.server.takeRequest(20, TimeUnit.SECONDS)!!

    private fun connect(client: RealTetherClient): okhttp3.WebSocket {
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        assertEquals("/api/auth/session", take().path)
        assertEquals("/ws", take().path)
        return ws
    }

    /** Fails at once when anything reached the scope's handler (a crash in production). */
    private fun assertNothingEscapedYet() =
        assertEquals("escaped the client scope", emptyList<String>(), recording.escaped.map { it.javaClass.name })

    private fun awaitCount(what: String, counter: AtomicInteger, atLeast: Int) {
        val deadline = System.currentTimeMillis() + 20_000
        while (counter.get() < atLeast) {
            assertNothingEscapedYet()
            assertTrue("$what: ${counter.get()} < $atLeast", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
    }

    /** [flow] reaches [predicate], polled; an escape fails the wait at once instead of a timeout. */
    private fun <T> awaitValue(flow: StateFlow<T>, predicate: (T) -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!predicate(flow.value)) {
            assertNothingEscapedYet()
            assertTrue("still ${flow.value}", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
    }

    /** No credential in memory: nothing goes out with one, and nothing went out at all. */
    private fun assertNothingInForce(client: RealTetherClient, requestsBefore: Int) {
        assertEquals(FilesResult.Failed(FilesCopy.NOT_SIGNED_IN), runBlocking { client.files.list("/w") })
        assertTrue("no credential in force", runBlocking { client.fetchStats() } is StatsResult.Failed)
        assertEquals("nothing carried a credential", requestsBefore, h.server.requestCount)
    }

    // ------------------------------------------------------------------
    // Item 1: start()'s settings.session()
    // ------------------------------------------------------------------

    /** A store that never reads: the start tries it 3 times, then fails closed, and nothing escapes. */
    @Test
    fun aStartWhoseStoreNeverReadsFailsClosedAndNothingEscapes() {
        val settings = FailingSettings(InMemorySettings(initialBaseUrl = base(), initialDeviceToken = "tthr_device"), sessionFailures = 1_000)
        val client = newClient(settings)
        client.start()
        awaitValue(client.connection) { it == ConnectionState.AuthRequired }
        assertEquals("tried, then tried twice more", 3, settings.sessionReads.get())
        assertNull("not a server verdict: no signed-out reason", client.signedOutReason.value)
        assertNothingInForce(client, requestsBefore = 0)
        Thread.sleep(300)
        assertEquals("no probe, no socket", 0, h.server.requestCount)
        assertEquals("escaped the client scope", emptyList<String>(), recording.drain())
    }

    /** A read that fails once (a transient I/O error) is retried, and the stored sign-in connects. */
    @Test
    fun aStartWhoseFirstReadFailsRetriesAndSignsIn() {
        val settings = FailingSettings(InMemorySettings(initialBaseUrl = base(), initialDeviceToken = "tthr_device"), sessionFailures = 1)
        val client = newClient(settings)
        connect(client)
        assertEquals(2, settings.sessionReads.get())
        assertEquals("escaped the client scope", emptyList<String>(), recording.drain())
    }

    /** After a start that failed closed, the next start() (the UI coming back) reads again and signs in. */
    @Test
    fun theNextStartAfterAFailedReadSignsIn() {
        val settings = FailingSettings(InMemorySettings(initialBaseUrl = base(), initialDeviceToken = "tthr_device"), sessionFailures = 3)
        val client = newClient(settings)
        client.start()
        awaitValue(client.connection) { it == ConnectionState.AuthRequired }
        assertEquals(0, h.server.requestCount)
        connect(client)
        assertEquals("escaped the client scope", emptyList<String>(), recording.drain())
    }

    /** A failed re-read never signs out a sign-in already in memory: the live connection stays. */
    @Test
    fun aFailedReadNeverDropsAPairAlreadyInMemory() {
        val settings = FailingSettings(InMemorySettings(initialBaseUrl = base(), initialDeviceToken = "tthr_device"))
        val client = newClient(settings)
        connect(client)
        val before = h.server.requestCount
        settings.sessionFailures.set(1_000)
        client.start()
        awaitCount("start()'s reads", settings.sessionReads, 1 + 3)
        Thread.sleep(300)
        assertEquals(ConnectionState.Connected, client.connection.value)
        assertEquals("no new probe or socket", before, h.server.requestCount)
        assertEquals("escaped the client scope", emptyList<String>(), recording.drain())
    }

    /** A start whose read fails after a logout stays signed out, and so does the next readable one. */
    @Test
    fun aFailedReadNeverUndoesALogout() {
        val settings = FailingSettings(InMemorySettings(initialBaseUrl = base(), initialDeviceToken = "tthr_device"))
        val client = newClient(settings)
        connect(client)
        assertEquals(LogoutResult.LocalOnly, runBlocking { client.logout() })
        val before = h.server.requestCount
        val reads = settings.sessionReads.get()
        settings.sessionFailures.set(1_000)
        client.start()
        awaitCount("start()'s reads", settings.sessionReads, reads + 3)
        h.await(client.connection) { it == ConnectionState.AuthRequired }
        assertNothingInForce(client, before)

        settings.sessionFailures.set(0)
        h.enqueueConnect()
        client.start()
        awaitCount("start()'s read", settings.sessionReads, reads + 4)
        Thread.sleep(500)
        assertEquals(ConnectionState.AuthRequired, client.connection.value)
        assertEquals("no probe, no socket", before, h.server.requestCount)
        assertEquals("escaped the client scope", emptyList<String>(), recording.drain())
    }

    // ------------------------------------------------------------------
    // Item 2: the configured / storedSettingsLoaded collector
    // ------------------------------------------------------------------

    /**
     * A settings flow that never reads: loaded (nothing waits forever), not configured (the login
     * screen, never a signed-in UI), resubscribed with a backoff, and nothing escapes.
     */
    @Test
    fun aSettingsFlowThatNeverReadsFailsClosedAndNothingEscapes() {
        val settings = FailingSettings(InMemorySettings(initialBaseUrl = base(), initialDeviceToken = "tthr_device"), flowFailures = 1_000)
        val client = newClient(settings)
        awaitValue(client.storedSettingsLoaded) { it }
        assertFalse("a store that does not read is not signed in", client.configured.value)
        awaitCount("resubscriptions", settings.subscriptions, 3)
        assertFalse(client.configured.value)
        assertNull(client.serverUrl.value)
        assertEquals("escaped the client scope", emptyList<String>(), recording.drain())
    }

    /** A settings flow that fails once recovers on its own: the stored sign-in shows. */
    @Test
    fun aSettingsFlowThatFailsOnceRecovers() {
        val base = base()
        val settings = FailingSettings(InMemorySettings(initialBaseUrl = base, initialDeviceToken = "tthr_device"), flowFailures = 1)
        val client = newClient(settings)
        awaitValue(client.configured) { it }
        assertEquals(base, client.serverUrl.value)
        assertTrue(client.storedSettingsLoaded.value)
        assertEquals("escaped the client scope", emptyList<String>(), recording.drain())
    }

    /**
     * Signed in, then the flow errors: signed out (fail closed) until the resubscription reads
     * the store again. After a logout the same error and recovery never read as signed in.
     */
    @Test
    fun aSettingsFlowThatBreaksReadsAsSignedOutThenRecoversWithoutUndoingALogout() {
        val base = base()
        val settings = FailingSettings(InMemorySettings(initialBaseUrl = base, initialDeviceToken = "tthr_device"))
        // 400 ms before each resubscription: long enough to see the signed-out state in between.
        val client = newClient(settings, settingsBackoff = Backoff(baseMs = 400, capMs = 400, random = { 1.0 }))
        h.await(client.configured) { it }
        settings.breaks.value++
        h.await(client.configured) { !it }
        assertTrue(client.storedSettingsLoaded.value)
        assertEquals("the last stored server stays (login prefill)", base, client.serverUrl.value)
        h.await(client.configured) { it }

        connect(client)
        assertEquals(LogoutResult.LocalOnly, runBlocking { client.logout() })
        h.await(client.configured) { !it }
        val subscribed = settings.subscriptions.get()
        settings.breaks.value++
        awaitCount("resubscription", settings.subscriptions, subscribed + 1)
        Thread.sleep(300)
        assertFalse("a recovered read never undoes the logout", client.configured.value)
        assertEquals("escaped the client scope", emptyList<String>(), recording.drain())
    }

    // ------------------------------------------------------------------
    // Item 3: the rejection path's clearCredentialIf
    // ------------------------------------------------------------------

    /**
     * A CancellationException out of the rejection's compare-and-clear is rethrown (its coroutine
     * ends cancelled, not completed), never reaches the app scope's handler, still releases the
     * clear in flight, and the revocation stands: the next start() does not readopt the
     * credential the store still holds.
     */
    @Test
    fun aRejectionWhoseClearIsCancelledRethrowsAndTheSignOutStands() {
        val base = base()
        val settings = FailingSettings(InMemorySettings(initialBaseUrl = base, initialDeviceToken = "tthr_device"))
        val client = newClient(settings)
        client.signOutClearWaitMs = 15_000
        val ws = connect(client)
        val clearJob = java.util.concurrent.atomic.AtomicReference<Job?>()
        settings.beforeClearIf = {
            settings.beforeClearIf = null
            clearJob.set(currentCoroutineContext()[Job])
            throw CancellationException("the store's own")
        }
        ws.close(4001, "device revoked")
        h.await(client.signedOutReason) { it == SignedOutReason.DeviceUnpaired }
        val deadline = System.currentTimeMillis() + 20_000
        while (clearJob.get() == null) {
            assertTrue("the rejection's clear ran", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
        val job = clearJob.get()!!
        runBlocking { withTimeout(20_000) { job.join() } }
        assertTrue("the CancellationException was swallowed", job.isCancelled)

        assertEquals("the clear failed: the store still holds it", Credential.DeviceToken("tthr_device"), runBlocking { settings.session().credential })
        val before = h.server.requestCount
        val reads = settings.sessionReads.get()
        client.start()
        awaitCount("start()'s read", settings.sessionReads, reads + 1)
        Thread.sleep(500)
        assertEquals(ConnectionState.AuthRequired, client.connection.value)
        assertEquals("the revoked token was not readopted", before, h.server.requestCount)
        assertNothingInForce(client, before)

        // The clear in flight was released: a sign-in does not wait for it.
        h.server.enqueue(MockResponse().setResponseCode(200).setBody(HEALTH_143))
        h.server.enqueue(MockResponse().setResponseCode(200).addHeader("Set-Cookie", "tether_session=cookie-b; Path=/").setBody("{}"))
        h.enqueueConnect()
        val started = System.currentTimeMillis()
        assertEquals(LoginResult.Success, runBlocking { client.login(base, "pw") })
        assertTrue("the sign-in waited for a clear that had ended", System.currentTimeMillis() - started < 5_000)
        assertEquals("escaped the client scope", emptyList<String>(), recording.drain())
    }

    // ------------------------------------------------------------------
    // Item 4: logoutNow()'s clearCredential (pinned: kept swallowing on purpose)
    // ------------------------------------------------------------------

    /**
     * logout() runs NonCancellable, so a CancellationException out of its clearCredential is the
     * store's own: it counts as a failed clear (retried once), and the logout still runs to the
     * end (the cookie is revoked server-side). Rethrowing it would end the logout half-way.
     */
    @Test
    fun aLogoutWhoseClearThrowsACancellationStillRunsToTheEnd() {
        val settings = FailingSettings(InMemorySettings(initialBaseUrl = base(), initialCookie = "cookie-1"))
        val client = newClient(settings)
        connect(client)
        val attempts = AtomicInteger()
        settings.beforeClearCredential = {
            if (attempts.incrementAndGet() == 1) throw CancellationException("the store's own")
        }
        h.server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertEquals(LogoutResult.Revoked, runBlocking { client.logout() })
        assertEquals("retried once", 2, attempts.get())
        assertNull("the retry cleared the store", runBlocking { settings.session().credential })
        var revoke: RecordedRequest? = null
        while (revoke == null) {
            val r = h.server.takeRequest(20, TimeUnit.SECONDS) ?: break
            if (r.path == "/api/auth/logout") revoke = r
        }
        assertNotNull("the cookie was revoked server-side", revoke)
        assertEquals(ConnectionState.AuthRequired, client.connection.value)
        assertEquals("escaped the client scope", emptyList<String>(), recording.drain())
    }
}
