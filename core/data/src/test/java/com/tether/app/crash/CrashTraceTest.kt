package com.tether.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashTraceTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun aShortTraceHasTheClassTheMessageTheFramesAndTheCause() {
        val root = IllegalArgumentException("root cause")
        val top = IllegalStateException("boom", root)
        val text = CrashTrace.render(top)
        val lines = text.lines()
        assertEquals("java.lang.IllegalStateException: boom", lines.first())
        assertTrue(lines[1].startsWith("\tat "))
        assertTrue(text.contains("\nCaused by: java.lang.IllegalArgumentException: root cause\n"))
        assertTrue(text.contains("more"))
        assertFalse(text.endsWith("\n"))
    }

    @Test
    fun aMessageIsCappedAt2KiBWithAMarkerAndTheFramesSurvive() {
        val text = CrashTrace.render(RuntimeException("m".repeat(1_000_000)))
        assertTrue("trace was ${bytes(text)} bytes", bytes(text) < CrashTrace.MAX_STACK_BYTES)
        val first = text.lines().first()
        assertTrue(first, bytes(first) < CrashTrace.MAX_MESSAGE_BYTES + 200)
        assertTrue(first.contains("cut"))
        assertTrue(text.contains("\tat "))
        val capped = CrashTrace.capMessage("m".repeat(1_000_000))!!
        assertTrue(bytes(capped) < CrashTrace.MAX_MESSAGE_BYTES + 100)
        assertEquals("short", CrashTrace.capMessage("short"))
        assertEquals(null, CrashTrace.capMessage(null))
    }

    @Test
    fun aMessageCutNeverSplitsASurrogatePair() {
        // 2 KiB exactly lands inside a 4-byte emoji: the cut moves back to the code point before it.
        val msg = "a".repeat(CrashTrace.MAX_MESSAGE_BYTES - 2) + "😀".repeat(50)
        val capped = CrashTrace.capMessage(msg)!!
        assertFalse(capped.any { it == '�' })
        var i = 0
        while (i < capped.length) {
            val c = capped[i]
            if (Character.isHighSurrogate(c)) {
                assertTrue("lone high surrogate at $i", i + 1 < capped.length && Character.isLowSurrogate(capped[i + 1]))
                i++
            } else {
                assertFalse("lone low surrogate at $i", Character.isLowSurrogate(c))
            }
            i++
        }
    }

    @Test
    fun aTwentyDeepCauseChainKeepsEveryLinkUnderTheCap() {
        var error: Throwable = IllegalStateException("level 0")
        for (n in 1..20) error = RuntimeException("level $n", error)
        val text = CrashTrace.render(error)
        for (n in 0..20) assertTrue("level $n missing", text.contains("level $n"))
        assertTrue(bytes(text) <= CrashTrace.MAX_STACK_BYTES)
    }

    @Test
    fun aCyclicCauseTerminates() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val text = CrashTrace.render(a)
        assertTrue(text.contains("CIRCULAR REFERENCE"))
        assertTrue(text.contains("b"))
    }

    @Test
    fun suppressedExceptionsAreIncluded() {
        val top = RuntimeException("top")
        top.addSuppressed(IllegalStateException("closing failed"))
        val text = CrashTrace.render(top)
        assertTrue(text.contains("Suppressed: java.lang.IllegalStateException: closing failed"))
    }

    @Test
    fun aHugeTraceIsCutToTheCapKeepingTheHeadAndTheRootCause() {
        val root = IllegalArgumentException("the root cause")
        var error: Throwable = root
        // Each link carries a big message and deep frames: the whole is far over the cap.
        for (n in 1..40) {
            error = RuntimeException("link $n " + "z".repeat(2_000), error).also { it.stackTrace = deepFrames(200) }
        }
        val text = CrashTrace.render(error)
        assertTrue("trace was ${bytes(text)} bytes", bytes(text) <= CrashTrace.MAX_STACK_BYTES)
        assertTrue(text.startsWith("java.lang.RuntimeException: link 40"))
        assertTrue(text.contains("[... "))
        assertTrue(text.contains("bytes cut ...]"))
        assertTrue("the root cause survives the cut", text.contains("Caused by: java.lang.IllegalArgumentException: the root cause"))
    }

    @Test
    fun aSingleHugeFrameListIsCutToTheCapWithTheMarker() {
        val e = RuntimeException("deep").also { it.stackTrace = deepFrames(5_000) }
        val text = CrashTrace.render(e)
        assertTrue(bytes(text) <= CrashTrace.MAX_STACK_BYTES)
        assertTrue(text.contains("bytes cut ...]"))
        assertTrue(text.startsWith("java.lang.RuntimeException: deep"))
    }

    @Test
    fun capStackKeepsAShortStackWholeAndCutsOnACodePoint() {
        assertEquals("a\nb", CrashTrace.capStack("a\nb"))
        val long = "x".repeat(CrashTrace.MAX_STACK_BYTES - 1) + "😀".repeat(20)
        val capped = CrashTrace.capStack(long)
        assertTrue(bytes(capped) <= CrashTrace.MAX_STACK_BYTES)
        assertFalse(capped.any { it == '�' })
        assertTrue(capped.contains("bytes cut ...]"))
    }

    @Test
    fun aShortHeadWithAHugeRootCauseKeepsTheHeadWholeAndCutsTheRootCauseAlone() {
        val stack = "top: boom\n\tat a.B.c(B.kt:1)\nCaused by: root: x\n" + "\tat z.Y.x(Y.kt:2)\n".repeat(20_000)
        val capped = CrashTrace.capStack(stack)
        assertTrue(bytes(capped) <= CrashTrace.MAX_STACK_BYTES)
        assertTrue(capped.startsWith("top: boom\n\tat a.B.c(B.kt:1)\nCaused by: root: x\n"))
        assertTrue(capped.contains("bytes cut ...]"))
    }

    private fun deepFrames(n: Int) = Array(n) { StackTraceElement("com.example.Frame$it", "call", "Frame.kt", it + 1) }
}
