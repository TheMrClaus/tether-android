package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.numberToString
import kotlin.math.floor

/**
 * ta-ceo: faithful port of lib/queue-wait.mjs (tether 887c222, issue #229, protocol v136): the words
 * for a message DEFERRED behind live work ("next-call") and the price of the destructive escape
 * hatch (Stop / "Interrupt now"). Pure, no clock reads: the caller passes `waitedMs`, measured from
 * the journal-stamped `queuedAt`. The counts come from [com.tether.app.protocol.fold.runningToolIds]
 * and [com.tether.app.protocol.fold.liveBackgroundTaskCount], always whole numbers here, so the web's
 * `count()` guard (a non-integer or non-positive value counts as 0) reduces to "positive or 0".
 */
object QueueWait {

    // lib/queue-wait.mjs:11 — after this long the row stops being a passive wait and asks.
    const val DEFERRAL_CHOICE_AFTER_MS: Long = 60_000L

    /** What a turn-wide Stop would destroy right now (chat-view.tsx:1974 `liveWork`). */
    data class LiveWork(val openTools: Int = 0, val outstandingBackground: Int = 0) {
        companion object {
            val None = LiveWork()
        }
    }

    /** `deferredWaitCopy`'s result: the row's words, and whether it should offer the explicit choice. */
    data class DeferredWait(val label: String, val needsChoice: Boolean)

    // lib/queue-wait.mjs:13
    private fun plural(count: Int, singular: String, pluralForm: String): String =
        "$count ${if (count == 1) singular else pluralForm}"

    // lib/queue-wait.mjs:17
    private fun count(value: Int): Int = if (value > 0) value else 0

    // lib/queue-wait.mjs:22 — "45s", "3m 05s", "1h 02m": a wait, rounded down to the second; "" for junk.
    fun formatWaited(ms: Double?): String {
        if (ms == null || !ms.isFinite() || ms < 0) return ""
        val total = floor(ms / 1000)
        if (total < 60) return "${numberToString(total)}s"
        val minutes = floor(total / 60)
        fun pad(value: Double) = numberToString(value).padStart(2, '0')
        if (minutes < 60) return "${numberToString(minutes)}m ${pad(total % 60)}s"
        return "${numberToString(floor(minutes / 60))}h ${pad(minutes % 60)}m"
    }

    // lib/queue-wait.mjs:33 — "1 tool running · 7 background tasks live", or "" when nothing is live.
    fun liveWorkCopy(work: LiveWork): String {
        val parts = ArrayList<String>(2)
        if (count(work.openTools) > 0) parts.add("${plural(work.openTools, "tool", "tools")} running")
        if (count(work.outstandingBackground) > 0) {
            parts.add("${plural(work.outstandingBackground, "background task", "background tasks")} live")
        }
        return parts.joinToString(" · ")
    }

    /**
     * lib/queue-wait.mjs:47 — what a deferred ("next-call") queued row says. `needsChoice`: the wait
     * has outlasted [DEFERRAL_CHOICE_AFTER_MS] while work is still live. [waitedMs] null = the row
     * carries no stamp (an older journal): it still renders and never asks for a choice.
     */
    fun deferredWaitCopy(work: LiveWork, waitedMs: Double?): DeferredWait {
        val live = liveWorkCopy(work)
        val waited = if (waitedMs == null) "" else formatWaited(waitedMs)
        val head = if (live.isNotEmpty()) {
            "Queued — waiting for a safe boundary"
        } else {
            "Queued — sends at the next tool call or when the turn ends"
        }
        val tail = listOf(live, if (waited.isNotEmpty()) "waiting $waited" else "").filter { it.isNotEmpty() }.joinToString(" · ")
        val needsChoice = live.isNotEmpty() && waitedMs != null && !waitedMs.isNaN() && waitedMs >= DEFERRAL_CHOICE_AFTER_MS
        return DeferredWait(if (tail.isNotEmpty()) "$head ($tail)" else head, needsChoice)
    }

    /**
     * lib/queue-wait.mjs:62 — the price of Stop / "Interrupt now", or "" when it would stop nothing but
     * the turn itself (no confirmation needed).
     */
    fun stopCostCopy(work: LiveWork): String {
        val tools = count(work.openTools)
        val background = count(work.outstandingBackground)
        val parts = ArrayList<String>(2)
        if (tools > 0) parts.add("the ${if (tools == 1) "open tool call" else "${work.openTools} open tool calls"}")
        if (background > 0) parts.add("kill ${plural(work.outstandingBackground, "background task", "background tasks")}")
        if (parts.isEmpty()) return ""
        if (tools > 0 && background > 0) return "Stop ends ${parts[0]} and will ${parts[1]}"
        return if (tools > 0) "Stop ends ${parts[0]}" else "Stop will ${parts[0]}"
    }

    /**
     * chat-view.tsx:1537 — the choice line names what interrupting stops: the cost without its
     * leading verb (`cost.replace(/^Stop (will |ends )?/, "")`), e.g. "kill 7 background tasks".
     */
    fun interruptStopsCopy(cost: String): String = cost.replaceFirst(Regex("^Stop (will |ends )?"), "")
}
