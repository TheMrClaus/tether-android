package com.tether.app.client.sync

import com.tether.app.client.snapshotFrame
import com.tether.app.client.type
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.num
import com.tether.app.protocol.tree.str
import com.tether.app.mirror.SessionBaseEntity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T13.1 acceptance gate (SYNC_DESIGN §11): EVERY reducer corpus case is driven through a scripted
 * server, RealTetherClient, the mirror writer and a temp-file DB, with process deaths at random
 * split points: the client and the DB are re-instantiated, the new process hydrates from the
 * mirror, re-attaches from the persisted cursor, and continues.
 *
 * Reference run A: today's client (no mirror) over the same frames, never interrupted. Run B:
 * the mirrored client with deaths. At every step:
 * - B's projection == A's projection ("mirror -> hydrate -> continue equals the in-memory result");
 * - A's projection == the corpus `expectedProjectionAfterEachStep` for every case whose seqs a
 *   journal can emit (contiguous, all stamped). The rest (duplicate / seqless / gapped seqs, which
 *   exercise the reducer's own seq handling) go through CursorTracker's dedupe, so the client
 *   legitimately differs from a raw fold; for them run B is held to run A;
 * - fold(DB.base, DB.tail) == B's projection whenever the DB claims coverage (a cursor);
 * - local checkpoints are promoted at random thresholds and change nothing.
 *
 * The server answers each attach as server.mjs does (§1.1): stateless at head, state when
 * behind, reset + state when ahead. After a death its state is A's projection at that step (a
 * server consistent with the uninterrupted client); A's own resyncs get the true fold.
 */
@RunWith(RobolectricTestRunner::class)
class JournalMirrorConformanceTest {

    private class Case(
        val name: String,
        val sessionId: String,
        val initial: JsObj,
        val events: List<JsObj>,
        val expected: List<JsObj?>,
    ) {
        val seqs: List<Long?> = events.map { it["seq"].num?.toLong() }
        val firstSeq: Long = seqs.firstOrNull { it != null } ?: 1L
        val baseSeq: Long = maxOf(0L, firstSeq - 1)

        /** Seqs a journal can emit: every event stamped, contiguous from the first. */
        val journalShaped: Boolean = seqs.all { it != null } && seqs.withIndex().all { (i, s) -> s == firstSeq + i }
    }

    private class Stats {
        var cases = 0
        var journalShaped = 0
        var steps = 0
        var corpusChecks = 0
        var dbChecks = 0
        var deaths = 0
        var statelessAtHead = 0
        var stateAfterDeath = 0
        var heldHydrations = 0
        var localBasesSeen = 0
    }

    private val corpusDir = File(System.getProperty("parity.corpus"), "reducer")

    private fun load(file: File): Case {
        val root = JsCodec.parse(file.readText()) as JsObj
        val initial = root["expectedInitialProjection"] as JsObj
        val events = (root["inputEvents"] as JsArr).map { it as JsObj }
        val expected = (root["expectedProjectionAfterEachStep"] as JsArr).map { if (it === JsNull) null else it as JsObj }
        require(events.size == expected.size) { "${file.name}: steps/events mismatch" }
        return Case(file.nameWithoutExtension, initial["tetherSessionId"].str ?: "s-parity", initial, events, expected)
    }

    private fun readyWith(sessionId: String) =
        """{"type":"ready","protocolVersion":137,"nativeProtocolFloor":129,"providers":[],"workspaceRoot":null,
            "sessions":[{"id":"$sessionId","provider":"claude","name":"conformance","cwd":"/work/parity","status":"ready",
            "startedAt":1,"updatedAt":1,"pinned":false,"runtimeArchived":false,"mode":"headless"}]}"""

