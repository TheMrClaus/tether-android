package com.tether.app.client

import com.tether.app.protocol.Attachment
import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.ui.prefs.DraftStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** ta-8cv: what the draft composer shows (hooks/use-draft-composer.ts DraftComposerState). */
data class DraftComposerState(
    /** lib/draft-form.ts DraftFormState. */
    val form: JsObj = DraftForm.INITIAL_DRAFT_FORM,
    /** lib/draft-form.ts DraftUserModified. */
    val modified: JsObj = DraftForm.INITIAL_USER_MODIFIED,
    val text: String = "",
    /** Staged for the first turn; memory only (never persisted, as on the web). */
    val attachments: List<Attachment> = emptyList(),
    /** A `create` is in flight: the composer is locked until its own reply, an error or a drop. */
    val creating: Boolean = false,
    /** The inline error ("" = none): readiness, a refusal, the server's own words, a dropped link. */
    val error: String = "",
    /** How many creates of this draft have been answered by their own `created` (the picker closes on one). */
    val completed: Long = 0,
) {
    /**
     * ta-8cv r2 (security F3): redacted. The prompt, the attachments and the form (its key is a
     * profile id on a profile row; its folder and picks) never reach a log.
     */
    override fun toString(): String =
        "DraftComposerState(text=${text.length} chars, attachments=${attachments.size}, creating=$creating, " +
            "error=${if (error.isEmpty()) "none" else "set"}, completed=$completed)"
}

/** ta-8cv: what became of a submit. */
enum class DraftSubmitResult {
    /** The `create` went out; the draft is [DraftComposerState.creating] until its reply. */
    Sent,

    /** A readiness reason stands ([DraftComposerState.error] says which); nothing was sent. */
    NotReady,

    /** A create is already in flight; nothing was sent (the web ignores the second submit). */
    Busy,
    NotConnected,
    NotLive,
    NotOffered,
}

/**
 * ta-8cv (T8.1 slice 1): the new-session draft composer's engine, a port of the web's
 * hooks/use-draft-composer.ts (887c222): the [DraftForm] store, per-provider preferences, the ordered
 * readiness reasons, submit, the reply matching, the first message and its orphan recovery.
 *
 * - **Preferences** are per server origin ([onOrigin], [DraftStore.readDraftPreferences]); one
 *   server's picks never seed another's draft. A pick made while the origin's record is still being
 *   read is applied on top of it when it lands, never lost and never clobbering it.
 * - **Submit** ([submit]) mints a fresh `requestId` per attempt and hands the draft to
 *   [TetherClient.createNewSession], which re-checks the link, the server and the row under its
 *   lock and builds the web's frame ([CreateFrame]). A create never goes through the outbox and is
 *   never retried: whatever kills it stays dead, and the draft keeps its text.
 * - **Matching**: a `created` is this draft's ONLY when it echoes the in-flight `requestId` (v76)
 *   AND its seq is newer than the one seen at submit ([DraftForm.replyMatchesRequest],
 *   [DraftForm.replyIsFresh]); a resume's reply (no token) or a stale create's never is. An `error`
 *   unlocks only under the same two rules ([onCreateError]); any other error leaves it locked.
 * - **Link drop**: the socket the create went out on closing, or being replaced ([onLink]), returns
 *   the draft with its text, and says so; its reply can never arrive on another socket. r2: unless
 *   that socket already answered it ([TetherClient.createReply], recorded with the frame): then the
 *   answer stands, whichever collector runs first, and a reply carried on a later socket never
 *   completes it ([answers]).
 * - **No answer** (r2): after [CREATE_REPLY_TIMEOUT_MS] the draft unlocks, keeps its text and says
 *   the session may already exist; the create is never resent.
 * - **First message**: after the matched `created`, the prompt goes through the client's own send
 *   paths ([TetherClient.sendFirst], durable and bound to the create's server and socket; with attachments [TetherClient.sendAttachments], once the
 *   new session is live on the same socket). If it cannot go out, the prompt is saved as the new
 *   session's draft ([saveSessionDraft]) and is never resent (attachments are dropped, as on the web).
 *
 * Room left for later slices: the handoff / takeover create (T8.5, issue #144: the first action
 * becomes `handoff`), the GitHub work dialog's prefill (T8.4: [setText], [setCwd]) and the worktree
 * setup confirmation (slice 5: before [submit] sends).
 *
 * Confined to the main thread: every method is called from the view model's collectors or a tap.
 */
