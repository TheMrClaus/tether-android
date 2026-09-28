package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.SearchHit
import com.tether.app.protocol.fold.jsTrim
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The sidebar's workspace-scoped content search (use-tether.ts:239 `searchResults`): the last
 * `search-results` frame. [query] is what the server searched (trimmed); the sidebar trusts
 * [hits] only while it matches what is typed (dashboard.tsx:853-856).
 */
data class SearchResults(val query: String = "", val hits: List<SearchHit> = emptyList())

/**
 * The cross-harness global search (use-tether.ts:240-245 `globalSearchResults`). [requestId] is
 * the newest request's token; [pending] is true between a sent request and its own reply.
 */
data class GlobalSearchResults(
    val requestId: Long = 0,
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val pending: Boolean = false,
)

/** `runGlobalSearch` params (components/global-search.tsx GlobalSearchParams). */
data class GlobalSearchParams(
    val query: String,
    val providers: List<String>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val cwd: String? = null,
)

/**
 * T5.3: the two search surfaces' client state, apart from the connection code (like
 * [SidebarSync]): RealTetherClient routes the two reply frames here ([onFrame]), clears it with
 * the other per-server views ([clear]) and hands it the socket ([send]). Mirrors
 * hooks/use-tether.ts 874-881 (the replies) and 1390-1435 (`search`, `runGlobalSearch`,
 * `clearGlobalSearch`). Thread-safe: state changes happen under this object's monitor, and
 * [send] is always called OUTSIDE it — the client's send takes its own lock, under which it also
 * delivers the replies to [onFrame], so holding both in the other order could deadlock.
 */
internal class SearchSync(private val send: (ClientMessage) -> Boolean) {
    val searchResults = MutableStateFlow(SearchResults())
    val globalSearchResults = MutableStateFlow(GlobalSearchResults())

    /** use-tether.ts:244 `globalSearchIdRef`: the monotonic token of the newest request. */
    private var globalSearchId = 0L

    /**
     * use-tether.ts:1393-1399: a blank / too-short query clears locally (the server would return
     * none); anything else asks for `search {cwd, query}`. Returns whether a frame went out.
     */
    fun search(cwd: String, query: String): Boolean {
        if (jsTrim(query).length < MIN_QUERY) {
            clearSearchResults()
            return false
        }
        return send(ClientMessage.Search(cwd, query))
    }

    /** use-tether.ts:1351 — selecting a workspace drops the previous workspace's hits. */
    @Synchronized
    fun clearSearchResults() {
        searchResults.value = SearchResults()
    }

    /**
     * use-tether.ts:1406-1430. Every call takes a NEW request id, so an in-flight reply for an
     * older query / filter set can never land ([onFrame] drops it). A too-short query clears the
     * results. The web leaves `pending` set when its socket refuses the frame (a spinner that
     * never stops); here an unsent request is not pending. Returns whether a frame went out.
     */
    fun runGlobalSearch(params: GlobalSearchParams): Boolean {
        val query = jsTrim(params.query)
        if (query.length < MIN_QUERY) {
            clearGlobalSearch()
            return false
        }
        val requestId = synchronized(this) {
            val id = ++globalSearchId
            globalSearchResults.value = globalSearchResults.value.copy(requestId = id, query = query, pending = true)
            id
        }
        val sent = send(
            ClientMessage.GlobalSearch(
                requestId = requestId,
                query = query,
                providers = params.providers?.takeIf { it.isNotEmpty() },
                since = params.since,
                until = params.until,
                cwd = params.cwd?.takeIf { it.isNotEmpty() },
            ),
        )
        if (!sent) {
            synchronized(this) {
                val current = globalSearchResults.value
                if (current.requestId == requestId) globalSearchResults.value = current.copy(pending = false)
            }
        }
        return sent
    }

    /** use-tether.ts:1432-1435: closing the modal invalidates any in-flight reply. */
    @Synchronized
    fun clearGlobalSearch() {
        globalSearchId += 1
        globalSearchResults.value = GlobalSearchResults(requestId = globalSearchId)
    }

    /** Folds one reply frame; false for a frame this class does not own. */
    @Synchronized
    fun onFrame(message: ServerMessage): Boolean {
        when (message) {
            is ServerMessage.SearchResults -> searchResults.value = SearchResults(message.query, message.hits)
            // use-tether.ts:876-880 — a reply to a superseded request is dropped.
            is ServerMessage.GlobalSearchResults -> if (message.requestId == globalSearchId) {
                globalSearchResults.value = GlobalSearchResults(message.requestId, message.query, message.hits, pending = false)
            }
            else -> return false
        }
        return true
    }

    /**
     * Another server's results must never show. The request counter keeps counting, so a reply
     * from before the switch still cannot match a newer request.
     */
    @Synchronized
    fun clear() {
        globalSearchId += 1
        searchResults.value = SearchResults()
        globalSearchResults.value = GlobalSearchResults(requestId = globalSearchId)
    }

    companion object {
        /** use-tether.ts:1394, 1414 and server.mjs:8279, 8305 — shorter queries are not searched. */
        const val MIN_QUERY = 2
    }
}