    /** The server's reply to one attach (tether:engines/session-manager.mjs:1682-1709). */
    private fun reply(h: MirrorHarness, attach: JsonObject, sessionId: String, lastSeq: Long, truth: JsObj): String {
        val after = attach["afterSeq"]?.jsonPrimitive?.longOrNull
        val frame = when {
            after != null && after > lastSeq -> snapshotFrame(sessionId, lastSeq, JsCodec.stringify(truth), reset = true)
            after != null && after == lastSeq -> snapshotFrame(sessionId, lastSeq, state = null)
            else -> snapshotFrame(sessionId, lastSeq, JsCodec.stringify(truth))
        }
        h.ws.send(frame)
        return when {
            after != null && after == lastSeq -> "stateless"
            else -> "state"
        }
    }

    /** Let the client handle everything sent so far, answering its attaches for [sessionId]. */
    private fun settle(h: MirrorHarness, sessionId: String, lastSeq: Long, truth: JsObj, replies: MutableList<String>? = null) {
        h.serverBarrier()
        while (true) {
            val attaches = h.framesUntilBarrier().filter {
                it.type() == "attach" && it["sessionId"]?.jsonPrimitive?.content == sessionId
            }
            if (attaches.isEmpty()) return
            for (attach in attaches) replies?.add(reply(h, attach, sessionId, lastSeq, truth)) ?: reply(h, attach, sessionId, lastSeq, truth)
            h.serverBarrier()
        }
    }

    private fun deliver(h: MirrorHarness, sessionId: String, event: JsObj) {
        h.ws.send("""{"type":"event","sessionId":"$sessionId","event":${JsCodec.stringify(event)}}""")
    }

    private fun tree(h: MirrorHarness, sessionId: String): JsObj =
        h.await(h.client.projectionTrees) { it.containsKey(sessionId) }.getValue(sessionId)

    /** Run A: today's client, no mirror, never interrupted. Returns its projection after 0..n events. */
    private fun referenceRun(case: Case): List<String> {
        val h = MirrorHarness(withMirror = false)
        try {
            h.startServer()
            h.boot(ready = readyWith(case.sessionId))
            var truth = case.initial
            var lastSeq = case.baseSeq
            h.client.attach(case.sessionId)
            settle(h, case.sessionId, lastSeq, truth)
            val out = ArrayList<String>()
            out += JsCodec.canonical(tree(h, case.sessionId))
            for ((i, event) in case.events.withIndex()) {
                truth = reduce(truth, event)
                case.seqs[i]?.let { lastSeq = maxOf(lastSeq, it) }
                deliver(h, case.sessionId, event)
                settle(h, case.sessionId, lastSeq, truth)
                out += JsCodec.canonical(tree(h, case.sessionId))
            }
            return out
        } finally {
            h.close()
        }
    }

