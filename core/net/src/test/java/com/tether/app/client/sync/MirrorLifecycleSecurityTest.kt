package com.tether.app.client.sync

import com.tether.app.client.ConnectionState
import com.tether.app.client.HEALTH_143
import com.tether.app.client.LoginResult
import com.tether.app.client.snapshotFrame
import com.tether.app.client.type
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T13.1 round 2, client side (security review M1, M2, L1, L3): a sign-out's wipe survives a
 * death, a signed-out cold start never binds, a stuck or dead writer never hangs or breaks the
 * client, a read of a wiped binding never publishes, and only known sessions are mirrored.
 */
@RunWith(RobolectricTestRunner::class)
class MirrorLifecycleSecurityTest {
    private var h = MirrorHarness()

    @Before
    fun setUp() = h.startServer()

    @After
    fun tearDown() = h.close()

    private val state = """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","turnOrder":[],"turnsById":{},"queuedMessages":[],"marker":"secret-transcript"}"""

    private fun sessionJson(id: String) =
        """{"id":"$id","provider":"claude","name":"n-$id","cwd":"/w","status":"ready","startedAt":1,"updatedAt":1,
            "pinned":false,"runtimeArchived":false,"mode":"headless"}"""

    private fun ready(vararg ids: String) =
        """{"type":"ready","protocolVersion":143,"nativeProtocolFloor":129,"providers":[],"workspaceRoot":null,
            "sessions":[${ids.joinToString(",") { sessionJson(it) }}]}"""

    /** A process that leaves s1 mirrored with a cursor, then dies cleanly. */
    private fun mirroredThenKilled() {
        h.boot(ready = ready("s1"))
        h.client.attach("s1")
        h.expectFrame("attach")
        h.ws.send(snapshotFrame("s1", 5, state))
        h.serverBarrier()
        h.dbSession("s1")
        h.kill(flushFirst = true)
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            assertTrue("timed out: $what", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }

    @Test
    fun aLogoutKilledBeforeTheWriterRanLeavesNothingReadable() {
        h.boot(ready = ready("s1"))
        h.client.attach("s1")
        h.expectFrame("attach")
        h.ws.send(snapshotFrame("s1", 5, state))
        h.serverBarrier()
        h.dbSession("s1")
        // Hold the writer so the Wipe op cannot run before the "death". The held read is queued
        // asynchronously: wait until the writer is inside it, or the wipe's queue drop (ta-hra
        // R1) would cancel it before it ran and nothing would be held (verifier F1).
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        h.mirror.beforeHydrateRead = {
            entered.countDown()
            gate.await(20, TimeUnit.SECONDS)
        }
        h.client.attach("held")
        assertTrue("the writer is held", entered.await(20, TimeUnit.SECONDS))
        h.server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) // POST /api/auth/logout
        runBlocking { h.client.logout() }
        // The keys went on logout's own thread, the writer still held.
        assertFalse(h.keyFile.exists())
        assertTrue(h.kek.destroyed >= 1)
        assertTrue("the writer never deleted the DB", h.dbFactory.existing().isNotEmpty())
        Thread { Thread.sleep(300); gate.countDown() }.start()
        h.kill(flushFirst = false)

        // Next cold start: the URL is kept, no credential, and (as in production) no start().
        // The DB goes; nothing is shown.
        h.bootSignedOut()
        awaitTrue("mirror files deleted") { h.dbFactory.existing().isEmpty() }
        assertTrue(h.client.sessions.value.isEmpty())
        assertTrue(h.client.projectionTrees.value.isEmpty())
    }

    @Test
    fun aSignedOutColdStartPurgesInsteadOfBindingEvenWithTheKeysIntact() {
        mirroredThenKilled()
        // The credential is gone but the mirror and its keys are intact (e.g. an older build's
        // sign-out, or a revocation whose wipe never ran).
        runBlocking { h.settings.clearCredential() }
        assertTrue(h.keyFile.exists())
        h.bootSignedOut() // no start(): the app starts the client only once configured (ta-jt9 L-A)
        awaitTrue("mirror purged") { h.dbFactory.existing().isEmpty() && !h.keyFile.exists() }
        assertTrue("the old list is never published under AuthRequired", h.client.sessions.value.isEmpty())
    }

