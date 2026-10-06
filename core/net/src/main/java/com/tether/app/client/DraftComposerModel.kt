package com.tether.app.client

import com.tether.app.protocol.Attachment
import com.tether.app.protocol.WorktreeCreateRequest
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
    /**
     * Staged for the first turn; memory only (never persisted, as on the web). ta-abm: each with its
     * stable id and raw size (the composer's chips, the web's AttachmentDraft).
     */
    val staged: List<StagedAttachment> = emptyList(),
    /** A `create` is in flight: the composer is locked until its own reply, an error or a drop. */
    val creating: Boolean = false,
    /** The inline error ("" = none): readiness, a refusal, the server's own words, a dropped link. */
    val error: String = "",
    /** How many creates of this draft have been answered by their own `created` (the picker closes on one). */
    val completed: Long = 0,
    /**
     * ta-2uq: the rows the model browser lists (use-draft-composer.ts `entries: mergedEntries`): the
     * live catalog of the current socket (else the base providers' default rows), each with this
     * server's valid custom model ids appended as models ([CustomModelId]).
     */
    val entries: List<ProviderCatalogEntry> = emptyList(),
    /** ta-2uq: this server's custom model ids per row key, valid ones only (the browser's Custom section). */
    val customModels: Map<String, List<String>> = emptyMap(),
    /**
     * ta-23f: the `worktree-source` that answered THIS folder's inspect on THIS socket (requestId,
     * folder and socket all matched), else null (isolation off, not asked yet, or no answer yet).
     */
    val worktreeSource: WorktreeSourceInfo? = null,
    /**
     * T8.5 (dashboard.tsx 90fbb9f :237-245 `takeoverSourceId`): the limited session this draft takes
     * over ("Take over in a new session"), else null. The create's first action is then `handoff`.
     */
    val takeoverSourceId: String? = null,
    /**
     * ta-m7ef (v143, use-draft-composer.ts `setupChecking`): an isolated create asked the server what it would
     * run from the ref it resolves, and the answer has not come. Send is off meanwhile.
     */
    val setupChecking: Boolean = false,
    /**
     * ta-m7ef (use-draft-composer.ts `setupConfirmation`): the setup and teardown the ref declares, waiting for
     * the operator's approval (the create is sent only on [DraftComposerModel.confirmSetup]).
     */
    val setupConfirmation: DraftSetupConfirmation? = null,
) {
    /** The wire attachments of [staged], in order. */
    val attachments: List<Attachment> get() = staged.map { it.attachment }

    /**
     * ta-8cv r2 (security F3): redacted. The prompt, the attachments and the form (its key is a
     * profile id on a profile row; its folder and picks) never reach a log.
     */
    override fun toString(): String =
        "DraftComposerState(text=${text.length} chars, attachments=${staged.size}, creating=$creating, " +
            "error=${if (error.isEmpty()) "none" else "set"}, completed=$completed, entries=${entries.size})"
}

/** What the setup confirmation shows (use-draft-composer.ts DraftSetupConfirmation): the hooks, and the create they belong to. */
data class DraftSetupConfirmation(
    val approval: SetupApproval,
    val prNumber: Long?,
    val branch: String?,
) {
    override fun toString(): String = "DraftSetupConfirmation($approval)"
}

