package com.tether.app.ui

import com.tether.app.client.TwoOriginFixture
import com.tether.app.client.TwoOriginFixture.Companion.createdFrame
import com.tether.app.client.type
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ta-2ew (R1), the view model over the real client and two servers: a resume's `created` (no
 * requestId: the dashboard opens it as it lands) that server A sent before a sign-in to server B,
 * but that the view model's collector handles only after it (Main held), opens nothing: no
 * selection, and A's session id is never subscribed, so B's ready never attaches it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TetherViewModelCreatedOriginTest {
    private val main = StandardTestDispatcher()
    private val fx = TwoOriginFixture()
    private val vms = TestViewModels()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        vms.clear()
        main.scheduler.advanceUntilIdle()
        fx.close()
        Dispatchers.resetMain()
    }

    @Test
    fun aResumeReplyFromTheOldServerHandledAfterTheSwitchOpensAndAttachesNothing() {
        val aws = fx.connectedToA()
        val vm = vms.track(TetherViewModel(fx.client, InMemoryDraftStore(), monotonicClock = { 0 }))
        main.scheduler.advanceUntilIdle() // the collectors are subscribed
        aws.send(createdFrame("s-on-a", null))
        fx.await(fx.client.createdSessions) { it?.session?.id == "s-on-a" }
        // Main is held: the reply waits in the stream while the sign-in to B runs.
        fx.loginTo(fx.b)
        main.scheduler.advanceUntilIdle()
        assertNull("A's session is selected on B", vm.selectedSessionId.value)

        val bws = fx.b.nextSocket()
        fx.handshake(fx.b, bws)
        main.scheduler.advanceUntilIdle()
        val sent = fx.framesUntilBarrier(fx.b)
        assertTrue("B received $sent", sent.isEmpty())
        assertFalse(fx.b.allFrames.any { it.contains("s-on-a") })

        // Positive control: B's own resume reply opens and attaches on B.
        bws.send(createdFrame("s-on-b", null))
        fx.await(fx.client.createdSessions) { it?.session?.id == "s-on-b" }
        main.scheduler.advanceUntilIdle()
        assertEquals("s-on-b", vm.selectedSessionId.value)
        val attach = fx.framesUntilBarrier(fx.b).single { it.type() == "attach" }
        assertEquals("s-on-b", (attach["sessionId"] as JsonPrimitive).content)
    }

    /**
     * r2 (security P4-1): the same, after a sign-out instead of a switch. The URL stays (the login
     * screen's prefill), so only the missing sign-in refuses it: a resume reply A sent before the
     * logout, handled after it, selects nothing and subscribes nothing, so the next sign-in to A does
     * not attach it either.
     */
    @Test
    fun aResumeReplyHandledAfterASignOutOpensAndAttachesNothingEvenAfterSigningBackIn() {
        val aws = fx.connectedToA()
        val vm = vms.track(TetherViewModel(fx.client, InMemoryDraftStore(), monotonicClock = { 0 }))
        main.scheduler.advanceUntilIdle()
        aws.send(createdFrame("s-resumed", null))
        fx.await(fx.client.createdSessions) { it?.session?.id == "s-resumed" }
        // Main is held: the reply waits in the stream while the sign-out runs.
        kotlinx.coroutines.runBlocking { fx.client.logout() }
        main.scheduler.advanceUntilIdle()
        assertNull("a session was selected while signed out", vm.selectedSessionId.value)

        fx.loginTo(fx.a)
        val ws = fx.a.nextSocket()
        fx.handshake(fx.a, ws)
        main.scheduler.advanceUntilIdle()
        val sent = fx.framesUntilBarrier(fx.a)
        assertTrue("re-attached after the sign-in: $sent", sent.none { it.type() == "attach" })
        assertFalse(fx.a.allFrames.any { it.contains("\"attach\"") && it.contains("s-resumed") })

        // Positive control: signed in again, A's own resume reply opens and attaches.
        ws.send(createdFrame("s-after", null))
        fx.await(fx.client.createdSessions) { it?.session?.id == "s-after" }
        main.scheduler.advanceUntilIdle()
        assertEquals("s-after", vm.selectedSessionId.value)
        val attach = fx.framesUntilBarrier(fx.a).single { it.type() == "attach" }
        assertEquals("s-after", (attach["sessionId"] as JsonPrimitive).content)
    }
}
