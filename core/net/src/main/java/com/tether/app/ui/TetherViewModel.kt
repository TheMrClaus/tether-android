package com.tether.app.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tether.app.client.ConnectionState
import com.tether.app.client.EventLog
import com.tether.app.client.LogoutResult
import com.tether.app.client.TetherClient
import com.tether.app.client.isWarning
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.prefs.DraftStore
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
     * to [draftStore], so a draft survives a session switch and process death.
     */
    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()
    private val draftLoads = HashSet<String>()
    private val draftWrites = Channel<Pair<String, String>>(Channel.UNLIMITED)

    /** Load [sessionId]'s stored draft once; never overwrites text typed meanwhile. */
    fun loadDraft(sessionId: String) {
        if (!draftLoads.add(sessionId) || sessionId in _drafts.value) return
        viewModelScope.launch {
            val stored = draftStore.read(sessionId)
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
        draftWrites.trySend(sessionId to text)
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
    val activeToast: StateFlow<String?> = _activeToast.asStateFlow()

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
        // One ordered writer: a burst of keystrokes collapses to the latest text per session.
        viewModelScope.launch {
            for (first in draftWrites) {
                val latest = linkedMapOf(first)
                while (true) {
                    val next = draftWrites.tryReceive().getOrNull() ?: break
                    latest[next.first] = next.second
                }
                for ((sessionId, text) in latest) draftStore.write(sessionId, text)
            }
        }
        viewModelScope.launch {
            client.errors.collect { message ->
                _activeToast.value = message
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

        // Auto-select the session created from the provider picker.
        knownIdsBeforeCreate?.let { before ->
            val created = list.firstOrNull { it.id !in before }
            if (created != null) {
                knownIdsBeforeCreate = null
                selectSession(created.id)
            }
        }

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
        _selectedSessionId.value = id
        loadDraft(id)
        client.attach(id)
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
        client.discover(cwd)
    }

    fun createSession(provider: String) {
        knownIdsBeforeCreate = client.sessions.value.map { it.id }.toSet()
        client.createSession(provider, cwd = _currentWorkspace.value)
    }

    /**
     * Busy turns queue; idle sessions send. Attachments ride the idle send
     * only (the server never queues them) — the composer disables attaching
     * while a turn is busy, so a non-empty list here always means idle.
     *
     * Returns false when the message was refused (attachments while
     * disconnected — §5.6: roll back and tell the user, draft kept by the
     * caller) so the composer can keep the draft instead of clearing it.
     */
    fun sendOrQueue(sessionId: String, text: String, attachments: List<Attachment> = emptyList()): Boolean {
        val projection = client.projections.value[sessionId]
        if (projection?.activeTurnId != null) {
            client.queueAdd(sessionId, text)
            return true
        }
        if (attachments.isNotEmpty() && client.connection.value != ConnectionState.Connected) {
            reportLocalError("Not connected — the message and its attachments were not sent.")
            return false
        }
        client.send(sessionId, text, attachments)
        return true
    }

    /** Client-side failure that never hit the server (e.g. unreadable attachment). */
    fun reportLocalError(message: String) {
        _activeToast.value = message
    }

    fun dismissToast() {
        _activeToast.value = null
    }

    /**
     * What the login screen says after a user logout, when there is something
     * to say about the server side (null = nothing to add).
     */
    private val _logoutNotice = MutableStateFlow<String?>(null)
    val logoutNotice: StateFlow<String?> = _logoutNotice.asStateFlow()

    fun logout() {
        viewModelScope.launch {
            _logoutNotice.value = logoutNoticeFor(client.logout())
            _selectedSessionId.value = null
        }
    }

    fun dismissLogoutNotice() {
        _logoutNotice.value = null
    }
}

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
