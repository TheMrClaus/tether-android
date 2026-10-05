package com.tether.app.protocol

import com.tether.app.protocol.model.WorktreeInfo
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-m7ef: the wire changes tether PR #241 / #244 brought between 90fbb9f (v140) and 1bf4a465 (v143),
 * plus the two the app had not modelled since v137 (v139 `secretsSet`, v140 `resume.sandboxPolicy`).
 * lib/protocol.ts v141 `archive-stale`, v142 `autoArchiveIdleDays`, v143 `setupConsent`,
 * `worktree-inspect.worktree`, `archive-inspect` / `archive-preview`, `teardownConsent`, and
 * `setupSkipped` / `teardownSkipped`. Each frame: encode (an absent optional is omitted), decode back, and
 * the tolerant reads.
 */
class ProtocolV138To143WireTest {
    private val digest = "sha256:" + "ab".repeat(32)

    private fun roundTrip(message: ClientMessage): JsonObject {
        val encoded = message.toJsonObject()
        val decoded = ClientMessage.decode(encoded).getOrThrow()
        assertEquals(message, decoded)
        assertEquals(encoded, decoded.toJsonObject())
        return encoded
    }

    @Test
    fun theVersionIsTheModelled143() {
        assertEquals(143, TARGET_PROTOCOL_VERSION)
        assertEquals(129, NATIVE_PROTOCOL_FLOOR)
    }

    @Test
    fun createCarriesSetupConsentOnlyWhenSet() {
        val with = roundTrip(
            ClientMessage.Create(provider = "claude", useWorktree = true, worktree = WorktreeCreateRequest("branch-off"), setupConsent = digest, requestId = "r"),
        )
        assertEquals(digest, with["setupConsent"]!!.jsonPrimitive.content)
        val none = roundTrip(ClientMessage.Create(provider = "claude", useWorktree = true, worktree = WorktreeCreateRequest("branch-off"), setupConsent = "none"))
        assertEquals("none", none["setupConsent"]!!.jsonPrimitive.content)
        assertFalse(ClientMessage.Create(provider = "claude").toJsonObject().containsKey("setupConsent"))
    }

