package com.tether.app.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T9.1: the AgentSession fields the inspector reads that the app did not decode before (all at or
 * below v132, lib/protocol.ts): SessionMetrics cache split / contextSnapshotAt (#164) / account
 * identity (v92) / reset summaries (v90, v126), SessionWorktree's v98 fields, and `acpAgentId`
 * (v74). Every one is optional; the deep ones stay raw so a malformed value never drops the row.
 */
class InspectorFieldsWireTest {

    private fun session(extra: String) =
        """{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"idle","startedAt":1,"updatedAt":2$extra}"""

    private fun ready(vararg sessions: String) = ServerMessage.parse(
        """{"type":"ready","protocolVersion":132,"nativeProtocolFloor":129,"sessions":[${sessions.joinToString(",")}],
           "providers":[],"workspaceRoot":null}""",
    ) as ServerMessage.Ready

    @Test
    fun theInspectorMetricsDecode() {
        val s = ready(
            session(
                ""","metrics":{"totalTokens":1200,"cacheReadInputTokens":900,"cacheMissInputTokens":300,
                   "contextTokens":5000,"contextSnapshotAt":1790000000000,
                   "accountEmail":"a@example.test","accountOrganization":"Org",
                   "codexResetCredits":{"availableCount":2,"credits":[]},
                   "claudeResetGrants":{"eligible":true,"atLimit":false,"exhausted":[],"grants":[],"availableCount":0},
                   "claudeResetGrantsAt":1790000000001,"futureField":1}""",
            ),
        ).sessions.single()
        val m = s.metrics!!
        assertEquals(900L, m.cacheReadInputTokens)
        assertEquals(300L, m.cacheMissInputTokens)
        assertEquals(1_790_000_000_000L, m.contextSnapshotAt)
        assertEquals("a@example.test", m.accountEmail)
        assertEquals("Org", m.accountOrganization)
        assertEquals("2", (m.codexResetCredits as JsonObject)["availableCount"]!!.jsonPrimitive.content)
        assertTrue(m.claudeResetGrants is JsonObject)
    }

    @Test
    fun absentFieldsReadAsAbsent() {
        val s = ready(session(""","metrics":{"totalTokens":1},"worktree":{"path":"/w","branch":"b","status":"active"}""")).sessions.single()
        val m = s.metrics!!
        assertNull(m.cacheReadInputTokens)
        assertNull(m.contextSnapshotAt)
        assertNull(m.accountEmail)
        assertNull(m.codexResetCredits)
        assertNull(m.claudeResetGrants)
        assertNull(s.acpAgentId)
        val w = s.worktree!!
        assertNull(w.mode)
        assertNull(w.baseRef)
        assertNull(w.setupStatus)
        assertNull(w.prNumber)
        assertEquals(emptyList<String>(), w.configWarningList)
    }

    @Test
    fun theV98WorktreeFieldsAndAcpAgentDecode() {
        val s = ready(
            session(
                ""","provider":"acp","acpAgentId":"agent-7","worktree":{"path":"/w/.t/x","branch":"tether/x","status":"active",
                   "slug":"x","mode":"checkout-pr","baseRef":"main","baseCommit":"abc","prNumber":42,"setupStatus":"failed",
                   "configWarnings":["scripts.dev: not a string",7,null,"setup[0]: empty"]}""",
            ),
        ).sessions.single()
        assertEquals("agent-7", s.acpAgentId)
        val w = s.worktree!!
        assertEquals("checkout-pr", w.mode)
        assertEquals("main", w.baseRef)
        assertEquals(42L, w.prNumber)
        assertEquals("failed", w.setupStatus)
        // A non-string element is skipped; the row survives.
        assertEquals(listOf("scripts.dev: not a string", "setup[0]: empty"), w.configWarningList)
    }

    @Test
    fun malformedDeepValuesNeverDropTheRow() {
        val s = ready(
            session(""","metrics":{"codexResetCredits":"nope","claudeResetGrants":[1]},"worktree":{"path":"/w","branch":"b","status":"active","configWarnings":"x","prNumber":null}"""),
        ).sessions.single()
        assertEquals("s1", s.id)
        assertTrue(s.metrics!!.codexResetCredits !is JsonObject)
        assertEquals(emptyList<String>(), s.worktree!!.configWarningList)
        assertNull(s.worktree!!.prNumber)
    }
}
