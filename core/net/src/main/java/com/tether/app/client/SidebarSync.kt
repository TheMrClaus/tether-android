package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.model.HistorySession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * T5.1: the per-server sidebar state RealTetherClient publishes — kept apart from the connection
 * code so the client only routes frames here ([onFrame]) and clears it with the other server
 * views ([clear]). Mirrors hooks/use-tether.ts:
 *  - `histories` → historiesByCwd[cwd] (853), replaced wholesale per workspace;
 *  - `session-order` → sessionOrders[cwd] (864-865);
 *  - `seen` → forwarded to the preferences store (869-873); kept here as the newest seenAt per
 *    historyId so a late subscriber still sees it;
 *  - `server-settings` → the last frame (1120).
 */
internal class SidebarSync {
    val historiesByCwd = MutableStateFlow<Map<String, List<HistorySession>>>(emptyMap())
    val sessionOrders = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val remoteSeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    val serverSettings = MutableStateFlow<ServerMessage.ServerSettings?>(null)

    /** Folds one frame; returns false for a frame this class does not own. */
    fun onFrame(message: ServerMessage): Boolean {
        when (message) {
            is ServerMessage.Histories -> historiesByCwd.update { it + (message.cwd to message.sessions) }
            is ServerMessage.SessionOrder -> sessionOrders.update { it + (message.cwd to message.order) }
            is ServerMessage.Seen -> remoteSeen.update { current ->
                val known = current[message.historyId]
                if (known != null && known >= message.seenAt) current else current + (message.historyId to message.seenAt)
            }
            is ServerMessage.ServerSettings -> serverSettings.value = message
            else -> return false
        }
        return true
    }

    /** use-tether.ts:1512 — optimistic after a successful send; the server's broadcast follows. */
    fun applyLocalOrder(cwd: String, order: List<String>) {
        sessionOrders.update { it + (cwd to order) }
    }

    /** Another server's sidebar must never show: dropped with the other per-server views. */
    fun clear() {
        historiesByCwd.value = emptyMap()
        sessionOrders.value = emptyMap()
        remoteSeen.value = emptyMap()
        serverSettings.value = null
    }

    companion object {
        /** The exact frames the sidebar sends (lib/protocol.ts ClientMessage). */
        fun discover(cwd: String, lastSeen: Map<String, Long>, watch: List<String>) =
            ClientMessage.Discover(cwd, lastSeen = lastSeen, watch = watch)

        fun markSeen(historyId: String, seenAt: Long) = ClientMessage.MarkSeen(historyId, seenAt)

        fun setSessionOrder(cwd: String, order: List<String>) = ClientMessage.SetSessionOrder(cwd, order)

        /** dashboard.tsx:1129/1154 — `updateServerSettings({ pinnedWorkspaces: next })`. */
        fun setPinnedWorkspaces(pinned: List<String>) = ClientMessage.SetServerSettings(
            buildJsonObject { put("pinnedWorkspaces", JsonArray(pinned.map(::JsonPrimitive))) },
        )
    }
}

