package com.tether.app.ui.fake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T13.2 r3: the demo client declares that it reports freshness (not inferred from its [syncStates]
 * identity), and its End session is bound to the demo origin like the real client's.
 */
class FakeTetherClientEndTest {

    @Test
    fun theDemoClientReportsFreshness() {
        assertTrue(FakeTetherClient().reportsFreshness)
    }

    @Test
    fun anEndDrawnForAnotherServerLeavesTheDemoSessionRunning() {
        val fake = FakeTetherClient()
        val origin = fake.consentOrigin.value
        val target = fake.sessions.value.first { it.status != "exited" }
        fake.kill(target.id, "https://other.example", requireLive = false)
        fake.kill(target.id, null)
        assertNotEquals("exited", fake.sessions.value.first { it.id == target.id }.status)

        fake.kill(target.id, origin)
        assertEquals("exited", fake.sessions.value.first { it.id == target.id }.status)
    }
}
