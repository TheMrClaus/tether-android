package com.tether.app.client.sync

import com.tether.app.client.ConnectionState
import com.tether.app.client.ConnectionTimings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ta-rv0o 1k: start()'s early connect (ta-coik.36) counts only when it began. One that returned because
 * the client is halted (here: background-suspended) did nothing, so start() tries again after its bind,
 * and a halt that lifts during the bind connects on its own signal (the foreground signal). The bind is
 * held at the boot purge's store read, as in [MirrorColdStartOrderTest].
 */
@RunWith(RobolectricTestRunner::class)
class ColdStartHaltedTest {
    private val h = MirrorHarness()

    @Before
    fun setUp() = h.startServer()

    @After
    fun tearDown() = h.close()

    private var holdEntered: CountDownLatch? = null

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

    /** The process comes up already suspended in the background (the grace period fired before start()). */
    private fun bootSuspended() = h.bootStartOnly { p ->
        p.client.setAppForeground(false)
        p.scheduler.await { it == ConnectionTimings.BACKGROUND_GRACE_MS }.fire()
        h.await(p.client.connection) { it == ConnectionState.Disconnected }
    }

    private fun requestPath(): String {
        val request = h.server.takeRequest(20, TimeUnit.SECONDS) ?: throw AssertionError("no request reached the server")
        return request.path!!
    }

    @Test
    fun aHaltThatLiftsDuringTheBindConnectsAndTheSocketStillWaitsForTheBind() {
        val release = holdBind()
        h.enqueueConnect()
        bootSuspended()
        try {
            assertTrue("the bind is held", holdEntered!!.await(20, TimeUnit.SECONDS))
            Thread.sleep(400)
            assertEquals("suspended: the early connect sent nothing", 0, h.server.requestCount)
            h.client.setAppForeground(true)
            assertEquals("the foreground signal connects while the bind runs", "/api/auth/session", requestPath())
            Thread.sleep(400)
            assertNull("but no socket before the bind is done", h.sockets.poll(0, TimeUnit.MILLISECONDS))
        } finally {
            release.complete(Unit)
        }
        val ws = h.sockets.poll(20, TimeUnit.SECONDS)
        assertTrue("the socket opens once the bind is done", ws != null)
        ws!!.send(com.tether.app.client.readyFrame())
        h.expectFrame("hello")
        h.await(h.client.connection) { it == ConnectionState.Connected }
        assertEquals("one probe, one upgrade: nothing connected twice", 2, h.server.requestCount)
    }

    @Test
    fun aHaltThatLastsPastTheBindConnectsNothingAndTheLaterSignalDoes() {
        val release = holdBind()
        h.enqueueConnect()
        bootSuspended()
        assertTrue("the bind is held", holdEntered!!.await(20, TimeUnit.SECONDS))
        release.complete(Unit)
        // The bind is done; still suspended: start()'s own retry after the bind does not connect either.
        Thread.sleep(600)
        assertEquals("still halted: nothing on the wire", 0, h.server.requestCount)
        assertTrue("and not signed out", h.client.connection.value !is ConnectionState.AuthRequired)
        h.client.setAppForeground(true)
        assertEquals("/api/auth/session", requestPath())
        val ws = h.sockets.poll(20, TimeUnit.SECONDS)
        assertTrue("the resume signal reconnects", ws != null)
        ws!!.send(com.tether.app.client.readyFrame())
        h.expectFrame("hello")
        h.await(h.client.connection) { it == ConnectionState.Connected }
    }
}
