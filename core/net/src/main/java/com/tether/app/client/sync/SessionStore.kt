package com.tether.app.client.sync

import com.tether.app.mirror.HydratedSession
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.tree.JsObj
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * T13.1 step 3 (SYNC_DESIGN §2.1, §2.6): the owner of every session projection the UI observes.
 *
 * It holds, per session, the tree (`fold(base, tail)`: the v128 JsValue projection, the source
 * of truth the reducer folds live events onto), its typed view, the v115 trim boundary, and the
 * journal-mirror bookkeeping that keeps `tree == fold(splice(DB.base, DB.details), DB.tail)`:
 * the saved copy being read (and the events folded meanwhile), the tail length since the base
 * (local checkpoints, §2.4), and the fetched turn details (§2.3). The UI keeps observing the same
 * three flows through [com.tether.app.client.TetherClient]; with the mirror on, they are fed from
 * the mirror at cold start and by the live connection after.
 *
 * Not thread-safe by itself: RealTetherClient calls every method except [adapt] under its lock,
 * which also orders the mirror writes. [adapt] may run on the frame thread and on a hydration
 * concurrently: it serializes per adapter.
 */
class SessionStore {
    private val treesState = MutableStateFlow<Map<String, JsObj>>(emptyMap())
    private val projectionsState = MutableStateFlow<Map<String, SessionProjection>>(emptyMap())
    private val trimmedBeforeState = MutableStateFlow<Map<String, Int>>(emptyMap())

    // T2.1D: one memoized adapter per session.
    private val adapters = HashMap<String, LegacyProjectionAdapter>()

    // Mirror bookkeeping (all empty while the mirror is off).
    private val hydrating = HashMap<String, MutableList<JsObj>>()
    private val hydrationTried = HashSet<String>()
    private val tailSinceBase = HashMap<String, Int>()
    private val fetchedDetails = HashMap<String, Map<String, JsObj>>()

    val trees: StateFlow<Map<String, JsObj>> = treesState
    val projections: StateFlow<Map<String, SessionProjection>> = projectionsState
    val trimmedBefore: StateFlow<Map<String, Int>> = trimmedBeforeState

    fun tree(sessionId: String): JsObj? = treesState.value[sessionId]

    fun has(sessionId: String): Boolean = treesState.value.containsKey(sessionId)

    /** [tree]'s typed view through [sessionId]'s memoized adapter: null when the tree does not fit the typed model. */
    fun adapt(sessionId: String, tree: JsObj): SessionProjection? {
        val adapter = synchronized(adapters) { adapters.getOrPut(sessionId) { LegacyProjectionAdapter() } }
        return synchronized(adapter) { adapter.adapt(tree) }
    }

    /** Publish [sessionId]'s projection: the tree, and its typed view (removed when null). */
    fun publish(sessionId: String, tree: JsObj, typed: SessionProjection?) {
        treesState.value = treesState.value + (sessionId to tree)
        projectionsState.value = if (typed != null) projectionsState.value + (sessionId to typed) else projectionsState.value - sessionId
    }

    /** A diverged base (fold exception): the tree and its typed view go; the next snapshot heals it. */
    fun drop(sessionId: String) {
        treesState.value = treesState.value - sessionId
        projectionsState.value = projectionsState.value - sessionId
        tailSinceBase.remove(sessionId)
        fetchedDetails.remove(sessionId)
    }

    fun setTrimmedBefore(sessionId: String, trimmedBefore: Int?) {
        trimmedBeforeState.value = trimmedBefore
            ?.let { trimmedBeforeState.value + (sessionId to it) }
            ?: (trimmedBeforeState.value - sessionId)
    }

    /** Another server's projections must never show (sign-in switch). */
    fun clearViews() {
        treesState.value = emptyMap()
        projectionsState.value = emptyMap()
        trimmedBeforeState.value = emptyMap()
    }

    /** The mirror bookkeeping is per server, like the cursors. */
    fun clearMirrorState() {
        hydrating.clear()
        hydrationTried.clear()
        tailSinceBase.clear()
        fetchedDetails.clear()
    }

    // ------------------------------------------------------------------
    // Journal mirror (T13.1)
    // ------------------------------------------------------------------

