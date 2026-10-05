package com.tether.app.client

import com.tether.app.protocol.model.SubagentDefault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-coik.10 on the real client: v138 `SessionMetrics.subagentDefaults` rides the session row
 * (ready and a later `session` frame) to [TetherClient.sessions]; a session update without it
 * (an older server, or no type declaring anything) clears it, as the web replaces the row.
 */
class SubagentDefaultsClientTest {
    private val h = ConnectionHarness()

    @After fun tearDown() = h.close()

    private fun row(metrics: String) =
        """{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"idle","startedAt":1,"updatedAt":2,"metrics":$metrics}"""

    @Test fun theDefaultsArriveOnReadyAndFollowSessionUpdates() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(
            ws,
            """{"type":"ready","protocolVersion":143,"nativeProtocolFloor":129,"providers":[],"workspaceRoot":null,
               "sessions":[${row("""{"totalTokens":1,"subagentDefaults":{"maker":{"model":"claude-opus-5-5","effort":"high"}}}""")}]}""",
        )
        val first = h.await(h.client.sessions) { it.isNotEmpty() }.single()
        assertEquals(mapOf("maker" to SubagentDefault("claude-opus-5-5", "high")), first.metrics!!.subagentDefaultMap)

        ws.send("""{"type":"session","session":${row("""{"totalTokens":2,"subagentDefaults":{"maker":{"effort":"low"}}}""")}}""")
        val updated = h.await(h.client.sessions) { it.singleOrNull()?.metrics?.totalTokens == 2L }.single()
        assertEquals(mapOf("maker" to SubagentDefault(null, "low")), updated.metrics!!.subagentDefaultMap)

        ws.send("""{"type":"session","session":${row("""{"totalTokens":3}""")}}""")
        val cleared = h.await(h.client.sessions) { it.singleOrNull()?.metrics?.totalTokens == 3L }.single()
        assertTrue(cleared.metrics!!.subagentDefaultMap.isEmpty())
    }
}
