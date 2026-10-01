package com.tether.app.client

import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-895: the one place a New session row reaches the wire (RealTetherClient over a MockWebServer
 * socket). `create` carries the row's `profileId` (use-draft-composer.ts:339) only when the row is
 * one of the catalog THIS socket delivered, exactly as drawn; a catalog of an earlier connection is
 * never trusted, a row the refreshed catalog dropped or changed is refused (never created on the
 * default profile instead), and nothing received ever produces a create.
 */
class NewSessionTransmissionTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    private val providers =
        """{"id":"claude","label":"Claude","glyph":"C","available":true},""" +
            """{"id":"codex","label":"Codex","glyph":"X","available":true},""" +
            """{"id":"acp","label":"ACP","glyph":"A","available":true}"""

    private fun ready(): String =
        """{"type":"ready","protocolVersion":${com.tether.app.protocol.PROTOCOL_VERSION},"nativeProtocolFloor":129,"sessions":[],""" +
            """"providers":[$providers],"workspaceRoot":null}"""

    private fun catalogFrame(vararg rows: String) = """{"type":"providers-snapshot","entries":[${rows.joinToString(",")}]}"""

    private val work = """{"key":"work","provider":"claude","status":"ready","profileId":"work","extends":"claude","label":"Claude (work)","models":[]}"""
    private val personal = """{"key":"personal","provider":"claude","status":"ready","profileId":"personal","extends":"claude","label":"Claude (personal)","models":[]}"""
    private val claude = """{"key":"claude","provider":"claude","status":"ready","label":"Claude","models":[]}"""
    private val codex = """{"key":"codex","provider":"codex","status":"ready","label":"Codex","models":[]}"""

    private val workChoice = NewSessionChoice("work", "claude", "work")
    private val claudeChoice = NewSessionChoice("claude", "claude", null)

    private fun connected(): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, ready())
        return client to ws
    }

    private fun withCatalog(vararg rows: String = arrayOf(work, personal, claude, codex)): Pair<RealTetherClient, WebSocket> {
        val (client, ws) = connected()
        assertFalse("no catalog of this socket yet", client.providerCatalogLive.value)
        assertTrue(client.requestProviderCatalog())
        assertEquals(listOf("providers-snapshot"), h.framesUntilBarrier().map { it.type() })
        ws.send(catalogFrame(*rows))
        h.await(client.providerCatalogLive) { it }
        return client to ws
    }

    private fun creates(): List<JsonObject> = h.framesUntilBarrier().filter { it.type() == "create" }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    /** ta-8cv: the web's keys for a cold draft (use-draft-composer.ts:313-347); Claude's sandbox explicit. */
    private val coldKeys = setOf("type", "provider", "cwd", "requestId", "permissionMode", "sandboxPolicy", "useWorktree")

    @Test
    fun aProfileRowCreatesOnThatProfile() {
        val (client, _) = withCatalog()
        assertEquals(NewSessionResult.Sent, client.createNewSession(workChoice, "/w", client.consentOrigin.value))
        val sent = creates().single()
        assertEquals(coldKeys + "profileId", sent.keys)
        assertEquals("claude", sent.str("provider"))
        assertEquals("work", sent.str("profileId"))
        assertEquals("/w", sent.str("cwd"))
        assertEquals("req-work", sent.str("requestId"))
        assertEquals("bypassPermissions", sent.str("permissionMode"))
        assertEquals("workspace-write", sent.str("sandboxPolicy"))
    }

    @Test
    fun aDefaultRowCreatesWithNoProfile() {
        val (client, _) = withCatalog()
        assertEquals(NewSessionResult.Sent, client.createNewSession(claudeChoice, "/w", client.consentOrigin.value))
        assertEquals(coldKeys, creates().single().keys)
    }

    /** ta-8cv: a draft composed on one socket is never created on the next one. */
    @Test
    fun aDraftComposedOnAnEarlierSocketIsRefusedOnTheNext() {
        val (client, ws) = withCatalog()
        val composedOn = client.linkEpoch.value
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        h.handshake(h.nextSocket(), ready())
        assertTrue(client.linkEpoch.value > composedOn)
        val stale = NewSessionRequest(claudeChoice, coldDraftForm("/w"), com.tether.app.protocol.helpers.DraftForm.INITIAL_USER_MODIFIED, "r-old", composedOn)
        assertEquals(NewSessionResult.NotConnected, client.createNewSession(stale, client.consentOrigin.value))
        assertTrue(creates().isEmpty())
        // Composed on the live one: sent (positive control).
        assertEquals(NewSessionResult.Sent, client.createNewSession(stale.copy(linkEpoch = client.linkEpoch.value), client.consentOrigin.value))
        assertEquals(1, creates().size)
    }

    /** ta-8cv: every `error` frame is published with its echo, in order, cleaned. */
    @Test
    fun errorFramesArePublishedWithTheirRequestId() {
        val (client, ws) = withCatalog()
        assertEquals(NewSessionResult.Sent, client.createNewSession(claudeChoice, "/w", client.consentOrigin.value, requestId = "r-1"))
        ws.send("""{"type":"error","message":"unrelated"}""")
        val first = h.await(client.createErrors) { it != null }!!
        assertEquals(null, first.requestId)
        ws.send("""{"type":"error","message":"Skipping tool approvals needs‮ a browser sign-in, not a paired device.","requestId":"r-1"}""")
        val second = h.await(client.createErrors) { it?.requestId == "r-1" }!!
        assertTrue(second.seq > first.seq)
        assertFalse(second.message.contains('‮'))
        assertTrue(second.message.startsWith("Skipping tool approvals needs"))
    }

    @Test
    fun aRowTheRefreshedCatalogDroppedIsRefusedAndNothingIsCreatedOnTheDefault() {
        val (client, ws) = withCatalog()
        // The account was removed: the server pushes the catalog without it.
        ws.send(catalogFrame(personal, claude, codex))
        h.await(client.providerCatalog) { list -> list.none { it.key == "work" } }
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(workChoice, "/w", client.consentOrigin.value))
        assertTrue(creates().isEmpty())
    }

    @Test
    fun aKeyThatNowNamesAnotherProfileIsRefused() {
        val (client, ws) = withCatalog()
        ws.send(catalogFrame("""{"key":"work","provider":"claude","status":"ready","profileId":"work-2","models":[]}""", claude))
        h.await(client.providerCatalog) { list -> list.any { it.profileId == "work-2" } }
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(workChoice, "/w", client.consentOrigin.value))
        assertTrue(creates().isEmpty())
    }

    @Test
    fun anEarlierConnectionsCatalogIsNeverTrustedForAProfile() {
        val (client, ws) = withCatalog()
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        assertFalse(client.providerCatalogLive.value)
        assertEquals(NewSessionResult.NotConnected, client.createNewSession(workChoice, "/w", client.consentOrigin.value))
        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2, ready())
        // The old catalog is still published (the @ Agents keep it) but it is not this socket's.
        assertTrue(client.providerCatalog.value.any { it.key == "work" })
        assertFalse(client.providerCatalogLive.value)
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(workChoice, "/w", client.consentOrigin.value))
        // A base provider's default row still starts a session meanwhile, with no profile.
        assertEquals(NewSessionResult.Sent, client.createNewSession(claudeChoice, "/w", client.consentOrigin.value))
        val meanwhile = creates()
        assertEquals(1, meanwhile.size)
        assertEquals(coldKeys, meanwhile[0].keys)
        // This socket's own catalog lands: the profile row creates again.
        ws2.send(catalogFrame(work, claude))
        h.await(client.providerCatalogLive) { it }
        assertEquals(NewSessionResult.Sent, client.createNewSession(workChoice, "/w", client.consentOrigin.value))
        assertEquals("work", creates().single().str("profileId"))
    }

    @Test
    fun aRowDrawnForAnotherServerOrForNoneIsNotLive() {
        val (client, _) = withCatalog()
        assertEquals(NewSessionResult.NotLive, client.createNewSession(workChoice, "/w", "https://other.example"))
        assertEquals(NewSessionResult.NotLive, client.createNewSession(workChoice, "/w", null))
        assertTrue(creates().isEmpty())
    }

    @Test
    fun anUnavailableOrLoadingRowIsRefusedAndAnAcpDefaultNeverExists() {
        val (client, _) = withCatalog(
            """{"key":"work","provider":"claude","status":"loading","profileId":"work","models":[]}""",
            """{"key":"codex","provider":"codex","status":"unavailable","models":[]}""",
            claude,
        )
        val origin = client.consentOrigin.value
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(workChoice, "/w", origin))
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(NewSessionChoice("codex", "codex", null), "/w", origin))
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(NewSessionChoice("acp", "acp", null), "/w", origin))
        assertTrue(creates().isEmpty())
    }

    /** r2 (F3): an empty snapshot (or one where nothing decodes) is live and offers nothing. */
    @Test
    fun anEmptyLiveCatalogCreatesNothingNotEvenTheDefault() {
        val (client, ws) = withCatalog()
        ws.send(catalogFrame())
        h.await(client.providerCatalog) { it.isEmpty() }
        assertTrue(client.providerCatalogLive.value)
        val origin = client.consentOrigin.value
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(claudeChoice, "/w", origin))
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(workChoice, "/w", origin))
        ws.send(catalogFrame("""{"key":"work"}""", """{"provider":"claude","status":"ready"}"""))
        h.serverBarrier(ws)
        assertTrue(client.providerCatalog.value.isEmpty())
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(claudeChoice, "/w", origin))
        assertTrue(creates().isEmpty())
    }

    @Test
    fun nothingReceivedEverProducesACreate() {
        val (client, ws) = withCatalog()
        ws.send(catalogFrame(work, claude))
        ws.send("""{"type":"create","provider":"claude","profileId":"work","cwd":"/"}""")
        h.serverBarrier(ws)
        assertTrue(client.providerCatalogLive.value)
        assertTrue(creates().isEmpty())
    }

    private fun createdFrame(id: String, requestId: String?): String =
        """{"type":"created","session":{"id":"$id","provider":"claude","name":"n","cwd":"/w","status":"ready",""" +
            """"startedAt":1,"updatedAt":1,"endedAt":null,"exitCode":null,"pinned":false,"runtimeArchived":false,"mode":"headless"}""" +
            (if (requestId != null) ""","requestId":"$requestId"""" else "") + "}"

    /**
     * ta-8cv, end to end through the real client and its socket: the draft composer's create goes out
     * with the web's frame; a resume's `created` and another create's do not complete it; the one
     * echoing its requestId does; the first message then goes out once, through the durable send,
     * under a fresh idempotency key.
     */
    @Test
    fun theDraftComposersCreateAndFirstMessageEndToEnd() {
        val (client, ws) = withCatalog()
        val origin = client.consentOrigin.value
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            val engine = DraftComposerModel(client, com.tether.app.ui.prefs.InMemoryDraftStore(), scope, { "/w" }, newRequestId = { "e2e-1" })
            engine.refresh()
            assertTrue(engine.selectProvider("work"))
            engine.setText("hello from the draft")
            assertEquals(DraftSubmitResult.Sent, engine.submit(origin))
            val create = creates().single()
            assertEquals(coldKeys + "profileId", create.keys)
            assertEquals("e2e-1", create.str("requestId"))
            assertEquals("workspace-write", create.str("sandboxPolicy"))
            assertEquals("bypassPermissions", create.str("permissionMode"))

            ws.send(createdFrame("resumed", null))
            val resumed = h.await(client.createdSessions) { it?.session?.id == "resumed" }!!
            assertEquals(null, engine.onCreated(resumed))
            ws.send(createdFrame("foreign", "someone-else"))
            val foreign = h.await(client.createdSessions) { it?.session?.id == "foreign" }!!
            assertEquals(null, engine.onCreated(foreign))
            assertTrue(engine.state.value.creating)

            ws.send(createdFrame("s-new", "e2e-1"))
            val mine = h.await(client.createdSessions) { it?.requestId == "e2e-1" }!!
            assertEquals("s-new", engine.onCreated(mine))
            assertFalse(engine.state.value.creating)
            // A first transmission goes out at once (pending-input.mjs: tries 0), as the web's send
            // right after `created` does; the server orders it after the create.
            val send = h.framesUntilBarrier().single { it.type() == "send" }
            assertEquals("s-new", send.str("sessionId"))
            assertEquals("hello from the draft", send.str("text"))
            assertTrue(send.str("idempotencyKey")!!.isNotEmpty())
            assertEquals("", engine.state.value.text)
            // Once only.
            assertEquals(null, engine.onCreated(mine))
            assertTrue(h.framesUntilBarrier().none { it.type() == "send" || it.type() == "create" })
        } finally {
            scope.cancel()
        }
    }

    /** ta-8cv: the server's refusal of THIS create (its requestId echoed) unlocks the draft with its words. */
    @Test
    fun aRefusalEchoingTheRequestIdUnlocksTheDraftEndToEnd() {
        val (client, ws) = withCatalog()
        val origin = client.consentOrigin.value
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            val engine = DraftComposerModel(client, com.tether.app.ui.prefs.InMemoryDraftStore(), scope, { "/w" }, newRequestId = { "e2e-2" })
            assertEquals(DraftSubmitResult.Sent, engine.submitChoice(claudeChoice, origin))
            ws.send("""{"type":"error","message":"unrelated"}""")
            assertEquals(false, engine.onCreateError(h.await(client.createErrors) { it != null }!!))
            ws.send("""{"type":"error","message":"Skipping tool approvals needs a browser sign-in, not a paired device.","requestId":"e2e-2"}""")
            assertEquals(true, engine.onCreateError(h.await(client.createErrors) { it?.requestId == "e2e-2" }!!))
            assertEquals("Skipping tool approvals needs a browser sign-in, not a paired device.", engine.state.value.error)
            assertFalse(engine.state.value.creating)
            assertEquals("never resent", 1, creates().size)
        } finally {
            scope.cancel()
        }
    }

    /** ta-8cv r2 (security F2): two replies back to back are both carried, in order, stamped and recorded. */
    @Test
    fun twoRepliesBackToBackAreBothCarriedAndRecorded() {
        val (client, ws) = withCatalog()
        val got = java.util.concurrent.CopyOnWriteArrayList<CreatedReply>()
        val errors = java.util.concurrent.CopyOnWriteArrayList<CreateErrorReply>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { client.createdReplies.collect { got += it } }
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { client.createErrorReplies.collect { errors += it } }
            ws.send(createdFrame("new", "r-1"))
            ws.send(createdFrame("resumed", null))
            ws.send("""{"type":"error","message":"no","requestId":"r-2"}""")
            ws.send("""{"type":"error","message":"other"}""")
            // r3: wait on what is asserted, the collected streams themselves (the latest-value flow is
            // set first, so it can show the last reply before a collector has appended it).
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            while ((got.size < 2 || errors.size < 2) && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals("both replies were carried: $got", 2, got.size)
            assertEquals("both errors were carried: $errors", 2, errors.size)
            assertEquals("the latest-value flow keeps only the last", "resumed", client.createdSessions.value?.session?.id)
            assertEquals(listOf("new", "resumed"), got.map { it.session.id })
            assertEquals(listOf("r-2", null), errors.map { it.requestId })
            assertTrue(got.all { it.linkEpoch == client.linkEpoch.value })
            assertEquals("new", (client.createReply("r-1") as CreateReplyRecord.Created).reply.session.id)
            assertEquals("no", (client.createReply("r-2") as CreateReplyRecord.Failed).reply.message)
            assertEquals(null, client.createReply("r-3"))
        } finally {
            scope.cancel()
        }
    }

    /**
     * ta-8cv r2 (security F2), end to end: the server answers the create and the socket closes; the
     * link is seen gone BEFORE the `created` is delivered. The answer the client recorded stands:
     * the session is opened, never reported as not created, and its first message (which cannot go
     * on another socket) is kept as that session's draft.
     */
    @Test
    fun aDropSeenBeforeTheCreatedStillCompletesTheCreateEndToEnd() {
        val (client, ws) = withCatalog()
        val origin = client.consentOrigin.value
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val opened = java.util.concurrent.CopyOnWriteArrayList<String>()
        val saved = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            val engine = DraftComposerModel(
                client,
                com.tether.app.ui.prefs.InMemoryDraftStore(),
                scope,
                { "/w" },
                saveSessionDraft = { _, sessionId, text -> saved += "$sessionId:$text" },
                newRequestId = { "e2e-3" },
                onSessionCreated = { opened += it },
            )
            engine.refresh()
            engine.selectProvider("claude")
            engine.setText("first words")
            assertEquals(DraftSubmitResult.Sent, engine.submit(origin))
            val epoch = client.linkEpoch.value
            ws.send(createdFrame("s-new", "e2e-3"))
            h.await(client.createdSessions) { it?.requestId == "e2e-3" }
            h.enqueueConnect()
            ws.close(1001, null)
            h.await(client.connection) { it == ConnectionState.Disconnected }
            engine.onLink(client.connection.value, epoch)
            assertFalse(engine.state.value.creating)
            assertEquals("", engine.state.value.error)
            assertEquals(listOf("s-new"), opened)
            assertEquals(listOf("s-new:first words"), saved)
            assertTrue("nothing was sent", h.received.toList().none { it.contains("\"type\":\"send\"") })
        } finally {
            scope.cancel()
        }
    }
}
