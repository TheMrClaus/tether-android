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

        override fun sendAttachments(sessionId: String, text: String, attachments: List<Attachment>, mention: com.tether.app.protocol.DelegateMention?, expectedOrigin: String?): AttachmentSendResult {
            attachmentSends += Triple(sessionId, text, attachments.size)
            return attachmentResult
        }

        private var seq = 0L

        fun created(id: String, requestId: String?, seq: Long = ++this.seq) {
            this.seq = maxOf(this.seq, seq)
            createdSessions.value = CreatedReply(session(id), seq, requestId)
        }

        private var errorSeq = 0L

        fun error(message: String, requestId: String?, seq: Long = ++errorSeq) {
            errorSeq = maxOf(errorSeq, seq)
            createErrors.value = CreateErrorReply(message, seq, requestId)
        }
    }

    private class Saved(val origin: String, val sessionId: String, val text: String)

    private class Harness(
        val client: Client,
        val model: DraftComposerModel,
        val store: DraftStore,
        val saved: MutableList<Saved>,
        val ids: MutableList<String>,
    )

    private fun TestScope.harness(
        client: Client = Client(),
        store: DraftStore = InMemoryDraftStore(),
        failingSave: Boolean = false,
        workspace: String? = "/w",
    ): Harness {
        val saved = mutableListOf<Saved>()
        val ids = mutableListOf<String>()
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
        )
        model.onOrigin(A)
        runCurrent()
        return Harness(client, model, store, saved, ids)
    }

    /** What the view model does with each input, in its order. */
    private fun Harness.deliverCreated() = client.createdSessions.value?.let { model.onCreated(it) }

    private fun Harness.deliverError() = client.createErrors.value?.let { model.onCreateError(it) }

    private val claude = NewSessionChoice("claude", "claude", null)

    // --- the cold draft and the frame ---------------------------------------------------------------

    @Test
    fun aColdDraftStartsOnTheWebsDefaultsAndClaudeIsSandboxed() = runTest {
        val h = harness()
        assertEquals(DraftSubmitResult.Sent, h.model.submitChoice(claude, A))
        val frame = h.client.frames.single()
        assertEquals("claude", frame.provider)
        assertEquals("/w", frame.cwd)
        assertEquals("bypassPermissions", frame.permissionMode)
        assertEquals("workspace-write", frame.sandboxPolicy)
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
        h.model.selectMode("default")
        h.model.setText("hi")
        assertEquals(DraftSubmitResult.Sent, h.model.submit(A))
        assertEquals("default", h.client.frames.single().permissionMode)
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
        h.model.selectMode("default")
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
        h.model.selectMode("default")
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
        h.model.selectMode("plan")
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
