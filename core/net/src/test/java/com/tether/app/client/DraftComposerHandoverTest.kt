package com.tether.app.client

import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.StubClient
import com.tether.app.ui.prefs.DraftStore
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-74gu / ta-9wdf: the draft composer's Session name and handover mode (use-draft-composer.ts:171-186, 391-508),
 * over a hand-driven client. Red first: every test here failed to compile before DraftComposerModel grew the name,
 * `openHandover`, `submitHandover`, `onSessions` and the inline refusal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftComposerHandoverTest {
    private companion object {
        const val A = "https://a.example:443"
    }

    private class Client : StubClient() {
        override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        override val consentOrigin = MutableStateFlow<String?>(A)
        override val linkEpoch = MutableStateFlow(1L)
        override val serverProtocolVersion = MutableStateFlow<Int?>(151)
        override val snapshotSeq = MutableStateFlow(1L)
        override val sessions = MutableStateFlow<List<AgentSession>>(emptyList())
        override val providers = MutableStateFlow(listOf(ProviderInfo("claude", "Claude", "C", true), ProviderInfo("codex", "Codex", "X", true)))
        override val providerCatalog = MutableStateFlow<List<ProviderCatalogEntry>>(emptyList())
        override val providerCatalogLive = MutableStateFlow(false)
        override val workspaceRoot = MutableStateFlow<String?>("/root")
        override val createdSessions = MutableStateFlow<CreatedReply?>(null)
        override val createErrors = MutableStateFlow<CreateErrorReply?>(null)

        val creates = mutableListOf<NewSessionRequest>()
        val requests = mutableListOf<Triple<String, String, JsonObject>>()
        val abandoned = mutableListOf<String>()
        var sendOk = true

        override fun createNewSession(request: NewSessionRequest, expectedOrigin: String?): NewSessionResult {
            creates += request
            return NewSessionResult.Sent
        }

        override fun handoverRequestWithSettings(sessionId: String, requestId: String, successor: JsonObject): Boolean {
            if (!sendOk) return false
            requests += Triple(sessionId, requestId, successor)
            return true
        }

        override fun abandonHandoverRequest(requestId: String) {
            abandoned += requestId
        }

        private var errorSeq = 0L
        fun error(message: String, requestId: String?) {
            createErrors.value = CreateErrorReply(message, ++errorSeq, requestId, linkEpoch.value, null)
        }
    }

    private class Harness(val client: Client, val model: DraftComposerModel, val store: DraftStore, val claimed: MutableList<String>, val succeeded: MutableList<String>)

    private fun TestScope.harness(client: Client = Client()): Harness {
        val claimed = mutableListOf<String>()
        val succeeded = mutableListOf<String>()
        var n = 0
        val store = InMemoryDraftStore()
        val model = DraftComposerModel(
            client = client, draftStore = store, scope = backgroundScope, currentWorkspace = { "/w" },
            newRequestId = { "req-${++n}" }, onHandoverRequested = { claimed += it }, onHandoverSucceeded = { succeeded += it },
        )
        model.onOrigin(A)
        runCurrent()
        return Harness(client, model, store, claimed, succeeded)
    }

    private fun source(block: (AgentSession) -> AgentSession = { it }) = block(
        AgentSession(
            id = "src", provider = "claude", name = "Billing", cwd = "/src", status = "idle", startedAt = 1, updatedAt = 1,
            model = "opus", reasoningEffort = null, permissionMode = "plan", handoverSuccessorName = JsonPrimitive("Billing 2"),
        ),
    )

    private fun Harness.open(session: AgentSession = source()): Boolean {
        client.sessions.value = listOf(session)
        model.refresh()
        return model.openHandover(session)
    }

    private val claudeRows = listOf(
        ProviderCatalogEntry(
            "claude", "claude", "ready",
            listOf(com.tether.app.protocol.SessionModelOption(value = "opus", displayName = "Opus"), com.tether.app.protocol.SessionModelOption(value = "sonnet", displayName = "Sonnet")),
        ),
        ProviderCatalogEntry("codex", "codex", "ready", emptyList()),
    )

    private fun Harness.live() {
        client.providerCatalog.value = claudeRows
        client.providerCatalogLive.value = true
    }

    // ---- the Session name (ordinary New Session, §3, wave-check 5) ----
    @Test fun theNameIsTrimmedAndSentOnlyWhenNonEmptyOnA151Server() = runTest {
        val h = harness()
        h.model.setName("  Billing API  ")
        assertEquals(DraftSubmitResult.Sent, h.model.submitChoice(NewSessionChoice("claude", "claude", null), A))
        assertEquals("Billing API", h.client.creates.single().name?.trim())
        val frame = CreateFrame.build(DraftForm.INITIAL_DRAFT_FORM, ProviderCatalogEntry("claude", "claude", "ready", emptyList()), DraftForm.INITIAL_USER_MODIFIED, "r", "  Billing API  ")
        assertEquals("Billing API", frame.name)
        assertNull("whitespace-only is absent", CreateFrame.build(DraftForm.INITIAL_DRAFT_FORM, ProviderCatalogEntry("claude", "claude", "ready", emptyList()), DraftForm.INITIAL_USER_MODIFIED, "r", "   ").name)
        assertNull(CreateFrame.build(DraftForm.INITIAL_DRAFT_FORM, ProviderCatalogEntry("claude", "claude", "ready", emptyList()), DraftForm.INITIAL_USER_MODIFIED, "r").name)
    }

    @Test fun theNameIsNotSentToAServerBelow151() = runTest {
        val h = harness()
        h.client.serverProtocolVersion.value = 150
        h.model.setName("Billing")
        h.model.submitChoice(NewSessionChoice("claude", "claude", null), A)
        assertNull(h.client.creates.single().name)
    }

    @Test fun theNameIsCappedAt80SurvivesCloseReopenAndIsNeverSavedToPreferences() = runTest {
        val h = harness()
        h.model.setName("x".repeat(120))
        assertEquals(80, h.model.state.value.name.length)
        h.model.setName("keep me")
        h.live(); h.model.refresh()
        assertTrue(h.open())
        h.model.closeHandover()
        assertEquals("keep me", h.model.state.value.name)
        assertTrue(h.model.state.value.nameEdited)
        assertNull(h.model.preferences()["name"])
    }

    @Test fun theNameNeverSplitsASurrogatePairAtTheCap() = runTest {
        val h = harness()
        h.model.setName("a".repeat(79) + "😀")
        assertEquals(79, h.model.state.value.name.length)
    }

    @Test fun theNameClearsAfterASuccessfulCreate() = runTest {
        val h = harness()
        h.model.setName("Billing")
        h.model.submitChoice(NewSessionChoice("claude", "claude", null), A)
        val requestId = h.client.creates.single().requestId
        h.client.createdSessions.value = CreatedReply(source { it.copy(id = "new") }, 1, requestId, h.client.linkEpoch.value, null)
        h.model.onCreated(h.client.createdSessions.value!!)
        assertEquals("", h.model.state.value.name)
        assertFalse(h.model.state.value.nameEdited)
    }

    // ---- open: stash, seed, name hint ----
    @Test fun openingSeedsEverySettingFromTheSourceAndPrefillsTheHint() = runTest {
        val h = harness(); h.live()
        h.model.setText("my draft"); h.model.setName("mine")
        assertTrue(h.open())
        val s = h.model.state.value
        assertEquals("claude", (s.form["key"] as JsStr).value)
        assertEquals("opus", (s.form["model"] as JsStr).value)
        assertEquals("plan", (s.form["mode"] as JsStr).value)
        assertEquals("/src", (s.form["cwd"] as JsStr).value)
        assertEquals("Billing 2", s.name)
        assertFalse(s.nameEdited)
        assertEquals("", s.text)
        assertEquals("Billing", s.handover!!.sourceName)
        assertEquals("Billing 2", s.handover!!.hint)
        assertFalse(s.form["useWorktree"] == com.tether.app.protocol.tree.JsBool.TRUE)
        // a later RESOLVE (catalog / workspace change) cannot overwrite a seeded field
        h.model.refresh()
        assertEquals("/src", (h.model.state.value.form["cwd"] as JsStr).value)
        assertEquals("plan", (h.model.state.value.form["mode"] as JsStr).value)
    }

    @Test fun anEngineMissingFromTheCatalogOpensNothing() = runTest {
        val h = harness(); h.live()
        assertFalse(h.open(source { it.copy(provider = "gemini") }))
        assertNull(h.model.state.value.handover)
    }

    @Test fun closingRestoresTheNormalDraftUntouchedAndNeverWritesPreferences() = runTest {
        val h = harness(); h.live()
        h.model.refresh(); h.model.selectProvider("codex"); h.model.setText("my draft"); h.model.setName("mine")
        val before = h.model.state.value
        val prefsBefore = h.model.preferences()
        assertTrue(h.open())
        h.model.selectModel("sonnet"); h.model.selectMode("acceptEdits", "claude"); h.model.setCwd("/elsewhere")
        assertEquals("picks in handover mode never reach preferences", prefsBefore, h.model.preferences())
        h.model.closeHandover()
        val after = h.model.state.value
        assertNull(after.handover)
        assertEquals(before.form, after.form); assertEquals(before.modified, after.modified)
        assertEquals("my draft", after.text); assertEquals("mine", after.name); assertTrue(after.nameEdited)
    }

    @Test fun anExtraSourceModelIsListedButNeverSaved() = runTest {
        val h = harness(); h.live()
        assertTrue(h.open(source { it.copy(model = "weird-9") }))
        assertTrue(h.model.state.value.entries.first { it.key == "claude" }.models.any { it.value == "weird-9" })
        assertNull(h.model.preferences()["customModels"]?.takeIf { it.toString().contains("weird-9") })
        h.model.closeHandover()
        assertFalse(h.model.state.value.entries.first { it.key == "claude" }.models.any { it.value == "weird-9" })
    }

    // ---- send ----
    @Test fun sendBuildsTheSuccessorAndAFreshRequestIdAndClaimsTheSource() = runTest {
        val h = harness(); h.live()
        h.open()
        h.model.setName("Billing 3")
        assertEquals(HandoverSubmitResult.Sent, h.model.submitHandover())
        val (id, requestId, successor) = h.client.requests.single()
        assertEquals("src", id); assertEquals("req-1", requestId)
        assertEquals("claude", (successor["provider"] as JsonPrimitive).content)
        assertEquals("Billing 3", (successor["name"] as JsonPrimitive).content)
        assertFalse("no prompt", successor.containsKey("prompt")); assertFalse(successor.containsKey("worktree"))
        assertEquals(listOf("src"), h.claimed)
        assertTrue(h.model.state.value.handover!!.pending)
        assertEquals("", h.model.state.value.error)
    }

    @Test fun anUneditedNameIsTheHintAndIsNotSent() = runTest {
        val h = harness(); h.live(); h.open()
        h.model.submitHandover()
        assertFalse(h.client.requests.single().third.containsKey("name"))
    }

    @Test fun aFailedSendSaysReconnectingAndStaysEditable() = runTest {
        val h = harness(); h.live(); h.open()
        h.client.sendOk = false
        assertEquals(HandoverSubmitResult.NotConnected, h.model.submitHandover())
        assertEquals("The secure link is reconnecting — nothing was handed over.", h.model.state.value.error)
        assertFalse(h.model.state.value.handover!!.pending)
        assertTrue(h.claimed.isEmpty())
    }

    @Test fun aV150ServerNeverReceivesASuccessor() = runTest {
        val h = harness(); h.live(); h.open()
        h.client.serverProtocolVersion.value = 150
        assertEquals(HandoverSubmitResult.NotSupported, h.model.submitHandover())
        assertTrue(h.client.requests.isEmpty())
    }

    @Test fun submitTimeReadinessLinesAndAPendingDoubleSend() = runTest {
        val h = harness(); h.live(); h.open()
        h.model.setCwd("   ")
        assertEquals(HandoverSubmitResult.NotReady, h.model.submitHandover())
        assertEquals("Choose a working directory.", h.model.state.value.error)
        h.model.setCwd("/src")
        assertEquals(HandoverSubmitResult.Sent, h.model.submitHandover())
        assertEquals(HandoverSubmitResult.Busy, h.model.submitHandover())
        assertEquals(1, h.client.requests.size)
        // the name field is disabled while pending
        h.model.setName("late")
        assertEquals("Billing 2", h.model.state.value.name)
    }

    @Test fun theOrdinaryCreatePathIsClosedWhileInHandoverMode() = runTest {
        val h = harness(); h.live(); h.open()
        assertEquals(DraftSubmitResult.Busy, h.model.submit(A, requirePrompt = false))
        assertTrue(h.client.creates.isEmpty())
    }

    // ---- the lifecycle: success, refusal, abandon, reconnect, blocked ----
    @Test fun successIsTheSourceRowShowingPendingOrAStageAndTheComposerCloses() = runTest {
        for (row in listOf<(AgentSession) -> AgentSession>({ it.copy(handoverPending = true) }, { it.copy(handoverPending = true, handoverStage = JsonPrimitive("requested")) })) {
            val h = harness(); h.live(); h.open()
            h.model.submitHandover()
            h.model.onSessions(listOf(source()))
            assertTrue("no change yet", h.model.state.value.handover!!.pending)
            h.model.onSessions(listOf(source(row)))
            assertNull(h.model.state.value.handover)
            assertEquals(listOf("src"), h.succeeded)
            assertEquals(1L, h.model.state.value.handoverSucceeded)
            assertTrue("a success is no abandon", h.client.abandoned.isEmpty())
        }
    }

    @Test fun aStageWithoutPendingDoesNotCloseTheComposerTheWebClosesOnPendingOnly() = runTest {
        val h = harness(); h.live(); h.open()
        h.model.submitHandover()
        h.model.onSessions(listOf(source { it.copy(handoverStage = JsonPrimitive("requested")) }))
        assertTrue(h.model.state.value.handover!!.pending)
        assertTrue(h.succeeded.isEmpty())
    }

    @Test fun anInlineRefusalKeepsEveryValueAndReadsCouldntHandOver() = runTest {
        val h = harness(); h.live(); h.open()
        h.model.selectModel("sonnet")
        h.model.submitHandover()
        h.client.error("engine_unavailable: nope", "other")
        assertNotNull(h.model.state.value.handover!!.pendingRequestId); assertEquals("", h.model.state.value.error)
        h.client.error("engine_unavailable: nope", "req-1")
        assertTrue(h.model.onCreateError(h.client.createErrors.value!!))
        val s = h.model.state.value
        assertEquals("Couldn't hand over: Tether can't start that engine. Pick another and try again.", s.error)
        assertFalse(s.handover!!.pending)
        assertEquals("sonnet", (s.form["model"] as JsStr).value)
        assertEquals("Billing 2", s.name)
        // an unknown / unparseable code reads generically
        h.model.submitHandover()
        h.client.error("some_new_code: x", "req-2")
        h.model.onCreateError(h.client.createErrors.value!!)
        assertEquals(Handover.GENERIC_REFUSAL, h.model.state.value.error)
        h.model.submitHandover()
        h.client.error("garbage without a code", "req-3")
        h.model.onCreateError(h.client.createErrors.value!!)
        assertEquals(Handover.GENERIC_REFUSAL, h.model.state.value.error)
    }

    @Test fun closingWhilePendingAbandonsTheRequestToTheToast() = runTest {
        val h = harness(); h.live(); h.open()
        h.model.submitHandover()
        h.model.closeHandover()
        assertEquals(listOf("req-1"), h.client.abandoned)
        assertNull(h.model.state.value.handover)
    }

    @Test fun closingWhenNothingIsPendingAbandonsNothing() = runTest {
        val h = harness(); h.live(); h.open()
        h.model.closeHandover()
        assertTrue(h.client.abandoned.isEmpty())
    }

    @Test fun reconnectWhilePendingReconcilesOnlyFromANewerSnapshot() = runTest {
        val h = harness(); h.live(); h.open()
        h.model.submitHandover()
        h.client.connection.value = ConnectionState.Disconnected
        h.model.onLink(ConnectionState.Disconnected, 1)
        h.client.connection.value = ConnectionState.Connected
        h.model.onLink(ConnectionState.Connected, 2)
        h.model.onSessions(listOf(source())) // connected fires BEFORE the fresh snapshot: still the old row
        assertTrue("not editable until a snapshot newer than the drop", h.model.state.value.handover!!.pending)
        h.client.snapshotSeq.value = 2
        h.model.onSessions(listOf(source()))
        assertFalse("editable again, no error", h.model.state.value.handover!!.pending)
        assertEquals("", h.model.state.value.error)
    }

    @Test fun reconnectThenASnapshotShowingTheStageIsASuccess() = runTest {
        val h = harness(); h.live(); h.open()
        h.model.submitHandover()
        h.model.onLink(ConnectionState.Disconnected, 1)
        h.client.snapshotSeq.value = 2
        h.model.onSessions(listOf(source { it.copy(handoverPending = true, handoverStage = JsonPrimitive("scheduled")) }))
        assertNull(h.model.state.value.handover)
        assertEquals(listOf("src"), h.succeeded)
    }

    @Test fun theBlockedLineFollowsTheSourceAndLiftsWhenItIsEligibleAgain() = runTest {
        val h = harness(); h.live(); h.open()
        assertNull(h.model.state.value.handover!!.blockedLine)
        h.model.onSessions(listOf(source { it.copy(handoverBlockedReason = JsonPrimitive("plan_mode")) }))
        assertEquals("“Billing” changed while you were choosing. Plan mode can't hand over — leave plan mode first.", h.model.state.value.handover!!.blockedLine)
        assertEquals(HandoverSubmitResult.Blocked, h.model.submitHandover())
        assertTrue(h.client.requests.isEmpty())
        h.model.onSessions(listOf(source()))
        assertNull(h.model.state.value.handover!!.blockedLine)
        h.model.onSessions(emptyList())
        assertEquals("“Billing” changed while you were choosing. This session can't hand over right now.", h.model.state.value.handover!!.blockedLine)
    }

    // ---- the Codex "Current (custom)" mode ----
    @Test fun aCodexSourceMatchingNoPresetOpensOnCurrentAndAProviderChangeDropsIt() = runTest {
        val h = harness(); h.live()
        val codex = source { it.copy(provider = "codex", model = null, permissionMode = null, sandboxPolicy = "read-only", approvalPolicy = "never") }
        h.client.sessions.value = listOf(codex); h.model.refresh()
        assertTrue(h.model.openHandover(codex))
        assertEquals("current", (h.model.state.value.form["mode"] as JsStr).value)
        assertNotNull(h.model.state.value.handover!!.codexCurrentOption)
        h.model.submitHandover()
        val sent = h.client.requests.single().third
        assertEquals("read-only", (sent["sandboxPolicy"] as JsonPrimitive).content)
        assertEquals("never", (sent["approvalPolicy"] as JsonPrimitive).content)
        assertTrue(sent["approvalsReviewer"] is JsonNull)
        h.model.onCreateError(CreateErrorReply("x_y: z", 99, "req-1", 1, null)) // clears pending via a refusal
        h.model.selectProvider("claude")
        assertNull("a provider change drops the held triple", h.model.state.value.handover!!.codexCurrent)
    }
}
