package com.tether.app.client

import com.tether.app.protocol.Attachment
import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.ui.StubClient
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-23f (T8.1 slice 5): the draft composer's worktree isolation over a hand-driven client.
 *
 * - `worktree-inspect` goes out with a fresh requestId for the folder, on the current socket, once
 *   per folder per socket, while isolation is on; only the `worktree-source` echoing it, for that
 *   folder, on that socket, is taken (a stale folder's, request's or socket's answer is ignored).
 * - Readiness: an incomplete isolation request blocks Send with the web's words, in the web's order.
 * - The setup confirmation (owner 2026-10-02; coordinator option A): Send opens it instead of sending
 *   when setup will or may run; confirm sends once, from the newest state; cancel sends nothing; any
 *   change to what it showed (a new answer, the folder, the socket, a field) closes it unsent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftWorktreeModelTest {

    private companion object {
        const val A = "https://a.example:443"
    }

    private class Client : StubClient() {
        override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        override val consentOrigin = MutableStateFlow<String?>(A)
        override val linkEpoch = MutableStateFlow(1L)
        override val providers = MutableStateFlow(listOf(ProviderInfo("claude", "Claude", "C", true)))
        override val providerCatalog = MutableStateFlow<List<ProviderCatalogEntry>>(emptyList())
        override val providerCatalogLive = MutableStateFlow(false)
        override val workspaceRoot = MutableStateFlow<String?>("/root")
        override val createdSessions = MutableStateFlow<CreatedReply?>(null)
        override val createErrors = MutableStateFlow<CreateErrorReply?>(null)
        override val liveSessions = MutableStateFlow<Set<String>>(emptySet())

        val frames = mutableListOf<ClientMessage.Create>()
        val sends = mutableListOf<Pair<String, String>>()

        /** Every inspect that went out: (cwd, requestId, socket). */
        val inspects = mutableListOf<Triple<String, String, Long>>()

        override fun createNewSession(request: NewSessionRequest, expectedOrigin: String?): NewSessionResult {
            if (expectedOrigin != consentOrigin.value) return NewSessionResult.NotLive
            if (request.linkEpoch != linkEpoch.value) return NewSessionResult.NotConnected
            val frame = NewSessionGuard.resolve(request, null, providers.value) ?: return NewSessionResult.NotOffered
            frames += frame
            return NewSessionResult.Sent
        }

        override fun sendFirst(sessionId: String, text: String, expectedOrigin: String, expectedEpoch: Long): Boolean {
            sends += sessionId to text
            return true
        }

        override fun send(sessionId: String, text: String, attachments: List<Attachment>) {
            sends += sessionId to text
        }

        /** As the real client: only on the live socket the composer asked on. */
        override fun inspectWorktree(cwd: String, requestId: String, expectedEpoch: Long): Boolean {
            if (connection.value != ConnectionState.Connected || expectedEpoch != linkEpoch.value) return false
            inspects += Triple(cwd, requestId, expectedEpoch)
            return true
        }

        /** The server's `worktree-source` for the last inspect (or as told), stamped with the socket it came on. */
        fun source(info: WorktreeSourceInfo, requestId: String? = inspects.last().second, epoch: Long = linkEpoch.value) =
            WorktreeSourceReply(info, requestId, epoch)

        fun newSocket() {
            linkEpoch.value += 1
        }
    }

    private class Harness(val client: Client, val model: DraftComposerModel)

    private fun TestScope.harness(): Harness {
        val client = Client()
        var n = 0
        val model = DraftComposerModel(
            client = client,
            draftStore = InMemoryDraftStore(),
            scope = backgroundScope,
            currentWorkspace = { "/w" },
            newRequestId = { "req-${++n}" },
        )
        model.onOrigin(A)
        runCurrent()
        model.refresh()
        check(model.selectProvider("claude"))
        model.setText("hello")
        return Harness(client, model)
    }

    private fun repo(hasSetup: Boolean, defaultBaseRef: String = "origin/main", scripts: Int = 0) = WorktreeSourceInfo(
        cwd = "/w",
        isRepo = true,
        defaultBaseRef = defaultBaseRef,
        branches = listOf("origin/main", "origin/dev", "main", "feat/x"),
        configPresent = hasSetup || scripts > 0,
        hasSetup = hasSetup,
        declaredScripts = List(scripts) { WorktreeDeclaredScript("s$it", "script", null) },
    )

    private val notARepo = WorktreeSourceInfo(cwd = "/w", isRepo = false)

    /** Isolation on in [mode], asked and answered with [info] (null: asked, not answered). */
    private fun Harness.isolate(mode: String, info: WorktreeSourceInfo?) {
        assertTrue(model.selectIsolation(mode))
        assertTrue(model.inspectWorktree())
        if (info != null) assertTrue(model.onWorktreeSource(client.source(info)))
    }

    private fun Harness.link() = model.onLink(client.connection.value, client.linkEpoch.value)

    // --- inspect ------------------------------------------------------------------------------------

    @Test
    fun theInspectGoesOutOncePerFolderPerSocketWhileIsolationIsOn() = runTest {
        val h = harness()
        assertFalse("not while local", h.model.inspectWorktree())
        assertTrue(h.model.selectIsolation("branch-off"))
        assertTrue(h.model.inspectWorktree())
        assertEquals(listOf(Triple("/w", "req-1", 1L)), h.client.inspects)
        assertFalse("not twice for the same folder on the same socket", h.model.inspectWorktree())
        h.model.setCwd("/w2")
        assertTrue(h.model.inspectWorktree())
        h.client.newSocket()
        h.link()
        assertTrue(h.model.inspectWorktree())
        h.model.selectIsolation("local")
        assertFalse(h.model.inspectWorktree())
        h.model.selectIsolation("checkout-pr")
        assertTrue("isolation switched back on asks again (the web's effect)", h.model.inspectWorktree())
        assertEquals(listOf("/w", "/w2", "/w2", "/w2"), h.client.inspects.map { it.first })
        assertEquals(listOf(1L, 1L, 2L, 2L), h.client.inspects.map { it.third })
        assertEquals("every inspect has its own token", 4, h.client.inspects.map { it.second }.toSet().size)
        h.model.setCwd("   ")
        assertFalse("no folder, no inspect", h.model.inspectWorktree())
    }

    @Test
    fun anAnswerForAnOldFolderIsIgnored() = runTest {
        val h = harness()
        h.isolate("branch-off", null)
        val old = h.client.inspects.last().second
        h.model.setCwd("/w2")
        assertFalse(h.model.onWorktreeSource(h.client.source(repo(true), requestId = old)))
        assertNull(h.model.state.value.worktreeSource)
        assertTrue(h.model.inspectWorktree())
        // The old folder's answer still cannot land, even late.
        assertFalse(h.model.onWorktreeSource(h.client.source(repo(true), requestId = old)))
        assertNull(h.model.state.value.worktreeSource)
        // Positive control: the new folder's own answer is taken.
        assertTrue(h.model.onWorktreeSource(h.client.source(repo(false))))
        assertEquals(repo(false), h.model.state.value.worktreeSource)
        // And going back to the first folder drops it again.
        h.model.setCwd("/w")
        assertNull(h.model.state.value.worktreeSource)
    }

    @Test
    fun anAnswerToAnOldRequestOrWithNoEchoIsIgnored() = runTest {
        val h = harness()
        h.isolate("branch-off", null)
        val first = h.client.inspects.last().second
        h.model.selectIsolation("local")
        h.model.selectIsolation("branch-off")
        assertTrue(h.model.inspectWorktree())
        assertFalse("an older request for the same folder", h.model.onWorktreeSource(h.client.source(repo(true), requestId = first)))
        assertFalse("no echo", h.model.onWorktreeSource(h.client.source(repo(true), requestId = null)))
        assertFalse("another token", h.model.onWorktreeSource(h.client.source(repo(true), requestId = "forged")))
        assertNull(h.model.state.value.worktreeSource)
        assertTrue("positive control", h.model.onWorktreeSource(h.client.source(repo(true))))
    }

    @Test
    fun anAnswerFromAnOldSocketIsIgnored() = runTest {
        val h = harness()
        h.isolate("branch-off", null)
        val asked = h.client.inspects.last().second
        // The reply was stamped with another socket than the one asked on.
        assertFalse(h.model.onWorktreeSource(h.client.source(repo(true), requestId = asked, epoch = 2)))
        // The socket moved after the question: its answer (even with the right token, stamped 1) is void.
        h.client.newSocket()
        assertFalse(h.model.onWorktreeSource(h.client.source(repo(true), requestId = asked, epoch = 1)))
        h.link()
        assertFalse(h.model.onWorktreeSource(h.client.source(repo(true), requestId = asked, epoch = 1)))
        assertNull(h.model.state.value.worktreeSource)
        // A taken answer goes with its socket too.
        assertTrue(h.model.inspectWorktree())
        assertTrue(h.model.onWorktreeSource(h.client.source(repo(true))))
        assertNotNull(h.model.state.value.worktreeSource)
        h.client.newSocket()
        h.link()
        assertNull(h.model.state.value.worktreeSource)
    }

    @Test
    fun theInspectIsNotSentOffTheLiveSocket() = runTest {
        val h = harness()
        h.model.selectIsolation("branch-off")
        h.client.connection.value = ConnectionState.Disconnected
        assertFalse(h.model.inspectWorktree())
        assertTrue(h.client.inspects.isEmpty())
        h.client.connection.value = ConnectionState.Connected
        assertTrue(h.model.inspectWorktree())
    }

    // --- readiness ----------------------------------------------------------------------------------

    @Test
    fun anIncompleteWorktreeBlocksSendWithTheWebsWordsInTheWebsOrder() = runTest {
        val h = harness()
        h.model.selectIsolation("checkout-branch")
        h.model.setText("")
        assertEquals("the prompt comes first", READINESS_NEED_PROMPT, h.model.readiness())
        h.model.setText("hello")
        h.model.setCwd(" ")
        assertEquals("then the folder", READINESS_NEED_CWD, h.model.readiness())
        h.model.setCwd("/w")
        assertEquals(READINESS_NEED_BRANCH, h.model.readiness())
        assertEquals(DraftSubmitResult.NotReady, h.model.submit(A))
        assertEquals(READINESS_NEED_BRANCH, h.model.state.value.error)
        h.model.selectIsolation("checkout-pr")
        assertEquals(READINESS_NEED_PR, h.model.readiness())
        h.model.setWorktreeField(WorktreeField.Pr, "12345678")
        assertEquals(READINESS_PR_TOO_LARGE, h.model.readiness())
        h.model.setWorktreeField(WorktreeField.Pr, "42")
        assertEquals("", h.model.readiness())
        h.model.selectIsolation("branch-off")
        assertEquals("a new branch needs nothing", "", h.model.readiness())
        assertTrue(h.client.frames.isEmpty())
    }

    @Test
    fun theFieldsKeepTheWebsInputRules() = runTest {
        val h = harness()
        h.model.selectIsolation("checkout-pr")
        assertTrue(h.model.setWorktreeField(WorktreeField.Pr, "#4 2x"))
        assertEquals("42", (h.model.state.value.form["worktreePr"] as com.tether.app.protocol.tree.JsStr).value)
        assertTrue(h.model.setWorktreeField(WorktreeField.Branch, "feat/x"))
        assertFalse("never cut to another name", h.model.setWorktreeField(WorktreeField.Branch, "b".repeat(WorktreeDraft.FIELD_MAX + 1)))
        assertEquals("feat/x", (h.model.state.value.form["worktreeBranch"] as com.tether.app.protocol.tree.JsStr).value)
        assertFalse(h.model.selectIsolation("bogus"))
    }

    // --- the setup confirmation ---------------------------------------------------------------------

    @Test
    fun withoutSetupANewBranchFromTheDefaultBaseSendsAtOnce() = runTest {
        val h = harness()
        h.isolate("branch-off", repo(hasSetup = false, scripts = 2))
        assertEquals(DraftSubmitResult.Sent, h.model.submit(A))
        assertNull(h.model.state.value.setupConfirm)
        val frame = h.client.frames.single()
        assertEquals(true, frame.useWorktree)
        assertEquals("branch-off", frame.worktree?.mode)
        assertNull(frame.worktree?.baseRef)
    }

    @Test
    fun localNeverConfirms() = runTest {
        val h = harness()
        assertEquals(DraftSubmitResult.Sent, h.model.submit(A))
        assertEquals(false, h.client.frames.single().useWorktree)
        assertNull(h.client.frames.single().worktree)
    }

    @Test
    fun withSetupSendOpensTheConfirmationAndConfirmSendsOnce() = runTest {
        val h = harness()
        h.isolate("branch-off", repo(hasSetup = true))
        assertEquals(DraftSubmitResult.NeedsConfirmation, h.model.submit(A))
        assertTrue("nothing sent yet", h.client.frames.isEmpty())
        val shown = h.model.state.value.setupConfirm!!
        assertEquals(SetupConfirmation("branch-off", "Base", "origin/main", null, "/w", certain = true), shown)
        val id = h.model.state.value.setupConfirmId
        assertEquals(DraftSubmitResult.Sent, h.model.confirmSetup(id, A))
        assertEquals("a double tap: the second finds nothing open", DraftSubmitResult.Stale, h.model.confirmSetup(id, A))
        assertEquals(1, h.client.frames.size)
        assertEquals("branch-off", h.client.frames.single().worktree?.mode)
        assertNull(h.model.state.value.setupConfirm)
        assertTrue(h.model.state.value.creating)
    }

    @Test
    fun cancelSendsNothing() = runTest {
        val h = harness()
        h.isolate("checkout-pr", repo(hasSetup = false))
        h.model.setWorktreeField(WorktreeField.Pr, "42")
        assertEquals(DraftSubmitResult.NeedsConfirmation, h.model.submit(A))
        val id = h.model.state.value.setupConfirmId
        h.model.cancelSetup(id)
        assertNull(h.model.state.value.setupConfirm)
        assertEquals("a confirm after cancel sends nothing", DraftSubmitResult.Stale, h.model.confirmSetup(id, A))
        assertTrue(h.client.frames.isEmpty())
        assertFalse(h.model.state.value.creating)
        assertEquals("cancel is not an error", "", h.model.state.value.error)
        assertEquals("hello", h.model.state.value.text)
    }

    @Test
    fun aPullRequestAnExistingBranchAndAnotherBaseAlwaysConfirm() = runTest {
        val h = harness()
        h.isolate("checkout-pr", repo(hasSetup = false))
        h.model.setWorktreeField(WorktreeField.Pr, "7")
        assertEquals(DraftSubmitResult.NeedsConfirmation, h.model.submit(A))
        assertEquals(SetupConfirmation("checkout-pr", "Pull request", "#7", null, "/w", certain = false), h.model.state.value.setupConfirm)
        h.model.cancelSetup()
        h.model.selectIsolation("checkout-branch")
        h.model.setWorktreeField(WorktreeField.Branch, "feat/x")
        assertEquals(DraftSubmitResult.NeedsConfirmation, h.model.submit(A))
        assertEquals("feat/x", h.model.state.value.setupConfirm?.ref)
        h.model.cancelSetup()
        h.model.selectIsolation("branch-off")
        h.model.setWorktreeField(WorktreeField.Branch, "")
        h.model.setWorktreeField(WorktreeField.BaseRef, "origin/dev")
        assertEquals(DraftSubmitResult.NeedsConfirmation, h.model.submit(A))
        assertEquals(false, h.model.state.value.setupConfirm?.certain)
        h.model.cancelSetup()
        h.model.setWorktreeField(WorktreeField.BaseRef, "origin/main")
        assertEquals("the inspected default base without setup sends at once", DraftSubmitResult.Sent, h.model.submit(A))
        assertEquals("origin/main", h.client.frames.single().worktree?.baseRef)
    }

    @Test
    fun withNoAnswerYetANewBranchConfirmsFailingClosed() = runTest {
        val h = harness()
        h.isolate("branch-off", null)
        assertEquals(DraftSubmitResult.NeedsConfirmation, h.model.submit(A))
        assertEquals(SetupConfirmation("branch-off", "Base", null, null, "/w", certain = false), h.model.state.value.setupConfirm)
        assertTrue(h.client.frames.isEmpty())
    }

    @Test
    fun aNewAnswerWhileOpenClosesItUnsent() = runTest {
        val h = harness()
        h.isolate("branch-off", repo(hasSetup = true))
        h.model.submit(A)
        val id = h.model.state.value.setupConfirmId
        // The same request answered again (the same content even): what was shown may not hold.
        assertTrue(h.model.onWorktreeSource(h.client.source(repo(hasSetup = true))))
        assertNull(h.model.state.value.setupConfirm)
        assertEquals(SETUP_CHANGED_COPY, h.model.state.value.error)
        assertEquals(DraftSubmitResult.Stale, h.model.confirmSetup(id, A))
        assertTrue(h.client.frames.isEmpty())
        // Send again: a fresh confirmation, and only its own id confirms.
        assertEquals(DraftSubmitResult.NeedsConfirmation, h.model.submit(A))
        val next = h.model.state.value.setupConfirmId
        assertEquals(DraftSubmitResult.Stale, h.model.confirmSetup(id, A))
        assertNotNull("an old id does not even close the new one", h.model.state.value.setupConfirm)
        assertEquals(DraftSubmitResult.Sent, h.model.confirmSetup(next, A))
        assertEquals(1, h.client.frames.size)
    }

    @Test
    fun aFolderOrFieldChangeWhileOpenClosesItUnsent() = runTest {
        val h = harness()
        h.isolate("checkout-pr", repo(hasSetup = false))
        h.model.setWorktreeField(WorktreeField.Pr, "42")
        h.model.submit(A)
        var id = h.model.state.value.setupConfirmId
        h.model.setWorktreeField(WorktreeField.Pr, "43")
        assertNull(h.model.state.value.setupConfirm)
        assertEquals(DraftSubmitResult.Stale, h.model.confirmSetup(id, A))
        h.model.submit(A)
        id = h.model.state.value.setupConfirmId
        h.model.setCwd("/elsewhere")
        assertNull(h.model.state.value.setupConfirm)
        assertEquals(DraftSubmitResult.Stale, h.model.confirmSetup(id, A))
        assertTrue(h.client.frames.isEmpty())
    }

    @Test
    fun aSocketChangeWhileOpenClosesItUnsent() = runTest {
        val h = harness()
        h.isolate("checkout-branch", repo(hasSetup = false))
        h.model.setWorktreeField(WorktreeField.Branch, "feat/x")
        h.model.submit(A)
        val id = h.model.state.value.setupConfirmId
        h.client.newSocket()
        h.link()
        assertNull(h.model.state.value.setupConfirm)
        assertEquals(DraftSubmitResult.Stale, h.model.confirmSetup(id, A))
        // A socket that moved without the link collector having run yet: confirm still refuses.
        h.model.submit(A)
        val again = h.model.state.value.setupConfirmId
        h.client.newSocket()
        assertEquals(DraftSubmitResult.Stale, h.model.confirmSetup(again, A))
        assertTrue(h.client.frames.isEmpty())
    }

    @Test
    fun aSocketChangeClosesAConfirmationOpenedWithNoAnswer() = runTest {
        val h = harness()
        h.isolate("checkout-pr", null)
        h.model.setWorktreeField(WorktreeField.Pr, "42")
        assertEquals(DraftSubmitResult.NeedsConfirmation, h.model.submit(A))
        val id = h.model.state.value.setupConfirmId
        h.client.newSocket()
        h.link()
        assertNull("closed with its socket, not only refused on confirm", h.model.state.value.setupConfirm)
        assertEquals(SETUP_CHANGED_COPY, h.model.state.value.error)
        assertEquals(DraftSubmitResult.Stale, h.model.confirmSetup(id, A))
        // A dropped link (same socket number, not connected) closes it too.
        h.model.submit(A)
        h.client.connection.value = ConnectionState.Disconnected
        h.link()
        assertNull(h.model.state.value.setupConfirm)
        assertTrue(h.client.frames.isEmpty())
    }

    @Test
    fun aConfirmDrawnForAnotherServerSendsNothing() = runTest {
        val h = harness()
        h.isolate("checkout-pr", null)
        h.model.setWorktreeField(WorktreeField.Pr, "42")
        h.model.submit(A)
        assertEquals(DraftSubmitResult.Stale, h.model.confirmSetup(h.model.state.value.setupConfirmId, "https://b.example:443"))
        assertTrue(h.client.frames.isEmpty())
    }

    @Test
    fun theConfirmedCreateIsBuiltFromTheNewestState() = runTest {
        val h = harness()
        h.isolate("checkout-pr", repo(hasSetup = true))
        h.model.setWorktreeField(WorktreeField.Pr, "42")
        h.model.setWorktreeField(WorktreeField.Slug, "review")
        h.model.submit(A)
        // The prompt is not part of what the confirmation shows; edited while it is open, the newest rides.
        h.model.setText("the newest words")
        assertNotNull(h.model.state.value.setupConfirm)
        assertEquals(DraftSubmitResult.Sent, h.model.confirmSetup(h.model.state.value.setupConfirmId, A))
        val frame = h.client.frames.single()
        assertEquals(42L, frame.worktree?.prNumber)
        assertEquals("review", frame.worktree?.slug)
        h.client.createdSessions.value = CreatedReply(
            com.tether.app.protocol.model.AgentSession(id = "s1", provider = "claude", name = "s1", cwd = "/w", status = "ready", startedAt = 1, updatedAt = 1),
            1,
            frame.requestId,
            1,
        )
        h.model.onCreated(h.client.createdSessions.value!!)
        assertEquals(listOf("s1" to "the newest words"), h.client.sends)
    }

    @Test
    fun theInterimPickerNeverSendsAnIsolatedCreateUnconfirmed() = runTest {
        val h = harness()
        h.isolate("checkout-pr", repo(hasSetup = false))
        h.model.setWorktreeField(WorktreeField.Pr, "42")
        assertEquals(DraftSubmitResult.NeedsConfirmation, h.model.submitChoice(NewSessionChoice("claude", "claude", null), A))
        assertTrue(h.client.frames.isEmpty())
    }

    @Test
    fun aServerSwitchDropsTheQuestionTheAnswerAndTheConfirmation() = runTest {
        val h = harness()
        h.isolate("branch-off", repo(hasSetup = true))
        h.model.submit(A)
        h.model.onOrigin("https://b.example:443")
        runCurrent()
        assertNull(h.model.state.value.setupConfirm)
        assertNull(h.model.state.value.worktreeSource)
        assertEquals(DraftSubmitResult.Stale, h.model.confirmSetup(1, A))
        assertTrue(h.client.frames.isEmpty())
    }
}
