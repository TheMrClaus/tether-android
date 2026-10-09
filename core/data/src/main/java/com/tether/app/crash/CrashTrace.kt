package com.tether.app.crash

import java.util.Collections
import java.util.IdentityHashMap

/**
 * ta-otgf: the trace text of a throwable, and its caps.
 *
 * `Throwable.stackTraceToString()` prints every message whole, so one huge message (the W28
 * 20,000-character line was exactly that) could fill the budget and push every frame and the root
 * cause out. This prints the same shape (frames, `... N more`, `Caused by:`, `Suppressed:`,
 * a circular cause named, not followed) with each message capped at [MAX_MESSAGE_BYTES], and then
 * caps the whole at [MAX_STACK_BYTES] of UTF-8 keeping the head and the root cause, with an
 * explicit `[... N bytes cut ...]` marker. Pure JVM; no allocation beyond the strings it returns
 * that is not bounded by a constant here.
 */
object CrashTrace {
    const val MAX_MESSAGE_BYTES = 2 * 1024
    const val MAX_STACK_BYTES = 64 * 1024

    /** Room kept for the root cause when the whole is cut. */
    private const val TAIL_BYTES = 16 * 1024

    /** Room kept for the marker line(s) inside [MAX_STACK_BYTES]. */
    private const val MARKER_ROOM = 96

    private const val MAX_CHAIN = 64
    private const val MAX_SUPPRESSED = 16
    private const val MAX_SUPPRESSED_DEPTH = 3

    /** One throwable's own text is never built past this many characters (frames beyond it are counted, not printed). */
    private const val SECTION_CHARS = 128 * 1024

    private const val CAUSE_MARK = "\nCaused by: "

    /** The whole trace of [error], with causes and suppressed exceptions, capped. */
    fun render(error: Throwable): String {
        val seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
        val out = StringBuilder()
        var current: Throwable? = error
        var enclosing: Array<StackTraceElement>? = null
        var links = 0
        while (current != null) {
            if (links++ >= MAX_CHAIN) {
                out.append("\n[... cause chain cut at ").append(MAX_CHAIN).append(" links ...]")
                break
            }
            val caption = if (enclosing == null) "" else "Caused by: "
            if (enclosing != null) out.append('\n')
            section(out, current, enclosing, caption, "", seen, 0)
            enclosing = framesOf(current)
            current = causeOf(current)
            if (current != null && current in seen) {
                out.append("\nCaused by: [CIRCULAR REFERENCE: ").append(describe(current)).append(']')
                break
            }
        }
        return capStack(out.toString())
    }

    /** [message] cut to [MAX_MESSAGE_BYTES] of UTF-8 on a code point, with a marker; null stays null. */
    fun capMessage(message: String?): String? {
        if (message == null) return null
        val total = utf8Length(message, message.length)
        if (total <= MAX_MESSAGE_BYTES) return message
        val keep = prefixLength(message, MAX_MESSAGE_BYTES)
        return message.substring(0, keep) + "… [" + (total - utf8Length(message, keep)) + " bytes cut]"
    }

    /**
     * [stack] unchanged when it fits [MAX_STACK_BYTES] of UTF-8, else its head and its root cause
     * (the last `Caused by:` section) with `[... N bytes cut ...]` between; with no cause, the head.
     */
    fun capStack(stack: String): String {
        val total = utf8Length(stack, stack.length)
        if (total <= MAX_STACK_BYTES) return stack
        val causeAt = stack.lastIndexOf(CAUSE_MARK)
        if (causeAt <= 0) {
            val keep = prefixLength(stack, MAX_STACK_BYTES - MARKER_ROOM)
            return stack.substring(0, keep) + marker(total - utf8Length(stack, keep), "\n")
        }
        var tail = stack.substring(causeAt + 1)
        val tailTotal = utf8Length(tail, tail.length)
        if (tailTotal > TAIL_BYTES) {
            val keepTail = prefixLength(tail, TAIL_BYTES - MARKER_ROOM)
            tail = tail.substring(0, keepTail) + marker(tailTotal - utf8Length(tail, keepTail), "\n")
        }
        val tailBytes = utf8Length(tail, tail.length)
        val keep = minOf(prefixLength(stack, MAX_STACK_BYTES - tailBytes - MARKER_ROOM), causeAt + 1)
        val middle = utf8Length(stack, causeAt + 1) - utf8Length(stack, keep)
        val head = stack.substring(0, keep)
        return if (middle == 0) head + tail else head + marker(middle, "\n") + tail
    }

