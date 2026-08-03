package com.tether.app.ui

import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tether.app.client.ConnectionState
import com.tether.app.client.TetherClient
import com.tether.app.protocol.Attachment
import com.tether.app.protocol.model.AgentSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Thin view-model over [TetherClient]: selection, create-then-select, the
 * event-anchored clock, and the error toast/log.
 */
class TetherViewModel(val client: TetherClient) : ViewModel() {

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

    /** Errors seen this connection, newest last (topbar badge + log dialog). */
    val errorLog = mutableStateListOf<String>()

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
        viewModelScope.launch {
            client.errors.collect { message ->
                errorLog.add(message)
                if (errorLog.size > 50) errorLog.removeAt(0)
                _activeToast.value = message
            }
        }
    }

    private fun onSessions(list: List<AgentSession>) {
        val mono = SystemClock.elapsedRealtime()
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
        return anchor.serverTs + (SystemClock.elapsedRealtime() - anchor.monotonicMs)
    }

    fun selectSession(id: String) {
        _selectedSessionId.value = id
        client.attach(id)
    }

    /**
     * Choose the project folder new sessions run in (folder picker / pinned
     * project). The session list filters to it, so the open chat is dropped —
     * the same setActiveId(null) the web client applies on a project switch.
     */
    fun selectWorkspace(cwd: String) {
        if (cwd == _currentWorkspace.value) return
        _currentWorkspace.value = cwd
        _selectedSessionId.value = null
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
        errorLog.add(message)
        if (errorLog.size > 50) errorLog.removeAt(0)
        _activeToast.value = message
    }

    fun dismissToast() {
        _activeToast.value = null
    }
}

class TetherViewModelFactory(private val client: TetherClient) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TetherViewModel(client) as T
}