    /** Run B: the mirrored client, killed and restarted at random split points. */
    private fun mirroredRun(case: Case, reference: List<String>, stats: Stats) {
        val rng = Random(case.name.hashCode())
        val n = case.events.size
        val deaths = (0..n).shuffled(rng).take(1 + rng.nextInt(3)).toSet()
        // Random local-checkpoint thresholds (a quarter of the cases keep the production ones).
        val every = if (rng.nextInt(4) == 0) 2_000 else 1 + rng.nextInt(6)
        val atTurnEnd = if (every == 2_000) 500 else 1 + rng.nextInt(3)
        val h = MirrorHarness(checkpointEvery = every, checkpointAtTurnEnd = atTurnEnd)
        try {
            h.startServer()
            h.boot(ready = readyWith(case.sessionId))
            var lastSeq = case.baseSeq
            h.client.attach(case.sessionId)
            settle(h, case.sessionId, lastSeq, case.initial)
            var held: CountDownLatch? = null

            // [withDb] = false right before a death: the write-behind batch must NOT be flushed
            // first, or a kill could never lose one.
            fun check(k: Int, withDb: Boolean = true) {
                val shown = JsCodec.canonical(tree(h, case.sessionId))
                assertEquals("${case.name} step $k: mirrored client != in-memory client", reference[k], shown)
                if (case.journalShaped && k > 0) {
                    case.expected[k - 1]?.let {
                        assertEquals("${case.name} step $k: != corpus", JsCodec.canonical(it), shown)
                        stats.corpusChecks++
                    }
                }
                if (!withDb) {
                    stats.steps++
                    return
                }
                val stored = h.dbSession(case.sessionId)
                if (case.journalShaped) assertNotNull("${case.name} step $k: journal-shaped case lost its mirror", stored?.cursor)
                if (stored?.cursor != null) {
                    assertEquals("${case.name} step $k: fold(DB) != tree", shown, JsCodec.canonical(MirrorLink.rebuild(stored)))
                    stats.dbChecks++
                    if (stored.origin == SessionBaseEntity.ORIGIN_LOCAL) stats.localBasesSeen++
                }
                stats.steps++
            }

            check(0, withDb = 0 !in deaths)
            for (k in 0..n) {
                if (k > 0) {
                    case.seqs[k - 1]?.let { lastSeq = maxOf(lastSeq, it) }
                    deliver(h, case.sessionId, case.events[k - 1])
                    settle(h, case.sessionId, lastSeq, JsCodec.parse(reference[k]) as JsObj)
                    held?.let {
                        it.countDown()
                        held = null
                    }
                    check(k, withDb = k !in deaths)
                }
                if (k !in deaths) continue
                // Process death after k events.
                stats.deaths++
                h.kill(flushFirst = rng.nextBoolean())
                val hold = k < n && rng.nextBoolean()
                val latch = if (hold) CountDownLatch(1) else null
                h.boot(ready = readyWith(case.sessionId)) { process ->
                    // Hold the new process's hydration read until the next event arrived.
                    if (latch != null) process.mirror.beforeHydrateRead = { latch.await(10, TimeUnit.SECONDS) }
                }
                h.client.attach(case.sessionId) // the UI re-opens the session (a no-op if ready attached it)
                val replies = ArrayList<String>()
                settle(h, case.sessionId, lastSeq, JsCodec.parse(reference[k]) as JsObj, replies)
                assertTrue("${case.name} after death at $k: exactly one attach, got $replies", replies.size == 1)
                if (replies.single() == "stateless") stats.statelessAtHead++ else stats.stateAfterDeath++
                if (latch != null) {
                    held = latch
                    stats.heldHydrations++
                } else {
                    check(k) // the saved copy (or the fresh state) is exactly the in-memory result
                }
            }
        } finally {
            h.close()
        }
    }

    @Test
    fun everyReducerCorpusCaseSurvivesProcessDeathThroughTheMirror() {
        val files = corpusDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }!!.sortedBy { it.name }
        val stats = Stats()
        for (file in files) {
            val case = load(file)
            val reference = referenceRun(case)
            if (case.journalShaped) {
                // The reference client itself equals the corpus fold (T2.3 parity).
                for ((i, expected) in case.expected.withIndex()) {
                    if (expected != null) assertEquals("${case.name} step ${i + 1}: in-memory client != corpus", JsCodec.canonical(expected), reference[i + 1])
                }
                stats.journalShaped++
            }
            mirroredRun(case, reference, stats)
            stats.cases++
        }
        println(
            "JournalMirrorConformanceTest: cases=${stats.cases}/${files.size} (journal-shaped ${stats.journalShaped}, " +
                "seq-irregular ${stats.cases - stats.journalShaped}) steps=${stats.steps} corpusChecks=${stats.corpusChecks} " +
                "dbChecks=${stats.dbChecks} deaths=${stats.deaths} statelessAtHead=${stats.statelessAtHead} " +
                "stateAfterDeath=${stats.stateAfterDeath} heldHydrations=${stats.heldHydrations} localBaseSteps=${stats.localBasesSeen}",
        )
        // 100 % of the corpus, and every path the gate is about was actually taken.
        assertEquals(files.size, stats.cases)
        assertTrue(files.size >= 70)
        assertTrue("no cold start hit the at-head stateless path", stats.statelessAtHead > 0)
        assertTrue("no cold start was behind", stats.stateAfterDeath > 0)
        assertTrue("no hydration raced a live event", stats.heldHydrations > 0)
        assertTrue("no local checkpoint was promoted", stats.localBasesSeen > 0)
    }
}
