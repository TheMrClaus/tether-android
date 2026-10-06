package com.tether.app.client.sync

import com.tether.app.client.ConnectionState
import com.tether.app.client.snapshotFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ta-coik.36: a cold start sends the auth probe alongside the saved-copy bind instead of after it,
 * and still opens the socket only once the copy is bound (so the ready's attach asks for the
 * delta). The bind is held at the boot purge's store read, the step every signed-in bind waits for.
 */
@RunWith(RobolectricTestRunner::class)
class MirrorColdStartOrderTest {
    private val h = MirrorHarness()

    @Before
    fun setUp() = h.startServer()

    @After
    fun tearDown() = h.close()

    private val state = """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","turnOrder":[],"turnsById":{},"queuedMessages":[],"marker":"x"}"""

    private fun sessionJson(id: String) =
        """{"id":"$id","provider":"claude","name":"n-$id","cwd":"/w","status":"ready","startedAt":1,"updatedAt":1,
            "pinned":false,"runtimeArchived":false,"mode":"headless"}"""

    private fun ready(vararg ids: String) =
        """{"type":"ready","protocolVersion":143,"nativeProtocolFloor":129,"providers":[],"workspaceRoot":null,
            "sessions":[${ids.joinToString(",") { sessionJson(it) }}]}"""

    /** A process that leaves s1 mirrored with a cursor at 5, then dies cleanly. */
    private fun mirroredThenKilled() {
        h.boot(ready = ready("s1"))
        h.client.attach("s1")
        h.expectFrame("attach")
        h.ws.send(snapshotFrame("s1", 5, state))
        h.serverBarrier()
        h.dbSession("s1")
        h.kill(flushFirst = true)
        // The first process's requests are not the next one's.
        while (h.server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
    }

    /** The boot purge's store read is held until [release]; returns once it is held. */
    private fun holdBind(): CompletableDeferred<Unit> {
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        h.beforeCredentialState = {
            h.beforeCredentialState = null
            entered.countDown()
            withTimeout(20_000) { release.await() }
        }
        holdEntered = entered
        return release
    }

    private var holdEntered: CountDownLatch? = null

    private fun requestPath(): String {
        val request = h.server.takeRequest(20, TimeUnit.SECONDS) ?: throw AssertionError("no request reached the server")
        return request.path!!
    }

    @Test
    fun theProbeGoesOutWhileTheBindIsHeldAndTheSocketWaitsForIt() {
        mirroredThenKilled()
        val release = holdBind()
        h.enqueueConnect()
        val before = h.server.requestCount
        h.bootStartOnly()
        try {
            assertEquals("the probe is the first thing on the wire, before the bind is done", "/api/auth/session", requestPath())
            assertTrue("the bind is still held", holdEntered!!.await(20, TimeUnit.SECONDS))
            // Long past the probe's answer: no upgrade while the saved copy is not bound.
            Thread.sleep(400)
            assertEquals("no socket request while the bind is held", before + 1, h.server.requestCount)
            assertNull("no socket yet", h.sockets.poll(0, TimeUnit.MILLISECONDS))
            assertTrue("the saved copy is not published yet", h.client.sessions.value.isEmpty())
        } finally {
            release.complete(Unit)
        }
        val ws = h.sockets.poll(20, TimeUnit.SECONDS)
        assertTrue("the socket opens once the bind is done", ws != null)
        ws!!.send(ready("s1"))
        h.expectFrame("hello")
        h.await(h.client.connection) { it == ConnectionState.Connected }
        h.client.attach("s1")
        assertEquals("the cursor restored by the bind is asked from: a delta attach", 5L, h.expectFrame("attach")["afterSeq"]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun aProbeThatFindsTheCredentialDeadWipesTheSavedCopyEvenWhileTheBindIsHeld() {
        mirroredThenKilled()
        val release = holdBind()
        h.server.enqueue(MockResponse().setResponseCode(200).setBody("""{"authenticated":false}"""))
        h.bootStartOnly()
        try {
            assertEquals("/api/auth/session", requestPath())
            assertTrue("the bind is held", holdEntered!!.await(20, TimeUnit.SECONDS))
            h.await(h.client.connection) { it is ConnectionState.AuthRequired }
        } finally {
            release.complete(Unit)
        }
        val deadline = System.currentTimeMillis() + 20_000
        while (h.dbFactory.existing().isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("the saved copy is gone", h.dbFactory.existing().isEmpty())
        assertTrue("the keys are gone", h.kek.destroyed >= 1)
        assertTrue("nothing of the dead sign-in is shown", h.client.sessions.value.isEmpty())
        assertNull("no socket was ever opened", h.sockets.poll(300, TimeUnit.MILLISECONDS))
    }
}
