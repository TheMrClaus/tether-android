package com.tether.app.client

/**
 * ta-coik.32: one line per connection milestone, with the milliseconds since the start, resume or
 * network change it belongs to (`resume +412ms socket-open`), for `adb logcat -s TetherTiming`.
 * A milestone is a fixed word: never a host, an id or any content. That is enforced, not only
 * meant: a label that is not one word, or a milestone that is not one or two words, of lowercase
 * letters and hyphens, drops its line.
 */
class TimingTrace(
    private val sink: (String) -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Volatile private var originMs = nowMs()

    @Volatile private var label = "start"

    /** A new run of milestones, timed from now. */
    fun begin(label: String) {
        originMs = nowMs()
        this.label = label
        emit("begin")
    }

    fun mark(milestone: String) = emit(milestone)

    private fun emit(milestone: String) {
        if (!LABEL.matches(label) || !MILESTONE.matches(milestone)) return
        try {
            sink("$label +${nowMs() - originMs}ms $milestone")
        } catch (_: RuntimeException) {
            // A trace never breaks the connection (e.g. android.util.Log stubbed in a JVM test).
        }
    }

    private companion object {
        val LABEL = Regex("^[a-z-]+$")
        val MILESTONE = Regex("^[a-z-]+( [a-z-]+)?$")
    }
}
