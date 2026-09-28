package com.tether.app.client.sync

import com.tether.app.client.ConnectionState
import com.tether.app.client.HEALTH_129
import com.tether.app.client.LoginResult
import com.tether.app.client.snapshotFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        """{"type":"ready","protocolVersion":129,"nativeProtocolFloor":129,"providers":[],"workspaceRoot":null,
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
        val deadline = System.currentTimeMillis() + 10_000
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
        // Hold the writer so the Wipe op cannot run before the "death".
        val gate = CountDownLatch(1)
        h.mirror.beforeHydrateRead = { gate.await(10, TimeUnit.SECONDS) }
        h.client.attach("held")
        h.server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) // POST /api/auth/logout
        runBlocking { h.client.logout() }
        // The keys went on logout's own thread, the writer still held.
        assertFalse(h.keyFile.exists())
        assertTrue(h.kek.destroyed >= 1)
        assertTrue("the writer never deleted the DB", h.dbFactory.existing().isNotEmpty())
        Thread { Thread.sleep(300); gate.countDown() }.start()
        h.kill(flushFirst = false)

        // Next cold start: the URL is kept, no credential. The DB goes; nothing is shown.
        h.bootSignedOut()
        h.await(h.client.connection) { it == ConnectionState.AuthRequired }
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
        h.bootSignedOut()
        h.await(h.client.connection) { it == ConnectionState.AuthRequired }
        awaitTrue("mirror purged") { h.dbFactory.existing().isEmpty() && !h.keyFile.exists() }
        assertTrue("the old list is never published under AuthRequired", h.client.sessions.value.isEmpty())
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
        h.server.enqueue(MockResponse().setResponseCode(200).setBody(HEALTH_129))
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
            b.enqueue(MockResponse().setResponseCode(200).setBody(HEALTH_129))
            b.enqueue(MockResponse().setResponseCode(200).addHeader("Set-Cookie", "tether_session=b; Path=/").setBody("{}"))
            b.enqueue(MockResponse().setResponseCode(200).setBody("""{"authenticated":true}"""))
            // In the window after the switch to B and before the mirror re-binds, the UI opens s1.
            h.client.raceHook = { point, _ -> if (point == com.tether.app.client.RacePoint.OriginSwitched) h.client.attach("s1") }
            assertEquals(LoginResult.Success, runBlocking { h.client.login(b.url("/").toString(), "pw") })
            h.client.raceHook = null
            runBlocking { h.mirror.flush() }
            Thread.sleep(200)
            assertNull("server A's saved transcript shown under server B", h.client.projectionTrees.value["s1"])
        } finally {
            b.shutdown()
        }
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
}
