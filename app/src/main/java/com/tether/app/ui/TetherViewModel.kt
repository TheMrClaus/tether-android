package com.tether.app.ui

import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tether.app.client.TetherClient
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

    fun createSession(provider: String) {
        knownIdsBeforeCreate = client.sessions.value.map { it.id }.toSet()
        client.createSession(provider)
    }

    /** Busy turns queue; idle sessions send. */
    fun sendOrQueue(sessionId: String, text: String) {
        val projection = client.projections.value[sessionId]
        if (projection?.activeTurnId != null) client.queueAdd(sessionId, text)
        else client.send(sessionId, text)
    }

    fun dismissToast() {
        _activeToast.value = null
    }
}

class TetherViewModelFactory(private val client: TetherClient) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TetherViewModel(client) as T
}
