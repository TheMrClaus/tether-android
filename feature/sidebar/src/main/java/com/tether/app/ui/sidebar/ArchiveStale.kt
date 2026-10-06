package com.tether.app.ui.sidebar

import com.tether.app.protocol.ServerMessage

/**
 * v141 (tether #244 part C): components/archive-stale-dialog.tsx's rules, pure. The "Archive idle
 * sessions" dialog asks the server for a preview first (counts and why others are skipped), then runs
 * bounded batches the operator can stop between. There is no extra confirmation: the preview is it, as
 * on the web. No Compose, no clock, no client: unit-tested on the JVM.
 */
object ArchiveStaleCopy {
    const val ENTRY = "Archive idle sessions…"
    const val TITLE = "Archive idle sessions"
    const val DAYS_LABEL = "Idle for more than"
    const val CHECKING = "Checking…"
    const val CANCEL = "Cancel"
    const val DONE = "Done"
    const val STOP = "Stop"
    const val NOTHING = "Nothing to archive"
    const val WORKTREE_NOTE = "Archiving also stops a session's worktree services and removes its checkout when it is clean and merged."

    fun dayLabel(days: Int) = "$days days"

    fun archiveKey(eligible: Int) = "Archive $eligible"

    private fun sessions(count: Int) = "session${if (count == 1) "" else "s"}"

    /** archive-stale-dialog.tsx:106-109 — the settled summary. */
    fun done(archived: Int, failed: Int): String =
        "Archived $archived ${sessions(archived)}${if (failed > 0) " · $failed failed — see the server log" else ""}."

    /** archive-stale-dialog.tsx:111 — the in-flight line; the "left" count only from a `run` reply. */
    fun running(archived: Int, result: ServerMessage.ArchiveStaleResult?): String =
        "Archiving… $archived done${if (result != null && result.mode == "run") " · ${result.remaining} left" else ""}"

    /** archive-stale-dialog.tsx:116 — the preview sentence's two halves around the bold count. */
    fun previewCount(eligible: Int) = "$eligible"
    fun previewAfter(eligible: Int, days: Int) = " ${sessions(eligible)} idle for more than $days days will be archived."

    /** The whole preview sentence, for semantics and tests. */
    fun preview(eligible: Int, days: Int) = previewCount(eligible) + previewAfter(eligible, days)

    /** archive-stale-dialog.tsx:84-90, 118-120 — "Skipped: 1 pinned · 2 turn in progress.", null when none. */
    fun skipped(result: ServerMessage.ArchiveStaleResult, days: Int): String? {
        if (result.days != days) return null
        val parts = listOf(
            result.skipped.pinned to "pinned",
            result.skipped.inFlight to "turn in progress",
            result.skipped.pendingRequest to "waiting on you",
            result.skipped.background to "background work running",
            result.skipped.viewing to "open now",
        ).filter { (count, _) -> count > 0 }
        if (parts.isEmpty()) return null
        return "Skipped: ${parts.joinToString(" · ") { (count, why) -> "$count $why" }}."
    }

    /** archive-stale-dialog.tsx:123-127 — the retention warning, null when nothing will be pruned. */
    fun retention(result: ServerMessage.ArchiveStaleResult): String? =
        if (result.retention.willBePruned > 0) {
            "Tether keeps only the newest ${result.retention.cap} archived sessions. About ${result.retention.willBePruned} of the oldest " +
                "will be removed from Tether (history and journal). Their conversations stay resumable from the CLI's own history."
        } else {
            null
        }
}

/** The dialog's own state (archive-stale-dialog.tsx:28-32): the chosen threshold, a run in flight, its tally, settled. */
data class ArchiveStaleState(
    val days: Int = ArchiveStaleModel.DEFAULT_DAYS,
    val running: Boolean = false,
    val archived: Int = 0,
    val failed: Int = 0,
    val done: Boolean = false,
)

/** An `archive-stale` frame to send: [mode] "preview" | "run" (the exempt open session rides with it). */
data class ArchiveStaleRequest(val mode: String, val days: Int)

