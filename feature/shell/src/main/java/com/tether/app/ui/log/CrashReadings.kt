package com.tether.app.ui.log

import com.tether.app.crash.CrashRecord
import com.tether.app.crash.ProcessExit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * ta-otgf: the pure half of the "Last crash" and "Recent exits" section of the Health & Event Log
 * dialog: an owner-directed addition with no web counterpart (the browser has its own devtools).
 * What each line prints, how the stack is cut into lazy items, and the plain text Copy puts on the
 * clipboard. The composables in LogDialog.kt only lay these strings out.
 */

/**
 * One lazy item's share of the stack: [lines] source lines (0 for the second and later pieces of a
 * line that was cut), and [piece] when the text is a cut of one long line.
 */
data class CrashChunk(val text: String, val lines: Int, val piece: Boolean)

/** What a row of exits is tinted as: LogRow's tints (warn 8% `--warning`, error 10% `--danger`). */
enum class ExitTone { None, Warn, Error }

/** The collapsed stack: the text shown, the lines it leaves out ([hidden], null when it cannot say), and whether a toggle is needed. */
data class CrashPeek(val text: String, val hidden: Int?, val toggle: Boolean)

object CrashReadings {
    /** A chunk holds at most this many lines and UTF-16 units: the bound that keeps one Text far under a Constraints' 262,143 px. */
    const val CHUNK_LINES = 100
    const val CHUNK_UNITS = 2_048

    /** The lines the collapsed stack shows. */
    const val PEEK_LINES = 8

    private const val ISO = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"

