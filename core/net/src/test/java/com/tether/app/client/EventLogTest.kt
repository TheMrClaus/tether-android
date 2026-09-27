package com.tether.app.client

import com.tether.app.protocol.LogEntry
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4.5: the event-log fold of use-tether.ts:1047-1059 (seq dedupe, bootId restart, 500 cap) and
 * the tolerant read of GET /api/stats (server.mjs computeStats).
 */
class EventLogTest {

    private fun entry(seq: Long, level: String? = "info") = LogEntry(seq = seq, ts = seq * 1000, level = level, event = "e$seq")

    private fun batch(boot: String, vararg seqs: Long) = ServerMessage.Log(seqs.map { entry(it) }, boot)

    @Test
    fun batchesAppendInOrderAndTheReplayedTailDedupesBySeq() {
        val log = EventLog().accept(batch("b1", 1, 2, 3)).accept(batch("b1", 2, 3, 4, 5))
        assertEquals(listOf(1L, 2, 3, 4, 5), log.entries.map { it.seq })
        assertEquals("b1", log.bootId)
    }

    @Test
    fun onlySeqsAboveTheLastKeptOneAreFreshEvenOutOfOrder() {
        // The web filters on `entry.seq > lastSeq` (the tail's seq), not on set membership.
        val log = EventLog().accept(batch("b1", 5)).accept(batch("b1", 3, 6, 4))
        assertEquals(listOf(5L, 6), log.entries.map { it.seq })
    }

    @Test
    fun aBatchThatAddsNothingKeepsTheSameLog() {
        val log = EventLog().accept(batch("b1", 1, 2))
        assertSame(log, log.accept(batch("b1", 1, 2)))
    }

    @Test
    fun aNewBootIdIsAServerRestartThatStartsTheLogOver() {
        val log = EventLog().accept(batch("b1", 10, 11)).accept(batch("b2", 1, 2))
        assertEquals(listOf(1L, 2), log.entries.map { it.seq })
        assertEquals("b2", log.bootId)
    }

    @Test
    fun aRestartWithAnEmptyBatchStillEmptiesTheLog() {
        val log = EventLog().accept(batch("b1", 10)).accept(ServerMessage.Log(emptyList(), "b2"))
        assertTrue(log.entries.isEmpty())
        assertEquals("b2", log.bootId)
    }

    @Test
    fun theFirstBatchIsNeverARestartAndAnEmptyOneRecordsTheBoot() {
        val first = EventLog().accept(ServerMessage.Log(emptyList(), "b1"))
        assertTrue(first.entries.isEmpty())
        assertEquals("b1", first.bootId)
        assertEquals(listOf(1L), first.accept(batch("b1", 1)).entries.map { it.seq })
    }

    @Test
    fun theSignInGenerationSurvivesBatchesAndRestarts() {
        val log = EventLog(generation = 4).accept(batch("b1", 1)).accept(batch("b2", 1)).accept(batch("b2", 1))
        assertEquals(4L, log.generation)
    }

    @Test
    fun theLogKeepsTheNewest500() {
        var log = EventLog()
        for (start in 1L..600L step 100) log = log.accept(batch("b", *(start until start + 100).toList().toLongArray()))
        assertEquals(EventLog.LIMIT, log.entries.size)
        assertEquals(101L, log.entries.first().seq)
        assertEquals(600L, log.entries.last().seq)
    }

    @Test
    fun everyLevelButInfoIsAWarningIncludingAMissingOne() {
        assertFalse(entry(1, "info").isWarning)
        assertTrue(entry(1, "warn").isWarning)
        assertTrue(entry(1, "error").isWarning)
        assertTrue(entry(1, null).isWarning)
    }

    private fun json(text: String) = TetherJson.parseToJsonElement(text) as JsonObject

    @Test
    fun statsReadEveryFieldTheDialogShows() {
        // The computeStats() shape at PARITY_BASE (server.mjs:4676-4705).
        val stats = ServerStats.fromJson(
            json(
                """{"ok":true,"now":1767225600000,"uptimeMs":3723000,"pid":4242,"protocolVersion":128,
                   "headless":{"mode":"claude,codex","persistent":true},
                   "sessions":{"total":7,"byStatus":{"ready":3,"active":1,"waiting":0,"exited":3},"byMode":{"headless":6}},
                   "headlessRuntime":{"warm":2,"activeTurns":1,"maxConcurrentTurns":4},
                   "boot":{"headlessReconciled":0,"headlessChildrenReaped":0,"selfCheck":null},
                   "memory":{"rss":157286400,"heapUsed":52428800,"heapTotal":80000000},"clients":3,"eventLogSize":120}""",
            ),
        )
        assertEquals(
            ServerStats(
                uptimeMs = 3_723_000, pid = 4242, protocolVersion = 128, headlessMode = "claude,codex",
                headlessPersistent = true, sessionsTotal = 7, sessionsHeadless = 6,
                runtime = ServerStats.Runtime(warm = 2, activeTurns = 1, maxConcurrentTurns = 4),
                memoryRss = 157_286_400, memoryHeapUsed = 52_428_800, clients = 3,
            ),
            stats,
        )
    }

    @Test
    fun missingOrOddStatsBranchesReadAsTheirZeroValue() {
        val stats = ServerStats.fromJson(json("""{"ok":true,"uptimeMs":"5","headless":{"mode":null,"persistent":"true"},"sessions":{}}"""))
        assertEquals(0L, stats.uptimeMs)
        assertNull(stats.headlessMode)
        assertFalse(stats.headlessPersistent)
        assertNull(stats.sessionsHeadless)
        assertNull(stats.runtime)
        assertEquals(0L, stats.memoryRss)
    }
}
