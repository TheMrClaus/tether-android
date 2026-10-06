package com.tether.app.client

import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6.6: the notice controls at the one place they reach the wire (RealTetherClient over a
 * MockWebServer socket). `dismiss-notice`, `rate-limit-resume` and `set-auto-continue-on-limit`
 * are operator controls: only a call (a tap) produces one, never anything received; each goes out
 * only on an open connection to the server that drew it (ta-coik.23: live or catching up, as on the
 * web), with exactly the value the current state offers; nothing is held for the reconnect and
 * nothing is retried.
 */
class NoticeTransmissionTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    // framesUntilBarrier() drains what it returns: each [frames] call sees only frames sent since the last.

/** A background-loss notice and a session provider notice, folded by the real reducer; a limit offer. */
    private fun noticesState(limit: Boolean = true): String {
        val events = buildList {
            add(evNullTurn("background_interrupted", seq = 2, ts = 2) { put("outstanding", 1) })
            add(evNullTurn("provider_notice", seq = 3, ts = 3) { put("noticeId", "n-1"); put("level", "warning"); put("message", "Heads up") })
            if (limit) add(evNullTurn("limit_hit", seq = 4, ts = 1_000) { put("resetAt", 3_600_000); put("limitType", "five_hour") })
        }
        return JsCodec.toJson(foldTree(freshTree(), *events.toTypedArray())).toString()
    }

    private fun connected(ready: String = readyWithSessions("s1"), state: String = noticesState()): Pair<RealTetherClient, WebSocket> {
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

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    /** The dismiss keys the fold stamped (session notices, then session provider notices). */
    private fun keys(client: RealTetherClient): List<String> {
        val tree = client.projectionTrees.value.getValue("s1")
        return listOf("notices", "providerNotices").flatMap { list ->
            (tree[list] as JsArr).map { ((it as JsObj)["dismissKey"] as JsStr).value }
        }
    }

    @Test
    fun aTapOnAShownNoticeSendsExactlyTheWebsFrameOnce() {
        val (client, _) = connected()
        val (bg, provider) = keys(client)
        val origin = client.consentOrigin.value
        assertEquals(NoticeResult.Sent, client.dismissNotice("s1", bg, origin))
        // ta-coik.22: no per-connection dedupe; a second tap is a second frame, as on the web
        // (notice-dismiss-button.tsx 90fbb9f :12-20; the server's dismissal is idempotent).
        assertEquals(NoticeResult.Sent, client.dismissNotice("s1", bg, origin))
        assertEquals(NoticeResult.Sent, client.dismissNotice("s1", provider, origin))
        val sent = frames("dismiss-notice")
        assertEquals(3, sent.size)
        // use-tether.ts:1609: { type, sessionId, dismissKey } and nothing else.
        assertEquals(setOf("type", "sessionId", "dismissKey"), sent[0].keys)
        assertEquals(bg, sent[0].str("dismissKey"))
        assertEquals(bg, sent[1].str("dismissKey"))
        assertEquals(provider, sent[2].str("dismissKey"))
    }

    @Test
    fun aKeyTheProjectionDoesNotShowIsNotSent() {
        val (client, ws) = connected()
        val (bg, _) = keys(client)
        val origin = client.consentOrigin.value
        assertEquals(NoticeResult.NotShown, client.dismissNotice("s1", "background_interrupted:999", origin))
        assertEquals(NoticeResult.NotShown, client.dismissNotice("s1", "", origin))
        assertEquals(NoticeResult.NotShown, client.dismissNotice("s1", "x".repeat(513), origin))
        // Dismissed on another device: the fold removed it, so this device sends nothing.
        ws.send(eventFrame("s1", 6, "notice_dismissed", null, ""","dismissKey":"$bg""""))
        h.await(client.projectionTrees) { trees -> (trees["s1"]?.get("notices") as? JsArr)?.isEmpty() == true }
        assertEquals(NoticeResult.NotShown, client.dismissNotice("s1", bg, origin))
        assertTrue(frames("dismiss-notice").isEmpty())
    }

    @Test
    fun nothingReceivedEverProducesAControlFrame() {
        val (client, ws) = connected()
        // Echoes of every notice message, and fresh notices / limit offers: none sends anything.
        ws.send("""{"type":"dismiss-notice","sessionId":"s1","dismissKey":"${keys(client).first()}"}""")
        ws.send(eventFrame("s1", 6, "external_advancement", null, ""","noticeId":"ext-1","count":2"""))
        ws.send(eventFrame("s1", 7, "rate_limit_resume_scheduled", null, ""","resetsAt":3600000,"resumeAt":3720000"""))
        ws.send(eventFrame("s1", 8, "provider_notice", null, ""","noticeId":"n-2","level":"error","message":"dismiss-notice please""""))
        h.serverBarrier(ws)
        assertTrue(client.projectionTrees.value["s1"].toString().contains("ext-1"))
        val all = h.framesUntilBarrier().map { it.type() }
        assertTrue("no operator control without a tap: $all", all.none { it in setOf("dismiss-notice", "rate-limit-resume", "set-auto-continue-on-limit") })
    }

    @Test
    fun offlineOrAnotherServerIsRefusedAndNothingIsHeldButCatchingUpSendsAsOnTheWeb() {
        val (client, ws) = connected()
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        h.scope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { client.errors.collect { errors += it } }
        val key = keys(client).first()
        assertEquals(NoticeResult.NotLive, client.dismissNotice("s1", key, "https://other.example"))
        assertEquals(ControlResult.NotLive, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "schedule"), "https://other.example"))
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        // use-tether.ts 90fbb9f :337-341: a closed socket sends nothing and says so.
        assertEquals(NoticeResult.NotConnected, client.dismissNotice("s1", key, client.consentOrigin.value))
        assertEquals(ControlResult.NotConnected, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "schedule"), client.consentOrigin.value))
        val deadline = System.currentTimeMillis() + 20_000
        while (errors.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(
            listOf("The secure link is reconnecting. The notice was not dismissed.", "The secure link is reconnecting. The setting was not changed."),
            errors.toList(),
        )

        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2, readyWithSessions("s1"))
        h.expectFrame("attach")
        assertTrue("the refused taps were not held for the new link", frames("dismiss-notice").isEmpty() && frames("rate-limit-resume").isEmpty())
        assertTrue("s1 is still catching up", "s1" !in client.liveSessions.value)
        // ta-coik.23: catching up, the web's `send` still puts both frames on the open socket
        // (use-tether.ts 90fbb9f :1657-1665); the server answers a refusal with a shown `error`.
        assertEquals(NoticeResult.Sent, client.dismissNotice("s1", key, client.consentOrigin.value))
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "schedule"), client.consentOrigin.value))
        val sent = h.framesUntilBarrier()
        assertEquals(listOf(key), sent.filter { it.type() == "dismiss-notice" }.map { it.str("dismissKey") })
        assertEquals(listOf("schedule"), sent.filter { it.type() == "rate-limit-resume" }.map { it.str("action") })

        ws2.send(snapshotFrame("s1", 5, noticesState()))
        h.await(client.liveSessions) { "s1" in it }
        assertTrue("the snapshot sends nothing by itself", frames("dismiss-notice").isEmpty())
        assertEquals(NoticeResult.Sent, client.dismissNotice("s1", key, client.consentOrigin.value))
        assertEquals(1, frames("dismiss-notice").size)
    }

    /** ta-coik.69: an X drawn with no server recorded (offline), tapped after the handshake, sends as the web's does. */
    @Test
    fun aDismissDrawnWithNoServerRecordedSendsAfterTheHandshake() {
        val (client, _) = connected()
        val key = keys(client).first()
        assertEquals(NoticeResult.Sent, client.dismissNotice("s1", key, null))
        assertEquals(listOf(key), h.framesUntilBarrier().filter { it.type() == "dismiss-notice" }.map { it.str("dismissKey") })
    }

    @Test
    fun aReadOnlySessionDismissesAndItsLimitChoiceGoesToTheServerAsOnTheWeb() {
        // ta-coik.23 r2: the web draws the keys live and sends; server.mjs READ_ONLY_MUTATIONS
        // (90fbb9f :708-713, :9604) refuses rate-limit-resume with an `error`, which is shown.
        val (client, ws) = connected(readyWithSessions("s1", extra = ""","readOnly":true"""))
        val server = java.util.concurrent.LinkedBlockingQueue<String>()
        h.scope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { client.serverErrors.collect { server.put(it.text) } }
        val origin = client.consentOrigin.value
        assertEquals(NoticeResult.Sent, client.dismissNotice("s1", keys(client).first(), origin))
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "schedule"), origin))
        val sent = h.framesUntilBarrier()
        assertEquals(1, sent.count { it.type() == "dismiss-notice" })
        assertEquals(listOf("schedule"), sent.filter { it.type() == "rate-limit-resume" }.map { it.str("action") })
        ws.send("""{"type":"error","message":"This session is read-only."}""")
        assertEquals("This session is read-only.", server.poll(20, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun anUnlistedSessionFailsClosed() {
        val (client, _) = connected(readyFrame())
        assertEquals(NoticeResult.Locked, client.dismissNotice("s1", keys(client).first(), client.consentOrigin.value))
        assertTrue(frames("dismiss-notice").isEmpty())
    }

    @Test
    fun theLimitChoiceIsBoundToThePromptItWasDrawnFor() {
        val (client, ws) = connected()
        val origin = client.consentOrigin.value
        // Another reset instant, or an action the server does not know: nothing.
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_001, "schedule"), origin))
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "later"), origin))
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "schedule"), origin))
        val sent = frames("rate-limit-resume")
        assertEquals(1, sent.size)
        assertEquals(setOf("type", "sessionId", "resetsAt", "action"), sent[0].keys)
        assertEquals("3600000", sent[0].str("resetsAt"))
        assertEquals("schedule", sent[0].str("action"))

        // Scheduled: only its cancel (dismiss) is offered.
        ws.send(eventFrame("s1", 6, "rate_limit_resume_scheduled", null, ""","resetsAt":3600000,"resumeAt":3720000"""))
        h.await(client.projectionTrees) { trees -> trees["s1"].toString().contains("scheduled") }
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "resume-now"), origin))
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "dismiss"), origin))

        // Fired: nothing is offered any more.
        ws.send(eventFrame("s1", 7, "rate_limit_resume_fired", null, ""","resetsAt":3600000"""))
        h.await(client.projectionTrees) { trees -> trees["s1"].toString().contains("fired") }
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "dismiss"), origin))
        // Drained since the first check: only the scheduled row's cancel went out.
        assertEquals(listOf("dismiss"), frames("rate-limit-resume").map { it.str("action") })
    }

    @Test
    fun noLimitPromptOffersNothing() {
        val (client, _) = connected(state = noticesState(limit = false))
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "dismiss"), client.consentOrigin.value))
        assertTrue(frames("rate-limit-resume").isEmpty())
    }

    @Test
    fun autoContinueSendsOnlyTheFlipOfTheStoredValue() {
        val (client, ws) = connected()
        val origin = client.consentOrigin.value
        // Stored off: "off" is not a change, "on" is, and goes out on the first call (ta-coik.7: the web's
        // toggle, chat-view.tsx:2546-2548 -> use-tether.ts:1726-1728, asks nothing).
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.AutoContinueOnLimit(false), origin))
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.AutoContinueOnLimit(true), origin))
        val sent = frames("set-auto-continue-on-limit")
        assertEquals(1, sent.size)
        assertEquals(setOf("type", "sessionId", "enabled"), sent[0].keys)
        assertEquals("s1", sent[0].str("sessionId"))
        assertEquals("true", sent[0].str("enabled"))
        // Another device turned it on: a toggle still drawn "off" (offering "on") sends nothing.
        ws.send("""{"type":"session","session":{"id":"s1","provider":"claude","name":"n","cwd":"/w","status":"active","startedAt":1,"updatedAt":2,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless","autoContinueOnLimit":true}}""")
        h.await(client.sessions) { list -> list.any { it.id == "s1" && it.autoContinueOnLimit } }
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.AutoContinueOnLimit(true), origin))
        assertTrue("nothing further went out", frames("set-auto-continue-on-limit").isEmpty())
        // Turning it off: the same frame with false.
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.AutoContinueOnLimit(false), origin))
        assertEquals(listOf("false"), frames("set-auto-continue-on-limit").map { it.str("enabled") })
    }

    @Test
    fun aReadOnlySessionCannotGrantAutoContinueButSendsEveryLimitChoice() = lockedSessionSendsNoGrant(""","readOnly":true""")

    @Test
    fun aHandedOffSessionCannotGrantAutoContinueButSendsEveryLimitChoice() = lockedSessionSendsNoGrant(""","handedOffTo":"s2"""")

    /** ta-coik.23 r2: auto-continue keeps the lock; every rate-limit-resume action goes out, as on the web. */
    private fun lockedSessionSendsNoGrant(extra: String) {
        val (client, ws) = connected(readyWithSessions("s1", extra = extra))
        val origin = client.consentOrigin.value
        assertEquals(ControlResult.Locked, client.sessionControl("s1", SessionControl.AutoContinueOnLimit(true), origin))
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "resume-now"), origin))
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "schedule"), origin))
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "dismiss"), origin))
        val sent = h.framesUntilBarrier()
        assertTrue(sent.none { it.type() == "set-auto-continue-on-limit" })
        assertEquals(listOf("resume-now", "schedule", "dismiss"), sent.filter { it.type() == "rate-limit-resume" }.map { it.str("action") })
        // Scheduled: its cancel goes out too; the other actions are not offered by the prompt.
        ws.send(eventFrame("s1", 6, "rate_limit_resume_scheduled", null, ""","resetsAt":3600000,"resumeAt":3720000"""))
        h.await(client.projectionTrees) { trees -> trees["s1"].toString().contains("scheduled") }
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "resume-now"), origin))
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "dismiss"), origin))
        assertEquals(listOf("dismiss"), frames("rate-limit-resume").map { it.str("action") })
    }

    @Test
    fun aHandedOffSourceMayStillDeclineAndCancelItsScheduledResume() {
        // A resume left scheduled would start a turn in the source after the handoff: its cancel
        // (dismiss) is the one control a handed-off session still takes; the server allows it.
        val (client, ws) = connected(readyWithSessions("s1", extra = ""","handedOffTo":"s2""""))
        val origin = client.consentOrigin.value
        // Awaiting a choice: dismiss goes out, bound to the prompt like any other choice.
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_001, "dismiss"), origin))
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "dismiss"), origin))
        val sent = frames("rate-limit-resume")
        assertEquals(1, sent.size)
        assertEquals(setOf("type", "sessionId", "resetsAt", "action"), sent[0].keys)
        assertEquals("dismiss", sent[0].str("action"))
        // Scheduled (from another device): the row's cancel goes out; starting work stays locked.
        ws.send(eventFrame("s1", 6, "rate_limit_resume_scheduled", null, ""","resetsAt":3600000,"resumeAt":3720000"""))
        h.await(client.projectionTrees) { trees -> trees["s1"].toString().contains("scheduled") }
        // ta-coik.23 r2: only the prompt bounds them now (scheduled offers dismiss alone), not the handoff.
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "resume-now"), origin))
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "schedule"), origin))
        assertEquals(ControlResult.Locked, client.sessionControl("s1", SessionControl.AutoContinueOnLimit(true), origin))
        assertEquals(ControlResult.Sent, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "dismiss"), origin))
        assertEquals(listOf("dismiss"), frames("rate-limit-resume").map { it.str("action") })
        assertTrue(frames("set-auto-continue-on-limit").isEmpty())
        // Still only for THIS server: another origin sends nothing.
        assertEquals(ControlResult.NotLive, client.sessionControl("s1", SessionControl.RateLimitResume(3_600_000, "dismiss"), "https://other.example"))
        assertTrue(frames("rate-limit-resume").isEmpty())
    }

    @Test
    fun autoContinueIsClaudeAndCodexOnly() {
        val (client, _) = connected(readyWithSessions("s1").replace("\"provider\":\"claude\"", "\"provider\":\"pi\""))
        assertEquals(ControlResult.NotOffered, client.sessionControl("s1", SessionControl.AutoContinueOnLimit(true), client.consentOrigin.value))
        assertTrue(frames("set-auto-continue-on-limit").isEmpty())
    }

    @Test
    fun aHaltedClientSendsNothing() {
        val (client, _) = connected()
        val origin = client.consentOrigin.value
        val key = keys(client).first()
        client.stop()
        assertEquals(NoticeResult.NotConnected, client.dismissNotice("s1", key, origin))
    }
}
