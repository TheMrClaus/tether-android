package com.tether.app.client

import com.tether.app.protocol.model.AgentSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ta-ozn2.3: the device's in-memory handover claims, the port of dashboard.tsx:1214-1290 at fe4aa8c0.
 * [requested] is THIS process's set of source ids it asked to hand over (never persisted: a reload forgets it, and the
 * banner still links). It drives the local "requested" flag until the server echoes `handoverPending`, and the focus
 * rule: only the asking device navigates, and only if it is still viewing the source. Not thread-safe; the view model
 * calls it from the main scope.
 */
class HandoverRequests {
    private val _requested = MutableStateFlow<Set<String>>(emptySet())
    val requested: StateFlow<Set<String>> = _requested.asStateFlow()

    /** Ids whose `handoverPending` the server has echoed (handoverSeenPendingRef). */
    private val seenPending = mutableSetOf<String>()

    /** Call only when the send succeeded: a `false` / LINK_RECONNECTING send sets no flag. */
    fun add(sourceId: String) {
        _requested.value = _requested.value + sourceId
    }

    /** The banner link (or any explicit open of another session) ends the claim on [sourceId]. */
    fun drop(ids: Collection<String>) {
        if (ids.isEmpty() || _requested.value.none { it in ids }) return
        _requested.value = _requested.value - ids.toSet()
        seenPending.removeAll(ids.toSet())
    }

    fun clear() {
        _requested.value = emptySet()
        seenPending.clear()
    }

    /**
     * The settle effect: a source whose row disappeared settles; one whose `handoverPending` the server echoed and then
     * dropped without a link (cancelled) clears. (Run before [decide], as the web's effects are ordered.)
     */
    fun settle(sessions: List<AgentSession>) {
        val settled = mutableListOf<String>()
        for (id in _requested.value) {
            val row = sessions.firstOrNull { it.id == id }
            if (row == null) { settled += id; continue }
            if (row.handoverPending) seenPending += id
            else if (id in seenPending && row.handedOffTo == null) { seenPending -= id; settled += id }
        }
        drop(settled)
    }

    /** A refusal: drop every claim the server never echoed (its `handoverPending` is not set). */
    fun onRefusal(sessions: List<AgentSession>) {
        drop(_requested.value.filter { id -> sessions.firstOrNull { it.id == id }?.handoverPending != true })
    }

    /**
     * The focus rule, evaluated on (sessions, the RESOLVED selected id): acts when the source's `handedOffTo` and the
     * successor row are both present, in either arrival order. Decided claims are dropped (navigate AND drop); the
     * caller navigates for each [Handover.NavigationDecision.Action.Navigate].
     */
    fun decide(activeId: String?, sessions: List<AgentSession>): List<Handover.NavigationDecision> {
        if (_requested.value.isEmpty()) return emptyList()
        val decisions = Handover.decideNavigation(_requested.value, activeId, sessions)
        if (decisions.isEmpty()) return decisions
        drop(decisions.map { it.sourceId })
        return decisions
    }
}
