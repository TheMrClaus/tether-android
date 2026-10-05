package com.tether.app.ui.draft

import com.tether.app.client.AttachmentSendResult
import com.tether.app.client.CreateErrorReply
import com.tether.app.client.CreatedReply
import com.tether.app.client.NewSessionGuard
import com.tether.app.client.NewSessionRequest
import com.tether.app.client.NewSessionResult
import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.client.ProviderRefreshResult
import com.tether.app.client.TetherClient
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.ui.shell.ShellConsentClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull

/** ta-abm: the new-session sheet's seeded catalog (ta-895's case: two Claude accounts first). */
object DraftFixtures {
    const val SERVER = "https://tether.test"
    const val ORIGIN = "https://tether.test"
    const val ROOT = "/srv/ws"

    val providers = listOf(
        ProviderInfo("claude", "Claude", "C", true),
        ProviderInfo("codex", "Codex", "X", true),
        ProviderInfo("opencode", "OpenCode", "O", true),
        ProviderInfo("pi", "Pi", "P", false),
    )

    private fun models(n: Int) = (1..n).map { SessionModelOption("m$it", "Model $it") }

    val catalog = listOf(
        ProviderCatalogEntry("work", "claude", "ready", models(3), label = "Claude (work)", profileId = "work", extends = "claude"),
        ProviderCatalogEntry("personal", "claude", "ready", models(3), label = "Claude (personal)", profileId = "personal", extends = "claude"),
        ProviderCatalogEntry("claude", "claude", "ready", models(4), label = "Claude"),
        ProviderCatalogEntry("codex", "codex", "ready", models(2), label = "Codex"),
        ProviderCatalogEntry("opencode", "opencode", "loading", emptyList(), label = "OpenCode"),
        ProviderCatalogEntry("pi", "pi", "unavailable", emptyList(), label = "Pi"),
    )
}

/**
 * A [TetherClient] for the sheet. [createNewSession] resolves the draft as the real client does
 * (the drawn origin, then [NewSessionGuard.resolve] against the live catalog: the web's whole frame)
 * and records the `create`; the first message is recorded by [sendFirst] / [sendAttachments]; the
 * server's answers are the test's to give ([answer], [refuse]). [failOnSend]: every wire call throws
 * (the goldens' writer: a golden that sends anything fails).
 */
