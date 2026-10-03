package com.tether.app.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ta-coik.14: the frames behind the inspector's Repository and Services actions, against the web's
 * own (tether 90fbb9f): what hooks/use-tether.ts:1497-1508 sends and what server.mjs:9695-9724 and
 * 6092 answer.
 */
class InspectorActionsWireTest {

    private fun web(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun theClientFramesAreTheWebs() {
        // controlWorktreeScript: send({ type: "worktree-script", sessionId, name, action })
        for (action in listOf("start", "stop", "restart")) {
            assertEquals(
                web("""{"type":"worktree-script","sessionId":"s1","name":"dev","action":"$action"}"""),
                ClientMessage.WorktreeScript("s1", "dev", action).toJsonObject(),
            )
        }
        // requestWorktreeLogs: send({ type: "worktree-logs", sessionId, name })
        assertEquals(web("""{"type":"worktree-logs","sessionId":"s1","name":"dev"}"""), ClientMessage.WorktreeLogsRequest("s1", "dev").toJsonObject())
        // requestChangeRequest(id, true): send({ type: "change-request", sessionId, refresh: true })
        assertEquals(web("""{"type":"change-request","sessionId":"s1","refresh":true}"""), ClientMessage.ChangeRequestFetch("s1", true).toJsonObject())
        // ...and the plain read carries no refresh key at all.
        assertEquals(web("""{"type":"change-request","sessionId":"s1"}"""), ClientMessage.ChangeRequestFetch("s1").toJsonObject())
    }

    @Test
    fun theServerRepliesDecode() {
        // server.mjs:9717-9723: lines and dropped default to [] / 0 server-side, always present.
        assertEquals(
            ServerMessage.WorktreeLogs("s1", "dev", listOf("ready on :5173", ""), 4),
            ServerMessage.parse("""{"type":"worktree-logs","sessionId":"s1","name":"dev","lines":["ready on :5173",""],"dropped":4}"""),
        )
        assertEquals(
            ServerMessage.WorktreeLogs("s1", "never-ran", emptyList(), 0),
            ServerMessage.parse("""{"type":"worktree-logs","sessionId":"s1","name":"never-ran","lines":[],"dropped":0}"""),
        )
        // server.mjs:6092: a failed lookup (unknown) is distinct from no pull request (null).
        val unknown = ServerMessage.parse("""{"type":"change-request","sessionId":"s1","changeRequest":null,"unknown":true}""") as ServerMessage.ChangeRequest
        assertEquals(true, unknown.unknown)
        assertEquals(null, unknown.changeRequest)
        val pr = ServerMessage.parse(
            """{"type":"change-request","sessionId":"s1","changeRequest":{"number":12,"url":"https://example.test/pr/12","state":"OPEN",
               "headRefOid":null,"mergeable":"MERGEABLE","reviewDecision":null,"mergedAt":null,"headRefName":null,"baseRefName":"main","isDraft":false},
               "unknown":false}""",
        ) as ServerMessage.ChangeRequest
        assertEquals(false, pr.unknown)
        assertEquals("https://example.test/pr/12", (pr.changeRequest!!["url"] as kotlinx.serialization.json.JsonPrimitive).content)
    }
}
