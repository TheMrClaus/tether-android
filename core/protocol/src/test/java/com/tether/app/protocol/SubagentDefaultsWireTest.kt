package com.tether.app.protocol

import com.tether.app.protocol.model.SessionMetrics
import com.tether.app.protocol.model.SubagentDefault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-coik.10: v138 `SessionMetrics.subagentDefaults` (lib/protocol.ts SessionMetrics /
 * SubagentDefault; tether f4c4133). Server->client only, additive: present, absent (an older
 * server) and malformed all decode, and a malformed map never drops the session row.
 */
class SubagentDefaultsWireTest {

    private fun metrics(json: String): SessionMetrics = (
        ServerMessage.parse(
            """{"type":"ready","protocolVersion":138,"nativeProtocolFloor":129,"providers":[],"workspaceRoot":null,
               "sessions":[{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"idle","startedAt":1,"updatedAt":2,
               "metrics":$json}]}""",
        ) as ServerMessage.Ready
        ).sessions.single().metrics!!

    @Test
    fun presentDefaultsDecodeInTheServersOrder() {
        val m = metrics(
            """{"totalTokens":1,"subagentDefaults":{"code-reviewer":{"model":"claude-opus-5-5","effort":"high"},
               "maker":{"effort":"medium"},"scout":{"model":"claude-haiku-4-5"}}}""",
        )
        assertEquals(
            linkedMapOf(
                "code-reviewer" to SubagentDefault("claude-opus-5-5", "high"),
                "maker" to SubagentDefault(null, "medium"),
                "scout" to SubagentDefault("claude-haiku-4-5", null),
            ),
            m.subagentDefaultMap,
        )
        assertEquals(listOf("code-reviewer", "maker", "scout"), m.subagentDefaultMap.keys.toList())
    }

    @Test
    fun anOlderServerSendsNoneAndTheMapIsEmpty() {
        val m = metrics("""{"totalTokens":1}""")
        assertNull(m.subagentDefaults)
        assertTrue(m.subagentDefaultMap.isEmpty())
    }

    @Test
    fun malformedEntriesAndFieldsAreSkippedNeverFatal() {
        val m = metrics(
            """{"totalTokens":7,"subagentDefaults":{"a":"high","b":null,"c":{"model":5,"effort":true},
               "d":{"model":"  ","effort":"low"},"e":[1],"f":{"model":"m","extra":1}}}""",
        )
        assertEquals(7L, m.totalTokens)
        assertEquals(
            mapOf("d" to SubagentDefault(null, "low"), "f" to SubagentDefault("m", null)),
            m.subagentDefaultMap,
        )
        // A non-object map is ignored as a whole.
        assertTrue(metrics("""{"subagentDefaults":["x"]}""").subagentDefaultMap.isEmpty())
        assertTrue(metrics("""{"subagentDefaults":"x"}""").subagentDefaultMap.isEmpty())
    }

    @Test
    fun theMapIsCappedAtTheServersBound() {
        val entries = (0 until 70).joinToString(",") { """"t$it":{"effort":"low"}""" }
        val m = metrics("""{"subagentDefaults":{$entries}}""")
        assertEquals(SessionMetrics.MAX_SUBAGENT_DEFAULTS, m.subagentDefaultMap.size)
        assertEquals("t0", m.subagentDefaultMap.keys.first())
    }
}