    private fun loginAgain(cookie: String = "again") {
        h.server.enqueue(MockResponse().setResponseCode(200).setBody(HEALTH_143))
        h.server.enqueue(MockResponse().setResponseCode(200).addHeader("Set-Cookie", "tether_session=$cookie; Path=/").setBody("{}"))
        h.enqueueConnect()
    }

    /** The socket of the sign-in in progress: ready -> hello -> Connected. */
    private fun handshakeNewSocket(vararg ids: String): okhttp3.WebSocket {
        val ws = h.sockets.poll(20, TimeUnit.SECONDS)
        assertNotNull("the sign-in never reached the ws upgrade", ws)
        ws!!.send(ready(*ids))
        h.expectFrame("hello")
        h.await(h.client.connection) { it == ConnectionState.Connected }
        return ws
    }

    /**
     * ta-jt9 L-A: a death between logout's clearCredential and its shred leaves the credential
     * gone but the mirror and its keys intact. The next boot is signed out, so (as in production)
     * nothing calls start(): the purge must run anyway. A later sign-in to the SAME server then
     * gets an empty mirror, never the previous sign-in's copy or cursors.
     */
    @Test
    fun aSignedOutBootPurgesWithoutStartAndASameOriginSignInGetsAnEmptyMirror() {
        mirroredThenKilled()
        runBlocking { h.settings.clearCredential() }
        assertTrue(h.keyFile.exists())
        assertTrue(h.dbFactory.existing().isNotEmpty())

        h.bootSignedOut()
        awaitTrue("purged with no start()") { !h.keyFile.exists() && h.dbFactory.existing().isEmpty() }

        h.received.clear()
        loginAgain()
        assertEquals(LoginResult.Success, runBlocking { h.client.login(h.server.url("/").toString(), "pw") })
        handshakeNewSocket("s1")
        // The new binding holds nothing of the previous sign-in's copy.
        assertNull("the previous sign-in's copy was readable by the next one", h.dbSession("s1"))
        assertTrue("no restored cursor asked for a delta", h.framesUntilBarrier().none { it.type() == "attach" })
        h.client.attach("s1")
        assertNull("a full attach: no cursor restored from the old copy", h.expectFrame("attach")["afterSeq"])
    }

    /**
     * ta-jt9 L-A1: a transient Keystore error at boot reads as "no credential" (the sealed one
     * is kept for a retry), so the boot is signed out. That user never signed out: the purge must
     * keep their copy, and once the store reads again the next start restores it.
     */
    @Test
    fun aTransientKeystoreErrorAtBootKeepsTheMirrorAndALaterReadRestoresIt() {
        mirroredThenKilled()
        h.credentialUnreadable = true
        val p = h.bootSignedOut()
        assertTrue("the purge decided", runBlocking { kotlinx.coroutines.withTimeout(20_000) { p.client.bootPurgeOutcome.await() } })
        assertTrue("the key survives an unreadable credential", h.keyFile.exists())
        assertTrue("the copy survives an unreadable credential", h.dbFactory.existing().isNotEmpty())
        assertEquals(0, h.kek.destroyed)
        h.kill(flushFirst = false)

        h.credentialUnreadable = false
        h.boot(ready = ready("s1"))
        // The restored cursor asks for a delta: the copy was kept and is read again.
        val attach = h.expectFrame("attach")
        assertEquals("s1", attach["sessionId"]!!.jsonPrimitive.content)
        assertEquals(5L, attach["afterSeq"]!!.jsonPrimitive.longOrNull)
        assertNotNull(h.dbSession("s1"))
    }