    private fun marker(bytes: Int, lead: String): String = "$lead[... $bytes bytes cut ...]"

    private fun section(
        out: StringBuilder,
        error: Throwable,
        enclosing: Array<StackTraceElement>?,
        caption: String,
        prefix: String,
        seen: MutableSet<Throwable>,
        depth: Int,
    ) {
        out.append(prefix).append(caption)
        if (!seen.add(error)) {
            out.append("[CIRCULAR REFERENCE: ").append(describe(error)).append(']')
            return
        }
        out.append(describe(error))
        val frames = framesOf(error)
        var m = frames.size - 1
        if (enclosing != null) {
            var n = enclosing.size - 1
            while (m >= 0 && n >= 0 && frames[m] == enclosing[n]) {
                m--
                n--
            }
        }
        val start = out.length
        var printed = 0
        while (printed <= m) {
            if (out.length - start > SECTION_CHARS) {
                out.append('\n').append(prefix).append("\t... ").append(m + 1 - printed).append(" frames cut")
                break
            }
            out.append('\n').append(prefix).append("\tat ").append(frames[printed])
            printed++
        }
        val common = frames.size - 1 - m
        if (common > 0) out.append('\n').append(prefix).append("\t... ").append(common).append(" more")
        if (depth < MAX_SUPPRESSED_DEPTH) {
            val suppressed = suppressedOf(error)
            for (i in 0 until minOf(suppressed.size, MAX_SUPPRESSED)) {
                out.append('\n')
                section(out, suppressed[i], frames, "Suppressed: ", "$prefix\t", seen, depth + 1)
            }
            if (suppressed.size > MAX_SUPPRESSED) {
                out.append('\n').append(prefix).append("\t... ").append(suppressed.size - MAX_SUPPRESSED).append(" more suppressed")
            }
        }
    }

    /** `ClassName: capped message` (the class alone with no message): nothing a hostile getMessage or toString does escapes. */
    private fun describe(error: Throwable): String {
        val name = runCatching { error.javaClass.name }.getOrDefault("?")
        val message = runCatching { error.message }.getOrNull()
        val capped = capMessage(message)
        return if (capped == null) name else "$name: $capped"
    }

    private fun framesOf(error: Throwable): Array<StackTraceElement> = runCatching { error.stackTrace }.getOrDefault(emptyArray())

    private fun causeOf(error: Throwable): Throwable? = runCatching { error.cause }.getOrNull()

    private fun suppressedOf(error: Throwable): Array<Throwable> = runCatching { error.suppressed }.getOrDefault(emptyArray())

    /** UTF-8 length of the first [end] chars of [s]; a lone surrogate encodes as one byte ('?'), as the encoder writes it. */
    internal fun utf8Length(s: String, end: Int): Int {
        var bytes = 0
        var i = 0
        while (i < end) {
            val c = s[i]
            when {
                c.code < 0x80 -> bytes += 1
                c.code < 0x800 -> bytes += 2
                Character.isHighSurrogate(c) && i + 1 < end && Character.isLowSurrogate(s[i + 1]) -> {
                    bytes += 4
                    i++
                }
                Character.isSurrogate(c) -> bytes += 1
                else -> bytes += 3
            }
            i++
        }
        return bytes
    }

    /** The longest prefix of [s], in chars, whose UTF-8 length is at most [maxBytes], never between a surrogate pair. */
    internal fun prefixLength(s: String, maxBytes: Int): Int {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val (width, chars) = when {
                c.code < 0x80 -> 1 to 1
                c.code < 0x800 -> 2 to 1
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> 4 to 2
                Character.isSurrogate(c) -> 1 to 1
                else -> 3 to 1
            }
            if (bytes + width > maxBytes) break
            bytes += width
            i += chars
        }
        return i
    }
}