    @Test
    fun worktreeInspectCarriesTheIntendedCreateBlock() {
        val e = roundTrip(ClientMessage.WorktreeInspect("/w", "i1", WorktreeCreateRequest("checkout-pr", prNumber = 42)))
        assertEquals("checkout-pr", e["worktree"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals("42", e["worktree"]!!.jsonObject["prNumber"]!!.jsonPrimitive.content)
        assertFalse(ClientMessage.WorktreeInspect("/w", "i1").toJsonObject().containsKey("worktree"))
    }

    @Test
    fun archiveKillAndInspectFrames() {
        assertEquals(setOf("type", "sessionId", "requestId"), roundTrip(ClientMessage.ArchiveInspect("s1", "q")).keys)
        assertEquals(setOf("type", "sessionId"), roundTrip(ClientMessage.ArchiveInspect("s1")).keys)
        assertEquals(setOf("type", "sessionId", "teardownConsent"), roundTrip(ClientMessage.Kill("s1", digest)).keys)
        assertEquals(setOf("type", "sessionId", "teardownConsent"), roundTrip(ClientMessage.Archive("s1", "none")).keys)
        // A consent-less kill/archive is byte-identical to the pre-v143 frame (an older caller).
        assertEquals(setOf("type", "sessionId"), roundTrip(ClientMessage.Kill("s1")).keys)
        assertEquals(setOf("type", "sessionId"), roundTrip(ClientMessage.Archive("s1")).keys)
    }

    @Test
    fun archiveStaleAndResumeSandbox() {
        val run = roundTrip(ClientMessage.ArchiveStale("run", 15, "s9"))
        assertEquals("15", run["days"]!!.jsonPrimitive.content)
        assertEquals("s9", run["exceptSessionId"]!!.jsonPrimitive.content)
        assertEquals(setOf("type", "mode", "days"), roundTrip(ClientMessage.ArchiveStale("preview", 7)).keys)
        assertEquals("workspace-write", roundTrip(ClientMessage.Resume("h", "/w", sandboxPolicy = "workspace-write"))["sandboxPolicy"]!!.jsonPrimitive.content)
        assertFalse(ClientMessage.Resume("h", "/w").toJsonObject().containsKey("sandboxPolicy"))
    }

    @Test
    fun scheduleSetupConsentIsExplicitNullWhenOrNullNull() {
        fun input(consent: OrNull<String>?) = ScheduledActionInput(
            "n", "p", "/w", "claude", null, null, null, null, null, true, "0 9 * * *", "UTC", null, setupConsent = consent,
        )
        val approved = roundTrip(ClientMessage.ScheduleCreate(input(OrNull(digest))))["schedule"]!!.jsonObject
        assertEquals(digest, approved["setupConsent"]!!.jsonPrimitive.content)
        val declined = roundTrip(ClientMessage.ScheduleCreate(input(OrNull(null))))["schedule"]!!.jsonObject
        assertTrue(declined["setupConsent"] is JsonNull)
        val omitted = roundTrip(ClientMessage.ScheduleCreate(input(null)))["schedule"]!!.jsonObject
        assertFalse(omitted.containsKey("setupConsent"))
        // It is a modelled key now: a stored schedule's `extra` never carries it back a second time.
        assertTrue("setupConsent" in ScheduledActionInput.KEYS)
    }

    @Test
    fun archivePreviewDecodesPresentNullAndMalformed() {
        val ok = ServerMessage.parse(ServerFixtures.ARCHIVE_PREVIEW) as ServerMessage.ArchivePreview
        assertEquals("sess-1", ok.sessionId)
        assertEquals("r1", ok.requestId)
        assertFalse(ok.malformed)
        assertEquals("pnpm run teardown", ok.preview!!["commands"].toString().trim('[', ']', '"'))
        val none = ServerMessage.parse("""{"type":"archive-preview","sessionId":"s","preview":null,"requestId":"q"}""") as ServerMessage.ArchivePreview
        assertNull(none.preview)
        assertFalse(none.malformed)
        val absent = ServerMessage.parse("""{"type":"archive-preview","sessionId":"s"}""") as ServerMessage.ArchivePreview
        assertTrue(absent.malformed)
        val wrong = ServerMessage.parse("""{"type":"archive-preview","sessionId":"s","preview":"x"}""") as ServerMessage.ArchivePreview
        assertTrue(wrong.malformed)
        assertTrue(ServerMessage.parse("""{"type":"archive-preview"}""") is ServerMessage.Unknown)
    }

    @Test
    fun archiveStaleResultDecodes() {
        val r = ServerMessage.parse(ServerFixtures.ARCHIVE_STALE_RESULT) as ServerMessage.ArchiveStaleResult
        assertEquals("preview", r.mode)
        assertEquals(15, r.days)
        assertEquals(12, r.eligible)
        assertEquals(12, r.remaining)
        assertEquals(ArchiveStaleSkipped(pinned = 1, pendingRequest = 2, viewing = 1), r.skipped)
        assertEquals(ArchiveStaleRetention(cap = 500, retiredNow = 0, willBePruned = 3), r.retention)
        assertTrue(ServerMessage.parse("""{"type":"archive-stale-result","mode":"run"}""") is ServerMessage.Unknown)
    }

    @Test
    fun serverSettingsCarriesSecretsSetAndAutoArchiveIdleDaysRaw() {
        val m = ServerMessage.parse(
            """{"type":"server-settings","settings":{"archiveOnMerge":false,"autoArchiveIdleDays":14,"password":""},
               "envForced":{},"restartRequired":false,"discovered":[],"detected":{},"secretsSet":{"password":true,"proxyToken":false}}""",
        ) as ServerMessage.ServerSettings
        assertEquals(mapOf("password" to true, "proxyToken" to false), m.secretsSet)
        assertEquals("14", m.settings["autoArchiveIdleDays"]!!.jsonPrimitive.content)
        val old = ServerMessage.parse("""{"type":"server-settings","settings":{},"envForced":{},"restartRequired":false,"discovered":[],"detected":{}}""") as ServerMessage.ServerSettings
        assertTrue(old.secretsSet.isEmpty())
        assertFalse(m.toString().contains("14"))
    }

    @Test
    fun sessionWorktreeCarriesTheSkipReasons() {
        val ready = ServerMessage.parse(
            """{"type":"ready","protocolVersion":143,"nativeProtocolFloor":129,"providers":[],"workspaceRoot":null,
               "sessions":[{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"idle","startedAt":1,"updatedAt":2,
               "worktree":{"path":"/w/.t/a","branch":"b","status":"active","setupStatus":"none","setupSkipped":"consent-mismatch",
                           "teardownSkipped":"sessions-changed"}}]}""",
        ) as ServerMessage.Ready
        val wt: WorktreeInfo = ready.sessions.single().worktree!!
        assertEquals("consent-mismatch", wt.setupSkipped)
        assertEquals("sessions-changed", wt.teardownSkipped)
        val plain = ServerMessage.parse(
            """{"type":"ready","protocolVersion":143,"nativeProtocolFloor":129,"providers":[],"workspaceRoot":null,
               "sessions":[{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"idle","startedAt":1,"updatedAt":2,
               "worktree":{"path":"/w/.t/a","branch":"b","status":"active"}}]}""",
        ) as ServerMessage.Ready
        assertNull(plain.sessions.single().worktree!!.setupSkipped)
        assertNull(plain.sessions.single().worktree!!.teardownSkipped)
    }
}
