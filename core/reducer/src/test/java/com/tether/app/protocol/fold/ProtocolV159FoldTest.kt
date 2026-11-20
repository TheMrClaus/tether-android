package com.tether.app.protocol.fold

import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * tth-5i8m (D7 2.5, server protocol 159): `cancelled.cause` / `turn_end.cause` / `TurnProjection.errorCause` gain the value
 * `"engine_restart"` (a requested engine restart ended the turn). `foldCancelled` folds it exactly like "interrupted" (the
 * server's events.ts folds both); a plain Stop still reads "interrupted"; an unknown cause is still ignored.
 */
class ProtocolV159FoldTest {
    private fun turn(state: JsObj): JsObj = ((state["turnsById"] as JsObj)["t1"] as JsObj)

    @Test fun aCancelledTurnFoldsTheEngineRestartCause() {
        val state = foldTree(freshTree(), ev("turn_started", "t1", seq = 1, ts = 1), ev("cancelled", "t1") { put("cause", "engine_restart") })
        assertEquals(JsStr("engine_restart"), turn(state)["errorCause"])
        assertEquals("engine_restart", checkNotNull(LegacyProjectionAdapter.adaptOnce(state)).turnsById.getValue("t1").errorCause)
    }

    @Test fun aPlainStopStillFoldsInterruptedAndAnUnknownCauseNothing() {
        val stop = foldTree(freshTree(), ev("turn_started", "t1", seq = 1, ts = 1), ev("cancelled", "t1") { put("cause", "interrupted") })
        assertEquals(JsStr("interrupted"), turn(stop)["errorCause"])
        val unknown = foldTree(freshTree(), ev("turn_started", "t1", seq = 1, ts = 1), ev("cancelled", "t1") { put("cause", "something_new") })
        assertNull(turn(unknown)["errorCause"])
    }

    @Test fun anUnknownOutcomeTurnEndKeepsTheEngineRestartCause() {
        val state = foldTree(
            freshTree(), ev("turn_started", "t1", seq = 1, ts = 1),
            ev("turn_end", "t1") { put("outcome", "outcome_unknown"); put("cause", "engine_restart") },
        )
        assertEquals(JsStr("engine_restart"), turn(state)["errorCause"])
        assertEquals("engine_restart", checkNotNull(LegacyProjectionAdapter.adaptOnce(state)).turnsById.getValue("t1").errorCause)
    }

    /** v167: a restart-cut turn_end keeps its cause and the notice-only mark; a key only when stamped. */
    @Test fun aTetherRestartTurnEndFoldsItsCauseAndTheNoticeMark() {
        val marked = foldTree(
            freshTree(), ev("turn_started", "t1", seq = 1, ts = 1),
            ev("turn_end", "t1") { put("outcome", "outcome_unknown"); put("cause", "tether_restart"); put("resume", "notice") },
        )
        assertEquals(JsStr("tether_restart"), turn(marked)["errorCause"])
        assertEquals(JsStr("notice"), turn(marked)["restartResume"])
        assertEquals("notice", checkNotNull(LegacyProjectionAdapter.adaptOnce(marked)).turnsById.getValue("t1").restartResume)
        val plain = foldTree(
            freshTree(), ev("turn_started", "t1", seq = 1, ts = 1),
            ev("turn_end", "t1") { put("outcome", "outcome_unknown"); put("cause", "tether_restart") },
        )
        assertNull(turn(plain)["restartResume"])
        assertNull(checkNotNull(LegacyProjectionAdapter.adaptOnce(plain)).turnsById.getValue("t1").restartResume)
    }
}