/** ta-8cv: what became of a submit. */
enum class DraftSubmitResult {
    /** ta-m7ef: an isolated create is checking what its setup would run; the create follows the answer. */
    Checking,

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
 *   paths, both bound to the create's server and socket under the client's lock
 *   ([TetherClient.sendFirst], durable; with attachments [TetherClient.sendAttachments] with the
 *   create's epoch, once the new session is live on that socket). If it cannot go out, the prompt is
 *   saved as the new session's draft ([saveSessionDraft]) and is never resent (attachments are
 *   dropped, as on the web).
 * - **Server switch** (ta-2ew): a create is the server's it went to. Once that server is no longer
 *   the configured one ([TetherClient.isConfiguredOrigin]) the create is over, whatever arrives
 *   next: a reply buffered from it, or its recorded answer, never completes it, and a reply stamped
 *   with another server ([CreatedReply.origin]) never answers it. The session it made is opened only
 *   through [TetherClient.attachIfConfigured] ([onSessionCreated] gets the create's origin).
 * - **Effort and Mode** (ta-xki, slice 4; ta-coik.4): [selectMode] / [selectEffort] / [toggleAuto]
 *   are use-draft-composer.ts's: the value is taken as it is and remembered for the row; an elevated
 *   mode is an ordinary choice with no confirmation (owner 2026-10-02). A stored preference rides as
 *   the reducer resolves it (lib/draft-form.ts resolveMode trusts it), as on the web; the server
 *   validates.
 *
 * - **Worktree isolation** (ta-23f, slice 5): while isolation is on, [inspectWorktree] asks what the
 *   folder's repo offers (`worktree-inspect` with a fresh requestId, on the current socket); only the
 *   `worktree-source` echoing that requestId, for that folder, on that socket, is taken
 *   ([onWorktreeSource]); a folder change, isolation off or a new socket drops the question and its
 *   answer. That answer feeds the web's notes ([WorktreeCopy.setupNote]).
 * - **Setup consent** (ta-m7ef, tether #241, use-draft-composer.ts 1bf4a465): an isolated create first asks
 *   what THIS create would run (`worktree-inspect` with its own `worktree` block, [TetherClient.inspectSetup])
 *   and is sent only with the consent the answer carries ([SetupConsentSteps.create]): at once with "none"
 *   when the ref declares nothing, after [confirmSetup] when it declares hooks, never when the intent did
 *   not resolve ([DraftComposerState.error] says why). Any edit to what the check was made for (the
 *   provider, model, mode, folder, isolation block, prompt or attachments), a dropped link or [cancelSetup]
 *   ends it.
 *
 * - **Takeover** (T8.5, issue #144): [setTakeover] puts the draft in takeover mode for a source; the
 *   source's brief, when it lands ([onHandoffBriefs]), seeds the prompt once; the create made in that
 *   mode sends `handoff` (the prompt as engineText) instead of the plain first message
 *   (use-draft-composer.ts 90fbb9f :355-358, :387-414).
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
    /**
     * The draft's own create made [sessionId] on [origin] (whichever path completed it): the caller
     * selects it, only while [origin] is still the configured server (ta-2ew).
     */
    private val onSessionCreated: (sessionId: String, origin: String) -> Unit = { _, _ -> },
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

    /** ta-23f: one `worktree-inspect`: its token, the folder asked about and the socket asked on. */
    private class InspectRequest(val requestId: String, val cwd: String, val epoch: Long)

    /** ta-23f: the inspect in force (null: none asked for the current folder on the current socket). */
    private var inspect: InspectRequest? = null

    /** ta-23f: the inspect [DraftComposerState.worktreeSource] answers (null: no source). */
    private var sourceFor: InspectRequest? = null

    private class PendingCreate(
        val requestId: String,
        val prompt: String,
        val attachments: List<Attachment>,
        val origin: String,
        val linkEpoch: Long,
        val createdSnapshot: Long,
        val errorSnapshot: Long,
        /** T8.5: the takeover source snapshotted with the prompt (use-draft-composer.ts :358). */
        val takeoverFrom: String? = null,
    )

    /** T8.5 (dashboard.tsx :246-249 takeoverPrefillRef): the source whose brief already seeded the prompt. */
    private var takeoverPrefilled: String? = null

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
        // ta-abm: the draft is that server's: its text, folder, picks and attachments go with it (an
        // in-flight create is let go of too; its reply could only come from the server left behind).
        if (prefsOrigin != null) dropDraft()
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
            // lib/draft-preferences.mjs readDraftPreferences: the stored object as it is.
            preferences = picks.fold(stored) { acc, pick -> pick(acc) }
            if (picks.isNotEmpty()) prefsWrites.trySend(origin to preferences)
            refresh()
        }
    }

    private fun dropDraft() {
        pending?.let(::settle)
        submitting = false
        inspect = null
        sourceFor = null
        _state.update { s -> dropAttachments(s).let { DraftComposerState(completed = it.completed) } }
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
        reconcileWorktree()
        reconcileSetupCheck()
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
        val custom = CustomModelId.asMap(preferences["customModels"])
        _state.update { it.copy(entries = mergeCustomModels(entries, custom), customModels = custom) }
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

    fun setText(text: String) {
        _state.update { it.copy(text = text) }
        reconcileSetupCheck()
    }

    /**
     * T8.5: dashboard.tsx :311-336 — [sourceId] for "Take over in a new session" (the prefill is
     * re-armed), null for a manually opened or a cancelled composer.
     */
    fun setTakeover(sourceId: String?) {
        takeoverPrefilled = null
        _state.update { it.copy(takeoverSourceId = sourceId) }
    }

    /**
     * T8.5 (dashboard.tsx :356-372): the takeover source's brief seeds the prompt with its
     * `instruction`, once per takeover, never clobbering what was typed after it.
     */
    fun onHandoffBriefs(briefs: Map<String, HandoffBriefReading>) {
        val source = _state.value.takeoverSourceId ?: return
        val instruction = briefs[source]?.instruction?.takeIf { it.isNotEmpty() } ?: return
        if (takeoverPrefilled == source) return
        takeoverPrefilled = source
        setText(instruction)
    }

    /** Replaces what is staged with [attachments] (each gets a fresh id; its size is its decoded length). */
    fun setAttachments(attachments: List<Attachment>) =
        setStagedAttachments(attachments.map { StagedAttachment(newAttachmentId(), it, decodedSize(it.data)) })

    fun setStagedAttachments(items: List<StagedAttachment>) {
        _state.update { it.copy(staged = items) }
        reconcileSetupCheck()
    }

    private var nextAttachmentId = 1L

    /** A fresh id for a newly staged attachment. */
    fun newAttachmentId(): Long = nextAttachmentId++

    /**
     * ta-abm: bumped whenever the staged attachments are dropped (sent, given up on, the draft
     * dropped with its server): a pick still being read then is discarded, never added to the next.
     */
    var attachmentGeneration: Long = 0L
        private set

    /**
     * ta-abm: the composer's pick, read under [generation]: appended (up to the web's cap) only while
     * nothing dropped the staged set since and no create holds the draft. True when it was added.
     */
    fun addAttachments(items: List<StagedAttachment>, generation: Long): Boolean {
        if (items.isEmpty() || generation != attachmentGeneration || _state.value.creating) return false
        _state.update { it.copy(staged = (it.staged + items).take(com.tether.app.protocol.helpers.AttachmentDraft.MAX_ATTACHMENTS)) }
        reconcileSetupCheck()
        return true
    }

    /**
     * r2 (F2, T7.4 r2 L4b): a sign-out or a revocation drops what is staged and bumps the generation,
     * so a pick still being read is discarded too. The text, folder and pick stay (memory only).
     */
    fun clearAttachments() {
        _state.update { dropAttachments(it) }
        reconcileSetupCheck()
    }

    fun removeAttachment(id: Long) {
        _state.update { s -> s.copy(staged = s.staged.filterNot { it.id == id }) }
        reconcileSetupCheck()
    }

    private fun dropAttachments(s: DraftComposerState): DraftComposerState {
        attachmentGeneration++
        return s.copy(staged = emptyList())
    }

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

    /** use-draft-composer.ts:223-231 selectEffort: taken as it is, and remembered for the row. True when it was taken. */
    fun selectEffort(effort: String): Boolean {
        dispatch(JsObj.of("type" to JsStr("SET_REASONING_EFFORT_FROM_USER"), "effort" to JsStr(effort)))
        val key = formKey()
        if (key.isNotEmpty()) persist { DraftForm.mergeDraftPreferences(it, JsStr(key), JsObj.of("reasoningEffort" to JsStr(effort))) }
        return true
    }

    /**
     * use-draft-composer.ts:233-241 selectMode: the mode as it is, remembered for the row; an elevated
     * mode is an ordinary choice (owner 2026-10-02), with no confirmation. True when it was taken.
     *
     * [drawnFor] is the provider the tapped control was drawn for: a tap that lands after the row's
     * provider changed (a profile re-extended, a catalog push) is stale and changes nothing (the
     * operator taps again on the control drawn now), so an Auto row drawn for Claude never becomes
     * opencode's Auto (`approvalPolicy: never`).
     */
    fun selectMode(mode: String, drawnFor: String): Boolean {
        val provider = entryForKey(formKey())?.provider ?: return false
        if (provider != drawnFor) return false
        dispatch(JsObj.of("type" to JsStr("SET_MODE_FROM_USER"), "mode" to JsStr(mode)))
        val key = formKey()
        if (key.isNotEmpty()) persist { DraftForm.mergeDraftPreferences(it, JsStr(key), JsObj.of("mode" to JsStr(mode))) }
        return true
    }

    /**
     * draft-composer.tsx toggleAuto (opencode's Auto chip): Auto on is `bypassPermissions` (Build +
     * `approvalPolicy: never` on the create), off is Build ("default"). Nothing for a provider without
     * the chip. True when it was taken.
     */
    fun toggleAuto(drawnFor: String): Boolean {
        val provider = entryForKey(formKey())?.provider ?: return false
        if (provider != drawnFor || !DraftModes.hasAutoChip(provider)) return false
        val on = formStr("mode") == ModeVocabulary.AUTO
        return selectMode(if (on) "default" else ModeVocabulary.AUTO, drawnFor)
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

    // ---------------------------------------------------------------------------------------------
    // Worktree isolation (ta-23f, slice 5)
    // ---------------------------------------------------------------------------------------------

    /**
     * draft-composer.tsx WorktreeSelect onChange: "local" turns isolation off; a mode turns it on and
     * picks that mode (SET_WORKTREE_FROM_USER, then SET_WORKTREE_OPTIONS_FROM_USER). Anything else
     * changes nothing. True when taken.
     */
    fun selectIsolation(value: String): Boolean {
        if (value == WorktreeModes.LOCAL) {
            setUseWorktree(false)
            return true
        }
        if (!WorktreeModes.isMode(value)) return false
        setUseWorktree(true)
        setWorktreeOptions(JsObj.of("worktreeMode" to JsStr(value)))
        return true
    }

    /**
     * One WorktreeDetails field, as draft-composer.tsx takes it: a ref, branch or name as typed (the
     * server validates it), the pull request number with its digits only (:756). True when taken.
     */
    fun setWorktreeField(field: WorktreeField, value: String): Boolean {
        val next = when (field) {
            WorktreeField.Pr -> WorktreeDraft.prInput(value)
            else -> value
        }
        setWorktreeOptions(JsObj.of(field.key to JsStr(next)))
        return true
    }

    /**
     * use-draft-composer.ts / draft-composer.tsx: while isolation is on and a folder is chosen, ask the
     * server what its repo offers (`worktree-inspect`), once per folder per socket: the sheet calls
     * this whenever isolation, the folder or the link changes (the web's effect on
     * `[form.useWorktree, form.cwd]`; here a new socket asks again, as the old one's answer is
     * dropped). A new question drops the previous answer. True when an inspect went out.
     */
    fun inspectWorktree(): Boolean {
        if (_state.value.form["useWorktree"] != JsBool.TRUE) return false
        val cwd = formStr("cwd")
        if (cwd.isBlank()) return false
        val epoch = client.linkEpoch.value
        inspect?.let { if (it.cwd == cwd && it.epoch == epoch) return false }
        val request = InspectRequest(newRequestId(), cwd, epoch)
        if (!client.inspectWorktree(cwd, request.requestId, epoch)) return false
        inspect = request
        dropSource()
        return true
    }

    /**
     * A `worktree-source` frame. Taken only when it echoes the inspect in force, came on the socket
     * that inspect went out on (the live one), and the folder asked about is still the draft's, with
     * isolation on; any other (an old folder's, an old request's, an old socket's, one with no echo)
     * changes nothing.
     */
    fun onWorktreeSource(reply: WorktreeSourceReply): Boolean {
        if (onSetupAnswer(reply)) return true
        val request = inspect ?: return false
        if (reply.requestId == null || reply.requestId != request.requestId) return false
        if (reply.linkEpoch != request.epoch || client.linkEpoch.value != request.epoch) return false
        if (_state.value.form["useWorktree"] != JsBool.TRUE || formStr("cwd") != request.cwd) return false
        sourceFor = request
        _state.update { it.copy(worktreeSource = reply.info) }
        return true
    }

    /** The answer no longer stands (a new question, the folder changed, isolation off, a new socket). */
    private fun dropSource() {
        if (sourceFor == null && _state.value.worktreeSource == null) return
        sourceFor = null
        _state.update { it.copy(worktreeSource = null) }
    }

    /** After every form change: a question or answer about another folder (or with isolation off) is dropped. */
    private fun reconcileWorktree() {
        val on = _state.value.form["useWorktree"] == JsBool.TRUE
        val cwd = formStr("cwd")
        inspect?.let { if (!on || it.cwd != cwd) inspect = null }
        sourceFor?.let { if (!on || it.cwd != cwd) dropSource() }
    }

    /**
     * use-draft-composer.ts:184-190 addCustomModel (issue #45): a hand-typed id, client-only, kept in
     * this server's preferences: the web's rule ([CustomModelId.problem]: trimmed, not empty), on a row
     * the browser lists, that the row does not already offer (model-browser.tsx:530-532
     * `alreadyExists`). True when it was added.
     */
    fun addCustomModel(entryKey: String, modelId: String): Boolean {
        if (CustomModelId.problem(modelId) != null) return false
        val id = CustomModelId.normalize(modelId)
        val entry = _state.value.entries.firstOrNull { it.key == entryKey } ?: return false
        if (entry.models.any { it.value == id }) return false
        persist { DraftForm.addCustomModelPref(it, JsStr(entryKey), JsStr(id)) as JsObj }
        refresh()
        return true
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
        // v98: an isolation mode whose required input is missing (ta-23f: or a PR number past the limit).
        return WorktreeDraft.readiness(s.form)
    }

    /**
     * use-draft-composer.ts submit: drawn for [expectedOrigin] (the server the composer was drawn
     * for; null: drawn with no server recorded, ta-coik.69: it goes to the live socket's server). One attempt, one fresh requestId; never retried.
     */
    fun submit(expectedOrigin: String?, requirePrompt: Boolean = true): DraftSubmitResult {
        if (_state.value.creating || submitting || setupCheck != null) return DraftSubmitResult.Busy
        val reason = readiness(requirePrompt)
        if (reason.isNotEmpty()) {
            _state.update { it.copy(error = reason) }
            return DraftSubmitResult.NotReady
        }
        val entry = entryForKey(formKey()) ?: return DraftSubmitResult.NotReady
        // ta-coik.69: a composer drawn with no server recorded (expectedOrigin null: offline) is no other
        // server's: the create goes to the server whose socket is live now (the web's `send` puts it on any
        // open socket); with none live the create is the link's "reconnecting" refusal, not "the server
        // changed". Only a composer drawn for a DIFFERENT server is refused, by the client's origin check.
        val origin = expectedOrigin ?: client.consentOrigin.value
        if (origin == null) {
            _state.update { it.copy(error = DRAFT_NOT_CONNECTED_COPY) }
            return DraftSubmitResult.NotConnected
        }
        // ta-m7ef (use-draft-composer.ts submit): an isolated create first asks what it would run.
        val intent = WorktreeDraft.request(_state.value.form)
        if (_state.value.form["useWorktree"] == JsBool.TRUE && intent != null) return startSetupCheck(origin, entry, CreateFrame.worktreeRequest(intent))
        return send(origin, entry, setupConsent = null)
    }

    /** The one send: the create built from the state as it is NOW, one fresh requestId, never retried. */
    private fun send(expectedOrigin: String, entry: ProviderCatalogEntry, setupConsent: String?): DraftSubmitResult {
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
            takeoverFrom = s.takeoverSourceId,
        )
        submitting = true
        pending = created
        _state.update { it.copy(creating = true, error = "") }
        val request = NewSessionRequest(NewSessionChoice(entry.key, entry.provider, entry.profileId), s.form, s.modified, requestId, epoch, setupConsent)
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
     * has it now) and submit with no first message. The web's mode rides along (and a sandbox tier for Codex only).
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
    // Setup consent (ta-m7ef; use-draft-composer.ts 1bf4a465 SetupCheck, submit, confirmSetup, cancelSetup)
    // ---------------------------------------------------------------------------------------------

    private enum class SetupPhase { Checking, Confirm }

    /**
     * One setup check: everything the create needs is captured at Send, so the frame finally sent is the one
     * the confirmation described. [formKey], [text] and [staged] are what the check was made for; any edit
     * cancels it ([reconcileSetupCheck]).
     */
    private class SetupCheck(
        val phase: SetupPhase,
        val inspectRequestId: String,
        /** The latest error's seq when the inspect went out: a newer requestId-less error ends the check. */
        val errorSeq: Long,
        val origin: String,
        val epoch: Long,
        val entryKey: String,
        val formKey: List<Any?>,
        val text: String,
        val staged: List<StagedAttachment>,
        val block: WorktreeCreateRequest,
        val confirmation: DraftSetupConfirmation?,
    ) {
        fun confirm(confirmation: DraftSetupConfirmation) =
            SetupCheck(SetupPhase.Confirm, inspectRequestId, errorSeq, origin, epoch, entryKey, formKey, text, staged, block, confirmation)
    }

    private var setupCheck: SetupCheck? = null

    /** use-draft-composer.ts setupFormKey: [key, model, effort, mode, cwd, useWorktree, worktree block]. */
    private fun setupFormKey(): List<Any?> {
        val form = _state.value.form
        return listOf(
            formStr("key"), formStr("model"), formStr("reasoningEffort"), formStr("mode"), formStr("cwd"),
            form["useWorktree"] == JsBool.TRUE, WorktreeDraft.request(form)?.let(CreateFrame::worktreeRequest),
        )
    }

    private fun startSetupCheck(expectedOrigin: String, entry: ProviderCatalogEntry, block: WorktreeCreateRequest): DraftSubmitResult {
        val s = _state.value
        val requestId = newRequestId()
        val epoch = client.linkEpoch.value
        val errorSeq = client.createErrors.value?.seq ?: 0L
        if (!client.inspectSetup(formStr("cwd"), block, requestId, epoch)) {
            _state.update { it.copy(error = DRAFT_NOT_CONNECTED_COPY) }
            return DraftSubmitResult.NotConnected
        }
        setupCheck = SetupCheck(SetupPhase.Checking, requestId, errorSeq, expectedOrigin, epoch, entry.key, setupFormKey(), s.text, s.staged, block, null)
        _state.update { it.copy(error = "", setupChecking = true, setupConfirmation = null) }
        return DraftSubmitResult.Checking
    }

    /**
     * The `worktree-source` that answers THIS submit's inspect (matched by its own requestId, on the socket it
     * went out on); any other is not this check's. True when it was.
     */
    private fun onSetupAnswer(reply: WorktreeSourceReply): Boolean {
        val check = setupCheck ?: return false
        if (check.phase != SetupPhase.Checking || reply.requestId == null || reply.requestId != check.inspectRequestId) return false
        if (reply.linkEpoch != check.epoch || client.linkEpoch.value != check.epoch) return false
        when (val step = SetupConsentSteps.create(reply.info)) {
            is SetupConsentStep.Create -> {
                setupCheck = null
                _state.update { it.copy(setupChecking = false, setupConfirmation = null) }
                sendChecked(check, step.setupConsent)
            }
            is SetupConsentStep.Confirm -> {
                val confirmation = DraftSetupConfirmation(step.preview, check.block.prNumber, check.block.branch)
                setupCheck = check.confirm(confirmation)
                _state.update { it.copy(setupChecking = false, setupConfirmation = confirmation) }
            }
            is SetupConsentStep.Refuse -> {
                // The server's own words for an intent that did not resolve.
                setupCheck = null
                _state.update { it.copy(setupChecking = false, setupConfirmation = null, error = step.message) }
            }
        }
        return true
    }

    /** The checked create goes out with [consent], on the row and server it was composed for. */
    private fun sendChecked(check: SetupCheck, consent: String): DraftSubmitResult {
        val entry = entryForKey(check.entryKey)
        if (entry == null || entry.status == "unavailable") {
            _state.update { it.copy(error = DRAFT_NOT_OFFERED_COPY) }
            return DraftSubmitResult.NotOffered
        }
        return send(check.origin, entry, consent)
    }

    /** The operator approved the listed setup: create with exactly that consent ("Run setup and start"). */
    fun confirmSetup(): DraftSubmitResult? {
        val check = setupCheck?.takeIf { it.phase == SetupPhase.Confirm } ?: return null
        val approval = check.confirmation?.approval ?: return null
        setupCheck = null
        _state.update { it.copy(setupChecking = false, setupConfirmation = null) }
        return sendChecked(check, approval.consent)
    }

    /** Back to the draft; nothing is created. */
    fun cancelSetup() {
        if (setupCheck != null) {
            setupCheck = null
            _state.update { it.copy(setupChecking = false, setupConfirmation = null) }
        }
    }

    /** The check ends with [message] shown in the draft. */
    private fun cancelSetupWith(message: String) {
        setupCheck = null
        _state.update { it.copy(setupChecking = false, setupConfirmation = null, error = message) }
    }

    /**
     * Any edit to what the check was made for (provider, model, mode, folder, isolation block, prompt,
     * attachments) cancels it: the confirmation must describe the create that would actually be sent.
     */
    private fun reconcileSetupCheck() {
        val check = setupCheck ?: return
        val s = _state.value
        if (check.formKey == setupFormKey() && check.text == s.text && check.staged == s.staged) return
        cancelSetupWith(DRAFT_SETUP_CHECK_CANCELLED_COPY)
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
     * replies is held to its current socket). ta-2ew: and, when stamped, came from the server the
     * create went to ([origin]).
     */
    private fun answers(p: PendingCreate, requestId: String?, seq: Long, snapshot: Long, linkEpoch: Long?, origin: String?): Boolean {
        if (!DraftForm.replyMatchesRequest(JsStr(p.requestId), requestId?.let(::JsStr))) return false
        if (!DraftForm.replyIsFresh(JsNum(snapshot.toDouble()), JsNum(seq.toDouble()))) return false
        if (origin != null && origin != p.origin) return false
        return (linkEpoch ?: client.linkEpoch.value) == p.linkEpoch
    }

    /**
     * ta-2ew (R1): the server [p] went to is no longer the configured one, or (r2) nobody is signed
     * in any more: [p] is over (settled, the draft unlocked with its text and told which), and
     * nothing that arrives for it later completes it. True when it was.
     */
    private fun settleIfServerChanged(p: PendingCreate): Boolean {
        val standing = client.originStanding(p.origin)
        if (standing == OriginStanding.Configured) return false
        settle(p)
        val copy = if (standing == OriginStanding.SignedOut) DRAFT_SIGNED_OUT_COPY else DRAFT_SERVER_CHANGED_COPY
        _state.update { it.copy(creating = false, error = copy) }
        return true
    }

    /**
     * A `created` reply. Returns the new session's id when it is THIS draft's create (and tells
     * [onSessionCreated], which selects it), else null and nothing changes. Then the first message
     * goes out, once.
     */
    fun onCreated(reply: CreatedReply): String? {
        val p = pending ?: return null
        if (!_state.value.creating) return null
        if (settleIfServerChanged(p)) return null
        if (!answers(p, reply.requestId, reply.seq, p.createdSnapshot, reply.linkEpoch, reply.origin)) return null
        return complete(p, reply)
    }

    private fun complete(p: PendingCreate, reply: CreatedReply): String {
        settle(p)
        val sessionId = reply.session.id
        when {
            p.prompt.isEmpty() && p.attachments.isEmpty() ->
                _state.update { it.copy(creating = false, completed = it.completed + 1) }
            // T8.5 (use-draft-composer.ts :387-414): the create claims the source in the same step:
            // the prompt is the takeover turn's engineText. Bound to the create's socket, as sendFirst.
            p.takeoverFrom != null -> {
                _state.update { it.copy(creating = false, completed = it.completed + 1) }
                val sent = client.linkEpoch.value == p.linkEpoch && client.handoff(p.takeoverFrom, sessionId, p.prompt)
                if (sent) {
                    // dashboard.tsx :250-255 onTakeoverCommitted: the brief is consumed, takeover mode ends.
                    client.clearHandoffBrief(p.takeoverFrom)
                    setTakeover(null)
                    onFirstSent()
                } else {
                    orphan(p, sessionId)
                }
            }
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
                    // ta-2ew (R3): the live check above is outside the client's lock; the epoch is
                    // checked again under it, with the send.
                    val sent = live && client.sendAttachments(sessionId, p.prompt, p.attachments, null, p.origin, p.linkEpoch) == AttachmentSendResult.Sent
                    _state.update { it.copy(creating = false) }
                    if (sent) onFirstSent() else orphan(p, sessionId)
                }
            }
        }
        onSessionCreated(sessionId, p.origin)
        return sessionId
    }