class DraftComposerModel(
    private val client: TetherClient,
    private val draftStore: DraftStore,
    private val scope: CoroutineScope,
    /** The operator's current workspace (null: the server's root). */
    private val currentWorkspace: () -> String?,
    /** Stores [text] as [sessionId]'s composer draft on [origin]; throws when it could not. */
    private val saveSessionDraft: suspend (origin: String, sessionId: String, text: String) -> Unit =
        { origin, sessionId, text -> draftStore.write(origin, sessionId, text) },
    /** A fresh correlation token per create attempt (use-draft-composer.ts newIdempotencyKey). */
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
    /** How long a first turn with attachments waits for the new session to be live. */
    private val firstSendLiveTimeoutMs: Long = FIRST_SEND_LIVE_TIMEOUT_MS,
    /** r2 (security F2): how long a create may wait for its own reply before the draft unlocks. */
    private val createReplyTimeoutMs: Long = CREATE_REPLY_TIMEOUT_MS,
    /** The draft's own create made [sessionId] (whichever path completed it): the caller selects it. */
    private val onSessionCreated: (sessionId: String) -> Unit = {},
) {
    private val _state = MutableStateFlow(DraftComposerState())
    val state: StateFlow<DraftComposerState> = _state.asStateFlow()

    /** The rows the form resolves against (the live catalog, or the base providers' default rows). */
    private var entries: List<ProviderCatalogEntry> = emptyList()

    private var preferences: JsObj = JsObj.EMPTY
    private var prefsOrigin: String? = null

    /** Picks made while [prefsOrigin]'s record is being read, re-applied on top of it (null: loaded). */
    private var prefsPending: MutableList<(JsObj) -> JsObj>? = null
    private val prefsWrites = Channel<Pair<String, JsObj>>(Channel.UNLIMITED)

    private var submitting = false
    private var pending: PendingCreate? = null
    private var replyTimeout: Job? = null

    private class PendingCreate(
        val requestId: String,
        val prompt: String,
        val attachments: List<Attachment>,
        val origin: String,
        val linkEpoch: Long,
        val createdSnapshot: Long,
        val errorSnapshot: Long,
    )

    init {
        // One ordered writer: the latest preferences per origin, in order.
        scope.launch {
            for ((origin, prefs) in prefsWrites) {
                try {
                    draftStore.writeDraftPreferences(origin, prefs)
                } catch (_: Exception) {
                    // draft-preferences.mjs writeDraftPreferences: best effort; the in-memory copy still serves.
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Preferences (lib/draft-preferences.mjs), per server origin
    // ---------------------------------------------------------------------------------------------

    /** The configured server is [origin] (canonical; null = none): its own preferences, read once. */
    fun onOrigin(origin: String?) {
        if (origin == prefsOrigin) return
        prefsOrigin = origin
        preferences = JsObj.EMPTY
        prefsPending = if (origin != null) mutableListOf() else null
        refresh()
        if (origin == null) return
        scope.launch {
            val stored = try {
                draftStore.readDraftPreferences(origin)
            } catch (_: Exception) {
                JsObj.EMPTY
            }
            if (prefsOrigin != origin) return@launch // the server changed while reading
            val picks = prefsPending.orEmpty()
            prefsPending = null
            preferences = picks.fold(stored) { acc, pick -> pick(acc) }
            if (picks.isNotEmpty()) prefsWrites.trySend(origin to preferences)
            refresh()
        }
    }

    private fun persist(pick: (JsObj) -> JsObj) {
        preferences = pick(preferences)
        prefsPending?.add(pick)
        val origin = prefsOrigin ?: return
        if (prefsPending == null) prefsWrites.trySend(origin to preferences)
    }

    /** The current origin's preferences (tests, and the composer sheet's custom models). */
    fun preferences(): JsObj = preferences

    // ---------------------------------------------------------------------------------------------
    // The form (lib/draft-form.ts reduceDraftForm)
    // ---------------------------------------------------------------------------------------------

    private fun dispatch(action: JsObj) {
        _state.update { s ->
            val store = DraftForm.reduceDraftForm(JsObj.of("form" to s.form, "userModified" to s.modified), action) as JsObj
            s.copy(form = store["form"] as JsObj, modified = store["userModified"] as JsObj)
        }
    }

    private fun entriesJs(): JsArr =
        DraftForm.mergeCustomModelsIntoEntries(JsArr.of(entries.map { it.toDraftEntry() }), preferences["customModels"])

    private fun entryJs(key: String): JsValue? = entriesJs().firstOrNull { (it as? JsObj)?.get("key") == JsStr(key) }

    private fun workspaceCwd(): String? = currentWorkspace()?.takeIf { it.isNotEmpty() } ?: client.workspaceRoot.value?.takeIf { it.isNotEmpty() }

    /**
     * use-draft-composer.ts:162 RESOLVE: re-seed the form from the catalog the CURRENT socket
     * delivered (else the base providers), the preferences and the workspace. The userModified guard
     * keeps every field the operator touched; no provider is ever pre-selected.
     */
    fun refresh() {
        val live = if (client.providerCatalogLive.value) client.providerCatalog.value else null
        entries = NewSessionGuard.draftEntries(live, client.providers.value)
        dispatch(
            JsObj.of(
                "type" to JsStr("RESOLVE"),
                "entries" to entriesJs(),
                "preferences" to preferences,
                "initial" to JsObj.of("cwd" to workspaceCwd()?.let(::JsStr)),
            ),
        )
    }

    fun entryForKey(key: String): ProviderCatalogEntry? = entries.firstOrNull { it.key == key }

    fun setText(text: String) = _state.update { it.copy(text = text) }

    fun setAttachments(attachments: List<Attachment>) = _state.update { it.copy(attachments = attachments) }

    /** The picker's row tap (SET_PROVIDER_FROM_USER): mode/model/effort re-resolve from that row's preferences. */
    fun selectProvider(key: String): Boolean {
        val entry = entryJs(key) ?: return false
        if ((entry as? JsObj)?.get("status") == JsStr("unavailable")) return false
        dispatch(JsObj.of("type" to JsStr("SET_PROVIDER_FROM_USER"), "entry" to entry, "preferences" to preferences))
        return true
    }

    /** use-draft-composer.ts selectModel. */
    fun selectModel(modelId: String) {
        val key = formKey()
        val entry = entryJs(key) ?: return
        dispatch(JsObj.of("type" to JsStr("SET_MODEL_FROM_USER"), "modelId" to JsStr(modelId), "entry" to entry, "preferences" to preferences))
        persist { DraftForm.mergeDraftPreferences(it, JsStr(key), JsObj.of("model" to JsStr(modelId))) }
    }

    /** use-draft-composer.ts selectProviderAndModel (one atomic provider + model pick). */
    fun selectProviderAndModel(key: String, modelId: String) {
        val entry = entryJs(key) ?: return
        if ((entry as? JsObj)?.get("status") == JsStr("unavailable")) return
        dispatch(JsObj.of("type" to JsStr("SET_PROVIDER_AND_MODEL_FROM_USER"), "entry" to entry, "modelId" to JsStr(modelId), "preferences" to preferences))
        persist { DraftForm.mergeDraftPreferences(it, JsStr(key), JsObj.of("model" to JsStr(modelId))) }
    }

    /** use-draft-composer.ts selectEffort. */
    fun selectEffort(effort: String) {
        dispatch(JsObj.of("type" to JsStr("SET_REASONING_EFFORT_FROM_USER"), "effort" to JsStr(effort)))
        val key = formKey()
        if (key.isNotEmpty()) persist { DraftForm.mergeDraftPreferences(it, JsStr(key), JsObj.of("reasoningEffort" to JsStr(effort))) }
    }

    /** use-draft-composer.ts selectMode: an elevated mode is an ordinary choice (owner 2026-10-02). */
    fun selectMode(mode: String) {
        dispatch(JsObj.of("type" to JsStr("SET_MODE_FROM_USER"), "mode" to JsStr(mode)))
        val key = formKey()
        if (key.isNotEmpty()) persist { DraftForm.mergeDraftPreferences(it, JsStr(key), JsObj.of("mode" to JsStr(mode))) }
    }

    /** use-draft-composer.ts selectAutoMode. */
    fun selectAutoMode(autoMode: Boolean) {
        dispatch(JsObj.of("type" to JsStr("SET_AUTO_MODE_FROM_USER"), "autoMode" to JsBool.of(autoMode)))
        val key = formKey()
        if (key.isNotEmpty()) persist { DraftForm.mergeDraftPreferences(it, JsStr(key), JsObj.of("autoMode" to JsBool.of(autoMode))) }
    }

    fun setCwd(cwd: String) = dispatch(JsObj.of("type" to JsStr("SET_CWD_FROM_USER"), "cwd" to JsStr(cwd)))

    fun setUseWorktree(useWorktree: Boolean) = dispatch(JsObj.of("type" to JsStr("SET_WORKTREE_FROM_USER"), "useWorktree" to JsBool.of(useWorktree)))

    /** v98: the isolation block (worktreeMode / worktreeBaseRef / worktreeBranch / worktreeSlug / worktreePr). */
    fun setWorktreeOptions(options: JsObj) = dispatch(JsObj.of("type" to JsStr("SET_WORKTREE_OPTIONS_FROM_USER"), "options" to options))

    /** use-draft-composer.ts addCustomModel (issue #45): a hand-typed id, client-only. */
    fun addCustomModel(entryKey: String, modelId: String) {
        if (modelId.isBlank()) return
        persist { DraftForm.addCustomModelPref(it, JsStr(entryKey), JsStr(modelId)) as JsObj }
        refresh()
    }

    fun removeCustomModel(entryKey: String, modelId: String) {
        persist { DraftForm.removeCustomModelPref(it, JsStr(entryKey), JsStr(modelId)) as JsObj }
        refresh()
    }

    /** The picker shows its own words for a new opening (UI only; the web's error lives until the next submit). */
    fun clearError() = _state.update { it.copy(error = "") }

    private fun formKey(): String = (_state.value.form["key"] as? JsStr)?.value ?: ""

    private fun formStr(key: String): String = (_state.value.form[key] as? JsStr)?.value ?: ""

    // ---------------------------------------------------------------------------------------------
    // Readiness and submit (use-draft-composer.ts:266-368)
    // ---------------------------------------------------------------------------------------------

    /**
     * The first reason this draft cannot be submitted, in the web's order (prompt or attachment →
     * provider → models still loading → working folder → isolation input), or "" when it can.
     * [requirePrompt] false is the interim picker's (slice 1): its rows start a session with no
     * first message, as the retired new-session dialog did.
     */
    fun readiness(requirePrompt: Boolean = true): String {
        val s = _state.value
        if (requirePrompt && s.text.isBlank() && s.attachments.isEmpty()) return READINESS_NEED_PROMPT
        val entry = entryForKey(formKey())
        if (entry == null || entry.status == "unavailable") return READINESS_NEED_PROVIDER
        if (entry.status == "loading") return READINESS_MODELS_LOADING
        if (formStr("cwd").isBlank()) return READINESS_NEED_CWD
        if (s.form["useWorktree"] == JsBool.TRUE && DraftForm.buildWorktreeCreateRequest(s.form) == null) {
            return if (formStr("worktreeMode") == "checkout-pr") READINESS_NEED_PR else READINESS_NEED_BRANCH
        }
        return ""
    }

    /**
     * use-draft-composer.ts submit: drawn for [expectedOrigin] (the server the composer was drawn
     * for). One attempt, one fresh requestId; never retried.
     */
    fun submit(expectedOrigin: String?, requirePrompt: Boolean = true): DraftSubmitResult {
        if (_state.value.creating || submitting) return DraftSubmitResult.Busy
        val reason = readiness(requirePrompt)
        if (reason.isNotEmpty()) {
            _state.update { it.copy(error = reason) }
            return DraftSubmitResult.NotReady
        }
        val entry = entryForKey(formKey()) ?: return DraftSubmitResult.NotReady
        if (expectedOrigin == null) {
            _state.update { it.copy(error = DRAFT_NOT_LIVE_COPY) }
            return DraftSubmitResult.NotLive
        }
        val s = _state.value
        val requestId = newRequestId()
        val epoch = client.linkEpoch.value
        // Snapshot BEFORE the send: the reply can land (on the socket's thread) before it returns.
        val created = PendingCreate(
            requestId = requestId,
            prompt = s.text.trim(),
            attachments = s.attachments.toList(),
            origin = expectedOrigin,
            linkEpoch = epoch,
            createdSnapshot = client.createdSessions.value?.seq ?: 0L,
            errorSnapshot = client.createErrors.value?.seq ?: 0L,
        )
        submitting = true
        pending = created
        _state.update { it.copy(creating = true, error = "") }
        val request = NewSessionRequest(NewSessionChoice(entry.key, entry.provider, entry.profileId), s.form, s.modified, requestId, epoch)
        val result = try {
            client.createNewSession(request, expectedOrigin)
        } finally {
            submitting = false
        }
        if (result == NewSessionResult.Sent) {
            // r2 (security F2): a create whose reply never comes (lost, or never sent) unlocks in time.
            if (pending === created) {
                replyTimeout?.cancel()
                replyTimeout = scope.launch {
                    delay(createReplyTimeoutMs)
                    onReplyTimeout(created)
                }
            }
            return DraftSubmitResult.Sent
        }
        // Refused before the wire: nothing is in flight (unless a reply raced in, which it cannot here).
        if (pending === created) {
            pending = null
            _state.update { it.copy(creating = false, error = refusalCopy(result)) }
        }
        return when (result) {
            NewSessionResult.NotConnected -> DraftSubmitResult.NotConnected
            NewSessionResult.NotLive -> DraftSubmitResult.NotLive
            NewSessionResult.NotOffered -> DraftSubmitResult.NotOffered
            NewSessionResult.Sent -> DraftSubmitResult.Sent
        }
    }

    /**
     * ta-895's picker on this engine (slice 1): select the row drawn ([choice], as the live catalog
     * has it now) and submit with no first message. The explicit mode and sandbox ride along.
     */
    fun submitChoice(choice: NewSessionChoice, expectedOrigin: String?): DraftSubmitResult {
        if (_state.value.creating || submitting) return DraftSubmitResult.Busy
        refresh()
        val entry = entryForKey(choice.key)
        if (entry == null || entry.provider != choice.provider || entry.profileId != choice.profileId || !selectProvider(choice.key)) {
            _state.update { it.copy(error = DRAFT_NOT_OFFERED_COPY) }
            return DraftSubmitResult.NotOffered
        }
        return submit(expectedOrigin, requirePrompt = false)
    }

    private fun refusalCopy(result: NewSessionResult): String = when (result) {
        NewSessionResult.NotConnected -> DRAFT_NOT_CONNECTED_COPY
        NewSessionResult.NotLive -> DRAFT_NOT_LIVE_COPY
        NewSessionResult.NotOffered -> DRAFT_NOT_OFFERED_COPY
        NewSessionResult.Sent -> ""
    }

    // ---------------------------------------------------------------------------------------------
    // Replies (use-draft-composer.ts:375-471)
    // ---------------------------------------------------------------------------------------------

    /** The in-flight create, cleared with its reply timer. */
    private fun settle(p: PendingCreate) {
        if (pending === p) pending = null
        replyTimeout?.cancel()
        replyTimeout = null
    }

    /**
     * r2 (verifier P4): a reply belongs to [p] only when it echoes [p]'s requestId (the authoritative
     * gate), is newer than the submit (belt and braces), and came on the socket the create went out
     * on (a matching token on a later socket never completes it; a client that does not stamp its
     * replies is held to its current socket).
     */
    private fun answers(p: PendingCreate, requestId: String?, seq: Long, snapshot: Long, linkEpoch: Long?): Boolean {
        if (!DraftForm.replyMatchesRequest(JsStr(p.requestId), requestId?.let(::JsStr))) return false
        if (!DraftForm.replyIsFresh(JsNum(snapshot.toDouble()), JsNum(seq.toDouble()))) return false
        return (linkEpoch ?: client.linkEpoch.value) == p.linkEpoch
    }

    /**
     * A `created` reply. Returns the new session's id when it is THIS draft's create (and tells
     * [onSessionCreated], which selects it), else null and nothing changes. Then the first message
     * goes out, once.
     */
    fun onCreated(reply: CreatedReply): String? {
        val p = pending ?: return null
        if (!_state.value.creating) return null
        if (!answers(p, reply.requestId, reply.seq, p.createdSnapshot, reply.linkEpoch)) return null
        return complete(p, reply)
    }

    private fun complete(p: PendingCreate, reply: CreatedReply): String {
        settle(p)
        val sessionId = reply.session.id
        when {
            p.prompt.isEmpty() && p.attachments.isEmpty() ->
                _state.update { it.copy(creating = false, completed = it.completed + 1) }
            p.attachments.isEmpty() -> {
                _state.update { it.copy(creating = false, completed = it.completed + 1) }
                // r2 (security F1): the durable send, recorded only if, in the same step under the
                // client's lock, the outbox and the live socket are still the create's server and
                // socket; a first transmission now, at most once under its own key.
                if (client.sendFirst(sessionId, p.prompt, p.origin, p.linkEpoch)) onFirstSent() else orphan(p, sessionId)
            }
            else -> {
                // Attachments go once, on the same socket, when the new session is live there (the
                // client's own rule for an attachment send); the composer stays locked meanwhile.
                _state.update { it.copy(completed = it.completed + 1) }
                scope.launch {
                    val live = withTimeoutOrNull(firstSendLiveTimeoutMs) {
                        combine(client.liveSessions, client.linkEpoch) { ids, epoch -> sessionId in ids || epoch != p.linkEpoch }.first { it }
                        sessionId in client.liveSessions.value && client.linkEpoch.value == p.linkEpoch
                    } == true
                    val sent = live && client.sendAttachments(sessionId, p.prompt, p.attachments, null, p.origin) == AttachmentSendResult.Sent
                    _state.update { it.copy(creating = false) }
                    if (sent) onFirstSent() else orphan(p, sessionId)
                }
            }
        }
        onSessionCreated(sessionId)
        return sessionId
    }

    /** The first message is on its way: the draft starts over (RESET re-seeds the workspace, issue #78). */
    private fun onFirstSent() {
        _state.update { it.copy(text = "", attachments = emptyList()) }
        dispatch(JsObj.of("type" to JsStr("RESET"), "cwd" to workspaceCwd()?.let(::JsStr)))
    }

    /**
     * Orphan case: the session exists but its first message did not reach the wire. The prompt is
     * saved as the new session's composer draft, so one manual Send recovers it; it is never resent.
     * Attachments are dropped (never persisted). If the draft cannot be saved, the text stays here.
     */
    private fun orphan(p: PendingCreate, sessionId: String) {
        scope.launch {
            try {
                saveSessionDraft(p.origin, sessionId, p.prompt)
            } catch (_: Exception) {
                return@launch // storage unavailable: the draft text stays here instead
            }
            _state.update { it.copy(text = "", attachments = emptyList()) }
        }
    }

    /**
     * An `error` frame. Unlocks the draft and shows the server's message ONLY when it answers the
     * in-flight create ([answers]); true when it did.
     */
    fun onCreateError(reply: CreateErrorReply): Boolean {
        val p = pending ?: return false
        if (!_state.value.creating) return false
        if (!answers(p, reply.requestId, reply.seq, p.errorSnapshot, reply.linkEpoch)) return false
        fail(p, reply)
        return true
    }

    private fun fail(p: PendingCreate, reply: CreateErrorReply) {
        settle(p)
        // The web needs a message to unlock; a matching refusal whose words cleaned to nothing still
        // unlocks here (never stuck), with the app's own words.
        _state.update { it.copy(creating = false, error = reply.message.ifEmpty { DRAFT_REFUSED_COPY }) }
    }

    /**
     * r2 (security F2): the answer the client already recorded for [p] (a reply handled before its
     * socket went, whose collector has not run yet). True when it settled [p].
     */
    private fun settleFromRecord(p: PendingCreate): Boolean {
        when (val record = client.createReply(p.requestId)) {
            is CreateReplyRecord.Created -> if (answers(p, record.reply.requestId, record.reply.seq, p.createdSnapshot, record.reply.linkEpoch)) {
                complete(p, record.reply)
                return true
            }
            is CreateReplyRecord.Failed -> if (answers(p, record.reply.requestId, record.reply.seq, p.errorSnapshot, record.reply.linkEpoch)) {
                fail(p, record.reply)
                return true
            }
            null -> Unit
        }
        return false
    }

    /**
     * The link changed. A create in flight on a socket that is not live any more (closed, or
     * replaced: [linkEpoch] moved) returns to the draft with the prompt intact, unless that socket
     * already answered it (r2: then the answer stands, whichever collector runs first: a created
     * session is never reported as not created; its first message is then the new session's draft).
     */
    fun onLink(connection: ConnectionState, linkEpoch: Long) {
        val p = pending ?: return
        if (connection == ConnectionState.Connected && linkEpoch == p.linkEpoch) return
        if (settleFromRecord(p)) return
        settle(p)
        _state.update { it.copy(creating = false, error = DRAFT_LINK_DROPPED_COPY) }
    }

    /** r2 (security F2): no answer in time. Never resent; the session may exist (the operator checks). */
    private fun onReplyTimeout(p: PendingCreate) {
        if (pending !== p) return
        if (settleFromRecord(p)) return
        settle(p)
        _state.update { it.copy(creating = false, error = DRAFT_REPLY_TIMEOUT_COPY) }
    }

    companion object {
        const val FIRST_SEND_LIVE_TIMEOUT_MS = 20_000L

        /** r2 (security F2): a create's reply is awaited this long, then the draft unlocks. */
        const val CREATE_REPLY_TIMEOUT_MS = 30_000L
    }
}

// use-draft-composer.ts readiness, verbatim.
const val READINESS_NEED_PROMPT = "Type a message or attach an image to start the session."
const val READINESS_NEED_PROVIDER = "Choose a provider first."
const val READINESS_MODELS_LOADING = "Models are still loading."
const val READINESS_NEED_CWD = "Choose a working directory."
const val READINESS_NEED_PR = "Enter the pull request number to check out."
const val READINESS_NEED_BRANCH = "Enter the branch to check out."

/** use-draft-composer.ts: `send` returned false. */
const val DRAFT_NOT_CONNECTED_COPY = "The secure link is reconnecting — the session was not created."

/** use-draft-composer.ts: the link dropped before `created`. */
const val DRAFT_LINK_DROPPED_COPY = "The secure link dropped before the session was created — your message was not sent."

/** ta-895: the draft was drawn for another server than the one now connected. */
const val DRAFT_NOT_LIVE_COPY = "The server changed. Nothing was created; pick again."

/** ta-895: the live catalog no longer offers the row as drawn. */
const val DRAFT_NOT_OFFERED_COPY = "This server no longer offers that choice. Nothing was created; pick again from the updated list."

/** ta-8cv r2: no reply to the create in time; the session may exist all the same. */
const val DRAFT_REPLY_TIMEOUT_COPY =
    "The server has not answered yet, so the session may already exist. Check the session list before you try again."

/** ta-8cv: the server refused this create and its words cleaned to nothing. */
const val DRAFT_REFUSED_COPY = "The server did not create the session."
