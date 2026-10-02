package com.tether.app.client

import com.tether.app.client.TwoOriginFixture.Companion.createdFrame
import com.tether.app.ui.prefs.InMemoryDraftStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-2ew (ta-8cv r2 security review, R1 and R2), through the real client and two servers: a create
 * or resume answer is its server's. After a sign-in to another server, nothing from the first one
 * (a reply still buffered for a collector, or an answer still on record in the window before the
 * switch clears it) completes a create, opens its session or attaches its id on the second server;
 * a sign-out forgets the recorded answers.
 */
class CreateReplyOriginTest {

    private val fx = TwoOriginFixture()
    private val engineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)

    /** Every session the engine reported its own create made, and whether the client attached it. */
    private val opened = CopyOnWriteArrayList<String>()
    private val attached = CopyOnWriteArrayList<Boolean>()
    private val saved = CopyOnWriteArrayList<String>()

    /** The `created` replies as a slow collector holds them (not yet delivered to the engine). */
    private val buffered = CopyOnWriteArrayList<CreatedReply>()

    @After
    fun tearDown() {
        fx.client.raceHook = null
        engineScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        fx.close()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    /** The draft engine as the view model wires it: its own create opens only through attachIfConfigured. */
    private fun engine(): DraftComposerModel = DraftComposerModel(
        fx.client,
        InMemoryDraftStore(),
        engineScope,
        { "/w" },
        saveSessionDraft = { origin, sessionId, text -> saved += "$origin|$sessionId|$text" },
        newRequestId = { "r-a" },
        onSessionCreated = { id, origin ->
            opened += id
            attached += fx.client.attachIfConfigured(id, origin)
        },
    )

    /** Connected to A; a create with a first message in flight there; replies collected, not delivered. */
    private fun createInFlightOnA(): Pair<okhttp3.WebSocket, DraftComposerModel> {
        val aws = fx.connectedToA()
        engineScope.launch(start = CoroutineStart.UNDISPATCHED) { fx.client.createdReplies.collect { buffered += it } }
        val engine = engine()
        engine.refresh()
        assertTrue(engine.selectProvider("claude"))
        engine.setText("first words for A")
        assertEquals(DraftSubmitResult.Sent, engine.submit(fx.a.origin()))
        assertEquals("r-a", fx.framesUntilBarrier(fx.a).single { it.type() == "create" }.str("requestId"))
        assertTrue(engine.state.value.creating)
        return aws to engine
    }

    /** B came up and never heard of A's session or its first message (no attach, no send). */
    private fun assertBNeverSawA() {
        val bws = fx.b.nextSocket()
        fx.handshake(fx.b, bws)
        val sent = fx.framesUntilBarrier(fx.b)
        assertTrue("B received $sent", sent.isEmpty())
        for (text in fx.b.allFrames) {
            assertFalse("A's session reached B: $text", text.contains("s-on-a"))
            assertFalse("A's prompt reached B: $text", text.contains("first words"))
        }
    }

    @Test
    fun aReplyFromTheOldServerDeliveredAfterTheSwitchNeverCompletesTheCreate() {
        val (aws, engine) = createInFlightOnA()
        aws.send(createdFrame("s-on-a", "r-a"))
        fx.awaitCondition("A's reply was carried") { buffered.any { it.requestId == "r-a" } }
        val reply = buffered.single { it.requestId == "r-a" }
        assertEquals("stamped with A when the frame was handled", fx.a.origin(), reply.origin)

        fx.loginTo(fx.b)
        // The collector was behind: A's reply reaches the engine only now, with B configured.
        assertNull(engine.onCreated(reply))
        assertFalse(engine.state.value.creating)
        assertEquals(DRAFT_SERVER_CHANGED_COPY, engine.state.value.error)
        assertTrue("A's session was opened on B: $opened", opened.isEmpty())
        assertTrue(saved.isEmpty())
        // Settled: a second delivery is nothing either.
        assertNull(engine.onCreated(reply))
        assertTrue(opened.isEmpty())
        assertBNeverSawA()
    }

    @Test
    fun anAnswerRecordedOnTheOldServerIsNeverTakenBeforeTheSwitchHasClearedIt() {
        val (aws, engine) = createInFlightOnA()
        aws.send(createdFrame("s-on-a", "r-a"))
        fx.awaitCondition("A's answer was recorded") { fx.client.createReply("r-a") != null }
        assertEquals(fx.a.origin(), fx.client.createReply("r-a")!!.origin)

        val hold = fx.Hold(RacePoint.ServerMoved)
        val result = AtomicReference<LoginResult>()
        val login = Thread { result.set(runBlocking { fx.client.login(fx.b.url(), "parity-fake-password") }) }
        login.start()
        try {
            hold.awaitReached()
            // The URL is B's; the per-server views (A's recorded answer with them) are not cleared yet.
            assertFalse(fx.client.isConfiguredOrigin(fx.a.origin()))
            assertNull("A's answer was returned with B configured", fx.client.createReply("r-a"))
            assertFalse("A's session id was attached with B configured", fx.client.attachIfConfigured("s-on-a", fx.a.origin()))
            // The link collector runs in the window: the create is over, not completed from the record.
            engine.onLink(fx.client.connection.value, fx.client.linkEpoch.value)
            assertFalse(engine.state.value.creating)
            assertEquals(DRAFT_SERVER_CHANGED_COPY, engine.state.value.error)
            assertTrue("A's session was opened on B: $opened", opened.isEmpty())
        } finally {
            hold.release()
        }
        login.join(15_000)
        assertEquals(LoginResult.Success, result.get())
        assertNull(fx.client.createReply("r-a"))
        assertBNeverSawA()
    }

    /** Positive control: on the server it came from, the same flow completes, opens and attaches. */
    @Test
    fun onTheSameServerTheCreateCompletesAndItsSessionIsAttached() {
        val (aws, engine) = createInFlightOnA()
        aws.send(createdFrame("s-on-a", "r-a"))
        fx.awaitCondition("A's reply was carried") { buffered.any { it.requestId == "r-a" } }
        assertEquals("s-on-a", engine.onCreated(buffered.single { it.requestId == "r-a" }))
        assertEquals(listOf("s-on-a"), opened)
        assertEquals(listOf(true), attached)
        val frames = fx.framesUntilBarrier(fx.a)
        assertTrue("$frames", frames.any { it.type() == "attach" && it.str("sessionId") == "s-on-a" })
        assertEquals("first words for A", frames.single { it.type() == "send" }.str("text"))
    }

    /** Signed in to A again after a sign-out: a fresh socket of A, handshaken. */
    private fun signBackInToA(): okhttp3.WebSocket {
        fx.loginTo(fx.a)
        val ws = fx.a.nextSocket()
        fx.handshake(fx.a, ws)
        assertTrue(fx.client.isConfiguredOrigin(fx.a.origin()))
        return ws
    }

    /**
     * R2: a sign-out empties the record. A sign-in to the SAME server afterwards is no switch (the URL
     * stayed), so nothing else would: the old answers must not come back with it.
     */
    @Test
    fun aSignOutForgetsTheRecordedCreateAnswersEvenAfterSigningBackIn() {
        val aws = fx.connectedToA()
        aws.send(createdFrame("s-on-a", "r-1"))
        aws.send("""{"type":"error","message":"no","requestId":"r-2"}""")
        fx.awaitCondition("both answers were recorded") { fx.client.createReply("r-1") != null && fx.client.createReply("r-2") != null }
        assertEquals(fx.a.origin(), fx.client.createReply("r-2")!!.origin)
        runBlocking { fx.client.logout() }
        // r2 (P4-1): the URL stays (the login screen's prefill), but nobody is signed in.
        assertEquals(OriginStanding.SignedOut, fx.client.originStanding(fx.a.origin()))
        assertNull(fx.client.createReply("r-1"))
        signBackInToA()
        assertNull("an answer from before the sign-out came back", fx.client.createReply("r-1"))
        assertNull(fx.client.createReply("r-2"))
    }

    /** r2 (security P4-3): a sign-out empties the latest replies; their seq keeps rising after it. */
    @Test
    fun aSignOutEmptiesTheLatestRepliesAndTheirSeqKeepsRising() {
        val aws = fx.connectedToA()
        aws.send(createdFrame("s-on-a", "r-1"))
        aws.send("""{"type":"error","message":"no","requestId":"r-2"}""")
        val created = fx.await(fx.client.createdSessions) { it?.requestId == "r-1" }!!
        val error = fx.await(fx.client.createErrors) { it?.requestId == "r-2" }!!
        runBlocking { fx.client.logout() }
        assertNull("the last created outlived the sign-out", fx.client.createdSessions.value)
        assertNull("the last error outlived the sign-out", fx.client.createErrors.value)
        val ws = signBackInToA()
        ws.send(createdFrame("s-later", "r-3"))
        ws.send("""{"type":"error","message":"later","requestId":"r-4"}""")
        assertTrue(fx.await(fx.client.createdSessions) { it?.requestId == "r-3" }!!.seq > created.seq)
        assertTrue(fx.await(fx.client.createErrors) { it?.requestId == "r-4" }!!.seq > error.seq)
    }

    /** Delegates to [inner]; clear() always fails, as a store that can write neither file does. */
    private class FailingClear(private val inner: SettingsStore) : SettingsStore by inner {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun clear() {
            attempts.incrementAndGet()
            throw java.io.IOException("disk full")
        }
    }

    /**
     * r2 (security P4-2): a stop() whose store clear fails leaves the URL on disk, so the next sign-in
     * to the same server is no switch; the answers recorded before the stop must not come back.
     */
    @Test
    fun aStopWhoseStoreClearFailsStillForgetsTheCreateAnswers() {
        var failing: FailingClear? = null
        TwoOriginFixture { FailingClear(it).also { f -> failing = f } }.use { own ->
            own.client.start()
            val aws = own.a.nextSocket()
            own.handshake(own.a, aws)
            aws.send(createdFrame("s-on-a", "r-1"))
            own.awaitCondition("the answer was recorded") { own.client.createReply("r-1") != null }
            own.client.stop()
            own.awaitCondition("the store clear was tried, and failed") { failing!!.attempts.get() >= 2 }
            own.loginTo(own.a)
            own.handshake(own.a, own.a.nextSocket())
            assertTrue(own.client.isConfiguredOrigin(own.a.origin()))
            assertNull("an answer from before the stop came back", own.client.createReply("r-1"))
        }
    }

    /** Verifier P4 (r2): a create pending at a sign-out says so, not that the server changed. */
    @Test
    fun aCreatePendingAtALogoutIsSettledWithTheSignOutWording() {
        val (_, engine) = createInFlightOnA()
        runBlocking { fx.client.logout() }
        engine.onLink(fx.client.connection.value, fx.client.linkEpoch.value)
        assertFalse(engine.state.value.creating)
        assertEquals(DRAFT_SIGNED_OUT_COPY, engine.state.value.error)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun aCreatePendingAtAStopIsSettledWithTheSignOutWording() {
        val (_, engine) = createInFlightOnA()
        fx.client.stop()
        engine.onLink(fx.client.connection.value, fx.client.linkEpoch.value)
        assertFalse(engine.state.value.creating)
        assertEquals(DRAFT_SIGNED_OUT_COPY, engine.state.value.error)
        assertTrue(opened.isEmpty())
    }
}
