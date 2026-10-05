package com.tether.app.client

import com.tether.app.protocol.Attachment
import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.StubClient
import com.tether.app.ui.prefs.DraftStore
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-8cv: the draft composer engine (hooks/use-draft-composer.ts) over a hand-driven client: the
 * cold draft's web defaults, a fresh requestId per attempt, `created` matched ONLY by requestId and
 * a newer seq, the matching `error` (and only it) unlocking, a dropped or replaced link returning
 * the draft, the first message through the client's own paths, the orphan prompt saved (never
 * resent), and preferences kept per server origin.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftComposerModelTest {

    private companion object {
        const val A = "https://a.example:443"
        const val B = "https://b.example:443"
    }

    /** The client the engine talks to: every input is set by the test; every output is recorded. */
    private class Client : StubClient() {
        override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        override val consentOrigin = MutableStateFlow<String?>(A)
        override val linkEpoch = MutableStateFlow(1L)
        override val providers = MutableStateFlow(
            listOf(ProviderInfo("claude", "Claude", "C", true), ProviderInfo("codex", "Codex", "X", true), ProviderInfo("pi", "Pi", "P", true)),
        )
        override val providerCatalog = MutableStateFlow<List<ProviderCatalogEntry>>(emptyList())
        override val providerCatalogLive = MutableStateFlow(false)
        override val workspaceRoot = MutableStateFlow<String?>("/root")
        override val createdSessions = MutableStateFlow<CreatedReply?>(null)
        override val createErrors = MutableStateFlow<CreateErrorReply?>(null)
        override val liveSessions = MutableStateFlow<Set<String>>(emptySet())

        val requests = mutableListOf<NewSessionRequest>()
        val frames = mutableListOf<ClientMessage.Create>()
        val sends = mutableListOf<Pair<String, String>>()
        val attachmentSends = mutableListOf<Triple<String, String, Int>>()
        var nextResult: NewSessionResult? = null
        var attachmentResult = AttachmentSendResult.Sent

        override fun createNewSession(request: NewSessionRequest, expectedOrigin: String?): NewSessionResult {
            requests += request
            nextResult?.let { return it }
            if (expectedOrigin != consentOrigin.value) return NewSessionResult.NotLive
            if (request.linkEpoch != linkEpoch.value) return NewSessionResult.NotConnected
            val frame = NewSessionGuard.resolve(request, if (providerCatalogLive.value) providerCatalog.value else null, providers.value)
                ?: return NewSessionResult.NotOffered
            frames += frame
            return NewSessionResult.Sent
        }

        override fun send(sessionId: String, text: String, attachments: List<Attachment>) {
            sends += sessionId to text
        }

        /** As the real client: only on the create's server and socket, still live. */
        override fun sendFirst(sessionId: String, text: String, expectedOrigin: String, expectedEpoch: Long): Boolean {
            if (connection.value != ConnectionState.Connected) return false
            if (consentOrigin.value != expectedOrigin || linkEpoch.value != expectedEpoch) return false
            sends += sessionId to text
            return true
        }

        val records = mutableMapOf<String, CreateReplyRecord>()

        override fun createReply(requestId: String): CreateReplyRecord? = records[requestId]

        override fun sendAttachments(sessionId: String, text: String, attachments: List<Attachment>, mention: com.tether.app.protocol.DelegateMention?, expectedOrigin: String?, expectedEpoch: Long?): AttachmentSendResult {
            attachmentSends += Triple(sessionId, text, attachments.size)
            attachmentEpochs += expectedEpoch
            return attachmentResult
        }

        /** ta-2ew (R3): the socket each attachment send was bound to (null: any live one). */
        val attachmentEpochs = mutableListOf<Long?>()

        /** T8.5: every `handoff` (source, target, engineText) and every cleared brief. */
        val handoffs = mutableListOf<Triple<String, String, String>>()
        val clearedBriefs = mutableListOf<String>()
        var handoffResult = true

        override fun handoff(sourceId: String, targetId: String, engineText: String): Boolean {
            if (!handoffResult) return false
            handoffs += Triple(sourceId, targetId, engineText)
            return true
        }

        override fun clearHandoffBrief(sourceId: String) {
            clearedBriefs += sourceId
        }

        private var seq = 0L

        /** The reply as the real client records it: stamped with the socket it came on. */
        fun created(id: String, requestId: String?, seq: Long = ++this.seq, epoch: Long = linkEpoch.value, origin: String? = null) {
            this.seq = maxOf(this.seq, seq)
            val reply = CreatedReply(session(id), seq, requestId, epoch, origin)
            createdSessions.value = reply
            if (requestId != null) records[requestId] = CreateReplyRecord.Created(reply)
        }

        private var errorSeq = 0L

        fun error(message: String, requestId: String?, seq: Long = ++errorSeq, epoch: Long = linkEpoch.value, origin: String? = null) {
            errorSeq = maxOf(errorSeq, seq)
            val reply = CreateErrorReply(message, seq, requestId, epoch, origin)
            createErrors.value = reply
            if (requestId != null) records[requestId] = CreateReplyRecord.Failed(reply)
        }
    }

    private class Saved(val origin: String, val sessionId: String, val text: String)

    private class Harness(
        val client: Client,
        val model: DraftComposerModel,
        val store: DraftStore,
        val saved: MutableList<Saved>,
        val ids: MutableList<String>,
        /** Every session the engine reported its own create made (the view model selects it). */
        val opened: MutableList<String>,
    )

    private fun TestScope.harness(
        client: Client = Client(),
        store: DraftStore = InMemoryDraftStore(),
        failingSave: Boolean = false,
        workspace: String? = "/w",
    ): Harness {
        val saved = mutableListOf<Saved>()
        val ids = mutableListOf<String>()
        val opened = mutableListOf<String>()
        var n = 0
        val model = DraftComposerModel(
            client = client,
            draftStore = store,
            scope = backgroundScope,
            currentWorkspace = { workspace },
            saveSessionDraft = { origin, sessionId, text ->
                if (failingSave) error("storage unavailable")
                saved += Saved(origin, sessionId, text)
            },
            newRequestId = { "req-${++n}".also { ids += it } },
            onSessionCreated = { id, _ -> opened += id },
        )
        model.onOrigin(A)
        runCurrent()
        return Harness(client, model, store, saved, ids, opened)
    }

    /** What the view model does with each input, in its order. */
    private fun Harness.deliverCreated() = client.createdSessions.value?.let { model.onCreated(it) }

    private fun Harness.deliverError() = client.createErrors.value?.let { model.onCreateError(it) }

    private val claude = NewSessionChoice("claude", "claude", null)

    // --- the cold draft and the frame ---------------------------------------------------------------

    @Test
    fun aColdDraftStartsOnTheWebsDefaultsAndClaudeNamesNoSandboxTier() = runTest {
        val h = harness()
        assertEquals(DraftSubmitResult.Sent, h.model.submitChoice(claude, A))
        val frame = h.client.frames.single()
        assertEquals("claude", frame.provider)
        assertEquals("/w", frame.cwd)
        assertEquals("bypassPermissions", frame.permissionMode)
        assertNull("ta-93qs: the server decides Claude's tier, as for the web", frame.sandboxPolicy)
        assertEquals("req-1", frame.requestId)
        assertNull("no model is pinned", frame.model)
        assertTrue(h.model.state.value.creating)
    }

    @Test
    fun codexStartsOnDefaultPermissionsAndAnEngineWithoutModesOnBypass() = runTest {
        val h = harness()
        h.model.submitChoice(NewSessionChoice("codex", "codex", null), A)
        h.client.created("c1", "req-1")
        h.deliverCreated()
        h.model.submitChoice(NewSessionChoice("pi", "pi", null), A)
        val (codex, pi) = h.client.frames
        assertEquals("workspace-write", codex.sandboxPolicy)
        assertNull(codex.approvalPolicy)
        assertNull(codex.approvalsReviewer)
        assertEquals("bypassPermissions", pi.permissionMode)
        assertNull(pi.sandboxPolicy)
    }

    @Test
    fun theWorkingFolderFollowsTheWorkspaceElseTheServersRoot() = runTest {
        val h = harness(workspace = null)
        h.model.submitChoice(claude, A)
        assertEquals("/root", h.client.frames.single().cwd)
    }

    @Test
    fun anElevatedModeIsAnOrdinaryChoiceAndRidesTheFrame() = runTest {
        val h = harness()
        h.model.refresh()
        assertTrue(h.model.selectProvider("claude"))
        h.model.selectMode("default", "claude")
        h.model.setText("hi")
        assertEquals(DraftSubmitResult.Sent, h.model.submit(A))
        assertEquals("default", h.client.frames.single().permissionMode)
    }

    // --- T8.5: takeover in a new session (use-draft-composer.ts 90fbb9f :355-358, :387-414) ---------

    private fun brief(source: String, instruction: String) = HandoffBriefReading(source, kotlinx.serialization.json.JsonObject(emptyMap()), "digest", instruction)

    @Test
    fun theTakeoverBriefSeedsThePromptOnceAndNeverOverEdits() = runTest {
        val h = harness()
        h.model.onHandoffBriefs(mapOf("src" to brief("src", "Resume src.")))
        assertEquals("not in takeover mode: nothing seeded", "", h.model.state.value.text)
        h.model.setTakeover("src")
        h.model.onHandoffBriefs(emptyMap())
        assertEquals("", h.model.state.value.text)
        h.model.onHandoffBriefs(mapOf("src" to brief("src", "Resume src.")))
        assertEquals("Resume src.", h.model.state.value.text)
        h.model.setText("Resume src. Also check CI.")
        h.model.onHandoffBriefs(mapOf("src" to brief("src", "A newer brief.")))
        assertEquals("Resume src. Also check CI.", h.model.state.value.text)
        // A new takeover click re-arms the prefill (dashboard.tsx :326).
        h.model.setTakeover("src")
        h.model.onHandoffBriefs(mapOf("src" to brief("src", "A newer brief.")))
        assertEquals("A newer brief.", h.model.state.value.text)
    }

    @Test
    fun aTakeoverCreateSendsHandoffInsteadOfTheFirstMessage() = runTest {
        val h = harness()
        h.model.refresh()
        h.model.selectProvider("codex")
        h.model.setTakeover("src")
        h.model.onHandoffBriefs(mapOf("src" to brief("src", "  Resume src.  ")))
        assertEquals(DraftSubmitResult.Sent, h.model.submit(A))
        assertTrue("nothing before created", h.client.handoffs.isEmpty())
        h.client.created("new-1", "req-1")
        assertEquals("new-1", h.deliverCreated())
        assertEquals(listOf(Triple("src", "new-1", "Resume src.")), h.client.handoffs)
        assertTrue("no plain first message", h.client.sends.isEmpty())
        // onTakeoverCommitted: the brief is dropped and takeover mode ends; the draft starts over.
        assertEquals(listOf("src"), h.client.clearedBriefs)
        assertNull(h.model.state.value.takeoverSourceId)
        assertEquals("", h.model.state.value.text)
        assertEquals(listOf("new-1"), h.opened)
    }

    @Test
    fun aCancelledTakeoverCreatesAnOrdinarySession() = runTest {
        val h = harness()
        h.model.refresh()
        h.model.selectProvider("codex")
        h.model.setTakeover("src")
        h.model.setText("Plain first message")
        h.model.setTakeover(null)
        h.model.submit(A)
        h.client.created("new-1", "req-1")
        h.deliverCreated()
        assertEquals(listOf("new-1" to "Plain first message"), h.client.sends)
        assertTrue(h.client.handoffs.isEmpty())
    }

    @Test
    fun aHandoffThatCouldNotGoKeepsThePromptAsTheNewSessionsDraft() = runTest {
        val h = harness()
        h.model.refresh()
        h.model.selectProvider("codex")
        h.model.setTakeover("src")
        h.model.setText("Resume src.")
        h.model.submit(A)
        h.client.handoffResult = false
        h.client.created("new-1", "req-1")
        h.deliverCreated()
        runCurrent()
        assertTrue(h.client.handoffs.isEmpty())
        assertEquals(listOf("new-1" to "Resume src."), h.saved.map { it.sessionId to it.text })
        assertTrue("never resent as a plain message", h.client.sends.isEmpty())
    }

    // --- readiness ------------------------------------------------------------------------------------

    @Test
    fun readinessReasonsComeInTheWebsOrderAndNothingIsSent() = runTest {
        val h = harness(client = Client().apply { workspaceRoot.value = null }, workspace = null)
        h.model.refresh()
        assertEquals(READINESS_NEED_PROMPT, h.model.readiness())
        h.model.setText("  ")
        assertEquals(READINESS_NEED_PROMPT, h.model.readiness())
        h.model.setText("hello")
        assertEquals(READINESS_NEED_PROVIDER, h.model.readiness())
        h.client.providerCatalog.value = listOf(ProviderCatalogEntry("claude", "claude", "loading", emptyList()))
        h.client.providerCatalogLive.value = true
        h.model.refresh()
        h.model.selectProvider("claude")
        assertEquals(READINESS_MODELS_LOADING, h.model.readiness())
        h.client.providerCatalog.value = listOf(ProviderCatalogEntry("claude", "claude", "ready", emptyList()))
        h.model.refresh()
        assertEquals(READINESS_NEED_CWD, h.model.readiness())
        h.model.setCwd("/w")
        h.model.setUseWorktree(true)
        h.model.setWorktreeOptions(JsObj.of("worktreeMode" to JsStr("checkout-pr")))
        assertEquals(READINESS_NEED_PR, h.model.readiness())
        h.model.setWorktreeOptions(JsObj.of("worktreeMode" to JsStr("checkout-branch")))
        assertEquals(READINESS_NEED_BRANCH, h.model.readiness())
        assertEquals(DraftSubmitResult.NotReady, h.model.submit(A))
        assertEquals(READINESS_NEED_BRANCH, h.model.state.value.error)
        h.model.setWorktreeOptions(JsObj.of("worktreeBranch" to JsStr("main")))
        assertEquals("", h.model.readiness())
        assertTrue("nothing went out while not ready", h.client.requests.isEmpty())
    }

    // --- submit, matching -------------------------------------------------------------------------------

    @Test
    fun aMatchingCreatedCompletesTheCreateAndNamesItsSession() = runTest {
        val h = harness()
        h.model.submitChoice(claude, A)
        h.client.created("new", "req-1")
        assertEquals("new", h.deliverCreated())
        assertFalse(h.model.state.value.creating)
        assertEquals(1, h.model.state.value.completed)
    }

    /** Negative control: a resume's reply has no token and never completes a create. */
    @Test
    fun aCreatedWithoutARequestIdIsNeverThisDrafts() = runTest {
        val h = harness()
        h.model.submitChoice(claude, A)
        h.client.created("resumed", null)
        assertNull(h.deliverCreated())
        assertTrue(h.model.state.value.creating)
        assertEquals(0, h.model.state.value.completed)
    }

    /** Negative control: another create's token never matches. */
    @Test
    fun aCreatedWithAnotherRequestIdIsNeverThisDrafts() = runTest {
        val h = harness()
        h.model.submitChoice(claude, A)
        h.client.created("other", "req-999")
        assertNull(h.deliverCreated())
        assertTrue(h.model.state.value.creating)
    }

    /** Belt and braces: even the right token on a reply older than the submit is stale. */
    @Test
    fun aStaleCreatedIsIgnoredEvenWithTheSameToken() = runTest {
        val h = harness()
        h.client.created("old", "req-1", seq = 7)
        h.model.submitChoice(claude, A)
        assertEquals("req-1", h.client.requests.single().requestId)
        assertNull(h.deliverCreated())
        assertTrue(h.model.state.value.creating)
        h.client.created("new", "req-1", seq = 8)
        assertEquals("new", h.deliverCreated())
    }

    @Test
    fun twoCreatesOnOneLinkEachMatchOnlyTheirOwnReply() = runTest {
        val h = harness()
        h.model.submitChoice(claude, A)
        assertEquals("a second tap while one is in flight sends nothing", DraftSubmitResult.Busy, h.model.submitChoice(claude, A))
        assertEquals(1, h.client.requests.size)
        h.client.created("first", "req-1")
        assertEquals("first", h.deliverCreated())
        h.model.submitChoice(claude, A)
        assertEquals(listOf("req-1", "req-2"), h.client.requests.map { it.requestId })
        // The first reply delivered again (a re-collect) is not the second create's.
        assertNull(h.deliverCreated())
        assertTrue(h.model.state.value.creating)
        h.client.created("second", "req-2")
        assertEquals("second", h.deliverCreated())
        assertEquals(2, h.model.state.value.completed)
    }

    // --- errors -----------------------------------------------------------------------------------------

    @Test
    fun aMatchingErrorUnlocksKeepsTheTextAndShowsTheServersWords() = runTest {
        val h = harness()
        h.model.refresh()
        h.model.selectProvider("claude")
        h.model.setText("build the thing")
        h.model.submit(A)
        h.client.error("Skipping tool approvals needs a browser sign-in, not a paired device.", "req-1")
        assertEquals(true, h.deliverError())
        val s = h.model.state.value
        assertFalse(s.creating)
        assertEquals("Skipping tool approvals needs a browser sign-in, not a paired device.", s.error)
        assertEquals("the text is kept", "build the thing", s.text)
        assertEquals("bypassPermissions", h.client.frames.single().permissionMode)
        assertTrue("never downgraded and resent", h.client.requests.size == 1 && h.client.sends.isEmpty())
        // The operator may try again: a fresh token.
        assertEquals(DraftSubmitResult.Sent, h.model.submit(A))
        assertEquals("req-2", h.client.requests.last().requestId)
    }

    /** Negative control: an error without this create's token never unlocks it. */
    @Test
    fun anUnrelatedErrorDoesNotUnlock() = runTest {
        val h = harness()
        h.model.submitChoice(claude, A)
        h.client.error("some other failure", null)
        assertEquals(false, h.deliverError())
        h.client.error("a node request failed", "node-7")
        assertEquals(false, h.deliverError())
        assertTrue(h.model.state.value.creating)
        assertEquals("", h.model.state.value.error)
    }

    @Test
    fun aStaleMatchingErrorDoesNotUnlock() = runTest {
        val h = harness()
        h.client.error("old", "req-1", seq = 4)
        h.model.submitChoice(claude, A)
        assertEquals(false, h.deliverError())
        assertTrue(h.model.state.value.creating)
    }

    @Test
    fun aMatchingErrorWhoseWordsCleanedToNothingStillUnlocks() = runTest {
        val h = harness()
        h.model.submitChoice(claude, A)
        h.client.error("", "req-1")
        assertEquals(true, h.deliverError())
        assertEquals(DRAFT_REFUSED_COPY, h.model.state.value.error)
    }

    @Test
    fun aRefusalBeforeTheWireLeavesNothingInFlight() = runTest {
        val h = harness()
        h.client.nextResult = NewSessionResult.NotConnected
        h.model.refresh()
        h.model.selectProvider("claude")
        h.model.setText("hi")
        assertEquals(DraftSubmitResult.NotConnected, h.model.submit(A))
        assertFalse(h.model.state.value.creating)
        assertEquals(DRAFT_NOT_CONNECTED_COPY, h.model.state.value.error)
        assertEquals("hi", h.model.state.value.text)
        // Nothing is retried on its own.
        h.client.nextResult = null
        h.model.onLink(ConnectionState.Connected, 1)
        assertEquals(1, h.client.requests.size)
    }

    // --- the link ---------------------------------------------------------------------------------------

    @Test
    fun aDroppedLinkReturnsTheDraftWithItsText() = runTest {
        val h = harness()
        h.model.refresh()
        h.model.selectProvider("claude")
        h.model.setText("keep me")
        h.model.submit(A)
        h.model.onLink(ConnectionState.Connected, 1) // still the same socket: nothing changes
        assertTrue(h.model.state.value.creating)
        h.model.onLink(ConnectionState.Disconnected, 1)
        val s = h.model.state.value
        assertFalse(s.creating)
        assertEquals(DRAFT_LINK_DROPPED_COPY, s.error)
        assertEquals("keep me", s.text)
        // Its reply can never be acted on afterwards.
        h.client.created("late", "req-1")
        assertNull(h.deliverCreated())
        assertTrue(h.client.sends.isEmpty())
    }

    @Test
    fun aLinkReplacedBetweenTwoLooksIsADropToo() = runTest {
        val h = harness()
        h.model.submitChoice(claude, A)
        // The collector only saw "Connected" again, on a new socket.
        h.model.onLink(ConnectionState.Connected, 2)
        assertFalse(h.model.state.value.creating)
        assertEquals(DRAFT_LINK_DROPPED_COPY, h.model.state.value.error)
    }

    @Test
    fun theCreateCarriesTheEpochItWasComposedOn() = runTest {
        val h = harness()
        h.client.linkEpoch.value = 5
        h.model.submitChoice(claude, A)
        assertEquals(5, h.client.requests.single().linkEpoch)
    }

    // --- the first message --------------------------------------------------------------------------------

    @Test
    fun theFirstMessageGoesOutOnceAfterCreatedAndTheDraftStartsOver() = runTest {
        val h = harness()
        h.model.refresh()
        h.model.selectProvider("claude")
        h.model.selectMode("default", "claude")
        h.model.setText("  hello there  ")
        h.model.submit(A)
        h.model.setText("edited while creating")
        assertTrue("nothing before created", h.client.sends.isEmpty())
        h.client.created("new", "req-1")
        h.deliverCreated()
        assertEquals(listOf("new" to "hello there"), h.client.sends)
        val s = h.model.state.value
        assertEquals("", s.text)
        assertEquals(JsStr(""), s.form["key"])
        assertEquals("RESET re-seeds the workspace", JsStr("/w"), s.form["cwd"])
        h.deliverCreated()
        assertEquals("never twice", 1, h.client.sends.size)
    }

    @Test
    fun aFirstMessageThatCannotGoOutIsSavedAsTheNewSessionsDraftAndNeverResent() = runTest {
        val h = harness()
        h.model.refresh()
        h.model.selectProvider("claude")
        h.model.setText("orphan me")
        h.model.submit(A)
        // The link moved to another server between the reply and the send.
        h.client.created("new", "req-1")
        h.client.consentOrigin.value = B
        h.deliverCreated()
        runCurrent()
        assertTrue(h.client.sends.isEmpty())
        val saved = h.saved.single()
        assertEquals(A, saved.origin)
        assertEquals("new", saved.sessionId)
        assertEquals("orphan me", saved.text)
        assertEquals("", h.model.state.value.text)
        h.client.consentOrigin.value = A
        h.deliverCreated()
        runCurrent()
        assertTrue("never resent", h.client.sends.isEmpty())
    }

    @Test
    fun anOrphanWhoseDraftCannotBeSavedKeepsTheText() = runTest {
        val h = harness(failingSave = true)
        h.model.refresh()
        h.model.selectProvider("claude")
        h.model.setText("keep me here")
        h.model.submit(A)
        h.client.created("new", "req-1")
        h.client.linkEpoch.value = 2
        h.deliverCreated()
        runCurrent()
        assertTrue(h.client.sends.isEmpty())
        assertEquals("keep me here", h.model.state.value.text)
        assertEquals("the session itself is still opened", listOf("new"), h.opened)
    }

    // --- r2: replies across the socket, the reply timeout, redaction ----------------------------------

    /** r2 (verifier P4): the right token on a later socket never completes the create. */
    @Test
    fun aMatchingTokenOnANewSocketNeverCompletesTheCreate() = runTest {
        val h = harness()
        h.model.submitChoice(claude, A)
        h.client.created("elsewhere", "req-1", epoch = 2)
        assertNull(h.deliverCreated())
        h.client.error("refused elsewhere", "req-1", epoch = 2)
        assertEquals(false, h.deliverError())
        assertTrue(h.model.state.value.creating)
        assertTrue(h.opened.isEmpty())
        // On the create's own socket it does (positive control).
        h.client.created("new", "req-1", epoch = 1)
        assertEquals("new", h.deliverCreated())
    }

    /** r2 (security F2): a create whose reply never comes unlocks after 30 s; never resent. */
    @Test
    fun aCreateWithNoReplyUnlocksInTimeAndSaysTheSessionMayExist() = runTest {
        val h = harness()
        h.model.refresh()
        h.model.selectProvider("claude")
        h.model.setText("still mine")
        h.model.submit(A)
        advanceTimeBy(DraftComposerModel.CREATE_REPLY_TIMEOUT_MS - 1)
        runCurrent()
        assertTrue(h.model.state.value.creating)
        advanceTimeBy(2)
        runCurrent()
        val s = h.model.state.value
        assertFalse(s.creating)
        assertEquals(DRAFT_REPLY_TIMEOUT_COPY, s.error)
        assertEquals("still mine", s.text)
        assertEquals("never resent", 1, h.client.requests.size)
        // Its late reply is not acted on (the session shows in the list; nothing is sent into it).
        h.client.created("late", "req-1")
        assertNull(h.deliverCreated())
        assertTrue(h.client.sends.isEmpty())
        assertTrue(h.opened.isEmpty())
    }

    @Test
    fun aReplyThatArrivedTheTimerStopsAndTheDraftIsNotUnlockedTwice() = runTest {
        val h = harness()
        h.model.submitChoice(claude, A)
        h.client.error("Refused.", "req-1")
        h.deliverError()
        advanceTimeBy(DraftComposerModel.CREATE_REPLY_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals("Refused.", h.model.state.value.error)
    }

    /** r2 (security F2): at the timeout, an answer the client recorded but nobody delivered still stands. */
    @Test
    fun anAnswerRecordedButNotDeliveredStandsAtTheTimeout() = runTest {
        val h = harness()
        h.model.submitChoice(claude, A)
        h.client.created("new", "req-1") // recorded; its delivery was lost
        advanceTimeBy(DraftComposerModel.CREATE_REPLY_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(listOf("new"), h.opened)
        assertEquals("", h.model.state.value.error)
        assertEquals(1, h.model.state.value.completed)
    }

    /**
     * r2 (security F2): the socket that answered goes before the `created` collector runs (the
     * link collector first, across a reconnect): the session was created and is opened, never
     * reported as not created; its first message cannot go on the new socket, so it is the new
     * session's draft.
     */
    @Test
    fun aDropSeenBeforeTheCreatedItAnsweredStillCompletesTheCreate() = runTest {
        val h = harness()
        h.model.refresh()
        h.model.selectProvider("claude")
        h.model.setText("my first words")
        h.model.submit(A)
        h.client.created("new", "req-1") // handled and recorded by the client on socket 1
        h.client.connection.value = ConnectionState.Connecting
        h.client.linkEpoch.value = 2
        h.client.connection.value = ConnectionState.Connected
        h.model.onLink(ConnectionState.Connected, 2) // the link collector runs first
        runCurrent()
        val s = h.model.state.value
        assertFalse(s.creating)
        assertEquals("not reported as not created", "", s.error)
        assertEquals(listOf("new"), h.opened)
        assertTrue("never sent on the new socket", h.client.sends.isEmpty())
        assertEquals("my first words", h.saved.single().text)
        // The created collector runs late: nothing happens twice.
        assertNull(h.deliverCreated())
        assertEquals(listOf("new"), h.opened)
    }

    /**
     * ta-2ew (R1): a reply stamped with another server than the create's never answers it, whether it
     * arrives on the stream or is found in the record; one stamped with the create's server does.
     */
    @Test
    fun aReplyStampedWithAnotherServerNeverAnswersTheCreate() = runTest {
        val h = harness()
        h.model.submitChoice(claude, A)
        h.client.created("from-b", "req-1", origin = B)
        assertNull(h.deliverCreated())
        h.client.error("from b", "req-1", origin = B)
        assertEquals(false, h.deliverError())
        // The socket drops with only B's answers on record: not completed from them either.
        h.client.created("from-b-2", "req-1", origin = B)
        h.model.onLink(ConnectionState.Disconnected, 1)
        assertTrue(h.opened.isEmpty())
        assertFalse(h.model.state.value.creating)
        assertEquals(DRAFT_LINK_DROPPED_COPY, h.model.state.value.error)

        // Positive control: stamped with the create's own server, it answers.
        h.client.connection.value = ConnectionState.Connected
        h.model.onLink(ConnectionState.Connected, 1)
        assertEquals(DraftSubmitResult.Sent, h.model.submitChoice(claude, A))
        h.client.created("from-a", "req-2", origin = A)
        assertEquals("from-a", h.deliverCreated())
        assertEquals(listOf("from-a"), h.opened)
    }

    @Test
    fun aRefusalSeenAfterTheDropStillShowsTheServersWords() = runTest {
        val h = harness()
        h.model.submitChoice(claude, A)
        h.client.error("Skipping tool approvals needs a browser sign-in, not a paired device.", "req-1")
        h.model.onLink(ConnectionState.Disconnected, 1)
        assertEquals("Skipping tool approvals needs a browser sign-in, not a paired device.", h.model.state.value.error)
    }

    /** r2 (security F3): neither the state nor the request nor the frame logs the prompt or the profile. */
    @Test
    fun nothingPrivateReachesToString() = runTest {
        val h = harness()
        h.client.providerCatalog.value = listOf(ProviderCatalogEntry("work-account-7", "claude", "ready", emptyList(), profileId = "work-account-7"))
        h.client.providerCatalogLive.value = true
        h.model.refresh()
        h.model.selectProvider("work-account-7")
        h.model.setText("the secret plan")
        h.model.setAttachments(listOf(Attachment(name = "private.png", mediaType = "image/png", data = "AAAA")))
        h.model.setCwd("/home/me/secret-project")
        h.model.submit(A)
        val texts = listOf(h.model.state.value.toString(), h.client.requests.single().toString(), h.client.frames.single().toString())
        for (t in texts) {
            for (secret in listOf("the secret plan", "private.png", "AAAA", "work-account-7", "secret-project")) {
                assertFalse("$secret in $t", t.contains(secret))
            }
        }
        assertTrue(texts[2], texts[2].contains("profile=***"))
    }

    @Test
    fun attachmentsGoOnceWhenTheNewSessionIsLive() = runTest {
        val h = harness()
        h.model.refresh()
        h.model.selectProvider("claude")
        h.model.setText("look")
        h.model.setAttachments(listOf(Attachment(name = "a.png", mediaType = "image/png", data = "AAAA")))
        h.model.submit(A)
        h.client.created("new", "req-1")
        assertEquals("new", h.deliverCreated())
        runCurrent()
        assertTrue("waits for the session to be live", h.client.attachmentSends.isEmpty())
        assertTrue(h.model.state.value.creating)
        h.client.liveSessions.value = setOf("new")
        runCurrent()
        assertEquals(listOf(Triple("new", "look", 1)), h.client.attachmentSends)
        // ta-2ew (R3): bound to the create's socket, checked again under the client's lock.
        assertEquals(listOf<Long?>(1L), h.client.attachmentEpochs)
        assertTrue(h.client.sends.isEmpty())
        val s = h.model.state.value
        assertFalse(s.creating)
        assertEquals("", s.text)
        assertTrue(s.attachments.isEmpty())
    }

    @Test
    fun attachmentsThatCannotGoBecomeAnOrphanPromptAndAreDropped() = runTest {
        val h = harness()
        h.client.attachmentResult = AttachmentSendResult.TooLarge
        h.model.refresh()
        h.model.selectProvider("claude")
        h.model.setText("look")
        h.model.setAttachments(listOf(Attachment(name = "a.png", mediaType = "image/png", data = "AAAA")))
        h.model.submit(A)
        h.client.created("new", "req-1")
        h.deliverCreated()
        h.client.liveSessions.value = setOf("new")
        runCurrent()
        assertEquals(1, h.client.attachmentSends.size)
        assertEquals("look", h.saved.single().text)
        assertTrue(h.model.state.value.attachments.isEmpty())
        assertFalse(h.model.state.value.creating)
    }

    @Test
    fun attachmentsWhoseSessionNeverGoesLiveAreNotSent() = runTest {
        val h = harness()
        h.model.refresh()
        h.model.selectProvider("claude")
        h.model.setText("look")
        h.model.setAttachments(listOf(Attachment(name = "a.png", mediaType = "image/png", data = "AAAA")))
        h.model.submit(A)
        h.client.created("new", "req-1")
        h.deliverCreated()
        advanceTimeBy(DraftComposerModel.FIRST_SEND_LIVE_TIMEOUT_MS + 1)
        runCurrent()
        assertTrue(h.client.attachmentSends.isEmpty())
        assertEquals("look", h.saved.single().text)
        assertFalse(h.model.state.value.creating)
    }

    // --- preferences, per origin --------------------------------------------------------------------------

    @Test
    fun preferencesArePerServerOrigin() = runTest {
        val store = InMemoryDraftStore()
        val h = harness(store = store)
        h.model.refresh()
        h.model.selectProvider("claude")
        h.model.selectMode("default", "claude")
        runCurrent()
        assertEquals(JsStr("default"), (store.readDraftPreferences(A)["providerPreferences"] as JsObj)["claude"].let { (it as JsObj)["mode"] })
        // Server B: nothing of A's, and a cold Claude draft is the web's default again.
        h.model.onOrigin(B)
        runCurrent()
        assertEquals(JsObj.EMPTY, h.model.preferences())
        h.client.consentOrigin.value = B
        h.model.submitChoice(claude, B)
        assertEquals("bypassPermissions", h.client.frames.last().permissionMode)
        assertEquals(JsObj.EMPTY, store.readDraftPreferences(B))
        h.model.onLink(ConnectionState.Disconnected, 1)
        // Back on A: A's own pick seeds the provider again.
        h.client.consentOrigin.value = A
        h.model.onOrigin(A)
        runCurrent()
        h.model.submitChoice(claude, A)
        assertEquals("default", h.client.frames.last().permissionMode)
    }

    @Test
    fun aPickMadeWhileTheOriginsPreferencesLoadIsKeptAndNothingIsClobbered() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backing = InMemoryDraftStore()
        backing.writeDraftPreferences(A, JsObj.of("providerPreferences" to JsObj.of("codex" to JsObj.of("mode" to JsStr("full-access")))))
        val slow = object : DraftStore by backing {
            override suspend fun readDraftPreferences(origin: String): JsObj {
                gate.await()
                return backing.readDraftPreferences(origin)
            }
        }
        val h = harness(store = slow)
        h.model.refresh()
        h.model.selectProvider("claude")
        h.model.selectMode("plan", "claude")
        gate.complete(Unit)
        runCurrent()
        val stored = backing.readDraftPreferences(A)["providerPreferences"] as JsObj
        assertEquals(JsStr("plan"), (stored["claude"] as JsObj)["mode"])
        assertEquals("the stored record is not clobbered", JsStr("full-access"), (stored["codex"] as JsObj)["mode"])
    }

    @Test
    fun aReadThatLandsAfterAServerSwitchIsDropped() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backing = InMemoryDraftStore()
        backing.writeDraftPreferences(A, JsObj.of("providerPreferences" to JsObj.of("claude" to JsObj.of("mode" to JsStr("plan")))))
        val slow = object : DraftStore by backing {
            override suspend fun readDraftPreferences(origin: String): JsObj {
                if (origin == A) gate.await()
                return backing.readDraftPreferences(origin)
            }
        }
        val h = harness(store = slow)
        h.model.onOrigin(B)
        gate.complete(Unit)
        runCurrent()
        assertEquals(JsObj.EMPTY, h.model.preferences())
    }

    // --- ta-abm: the draft is its server's ------------------------------------------------------------

    @Test
    fun aServerSwitchDropsTheDraftAndAPickStillBeingRead() = runTest {
        val h = harness()
        h.model.refresh()
        h.model.selectProvider("claude")
        h.model.setText("for A only")
        h.model.setCwd("/srv/a-project")
        h.model.setAttachments(listOf(Attachment(name = "a.png", mediaType = "image/png", data = "AAAA")))
        val reading = h.model.attachmentGeneration
        h.model.onOrigin(B)
        runCurrent()
        val s = h.model.state.value
        assertEquals("", s.text)
        assertTrue(s.staged.isEmpty())
        assertEquals("no provider carried over", JsStr(""), s.form["key"])
        assertEquals("the folder is re-seeded from the workspace", JsStr("/w"), s.form["cwd"])
        val late = StagedAttachment(h.model.newAttachmentId(), Attachment(name = "late.png", mediaType = "image/png", data = "AAAA"), 3)
        assertFalse("a pick read for A never lands in B's draft", h.model.addAttachments(listOf(late), reading))
        assertTrue(h.model.state.value.staged.isEmpty())
        assertTrue(h.model.addAttachments(listOf(late), h.model.attachmentGeneration))
    }

    @Test
    fun aServerSwitchLetsGoOfTheCreateInFlight() = runTest {
        val h = harness()
        h.model.refresh()
        h.model.selectProvider("claude")
        h.model.setText("hello")
        assertEquals(DraftSubmitResult.Sent, h.model.submit(A))
        assertTrue(h.model.state.value.creating)
        h.model.onOrigin(B)
        runCurrent()
        assertFalse(h.model.state.value.creating)
        assertEquals("", h.model.state.value.error)
        h.client.created("late", "req-1")
        assertNull("its reply opens nothing", h.deliverCreated())
        assertTrue(h.opened.isEmpty())
        assertTrue(h.client.sends.isEmpty())
    }

    @Test
    fun aPickFinishingAfterTheFirstTurnTookTheAttachmentsIsDropped() = runTest {
        val h = harness()
        h.model.refresh()
        h.model.selectProvider("claude")
        h.model.setText("look")
        val reading = h.model.attachmentGeneration
        h.model.setAttachments(listOf(Attachment(name = "a.png", mediaType = "image/png", data = "AAAA")))
        h.model.submit(A)
        val late = StagedAttachment(h.model.newAttachmentId(), Attachment(name = "late.png", mediaType = "image/png", data = "AAAA"), 3)
        assertFalse("nothing is added while a create holds the draft", h.model.addAttachments(listOf(late), reading))
        h.client.created("new", "req-1")
        h.deliverCreated()
        h.client.liveSessions.value = setOf("new")
        runCurrent()
        assertEquals(listOf(Triple("new", "look", 1)), h.client.attachmentSends)
        assertFalse(h.model.addAttachments(listOf(late), reading))
        assertTrue(h.model.state.value.staged.isEmpty())
    }

    @Test
    fun stagedAttachmentsKeepTheirIdsAndSizesAndOneCanBeRemoved() = runTest {
        val h = harness()
        h.model.setAttachments(listOf(Attachment(name = "a.png", mediaType = "image/png", data = "AAAAAA=="), Attachment(name = "b.txt", mediaType = "text/plain", data = "aGk=")))
        val staged = h.model.state.value.staged
        assertEquals(listOf(4L, 2L), staged.map { it.sizeBytes })
        assertNotEquals(staged[0].id, staged[1].id)
        h.model.removeAttachment(staged[0].id)
        assertEquals(listOf("b.txt"), h.model.state.value.attachments.map { it.name })
    }

    // --- requestIds ---------------------------------------------------------------------------------------

    @Test
    fun everyAttemptHasItsOwnRequestId() = runTest {
        val h = harness()
        repeat(3) { i ->
            h.model.submitChoice(claude, A)
            h.client.error("no", "req-${i + 1}")
            h.deliverError()
        }
        val ids = h.client.requests.map { it.requestId }
        assertEquals(3, ids.toSet().size)
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun aDraftForNoServerIsNotLive() = runTest {
        val h = harness()
        assertEquals(DraftSubmitResult.NotLive, h.model.submitChoice(claude, null))
        assertTrue(h.client.requests.isEmpty())
    }
}

private fun session(id: String) = AgentSession(id = id, provider = "claude", name = id, cwd = "/w", status = "ready", startedAt = 1, updatedAt = 1)