class DraftTestClient(
    val inner: ShellConsentClient = ShellConsentClient(),
    private val failOnSend: Boolean = false,
) : TetherClient by inner {
    override val providers = MutableStateFlow(DraftFixtures.providers)
    override val providerCatalog = MutableStateFlow(DraftFixtures.catalog)
    override val providerCatalogLive = MutableStateFlow(true)
    override val consentOrigin = MutableStateFlow<String?>(DraftFixtures.ORIGIN)
    val server = MutableStateFlow<String?>(DraftFixtures.SERVER)
    override val serverUrl: StateFlow<String?> get() = server
    override val workspaceRoot = MutableStateFlow<String?>(DraftFixtures.ROOT)
    override val directories = MutableStateFlow<DirectoryListing?>(null)
    override val createdSessions = MutableStateFlow<CreatedReply?>(null)
    override val createErrors = MutableStateFlow<CreateErrorReply?>(null)
    override val createdReplies = createdSessions.filterNotNull()
    override val createErrorReplies = createErrors.filterNotNull()
    override val sessions = MutableStateFlow<List<AgentSession>>(emptyList())
    override val liveSessions: StateFlow<Set<String>> get() = inner.live

    /** T8.4: the GitHub issues / pull requests reads (answers set by the test). */
    val github = FakeGitHubWork()
    override val githubWork: com.tether.app.client.GitHubWorkSource get() = github

    /** ta-2uq: the socket the sheet is drawn on; [newSocket] replaces it. */
    override val linkEpoch = MutableStateFlow(1L)

    /** ta-2uq: every `refresh-providers` that went out: (row key, socket). */
    val refreshes = CopyOnWriteArrayList<Pair<String, Long>>()

    @Volatile var catalogRequests = 0
    val creates = CopyOnWriteArrayList<ClientMessage.Create>()
    val firstSends = CopyOnWriteArrayList<Pair<String, String>>()
    val attachmentSends = CopyOnWriteArrayList<Triple<String, String, List<Attachment>>>()
    val browsed = CopyOnWriteArrayList<String?>()
    private var seq = 0L

    private fun refuseInGolden(what: String) {
        if (failOnSend) throw AssertionError("a golden must not send anything ($what)")
    }

    override fun requestProviderCatalog(): Boolean {
        refuseInGolden("refresh catalog")
        catalogRequests += 1
        return true
    }

    /** ta-2uq: as the real client: the drawn socket and a row of its live catalog (ta-coik.4: no throttle, as the web). */
    override fun refreshProviders(key: String, expectedEpoch: Long): ProviderRefreshResult {
        refuseInGolden("refresh-providers")
        if (expectedEpoch != linkEpoch.value) return ProviderRefreshResult.NotConnected
        if (!providerCatalogLive.value) return ProviderRefreshResult.NotOffered
        providerCatalog.value.singleOrNull { it.key == key } ?: return ProviderRefreshResult.NotOffered
        refreshes += key to expectedEpoch
        return ProviderRefreshResult.Sent
    }

    /** ta-2uq: the server pushes a catalog on the current socket. */
    fun push(entries: List<ProviderCatalogEntry>) {
        providerCatalog.value = entries
        providerCatalogLive.value = true
    }

    /** ta-2uq: the socket is replaced: its catalog is no longer live and its refreshes are dropped. */
    fun newSocket() {
        providerCatalogLive.value = false
        linkEpoch.value = linkEpoch.value + 1
    }

    override fun createNewSession(request: NewSessionRequest, expectedOrigin: String?): NewSessionResult {
        refuseInGolden("create")
        if (expectedOrigin != consentOrigin.value) return NewSessionResult.NotLive
        val live = if (providerCatalogLive.value) providerCatalog.value else null
        val frame = NewSessionGuard.resolve(request, live, providers.value) ?: return NewSessionResult.NotOffered
        creates += frame
        return NewSessionResult.Sent
    }

    override fun sendFirst(sessionId: String, text: String, expectedOrigin: String, expectedEpoch: Long): Boolean {
        refuseInGolden("first send")
        firstSends += sessionId to text
        return true
    }

    override fun sendAttachments(
        sessionId: String,
        text: String,
        attachments: List<Attachment>,
        mention: com.tether.app.protocol.DelegateMention?,
        expectedOrigin: String?,
        expectedEpoch: Long?,
    ): AttachmentSendResult {
        refuseInGolden("attachments")
        attachmentSends += Triple(sessionId, text, attachments)
        return AttachmentSendResult.Sent
    }

    /** ta-23f: every `worktree-inspect` that went out: (cwd, requestId, socket). */
    val inspects = CopyOnWriteArrayList<Triple<String, String, Long>>()
    private val sources = kotlinx.coroutines.flow.MutableSharedFlow<com.tether.app.client.WorktreeSourceReply>(extraBufferCapacity = 64)
    override val worktreeSources: kotlinx.coroutines.flow.Flow<com.tether.app.client.WorktreeSourceReply> get() = sources

    /** ta-23f: as the real client: only on the socket the composer asked on. */
    override fun inspectWorktree(cwd: String, requestId: String, expectedEpoch: Long): Boolean {
        refuseInGolden("worktree-inspect")
        if (expectedEpoch != linkEpoch.value) return false
        inspects += Triple(cwd, requestId, expectedEpoch)
        return true
    }

    /** ta-23f: the server's `worktree-source`, for the last inspect unless told otherwise, on the current socket. */
    fun answerInspect(info: com.tether.app.client.WorktreeSourceInfo, requestId: String? = inspects.last().second, epoch: Long = linkEpoch.value) {
        check(sources.tryEmit(com.tether.app.client.WorktreeSourceReply(info, requestId, epoch)))
    }

    /** ta-m7ef: every setup check (an intent `worktree-inspect`) that went out: (cwd, block, requestId, socket). */
    val setupChecks = CopyOnWriteArrayList<List<Any>>()

    override fun inspectSetup(cwd: String, worktree: com.tether.app.protocol.WorktreeCreateRequest, requestId: String, expectedEpoch: Long): Boolean {
        refuseInGolden("worktree-inspect (setup check)")
        if (expectedEpoch != linkEpoch.value) return false
        setupChecks += listOf(cwd, worktree, requestId, expectedEpoch)
        return true
    }

    /** ta-m7ef: the server's `worktree-source` for the last setup check, on the current socket. */
    fun answerSetup(info: com.tether.app.client.WorktreeSourceInfo, requestId: String = setupChecks.last()[2] as String, epoch: Long = linkEpoch.value) {
        check(sources.tryEmit(com.tether.app.client.WorktreeSourceReply(info, requestId, epoch)))
    }

    override fun browse(cwd: String?) {
        refuseInGolden("browse")
        browsed += cwd
    }

    /** The server's `created` for the last create (live at once on this socket), naming session [id]. */
    fun answer(id: String = "new-${creates.size}", requestId: String? = creates.last().requestId) {
        val session = AgentSession(id = id, provider = creates.last().provider, name = id, cwd = creates.last().cwd ?: "/w", status = "ready", startedAt = 1, updatedAt = 1)
        sessions.value = listOf(session) + sessions.value
        inner.live.value = inner.live.value + id
        createdSessions.value = CreatedReply(session, ++seq, requestId)
    }

    /** T8.5: the takeover's frames, as the real client sends them (use-tether.ts 90fbb9f :1889-1913). */
    override val handoffBriefs = MutableStateFlow<Map<String, com.tether.app.client.HandoffBriefReading>>(emptyMap())
    val briefRequests = CopyOnWriteArrayList<Pair<String, String>>()
    val handoffs = CopyOnWriteArrayList<Triple<String, String, String>>()

    override fun requestHandoffBrief(sourceId: String, targetId: String): Boolean {
        refuseInGolden("handoff-brief")
        briefRequests += sourceId to targetId
        return true
    }

    override fun handoff(sourceId: String, targetId: String, engineText: String): Boolean {
        refuseInGolden("handoff")
        handoffs += Triple(sourceId, targetId, engineText)
        return true
    }

    override fun clearHandoffBrief(sourceId: String) {
        handoffBriefs.value = handoffBriefs.value - sourceId
    }

    /** T8.5: the server's `handoff-brief` reply for [sourceId]. */
    fun answerBrief(sourceId: String, instruction: String) {
        handoffBriefs.value = handoffBriefs.value + (sourceId to com.tether.app.client.HandoffBriefReading(sourceId, kotlinx.serialization.json.JsonObject(emptyMap()), "# digest", instruction))
    }

    /** The server's `error` for the last create. */
    fun refuse(message: String, requestId: String? = creates.last().requestId) {
        createErrors.value = CreateErrorReply(message, ++seq, requestId)
    }
}
