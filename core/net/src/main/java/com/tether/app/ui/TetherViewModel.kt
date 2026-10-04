package com.tether.app.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tether.app.client.AttachmentSendResult
import com.tether.app.client.ConnectionState
import com.tether.app.client.StagedAttachments
import com.tether.app.client.CreatedReply
import com.tether.app.client.DraftComposerModel
import com.tether.app.client.DraftSubmitResult
import com.tether.app.client.EventLog
import com.tether.app.client.LogoutResult
import com.tether.app.client.NewSessionChoice
import com.tether.app.client.TetherClient
import com.tether.app.client.isWarning
import com.tether.app.client.serverOrigin
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.HistorySession
import com.tether.app.ui.prefs.DraftStore
import com.tether.app.ui.prefs.InMemoryDraftStore
import com.tether.app.ui.prefs.bestEffortPreferenceWrite
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Thin view-model over [TetherClient]: selection, the new-session draft composer
 * ([draftComposer]; ta-8cv: a new session is selected only from its own create's
 * `created`, matched by requestId), the event-anchored clock, per-session composer
 * drafts, the error toast, and the Health & Event Log badge (unseen warnings).
 */
class TetherViewModel(
    val client: TetherClient,
    private val draftStore: DraftStore = InMemoryDraftStore(),
    private val monotonicClock: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {

    private val _selectedSessionId = MutableStateFlow<String?>(null)
    val selectedSessionId: StateFlow<String?> = _selectedSessionId.asStateFlow()

    // T5.2 (declared before init: the created collector may run during it).
    /**
     * dashboard.tsx:202-206 — the history row the operator just opened, highlighted before the
     * server has created its live session, so nothing snaps back to the previous chat meanwhile.
     */
    private val _openingHistoryId = MutableStateFlow<String?>(null)
    val openingHistoryId: StateFlow<String?> = _openingHistoryId.asStateFlow()

    // ta-coik.41 (declared before init, like the opening row: the created collector may run during it).
    private val _selectionPending = MutableStateFlow(false)

    /**
     * Whether the selection is the web's `pendingSessionId` (a link, the remembered chat, a `created`,
     * a live search hit) rather than a pick: until the list arrives it reads as "Reopening your
     * session" (dashboard.tsx:1702 `pendingSessionId && !visibleSessions.length`). An explicit
     * selection, a resume and the restore giving up retire it.
     */
    val selectionPending: StateFlow<Boolean> = _selectionPending.asStateFlow()

    /** The first view of this view model is resolved (the remembered chat is seeded, or not, once). */
    private var bootViewSeen = false

    /** The remembered chat seeded at boot, while its restore (:856-886) is still owed. */
    private var restoreTarget: com.tether.app.ui.prefs.LastOpenedSession? = null
    private var restoreSettled = false

    private val _restoreGraceElapsed = MutableStateFlow(false)

    /**
     * dashboard.tsx:849-854: discovery's bounded grace, 2 s after a connection opens (armed once per
     * connection; once elapsed it stays so).
     */
    val restoreGraceElapsed: StateFlow<Boolean> = _restoreGraceElapsed.asStateFlow()

    private val _listLive = MutableStateFlow(false)

    /**
     * ta-coik.41 r2: a server snapshot has listed the sessions since this sign-in (the web has no list
     * before its first `ready`, use-tether.ts 90fbb9f :769). A list read back from the device's saved
     * copy is not one, so the one-time pick waits for this.
     */
    val listLive: StateFlow<Boolean> = _listLive.asStateFlow()

    /** The newest `created` seq already acted on (a reply that arrived before this VM is not a new one). */
    private var followedCreatedSeq = 0L

    /**
     * Operator-chosen project folder (the folder picker's result). Null means
     * the server's default folder (ready.workspaceRoot). New sessions inherit
     * it as their cwd; selecting it re-runs history discovery for that subtree.
     */
    private val _currentWorkspace = MutableStateFlow<String?>(null)
    val currentWorkspace: StateFlow<String?> = _currentWorkspace.asStateFlow()

    /**
     * ta-8cv (T8.1): the new-session draft composer (use-draft-composer.ts), held here like the
     * web's dashboard holds the hook, so the draft survives a session switch and a rotation. It is
     * the only path a new session takes ([createNewSession]); its own matched `created` is the one
     * a new session is selected from.
     */
    val draftComposer = DraftComposerModel(
        client = client,
        draftStore = draftStore,
        scope = viewModelScope,
        currentWorkspace = { _currentWorkspace.value },
        saveSessionDraft = { origin, sessionId, text -> saveSessionDraft(origin, sessionId, text) },
        onSessionCreated = { sessionId, origin -> onOwnCreate(sessionId, origin) },
    )

    /**
     * ta-abm (T8.1 slice 2): dashboard.tsx `draftOpen` — the operator is composing a new session.
     * Held here with the draft itself, so a rotation, a recreated shell or a session switch neither
     * closes the sheet nor loses what is in it. It stays true across the create round trip (the sheet
     * gives way to the launching stage while [DraftComposerModel] is creating, and comes back with
     * the prompt and the error when the create fails); a `created`, a selection, a resume and a
     * server switch close it (dashboard.tsx 781, selectSession, reopen; the web's /login unmounts it).
     */
    private val _draftOpen = MutableStateFlow(false)
    val draftOpen: StateFlow<Boolean> = _draftOpen.asStateFlow()

    /** dashboard.tsx openDraft: raise the new-session sheet over whatever is on screen (nothing is deselected). */
    fun openDraft() {
        _draftOpen.value = true
    }

    /** dashboard.tsx closeDraft: the sheet goes; the draft (text, folder, provider, attachments) stays. */
    fun closeDraft() {
        hideDraft()
    }

    /** Every way the sheet goes (close, a selection, a resume, a `created`, another server, sign-out). */
    private fun hideDraft() {
        _draftOpen.value = false
    }

    /**
     * The selected sub-agent run tab per session (null/absent = the whole
     * session transcript). Owned here — like the web's dashboard — NOT in the
     * composable, so it survives recomposition; consumers resolve the id by
     * lookup against the current runs list, so a vanished run degrades to the
     * Session tab by itself (no reset effect).
     */
    private val _selectedRunIdBySession = MutableStateFlow<Map<String, String?>>(emptyMap())
    val selectedRunIdBySession: StateFlow<Map<String, String?>> = _selectedRunIdBySession.asStateFlow()

    fun selectRun(sessionId: String, runId: String?) {
        _selectedRunIdBySession.value = _selectedRunIdBySession.value + (sessionId to runId)
    }

    /**
     * Unsent composer text per session (T2.3; the web's `tether:draft:<id>`, chat-view.tsx:
     * 1561-1584). A session is present once its stored draft has been loaded ([loadDraft]) or
     * the operator has typed; absent means "not loaded yet". Every change is written through
     * to [draftStore], so a draft survives a session switch, a rotation (this view model) and
     * process death (the store).
     *
     * T7.1: the map holds the drafts of ONE server origin, [draftOrigin] (the canonical origin of
     * [TetherClient.serverUrl], as T1.3/ta-s8q keys unsent input). When the configured server
     * changes, the map empties and the other origin's drafts are read from its own namespace;
     * a load or write that started for the old origin never lands in the new one. With no server
     * configured (no origin) a draft lives in memory only.
     */
    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()
    private val draftLoads = HashSet<String>()
    private var draftOrigin: String? = serverOrigin(client.serverUrl.value)
    private val draftWrites = Channel<DraftWrite>(Channel.UNLIMITED)

    private data class DraftWrite(val origin: String, val sessionId: String, val text: String)

    /** Load [sessionId]'s stored draft once; never overwrites text typed meanwhile. */
    fun loadDraft(sessionId: String) {
        if (!draftLoads.add(sessionId) || sessionId in _drafts.value) return
        val origin = draftOrigin
        if (origin == null) {
            _drafts.update { if (sessionId in it) it else it + (sessionId to "") }
            return
        }
        viewModelScope.launch {
            val stored = draftStore.read(origin, sessionId)
            if (draftOrigin != origin) return@launch // the server changed while reading
            _drafts.update { if (sessionId in it) it else it + (sessionId to stored) }
        }
    }

    /** [sessionId]'s draft if already loaded, else null (a snapshot read, not a subscription). */
    fun loadedDraft(sessionId: String): String? = _drafts.value[sessionId]

    /** [sessionId]'s draft once loaded (immediately if it already is). */
    suspend fun awaitDraft(sessionId: String): String {
        loadDraft(sessionId)
        return _drafts.mapNotNull { it[sessionId] }.first()
    }

    /**
     * ta-8cv: the orphaned first message (use-draft-composer.ts:429-437): stored as [sessionId]'s
     * draft on [origin] (awaited: a failure throws, and the draft composer keeps the text), then
     * shown in that session's composer unless something was typed there meanwhile.
     */
    private suspend fun saveSessionDraft(origin: String, sessionId: String, text: String) {
        draftStore.write(origin, sessionId, text)
        if (draftOrigin == origin) _drafts.update { if (it[sessionId].isNullOrEmpty()) it + (sessionId to text) else it }
    }

    /** The composer's text changed; "" (sent or cleared) removes the stored draft, like the web. */
    fun setDraft(sessionId: String, text: String) {
        if (_drafts.value[sessionId] == text) return
        _drafts.update { it + (sessionId to text) }
        draftOrigin?.let { draftWrites.trySend(DraftWrite(it, sessionId, text)) }
    }

    /**
     * T7.4: the composer's staged attachments (one session on one server, memory only). They
     * survive a rotation (this view model) but not process death, and are dropped on a server
     * switch, a sign-out or revocation (configured going false), a switch to another session, and
     * when their session locks (read-only, handed off or archived) or leaves the list. Only an
     * explicit Send transmits them ([sendAttachments]).
     *
     * r2 (L4, verifier M1): every one of those triggers drops ([StagedAttachments.clear], which
     * bumps its generation) whether or not anything is staged yet, so a FIRST pick still being read
     * when it fires is discarded too, never staged into the session or server it no longer belongs
     * to; the stager also re-checks [attachmentsAllowed] when the read is done.
     */
    val stagedAttachments = StagedAttachments()

    /** The link origin last seen (T7.4 r2: a move to another server drops what is staged or being read). */
    private var lastLinkOrigin: String? = null

    /**
     * T7.4 r2: may a pick for [sessionId] be staged now? Only for the selected session, while it is
     * listed and not locked (read-only, handed off, archived).
     */
    fun attachmentsAllowed(sessionId: String): Boolean {
        if (_selectedSessionId.value != sessionId) return false
        val session = client.sessions.value.firstOrNull { it.id == sessionId } ?: return false
        return !attachmentsLocked(session)
    }

    private fun attachmentsLocked(s: AgentSession): Boolean = s.readOnly || !s.handedOffTo.isNullOrEmpty() || s.runtimeArchived

    /** Another server is configured: its drafts are its own (read on demand from its namespace). */
    private fun onServerUrl(url: String?) {
        val origin = serverOrigin(url)
        // T6.7 r2: a server's words never outlive the switch to another server.
        dropServerToastUnless(origin)
        // T7.4: attachments staged for one server are never offered to another (r2: nor is a pick
        // still being read when the server changes).
        val staged = stagedAttachments.current.value
        if ((staged != null && staged.origin != origin) || origin != draftOrigin) stagedAttachments.clear()
        // ta-8cv: the draft composer's preferences are that server's own.
        draftComposer.onOrigin(origin)
        if (origin == draftOrigin) return
        // ta-coik.41 r2: another server is another web origin, a page of its own: its console starts
        // afresh (selection, remembered chat, restore, current workspace). Not the first server
        // learnt after a cold start (no previous origin).
        if (draftOrigin != null) resetConsole()
        // ta-abm: the dropped draft's sheet goes with its server.
        hideDraft()
        draftOrigin = origin
        draftLoads.clear()
        _drafts.value = emptyMap()
    }

    /**
     * The warning count the operator acknowledged by opening the log (dashboard.tsx:194-199
     * `seenWarnAt`), with the sign-in [EventLog.generation] it was taken in. The web's mark lives
     * in the Dashboard, which unmounts on /login, so every sign-in starts at 0; this view model
     * outlives the login screen, so a mark from another generation reads as 0 instead.
     */
    private data class SeenMark(val count: Int, val generation: Long)

    private val seenWarnAt = MutableStateFlow(SeenMark(0, client.eventLog.value.generation))

    /**
     * Warnings (non-`info` log entries) not seen yet: `max(0, warnCount - seenWarnAt)`, like the
     * web. After a server restart empties the log (same generation) the count can only rise again
     * past the acknowledged mark, exactly as there.
     */
    val unseenWarnings: StateFlow<Int> = combine(client.eventLog, seenWarnAt) { log, seen -> unseen(log, seen) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, unseen(client.eventLog.value, seenWarnAt.value))

    /** Opening the log acknowledges every warning logged so far (dashboard.tsx:199 `openLog`). */
    fun openLog() {
        val log = client.eventLog.value
        seenWarnAt.value = SeenMark(warnCount(log), log.generation)
    }

    private fun warnCount(log: EventLog): Int = log.entries.count { it.isWarning }

    private fun unseen(log: EventLog, seen: SeenMark): Int =
        maxOf(0, warnCount(log) - if (seen.generation == log.generation) seen.count else 0)

    private val _activeToast = MutableStateFlow<String?>(null)

    /** The toast's words (whoever wrote them); [toast] also says who. */
    val activeToast: StateFlow<String?> = _activeToast.asStateFlow()

    private val _toast = MutableStateFlow<Toast?>(null)

    /**
     * T6.7: the one error toast (the web's `.error-toast`), and whether a SERVER wrote its words
     * ([TetherClient.serverErrors]: cleaned by the client, shown attributed so they cannot pass for
     * the app's own).
     */
    val toast: StateFlow<Toast?> = _toast.asStateFlow()

    private fun showToast(text: String, fromServer: Boolean, origin: String? = null) {
        _toast.value = Toast(text, fromServer, origin)
        _activeToast.value = text
    }

    /** T6.7 r2: a server toast from anywhere but [origin] (null: no server) is closed. */
    private fun dropServerToastUnless(origin: String?) {
        val shown = _toast.value ?: return
        if (shown.fromServer && shown.origin != origin) dismissToast()
    }

    /**
     * T6.7 r2: a server's words are shown only while their server is the one configured and (when
     * connected) the one the socket is on; they are tagged with the socket they came in on.
     */
    private fun onServerError(error: com.tether.app.client.ServerErrorText) {
        val configured = serverOrigin(client.serverUrl.value)
        val live = client.consentOrigin.value
        if (configured != null && configured != error.origin) return
        if (live != null && live != error.origin) return
        showToast(error.text, fromServer = true, origin = error.origin)
    }

    /**
     * Event-anchored clock (visual-spec §4 TurnActivity): elapsed readings anchor
     * the latest journal ts we have seen for a session to the device's MONOTONIC
     * clock — never raw wall-clock vs ts, so a skewed device clock cannot inflate
     * or reverse the reading.
     */
    private data class Anchor(val serverTs: Long, val monotonicMs: Long)

    private val anchors = HashMap<String, Anchor>()
    private var globalAnchor: Anchor? = null

    init {
        viewModelScope.launch {
            client.sessions.collect { list -> onSessions(list) }
        }
        viewModelScope.launch {
            client.serverUrl.collect { url -> onServerUrl(url) }
        }
        // One ordered writer: a burst of keystrokes collapses to the latest text per session.
        viewModelScope.launch {
            for (first in draftWrites) {
                val latest = linkedMapOf((first.origin to first.sessionId) to first.text)
                while (true) {
                    val next = draftWrites.tryReceive().getOrNull() ?: break
                    latest[next.origin to next.sessionId] = next.text
                }
                for ((key, text) in latest) bestEffortPreferenceWrite { draftStore.write(key.first, key.second, text) }
            }
        }
        viewModelScope.launch {
            client.errors.collect { message -> showToast(message, fromServer = false) }
        }
        viewModelScope.launch {
            client.serverErrors.collect { error -> onServerError(error) }
        }
        viewModelScope.launch {
            // T6.7 r2: the link moved to another server: that server's words are not this one's.
            // T7.4: nor are the attachments staged for the other one.
            client.consentOrigin.collect { origin ->
                if (origin != null) {
                    dropServerToastUnless(origin)
                    val staged = stagedAttachments.current.value
                    val moved = lastLinkOrigin != null && lastLinkOrigin != origin
                    if ((staged != null && staged.origin != origin) || moved) stagedAttachments.clear()
                    lastLinkOrigin = origin
                }
            }
        }
        // T7.4 r2 (L4b): signed out or revoked, even with the same server URL kept (a re-pairing
        // to the same server starts with nothing staged, and nothing still being read lands).
        viewModelScope.launch {
            client.configured.collect { configured ->
                if (!configured) {
                    stagedAttachments.clear()
                    draftComposer.clearAttachments()
                }
            }
        }
        // T5.2: follow this device's own create/resume reply (dashboard.tsx:708-722).
        viewModelScope.launch {
            val seenSeq = client.createdSessions.value?.seq ?: 0L
            // ta-8cv r2 (security F2): every reply in order, never conflated away.
            client.createdReplies.collect { reply -> onCreated(reply, seenSeq) }
        }
        // ta-8cv: the draft composer's other inputs (use-draft-composer.ts effects): the catalog and
        // workspace it resolves against, the `error` frames, and the link its create went out on.
        viewModelScope.launch {
            combine(client.providerCatalog, client.providerCatalogLive, client.providers, client.workspaceRoot, _currentWorkspace) { _, _, _, _, _ -> }
                .collect { draftComposer.refresh() }
        }
        viewModelScope.launch {
            client.createErrorReplies.collect { reply -> draftComposer.onCreateError(reply) }
        }
        // ta-23f: the answers to the composer's own `worktree-inspect` (it keeps only its own).
        viewModelScope.launch {
            client.worktreeSources.collect { reply -> draftComposer.onWorktreeSource(reply) }
        }
        viewModelScope.launch {
            combine(client.connection, client.linkEpoch, ::Pair).collect { (connection, epoch) -> draftComposer.onLink(connection, epoch) }
        }
        // ta-coik.41 (dashboard.tsx:849-854): the boot restore's grace, armed on each connection.
        viewModelScope.launch {
            client.connection.collectLatest { state ->
                if (state != ConnectionState.Connected) return@collectLatest
                // r2: the list is the server's from here (its `ready` set it before Connected).
                _listLive.value = true
                kotlinx.coroutines.delay(RESTORE_GRACE_MS)
                _restoreGraceElapsed.value = true
            }
        }
    }

    private fun onSessions(list: List<AgentSession>) {
        val mono = monotonicClock()
        for (session in list) {
            val existing = anchors[session.id]
            if (existing == null || session.updatedAt > existing.serverTs) {
                anchors[session.id] = Anchor(session.updatedAt, mono)
            }
        }
        val latest = list.maxOfOrNull { it.updatedAt }
        if (latest != null && (globalAnchor == null || latest > globalAnchor!!.serverTs)) {
            globalAnchor = Anchor(latest, mono)
        }

        // T7.4: attachments staged for a session that locked (read-only, handed off, archived) go;
        // r2: so do those of a session that left the list (verifier L3), and a lock of the SELECTED
        // session drops a first pick still being read for it (nothing staged yet: L4a).
        val staged = stagedAttachments.current.value
        val stagedGone = staged != null && list.firstOrNull { it.id == staged.sessionId }.let { it == null || attachmentsLocked(it) }
        val selectedNow = _selectedSessionId.value
        val selectedLocked = selectedNow != null && list.firstOrNull { it.id == selectedNow }?.let(::attachmentsLocked) == true
        if (stagedGone || selectedLocked) stagedAttachments.clear()

        // ta-coik.41: a selection whose session left the list is KEPT (dashboard.tsx 90fbb9f: nothing
        // clears `activeId`; `selectedSession` :709-717 just resolves to nothing, so the empty workspace
        // shows, and the chat is on screen again if it comes back).

        // Drop run-tab selections for sessions that no longer exist (archive /
        // workspace switch) — stale entries are inert (consumers resolve by
        // lookup) but this keeps the map from growing unbounded over time.
        val liveIds = list.mapTo(HashSet()) { it.id }
        val runSelections = _selectedRunIdBySession.value
        if (runSelections.isNotEmpty() && runSelections.any { it.key !in liveIds }) {
            _selectedRunIdBySession.value = runSelections.filterKeys { it in liveIds }
        }
    }

    /** Server-time "now" for a session, from the event anchor + monotonic delta. */
    fun serverNow(sessionId: String?): Long {
        val anchor = sessionId?.let { anchors[it] } ?: globalAnchor
        ?: return System.currentTimeMillis()
        return anchor.serverTs + (monotonicClock() - anchor.monotonicMs)
    }

    fun selectSession(id: String) {
        // dashboard.tsx:300-304 selectActiveId: an explicit selection retires a pending target.
        _selectionPending.value = false
        showSelected(id)
        if (mountedChat == id) {
            // Re-selected while its chat view stays on screen: the web remounts nothing (dashboard.tsx
            // `key={activeSession.id}`), so the client sends only if another session was attached since.
            client.attach(id)
        } else {
            // This open mounts the chat view: its attach is the mount's (the shell's report consumes it).
            attachedForMount = id
            client.attachMounted(id)
        }
    }

    // ta-coik.39 r2: the chat whose view the shell reports on screen (the web's mounted ChatView:
    // Sessions, not Usage, not a create in flight; dashboard.tsx 90fbb9f :1572-1647), and an open's
    // attach still to be matched with the mount it causes. Held here, so a rotation (which recomposes
    // the shell and reports the same chat again) is no mount.
    private var mountedChat: String? = null
    private var attachedForMount: String? = null

    /**
     * ta-coik.39 r2: the shell's report of the chat view on screen ([sessionId]; null: none). A real
     * mount (another chat, or the same one back from Overview, Scheduled, Usage or a create) attaches
     * it as the web's ChatView mount does (use-tether.ts 90fbb9f :1568, afterSeq = cursor), unless an
     * open just attached it for this very mount. The same report again (recomposition, rotation,
     * background and back) does nothing.
     */
    fun chatViewShown(sessionId: String?) {
        if (sessionId == mountedChat) return
        mountedChat = sessionId
        if (sessionId == null) return
        val opened = attachedForMount == sessionId
        attachedForMount = null
        if (!opened) client.attachMounted(sessionId)
    }

    /** [selectSession] without the attach (the caller attaches, or already has). */
    private fun showSelected(id: String) {
        // dashboard.tsx:227 selectActiveId — every explicit selection retires the opening row.
        _openingHistoryId.value = null
        // ta-abm (dashboard.tsx selectSession): a selection closes the new-session sheet; the draft stays.
        hideDraft()
        // T7.4: staged attachments belong to the conversation they were picked in (the web's ChatView);
        // r2: another selection drops a first pick still being read for the previous one as well.
        val staged = stagedAttachments.current.value
        if ((staged != null && staged.sessionId != id) || _selectedSessionId.value != id) stagedAttachments.clear()
        _selectedSessionId.value = id
        loadDraft(id)
    }

    private val _openRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /**
     * T4.4: sessions opened from outside the shell (a deep link, a notification, a session link in
     * a chat). No replay, so a recreated shell never re-runs an old one. The phone shell closes its
     * drawer and popover for each, as a sidebar pick does; the sidebar makes the session's workspace
     * block current (dashboard.tsx focusWorkspaceFor on `pendingSessionId`).
     */
    val openRequests: SharedFlow<String> = _openRequests.asSharedFlow()

    private val _bootLinkPending = MutableStateFlow(false)

    /**
     * T15.4 r2: a session link came with the intent that LAUNCHED the app and has not settled yet
     * (it waits for the stored settings and the session list). Like the web's `?session=` on a
     * cold load it wins the console's boot view: Sessions, with nothing behind it. Set by the root
     * that routes links; never by a link arriving later (that one steps to Sessions).
     */
    val bootLinkPending: StateFlow<Boolean> = _bootLinkPending.asStateFlow()

    fun setBootLinkPending(pending: Boolean) {
        _bootLinkPending.value = pending
    }

    /** Select [id] on behalf of a link: [selectSession] plus an [openRequests] event. Navigation only. */
    fun openSession(id: String) {
        selectSession(id)
        // dashboard.tsx:1114, 1137, 1267: a link's (or a live search hit's) target is a pending one.
        _selectionPending.value = true
        _openRequests.tryEmit(id)
    }

    // ------------------------------------------------------------------
    // ta-coik.41: the web's selection model (dashboard.tsx 90fbb9f). The web holds `activeId` (a pick)
    // and `pendingSessionId` (a link, the remembered chat, a `created`, a live search hit) and shows
    // whichever names a listed chat. Here one selection carries both, and [selectionPending] says
    // whether it came the pending way.
    // ------------------------------------------------------------------

    /**
     * dashboard.tsx:736-748: the console's first view is resolved ([sessionsView]: it is Sessions).
     * Once per view model (a cold start, or a process the system brought back): on Sessions, with no
     * link and nothing opening, the chat this device last had open ([remembered], the stored
     * `lastOpenedSession`) becomes the pending selection. Nothing is attached: the chat attaches when
     * its view mounts ([chatViewShown]).
     */
    fun onBootView(sessionsView: Boolean, remembered: com.tether.app.ui.prefs.LastOpenedSession?) {
        if (bootViewSeen) return
        bootViewSeen = true
        if (!sessionsView || remembered == null) return
        if (_selectedSessionId.value != null || _openingHistoryId.value != null || _bootLinkPending.value) return
        restoreTarget = remembered
        adopt(remembered.sessionId)
        _selectionPending.value = true
    }

    /**
     * dashboard.tsx:856-886, the boot restore, re-evaluated on every change of its inputs and acted
     * on at most once. While the remembered chat is still the pending selection and not in the
     * session list, it waits for [histories] (the current workspace's discovered conversations) or
     * the grace ([restoreGraceElapsed]); then it returns the conversation to reopen by its historyId,
     * or, with none, gives the remembered chat up (the selection clears, so the one-time pick may
     * run). Returns null when there is nothing to reopen now; the caller reopens what it returns
     * (dashboard.tsx `reopen`: the block becomes current, [resumeHistory], seen).
     */
    fun bootRestoreStep(sessionsView: Boolean, histories: List<HistorySession>): HistorySession? {
        if (restoreSettled || !sessionsView) return null
        val grace = _restoreGraceElapsed.value
        val target = restoreTarget
        val stillPending = target != null && _selectionPending.value && _selectedSessionId.value == target.sessionId &&
            client.sessions.value.none { it.id == target.sessionId }
        if (!stillPending) {
            if (grace) restoreSettled = true
            return null
        }
        if (histories.isEmpty() && !grace) return null
        restoreSettled = true
        restoreTarget = null
        val hit = target!!.historyId?.let { id -> histories.firstOrNull { it.historyId == id } }
        if (hit == null) {
            _selectedSessionId.value = null
            _selectionPending.value = false
        }
        return hit
    }

    /**
     * dashboard.tsx:752-763, the one-time pick: on Sessions ([sessionsView]) with nothing selected,
     * pending or opening (a cold start with no link and no remembered chat, the restore given up, a
     * return to Sessions with nothing selected) and a non-empty [visible] list (`visibleSessions`),
     * the first chat in [currentWorkspace], else the first listed, is selected and kept. A selection
     * whose chat left the list is still a selection, so nothing is picked over it. Nothing is
     * attached: the chat attaches when its view mounts. Waits for the boot view (the remembered chat
     * is seeded first, as the web seeds it at mount).
     */
    fun pickIfNothingSelected(sessionsView: Boolean, visible: List<AgentSession>, currentWorkspace: String?) {
        if (!bootViewSeen || !_listLive.value || !sessionsView || visible.isEmpty()) return
        if (_selectedSessionId.value != null || _openingHistoryId.value != null || _bootLinkPending.value) return
        val preferred = visible.firstOrNull { it.cwd == currentWorkspace } ?: visible.first()
        adopt(preferred.id)
    }

    /**
     * dashboard.tsx:1352-1364 navigateTo("sessions") from the top bar: with nothing selected, the
     * remembered chat ([remembered]) becomes the pending selection when it is listed ([visible]).
     * "Nothing selected" is the web's `selectedSession?.id ?? pendingSessionId` being null: no
     * selection, or a picked one whose chat left the list (a pending one is kept).
     */
    fun onNavigateToSessions(visible: List<AgentSession>, remembered: com.tether.app.ui.prefs.LastOpenedSession?) {
        val selected = _selectedSessionId.value
        if (selected != null && (_selectionPending.value || visible.any { it.id == selected })) return
        val id = remembered?.sessionId ?: return
        if (visible.none { it.id == id }) return
        adopt(id)
        _selectionPending.value = true
    }

    /**
     * The web's /login unmount (a sign-out, another server): nothing selected, pending or opening, the
     * remembered chat seeded again on the next boot view, the grace and the live list re-armed by the
     * next connection, and the current workspace settled again on its `ready` (use-tether.ts :783).
     */
    private fun resetConsole() {
        _selectedSessionId.value = null
        _selectionPending.value = false
        _openingHistoryId.value = null
        bootViewSeen = false
        restoreTarget = null
        restoreSettled = false
        _restoreGraceElapsed.value = false
        _listLive.value = false
        _currentWorkspace.value = null
    }

    /** The web's `setActiveId` / `setPendingSessionId` alone: no attach, the sheet stays, the opening row stays. */
    private fun adopt(id: String) {
        val staged = stagedAttachments.current.value
        if ((staged != null && staged.sessionId != id) || _selectedSessionId.value != id) stagedAttachments.clear()
        _selectedSessionId.value = id
        loadDraft(id)
    }

    /**
     * Choose the workspace new sessions run in (a sidebar block, "Add workspace"). Since v93 every
     * block is on screen at once, so the open conversation stays put (dashboard.tsx switchProject /
     * chooseWorkspace leave it alone, T5.1); the sidebar re-declares its full watch set after this
     * `discover`.
     */
    fun selectWorkspace(cwd: String) {
        if (cwd == _currentWorkspace.value) return
        _currentWorkspace.value = cwd
        // T5.3: use-tether.ts:1351 — the previous workspace's content hits go with it.
        client.clearSearchResults()
        client.discover(cwd)
    }

    /**
     * ta-coik.41 (use-tether.ts 90fbb9f :783-785): on `ready` the web fixes its current workspace to
     * the one it already has, else the preferred one (the last-opened chat's folder, else the default
     * workspace: dashboard.tsx:207), else the server's root ([resolved]), and keeps it until the
     * operator picks another. Only when nothing is chosen yet; nothing is sent (the sidebar's watch
     * discovers it, as the web's `ready` handler does).
     */
    fun settleWorkspace(resolved: String?) {
        if (resolved.isNullOrEmpty() || _currentWorkspace.value != null) return
        _currentWorkspace.value = resolved
    }

    /**
     * ta-895 / ta-8cv: the New session picker's tap on [choice], drawn for [expectedOrigin], in the
     * current workspace, through the draft composer ([DraftComposerModel.submitChoice]: the web's
     * frame with its mode (a sandbox tier for Codex only) and a fresh requestId; the client re-checks it under
     * its lock). Sent: the session whose `created` echoes that requestId is selected when it lands,
     * and no other. Anything else: nothing was created, and nothing is selected later.
     */
    fun createNewSession(choice: NewSessionChoice, expectedOrigin: String?): DraftSubmitResult =
        draftComposer.submitChoice(choice, expectedOrigin)

    /**
     * Busy turns queue; idle sessions send. Text only (T7.4): a message with attachments goes
     * through [sendAttachments] alone, so a non-empty [attachments] is refused here (false, the
     * draft and the chips kept) and never reaches the durable outbox.
     */
    fun sendOrQueue(sessionId: String, text: String, attachments: List<Attachment> = emptyList()): Boolean {
        if (attachments.isNotEmpty()) {
            reportLocalError(ATTACHMENTS_REFUSED_COPY)
            return false
        }
        val projection = client.projections.value[sessionId]
        if (projection?.activeTurnId != null) {
            client.queueAdd(sessionId, text)
            return true
        }
        client.send(sessionId, text)
        return true
    }

    /**
     * T7.4: send [text] with the attachments staged for [sessionId] (and [mention], a delegation),
     * from an explicit Send only. [expectedOrigin] is the server the composer was drawn for; the
     * staged set must belong to it and to [sessionId], or nothing is sent. The client re-checks the
     * link, the origin, the session's liveness and locks, idleness and the frame size under its lock
     * ([TetherClient.sendAttachments]). Sent: the staged set is cleared. Anything else keeps it (and
     * the draft) and returns why.
     */
    fun sendAttachments(sessionId: String, text: String, mention: com.tether.app.protocol.DelegateMention?, expectedOrigin: String?): AttachmentSendResult {
        val staged = stagedAttachments.current.value
        if (staged == null || staged.sessionId != sessionId || staged.items.isEmpty()) return AttachmentSendResult.Empty
        if (expectedOrigin == null || staged.origin != expectedOrigin) return AttachmentSendResult.NotLive
        if (client.connection.value != ConnectionState.Connected) return AttachmentSendResult.NotConnected
        val result = client.sendAttachments(sessionId, text, staged.items.map { it.attachment }, mention, expectedOrigin)
        if (result == AttachmentSendResult.Sent) stagedAttachments.clear()
        return result
    }

    /**
     * The server origin attachments are staged under now: the configured server's (a client with
     * none configured, such as the demo, answers with its link's origin; a signed-out client has
     * neither).
     */
    fun attachmentOrigin(): String? = draftOrigin ?: client.consentOrigin.value

    /**
     * T7.3: an idle send that delegates (chat-view.tsx:3150-3167: never queued — queue-add carries
     * no mention). False when nothing was recorded (a turn runs, attachments (T7.4: those go through
     * [sendAttachments]), or a mention the current catalog does not offer); the composer then keeps
     * the draft and the chip.
     */
    fun sendDelegated(sessionId: String, text: String, attachments: List<Attachment>, mention: com.tether.app.protocol.DelegateMention, expectedOrigin: String?): Boolean {
        // T7.4: a delegation with attachments is [sendAttachments]'s (never the durable outbox's).
        if (attachments.isNotEmpty()) {
            reportLocalError(ATTACHMENTS_REFUSED_COPY)
            return false
        }
        if (client.projections.value[sessionId]?.activeTurnId != null) return false
        return client.sendDelegated(sessionId, text, attachments, mention, expectedOrigin) == com.tether.app.client.MentionResult.Sent
    }

    /** Client-side failure that never hit the server (e.g. unreadable attachment). */
    fun reportLocalError(message: String) {
        showToast(message, fromServer = false)
    }

    fun dismissToast() {
        _toast.value = null
        _activeToast.value = null
    }

    /**
     * What the login screen says after a user logout, when there is something
     * to say about the server side (null = nothing to add).
     */
    private val _logoutNotice = MutableStateFlow<String?>(null)
    val logoutNotice: StateFlow<String?> = _logoutNotice.asStateFlow()

    fun logout() {
        // T7.4 r2: nothing staged, or still being read, outlives the decision to sign out (ta-abm r2:
        // the new-session draft's attachments neither).
        stagedAttachments.clear()
        draftComposer.clearAttachments()
        viewModelScope.launch {
            _logoutNotice.value = logoutNoticeFor(client.logout())
            stagedAttachments.clear()
            draftComposer.clearAttachments()
            // ta-coik.41: the web's Dashboard unmounts on /login, so the next sign-in boots afresh.
            resetConsole()
            // ta-abm: so does the new-session sheet (the draft itself is the server's, kept in memory).
            hideDraft()
            // T5.3: the web's search state lives in the Dashboard, which /login unmounts.
            _globalSearchOpen.value = false
            _globalSearchForm.value = GlobalSearchForm()
            _findRequest.value = null
        }
    }

    fun dismissLogoutNotice() {
        _logoutNotice.value = null
    }

    // ------------------------------------------------------------------
    // T5.2: resume a discovered conversation (dashboard.tsx reopen, use-tether.ts resumeHistory).
    // ------------------------------------------------------------------

    /**
     * dashboard.tsx:394-409: send `resume`; only if it went out, the row becomes the opening one
     * and the current selection clears (the transcript waits for the server's `created`). A
     * refusal (history gone, recoverable work elsewhere) arrives as an `error` toast and leaves
     * the row opening, as on the web. Returns whether the frame was sent.
     */
    fun resumeHistory(history: HistorySession): Boolean {
        if (!client.resume(history)) return false
        hideDraft()
        _openingHistoryId.value = history.historyId
        _selectedSessionId.value = null
        _selectionPending.value = false
        return true
    }

    /**
     * dashboard.tsx:708-722: the unicast reply is a deliberate target; it outranks the rest.
     *
     * ta-8cv: a reply that carries a `requestId` answers a create, and is followed only when the
     * draft composer matches it to ITS in-flight create (requestId, newer seq, the create's socket;
     * [onOwnCreate]); a stale or foreign one is never selected. A reply without one answers a
     * resume and opens the resumed session as it lands, as the web's dashboard does for every
     * `created` (r2, verifier P3: also while a create is in flight). It cannot take that create's
     * place: the create completes only on its own reply, which then opens ITS session, so whichever
     * reply lands last is on screen, exactly as on the web.
     *
     * ta-2ew (R1): a reply is its server's ([CreatedReply.origin]); one handled after a switch to
     * another server (still buffered here) opens nothing ([openCreated]).
     */
    private fun onCreated(reply: CreatedReply?, seenAtStart: Long) {
        if (reply == null || reply.seq <= maxOf(seenAtStart, followedCreatedSeq)) return
        followedCreatedSeq = reply.seq
        if (reply.requestId != null) {
            draftComposer.onCreated(reply)
            return
        }
        openCreated(reply.session.id, reply.origin)
    }

    /** ta-8cv: the draft composer's own create made [sessionId] (from its reply, or from the record after a drop). */
    private fun onOwnCreate(sessionId: String, origin: String) = openCreated(sessionId, origin)

    /**
     * ta-2ew (R1): [origin] (the server the reply came from; null: a client that does not stamp its
     * replies) must still be the configured server. The client checks it and subscribes the session
     * in one step under its lock ([TetherClient.attachIfConfigured]), so a switch cannot slip between;
     * refused, nothing here changes (no selection, the sheet stays).
     */
    private fun openCreated(sessionId: String, origin: String?) {
        if (origin != null && !client.attachIfConfigured(sessionId, origin)) return
        // ta-coik.39 r2: that attach is the one of the mount it causes (the server watches the
        // created session already: server.mjs 90fbb9f :9073, :9294, :9319).
        if (origin != null && mountedChat != sessionId) attachedForMount = sessionId
        _openingHistoryId.value = null
        // dashboard.tsx:781: every `created` (this draft's own, or a resume's) closes the sheet.
        hideDraft()
        if (_selectedSessionId.value != sessionId) {
            if (origin != null) showSelected(sessionId) else selectSession(sessionId)
        }
        // ta-coik.41 (dashboard.tsx:778): the reply's session is a pending target.
        _selectionPending.value = true
    }

    // ------------------------------------------------------------------
    // T5.3: the global search modal and the in-chat find request (dashboard.tsx:421-427,
    // 1157-1194). Held here so both survive a rotation; the web's modal stays mounted while
    // closed, so its query and filters survive a close and reopen too.
    // ------------------------------------------------------------------

    private val _globalSearchOpen = MutableStateFlow(false)
    val globalSearchOpen: StateFlow<Boolean> = _globalSearchOpen.asStateFlow()

    private val _globalSearchForm = MutableStateFlow(GlobalSearchForm())
    val globalSearchForm: StateFlow<GlobalSearchForm> = _globalSearchForm.asStateFlow()

    /**
     * dashboard.tsx:426 `findRequest`: the query that surfaced a result, for the opened session's
     * in-chat find bar — keyed by historyId so it fires only on that conversation, and by a nonce
     * so opening the same conversation again re-triggers the jump.
     */
    private val _findRequest = MutableStateFlow<FindRequest?>(null)
    val findRequest: StateFlow<FindRequest?> = _findRequest.asStateFlow()
    private var findNonce = 0L

    /** dashboard.tsx:1157 (the sidebar key) and 1186-1194 (Ctrl/Cmd+Shift+F). */
    fun openGlobalSearch() {
        _globalSearchOpen.value = true
    }

    /** dashboard.tsx:1158-1161: closing also clears the results and drops any in-flight reply. */
    fun closeGlobalSearch() {
        _globalSearchOpen.value = false
        client.clearGlobalSearch()
    }

    fun updateGlobalSearchForm(form: GlobalSearchForm) {
        _globalSearchForm.value = form
    }

    /** dashboard.tsx:1179-1180: arm the in-chat find for [historyId]'s conversation. */
    fun requestFind(query: String, historyId: String) {
        findNonce += 1
        _findRequest.value = FindRequest(query, findNonce, historyId)
    }
}

/**
 * components/global-search.tsx's own state (57-60): the typed text, the harness chips, the time
 * window (`any` | `1d` | `7d` | `30d`) and "This workspace only".
 */
data class GlobalSearchForm(
    val text: String = "",
    val providers: List<String> = emptyList(),
    val timeWindow: String = "any",
    val scopeToWorkspace: Boolean = false,
)

/** dashboard.tsx:426 — `{ query, nonce, historyId }`. */
data class FindRequest(val query: String, val nonce: Long, val historyId: String)

/** Login-screen copy for a finished logout (see [TetherClient.logout] for the semantics). */
fun logoutNoticeFor(result: LogoutResult): String? = when (result) {
    LogoutResult.Revoked -> null
    LogoutResult.LocalOnly ->
        "Signed out on this phone. It stays paired with the server until it is revoked in " +
            "Settings → Paired devices."
    LogoutResult.ServerNotReached ->
        "Signed out on this phone, but the server could not be reached. That session stays valid " +
            "until it expires or is signed out in Settings → Signed-in sessions."
}

class TetherViewModelFactory(
    private val client: TetherClient,
    private val draftStore: DraftStore = InMemoryDraftStore(),
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TetherViewModel(client, draftStore) as T
}

/** ta-coik.41 (dashboard.tsx:851): how long the boot restore waits for discovery after a connection opens. */
const val RESTORE_GRACE_MS = 2_000L

/** T7.4: attachments handed to the text-only send (the composer never does; nothing is sent). */
internal const val ATTACHMENTS_REFUSED_COPY = "Not connected — the message and its attachments were not sent."

/** T6.7: an error toast's words, and whether a server wrote them ([TetherViewModel.toast]). */
data class Toast(val text: String, val fromServer: Boolean, val origin: String? = null)