    /**
     * ta-jt9 L-A2 (verifier R1): the boot purge's store read is held while a sign-in to the same
     * server goes through. The sign-in must wait for the purge: it neither binds nor writes the
     * store first, so the purge still sees the signed-out store, shreds the old copy, and the new
     * sign-in gets an empty mirror (no old copy, no delta attach).
     */
    @Test
    fun aSignInRacingTheBootPurgeWaitsForItAndNeverReadsTheOldCopy() {
        mirroredThenKilled()
        runBlocking { h.settings.clearCredential() }
        val entered = CountDownLatch(1)
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.beforeCredentialState = {
            h.beforeCredentialState = null
            entered.countDown()
            kotlinx.coroutines.withTimeout(20_000) { release.await() }
        }
        var login: Thread? = null
        try {
            h.bootSignedOut()
            assertTrue("the purge's read is held", entered.await(20, TimeUnit.SECONDS))
            assertTrue(h.keyFile.exists())
            h.received.clear()
            loginAgain()
            login = Thread { assertEquals(LoginResult.Success, runBlocking { h.client.login(h.server.url("/").toString(), "pw") }) }
            login.start()
            while (true) {
                val request = h.server.takeRequest(20, TimeUnit.SECONDS) ?: throw AssertionError("no login request")
                if (request.path == "/api/auth/login") break
            }
            Thread.sleep(300) // the sign-in is past the server and would adopt and bind now
        } finally {
            release.complete(Unit)
        }
        login!!.join(20_000)
        handshakeNewSocket("s1")
        assertTrue("the old key was shredded", h.kek.destroyed >= 1)
        assertNull("the previous sign-in's copy was readable by the next one", h.dbSession("s1"))
        assertTrue("no restored cursor asked for a delta", h.framesUntilBarrier().none { it.type() == "attach" })
        h.client.attach("s1")
        assertNull("a full attach", h.expectFrame("attach")["afterSeq"])
    }

    /**
     * ta-jt9 L-A2: a boot purge that cannot decide (the store read throws) leaves this process
     * without a mirror (nothing binds a copy nobody decided on) and deletes nothing: the next
     * boot decides again and, still signed in, restores the copy.
     */
    @Test
    fun aBootPurgeThatCannotDecideRunsTheProcessWithoutAMirrorAndKeepsTheCopy() {
        mirroredThenKilled()
        h.beforeCredentialState = {
            h.beforeCredentialState = null
            throw java.io.IOException("store unreadable")
        }
        h.boot(ready = ready("s1"))
        assertFalse("undecided", runBlocking { kotlinx.coroutines.withTimeout(20_000) { h.client.bootPurgeOutcome.await() } })
        assertTrue("no restored cursor asked for a delta", h.framesUntilBarrier().none { it.type() == "attach" })
        h.client.attach("s1")
        assertNull("a full attach: nothing restored", h.expectFrame("attach")["afterSeq"])
        assertTrue("nothing deleted", h.keyFile.exists() && h.dbFactory.existing().isNotEmpty())
        h.kill(flushFirst = false)

        h.boot(ready = ready("s1"))
        assertEquals(5L, h.expectFrame("attach")["afterSeq"]!!.jsonPrimitive.longOrNull)
    }

    /**
     * ta-jt9 L-A2: a boot purge that is still undecided when a sign-in arrives (its read held
     * past the bound) never holds that sign-in for longer than the bound, the sign-in runs with
     * no mirror, and the previous sign-in's copy is shredded anyway: the purge's late decision,
     * made on the store the sign-in has changed, must not keep it for the next boot. L-2: the
     * shred is complete BEFORE the sign-in seals its credential, so a death right after that
     * never leaves a boot that reads Present next to a readable old copy.
     */
    @Test
    fun anOverdueBootPurgeNeverHoldsASignInAndThePreviousCopyIsStillShredded() {
        mirroredThenKilled() // with the default (generous) bind bound: the copy is really there
        runBlocking { h.settings.clearCredential() }
        assertTrue("the old key is there before the race", h.keyFile.exists())
        assertTrue("the old copy is there before the race", h.dbFactory.existing().isNotEmpty())
        val entered = CountDownLatch(1)
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.beforeCredentialState = {
            h.beforeCredentialState = null
            entered.countDown()
            kotlinx.coroutines.withTimeout(20_000) { release.await() }
        }
        // The shred's Keystore delete is slow, still well inside the bound.
        h.kek.beforeDestroyKey = {
            h.kek.beforeDestroyKey = null
            Thread.sleep(400)
        }
        var shredDoneAtSetServer: Boolean? = null
        h.beforeSetServer = {
            h.beforeSetServer = null
            shredDoneAtSetServer = !h.keyFile.exists() && h.kek.destroyed >= 1
        }
        h.bindTimeoutMs = 1_000 // only this process: the purge is overdue after 1 s
        try {
            h.bootSignedOut()
            assertTrue("the purge's read is held", entered.await(20, TimeUnit.SECONDS))
            h.received.clear()
            loginAgain()
            val started = System.currentTimeMillis()
            assertEquals(LoginResult.Success, runBlocking { h.client.login(h.server.url("/").toString(), "pw") })
            assertTrue("the sign-in waited past the bound", System.currentTimeMillis() - started < 10_000)
            assertEquals("the shred was complete before the new credential was sealed", true, shredDoneAtSetServer)
            handshakeNewSocket("s1")
            assertTrue("no restored cursor asked for a delta", h.framesUntilBarrier().none { it.type() == "attach" })
            awaitTrue("the previous copy shredded") { !h.keyFile.exists() && h.dbFactory.existing().isEmpty() }
        } finally {
            release.complete(Unit)
        }
        assertTrue(runBlocking { kotlinx.coroutines.withTimeout(20_000) { h.client.bootPurgeOutcome.await() } })
        assertFalse("the late decision minted or kept nothing", h.keyFile.exists())
    }

