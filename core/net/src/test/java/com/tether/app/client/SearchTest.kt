package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T5.3: `search` / `global-search` against the vendored wire corpus (parity-corpus/wire), and
 * RealTetherClient's half of use-tether.ts 874-881 + 1390-1435: the frames it sends, the replies
 * it publishes, and the request-id rule that keeps a superseded global reply off the screen.
 */
class SearchTest {

    private val wire = File(System.getProperty("parity.corpus") ?: "../../parity-corpus", "wire")

    private fun jsonl(name: String): List<JsonObject> =
        File(wire, name).readLines().filter { it.isNotBlank() }.map { TetherJson.parseToJsonElement(it).jsonObject }

    private fun example(type: String): JsonObject {
        val line = jsonl("client-examples.jsonl").single { it["type"]!!.jsonPrimitive.content == type }
        assertEquals("true", line["verdict"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
        return line["frame"]!!.jsonObject
    }

    private fun recorded(type: String): JsonObject =
        jsonl("discovery.jsonl").map { it["frame"]!!.jsonObject }.single { it["type"]!!.jsonPrimitive.content == type }

    // ---- client -> server: re-encoded against the recorded valid examples --------------------

    @Test fun searchReEncodesAsTheCorpusExample() {
        val recorded = example("search")
        val encoded = ClientMessage.Search(recorded["cwd"]!!.jsonPrimitive.content, recorded["query"]!!.jsonPrimitive.content)
        // Byte for byte, key order included (lib/protocol.ts:3435).
        assertEquals(recorded.toString(), encoded.encode())
    }

    @Test fun globalSearchReEncodesAsTheCorpusExampleWithEveryFilter() {
        val recorded = example("global-search")
        val encoded = ClientMessage.GlobalSearch(
            requestId = recorded["requestId"]!!.jsonPrimitive.long,
            query = recorded["query"]!!.jsonPrimitive.content,
            providers = recorded["providers"]!!.jsonArray.map { it.jsonPrimitive.content },
            since = recorded["since"]!!.jsonPrimitive.long,
            until = recorded["until"]!!.jsonPrimitive.long,
            cwd = recorded["cwd"]!!.jsonPrimitive.content,
        )
        assertEquals(recorded.toString(), encoded.encode())
    }

    @Test fun theRecordedScenarioFramesReEncode() {
        val search = recorded("search")
        assertEquals(search.toString(), ClientMessage.Search(search["cwd"]!!.jsonPrimitive.content, "parity").encode())
        // The web omits every absent filter (use-tether.ts:1421-1429): requestId, query, cwd only.
        val global = recorded("global-search")
        assertEquals(global.toString(), ClientMessage.GlobalSearch(7, "parity", cwd = global["cwd"]!!.jsonPrimitive.content).encode())
    }

    @Test fun theRecordedRepliesDecodeWithTheirHistoryFields() {
        val results = ServerMessage.parse(recorded("search-results")) as ServerMessage.SearchResults
        assertEquals("parity", results.query)
        val hit = results.hits.single()
        assertEquals("id-0002", hit.historyId)
        assertEquals(2, hit.matchCount)
        assertEquals("parity corpus seeded question parity corpus seeded answer", hit.snippet)
        assertEquals(1767225600000L, hit.createdAt)
        assertEquals("unknown", hit.origin)
        // A hit IS a HistorySession (lib/protocol.ts:1859): the sidebar row and `resume` read it so.
        val history = hit.toHistory()
        assertEquals(listOf("id-0002", "claude", "parity corpus seeded question"), listOf(history.historyId, history.provider, history.name))
        assertEquals(1767225600000L, history.updatedAt)

        val global = ServerMessage.parse(recorded("global-search-results")) as ServerMessage.GlobalSearchResults
        assertEquals(7L, global.requestId)
        assertEquals(3, global.hits.single().matchCount)
    }

    @Test fun aHitKeepsItsV89ProfileForTheResume() {
        val frame = """{"type":"global-search-results","requestId":1,"query":"q","hits":[""" +
            """{"historyId":"h","provider":"claude","name":"n","cwd":"/w","updatedAt":5,"profileId":"work","snippet":"s","matchCount":1}]}"""
        val parsed = ServerMessage.parse(TetherJson.parseToJsonElement(frame).jsonObject) as ServerMessage.GlobalSearchResults
        assertEquals("work", parsed.hits.single().toHistory().profileId)
    }

    // ---- RealTetherClient over a socket ------------------------------------------------------

    private val h = ConnectionHarness()

    @After fun tearDown() = h.close()

    private fun connected(): okhttp3.WebSocket {
        h.newClient()
        h.enqueueConnect()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return ws
    }

    private fun reply(requestId: Long, query: String, historyId: String) =
        """{"type":"global-search-results","requestId":$requestId,"query":"$query","hits":[""" +
            """{"historyId":"$historyId","provider":"codex","name":"n","cwd":"/w","updatedAt":1,"snippet":"s","matchCount":1}]}"""

    @Test fun theFirstGlobalSearchIsTheCorpusExampleOnTheWire() {
        connected()
        val params = GlobalSearchParams("  parity ", listOf("claude", "codex"), since = 0, until = 1767225600000, cwd = "/workspace/project")
        assertTrue(h.client.runGlobalSearch(params))
        assertEquals(example("global-search").toString(), h.expectFrame("global-search").toString())
        val state = h.client.globalSearchResults.value
        assertEquals(GlobalSearchResults(requestId = 1, query = "parity", pending = true), state)
    }

    @Test fun emptyFiltersAreOmitted() {
        connected()
        h.client.runGlobalSearch(GlobalSearchParams("parity", providers = emptyList(), cwd = ""))
        assertEquals("""{"type":"global-search","requestId":1,"query":"parity"}""", h.expectFrame("global-search").toString())
    }

    @Test fun aReplyToASupersededRequestNeverOverwritesTheNewerOne() {
        val ws = connected()
        h.client.runGlobalSearch(GlobalSearchParams("pa"))
        h.client.runGlobalSearch(GlobalSearchParams("parity"))
        assertEquals(1L, h.expectFrame("global-search")["requestId"]!!.jsonPrimitive.long)
        assertEquals(2L, h.expectFrame("global-search")["requestId"]!!.jsonPrimitive.long)
        // The slow reply to request 1 lands after request 2 went out: dropped.
        ws.send(reply(1, "pa", "stale"))
        h.serverBarrier(ws)
        assertEquals(GlobalSearchResults(requestId = 2, query = "parity", pending = true), h.client.globalSearchResults.value)
        ws.send(reply(2, "parity", "fresh"))
        val landed = h.await(h.client.globalSearchResults) { !it.pending }
        assertEquals(listOf("fresh"), landed.hits.map { it.historyId })
        assertEquals("parity", landed.query)
        // ... and a late duplicate of request 1 after that changes nothing either.
        ws.send(reply(1, "pa", "stale"))
        h.serverBarrier(ws)
        assertEquals(listOf("fresh"), h.client.globalSearchResults.value.hits.map { it.historyId })
    }

    @Test fun closingTheModalInvalidatesTheInFlightReply() {
        val ws = connected()
        h.client.runGlobalSearch(GlobalSearchParams("parity"))
        h.expectFrame("global-search")
        h.client.clearGlobalSearch()
        ws.send(reply(1, "parity", "late"))
        h.serverBarrier(ws)
        assertEquals(GlobalSearchResults(requestId = 2), h.client.globalSearchResults.value)
    }

    @Test fun aTooShortQueryClearsAndInvalidatesWithoutAFrame() {
        val ws = connected()
        h.client.runGlobalSearch(GlobalSearchParams("parity"))
        h.expectFrame("global-search")
        assertFalse(h.client.runGlobalSearch(GlobalSearchParams(" p ")))
        ws.send(reply(1, "parity", "late"))
        h.serverBarrier(ws)
        assertEquals(GlobalSearchResults(requestId = 2), h.client.globalSearchResults.value)
        // Nothing but the barrier followed the first request.
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
    }

    @Test fun theSidebarSearchSendsItsFrameAndPublishesTheReply() {
        val ws = connected()
        assertTrue(h.client.search("/w", "parity"))
        assertEquals("""{"type":"search","cwd":"/w","query":"parity"}""", h.expectFrame("search").toString())
        val recordedReply = recorded("search-results")
        ws.send(recordedReply.toString())
        val results = h.await(h.client.searchResults) { it.hits.isNotEmpty() }
        assertEquals("parity", results.query)
        assertEquals(listOf("id-0002"), results.hits.map { it.historyId })
        // use-tether.ts:1394 — a too-short query clears locally, no frame.
        assertFalse(h.client.search("/w", " x"))
        assertEquals(SearchResults(), h.client.searchResults.value)
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
    }

    @Test fun anUnsentSearchSaysSoAndIsNotPending() {
        h.newClient(configured = false)
        runBlocking {
            val toast = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { withTimeout(20_000) { h.client.errors.first() } }
            assertFalse(h.client.runGlobalSearch(GlobalSearchParams("parity")))
            assertEquals(NodeRegistryRules.NOT_SENT_MESSAGE, toast.await())
        }
        assertEquals(GlobalSearchResults(requestId = 1, query = "parity", pending = false), h.client.globalSearchResults.value)
        assertFalse(h.client.search("/w", "parity"))
    }

    // ---- SearchSync alone ----------------------------------------------------------------------

    @Test fun anotherServersResultsAreDroppedAndItsRepliesCannotLand() {
        val sent = mutableListOf<ClientMessage>()
        val sync = SearchSync { sent += it; true }
        sync.runGlobalSearch(GlobalSearchParams("parity"))
        sync.onFrame(ServerMessage.SearchResults("/w", "parity", listOf(com.tether.app.protocol.SearchHit("h"))))
        sync.clear()
        assertEquals(SearchResults(), sync.searchResults.value)
        assertEquals(GlobalSearchResults(requestId = 2), sync.globalSearchResults.value)
        // The old server's reply to request 1 still cannot land.
        sync.onFrame(ServerMessage.GlobalSearchResults(1, "parity", listOf(com.tether.app.protocol.SearchHit("h"))))
        assertEquals(GlobalSearchResults(requestId = 2), sync.globalSearchResults.value)
        assertEquals(1, sent.size)
    }

    @Test fun pendingKeepsThePreviousHitsUntilTheNewReply() {
        // use-tether.ts:1420 `{ ...prev, requestId, query, pending: true }`.
        val sync = SearchSync { true }
        sync.runGlobalSearch(GlobalSearchParams("par"))
        sync.onFrame(ServerMessage.GlobalSearchResults(1, "par", listOf(com.tether.app.protocol.SearchHit("a"))))
        sync.runGlobalSearch(GlobalSearchParams("parity"))
        val state = sync.globalSearchResults.value
        assertEquals(listOf("a"), state.hits.map { it.historyId })
        assertEquals("parity", state.query)
        assertTrue(state.pending)
    }
}
