package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v144 (ta-j3h9): a console-credential write is `set-server-settings {settings, currentPassword, requestId}`, settled by
 * the correlated `server-settings-result`, the web's 20 s no-reply window, or at once with no socket
 * (use-tether.ts:1940-1963). Every secret here is obviously fake.
 */
class ServerSecretWriteTest {
    private val h = ConnectionHarness()
    private val current = "SENTINEL-current-77aa"
    private val fresh = "SENTINEL-new-91cd"

    @After fun tearDown() = h.close()

    private fun <T> Deferred<T>.get(): T = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { await() } }

    private fun origin(): String = serverOrigin(h.server.url("/").toString())!!

    private fun connected(): WebSocket {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return ws
    }

    private fun patch(key: String = "proxyToken", value: String = fresh): JsonObject =
        TetherJson.parseToJsonElement("""{"$key":"$value"}""").jsonObject

    private fun write(p: JsonObject = patch()): Deferred<ServerSecretOutcome> = h.scope.async { h.client.changeServerSecret(p, current, origin()) }

    private fun pending(): Int = h.client.pendingSecretRequestCount()

    private fun awaitIdle() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (pending() != 0 && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("nothing leaks", 0, pending())
    }

    private fun result(requestId: String?, ok: Boolean, reason: String? = null, message: String? = null) = buildString {
        append("""{"type":"server-settings-result","ok":$ok""")
        if (requestId != null) append(""","requestId":"$requestId"""")
        if (reason != null) append(""","reason":"$reason"""")
        if (message != null) append(""","message":"$message"""")
        append("}")
    }

    // ---- the frame ------------------------------------------------------------------------------

    @Test fun theFrameCarriesTheCurrentPasswordAndAFreshRequestIdAndOnlyTheOneSecretKey() {
        connected()
        val sent = write()
        val frame = h.expectFrame("set-server-settings")
        assertEquals(setOf("type", "settings", "currentPassword", "requestId"), frame.keys)
        assertEquals(setOf("proxyToken"), frame["settings"]!!.jsonObject.keys)
        assertEquals(fresh, frame["settings"]!!.jsonObject["proxyToken"]!!.jsonPrimitive.content)
        assertEquals(current, frame["currentPassword"]!!.jsonPrimitive.content)
        val id = frame["requestId"]!!.jsonPrimitive.content
        assertEquals("a random UUID, under the server's 64 cap", 36, id.length)
        sent.cancel()
        awaitIdle()
    }

    @Test fun twoWritesUseTwoRequestIds() {
        connected()
        val a = write()
        val idA = h.expectFrame("set-server-settings")["requestId"]!!.jsonPrimitive.content
        val b = write()
        val idB = h.expectFrame("set-server-settings")["requestId"]!!.jsonPrimitive.content
        assertTrue(idA != idB)
        a.cancel(); b.cancel()
        awaitIdle()
    }

    @Test fun aClearIsAnEmptyProxyTokenAndAPasswordIsItsOwnKey() {
        connected()
        val clear = write(patch("proxyToken", ""))
        assertEquals("", h.expectFrame("set-server-settings")["settings"]!!.jsonObject["proxyToken"]!!.jsonPrimitive.content)
        val pw = write(patch("password", fresh))
        assertEquals(setOf("password"), h.expectFrame("set-server-settings")["settings"]!!.jsonObject.keys)
        clear.cancel(); pw.cancel()
        awaitIdle()
    }

    @Test fun theFramesToStringNamesNoSecret() {
        val text = ClientMessage.SetServerSettings(patch(), current, "req-1").toString()
        assertFalse(text, text.contains(current))
        assertFalse(text, text.contains(fresh))
        assertTrue(text, text.contains("proxyToken") && text.contains("req-1"))
        val plain = ClientMessage.SetServerSettings(patch()).toString()
        assertFalse(plain, plain.contains(fresh))
    }

    // ---- the answer -------------------------------------------------------------------------------

    @Test fun aCorrelatedOkResultSavesIt() {
        val ws = connected()
        val sent = write()
        val id = h.expectFrame("set-server-settings")["requestId"]!!.jsonPrimitive.content
        ws.send(result(id, ok = true))
        assertEquals(ServerSecretOutcome.Saved, sent.get())
        awaitIdle()
    }

    @Test fun aRefusalCarriesTheServersReasonAndOwnWords() {
        val ws = connected()
        val sent = write()
        val id = h.expectFrame("set-server-settings")["requestId"]!!.jsonPrimitive.content
        ws.send(result(id, ok = false, reason = "bad_current_password", message = "That isn't the current password. Nothing was changed."))
        assertEquals(ServerSecretOutcome.Refused("bad_current_password", "That isn't the current password. Nothing was changed."), sent.get())
        awaitIdle()
    }

    @Test fun aRefusalWithoutReasonOrMessageReadsAsTheWebsFallbacks() {
        val ws = connected()
        val sent = write()
        val id = h.expectFrame("set-server-settings")["requestId"]!!.jsonPrimitive.content
        ws.send(result(id, ok = false))
        assertEquals(ServerSecretOutcome.Refused("weak", "The change was refused."), sent.get())
        awaitIdle()
    }

    @Test fun aResultForAnotherRequestIsIgnored() {
        val ws = connected()
        val sent = write()
        val id = h.expectFrame("set-server-settings")["requestId"]!!.jsonPrimitive.content
        ws.send(result("someone-elses-request", ok = true))
        ws.send(result(null, ok = true))
        ws.send(result(id, ok = false, reason = "throttled", message = "Too many wrong attempts from this address. Wait a few minutes, then try again."))
        assertEquals(ServerSecretOutcome.Refused("throttled", "Too many wrong attempts from this address. Wait a few minutes, then try again."), sent.get())
        awaitIdle()
    }

    // ---- no reply -----------------------------------------------------------------------------------

    @Test fun noReplyInTheWindowIsNoReplyAndALateResultChangesNothing() {
        val ws = connected()
        val sent = write()
        val id = h.expectFrame("set-server-settings")["requestId"]!!.jsonPrimitive.content
        h.scheduler.await { it == 20_000L }.fire()
        assertEquals(ServerSecretOutcome.NoReply, sent.get())
        awaitIdle()
        ws.send(result(id, ok = true))
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
    }

    @Test fun aWriteDrawnFromAnotherServerIsNoReplyAndNothingIsSent() {
        connected()
        val outcome = h.scope.async { h.client.changeServerSecret(patch(), current, "https://elsewhere.example") }.get()
        assertEquals(ServerSecretOutcome.NoReply, outcome)
        assertTrue(h.framesUntilBarrier().none { it.type() == "set-server-settings" })
        assertEquals(0, pending())
    }

    @Test fun beforeAnySocketItSettlesAtOnce() {
        h.newClient()
        val outcome = h.scope.async { h.client.changeServerSecret(patch(), current, origin()) }.get()
        assertEquals(ServerSecretOutcome.NoReply, outcome)
        assertEquals(0, pending())
    }

    @Test fun aSocketClosingMidFlightDoesNotSettleEarlyTheWindowIsTheWebs() {
        val ws = connected()
        val sent = write()
        h.expectFrame("set-server-settings")
        ws.close(1011, "gone")
        Thread.sleep(300)
        assertFalse("the web waits its 20 s out", sent.isCompleted)
        h.scheduler.await { it == 20_000L }.fire()
        assertEquals(ServerSecretOutcome.NoReply, sent.get())
        awaitIdle()
    }

    @Test fun aCancelledCallerLeavesNoWaiterOrTimer() {
        connected()
        val sent = write()
        h.expectFrame("set-server-settings")
        sent.cancel()
        awaitIdle()
        assertTrue(h.scheduler.pending().none { it.delayMs == 20_000L })
    }

    // ---- the wire -------------------------------------------------------------------------------------

    @Test fun theResultDecodesWithItsOptionalFieldsAbsent() {
        val bare = ServerMessage.parse("""{"type":"server-settings-result","ok":true}""") as ServerMessage.ServerSettingsResult
        assertEquals(ServerMessage.ServerSettingsResult(null, true, null, null), bare)
        val full = ServerMessage.parse(result("r1", false, "env_forced", "The password is set by the environment (TETHER_PASSWORD); change it there and restart.")) as ServerMessage.ServerSettingsResult
        assertEquals("env_forced", full.reason)
        assertNull(ServerMessage.parse("""{"type":"server-settings-result"}""").let { (it as ServerMessage.ServerSettingsResult).reason })
        assertFalse((ServerMessage.parse("""{"type":"server-settings-result"}""") as ServerMessage.ServerSettingsResult).ok)
    }
}