    /**
     * ta-jt9 I-3: the server has minted a credential (a session cookie, or a device token for a
     * single-use code now spent), then the caller of login() / pair() is cancelled while the
     * sign-in waits for the boot purge. The credential is adopted and stored anyway: otherwise a
     * live cookie session is never revoked, or the claimed device token is orphaned.
     */
    private fun aSignInWhoseCallerIsCancelledAfterTheServerAnswered(
        route: String,
        enqueue: () -> Unit,
        signIn: suspend () -> Unit,
        minted: com.tether.app.client.Credential,
    ) {
        mirroredThenKilled()
        runBlocking { h.settings.clearCredential() }
        val entered = CountDownLatch(1)
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.beforeCredentialState = {
            h.beforeCredentialState = null
            entered.countDown()
            kotlinx.coroutines.withTimeout(20_000) { release.await() }
        }
        val caller = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            h.bootSignedOut()
            assertTrue("the purge's read is held", entered.await(20, TimeUnit.SECONDS))
            h.received.clear()
            enqueue()
            val job = caller.launch { signIn() }
            while (true) {
                val request = h.server.takeRequest(20, TimeUnit.SECONDS) ?: throw AssertionError("no $route request")
                if (request.path == route) break
            }
            Thread.sleep(300) // past the server's 200: waiting for the purge now
            job.cancel()
        } finally {
            release.complete(Unit)
        }
        awaitTrue("the minted credential was stored") { runBlocking { h.settings.session().credential } == minted }
        handshakeNewSocket("s1")
        caller.cancel()
    }

    @Test
    fun aLoginWhoseCallerIsCancelledAfterTheServerAnsweredIsStillAdopted() = aSignInWhoseCallerIsCancelledAfterTheServerAnswered(
        route = "/api/auth/login",
        enqueue = { loginAgain() },
        signIn = { h.client.login(h.server.url("/").toString(), "pw") },
        minted = com.tether.app.client.Credential.Cookie("again"),
    )

    @Test
    fun aPairingWhoseCallerIsCancelledAfterTheServerAnsweredIsStillAdopted() = aSignInWhoseCallerIsCancelledAfterTheServerAnswered(
        route = "/api/devices/claim",
        enqueue = {
            h.server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"protocolVersion":143,"nativeProtocolFloor":129,"pairing":true}"""))
            h.server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"tthr_minted"}"""))
            h.enqueueConnect()
        },
        signIn = { h.client.pair(h.server.url("/").toString(), "123456", "test phone") },
        minted = com.tether.app.client.Credential.DeviceToken("tthr_minted"),
    )

    /** L-A with nothing configured at all (after a stop(): no URL either): still purged. */
    @Test
    fun aBootWithNoServerAndNoCredentialStillPurges() {
        mirroredThenKilled()
        h.settings = com.tether.app.client.InMemorySettings()
        h.bootSignedOut()
        awaitTrue("purged with no server configured") { !h.keyFile.exists() && h.dbFactory.existing().isEmpty() }
    }

    /**
     * ta-jt9 I-C: a sign-in that lands while logout's shred is still in the Keystore owns the
     * connection state. When the shred returns, the logout must not report AuthRequired over the
     * live sign-in.
     */
    @Test
    fun aSignInThatLandsDuringTheLogoutShredKeepsItsConnectedState() {
        h.boot(ready = ready("s1"))
        val inShred = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.kek.beforeDestroyKey = {
            h.kek.beforeDestroyKey = null
            inShred.countDown()
            release.await(20, TimeUnit.SECONDS)
        }
        loginAgain()
        h.server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) // the logout's revoke, after the shred
        val logout = Thread { runBlocking { h.client.logout() } }
        try {
            logout.start()
            assertTrue("the logout is inside the Keystore delete", inShred.await(20, TimeUnit.SECONDS))
            assertEquals("never Connected while signing out", ConnectionState.Disconnected, h.client.connection.value)
            h.received.clear()
            assertEquals(LoginResult.Success, runBlocking { h.client.login(h.server.url("/").toString(), "pw") })
            handshakeNewSocket("s1")
        } finally {
            release.countDown()
        }
        logout.join(20_000)
        assertFalse("logout returned", logout.isAlive)
        assertEquals(ConnectionState.Connected, h.client.connection.value)
    }

    /**
     * ta-hra L-2 (verifier P3): start()'s bind runs in a coroutine logout never cancels. Hold it
     * inside the Keystore, log out (the wipe overtakes it, so it will answer null) and sign in to
     * the same server. The stale null must not switch the mirror off for the new sign-in: the
     * per-binding generation checks drop it. Proven by the new sign-in's frames being mirrored.
     */
    @Test
    fun aStaleStartBindAnsweringAfterALogoutAndASameOriginSignInLeavesTheNewMirrorOn() {
        mirroredThenKilled()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.kek.beforeExistingKey = {
            h.kek.beforeExistingKey = null
            entered.countDown()
            release.await(20, TimeUnit.SECONDS)
        }
        var login: Thread? = null
        try {
            h.bootStartOnly()
            assertTrue("start()'s bind is inside the Keystore", entered.await(20, TimeUnit.SECONDS))
            h.server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) // logout
            runBlocking { h.client.logout() }
            assertFalse(h.keyFile.exists())
            h.received.clear()
            loginAgain()
            login = Thread { assertEquals(LoginResult.Success, runBlocking { h.client.login(h.server.url("/").toString(), "pw") }) }
            login.start()
            while (true) {
                val request = h.server.takeRequest(20, TimeUnit.SECONDS) ?: throw AssertionError("no login request")
                if (request.path == "/api/auth/login") break
            }
        } finally {
            release.countDown()
        }
        login!!.join(20_000)
        val ws = handshakeNewSocket("s1")
        h.client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 7, state.replace("secret-transcript", "new-sign-in")))
        h.await(h.client.projectionTrees) { it.containsKey("s1") }
        val copy = h.dbFold("s1")
        assertNotNull("the stale bind switched the mirror off for the new sign-in", copy)
        assertTrue("the new sign-in's copy", copy.toString().contains("new-sign-in"))
    }

    @Test
    fun aStuckWriterNeverHangsTheStartAndTheClientRunsWithoutAMirror() {
        h.close()
        // A dispatcher that never runs anything: the writer never answers bind().
        val never = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        }
        h = MirrorHarness(mirrorDispatcher = never, bindTimeoutMs = 5_000) // the production bound
        h.startServer()
        val started = System.currentTimeMillis()
        h.boot(ready = ready("s1"))
        assertTrue("start waited at most the bind bound", System.currentTimeMillis() - started < 9_000)
        h.client.attach("s1")
        h.expectFrame("attach")
        h.ws.send(snapshotFrame("s1", 5, state))
        h.await(h.client.projectionTrees) { it.containsKey("s1") }
    }

    @Test
    fun aDeadWriterLeavesTheClientWorkingAndALogoutStillWipes() {
        h.boot(ready = ready("s1", "s2"))
        h.client.attach("s1")
        h.expectFrame("attach")
        h.ws.send(snapshotFrame("s1", 5, state))
        h.serverBarrier()
        h.dbSession("s1")
        h.mirror.beforeHydrateRead = { throw OutOfMemoryError("simulated") }
        h.client.attach("s2") // its read kills the writer
        awaitTrue("writer dead") { h.mirror.dead }
        // Live frames still fold in memory.
        h.ws.send("""{"type":"event","sessionId":"s1","event":{"type":"turn_started","turnId":"t1","seq":6,"ts":6}}""")
        h.serverBarrier()
        assertEquals(com.tether.app.protocol.tree.JsStr("t1"), h.client.projectionTrees.value.getValue("s1")["activeTurnId"])
        h.server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        runBlocking { h.client.logout() }
        assertFalse(h.keyFile.exists())
        assertTrue(h.dbFactory.existing().isEmpty())
    }

    @Test
    fun aReadOfAWipedBindingNeverPublishesEvenAfterARebindToTheSameServer() {
        mirroredThenKilled()
        val gate = CountDownLatch(1)
        // The ready re-attach hydrates s1: hold that read from the start.
        h.boot(ready = ready("s1")) { p -> p.mirror.beforeHydrateRead = { gate.await(10, TimeUnit.SECONDS) } }
        h.server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) // logout
        runBlocking { h.client.logout() }
        // Sign in again to the SAME server while the old read is still held.
        h.server.enqueue(MockResponse().setResponseCode(200).setBody(HEALTH_143))
        h.server.enqueue(MockResponse().setResponseCode(200).addHeader("Set-Cookie", "tether_session=again; Path=/").setBody("{}"))
        h.enqueueConnect()
        val login = Thread { assertEquals(LoginResult.Success, runBlocking { h.client.login(h.server.url("/").toString(), "pw") }) }
        login.start()
        while (true) {
            val request = h.server.takeRequest(10, TimeUnit.SECONDS) ?: throw AssertionError("no login request")
            if (request.path == "/api/auth/login") break
        }
        Thread.sleep(300) // the sign-in re-binds the mirror (same origin, new binding) and waits on the writer
        // The UI opens s1 under the NEW binding: a new read is queued behind the old one. The old
        // read must not land in the new binding's place (per-binding generation, L1).
        h.client.attach("s1")
        gate.countDown()
        login.join(15_000)
        runBlocking { h.mirror.flush() }
        Thread.sleep(100)
        assertNull("a wiped copy resurfaced after a re-sign-in", h.client.projectionTrees.value["s1"])
    }

    @Test
    fun aSessionOpenedRightAfterASwitchNeverShowsTheOldServersCopy() {
        mirroredThenKilled()
        // Process 2: signed in to server A, s1 mirrored there, not opened yet.
        h.boot(handshake = false)
        val b = okhttp3.mockwebserver.MockWebServer()
        try {
            b.start()
            b.enqueue(MockResponse().setResponseCode(200).setBody(HEALTH_143))
            b.enqueue(MockResponse().setResponseCode(200).addHeader("Set-Cookie", "tether_session=b; Path=/").setBody("{}"))
            b.enqueue(MockResponse().setResponseCode(200).setBody("""{"authenticated":true}"""))
            // In the window after the switch to B and before the mirror re-binds, the UI opens s1.
            h.client.raceHook = { point, _ ->
                if (point == com.tether.app.client.RacePoint.OriginSwitched) {
                    h.client.attach("s1")
                    // Let a read (if one was wrongly started) land INSIDE the window, before the re-bind.
                    val until = System.currentTimeMillis() + 1_500
                    while (System.currentTimeMillis() < until && !h.client.projectionTrees.value.containsKey("s1")) Thread.sleep(10)
                }
            }
            assertEquals(LoginResult.Success, runBlocking { h.client.login(b.url("/").toString(), "pw") })
            h.client.raceHook = null
            runBlocking { h.mirror.flush() }
            Thread.sleep(200)
            assertNull("server A's saved transcript shown under server B", h.client.projectionTrees.value["s1"])
        } finally {
            b.shutdown()
        }
    }

    /**
     * Verifier repro (round 3): after a restart the writer dies on the hydration read, the
     * restored cursor got a stateless reply, and the dead writer answers "no copy". The session
     * must not stay blank: the client falls back to a full attach, and live events show.
     */
    @Test
    fun aRestoredSessionWhoseReadDiesIsFullyReattachedNotLeftBlank() {
        mirroredThenKilled()
        h.boot(ready = ready("s1"), handshake = false) { p -> p.mirror.beforeHydrateRead = { throw OutOfMemoryError("simulated") } }
        h.ws.send(ready("s1"))
        h.expectFrame("hello")
        h.await(h.client.connection) { it == ConnectionState.Connected }
        recoversWithAFullAttach()
        assertTrue(h.mirror.dead)
    }

    /** The same trap with a writer that is stuck rather than dead: the read's bound recovers it. */
    @Test
    fun aRestoredSessionWhoseReadNeverLandsIsFullyReattachedNotLeftBlank() {
        h.close()
        h = MirrorHarness(hydrateTimeoutMs = 1_000)
        h.startServer()
        mirroredThenKilled()
        val stuck = CountDownLatch(1)
        try {
            h.boot(ready = ready("s1"), handshake = false) { p -> p.mirror.beforeHydrateRead = { stuck.await(30, TimeUnit.SECONDS) } }
            h.ws.send(ready("s1"))
            h.expectFrame("hello")
            h.await(h.client.connection) { it == ConnectionState.Connected }
            recoversWithAFullAttach()
        } finally {
            stuck.countDown()
        }
    }

    private fun recoversWithAFullAttach() {
        // The capped ready re-attach asks for a delta from the restored cursor; the server is at head.
        val delta = h.expectFrame("attach")
        assertEquals(5L, delta["afterSeq"]!!.jsonPrimitive.longOrNull)
        h.ws.send(snapshotFrame("s1", 5, state = null))
        // The read comes back empty: a FULL attach, not a blank session.
        val full = h.expectFrame("attach")
        assertEquals("s1", full["sessionId"]!!.jsonPrimitive.content)
        assertNull("recovery is a full attach", full["afterSeq"])
        h.ws.send(snapshotFrame("s1", 5, state))
        h.await(h.client.projectionTrees) { it.containsKey("s1") }
        h.ws.send("""{"type":"event","sessionId":"s1","event":{"type":"turn_started","turnId":"t6","seq":6,"ts":6}}""")
        h.await(h.client.projectionTrees) { it["s1"]?.get("activeTurnId") == com.tether.app.protocol.tree.JsStr("t6") }
    }

    @Test
    fun onlyListedOrAttachedSessionsAreMirrored() {
        h.boot(ready = ready("listed"))
        // Unsolicited states (the server broadcasts to every socket): one for a listed session,
        // one for a session this client has never seen listed or opened.
        h.ws.send(snapshotFrame("listed", 3, state.replace("s1", "listed")))
        h.ws.send(snapshotFrame("stranger", 3, state.replace("s1", "stranger")))
        h.serverBarrier()
        assertTrue(h.dbSession("listed") != null)
        assertNull(h.dbSession("stranger"))
        // Opening it makes it known.
        h.client.attach("stranger")
        h.expectFrame("attach")
        h.ws.send(snapshotFrame("stranger", 4, state.replace("s1", "stranger")))
        h.serverBarrier()
        assertTrue(h.dbSession("stranger") != null)
    }

    /**
     * ta-hra R3: the UI calls logout on main. A writer stuck inside a Keystore call (here the
     * unwrap of the next process's bind) must not hold the logout, and the shred must not run
     * on the caller's thread; the result is still wiped, then and after the writer comes back.
     */
    @Test
    fun aLogoutWithTheWriterStuckInsideTheKeystoreReturnsPromptlyOffTheCallersThreadAndStillWipes() {
        mirroredThenKilled()
        h.bindTimeoutMs = 1_000 // the stuck bind gives up quickly; the writer stays stuck
        val entered = CountDownLatch(1)
        val stuck = CountDownLatch(1)
        h.kek.beforeExistingKey = {
            h.kek.beforeExistingKey = null
            entered.countDown()
            stuck.await(20, TimeUnit.SECONDS)
        }
        try {
            h.boot(ready = ready("s1"))
            assertTrue("the writer is inside the Keystore", entered.await(20, TimeUnit.SECONDS))
            assertTrue(h.keyFile.exists())
            h.server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) // POST /api/auth/logout
            h.kek.destroyThreads.clear()
            val returned = CountDownLatch(1)
            val caller = Thread {
                runBlocking { h.client.logout() }
                returned.countDown()
            }
            caller.start()
            assertTrue("logout waited for the stuck writer", returned.await(10, TimeUnit.SECONDS))
            // Unreadable already, the writer still stuck.
            assertFalse(h.keyFile.exists())
            assertTrue(h.kek.destroyed >= 1)
            assertTrue(stuck.count == 1L)
            assertTrue("the shred ran on the caller's thread", h.kek.destroyThreads.first() !== caller)
        } finally {
            stuck.countDown()
            h.kek.beforeExistingKey = null
        }
        awaitTrue("mirror files deleted once the writer is back") { h.dbFactory.existing().isEmpty() }
        assertFalse("the writer minted no key after the wipe", h.keyFile.exists())
    }

    /**
     * ta-hra M-1: the UI's scope is cancelled while logout waits for the mirror's Keystore
     * delete. The logout still runs to the end: the credential was forgotten BEFORE the shred
     * (I-9), the push hook runs, the cookie is revoked, and the next start does not sign in.
     */
    @Test
    fun aLogoutWhoseCallerIsCancelledMidWipeStillForgetsTheCredentialAndFinishes() {
        h.boot(ready = ready("s1"))
        h.client.attach("s1")
        h.expectFrame("attach")
        h.ws.send(snapshotFrame("s1", 5, state))
        h.serverBarrier()
        h.dbSession("s1")
        val inKeystore = CountDownLatch(1)
        val release = CountDownLatch(1)
        val credentialAtShred = java.util.concurrent.atomic.AtomicReference<Any?>("unset")
        // Every Keystore delete (the logout's shred, and the writer's own) is held until release.
        h.kek.beforeDestroyKey = {
            credentialAtShred.compareAndSet("unset", runBlocking { h.settings.session().credential })
            inKeystore.countDown()
            release.await(20, TimeUnit.SECONDS)
        }
        val hookRan = CountDownLatch(1)
        h.onLogout = { _, _ -> hookRan.countDown() }
        h.server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) // POST /api/auth/logout
        val uiScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        try {
            val job = uiScope.launch { h.client.logout() }
            assertTrue("the wipe is inside the Keystore", inKeystore.await(20, TimeUnit.SECONDS))
            job.cancel() // viewModelScope cleared mid-wipe
        } finally {
            release.countDown()
            h.kek.beforeDestroyKey = null
        }
        assertEquals("the credential was forgotten before the shred", null, credentialAtShred.get())
        assertTrue("the push hook still ran", hookRan.await(20, TimeUnit.SECONDS))
        var revoke: okhttp3.mockwebserver.RecordedRequest? = null
        val deadline = System.currentTimeMillis() + 20_000
        while (revoke == null && System.currentTimeMillis() < deadline) {
            val r = h.server.takeRequest(1, TimeUnit.SECONDS) ?: continue
            if (r.path == "/api/auth/logout") revoke = r
        }
        assertNotNull("the cookie was still revoked server-side", revoke)
        assertFalse(h.keyFile.exists())
        assertNull(runBlocking { h.settings.session().credential })
        uiScope.cancel()

        // The next process does not sign back in (configured is false), even if started.
        h.kill(flushFirst = false)
        h.bootSignedOut()
        h.client.start()
        h.await(h.client.connection) { it == ConnectionState.AuthRequired }
        awaitTrue("mirror files deleted") { h.dbFactory.existing().isEmpty() }
        assertNull("no socket was opened", h.sockets.poll(500, TimeUnit.MILLISECONDS))
    }
}
