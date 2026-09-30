package com.tether.app.client

import com.tether.app.protocol.DelegateMention
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7.3: the one place a `!` command RUN, a foreground command's BACKGROUND and a DELEGATED send reach
 * the wire (RealTetherClient over a MockWebServer socket). Each is an operator control: only a call
 * (a tap, an explicit submit) produces one, never anything received; it goes out only on a live,
 * handshaken link of the server the composer was drawn for, for a session live on it that may be
 * driven, with what the server offered (command mode for the provider; the catalog's agents, models,
 * efforts), and nothing is held for later.
 */
class CommandTransmissionTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    private fun provider(id: String, commandRunner: Boolean) =
        """{"id":"$id","label":"$id","glyph":"${id.take(1)}","available":true,"capabilities":{"commandRunner":$commandRunner}}"""

    private fun ready(
        extra: String = "",
        providers: String = provider("claude", true) + "," + provider("codex", false),
        vararg ids: String = arrayOf("s1"),
    ): String {
        val rows = ids.joinToString(",") { id ->
            """{"id":"$id","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":1,"endedAt":null,""" +
                """"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"$extra}"""
        }
        return """{"type":"ready","protocolVersion":${com.tether.app.protocol.PROTOCOL_VERSION},"nativeProtocolFloor":129,"sessions":[$rows],"providers":[$providers],"workspaceRoot":null}"""
    }

    private fun idleState(): String = JsCodec.toJson(freshTree()).toString()

    /** An ordinary agent turn t1 running. */
    private fun busyState(): String = JsCodec.toJson(foldTree(freshTree(), ev("turn_started", "t1", seq = 1, ts = 1))).toString()

    /** A FOREGROUND `!` command turn [turnId] running (turn_started.commandRun, v54). */
    private fun commandState(turnId: String = "t1"): String = JsCodec.toJson(
        foldTree(
            freshTree(),
            ev("turn_started", turnId, seq = 1, ts = 1) {
                put("commandRun", buildJsonObject { put("command", "npm test"); put("cwd", "/w"); put("logFile", "/w/.tether/c.log") })
            },
            ev("command_output_started", turnId, seq = 2, ts = 2) { put("blockId", "b1"); put("command", "npm test"); put("logFile", "/w/.tether/c.log") },
        ),
    ).toString()

    private fun connected(ready: String = ready(), state: String = idleState()): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, ready)
        client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 5, state))
        h.await(client.liveSessions) { "s1" in it }
        return client to ws
    }

    private fun frames(type: String): List<JsonObject> = h.framesUntilBarrier().filter { it.type() == type }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    // --- run-command ---------------------------------------------------------------------------

    @Test
    fun aRunSendsExactlyTheWebsFrameWithAFreshKeyEachTime() {
        val (client, _) = connected()
        val origin = client.consentOrigin.value
        assertEquals(RunCommandResult.Sent, client.runCommand("s1", "npm test", background = false, expectedOrigin = origin))
        assertEquals(RunCommandResult.Sent, client.runCommand("s1", "sleep 30", background = true, expectedOrigin = origin))
        val sent = frames("run-command")
        assertEquals(2, sent.size)
        // use-tether.ts:1557-1565: { type, sessionId, command, idempotencyKey, ...(background ? { background: true } : {}) }.
        assertEquals(setOf("type", "sessionId", "command", "idempotencyKey"), sent[0].keys)
        assertEquals("npm test", sent[0].str("command"))
        assertEquals(setOf("type", "sessionId", "command", "idempotencyKey", "background"), sent[1].keys)
        assertEquals("true", sent[1].str("background"))
        assertEquals("s1", sent[1].str("sessionId"))
        assertNotEquals("every run mints its own key", sent[0].str("idempotencyKey"), sent[1].str("idempotencyKey"))
    }

    @Test
    fun aProviderTheServerDoesNotOfferCommandModeForIsRefused() {
        val (client, _) = connected(ready(providers = provider("claude", false)))
        assertEquals(RunCommandResult.NotOffered, client.runCommand("s1", "ls", false, client.consentOrigin.value))
        assertTrue(frames("run-command").isEmpty())
    }

    @Test
    fun noProviderListIsNotOffered() {
        val (client, _) = connected(ready(providers = ""))
        assertEquals(RunCommandResult.NotOffered, client.runCommand("s1", "ls", true, client.consentOrigin.value))
        assertTrue(frames("run-command").isEmpty())
    }

    @Test
    fun anEmptyOrOversizedCommandIsRefused() {
        val (client, _) = connected()
        val origin = client.consentOrigin.value
        assertEquals(RunCommandResult.Invalid, client.runCommand("s1", "", false, origin))
        assertEquals(RunCommandResult.Invalid, client.runCommand("s1", "   ", false, origin))
        // protocol-validate.mjs LIMITS.COMMAND_BYTES = 16 KiB of UTF-8: "é" is two bytes.
        assertEquals(RunCommandResult.Invalid, client.runCommand("s1", "é".repeat(8 * 1024 + 1), false, origin))
        assertTrue(frames("run-command").isEmpty())
        assertEquals(RunCommandResult.Sent, client.runCommand("s1", "é".repeat(8 * 1024), false, origin))
        assertEquals(1, frames("run-command").size)
    }

    @Test
    fun aForegroundRunWaitsForTheTurnButABackgroundRunDoesNot() {
        val (client, _) = connected(state = busyState())
        val origin = client.consentOrigin.value
        assertEquals(RunCommandResult.Busy, client.runCommand("s1", "npm test", false, origin))
        assertTrue(frames("run-command").isEmpty())
        assertEquals(RunCommandResult.Sent, client.runCommand("s1", "npm test", true, origin))
        assertEquals(1, frames("run-command").size)
    }

    @Test
    fun aRunDrawnForAnotherServerIsRefused() {
        val (client, _) = connected()
        assertEquals(RunCommandResult.NotLive, client.runCommand("s1", "ls", false, "https://other.example"))
        assertEquals(RunCommandResult.NotLive, client.runCommand("s1", "ls", false, null))
        assertTrue(frames("run-command").isEmpty())
    }

    @Test
    fun readOnlyHandedOffArchivedOrUnlistedSessionsRunNothing() {
        val (client, ws) = connected(ready(extra = ""","readOnly":true"""))
        assertEquals(RunCommandResult.Locked, client.runCommand("s1", "ls", false, client.consentOrigin.value))
        ws.send("""{"type":"session","session":{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":2,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless","handedOffTo":"s2"}}""")
        h.await(client.sessions) { list -> list.any { it.id == "s1" && it.handedOffTo == "s2" } }
        assertEquals(RunCommandResult.Locked, client.runCommand("s1", "ls", false, client.consentOrigin.value))
        ws.send("""{"type":"session","session":{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":3,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":true,"mode":"headless"}}""")
        h.await(client.sessions) { list -> list.any { it.id == "s1" && it.runtimeArchived } }
        assertEquals(RunCommandResult.Locked, client.runCommand("s1", "ls", false, client.consentOrigin.value))
        assertTrue(frames("run-command").isEmpty())
    }

    @Test
    fun aLiveButUnlistedSessionFailsClosed() {
        // Attached and confirmed on this link, but the server's list does not carry it.
        val (client, _) = connected(ready(ids = arrayOf()), state = commandState())
        assertEquals(RunCommandResult.Locked, client.runCommand("s1", "ls", true, client.consentOrigin.value))
        assertEquals(BackgroundCommandResult.Locked, client.backgroundCommand("s1", client.consentOrigin.value, "t1"))
        assertEquals(RunCommandResult.NotLive, client.runCommand("s-unknown", "ls", true, client.consentOrigin.value))
        assertTrue(h.framesUntilBarrier().none { it.type() == "run-command" || it.type() == "background-command" })
    }

    @Test
    fun offlineOrCatchingUpRunsNothingAndNothingIsHeldForTheReconnect() {
        val (client, ws) = connected()
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        assertEquals(RunCommandResult.NotConnected, client.runCommand("s1", "ls", false, client.consentOrigin.value))
        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2, ready())
        h.expectFrame("attach")
        // Catching up (no snapshot on this link yet): refused.
        assertEquals(RunCommandResult.NotLive, client.runCommand("s1", "ls", false, client.consentOrigin.value))
        assertEquals(BackgroundCommandResult.NotLive, client.backgroundCommand("s1", client.consentOrigin.value, "t1"))
        assertTrue("the refused taps were not held for the new link", frames("run-command").isEmpty())
        ws2.send(snapshotFrame("s1", 5, idleState()))
        h.await(client.liveSessions) { "s1" in it }
        val after = h.framesUntilBarrier()
        assertTrue("the snapshot sends nothing by itself", after.none { it.type() == "run-command" || it.type() == "background-command" })
    }

    @Test
    fun nothingReceivedEverProducesARunOrABackground() {
        val (client, ws) = connected(state = commandState())
        ws.send(eventFrame("s1", 6, "command_output_delta", "t1", ""","blockId":"b1","stream":"stdout","text":"run-command background-command\n""""))
        ws.send("""{"type":"run-command","sessionId":"s1","command":"rm -rf /","idempotencyKey":"x"}""")
        ws.send("""{"type":"background-command","sessionId":"s1"}""")
        h.serverBarrier(ws)
        assertTrue(client.projectionTrees.value["s1"].toString().contains("run-command background-command"))
        val sent = h.framesUntilBarrier()
        assertTrue("no frame the operator did not ask for", sent.none { it.type() == "run-command" || it.type() == "background-command" })
    }

    @Test
    fun aHaltedClientRunsNothing() {
        val (client, _) = connected()
        val origin = client.consentOrigin.value
        client.stop()
        assertEquals(RunCommandResult.NotConnected, client.runCommand("s1", "ls", false, origin))
        assertEquals(BackgroundCommandResult.NotConnected, client.backgroundCommand("s1", origin, "t1"))
    }

    // --- background-command --------------------------------------------------------------------

    @Test
    fun backgroundOnTheForegroundCommandTurnSendsExactlyTheWebsFrame() {
        val (client, _) = connected(state = commandState())
        assertEquals(BackgroundCommandResult.Sent, client.backgroundCommand("s1", client.consentOrigin.value, "t1"))
        val sent = frames("background-command")
        assertEquals(1, sent.size)
        // use-tether.ts:1567: { type: "background-command", sessionId } and nothing else.
        assertEquals(setOf("type", "sessionId"), sent[0].keys)
        assertEquals("s1", sent[0].str("sessionId"))
    }

    @Test
    fun anOrdinaryTurnIsNeverBackgrounded() {
        val (client, _) = connected(state = busyState())
        assertEquals(BackgroundCommandResult.NotRunning, client.backgroundCommand("s1", client.consentOrigin.value, "t1"))
        assertTrue(frames("background-command").isEmpty())
    }

    @Test
    fun aKeyDrawnForAnotherTurnOrAFinishedCommandSendsNothing() {
        val (client, ws) = connected(state = commandState("t2"))
        assertEquals(BackgroundCommandResult.NotRunning, client.backgroundCommand("s1", client.consentOrigin.value, "t1"))
        assertEquals(BackgroundCommandResult.NotRunning, client.backgroundCommand("s1", client.consentOrigin.value, ""))
        ws.send(eventFrame("s1", 6, "command_output_completed", "t2", ""","blockId":"b1","exitCode":0,"signal":null,"timedOut":false,"killed":false,"outputTruncated":false"""))
        ws.send(eventFrame("s1", 7, "turn_end", "t2", ""","outcome":"ok""""))
        h.await(client.projectionTrees) { trees -> trees["s1"].toString().contains("\"done\"") && !trees["s1"].toString().contains("\"activeTurnId\":\"t2\"") }
        assertEquals(BackgroundCommandResult.NotRunning, client.backgroundCommand("s1", client.consentOrigin.value, "t2"))
        assertTrue(frames("background-command").isEmpty())
    }

    @Test
    fun backgroundDrawnForAnotherServerOrALockedSessionIsRefused() {
        val (client, ws) = connected(state = commandState())
        assertEquals(BackgroundCommandResult.NotLive, client.backgroundCommand("s1", "https://other.example", "t1"))
        assertEquals(BackgroundCommandResult.NotLive, client.backgroundCommand("s1", null, "t1"))
        ws.send("""{"type":"session","session":{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":2,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless","readOnly":true}}""")
        h.await(client.sessions) { list -> list.any { it.id == "s1" && it.readOnly } }
        assertEquals(BackgroundCommandResult.Locked, client.backgroundCommand("s1", client.consentOrigin.value, "t1"))
        assertTrue(frames("background-command").isEmpty())
    }

    @Test
    fun stopOnAForegroundCommandIsTheTurnBoundInterrupt() {
        // chat-view.tsx:4474: the relabelled key sends `interrupt` (T6.7's guarded path, bound to the turn).
        val (client, _) = connected(state = commandState())
        assertEquals(InterruptResult.NotCurrentTurn, client.interrupt("s1", client.consentOrigin.value, "t0"))
        assertEquals(InterruptResult.Sent, client.interrupt("s1", client.consentOrigin.value, "t1"))
        val sent = h.framesUntilBarrier()
        assertEquals(listOf("interrupt"), sent.map { it.type() })
        assertEquals(setOf("type", "sessionId"), sent[0].keys)
    }

    // --- the @ Agents catalog and the delegated send ---------------------------------------------

    private val catalogFrame = """{"type":"providers-snapshot","entries":[
        {"key":"claude","provider":"claude","status":"ready","defaultModel":"claude-opus-5","label":"Claude","models":[
          {"value":"claude-opus-5","displayName":"Opus 5","variants":[{"value":"high","label":"High"}]},
          {"value":"claude-haiku-5","displayName":"Haiku 5"}]},
        {"key":"codex","provider":"codex","status":"ready","models":[]},
        {"key":"acp:x","provider":"acp","status":"ready","models":[]},
        {"key":"gemini","provider":"gemini","status":"ready","models":[]},
        {"key":"prof-1","provider":"claude","status":"ready","profileId":"prof-1","models":[]},
        {"key":"pi","provider":"pi","status":"unavailable","models":[]}]}"""

    private fun withCatalog(ready: String = ready()): Pair<RealTetherClient, WebSocket> {
        val (client, ws) = connected(ready)
        assertTrue(client.requestProviderCatalog())
        val asked = frames("providers-snapshot")
        assertEquals(1, asked.size)
        assertEquals(setOf("type"), asked[0].keys)
        ws.send(catalogFrame)
        h.await(client.providerCatalog) { it.isNotEmpty() }
        return client to ws
    }

    @Test
    fun theCatalogOffersOnlyEnabledProviderRows() {
        val (client, _) = withCatalog()
        val session = client.sessions.value.first { it.id == "s1" }
        assertEquals(listOf("claude", "codex"), CommandGuard.delegateAgents(session, client.providerCatalog.value).map { it.key })
    }

    @Test
    fun aDelegatedSendCarriesExactlyTheOfferedMention() {
        val (client, _) = withCatalog()
        val mention = DelegateMention(provider = "claude", mode = "build", model = "claude-opus-5", reasoningEffort = "high")
        assertEquals(MentionResult.Sent, client.sendDelegated("s1", "review the diff", emptyList(), mention, client.consentOrigin.value))
        val sent = frames("send")
        assertEquals(1, sent.size)
        val m = sent[0]["mention"]!!.jsonObject
        assertEquals(setOf("kind", "provider", "model", "reasoningEffort", "mode"), m.keys)
        assertEquals("delegate", m["kind"]!!.jsonPrimitive.content)
        assertEquals("claude", m["provider"]!!.jsonPrimitive.content)
        assertEquals("build", m["mode"]!!.jsonPrimitive.content)
        assertEquals("review the diff", sent[0].str("text"))
    }

    @Test
    fun aMentionTheCatalogDoesNotOfferIsNeverRecorded() {
        val (client, _) = withCatalog()
        val refused = listOf(
            DelegateMention(provider = "acp", mode = "review"),
            DelegateMention(provider = "gemini", mode = "review"),
            DelegateMention(provider = "pi", mode = "review"),
            DelegateMention(provider = "reasonix", mode = "review"),
            DelegateMention(provider = "claude", mode = "yolo"),
            DelegateMention(provider = "claude", mode = "review", model = "claude-unlisted"),
            DelegateMention(provider = "claude", mode = "review", model = "claude-haiku-5", reasoningEffort = "high"),
            DelegateMention(provider = "claude", mode = "review", reasoningEffort = "high"),
        )
        for (m in refused) assertEquals("$m", MentionResult.NotOffered, client.sendDelegated("s1", "x", emptyList(), m, client.consentOrigin.value))
        assertTrue(frames("send").isEmpty())
    }

    @Test
    fun aDelegateChildIsNeverOfferedDelegation() {
        val (client, _) = withCatalog(ready(extra = ""","parentSessionId":"p1""""))
        assertEquals(MentionResult.NotOffered, client.sendDelegated("s1", "x", emptyList(), DelegateMention("claude", "review"), client.consentOrigin.value))
        assertTrue(frames("send").isEmpty())
    }

    @Test
    fun theWarmControlsReadAsksWithWarm() {
        val (client, _) = connected()
        client.requestWarmSessionControls("s1")
        val sent = frames("session-controls")
        assertEquals(1, sent.size)
        assertEquals("true", sent[0].str("warm"))
    }

    // --- r2 --------------------------------------------------------------------------------------

    @Test
    fun aDelegationDrawnForAnotherServerOrForNoneIsNeverRecorded() {
        val (client, _) = withCatalog()
        val m = DelegateMention("claude", "review")
        assertEquals(MentionResult.NotLive, client.sendDelegated("s1", "x", emptyList(), m, "https://other.example"))
        assertEquals(MentionResult.NotLive, client.sendDelegated("s1", "x", emptyList(), m, null))
        assertTrue(frames("send").isEmpty())
    }

    @Test
    fun aReadOnlyHandedOffArchivedOrUnlistedSessionIsNeverDelegatedFrom() {
        val (client, ws) = withCatalog()
        val m = DelegateMention("claude", "review")
        val origin = client.consentOrigin.value
        assertEquals(MentionResult.Locked, client.sendDelegated("s-unknown", "x", emptyList(), m, origin))
        fun row(extra: String, updated: Int) =
            """{"type":"session","session":{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":$updated,"endedAt":null,"exitCode":null,"pinned":false,"mode":"headless"$extra}}"""
        ws.send(row(""","runtimeArchived":false,"readOnly":true""", 2))
        h.await(client.sessions) { list -> list.any { it.id == "s1" && it.readOnly } }
        assertEquals(MentionResult.Locked, client.sendDelegated("s1", "x", emptyList(), m, origin))
        ws.send(row(""","runtimeArchived":false,"handedOffTo":"s2"""", 3))
        h.await(client.sessions) { list -> list.any { it.id == "s1" && it.handedOffTo == "s2" } }
        assertEquals(MentionResult.Locked, client.sendDelegated("s1", "x", emptyList(), m, origin))
        ws.send(row(""","runtimeArchived":true""", 4))
        h.await(client.sessions) { list -> list.any { it.id == "s1" && it.runtimeArchived } }
        assertEquals(MentionResult.Locked, client.sendDelegated("s1", "x", emptyList(), m, origin))
        assertTrue(frames("send").isEmpty())
    }

    private fun outputBlock(client: RealTetherClient): com.tether.app.protocol.tree.JsObj {
        val turns = client.projectionTrees.value.getValue("s1")["turnsById"] as com.tether.app.protocol.tree.JsObj
        val blocks = (turns["t1"] as com.tether.app.protocol.tree.JsObj)["blocksById"] as com.tether.app.protocol.tree.JsObj
        return blocks["b1"] as com.tether.app.protocol.tree.JsObj
    }

    private fun outputChars(block: com.tether.app.protocol.tree.JsObj): Int =
        (block["segments"] as com.tether.app.protocol.tree.JsArr).sumOf { (((it as com.tether.app.protocol.tree.JsObj)["text"]) as com.tether.app.protocol.tree.JsStr).value.length }

    @Test
    fun anEndlessForegroundOutputStreamStaysBoundedAndEachDeltaScansOneBlock() {
        val (client, ws) = connected(state = commandState())
        val chunk = "z".repeat(60_000)
        var seq = 6L
        for (i in 0 until 6) ws.send(eventFrame("s1", seq++, "command_output_delta", "t1", ""","blockId":"b1","stream":"${if (i % 2 == 0) "stdout" else "stderr"}","text":"#$i#$chunk""""))
        h.await(client.projectionTrees) { trees -> trees["s1"].toString().contains("#5#") }
        val block = outputBlock(client)
        assertTrue("kept ${outputChars(block)}", outputChars(block) <= OutputIntakeCap.MAX_CHARS)
        assertEquals(com.tether.app.protocol.tree.JsBool.TRUE, block["outputTruncated"])
        // One more delta: the publish scans that one block, not the stored history.
        val before = OutputIntakeCap.outputBlocksScanned.get()
        ws.send(eventFrame("s1", seq++, "command_output_delta", "t1", ""","blockId":"b1","stream":"stdout","text":"#last#""""))
        h.await(client.projectionTrees) { trees -> trees["s1"].toString().contains("#last#") }
        assertEquals(1L, OutputIntakeCap.outputBlocksScanned.get() - before)
        assertTrue(outputChars(outputBlock(client)) <= OutputIntakeCap.MAX_CHARS)
    }

    @Test
    fun anOversizedForegroundOutputSnapshotIsCappedWhenPublished() {
        val big = foldTree(
            JsCodec.parse(commandState()) as com.tether.app.protocol.tree.JsObj,
            ev("command_output_delta", "t1", seq = 3, ts = 3) { put("blockId", "b1"); put("stream", "stdout"); put("text", "q".repeat(OutputIntakeCap.MAX_CHARS + 5_000)) },
        )
        val (client, _) = connected(state = JsCodec.toJson(big).toString())
        assertEquals(OutputIntakeCap.MAX_CHARS, outputChars(outputBlock(client)))
        assertEquals(com.tether.app.protocol.tree.JsBool.TRUE, outputBlock(client)["outputTruncated"])
    }
}
