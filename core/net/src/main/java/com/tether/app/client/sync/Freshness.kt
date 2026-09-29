package com.tether.app.client.sync

import com.tether.app.client.Freshness
import com.tether.app.client.SessionSync
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.str

/**
 * T13.2 (SYNC_DESIGN §4.1): the freshness state machine, as a pure function of what the client
 * knows about the connection and each session. RealTetherClient feeds it from its flows; nothing
 * here reads a clock or the network.
 */
object FreshnessRules {

    /**
     * Everything [derive] reads. [connected] = the connection is [com.tether.app.client.ConnectionState.Connected];
     * [attached] = sessions attached on the current socket; [live] = those a snapshot on it confirmed;
     * [saved] = sessions with a saved copy in the mirror (not necessarily hydrated), with its
     * persisted `last_verified_at`; [verifiedAt] = this process's own, newer, verification times.
     */
    data class Inputs(
        val connected: Boolean,
        val listed: Collection<String> = emptyList(),
        val trees: Map<String, JsObj> = emptyMap(),
        val trimmedBefore: Map<String, Int> = emptyMap(),
        val saved: Map<String, Long?> = emptyMap(),
        val attached: Set<String> = emptySet(),
        val live: Set<String> = emptySet(),
        val verifiedAt: Map<String, Long> = emptyMap(),
    )

    /**
     * One entry per session the app can show (listed, holding a copy, or attached while
     * connected):
     * - **Live**: connected, confirmed on this connection, and its copy is in memory;
     * - **CatchingUp**: connected and attached, but not (yet, or no longer) confirmed, or
     *   confirmed while its saved copy is still being read;
     * - **Saved**: a copy exists (in memory or in the mirror) and none of the above holds;
     * - **NotDownloaded**: no copy at all.
     * Live is never inferred from anything but [Inputs.live] and [Inputs.connected] together.
     */
    fun derive(inputs: Inputs): Map<String, SessionSync> {
        val ids = LinkedHashSet<String>()
        ids.addAll(inputs.listed)
        ids.addAll(inputs.trees.keys)
        ids.addAll(inputs.saved.keys)
        if (inputs.connected) ids.addAll(inputs.attached)
        val out = LinkedHashMap<String, SessionSync>(ids.size)
        for (id in ids) out[id] = sessionSync(inputs, id)
        return out
    }

    private fun sessionSync(inputs: Inputs, id: String): SessionSync {
        val tree = inputs.trees[id]
        val hasCopy = tree != null || inputs.saved.containsKey(id)
        val freshness = when {
            inputs.connected && id in inputs.live && tree != null -> Freshness.Live
            inputs.connected && (id in inputs.attached || id in inputs.live) -> Freshness.CatchingUp
            hasCopy -> Freshness.Saved
            else -> Freshness.NotDownloaded
        }
        val verified = inputs.verifiedAt[id] ?: inputs.saved[id]
        return SessionSync(freshness, verified, partial = isPartial(tree, inputs.trimmedBefore[id]))
    }

    /**
     * v115 bounded snapshots: the leading turns below `trimmedBefore` arrive with no blocks, and
     * `turns-detail` fills them in. Trimmed turns are a prefix, so the copy is partial exactly while
     * its FIRST turn is still an empty stub.
     */
    fun isPartial(tree: JsObj?, trimmedBefore: Int?): Boolean {
        if (tree == null || trimmedBefore == null || trimmedBefore <= 0) return false
        val first = (tree["turnOrder"] as? JsArr)?.firstOrNull()?.str ?: return false
        val turn = (tree["turnsById"] as? JsObj)?.get(first) as? JsObj ?: return false
        val blocks = turn["blocks"] as? JsArr
        val byId = turn["blocksById"] as? JsObj
        return blocks.isNullOrEmpty() && (byId == null || byId.isEmpty())
    }
}