    /** The first message is on its way: the draft starts over (RESET re-seeds the workspace, issue #78). */
    private fun onFirstSent() {
        _state.update { dropAttachments(it).copy(text = "") }
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
            _state.update { dropAttachments(it).copy(text = "") }
        }
    }

    /**
     * An `error` frame. Unlocks the draft and shows the server's message ONLY when it answers the
     * in-flight create ([answers]); true when it did.
     */
    fun onCreateError(reply: CreateErrorReply): Boolean {
        // ta-m7ef (use-draft-composer.ts setupCheckErrorApplies): a refusal of the setup check itself ends it.
        setupCheck?.let { check ->
            if (SetupConsentSteps.errorEndsCheck(check.phase == SetupPhase.Checking, check.inspectRequestId, check.errorSeq, reply)) {
                cancelSetupWith(reply.message)
                return true
            }
        }
        val p = pending ?: return false
        if (!_state.value.creating) return false
        if (settleIfServerChanged(p)) return false
        if (!answers(p, reply.requestId, reply.seq, p.errorSnapshot, reply.linkEpoch, reply.origin)) return false
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
            is CreateReplyRecord.Created -> if (answers(p, record.reply.requestId, record.reply.seq, p.createdSnapshot, record.reply.linkEpoch, record.reply.origin)) {
                complete(p, record.reply)
                return true
            }
            is CreateReplyRecord.Failed -> if (answers(p, record.reply.requestId, record.reply.seq, p.errorSnapshot, record.reply.linkEpoch, record.reply.origin)) {
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
        // ta-23f: a question or answer from another socket no longer stands.
        inspect?.let { if (it.epoch != linkEpoch) inspect = null }
        sourceFor?.let { if (it.epoch != linkEpoch) dropSource() }
        // ta-m7ef (use-draft-composer.ts): a dropped link or a replaced socket cancels the setup check.
        setupCheck?.let { if (connection != ConnectionState.Connected || it.epoch != linkEpoch) cancelSetupWith(DRAFT_LINK_DROPPED_COPY) }
        val p = pending ?: return
        if (settleIfServerChanged(p)) return
        if (connection == ConnectionState.Connected && linkEpoch == p.linkEpoch) return
        if (settleFromRecord(p)) return
        settle(p)
        _state.update { it.copy(creating = false, error = DRAFT_LINK_DROPPED_COPY) }
    }

    /** r2 (security F2): no answer in time. Never resent; the session may exist (the operator checks). */
    private fun onReplyTimeout(p: PendingCreate) {
        if (pending !== p) return
        if (settleIfServerChanged(p)) return
        if (settleFromRecord(p)) return
        settle(p)
        _state.update { it.copy(creating = false, error = DRAFT_REPLY_TIMEOUT_COPY) }
    }

    companion object {
        /**
         * lib/draft-form.ts mergeCustomModelsIntoEntries, on the typed rows: each row's custom ids it
         * does not already list are appended as models named by their id; untouched rows by identity.
         */
        fun mergeCustomModels(entries: List<ProviderCatalogEntry>, custom: Map<String, List<String>>): List<ProviderCatalogEntry> {
            if (custom.isEmpty()) return entries
            return entries.map { entry ->
                val ids = custom[entry.key].orEmpty()
                if (ids.isEmpty()) return@map entry
                val present = entry.models.mapTo(HashSet()) { it.value }
                val additions = ids.filter { present.add(it) }.map { com.tether.app.protocol.SessionModelOption(value = it, displayName = it) }
                if (additions.isEmpty()) entry else entry.copy(models = entry.models + additions)
            }
        }

        /** A staged attachment's raw size from its base64 [data] (padding excluded). */
        internal fun decodedSize(data: String): Long {
            val pad = data.takeLast(2).count { it == '=' }
            return maxOf(0L, data.length / 4L * 3L - pad)
        }

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

/** use-draft-composer.ts: an edit ended the setup check. */
const val DRAFT_SETUP_CHECK_CANCELLED_COPY = "The setup check was cancelled because the draft changed. Press Send again."

/** use-draft-composer.ts: the link dropped before `created`. */
const val DRAFT_LINK_DROPPED_COPY = "The secure link dropped before the session was created — your message was not sent."

/** ta-895: the draft was drawn for another server than the one now connected. */
const val DRAFT_NOT_LIVE_COPY = "The server changed. Nothing was created; pick again."

/** ta-2ew: the configured server changed while a create was in flight; it is not completed here. */
const val DRAFT_SERVER_CHANGED_COPY = "The server changed while the session was being created, so it was not opened here and your message was not sent."

/** ta-2ew r2: signed out (a logout, a stop, a rejected credential) while a create was in flight. */
const val DRAFT_SIGNED_OUT_COPY = "You were signed out while the session was being created, so it was not opened and your message was not sent."

/** ta-895: the live catalog no longer offers the row as drawn. */
const val DRAFT_NOT_OFFERED_COPY = "This server no longer offers that choice. Nothing was created; pick again from the updated list."

/** ta-8cv r2: no reply to the create in time; the session may exist all the same. */
const val DRAFT_REPLY_TIMEOUT_COPY =
    "The server has not answered yet, so the session may already exist. Check the session list before you try again."

/** ta-23f: the WorktreeDetails fields (lib/draft-form.ts DraftWorktreeOptions keys). */
enum class WorktreeField(val key: String) {
    BaseRef("worktreeBaseRef"),
    Branch("worktreeBranch"),
    Slug("worktreeSlug"),
    Pr("worktreePr"),
}

/** ta-8cv: the server refused this create and its words cleaned to nothing. */
const val DRAFT_REFUSED_COPY = "The server did not create the session."
