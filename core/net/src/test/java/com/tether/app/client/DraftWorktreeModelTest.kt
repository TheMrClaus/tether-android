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
 * - ta-coik.11: Send creates at once whatever the inspected config says, as the deployed web does
 *   (90fbb9f draft-composer.tsx:789-795 shows a note only); the frame is the one the same form makes
 *   with no answer at all.
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

    private fun repo(hasSetup: Boolean, defaultBaseRef: String = "origin/main", scripts: Int = 0, remote: String? = "origin") = WorktreeSourceInfo(
        cwd = "/w",
        isRepo = true,
        remote = remote,
        remotes = listOfNotNull(remote),
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
        // ta-coik.4: the web's Number.parseInt rule, no limit of the app's own (the server validates).
        h.model.setWorktreeField(WorktreeField.Pr, "12345678")
        assertEquals("", h.model.readiness())
        h.model.setWorktreeField(WorktreeField.Pr, "0")
        assertEquals(READINESS_NEED_PR, h.model.readiness())
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
        assertTrue(h.model.setWorktreeField(WorktreeField.Pr, "1".repeat(40)))
        assertEquals("no app cap on the digits", "1".repeat(40), (h.model.state.value.form["worktreePr"] as com.tether.app.protocol.tree.JsStr).value)
        assertTrue(h.model.setWorktreeField(WorktreeField.Branch, "feat/x"))
        assertEquals("feat/x", (h.model.state.value.form["worktreeBranch"] as com.tether.app.protocol.tree.JsStr).value)
        // ta-coik.4: a ref past 256 characters is taken whole, as the web's input takes it (the server validates).
        assertTrue(h.model.setWorktreeField(WorktreeField.Branch, "b".repeat(257)))
        assertEquals("b".repeat(257), (h.model.state.value.form["worktreeBranch"] as com.tether.app.protocol.tree.JsStr).value)
        assertFalse(h.model.selectIsolation("bogus"))
    }

    // --- ta-coik.11: Send sends at once (the web shows a note, no confirmation) -------------------

    /** Every isolation mode with its required input, as the user would fill it. */
    private fun Harness.fill(mode: String) {
        assertTrue(model.selectIsolation(mode))
        when (mode) {
            "checkout-pr" -> model.setWorktreeField(WorktreeField.Pr, "42")
            "checkout-branch" -> model.setWorktreeField(WorktreeField.Branch, "feat/x")
        }
    }

    /** The answers ta-23f confirmed on (setup will run, may run, cannot be vouched for) and their controls. */
    private val answers: List<WorktreeSourceInfo?> = listOf(
        null,
        repo(hasSetup = true),
        repo(hasSetup = true, scripts = 3),
        repo(hasSetup = false),
        repo(hasSetup = false, scripts = 2),
        repo(hasSetup = false, defaultBaseRef = "upstream/main", remote = "upstream"),
        repo(hasSetup = true, defaultBaseRef = "HEAD", remote = null),
        notARepo,
    )

    /** The frame [mode] makes with no inspect at all (requestId blanked: the inspect draws tokens too). */
    private fun TestScope.baseline(mode: String, base: String? = null): ClientMessage.Create {
        val h = harness()
        h.fill(mode)
        base?.let { h.model.setWorktreeField(WorktreeField.BaseRef, it) }
        assertEquals(DraftSubmitResult.Sent, h.model.submit(A))
        return h.client.frames.single().copy(requestId = "")
    }

    @Test
    fun sendCreatesAtOnceEvenWithSetupPresent() = runTest {
        for (mode in listOf("branch-off", "checkout-branch", "checkout-pr")) {
            for (base in if (mode == "branch-off") listOf(null, "origin/main", "origin/dev") else listOf(null)) {
                val expected = baseline(mode, base)
                for (info in answers) {
                    val h = harness()
                    h.fill(mode)
                    base?.let { h.model.setWorktreeField(WorktreeField.BaseRef, it) }
                    assertTrue(h.model.inspectWorktree())
                    if (info != null) assertTrue(h.model.onWorktreeSource(h.client.source(info)))
                    val label = "$mode base=$base $info"
                    assertEquals(label, DraftSubmitResult.Sent, h.model.submit(A))
                    assertEquals("$label: one frame, at once", 1, h.client.frames.size)
                    assertTrue(label, h.model.state.value.creating)
                    assertEquals("$label: no error", "", h.model.state.value.error)
                    assertEquals("$label: the frame is the one the form makes with no answer", expected, h.client.frames.single().copy(requestId = ""))
                }
            }
        }
    }

    /** Positive control for the baseline: the block is the web's builder output, never empty. */
    @Test
    fun theBaselineFramesCarryTheWebsBlock() = runTest {
        val pr = baseline("checkout-pr")
        assertEquals(true, pr.useWorktree)
        assertEquals("checkout-pr", pr.worktree?.mode)
        assertEquals(42L, pr.worktree?.prNumber)
        val branch = baseline("checkout-branch")
        assertEquals("feat/x", branch.worktree?.branch)
        val off = baseline("branch-off")
        assertEquals("branch-off", off.worktree?.mode)
        assertNull(off.worktree?.baseRef)
        assertEquals("origin/dev", baseline("branch-off", "origin/dev").worktree?.baseRef)
    }

    @Test
    fun localSendsAtOnceWithNoBlock() = runTest {
        val h = harness()
        assertEquals(DraftSubmitResult.Sent, h.model.submit(A))
        assertEquals(false, h.client.frames.single().useWorktree)
        assertNull(h.client.frames.single().worktree)
    }

    @Test
    fun theInterimPickerSendsAnIsolatedCreateAtOnce() = runTest {
        val h = harness()
        h.isolate("checkout-pr", repo(hasSetup = true))
        h.model.setWorktreeField(WorktreeField.Pr, "42")
        assertEquals(DraftSubmitResult.Sent, h.model.submitChoice(NewSessionChoice("claude", "claude", null), A))
        assertEquals(42L, h.client.frames.single().worktree?.prNumber)
    }

    @Test
    fun aNewAnswerAfterSendChangesNothing() = runTest {
        val h = harness()
        h.isolate("branch-off", repo(hasSetup = true))
        assertEquals(DraftSubmitResult.Sent, h.model.submit(A))
        assertTrue(h.model.onWorktreeSource(h.client.source(repo(hasSetup = false))))
        assertEquals("", h.model.state.value.error)
        assertTrue(h.model.state.value.creating)
        assertEquals(1, h.client.frames.size)
    }

    @Test
    fun aServerSwitchDropsTheQuestionAndTheAnswer() = runTest {
        val h = harness()
        h.isolate("branch-off", repo(hasSetup = true))
        assertNotNull(h.model.state.value.worktreeSource)
        h.model.onOrigin("https://b.example:443")
        runCurrent()
        assertNull(h.model.state.value.worktreeSource)
        assertTrue(h.client.frames.isEmpty())
    }
}
