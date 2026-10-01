package com.tether.app.ui.sidebar

import com.tether.app.client.CreateErrorReply
import com.tether.app.client.CreatedReply
import com.tether.app.client.NewSessionChoice
import com.tether.app.client.NewSessionGuard
import com.tether.app.client.NewSessionRequest
import com.tether.app.client.NewSessionResult
import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.client.TetherClient
import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.SessionModelOption
import kotlinx.coroutines.flow.MutableStateFlow

/** ta-895: the New session picker's seeded catalog (the owner's case: two Claude accounts). */
object NewSessionFixtures {
    const val ORIGIN = "https://tether.test"

    val providers = listOf(
        ProviderInfo("claude", "Claude", "C", true),
        ProviderInfo("codex", "Codex", "X", true),
        ProviderInfo("opencode", "OpenCode", "O", true),
        ProviderInfo("acp", "ACP", "A", true),
        ProviderInfo("pi", "Pi", "P", false),
    )

    private fun models(n: Int) = (1..n).map { SessionModelOption("m$it", "Model $it") }

    /** lib/provider-catalog.mjs order: the enabled profiles, then one default row per non-acp engine. */
    val catalog = listOf(
        ProviderCatalogEntry("work", "claude", "ready", models(3), label = "Claude (work)", profileId = "work", extends = "claude"),
        ProviderCatalogEntry("personal", "claude", "ready", models(3), label = "Claude (personal)", profileId = "personal", extends = "claude"),
        ProviderCatalogEntry("gemini-acp", "acp", "ready", models(1), label = "Gemini (ACP)", profileId = "gemini-acp", extends = "acp"),
        ProviderCatalogEntry("claude", "claude", "ready", models(4), label = "Claude"),
        ProviderCatalogEntry("codex", "codex", "error", emptyList(), label = "Codex", error = "codex app-server exited (code 1)"),
        ProviderCatalogEntry("opencode", "opencode", "loading", emptyList(), label = "OpenCode"),
        ProviderCatalogEntry("pi", "pi", "unavailable", emptyList(), label = "Pi"),
    )

    /**
     * r2 (F1): two accounts added with the same long nickname. The server labels both
     * `Claude Code (<nickname>)` and only the second id gets "-2" (lib/claude-accounts.mjs
     * deriveAccountId, addAccount), so the label is the same and the ids differ at their END.
     */
    const val NICKNAME = "Marketing and growth experiments team"
    const val LOOK_ALIKE_ID = "claude-marketing-and-growth-experiments-team"
    val lookAlike = listOf(
        ProviderCatalogEntry(LOOK_ALIKE_ID, "claude", "ready", models(3), label = "Claude Code ($NICKNAME)", profileId = LOOK_ALIKE_ID, extends = "claude"),
        ProviderCatalogEntry("$LOOK_ALIKE_ID-2", "claude", "ready", models(3), label = "Claude Code ($NICKNAME)", profileId = "$LOOK_ALIKE_ID-2", extends = "claude"),
        ProviderCatalogEntry("claude", "claude", "ready", models(4), label = "Claude Code"),
    )

    /** r2 (F2): rows the client would refuse whatever their status says. */
    val refused = listOf(
        ProviderCatalogEntry("dup", "claude", "ready", emptyList(), label = "Claude (dup)", profileId = "dup", extends = "claude"),
        ProviderCatalogEntry("dup", "claude", "ready", emptyList(), label = "Claude (dup again)", profileId = "dup", extends = "claude"),
        ProviderCatalogEntry("odd", "claude", "ready", emptyList(), label = "Odd", profileId = "not-the-key"),
        ProviderCatalogEntry("future", "claude", "paused", emptyList(), label = "Future", profileId = "future"),
        ProviderCatalogEntry("claude", "claude", "ready", emptyList(), label = "Claude"),
    )
}

/**
 * A [TetherClient] for the picker: the catalog and its liveness are set by the test, every catalog
 * request is counted, and [createNewSession] resolves the tap as the real client does (the drawn
 * origin, then [NewSessionGuard.resolve] against the live catalog: ta-8cv, the web's whole frame)
 * and records the `create`, unless [nextResult] forces an outcome. ta-8cv: the server's answers are
 * the test's to give ([answer], [refuse]), each echoing a requestId as the server does.
 */
class PickerClient(
    val inner: RecordingClient = RecordingClient(),
) : TetherClient by inner {
    override val providers = MutableStateFlow(NewSessionFixtures.providers)
    override val providerCatalog = MutableStateFlow(NewSessionFixtures.catalog)
    override val providerCatalogLive = MutableStateFlow(true)
    override val consentOrigin = MutableStateFlow<String?>(NewSessionFixtures.ORIGIN)
    override val createdSessions = MutableStateFlow<CreatedReply?>(null)
    override val createErrors = MutableStateFlow<CreateErrorReply?>(null)
    override val sessions = MutableStateFlow<List<AgentSession>>(emptyList())

    var catalogRequests = 0
    val creates = mutableListOf<ClientMessage.Create>()
    val choices = mutableListOf<NewSessionChoice>()
    var nextResult: NewSessionResult? = null
    private var seq = 0L

    override fun requestProviderCatalog(): Boolean {
        catalogRequests += 1
        return true
    }

    override fun createNewSession(request: NewSessionRequest, expectedOrigin: String?): NewSessionResult {
        choices += request.choice
        nextResult?.let { return it }
        if (expectedOrigin != consentOrigin.value) return NewSessionResult.NotLive
        val live = if (providerCatalogLive.value) providerCatalog.value else null
        val frame = NewSessionGuard.resolve(request, live, providers.value) ?: return NewSessionResult.NotOffered
        creates += frame
        return NewSessionResult.Sent
    }

    /** The server's `created` for the last create (or for [requestId]), naming session [id]. */
    fun answer(id: String = "new-${creates.size}", requestId: String? = creates.last().requestId) {
        val session = AgentSession(id = id, provider = creates.last().provider, name = id, cwd = "/w", status = "ready", startedAt = 1, updatedAt = 1)
        sessions.value = listOf(session) + sessions.value
        createdSessions.value = CreatedReply(session, ++seq, requestId)
    }

    /** The server's `error` (for the last create unless [requestId] says otherwise). */
    fun refuse(message: String, requestId: String? = creates.last().requestId) {
        createErrors.value = CreateErrorReply(message, ++seq, requestId)
    }
}
