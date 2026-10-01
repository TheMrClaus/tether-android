package com.tether.app.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tether.app.client.AttachmentSendResult
import com.tether.app.client.ConnectionState
import com.tether.app.client.StagedAttachments
import com.tether.app.client.CreatedReply
import com.tether.app.client.EventLog
import com.tether.app.client.LogoutResult
import com.tether.app.client.NewSessionChoice
import com.tether.app.client.NewSessionResult
import com.tether.app.client.TetherClient
import com.tether.app.client.isWarning
import com.tether.app.client.serverOrigin
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.HistorySession
import com.tether.app.ui.prefs.DraftStore
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Thin view-model over [TetherClient]: selection, create-then-select, the
 * event-anchored clock, per-session composer drafts, the error toast, and the
 * Health & Event Log badge (unseen warnings).
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
        if (origin == draftOrigin) return
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

    /** Set once a create was requested, so the next new session row is auto-selected. */
    private var knownIdsBeforeCreate: Set<String>? = null

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
                for ((key, text) in latest) draftStore.write(key.first, key.second, text)
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
            client.configured.collect { configured -> if (!configured) stagedAttachments.clear() }
        }
        // T5.2: follow this device's own create/resume reply (dashboard.tsx:708-722).
        viewModelScope.launch {
            val seenSeq = client.createdSessions.value?.seq ?: 0L
            client.createdSessions.collect { reply -> onCreated(reply, seenSeq) }
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

        // Auto-select the session created from the provider picker.
        knownIdsBeforeCreate?.let { before ->
            val created = list.firstOrNull { it.id !in before }
            if (created != null) {
                knownIdsBeforeCreate = null
                selectSession(created.id)
            }
        }

        // T7.4: attachments staged for a session that locked (read-only, handed off, archived) go;
        // r2: so do those of a session that left the list (verifier L3), and a lock of the SELECTED
        // session drops a first pick still being read for it (nothing staged yet: L4a).
        val staged = stagedAttachments.current.value
        val stagedGone = staged != null && list.firstOrNull { it.id == staged.sessionId }.let { it == null || attachmentsLocked(it) }
        val selectedNow = _selectedSessionId.value
        val selectedLocked = selectedNow != null && list.firstOrNull { it.id == selectedNow }?.let(::attachmentsLocked) == true
        if (stagedGone || selectedLocked) stagedAttachments.clear()

        // Drop a selection whose session disappeared (archive).
        val selected = _selectedSessionId.value
        if (selected != null && list.none { it.id == selected }) {
            _selectedSessionId.value = null
        }

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
        // dashboard.tsx:227 selectActiveId — every explicit selection retires the opening row.
        _openingHistoryId.value = null
        // T7.4: staged attachments belong to the conversation they were picked in (the web's ChatView);
        // r2: another selection drops a first pick still being read for the previous one as well.
        val staged = stagedAttachments.current.value
        if ((staged != null && staged.sessionId != id) || _selectedSessionId.value != id) stagedAttachments.clear()
        _selectedSessionId.value = id
        loadDraft(id)
        client.attach(id)
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
        _openRequests.tryEmit(id)
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

    fun createSession(provider: String) {
        knownIdsBeforeCreate = client.sessions.value.map { it.id }.toSet()
        client.createSession(provider, cwd = _currentWorkspace.value)
    }

    /**
     * ta-895: the New session picker's tap on [choice], drawn for [expectedOrigin], in the current
     * workspace ([TetherClient.createNewSession] re-checks it under the client's lock). Sent: the
     * next new session row is auto-selected, as [createSession] does. Anything else: nothing was
     * created, and nothing is auto-selected later.
     */
    fun createNewSession(choice: NewSessionChoice, expectedOrigin: String?): NewSessionResult {
        val before = client.sessions.value.map { it.id }.toSet()
        val result = client.createNewSession(choice, _currentWorkspace.value, expectedOrigin)
        if (result == NewSessionResult.Sent) knownIdsBeforeCreate = before
        return result
    }

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
        // T7.4 r2: nothing staged, or still being read, outlives the decision to sign out.
        stagedAttachments.clear()
        viewModelScope.launch {
            _logoutNotice.value = logoutNoticeFor(client.logout())
            stagedAttachments.clear()
            _selectedSessionId.value = null
            _openingHistoryId.value = null
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
        _openingHistoryId.value = history.historyId
        _selectedSessionId.value = null
        return true
    }

    /** dashboard.tsx:708-722: the unicast reply is a deliberate target; it outranks the rest. */
    private fun onCreated(reply: CreatedReply?, seenAtStart: Long) {
        if (reply == null || reply.seq <= maxOf(seenAtStart, followedCreatedSeq)) return
        followedCreatedSeq = reply.seq
        _openingHistoryId.value = null
        // The provider picker's create-then-select may already have landed on it.
        if (knownIdsBeforeCreate != null && client.sessions.value.any { it.id == reply.session.id }) knownIdsBeforeCreate = null
        if (_selectedSessionId.value == reply.session.id) return
        selectSession(reply.session.id)
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
        "Signed out on this phone. A paired device can only be revoked from a browser: " +
            "Settings → Paired devices."
    LogoutResult.ServerNotReached ->
        "Signed out on this phone, but the server could not be reached. That session stays valid " +
            "until it expires or you sign it out from a browser."
}

class TetherViewModelFactory(
    private val client: TetherClient,
    private val draftStore: DraftStore = InMemoryDraftStore(),
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TetherViewModel(client, draftStore) as T
}

/** T7.4: attachments handed to the text-only send (the composer never does; nothing is sent). */
internal const val ATTACHMENTS_REFUSED_COPY = "Not connected — the message and its attachments were not sent."

/** T6.7: an error toast's words, and whether a server wrote them ([TetherViewModel.toast]). */
data class Toast(val text: String, val fromServer: Boolean, val origin: String? = null)
