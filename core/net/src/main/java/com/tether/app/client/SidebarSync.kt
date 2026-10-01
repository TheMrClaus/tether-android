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
 *  - `server-settings` → the last frame (1120);
 *  - `advanced-settings` → the last frame (1140; ta-t7l, the Claude CLI picker). Both settings
 *    frames are per server, so [clear] drops them with the sidebar on a server switch.
 *  - `providers` → the custom-providers registry (1155; ta-q6p), numbered by arrival (a write
 *    built from an older list is refused). It carries each profile's env values in plaintext, so
 *    it is dropped with the settings frames ([clearSettings], [clear]).
 */
internal class SidebarSync {
    val historiesByCwd = MutableStateFlow<Map<String, List<HistorySession>>>(emptyMap())
    val sessionOrders = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val remoteSeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    val serverSettings = MutableStateFlow<ServerMessage.ServerSettings?>(null)
    val advancedSettings = MutableStateFlow<ServerMessage.AdvancedSettings?>(null)

    /** ta-dh1: every `server-settings` frame counted, an unchanged one too ("Scan again" waits for the next). */
    val serverSettingsReplies = MutableStateFlow(0L)

    /** ta-q6p: the last `providers` frame, as the editor reads it (null until one arrives on this server). */
    val providerProfiles = MutableStateFlow<ProvidersList?>(null)

    // ta-q6p: every `providers` frame gets the next number, across clears too, so a list from
    // before a clear can never pass for the one after it.
    private var providersGeneration = 0L

    /** Folds one frame; returns false for a frame this class does not own. */
    fun onFrame(message: ServerMessage): Boolean {
        when (message) {
            is ServerMessage.Histories -> historiesByCwd.update { it + (message.cwd to message.sessions) }
            is ServerMessage.SessionOrder -> sessionOrders.update { it + (message.cwd to message.order) }
            is ServerMessage.Seen -> remoteSeen.update { current ->
                val known = current[message.historyId]
                if (known != null && known >= message.seenAt) current else current + (message.historyId to message.seenAt)
            }
            is ServerMessage.ServerSettings -> {
                serverSettings.value = message
                serverSettingsReplies.update { it + 1 }
            }
            is ServerMessage.AdvancedSettings -> advancedSettings.value = message
            is ServerMessage.Providers -> providerProfiles.value = ProvidersList.of(message, ++providersGeneration)
            else -> return false
        }
        return true
    }

    /** use-tether.ts:1512 — optimistic after a successful send; the server's broadcast follows. */
    fun applyLocalOrder(cwd: String, order: List<String>) {
        sessionOrders.update { it + (cwd to order) }
    }

    /** ta-t7l r2: the settings frames only (sign-out, auth required): they hold plaintext secrets. ta-q6p: the providers list too. */
    fun clearSettings() {
        serverSettings.value = null
        advancedSettings.value = null
        providerProfiles.value = null
    }

    /** Another server's sidebar must never show: dropped with the other per-server views. */
    fun clear() {
        historiesByCwd.value = emptyMap()
        sessionOrders.value = emptyMap()
        remoteSeen.value = emptyMap()
        serverSettings.value = null
        advancedSettings.value = null
        providerProfiles.value = null
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

