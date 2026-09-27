package com.tether.app.protocol.conformance

import com.tether.app.protocol.fold.initialSessionState
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.tree.JsObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * T2.1D: the legacy adapter over every step of every reducer corpus case (70 cases, the v128
 * fold's own output):
 *  - every projection adapts, with no turn or block left out and no key falling back
 *    (misfits == 0: the typed model reads all of what it models);
 *  - the memoized adapter, fed the live sequence, equals a fresh one-shot adapt at every step
 *    (memo correctness), and an unchanged tree (`===`) yields the same typed instance;
 *  - an untouched turn keeps its typed instance across a step (the per-turn memo works).
 */
class LegacyAdapterCorpusTest {

    @Test
    fun everyCorpusStepAdaptsAndTheMemoMatchesAFreshDecode() {
        var steps = 0
        var reusedTurns = 0
        var misfits = 0
        for (name in CorpusCase.names()) {
            val case = CorpusCase.load(name)
            val adapter = LegacyProjectionAdapter()
            var state = initialSessionState(case.initial)
            var typed = check(name, -1, adapter, state)
            for ((index, event) in case.inputEvents.withIndex()) {
                val next = reduce(state, event)
                val nextTyped = check(name, index, adapter, next)
                if (next === state) assertSame("$name step $index: same tree, same projection", typed, nextTyped)
                for ((turnId, turn) in nextTyped.turnsById) {
                    val before = typed.turnsById[turnId] ?: continue
                    val sameSource = (state["turnsById"] as JsObj)[turnId] === (next["turnsById"] as JsObj)[turnId]
                    if (sameSource) {
                        assertSame("$name step $index: untouched turn $turnId re-decoded", before, turn)
                        reusedTurns++
                    }
                }
                state = next
                typed = nextTyped
                steps++
            }
            misfits += adapter.misfits
        }
        println("LegacyAdapterCorpusTest: $steps steps adapted, $reusedTurns untouched-turn reuses, misfits=$misfits")
        assertEquals("objects that did not fit the typed model", 0, misfits)
    }

    private fun check(name: String, index: Int, adapter: LegacyProjectionAdapter, state: JsObj): SessionProjection {
        val adapted = adapter.adapt(state)
        assertNotNull("$name step $index: adapter rejected the session", adapted)
        val typed = adapted!!
        assertEquals("$name step $index: memoized != fresh decode", LegacyProjectionAdapter.adaptOnce(state), typed)
        val turns = state["turnsById"] as JsObj
        assertEquals("$name step $index: turns left out", turns.size, typed.turnsById.size)
        for ((turnId, turn) in turns) {
            val blocks = (turn as JsObj)["blocksById"] as JsObj
            assertEquals("$name step $index: blocks left out of $turnId", blocks.size, typed.turnsById.getValue(turnId).blocksById.size)
        }
        return typed
    }
}
