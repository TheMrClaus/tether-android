package com.tether.app.client

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-jtfq: what the real client publishes while an agent message streams. Every state the UI can collect is counted
 * across a run of `message_delta` frames; a plain delta may change the projection tree and its typed projection and
 * nothing else, or every collector (and everything that reads it) recomposes at the delta rate.
 */
class StreamingEmissionsTest {
    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    private fun connected(): WebSocket {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return ws
    }

    private fun fullState() =
        """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","nativeSessionId":null,"cliCapabilities":[],
            "cliVersion":null,"cliInventory":null,"mcpHealth":{},"rateLimit":null,"rateLimitResume":null,
            "fastModeState":null,"fastModeDisabledReason":null,"accountAuth":null,"todo":null,"todoTasks":[],
            "status":"ready","lastTurnOutcome":null,"lastError":null,"unattributedPermissionDenials":[],
            "providerNotices":[],"lastModelFallback":null,"notices":[],"dismissedNotices":[],
            "backgroundCommands":[],"backgroundTasks":[],"spawnedRuns":[],
            "spawnedRunKeys":[],"turnOrder":[],"turnsById":{},"activeTurnId":null,"queuedMessages":[]}"""

    /** The server's `session` frame: onHeadlessStateChange stamps updatedAt / lastMessageAt and the journal head on every event. */
    private fun sessionFrame(ws: WebSocket, seq: Int, status: String = "active") = ws.send(
        """{"type":"session","session":{"id":"s1","provider":"claude","name":"s1","cwd":"/w","status":"$status",
           "startedAt":1,"updatedAt":${10_000 + seq},"lastMessageAt":${10_000 + seq},"lastSeq":$seq,"endedAt":null,"exitCode":null,
           "pinned":false,"runtimeArchived":false,"mode":"headless"}}""",
    )

    private fun event(ws: WebSocket, type: String, seq: Int, extra: String = "") =
        ws.send("""{"type":"event","sessionId":"s1","event":{"type":"$type","turnId":"t1","seq":$seq,"ts":${2000 + seq}$extra}}""")

    @Test
    fun aPlainDeltaPublishesOnlyTheTreeAndTheProjection() {
        val ws = connected()
        h.client.attach("s1")
        h.expectFrame("attach")
        ws.send(snapshotFrame("s1", 1, fullState()))
        h.await(h.client.projectionTrees) { it.containsKey("s1") }
        sessionFrame(ws, 1, "ready")
        h.await(h.client.sessions) { list -> list.any { it.id == "s1" } }
        event(ws, "turn_started", 2)
        sessionFrame(ws, 2)
        event(ws, "message_delta", 3, ""","blockId":"b1","text":"start """")
        h.await(h.client.projections) { it["s1"]?.turnsById?.get("t1")?.blocksById?.get("b1")?.text == "start " }

        val flows = TetherClient::class.java.methods
            .filter { it.parameterCount == 0 && StateFlow::class.java.isAssignableFrom(it.returnType) }
            .mapNotNull { m -> runCatching { m.name to (m.invoke(h.client) as StateFlow<*>) }.getOrNull() }
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        for ((name, flow) in flows) {
            val counter = counts.getOrPut(name) { AtomicInteger() }
            var first = true
            scope.launch { flow.collect { if (first) first = false else counter.incrementAndGet() } }
        }
        // What the screens draw (TetherViewModel.displaySessions): the same list through displayStable.
        val displayed = com.tether.app.ui.displayStable(h.client.sessions, scope)
        val displayedCount = AtomicInteger()
        scope.launch { var first = true; displayed.collect { if (first) first = false else displayedCount.incrementAndGet() } }
        val n = 30
        for (i in 1..n) {
            event(ws, "message_delta", 3 + i, ""","blockId":"b1","text":"word$i """")
            sessionFrame(ws, 3 + i)
            h.await(h.client.sessions) { list -> list.firstOrNull { it.id == "s1" }?.lastSeq == 3L + i }
            h.await(h.client.projections) { it["s1"]?.turnsById?.get("t1")?.blocksById?.get("b1")?.text?.contains("word$i ") == true }
        }
        scope.cancel()
        val changed = counts.filterValues { it.get() > 0 }.mapValues { it.value.get() }.toSortedMap()
        println("EMISSIONS over $n deltas of ${flows.size} flows: $changed")
        val allowed = setOf("getProjectionTrees", "getProjections", "getSessions")
        val extra = changed.keys - allowed
        assertTrue("a plain delta also published: ${changed.filterKeys { it in extra }}", extra.isEmpty())
        assertTrue("the raw list changed with every frame: $changed", changed["getSessions"] == n)
        assertTrue("the drawn list changed ${displayedCount.get()} times over $n frames that stamped only updatedAt, lastMessageAt and lastSeq", displayedCount.get() == 0)
    }
}
