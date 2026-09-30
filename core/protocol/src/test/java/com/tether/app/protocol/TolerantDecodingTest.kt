package com.tether.app.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN D4 tolerance rules for [ServerMessage.parse], plus the v129 fields. */
class TolerantDecodingTest {

    private inline fun <reified T : ServerMessage> parseAs(text: String): T {
        val m = ServerMessage.parse(text)
        assertTrue("expected ${T::class.simpleName}, got $m", m is T)
        return m as T
    }

    private fun unknown(text: String): ServerMessage.Unknown = parseAs(text)

    @Test
    fun unknownTypeIsUnknownWithRawAndNeverThrows() {
        val u = unknown("""{"type":"brand_new_frame","x":1}""")
        assertEquals("brand_new_frame", u.type)
        assertEquals(JsonPrimitive(1), u.raw!!["x"])
        assertEquals("unknown type", u.reason)

        assertNull(unknown("not json at all").type)
        assertNull(unknown("[1,2]").type)
        assertNull(unknown("""{"noType":true}""").type)
        assertNull(unknown("""{"type":7}""").type)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val pong = parseAs<ServerMessage.Pong>("""{"type":"pong","nonce":"n1","futureField":{"deep":[1,2]}}""")
        assertEquals("n1", pong.nonce)
        val seen = parseAs<ServerMessage.Seen>("""{"type":"seen","historyId":"h","seenAt":5,"origin":"x"}""")
        assertEquals(5L, seen.seenAt)
    }

    @Test
    fun missingOptionalFieldsDefault() {
        assertNull(parseAs<ServerMessage.Pong>("""{"type":"pong"}""").nonce)
        val nr = parseAs<ServerMessage.NodeResult>("""{"type":"node-result","ok":true}""")
        assertNull(nr.nodeId)
        assertNull(nr.requestId)
        val snap = parseAs<ServerMessage.Snapshot>("""{"type":"snapshot","sessionId":"s","throughSeq":9}""")
        assertFalse(snap.hasState)
        assertNull(snap.projection)
        assertFalse(snap.reset)
        assertNull(snap.trimmedBefore)
    }

    @Test
    fun wronglyTypedOptionalDegradesToNull() {
        // pong.nonce: string expected, number given -> absent, frame survives.
        assertNull(parseAs<ServerMessage.Pong>("""{"type":"pong","nonce":5}""").nonce)
        // created.requestId wrongly typed; session still decodes.
        val created = parseAs<ServerMessage.Created>(
            """{"type":"created","requestId":{"no":1},"session":$SESSION}""",
        )
        assertNull(created.requestId)
        assertEquals("s1", created.session.id)
        // interrupt_result.stillQueued with a non-string element: element dropped.
        val ir = parseAs<ServerMessage.InterruptResult>(
            """{"type":"interrupt_result","sessionId":"s","turnId":"t","status":"requested","stillQueued":["q1",3]}""",
        )
        assertEquals(listOf("q1"), ir.stillQueued)
        // snapshot.state wrongly typed -> treated as absent.
        assertFalse(parseAs<ServerMessage.Snapshot>("""{"type":"snapshot","sessionId":"s","throughSeq":1,"state":"x"}""").hasState)
        // ready.workspaceRoot null / wrong type -> null; v129 floor wrong type -> null.
        val ready = parseAs<ServerMessage.Ready>(
            """{"type":"ready","protocolVersion":129,"nativeProtocolFloor":"129","sessions":[],"providers":[],"workspaceRoot":null}""",
        )
        assertNull(ready.workspaceRoot)
        assertNull(ready.nativeProtocolFloor)
    }

    @Test
    fun malformedListElementIsDroppedNotTheFrame() {
        val ready = parseAs<ServerMessage.Ready>(
            """{"type":"ready","protocolVersion":132,"nativeProtocolFloor":129,
                "sessions":[$SESSION,{"id":"broken"}],"providers":[],"workspaceRoot":"/w"}""",
        )
        assertEquals(listOf("s1"), ready.sessions.map { it.id })
        assertEquals(129, ready.nativeProtocolFloor)
    }

    @Test
    fun missingRequiredFieldIsUnknownWithReason() {
        val noSession = unknown("""{"type":"created"}""")
        assertEquals("created", noSession.type)
        assertTrue(noSession.reason!!, noSession.reason.contains("session"))

        val badSession = unknown("""{"type":"session","session":{"id":"only-id"}}""")
        assertTrue(badSession.reason!!.contains("session"))

        val noSeq = unknown("""{"type":"snapshot","sessionId":"s"}""")
        assertTrue(noSeq.reason!!.contains("throughSeq"))

        // Required field present but wrongly typed counts as missing.
        val wrongType = unknown("""{"type":"seen","historyId":"h","seenAt":"yesterday"}""")
        assertTrue(wrongType.reason!!.contains("seenAt"))

        assertTrue(unknown("""{"type":"event","sessionId":"s"}""").reason!!.contains("event"))
        assertTrue(unknown("""{"type":"codex-controls","sessionId":"s"}""").reason!!.contains("snapshot"))
        assertTrue(unknown("""{"type":"metadata-draft-result","requestId":"r","result":{"ok":true}}""").reason!!.contains("title"))
    }

