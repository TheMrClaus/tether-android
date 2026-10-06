package com.tether.app.client

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-rv0o 1l: the socket waits at the local-bind gate for start()'s bind (the unsent-input read) and the
 * wait is bounded. When it times out the end state is that of a settings read that failed: nothing
 * adopted, nothing opened, the login screen (never a connect on the pair the bind never finished with).
 */
class LocalBindTimeoutTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val paths = ConcurrentLinkedQueue<String>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path.orEmpty()
                return if (request.path == "/api/auth/session") {
                    MockResponse().setHeader("Content-Type", "application/json").setBody("""{"authenticated":true}""")
                } else if (request.path == "/healthz") {
                    MockResponse().setHeader("Content-Type", "application/json").setBody(HEALTH_143)
                } else if (request.path == "/api/auth/login") {
                    MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true}""")
                        .addHeader("set-cookie", "tether_session=fresh; Path=/; HttpOnly")
                } else {
                    MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
                }
            }
        }
        server.start()
    }

    @After fun tearDown() {
        scope.cancel()
        runCatching { server.shutdown() }
    }

    private val base get() = server.url("/").toString().trimEnd('/')

    private fun client(settings: SettingsStore, timeoutMs: Long) = RealTetherClient(
        settings = settings,
        scope = scope,
        backoff = testBackoff(),
        sweepIntervalMs = 3_600_000,
    ).also { it.localBindTimeoutMs = timeoutMs }

    private fun awaitTrue(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(10_000) { while (!condition()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("never: $what", e)
        }
    }

    /** The stored server and credential, with the unsent-input read ([read]) deciding when it answers. */
    private inner class PendingReadSeam(
        private val inner: InMemorySettings = InMemorySettings(initialBaseUrl = base, initialCookie = "stored-cookie"),
        private val read: suspend () -> Unit,
    ) : SettingsStore by inner {
        override suspend fun readPendingInput(origin: String): String? {
            read()
            return inner.readPendingInput(origin)
        }
    }

    @Test fun aBindThatNeverEndsEndsAsAFailedSettingsReadDoesAndNothingConnects() {
        val hung = CompletableDeferred<Unit>()
        val client = client(PendingReadSeam { hung.await() }, timeoutMs = 400)
        client.start()
        awaitTrue("the login screen shows") { client.connection.value is ConnectionState.AuthRequired }
        Thread.sleep(500)
        // The same end state as a store that does not read at all.
        val failedRead = client(
            object : SettingsStore by InMemorySettings(initialBaseUrl = base, initialCookie = "stored-cookie") {
                override suspend fun session(): Session = throw java.io.IOException("store down")
            },
            timeoutMs = 400,
        ).also { it.settingsReadRetryMs = 10 }
        failedRead.start()
        awaitTrue("the failed read shows the login screen") { failedRead.connection.value is ConnectionState.AuthRequired }
        assertEquals(failedRead.connection.value, client.connection.value)
        assertEquals(failedRead.originStanding(OriginProbe.of(base)), client.originStanding(OriginProbe.of(base)))
        assertEquals(OriginStanding.SignedOut, client.originStanding(OriginProbe.of(base)))
        // The probe went out alongside the bind, but no socket was ever asked for, and the failed read sent nothing.
        assertEquals(listOf("/api/auth/session"), paths.toList())
        assertNull("no signed-out reason: no server said anything", client.signedOutReason.value)
        hung.complete(Unit)
    }

    /**
     * ta-huo3: the hung start() is let go of the gate when the wait times out, so the connects after it
     * (a fresh sign-in's) do not each wait out the timeout behind it.
     */
    @Test fun aFreshSignInAfterTheTimeoutDoesNotWaitOutTheTimeoutAgain() {
        val hung = CompletableDeferred<Unit>()
        val timeoutMs = 2_000L
        // Only start()'s own read hangs; the sign-in's bind reads the store too and answers.
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val client = client(PendingReadSeam { if (reads.getAndIncrement() == 0) hung.await() }, timeoutMs = timeoutMs)
        try {
            client.start()
            awaitTrue("the login screen shows") { client.connection.value is ConnectionState.AuthRequired }
            // The bind is STILL hung. A fresh sign-in connects on its own pair.
            val before = System.nanoTime()
            assertEquals(LoginResult.Success, runBlocking { client.login(base, "pw", "operator") })
            awaitTrue("the sign-in's connect reached the socket upgrade") { paths.any { it.startsWith("/ws") } }
            val tookMs = (System.nanoTime() - before) / 1_000_000
            assertTrue("it did not wait the timeout again (took ${tookMs} ms of a ${timeoutMs} ms gate)", tookMs < timeoutMs - 500)
        } finally {
            hung.complete(Unit)
        }
    }

    @Test fun aBindThatEndsInTimeConnectsAsBefore() {
        val client = client(PendingReadSeam { delay(300) }, timeoutMs = 5_000)
        client.start()
        awaitTrue("the socket was asked for after the bind") { paths.any { it != "/api/auth/session" } }
        assertTrue("never the login screen", client.connection.value !is ConnectionState.AuthRequired)
    }
}

/** The canonical origin of [base], as the client names it. */
private object OriginProbe {
    fun of(base: String): String = serverOrigin(base)!!
}
