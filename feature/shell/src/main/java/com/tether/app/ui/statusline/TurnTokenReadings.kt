package com.tether.app.ui.statusline

import androidx.compose.runtime.Immutable
import com.tether.app.protocol.helpers.Format
import com.tether.app.protocol.model.SessionView
import com.tether.app.protocol.model.TurnView
import com.tether.app.protocol.tree.JsNum
import kotlin.math.max

/*
 * T4.3: what the `token_progress` and `usage` events become on screen — a port of the token
 * derivations in components/turn-activity.tsx (tether @ PARITY_BASE). The fold (T2.1) writes
 * `turn.liveTokens` (token_progress: the main agent's monotonic mid-turn output estimate) and
 * `turn.usage.perTurnTokens` (usage: the whole tree — sub-agents and input included); these
 * functions turn them into the run counter and the session total the composer deck prints.
 */

/**
 * turn-activity.tsx:66-75 `settledTurnTokens`: tokens for a FINISHED turn, or null while it is
 * still open (Codex reports usage mid-turn, so gate on status, not on `usage`). The max of the
 * two finished figures, never `usage ?? live`, so the total cannot fall when the authoritative
 * figure lands under the estimate.
 */
fun settledTurnTokens(turn: TurnView): Double? {
    if (turn.status != "done") return null
    // `turn.usage?.perTurnTokens` / `turn.liveTokens`: `== null` is JS loose null (absent or null).
    val settled = (turn.usage?.get("perTurnTokens") as? JsNum)?.value
    val live = turn.liveTokens
    if (settled == null && live == null) return 0.0
    if (settled == null) return live ?: 0.0
    if (live == null) return settled
    return jsMax(settled, live)
}

/** `Math.max(a, b)`: NaN if either is NaN. */
private fun jsMax(a: Double, b: Double): Double = if (a.isNaN() || b.isNaN()) Double.NaN else max(a, b)

/**
 * turn-activity.tsx:169-173: the open run's own count — `liveTokens` is cumulative for the turn,
 * so subtract what had been counted when the run opened. Null without a live estimate or a run.
 */
fun runTokens(turn: TurnView?): Double? {
    val live = turn?.liveTokens ?: return null
    val run = turn.run ?: return null
    val start = (run["tokensStart"] as? JsNum)?.value ?: 0.0
    return jsMax(0.0, live - start)
}

/** turn-activity.tsx:104-117: the session total over completed turns only. */
@Immutable
data class SessionTokenTotal(val tokens: Double, val accountedTurns: Int)

fun sessionTokenTotal(state: SessionView): SessionTokenTotal {
    var tokens = 0.0
    var accounted = 0
    for (index in 0 until state.turnCount) {
        val turn = state.turnAt(index) ?: continue
        val settled = settledTurnTokens(turn) ?: continue
        tokens += settled
        accounted += 1
    }
    return SessionTokenTotal(tokens, accounted)
}

/** turn-activity.tsx:78-80: "1 token" / "960 tokens" / "1.3K tokens" (lib/format compactNumber). */
fun tokenLabel(value: Double): String = "${Format.compactNumber(value)} ${if (value == 1.0) "token" else "tokens"}"