    @Test
    fun versionMismatchNeverDemoted() {
        val bare = parseAs<ServerMessage.VersionMismatch>("""{"type":"version_mismatch"}""")
        assertEquals(-1, bare.requiredVersion)
        val native = parseAs<ServerMessage.VersionMismatch>(
            """{"type":"version_mismatch","requiredVersion":129,"message":"update the app",
                "nativeProtocolFloor":129,"serverProtocolVersion":131,"reason":"client_too_old"}""",
        )
        assertEquals(129, native.nativeProtocolFloor)
        assertEquals(131, native.serverProtocolVersion)
        assertEquals("client_too_old", native.reason)
    }

    @Test
    fun handAuthoredApprovalFixtures() {
        val a = parseAs<ServerMessage.Approval>(ServerFixtures.APPROVAL)
        assertEquals("toolu_1", a.toolId)
        assertEquals(listOf("allow", "deny"), a.choices!!.map { it.choiceId })
        assertEquals("exact", a.choices.first().permissionGrant)
        assertEquals("rm -rf /tmp/x", a.input!!.jsonObject["command"]!!.jsonPrimitive.content)
        val requested = GrantedPermissions.from(a.metadata!!["requestedPermissions"]!!.jsonObject)
        assertEquals(listOf("/tmp/x"), requested.fileSystemWrite)
        assertNull(requested.fileSystemRead)

        val r = parseAs<ServerMessage.ApprovalResolved>(ServerFixtures.APPROVAL_RESOLVED)
        assertEquals("allow", r.choiceId)
        assertEquals(1, parseAs<ServerMessage.AcpAgents>(ServerFixtures.ACP_AGENTS).agents.size)
    }

    @Test
    fun metadataDraftVariants() {
        fun draft(result: String) =
            parseAs<ServerMessage.MetadataDraftResult>("""{"type":"metadata-draft-result","requestId":"r","result":$result}""").result
        assertEquals(MetadataDraft.CommitMessage("fix: x"), draft("""{"ok":true,"text":"fix: x"}"""))
        assertEquals(MetadataDraft.PullRequest("T", "B"), draft("""{"ok":true,"title":"T","body":"B"}"""))
        assertEquals(MetadataDraft.Failure("nope"), draft("""{"ok":false,"error":"nope"}"""))
    }

    @Test
    fun helloCarriesTheV129ClientField() {
        val hello = ClientMessage.Hello(TARGET_PROTOCOL_VERSION, HELLO_CLIENT_ANDROID).toJsonObject()
        assertEquals(setOf("type", "protocolVersion", "client"), hello.keys)
        assertEquals("android", hello["client"]!!.jsonPrimitive.content)
        assertEquals(setOf("type", "protocolVersion"), ClientMessage.Hello().toJsonObject().keys)
        // ta-koy: the app speaks v132; the native floor it documents stays 129.
        // ta-ylh: the wire types model v135, but the ADVERTISED hello stays 132 — see the pin below.
        assertEquals(135, TARGET_PROTOCOL_VERSION)
        assertEquals(129, NATIVE_PROTOCOL_FLOOR)
    }

    /**
     * ta-ylh OWNER GATE: the version the app ADVERTISES in `hello` is pinned at 132. A server
     * refuses a native hello newer than its own PROTOCOL_VERSION (lib/hello-compat.mjs,
     * server_too_old) and the deployed server was last known at 133, so 135 would lock the app
     * out. Raise this only after the owner confirms the deployed server is at >= the new value
     * (a separate owner-gated step, not a protocol catch-up task).
     */
    @Test
    fun advertisedHelloVersionIsPinnedAt132UntilTheOwnerGate() {
        assertEquals(132, PROTOCOL_VERSION)
        assertEquals(132, ClientMessage.Hello(client = HELLO_CLIENT_ANDROID).protocolVersion)
        val sent = ClientMessage.Hello(PROTOCOL_VERSION, HELLO_CLIENT_ANDROID).toJsonObject()
        assertEquals(132, sent["protocolVersion"]!!.jsonPrimitive.content.toInt())
        assertTrue("the types may run ahead of the hello, never behind it", PROTOCOL_VERSION <= TARGET_PROTOCOL_VERSION)
    }

    @Test
    fun clientEncodingOmitsAbsentOptionalsButKeepsTsNulls() {
        val create = ClientMessage.Create(provider = "codex", approvalsReviewer = OrNull(null)).toJsonObject()
        assertEquals(setOf("type", "provider", "approvalsReviewer"), create.keys)
        assertEquals(kotlinx.serialization.json.JsonNull, create["approvalsReviewer"])
        assertEquals(setOf("type", "claudeCliVersion"), ClientMessage.SetAdvancedSettings(null).toJsonObject().keys)
        assertEquals(setOf("type"), ClientMessage.RefreshProviders().toJsonObject().keys)
        assertEquals(setOf("type"), ClientMessage.Ping().toJsonObject().keys)
        assertTrue(runCatching { ClientMessage.Approval("s", "r", decision = "allow", grantedPermissions = GrantedPermissions()) }.isFailure)
    }

    @Test
    fun clientDecodeReportsMissingRequired() {
        val r = ClientMessage.decode(JsonObject(mapOf("type" to JsonPrimitive("pin"), "sessionId" to JsonPrimitive("s"))))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("pinned"))
        assertTrue(ClientMessage.decode(JsonObject(mapOf("type" to JsonPrimitive("nope")))).isFailure)
        assertNotNull(ClientMessage.decode(JsonObject(mapOf("type" to JsonPrimitive("detect-engines")))).getOrNull())
    }

    private companion object {
        const val SESSION =
            """{"id":"s1","provider":"claude","name":"one","cwd":"/w","status":"ready","startedAt":1,"updatedAt":2}"""
    }
}
