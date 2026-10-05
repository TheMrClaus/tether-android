package com.tether.app.client

import com.tether.app.protocol.Attachment
import com.tether.app.protocol.WorktreeCreateRequest
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
 * ta-m7ef (tether PR #241, use-draft-composer.ts 1bf4a465): the new-session composer's setup consent.
 * An isolated Send asks the server what THIS create would run (`worktree-inspect` with its own `worktree`
 * block) and creates with the consent the answer carries: at once with "none" when nothing is declared,
 * after the operator approves when hooks are, never when the intent did not resolve. Any edit, a dropped
 * link, a refused check or Cancel ends the check; an answer that is not this check's is ignored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftSetupConsentTest {
    private companion object {
        const val A = "https://a.example:443"
        val DIGEST = "sha256:" + "ab".repeat(32)
        val COMMIT = "0123456789abcdef0123456789abcdef01234567"
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

        val frames = mutableListOf<com.tether.app.protocol.ClientMessage.Create>()
        val checks = mutableListOf<Triple<String, WorktreeCreateRequest, String>>()

        override fun createNewSession(request: NewSessionRequest, expectedOrigin: String?): NewSessionResult {
            if (expectedOrigin != consentOrigin.value) return NewSessionResult.NotLive
            if (request.linkEpoch != linkEpoch.value) return NewSessionResult.NotConnected
            frames += NewSessionGuard.resolve(request, null, providers.value) ?: return NewSessionResult.NotOffered
            return NewSessionResult.Sent
        }

        override fun sendFirst(sessionId: String, text: String, expectedOrigin: String, expectedEpoch: Long): Boolean = true
        override fun send(sessionId: String, text: String, attachments: List<Attachment>) = Unit

        override fun inspectSetup(cwd: String, worktree: WorktreeCreateRequest, requestId: String, expectedEpoch: Long): Boolean {
            if (connection.value != ConnectionState.Connected || expectedEpoch != linkEpoch.value) return false
            checks += Triple(cwd, worktree, requestId)
            return true
        }
    }

    private class Harness(val client: Client, val model: DraftComposerModel) {
        val state get() = model.state.value
        val token get() = client.checks.last().third
        fun reply(info: WorktreeSourceInfo, requestId: String? = token, epoch: Long = client.linkEpoch.value) =
            model.onWorktreeSource(WorktreeSourceReply(info, requestId, epoch))
        fun error(message: String = "refused", seq: Long, requestId: String? = null) =
            model.onCreateError(CreateErrorReply(message, seq, requestId, client.linkEpoch.value, A))
    }

    private fun TestScope.harness(mode: String = "branch-off"): Harness {
        val client = Client()
        var n = 0
        val model = DraftComposerModel(client, InMemoryDraftStore(), backgroundScope, { "/w" }, newRequestId = { "req-${++n}" })
        model.onOrigin(A)
        runCurrent()
        model.refresh()
        check(model.selectProvider("claude"))
        model.setText("hello")
        check(model.selectIsolation(mode))
        return Harness(client, model)
    }

    private fun repo(preview: WorktreeSetupPreview?, isRepo: Boolean = true) = WorktreeSourceInfo(cwd = "/w", isRepo = isRepo, remote = "origin", setupPreview = preview)

    private fun preview(
        commands: List<String>? = listOf("pnpm install"),
        teardown: List<String>? = listOf("rm -rf out"),
        consent: String? = DIGEST,
        digest: String? = DIGEST,
        commit: String? = COMMIT,
        baseRef: String? = "origin/main",
        error: String? = null,
        portScript: String? = null,
        portScriptWellFormed: Boolean = true,
        hidden: Boolean = false,
    ) = WorktreeSetupPreview("branch-off", "origin", baseRef, commit, commands, teardown, portScript, portScriptWellFormed, null, hidden, digest, consent, null, error)

    private val none = preview(commands = emptyList(), teardown = emptyList(), consent = "none", digest = null)

    // --- the three outcomes ----------------------------------------------------------------------

    @Test
    fun nothingDeclaredCreatesAtOnceWithConsentNone() = runTest {
        val h = harness()
        assertEquals(DraftSubmitResult.Checking, h.model.submit(A))
        assertTrue(h.state.setupChecking)
        assertTrue(h.reply(repo(none)))
        assertEquals(1, h.client.frames.size)
        assertEquals("none", h.client.frames.single().setupConsent)
        assertNull(h.state.setupConfirmation)
        assertTrue(h.state.creating)
    }

    @Test
    fun declaredHooksWaitForTheOperatorsApprovalAndSendExactlyTheReportedConsent() = runTest {
        val h = harness("checkout-pr")
        h.model.setWorktreeField(WorktreeField.Pr, "42")
        assertEquals(DraftSubmitResult.Checking, h.model.submit(A))
        assertEquals(WorktreeCreateRequest("checkout-pr", prNumber = 42), h.client.checks.single().second)
        assertTrue(h.reply(repo(preview().copy(mode = "checkout-pr"))))
        val confirmation = h.state.setupConfirmation!!
        assertFalse(h.state.setupChecking)
        assertEquals(listOf("pnpm install"), confirmation.approval.commands)
        assertEquals(listOf("rm -rf out"), confirmation.approval.teardown)
        assertEquals(COMMIT, confirmation.approval.commit)
        assertEquals("origin/main", confirmation.approval.baseRef)
        assertEquals(42L, confirmation.prNumber)
        assertTrue("nothing is created before the approval", h.client.frames.isEmpty())
        assertEquals("a second Send while it waits is ignored", DraftSubmitResult.Busy, h.model.submit(A))
        assertEquals(DraftSubmitResult.Sent, h.model.confirmSetup())
        assertEquals(DIGEST, h.client.frames.single().setupConsent)
        assertEquals(42L, h.client.frames.single().worktree?.prNumber)
        assertNull(h.state.setupConfirmation)
        assertTrue(h.state.creating)
        assertNull("it cannot be approved twice", h.model.confirmSetup())
        assertEquals(1, h.client.frames.size)
    }

    @Test
    fun anIntentThatDidNotResolveRefusesWithTheServersWordsAndCreatesNothing() = runTest {
        val refusals = listOf(
            repo(preview(error = "No such branch: feat/x")) to "No such branch: feat/x",
            repo(null, isRepo = false) to "That folder is not a Git repository, so it cannot host an isolated session.",
            repo(null) to SETUP_CHECK_FAILED_COPY,
            // "none" with commands listed would be a server bug: never waved through.
            repo(preview(consent = "none", commands = listOf("x"))) to SETUP_CHECK_FAILED_COPY,
            repo(preview(consent = "none", commands = emptyList(), teardown = null)) to SETUP_CHECK_FAILED_COPY,
            repo(preview(consent = "none", commands = emptyList(), teardown = emptyList(), portScript = "/p")) to SETUP_CHECK_FAILED_COPY,
            // A digest that is not the digest, a missing commit, a malformed list or port script.
            repo(preview(digest = "sha256:" + "cd".repeat(32))) to SETUP_CHECK_FAILED_COPY,
            repo(preview(consent = "sha256:xyz", digest = "sha256:xyz")) to SETUP_CHECK_FAILED_COPY,
            repo(preview(commit = null)) to SETUP_CHECK_FAILED_COPY,
            repo(preview(baseRef = null)) to SETUP_CHECK_FAILED_COPY,
            repo(preview(commands = null)) to SETUP_CHECK_FAILED_COPY,
            repo(preview(portScriptWellFormed = false)) to SETUP_CHECK_FAILED_COPY,
            repo(preview(consent = null, digest = null)) to SETUP_CHECK_FAILED_COPY,
        )
        for ((info, message) in refusals) {
            val h = harness()
            assertEquals(DraftSubmitResult.Checking, h.model.submit(A))
            assertTrue(h.reply(info))
            assertEquals(message, h.state.error)
            assertFalse(h.state.setupChecking)
            assertNull(h.state.setupConfirmation)
            assertTrue("$message: nothing created", h.client.frames.isEmpty())
            assertFalse(h.state.creating)
            // The operator may press Send again.
            assertEquals(DraftSubmitResult.Checking, h.model.submit(A))
        }
    }

    @Test
    fun aPortScriptIsShownForApprovalEvenWithNoCommands() = runTest {
        val h = harness()
        h.model.submit(A)
        assertTrue(h.reply(repo(preview(commands = emptyList(), teardown = emptyList(), portScript = "/opt/ports.sh"))))
        assertEquals("/opt/ports.sh", h.state.setupConfirmation!!.approval.portScript)
    }

    // --- cancellation ------------------------------------------------------------------------------

    @Test
    fun cancelGoesBackToTheDraftAndCreatesNothing() = runTest {
        val h = harness()
        h.model.submit(A)
        h.reply(repo(preview()))
        h.model.cancelSetup()
        assertNull(h.state.setupConfirmation)
        assertFalse(h.state.setupChecking)
        assertNull(h.model.confirmSetup())
        assertTrue(h.client.frames.isEmpty())
        assertEquals("hello", h.state.text)
    }

    @Test
    fun anyEditToWhatTheCheckWasMadeForCancelsIt() = runTest {
        val edits: List<Pair<String, (DraftComposerModel) -> Unit>> = listOf(
            "text" to { m -> m.setText("hello!") },
            "folder" to { m -> m.setCwd("/other") },
            "isolation block" to { m -> m.setWorktreeField(WorktreeField.BaseRef, "origin/dev") },
            "isolation mode" to { m -> m.selectIsolation("checkout-branch") },
            "isolation off" to { m -> m.selectIsolation("local") },
            "attachments" to { m -> m.setAttachments(listOf(Attachment("a.png", "image/png", "AAAA"))) },
        )
        for ((label, edit) in edits) {
            for (confirming in listOf(false, true)) {
                val h = harness()
                h.model.submit(A)
                if (confirming) h.reply(repo(preview()))
                edit(h.model)
                assertEquals("$label confirming=$confirming", DRAFT_SETUP_CHECK_CANCELLED_COPY, h.state.error)
                assertFalse(label, h.state.setupChecking)
                assertNull(label, h.state.setupConfirmation)
                assertNull(label, h.model.confirmSetup())
                assertTrue(label, h.client.frames.isEmpty())
            }
        }
    }

    @Test
    fun anEditThatChangesNothingTheCheckDependsOnKeepsIt() = runTest {
        val h = harness()
        h.model.submit(A)
        h.model.setText("hello") // the same text
        assertTrue(h.state.setupChecking)
        assertEquals("", h.state.error)
    }

    @Test
    fun aDroppedLinkOrAReplacedSocketCancelsTheCheck() = runTest {
        val h = harness()
        h.model.submit(A)
        h.client.connection.value = ConnectionState.Disconnected
        h.model.onLink(ConnectionState.Disconnected, 1)
        assertEquals(DRAFT_LINK_DROPPED_COPY, h.state.error)
        assertFalse(h.state.setupChecking)
        // A replaced socket too, while confirming.
        h.client.connection.value = ConnectionState.Connected
        h.model.clearError()
        assertEquals(DraftSubmitResult.Checking, h.model.submit(A))
        h.reply(repo(preview()))
        assertNotNull(h.state.setupConfirmation)
        h.client.linkEpoch.value = 2
        h.model.onLink(ConnectionState.Connected, 2)
        assertNull(h.state.setupConfirmation)
        assertEquals(DRAFT_LINK_DROPPED_COPY, h.state.error)
        assertTrue(h.client.frames.isEmpty())
    }

    // --- replies that are not this check's -------------------------------------------------------

    @Test
    fun anAnswerThatIsNotThisChecksOwnIsIgnored() = runTest {
        val h = harness()
        h.model.submit(A)
        assertFalse("no echo", h.reply(repo(none), requestId = null))
        assertFalse("another token", h.reply(repo(none), requestId = "forged"))
        assertFalse("another socket", h.reply(repo(none), epoch = 2))
        assertTrue(h.client.frames.isEmpty())
        assertTrue(h.state.setupChecking)
        assertTrue("positive control", h.reply(repo(none)))
        // A second answer to the same check is void: the create is already out.
        assertFalse(h.reply(repo(none)))
        assertEquals(1, h.client.frames.size)
    }

    @Test
    fun aRefusalOfTheCheckEndsItAndSaysWhy() = runTest {
        val h = harness()
        h.model.submit(A)
        // An older error (seq not newer) and an error echoing another request do not end it.
        assertFalse(h.error(seq = 0))
        assertFalse(h.error(seq = 5, requestId = "someone-else"))
        assertTrue(h.state.setupChecking)
        // A validator refusal carries no requestId; a refusal with the check's own token also ends it.
        assertTrue(h.error("Folder refused", seq = 1))
        assertEquals("Folder refused", h.state.error)
        assertFalse(h.state.setupChecking)
        assertEquals(DraftSubmitResult.Checking, h.model.submit(A))
        assertTrue(h.error("Refused for this one", seq = 2, requestId = h.token))
        assertEquals("Refused for this one", h.state.error)
        assertTrue(h.client.frames.isEmpty())
    }

    @Test
    fun theCheckIsNotSentOffTheLiveSocket() = runTest {
        val h = harness()
        h.client.connection.value = ConnectionState.Disconnected
        assertEquals(DraftSubmitResult.NotConnected, h.model.submit(A))
        assertEquals(DRAFT_NOT_CONNECTED_COPY, h.state.error)
        assertFalse(h.state.setupChecking)
        assertTrue(h.client.checks.isEmpty())
    }

    @Test
    fun aLocalCreateNeverChecks() = runTest {
        val h = harness()
        h.model.selectIsolation("local")
        assertEquals(DraftSubmitResult.Sent, h.model.submit(A))
        assertTrue(h.client.checks.isEmpty())
        assertNull(h.client.frames.single().setupConsent)
    }
}