    /** `MMM d, yyyy, hh:mm:ss a`: the app's absolute time with the dialog row's seconds, in the dialog's locale and zone. */
    fun crashTime(ts: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("MMM d, yyyy, hh:mm:ss a", locale).format(Instant.ofEpochMilli(ts).atZone(zone))

    private fun iso(ts: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern(ISO, Locale.ROOT).format(Instant.ofEpochMilli(ts).atZone(zone))

    fun version(crash: CrashRecord): String = "${crash.versionName} (${crash.versionCode})"

    fun android(crash: CrashRecord): String = "${crash.androidRelease} (API ${crash.androidSdk})"

    /** `SimpleName: message`; the simple name alone with no message; `Unknown exception` with no class name. */
    fun summary(exceptionClass: String, message: String?): String {
        val simple = exceptionClass.trim().substringAfterLast('.')
        if (simple.isEmpty()) return "Unknown exception"
        return if (message.isNullOrBlank()) simple else "$simple: $message"
    }

    /**
     * The stack as lazy items: lines packed greedily into chunks of at most [CHUNK_LINES] lines and
     * [CHUNK_UNITS] units (the `\n` joiners counted); a line over [CHUNK_UNITS] is cut into pieces
     * of its own, never between a surrogate pair. A blank stack has none.
     */
    fun chunks(stack: String): List<CrashChunk> {
        val trimmed = stack.trimEnd('\n', '\r')
        if (trimmed.isEmpty()) return emptyList()
        val out = ArrayList<CrashChunk>()
        val pending = ArrayList<String>()
        var units = 0
        fun flush() {
            if (pending.isEmpty()) return
            out += CrashChunk(pending.joinToString("\n"), pending.size, piece = false)
            pending.clear()
            units = 0
        }
        for (line in trimmed.split('\n')) {
            if (line.length > CHUNK_UNITS) {
                flush()
                var at = 0
                var first = true
                while (at < line.length) {
                    var end = minOf(at + CHUNK_UNITS, line.length)
                    if (end < line.length && Character.isHighSurrogate(line[end - 1]) && Character.isLowSurrogate(line[end])) end--
                    out += CrashChunk(line.substring(at, end), if (first) 1 else 0, piece = true)
                    first = false
                    at = end
                }
                continue
            }
            val joined = units + (if (pending.isEmpty()) 0 else 1) + line.length
            if (pending.isNotEmpty() && (pending.size >= CHUNK_LINES || joined > CHUNK_UNITS)) flush()
            units += (if (pending.isEmpty()) 0 else 1) + line.length
            pending += line
        }
        flush()
        return out
    }

    /**
     * The collapsed stack: the first [PEEK_LINES] lines of the first chunk, and "Show N more lines"
     * for the source lines that leaves out. When the first chunk is a piece of a long line the count
     * is null ("Show more": an unnumbered label beats a wrong number).
     */
    fun peek(chunks: List<CrashChunk>): CrashPeek {
        val first = chunks.firstOrNull() ?: return CrashPeek("", null, false)
        val shown = first.text.split('\n').take(PEEK_LINES)
        val shownLines = if (first.piece) first.lines else shown.size
        val toggle = chunks.size > 1 || (!first.piece && first.lines > PEEK_LINES)
        val hidden = if (first.piece) null else chunks.sumOf { it.lines } - shownLines
        return CrashPeek(shown.joinToString("\n"), hidden, toggle)
    }

    private val Reasons = mapOf(
        0 to Triple("Unknown reason", "REASON_UNKNOWN", ExitTone.None),
        1 to Triple("Exited itself", "REASON_EXIT_SELF", ExitTone.None),
        2 to Triple("Killed by signal", "REASON_SIGNALED", ExitTone.Warn),
        3 to Triple("Low memory", "REASON_LOW_MEMORY", ExitTone.Warn),
        4 to Triple("Crash", "REASON_CRASH", ExitTone.Error),
        5 to Triple("Native crash", "REASON_CRASH_NATIVE", ExitTone.Error),
        6 to Triple("Not responding (ANR)", "REASON_ANR", ExitTone.Error),
        7 to Triple("Startup failure", "REASON_INITIALIZATION_FAILURE", ExitTone.Error),
        8 to Triple("Permission changed", "REASON_PERMISSION_CHANGE", ExitTone.None),
        9 to Triple("Excessive resource use", "REASON_EXCESSIVE_RESOURCE_USAGE", ExitTone.Warn),
        10 to Triple("Stopped by user request", "REASON_USER_REQUESTED", ExitTone.None),
        11 to Triple("Stopped from task manager", "REASON_USER_STOPPED", ExitTone.None),
        12 to Triple("A dependency died", "REASON_DEPENDENCY_DIED", ExitTone.Warn),
        13 to Triple("Other system reason", "REASON_OTHER", ExitTone.None),
        14 to Triple("Killed while frozen", "REASON_FREEZER", ExitTone.Warn),
        15 to Triple("Package state changed", "REASON_PACKAGE_STATE_CHANGE", ExitTone.None),
        16 to Triple("App updated", "REASON_PACKAGE_UPDATED", ExitTone.None),
        // 17 and 18 are newer than some SDKs this builds against: matched by value, no version guard.
        17 to Triple("Over memory limit", "REASON_MEMORY_LIMITER", ExitTone.Warn),
        18 to Triple("Killed for an anomaly", "REASON_ANOMALY", ExitTone.Warn),
    )

    fun reasonLabel(reason: Int): String = Reasons[reason]?.first ?: "Reason $reason"

    fun reasonConstant(reason: Int): String = Reasons[reason]?.second ?: "REASON_$reason"

    fun reasonTone(reason: Int): ExitTone = Reasons[reason]?.third ?: ExitTone.None

    private const val REASON_EXIT_SELF = 1
    private const val REASON_SIGNALED = 2

    /** `IMPORTANCE_VISIBLE` (200) and more important is "in foreground". */
    private fun place(exit: ProcessExit): String = if (exit.importance <= 200) "in foreground" else "in background"

    private fun status(exit: ProcessExit): String? = when (exit.reason) {
        REASON_EXIT_SELF -> "exit code ${exit.status}"
        REASON_SIGNALED -> "signal ${exit.status}"
        else -> null
    }

    /** CR, LF and TAB become one space each run, so a description is one line. */
    private fun oneLine(text: String?): String = text.orEmpty().replace(Regex("[\\r\\n\\t]+"), " ").trim()

    /** The row's detail: where the app was, the exit code or signal, then the system's description, " · " joined. */
    fun exitDetail(exit: ProcessExit): String =
        listOfNotNull(place(exit), status(exit), oneLine(exit.description).takeIf { it.isNotEmpty() }).joinToString(" · ")

    /**
     * What Copy puts on the clipboard: plain text, blocks separated by one empty line, no trailing
     * newline. Times are ISO-8601 with offset in [zone]; the stack is verbatim.
     */
    fun copyText(crash: CrashRecord?, exits: List<ProcessExit>, zone: ZoneId): String {
        val blocks = ArrayList<String>()
        if (crash != null) {
            val head = listOf(
                "Last crash",
                "When: ${iso(crash.timeMs, zone)}",
                "App version: ${version(crash)}",
                "Android: ${android(crash)}",
                "Thread: ${crash.thread}",
                "Retrace with the mapping file of version ${version(crash)}.",
            ).joinToString("\n")
            blocks += head
            val stack = crash.stack.trimEnd('\n', '\r')
            if (stack.isNotEmpty()) blocks += stack
        }
        if (exits.isNotEmpty()) {
            blocks += (listOf("Recent exits") + exits.map { exitLine(it, zone) }).joinToString("\n")
        }
        return blocks.joinToString("\n\n")
    }

    private fun exitLine(exit: ProcessExit, zone: ZoneId): String {
        val parts = listOfNotNull(
            "${place(exit)} (importance ${exit.importance})",
            status(exit),
            "pid ${exit.pid}",
            oneLine(exit.description).takeIf { it.isNotEmpty() },
        )
        return "${iso(exit.timeMs, zone)}  ${reasonLabel(exit.reason)} (${reasonConstant(exit.reason)}) · ${parts.joinToString(" · ")}"
    }
}