/** The next state and the frame it asks for, if any. */
data class ArchiveStaleStep(val state: ArchiveStaleState, val request: ArchiveStaleRequest? = null)

/** What the dialog's body and keys show for a state and the latest reply. */
sealed interface ArchiveStaleBody {
    data class Done(val text: String) : ArchiveStaleBody
    data class Running(val text: String) : ArchiveStaleBody
    data object Checking : ArchiveStaleBody
    data class Preview(
        val eligible: Int,
        val days: Int,
        val skipped: String?,
        val retention: String?,
    ) : ArchiveStaleBody
}

object ArchiveStaleModel {
    /** lib/stale-archive.mjs STALE_ARCHIVE_DAYS. */
    val THRESHOLDS = listOf(7, 15, 30)

    /** archive-stale-dialog.tsx:27 — `useState<Days>(30)`. */
    const val DEFAULT_DAYS = 30

    /** `open()` (:34-43): the tally resets, the last reply is dropped (the host clears it), a preview is asked. */
    fun open(state: ArchiveStaleState): ArchiveStaleStep =
        ArchiveStaleStep(state.copy(running = false, done = false, archived = 0, failed = 0), ArchiveStaleRequest("preview", state.days))

    /** `choose()` (:60-66): inert while a run is in flight; otherwise a fresh preview for the new threshold. */
    fun choose(state: ArchiveStaleState, days: Int): ArchiveStaleStep {
        if (state.running) return ArchiveStaleStep(state)
        return ArchiveStaleStep(
            state.copy(days = days, done = false, archived = 0, failed = 0),
            ArchiveStaleRequest("preview", days),
        )
    }

    /** `start()` (:67-72): the first bounded batch. */
    fun start(state: ArchiveStaleState): ArchiveStaleStep =
        ArchiveStaleStep(state.copy(running = true, done = false, archived = 0, failed = 0), ArchiveStaleRequest("run", state.days))

    /** Stop (:162): between batches, the summary settles on what was done so far. */
    fun stop(state: ArchiveStaleState): ArchiveStaleState = state.copy(running = false, done = true)

    /**
     * The effect on a new reply (:46-58), once per reply: only a `run` reply while running counts. Each adds to
     * the tally, then either continues (more remain, nothing failed, something was archived) or settles.
     */
    fun onReply(state: ArchiveStaleState, result: ServerMessage.ArchiveStaleResult): ArchiveStaleStep {
        if (!state.running || result.mode != "run") return ArchiveStaleStep(state)
        val next = state.copy(archived = state.archived + result.archived, failed = state.failed + result.failed)
        return if (result.remaining > 0 && result.failed == 0 && result.archived > 0) {
            ArchiveStaleStep(next, ArchiveStaleRequest("run", result.days))
        } else {
            ArchiveStaleStep(next.copy(running = false, done = true))
        }
    }

    /** The body for [state] and the latest reply (:99-131). A preview counts only for the threshold chosen now. */
    fun body(state: ArchiveStaleState, result: ServerMessage.ArchiveStaleResult?): ArchiveStaleBody {
        if (state.done) return ArchiveStaleBody.Done(ArchiveStaleCopy.done(state.archived, state.failed))
        if (state.running) return ArchiveStaleBody.Running(ArchiveStaleCopy.running(state.archived, result))
        val eligible = result?.takeIf { it.days == state.days }?.eligible ?: return ArchiveStaleBody.Checking
        return ArchiveStaleBody.Preview(
            eligible = eligible,
            days = state.days,
            skipped = result?.let { ArchiveStaleCopy.skipped(it, state.days) },
            retention = result?.let(ArchiveStaleCopy::retention),
        )
    }

    /** The count the Archive key offers (`eligible` for the threshold chosen now), null while checking. */
    fun eligible(state: ArchiveStaleState, result: ServerMessage.ArchiveStaleResult?): Int? =
        result?.takeIf { it.days == state.days }?.eligible
}