    /**
     * Start reading [sessionId]'s saved copy: true (and live events for it are buffered from
     * now on) when it has no projection and no read was tried in this binding.
     */
    fun beginHydration(sessionId: String): Boolean {
        if (has(sessionId) || !hydrationTried.add(sessionId)) return false
        hydrating[sessionId] = ArrayList()
        return true
    }

    /** A read of [sessionId]'s saved copy is in flight. */
    fun isHydrating(sessionId: String): Boolean = hydrating.containsKey(sessionId)

    /** A live event folded by the cursor while its session's saved copy is read: kept for later. True = buffered. */
    fun bufferIfHydrating(sessionId: String, event: JsObj): Boolean {
        val pending = hydrating[sessionId] ?: return false
        pending += event
        return true
    }

    /**
     * The read landed: fold the events buffered meanwhile on top of [rebuilt] (the copy's
     * `fold(splice(base, details), tail)`) and adopt its bookkeeping. Null result = nothing to
     * publish: the read was cancelled (a state won, a sign-out), a projection exists already, or
     * the buffered events cannot fold ([failed] is then set, and the caller drops the copy).
     */
    fun completeHydration(sessionId: String, session: HydratedSession?, rebuilt: JsObj?): HydrationOutcome {
        val buffered = hydrating.remove(sessionId) ?: return HydrationOutcome.Cancelled
        if (has(sessionId)) return HydrationOutcome.Cancelled
        if (session == null || rebuilt == null) return HydrationOutcome.Failed
        var tree: JsObj = rebuilt
        try {
            for (event in buffered) tree = reduce(tree, event)
        } catch (_: RuntimeException) {
            return HydrationOutcome.Failed
        }
        fetchedDetails[sessionId] = session.details
        tailSinceBase[sessionId] = session.tail.size + buffered.count { it["seq"] != null }
        return HydrationOutcome.Ready(tree)
    }

    sealed interface HydrationOutcome {
        data class Ready(val tree: JsObj) : HydrationOutcome
        data object Cancelled : HydrationOutcome
        data object Failed : HydrationOutcome
    }

    /** Forget [sessionId]'s mirror bookkeeping (its saved copy was dropped). */
    fun forgetMirrored(sessionId: String) {
        tailSinceBase.remove(sessionId)
        fetchedDetails.remove(sessionId)
    }

    /**
     * A snapshot WITH state re-bases [sessionId]: a read still running is cancelled (the state
     * wins), the tail restarts, and the fetched details of turns the state still trims stay
     * (exactly [MirrorLink.keptDetailIds], as the mirror keeps them). Returns [state] with those
     * spliced in: the tree to publish.
     */
    fun rebase(sessionId: String, state: JsObj, trimmedBefore: Int?): JsObj {
        hydrating.remove(sessionId)
        hydrationTried.add(sessionId)
        tailSinceBase[sessionId] = 0
        val keep = MirrorLink.keptDetailIds(state, trimmedBefore)
        val kept = fetchedDetails[sessionId]?.filterKeys { it in keep }?.takeIf { it.isNotEmpty() }
        if (kept == null) {
            fetchedDetails.remove(sessionId)
            return state
        }
        fetchedDetails[sessionId] = kept
        return MirrorLink.splice(state, kept)
    }

    /** One more event with a seq in [sessionId]'s tail. */
    fun countTail(sessionId: String) {
        tailSinceBase[sessionId] = (tailSinceBase[sessionId] ?: 0) + 1
    }

    fun addDetails(sessionId: String, turns: JsObj) {
        val entries = turns.entries.mapNotNull { (id, turn) -> (turn as? JsObj)?.let { id to it } }
        if (entries.isNotEmpty()) fetchedDetails[sessionId] = (fetchedDetails[sessionId] ?: emptyMap()) + entries
    }

    /**
     * §2.4: is a local checkpoint of [tree] (the published projection) due after an event of
     * [type]? Resets the tail count when it is.
     */
    fun checkpointDue(sessionId: String, type: String, tree: JsObj, every: Int, atTurnEnd: Int): Boolean {
        val tail = tailSinceBase[sessionId] ?: return false
        val due = tail >= every || (type == "turn_end" && tail >= atTurnEnd)
        if (!due || treesState.value[sessionId] !== tree) return false
        tailSinceBase[sessionId] = 0
        return true
    }
}
