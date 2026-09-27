package com.tether.app.protocol.fold

import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2.1D stress / perf bar for T14.1 (docs/parity/T2.1_PLAN.md, optional bar adopted): a
 * 5k-block transcript, then 20k `message_delta` events, each taken the way RealTetherClient
 * takes it — JsonObject → tree, v128 `reduce`, memoized [LegacyProjectionAdapter] — timed per
 * delta (p50 / p99 / max) plus retained heap per block.
 *
 * Two shapes: SPREAD (100 finished turns × 50 blocks, deltas stream into the live 101st turn,
 * a new message block every 500 deltas) — the realistic transcript — and ONE-TURN (all 5k blocks
 * in the live turn, the adapter's worst case: it re-assembles that turn's block map per delta).
 *
 * The bar is p99 < 2 ms per delta on the JVM. It is asserted for both shapes unless
 * `-Pparity.perfAssert=false` (a slow or shared CI box); the numbers are always printed and
 * written to build/parity/stress-fold-adapter.txt.
 */
class FoldAdapterStressTest {

    private class Result(val name: String, val p50: Double, val p99: Double, val max: Double, val treeBytesPerBlock: Long, val typedBytesPerBlock: Long)

    @Test
    fun fiveThousandBlocksTwentyThousandDeltas() {
        val spread = run("spread 100x50 + live turn", turns = 100, blocksPerTurn = 50)
        val oneTurn = run("one live turn x5000", turns = 0, blocksPerTurn = 0, liveBlocks = 5_000)
        val report = buildString {
            appendLine("T2.1D fold + legacy adapter stress (JVM ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} cpus)")
            for (r in listOf(spread, oneTurn)) {
                appendLine(
                    String.format(
                        "  %-28s per delta p50=%.3f ms p99=%.3f ms max=%.3f ms | retained/block: tree≈%d B, +typed≈%d B",
                        r.name, r.p50, r.p99, r.max, r.treeBytesPerBlock, r.typedBytesPerBlock,
                    ),
                )
            }
        }
        print(report)
        File("build/parity").mkdirs()
        File("build/parity/stress-fold-adapter.txt").writeText(report)
        if (System.getProperty("parity.perfAssert") != "false") {
            for (r in listOf(spread, oneTurn)) assertTrue("${r.name}: p99 ${r.p99} ms >= 2 ms", r.p99 < 2.0)
        }
    }

    private fun run(name: String, turns: Int, blocksPerTurn: Int, liveBlocks: Int = 0): Result {
        var seq = 0L
        fun ev(type: String, turnId: String, extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = {}): JsObj {
            seq++
            return JsCodec.fromJson(
                buildJsonObject {
                    put("type", type)
                    put("turnId", turnId)
                    put("seq", seq)
                    put("ts", 1_790_000_000_000L + seq * 1000)
                    extra()
                },
            ) as JsObj
        }
        val filler = "The quick brown fox jumps over the lazy dog; ".repeat(4)

        gc()
        val heapBefore = usedHeap()
        var state = initialSessionState("s1", "claude", "/workspace")
        for (t in 0 until turns) {
            val turnId = "t$t"
            state = reduce(state, ev("turn_started", turnId))
            for (b in 0 until blocksPerTurn) {
                val blockId = "m$t:$b"
                state = reduce(state, ev("message_started", turnId) { put("blockId", blockId) })
                state = reduce(state, ev("message_delta", turnId) { put("blockId", blockId); put("text", filler) })
                state = reduce(state, ev("message_completed", turnId) { put("blockId", blockId); put("text", filler) })
            }
            state = reduce(state, ev("turn_end", turnId) { put("outcome", "ok") })
        }
        val live = "live"
        state = reduce(state, ev("turn_started", live))
        for (b in 0 until liveBlocks) {
            val blockId = "l:$b"
            state = reduce(state, ev("message_started", live) { put("blockId", blockId) })
            state = reduce(state, ev("message_completed", live) { put("blockId", blockId); put("text", filler) })
        }
        val blocks = turns * blocksPerTurn + liveBlocks
        gc()
        val heapTree = usedHeap()
        val adapter = LegacyProjectionAdapter()
        var typed: SessionProjection = adapter.adapt(state)!!
        gc()
        val heapTyped = usedHeap()

        // The deltas: pre-built wire objects (the frame parse is not the reducer's cost), each
        // converted, folded and adapted inside the timed region.
        val deltaCount = 20_000
        val warmup = 5_000
        val wire = ArrayList<JsonObject>(warmup + deltaCount)
        var block = 0
        for (i in 0 until warmup + deltaCount) {
            if (i % 500 == 0) {
                block++
                seq++
                wire.add(buildJsonObject { put("type", "message_started"); put("turnId", live); put("blockId", "d:$block"); put("seq", seq); put("ts", 1_790_000_000_000L + seq * 1000) })
            }
            seq++
            wire.add(buildJsonObject { put("type", "message_delta"); put("turnId", live); put("blockId", "d:$block"); put("text", "tok$i "); put("seq", seq); put("ts", 1_790_000_000_000L + seq * 1000) })
        }
        val times = LongArray(deltaCount)
        var measured = 0
        var deltasSeen = 0
        for (event in wire) {
            val isDelta = event["type"].toString() == "\"message_delta\""
            val start = System.nanoTime()
            state = reduce(state, JsCodec.fromJson(event) as JsObj)
            typed = adapter.adapt(state)!!
            val elapsed = System.nanoTime() - start
            if (isDelta) {
                if (deltasSeen >= warmup) times[measured++] = elapsed
                deltasSeen++
            }
        }
        assertEquals(deltaCount, measured)
        val liveTurn = typed.turnsById.getValue(live)
        // Every pre-filled block plus one per 500 deltas, all adapted.
        assertEquals(blocks + block, typed.turnsById.values.sumOf { it.blocksById.size })
        assertTrue(liveTurn.blocksById.getValue("d:$block").text!!.endsWith("tok${warmup + deltaCount - 1} "))
        assertEquals(LegacyProjectionAdapter.adaptOnce(state), typed)

        times.sort()
        fun ms(nanos: Long) = nanos / 1_000_000.0
        return Result(
            name = name,
            p50 = ms(times[deltaCount / 2]),
            p99 = ms(times[(deltaCount * 99) / 100]),
            max = ms(times[deltaCount - 1]),
            treeBytesPerBlock = (heapTree - heapBefore) / blocks,
            typedBytesPerBlock = (heapTyped - heapTree) / blocks,
        ).also {
            // Keep the measured structures reachable until the heap was sampled.
            check(state.isNotEmpty() && typed.turnOrder.isNotEmpty())
        }
    }

    private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun gc() {
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
    }
}
