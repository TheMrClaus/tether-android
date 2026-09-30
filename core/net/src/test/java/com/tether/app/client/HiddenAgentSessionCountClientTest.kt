package com.tether.app.client

import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ta-ylh on the real client: a v135 server's `ready.hiddenAgentSessionCount` is published on
 * [TetherClient.hiddenAgentSessionCount] for the sidebar (T15.6), while the app's hello still
 * advertises 132 (owner gate) and is served by the 135 server.
 */
class HiddenAgentSessionCountClientTest {
    private val h = ConnectionHarness()

    @After fun tearDown() = h.close()

    private fun ready135(extra: String) =
        """{"type":"ready","protocolVersion":135,"nativeProtocolFloor":129,$extra
           "sessions":[{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"idle","startedAt":1,
             "updatedAt":2,"createdVia":"console"}],"providers":[],"workspaceRoot":null}"""

    @Test fun aV135ReadyPublishesTheCountAndTheHelloStillSays132() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val hello = h.handshake(h.nextSocket(), ready135(""""hiddenAgentSessionCount":4,"""))
        assertEquals("132", hello["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("android", hello["client"]!!.jsonPrimitive.content)
        assertEquals(4, h.await(h.client.hiddenAgentSessionCount) { it != null })
        assertEquals("console", h.client.sessions.value.single().createdViaStamp)
    }

    @Test fun aReadyWithoutTheCountLeavesItNull() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        h.handshake(h.nextSocket(), ready135(""))
        assertEquals(1, h.await(h.client.sessions) { it.isNotEmpty() }.size)
        assertNull(h.client.hiddenAgentSessionCount.value)
    }
}
