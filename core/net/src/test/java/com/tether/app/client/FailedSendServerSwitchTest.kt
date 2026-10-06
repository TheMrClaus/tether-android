package com.tether.app.client

import com.tether.app.protocol.helpers.PendingInput as Web
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-coik.19 r2: a failed bubble belongs to the server its send was given up on. A sign-in to another
 * server drops them, and one computed for the old server but not yet published when the switch lands
 * is never shown on the new server's chat. The web lives on one server per page, so it has neither.
 * A send is given up on here by eviction (MAX_RECORDS + 1 sends), which needs no sweep or clock.
 */
class FailedSendServerSwitchTest {

    private val f = TwoOriginFixture()

    @After
    fun tearDown() = f.close()

    /** Fill the outbox to MAX_RECORDS on A (nothing acks them, so nothing is given up yet). */
    private fun fillOnA() {
        f.connectedToA()
        repeat(Web.MAX_RECORDS) { f.client.send("s1", "m$it") }
        f.awaitCondition("the outbox is full") { f.client.pendingSends.value.size == Web.MAX_RECORDS }
        assertTrue(f.client.failedSends.value.isEmpty())
    }

    @Test
    fun aSignInToAnotherServerDropsTheFailedBubbles() {
        fillOnA()
        f.client.send("s1", "one too many")
        val failed = f.await(f.client.failedSends) { it.isNotEmpty() }
        assertEquals(listOf("m0"), failed.map { it.text })
        f.loginTo(f.b)
        assertTrue("A's failed bubble shows on B", f.client.failedSends.value.isEmpty())
    }

    @Test
    fun aBubbleComputedForTheOldServerIsNeverPublishedAfterTheSwitch() {
        fillOnA()
        val hold = f.Hold(RacePoint.FailedComputed)
        // The eviction is computed under the lock on A, then held before it is published.
        val sender = Thread { f.client.send("s1", "one too many") }.apply { start() }
        hold.awaitReached()
        f.loginTo(f.b)
        hold.release()
        sender.join(20_000)
        assertTrue("the sender finished", !sender.isAlive)
        assertTrue("A's failed bubble was published after the switch to B", f.client.failedSends.value.isEmpty())
    }

    /** ta-coik.34: the same race with a sign-out (stop()) landing in the window instead of a switch. */
    @Test
    fun aBubbleComputedBeforeASignOutIsNeverPublishedAfterIt() {
        fillOnA()
        val hold = f.Hold(RacePoint.FailedComputed)
        val sender = Thread { f.client.send("s1", "one too many") }.apply { start() }
        hold.awaitReached()
        f.client.stop()
        assertTrue("the sign-out cleared what was there", f.client.failedSends.value.isEmpty())
        hold.release()
        sender.join(20_000)
        assertTrue("the sender finished", !sender.isAlive)
        assertTrue("A's failed bubble was published after the sign-out", f.client.failedSends.value.isEmpty())
    }
}
