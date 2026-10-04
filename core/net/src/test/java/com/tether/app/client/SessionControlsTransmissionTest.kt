package com.tether.app.client

import com.tether.app.client.SessionControlsGuardTest.Companion.codexRaw
import com.tether.app.client.SessionControlsGuardTest.Companion.opencodeRaw
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7.2: the one place a session control reaches the wire (RealTetherClient over a MockWebServer
 * socket). `set-mode` / `set-model` / `set-reasoning-effort` / `set-fast-mode` and the provider
 * actions are operator controls: only a call (a tap) produces one, never anything received; each
 * goes out only on a live connection of the server the row was drawn for, for a session live on it
 * that may be driven, with a value the session's current state offers. Nothing is held for later.
 */
class SessionControlsTransmissionTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    private val controlTypes = setOf("set-mode", "set-model", "set-reasoning-effort", "set-fast-mode", "codex-control-action", "opencode-control-action")

    private fun ready(provider: String = "claude", engine: String? = null, extra: String = ""): String {
        val gen = if (engine != null) ""","engineGeneration":"$engine"""" else ""
        return """{"type":"ready","protocolVersion":${com.tether.app.protocol.PROTOCOL_VERSION},"nativeProtocolFloor":129,"sessions":[""" +
            """{"id":"s1","provider":"$provider"$gen,"name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":1,"endedAt":null,""" +
            """"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"$extra}],"providers":[],"workspaceRoot":null}"""
    }

    private val claudeControls =
        """{"type":"session-controls","sessionId":"s1","model":null,"defaultModel":"claude-opus-5",""" +
            """"models":[{"value":"claude-opus-5","displayName":"Opus","supportsFastMode":true,"variants":[{"value":"high","label":"high"}]},""" +
            """{"value":"claude-sonnet-5","displayName":"Sonnet"}],"commands":[]}"""

    private fun connected(ready: String = ready(), controls: String? = claudeControls): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, ready)
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 5))
        h.await(client.liveSessions) { "s1" in it }
        if (controls != null) {
            ws.send(controls)
            h.await(client.sessionControls) { it.containsKey("s1") }
        }
        return client to ws
    }

    private fun controlFrames(): List<JsonObject> = h.framesUntilBarrier().filter { it.type() in controlTypes }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun aTapSendsExactlyTheChosenValue() {
        val (client, _) = connected()
        val origin = client.consentOrigin.value
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.Mode("plan"), origin))
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.Model("claude-sonnet-5"), origin))
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.Effort("high"), origin))
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.FastMode(true), origin))
        val frames = controlFrames()
        assertEquals(listOf("set-mode", "set-model", "set-reasoning-effort", "set-fast-mode"), frames.map { it.type() })
        assertEquals("plan", frames[0].str("permissionMode"))
        assertEquals(setOf("type", "sessionId", "permissionMode"), frames[0].keys)
        assertEquals("claude-sonnet-5", frames[1].str("model"))
        assertEquals("high", frames[2].str("reasoningEffort"))
        assertEquals("true", frames[3].str("enabled"))
    }

    @Test
    fun aValueTheSessionDoesNotOfferIsNotSent() {
        val (client, _) = connected()
        val origin = client.consentOrigin.value
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.Mode("dontAsk"), origin))
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.Model("claude-evil"), origin))
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.Effort("max"), origin))
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.CodexCompaction("catalog-3"), origin))
        assertTrue(controlFrames().isEmpty())
        // ta-coik.7: Auto is offered and goes out on the first call, the web's set-mode (use-tether.ts:1693-1695).
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.Mode("bypassPermissions"), origin))
        val auto = controlFrames().single()
        assertEquals(setOf("type", "sessionId", "permissionMode"), auto.keys)
        assertEquals("set-mode", auto.str("type"))
        assertEquals("bypassPermissions", auto.str("permissionMode"))
    }

    @Test
    fun nothingReceivedEverProducesAControl() {
        val (client, ws) = connected()
        // A session broadcast moving the mode to Auto, a fresh controls reply, provider frames and
        // even a client-shaped set-mode echoed by the server: none of them may send anything.
        ws.send("""{"type":"session","session":{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":2,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless","permissionMode":"bypassPermissions","model":"claude-sonnet-5","fastModeState":"on"}}""")
        ws.send(claudeControls)
        ws.send("""{"type":"set-mode","sessionId":"s1","permissionMode":"bypassPermissions"}""")
        ws.send("""{"type":"codex-control-result","sessionId":"s1","ok":true,"message":"applied"}""")
        ws.send(eventFrame("s1", 6, "fast_mode", null, ""","state":"on""""))
        h.serverBarrier(ws)
        assertEquals("bypassPermissions", client.sessions.value.first { it.id == "s1" }.permissionMode)
        assertTrue("no frame the operator did not tap for", controlFrames().isEmpty())
    }

    @Test
    fun offlineIsRefusedAndNothingIsHeldButCatchingUpSendsAsOnTheWeb() {
        val (client, ws) = connected()
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        val stale = client.consentOrigin.value
        assertEquals(ControlResult.NotConnected, client.sessionControl("s1", SessionControl.Mode("plan"), stale))

        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2, ready())
        h.expectFrame("attach")
        assertTrue("the refused tap was not held for the new link", controlFrames().isEmpty())
        // ta-coik.23: catching up, the web's `send` puts the control on the open socket
        // (use-tether.ts 90fbb9f :337-344); the server answers a refusal with a shown `error`.
        assertTrue("s1 is still catching up", "s1" !in client.liveSessions.value)
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.Mode("plan"), client.consentOrigin.value))
        assertEquals(1, controlFrames().size)

        ws2.send(snapshotFrame("s1", 5))
        h.await(client.liveSessions) { "s1" in it }
        assertTrue("the snapshot sends nothing by itself", controlFrames().isEmpty())
    }

    @Test
    fun aReadOnlyHandedOffOrUnlistedSessionIsLocked() {
        val (client, ws) = connected(ready(extra = ""","readOnly":true"""))
        assertEquals(ControlResult.Locked, client.sessionControl("s1", SessionControl.Mode("plan"), client.consentOrigin.value))
        ws.send("""{"type":"session","session":{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":1,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless","handedOffTo":"s2"}}""")
        h.await(client.sessions) { list -> list.any { it.id == "s1" && it.handedOffTo == "s2" } }
        assertEquals(ControlResult.Locked, client.sessionControl("s1", SessionControl.Model("claude-sonnet-5"), client.consentOrigin.value))
        assertTrue(controlFrames().isEmpty())
    }

    @Test
    fun anUnlistedSessionFailsClosed() {
        val (client, _) = connected(readyFrame(), controls = null)
        assertEquals(ControlResult.Locked, client.sessionControl("s1", SessionControl.Mode("plan"), client.consentOrigin.value))
        assertTrue(controlFrames().isEmpty())
    }

    @Test
    fun aControlDrawnForAnotherServerIsRefused() {
        val (client, _) = connected()
        assertEquals(ControlResult.NotLive, client.sessionControl("s1", SessionControl.Mode("plan"), "https://other.example"))
        assertEquals(ControlResult.NotLive, client.sessionControl("s1", SessionControl.Mode("plan"), null))
        assertTrue(controlFrames().isEmpty())
    }

    @Test
    fun aHaltedClientSendsNothing() {
        val (client, _) = connected()
        val origin = client.consentOrigin.value
        client.stop()
        assertEquals(ControlResult.NotConnected, client.sessionControl("s1", SessionControl.Mode("plan"), origin))
    }

    @Test
    fun codexActionsBindToTheSnapshotOfThisSocketOnly() {
        val (client, ws) = connected(ready("codex", CODEX_V2), controls = null)
        val origin = client.consentOrigin.value
        // Before any catalog: nothing to bind an action to.
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.CodexCompaction("catalog-3"), origin))
        assertTrue(client.requestCodexControls("s1"))
        assertEquals("codex-controls", h.expectFrame("codex-controls").type())
        assertEquals(true, client.codexControls.value["s1"]?.busy)
        ws.send("""{"type":"codex-controls","sessionId":"s1","snapshot":${codexRaw()}}""")
        h.await(client.codexControls) { it["s1"]?.snapshot != null }
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.CodexModelSelection("gpt-5.5", "high", "catalog-3"), origin))
        assertEquals(true, client.codexControls.value["s1"]?.busy)
        val frame = controlFrames().single()
        val action = frame["action"]!!.jsonObject
        assertEquals("set-model-selection", action.str("type"))
        assertEquals("catalog-3", action.str("revision"))
        assertEquals("true", action.str("operatorAction"))
        assertTrue(action.str("operatorActionId")!!.isNotEmpty())
        ws.send("""{"type":"codex-control-result","sessionId":"s1","ok":true,"message":"Model applied."}""")
        h.await(client.codexControls) { it["s1"]?.message == "Model applied." }
        assertEquals(false, client.codexControls.value["s1"]?.busy)

        // The socket goes: the snapshot goes with it, and a reconnect re-reads before anything binds.
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        assertNull(client.codexControls.value["s1"])
        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2, ready("codex", CODEX_V2))
        h.expectFrame("attach")
        ws2.send(snapshotFrame("s1", 5))
        h.await(client.liveSessions) { "s1" in it }
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.CodexCompaction("catalog-3"), client.consentOrigin.value))
        assertTrue(controlFrames().isEmpty())
    }

    @Test
    fun aDrawFromTheCatalogBeforeTheLatestSendsNothing() {
        val (client, ws) = connected(ready("codex", CODEX_V2), controls = null)
        ws.send("""{"type":"codex-controls","sessionId":"s1","snapshot":${codexRaw()}}""")
        h.await(client.codexControls) { it["s1"]?.snapshot?.revision == "catalog-3" }
        // The engine re-read its catalogs while the panel was on screen.
        ws.send("""{"type":"codex-controls","sessionId":"s1","snapshot":${codexRaw().toString().replace("catalog-3", "catalog-4")}}""")
        h.await(client.codexControls) { it["s1"]?.snapshot?.revision == "catalog-4" }
        val origin = client.consentOrigin.value
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.CodexCompaction("catalog-3"), origin))
        assertTrue(controlFrames().isEmpty())
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.CodexCompaction("catalog-4"), origin))
        assertEquals("catalog-4", controlFrames().single()["action"]!!.jsonObject.str("revision"))
    }

    @Test
    fun codexAutoApproveIsSentOnTheFirstCall() {
        val (client, ws) = connected(ready("codex", CODEX_V2), controls = null)
        ws.send("""{"type":"codex-controls","sessionId":"s1","snapshot":${codexRaw()}}""")
        h.await(client.codexControls) { it["s1"]?.snapshot != null }
        val origin = client.consentOrigin.value
        // ta-coik.7: chat-view.tsx:2488-2497 + 2552-2555, no confirmation.
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.CodexAutoApprove(true, "catalog-3"), origin))
        val frame = controlFrames().single()
        assertEquals("codex-control-action", frame.str("type"))
        val action = frame["action"]!!.jsonObject
        assertEquals(setOf("type", "approvalPolicy", "revision", "operatorAction", "operatorActionId"), action.keys)
        assertEquals("set-approval-policy", action.str("type"))
        assertEquals("never", action.str("approvalPolicy"))
        assertEquals("catalog-3", action.str("revision"))
    }

    @Test
    fun opencodePanelActionsAreCheckedAndSent() {
        val (client, ws) = connected(ready("opencode", OPENCODE_V2), controls = null)
        ws.send("""{"type":"opencode-controls","sessionId":"s1","snapshot":${opencodeRaw()}}""")
        h.await(client.opencodeControls) { it["s1"]?.snapshot != null }
        val origin = client.consentOrigin.value
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.OpencodeModelSelection("openai/gpt-5", "max", "oc-1"), origin))
        // ta-coik.7: a danger agent applies on the first call (opencode-serve-controls.tsx:132-139).
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.OpencodeMode("yolo", "oc-1"), origin))
        val mode = controlFrames().single()["action"]!!.jsonObject
        assertEquals(setOf("type", "mode", "revision", "operatorAction", "operatorActionId"), mode.keys)
        assertEquals("set-mode", mode.str("type"))
        assertEquals("yolo", mode.str("mode"))
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.OpencodeModelSelection("openai/gpt-5", "high", "oc-1"), origin))
        val action = controlFrames().single()["action"]!!.jsonObject
        assertEquals("oc-1", action.str("revision"))
        assertEquals("high", action.str("variantId"))
    }
}
