package com.tether.app.ui.log

import com.tether.app.crash.ProcessExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class CrashReadingsTest {
    private val locale = LogFixtures.locale
    private val zone = LogFixtures.zone
    private val crash = LogFixtures.crash

    @Test
    fun theTimeIsDateAndClockInTheDialogsLocaleAndZone() {
        assertEquals("Dec 31, 2025, 11:00:04 PM", CrashReadings.crashTime(crash.timeMs, locale, zone))
        assertEquals("Jan 1, 2026, 12:00:04 AM", CrashReadings.crashTime(crash.timeMs, locale, ZoneId.of("Europe/Paris")))
    }

    @Test
    fun versionAndAndroidAreOneLineEach() {
        assertEquals("0.6.0 (16)", CrashReadings.version(crash))
        assertEquals("16 (API 36)", CrashReadings.android(crash))
    }

    @Test
    fun theSummaryIsTheSimpleClassNameAndTheMessage() {
        assertEquals("IllegalStateException: boom", CrashReadings.summary("java.lang.IllegalStateException", "boom"))
        assertEquals("Outer\$Inner: x", CrashReadings.summary("a.b.Outer\$Inner", "x"))
        assertEquals("c: x", CrashReadings.summary("a.b.c", "x"))
        assertEquals("IllegalStateException", CrashReadings.summary("java.lang.IllegalStateException", null))
        assertEquals("IllegalStateException", CrashReadings.summary("java.lang.IllegalStateException", "  "))
        assertEquals("Unknown exception", CrashReadings.summary("", "boom"))
        assertEquals("Unknown exception", CrashReadings.summary("  ", null))
    }

    @Test
    fun theFixtureStackIsTwoChunksOf27And15Lines() {
        val chunks = CrashReadings.chunks(crash.stack)
        assertEquals(2, chunks.size)
        assertEquals(27, chunks[0].lines)
        assertEquals(15, chunks[1].lines)
        assertEquals(2012, chunks[0].text.length)
        assertEquals(1020, chunks[1].text.length)
        assertEquals(crash.stack, chunks.joinToString("\n") { it.text })
        assertTrue(chunks.none { it.piece })
    }

    @Test
    fun aBlankStackHasNoChunks() {
        assertTrue(CrashReadings.chunks("").isEmpty())
        assertTrue(CrashReadings.chunks("\n\r\n").isEmpty())
    }

    @Test
    fun chunksHoldAtMost100LinesAnd2048Units() {
        val chunks = CrashReadings.chunks((1..1_000).joinToString("\n") { "ab" })
        assertEquals(10, chunks.size)
        assertTrue(chunks.all { it.lines == 100 })
        val wide = CrashReadings.chunks((1..500).joinToString("\n") { "x".repeat(99) })
        assertTrue(wide.all { it.text.length <= 2048 })
        assertEquals(500, wide.sumOf { it.lines })
    }

    @Test
    fun aLongLineIsCutIntoPiecesOf2048UnitsAndNeverSplitsASurrogatePair() {
        val line = "a".repeat(2047) + "😀".repeat(3000)
        val chunks = CrashReadings.chunks("head\n$line\ntail")
        assertEquals("head", chunks.first().text)
        assertEquals("tail", chunks.last().text)
        val pieces = chunks.filter { it.piece }
        assertTrue(pieces.size > 3)
        assertTrue(pieces.all { it.text.length <= 2048 })
        for (p in pieces) {
            assertFalse("a piece starts with a low surrogate", Character.isLowSurrogate(p.text.first()))
            assertFalse("a piece ends with a high surrogate", Character.isHighSurrogate(p.text.last()))
        }
        assertEquals(line, pieces.joinToString("") { it.text })
        assertEquals(1, pieces.sumOf { it.lines })
    }

    @Test
    fun aSixtyFourKiBLineWithNoSpacesIsBoundedChunks() {
        val chunks = CrashReadings.chunks("q".repeat(64 * 1024))
        assertEquals(32, chunks.size)
        assertTrue(chunks.all { it.text.length <= 2048 && it.piece })
    }

    @Test
    fun threeThousandFramesStayInBoundedChunks() {
        val chunks = CrashReadings.chunks((1..3_000).joinToString("\n") { "\tat com.example.Frame$it.call(Frame.kt:$it)" })
        assertTrue(chunks.all { it.lines <= 100 && it.text.length <= 2048 })
        assertEquals(3_000, chunks.sumOf { it.lines })
    }

    @Test
    fun theCollapsedStackShowsEightLinesAndCountsTheRest() {
        val peek = CrashReadings.peek(CrashReadings.chunks(crash.stack))
        assertEquals(8, peek.text.lines().size)
        assertEquals(crash.stack.lines().take(8), peek.text.lines())
        assertEquals(34, peek.hidden)
        assertTrue(peek.toggle)
    }

    @Test
    fun aShortStackHasNoToggleAndAChunkPieceSaysShowMore() {
        val short = CrashReadings.peek(CrashReadings.chunks("a\nb\nc"))
        assertEquals("a\nb\nc", short.text)
        assertFalse(short.toggle)
        val eight = CrashReadings.peek(CrashReadings.chunks((1..8).joinToString("\n") { "l$it" }))
        assertFalse(eight.toggle)
        val nine = CrashReadings.peek(CrashReadings.chunks((1..9).joinToString("\n") { "l$it" }))
        assertTrue(nine.toggle)
        assertEquals(1, nine.hidden)
        val piece = CrashReadings.peek(CrashReadings.chunks("z".repeat(5_000)))
        assertTrue(piece.toggle)
        assertNull(piece.hidden)
        assertEquals(2048, piece.text.length)
        val none = CrashReadings.peek(emptyList())
        assertFalse(none.toggle)
        assertEquals("", none.text)
    }

    @Test
    fun everyExitReasonHasALabelAConstantAndATone() {
        val expected = listOf(
            Triple(0, "Unknown reason", "REASON_UNKNOWN"), Triple(1, "Exited itself", "REASON_EXIT_SELF"),
            Triple(2, "Killed by signal", "REASON_SIGNALED"), Triple(3, "Low memory", "REASON_LOW_MEMORY"),
            Triple(4, "Crash", "REASON_CRASH"), Triple(5, "Native crash", "REASON_CRASH_NATIVE"),
            Triple(6, "Not responding (ANR)", "REASON_ANR"), Triple(7, "Startup failure", "REASON_INITIALIZATION_FAILURE"),
            Triple(8, "Permission changed", "REASON_PERMISSION_CHANGE"), Triple(9, "Excessive resource use", "REASON_EXCESSIVE_RESOURCE_USAGE"),
            Triple(10, "Stopped by user request", "REASON_USER_REQUESTED"), Triple(11, "Stopped from task manager", "REASON_USER_STOPPED"),
            Triple(12, "A dependency died", "REASON_DEPENDENCY_DIED"), Triple(13, "Other system reason", "REASON_OTHER"),
            Triple(14, "Killed while frozen", "REASON_FREEZER"), Triple(15, "Package state changed", "REASON_PACKAGE_STATE_CHANGE"),
            Triple(16, "App updated", "REASON_PACKAGE_UPDATED"), Triple(17, "Over memory limit", "REASON_MEMORY_LIMITER"),
            Triple(18, "Killed for an anomaly", "REASON_ANOMALY"),
        )
        for ((reason, label, constant) in expected) {
            assertEquals("label of $reason", label, CrashReadings.reasonLabel(reason))
            assertEquals("constant of $reason", constant, CrashReadings.reasonConstant(reason))
        }
        assertEquals("Reason 99", CrashReadings.reasonLabel(99))
        assertEquals("REASON_99", CrashReadings.reasonConstant(99))
        assertEquals("Reason -1", CrashReadings.reasonLabel(-1))
        val tones = mapOf(
            ExitTone.Error to listOf(4, 5, 6, 7), ExitTone.Warn to listOf(2, 3, 9, 12, 14, 17, 18),
            ExitTone.None to listOf(0, 1, 8, 10, 11, 13, 15, 16, 99),
        )
        for ((tone, reasons) in tones) for (r in reasons) assertEquals("tone of $r", tone, CrashReadings.reasonTone(r))
    }

    @Test
    fun theAppsReasonConstantsAgreeWithTheLabelsTheSystemDefines() {
        // The platform's own constants (0-16 are in every SDK this builds against) must keep the values the table uses.
        val info = android.app.ApplicationExitInfo::class.java
        val table = mapOf(
            "REASON_UNKNOWN" to 0, "REASON_EXIT_SELF" to 1, "REASON_SIGNALED" to 2, "REASON_LOW_MEMORY" to 3, "REASON_CRASH" to 4,
            "REASON_CRASH_NATIVE" to 5, "REASON_ANR" to 6, "REASON_INITIALIZATION_FAILURE" to 7, "REASON_PERMISSION_CHANGE" to 8,
            "REASON_EXCESSIVE_RESOURCE_USAGE" to 9, "REASON_USER_REQUESTED" to 10, "REASON_USER_STOPPED" to 11,
            "REASON_DEPENDENCY_DIED" to 12, "REASON_OTHER" to 13, "REASON_FREEZER" to 14, "REASON_PACKAGE_STATE_CHANGE" to 15,
            "REASON_PACKAGE_UPDATED" to 16,
        )
        for ((name, value) in table) {
            assertEquals(name, value, info.getField(name).getInt(null))
            assertEquals(name, CrashReadings.reasonConstant(value))
        }
    }

    @Test
    fun theExitDetailJoinsImportanceStatusAndTheDescription() {
        assertEquals("in foreground · crash", CrashReadings.exitDetail(LogFixtures.exits[0]))
        assertEquals("in background", CrashReadings.exitDetail(LogFixtures.exits[1]))
        assertEquals("in foreground", CrashReadings.exitDetail(ProcessExit(0, 4, 200, 0, 1, "  ")))
        assertEquals("in background", CrashReadings.exitDetail(ProcessExit(0, 4, 201, 0, 1, null)))
        assertEquals("in background · exit code 3", CrashReadings.exitDetail(ProcessExit(0, 1, 400, 3, 1, null)))
        assertEquals("in background · signal 9", CrashReadings.exitDetail(ProcessExit(0, 2, 400, 9, 1, null)))
        assertEquals("in background · a b c d", CrashReadings.exitDetail(ProcessExit(0, 4, 400, 0, 1, "a\nb\r\nc\td")))
    }

    @Test
    fun copyTextIsExactlyTheSpecsForTheFixture() {
        val lines = crash.stack.lines().joinToString("\n")
        val expected = buildString {
            append("Last crash\n")
            append("When: 2025-12-31T23:00:04.250Z\n")
            append("App version: 0.6.0 (16)\n")
            append("Android: 16 (API 36)\n")
            append("Thread: main\n")
            append("Retrace with the mapping file of version 0.6.0 (16).\n")
            append("\n")
            append(lines).append("\n")
            append("\n")
            append("Recent exits\n")
            append("2025-12-31T23:00:04.480Z  Crash (REASON_CRASH) · in foreground (importance 100) · pid 21734 · crash\n")
            append("2025-12-30T18:42:17.000Z  Low memory (REASON_LOW_MEMORY) · in background (importance 400) · pid 20211")
        }
        assertEquals(expected, CrashReadings.copyText(crash, LogFixtures.exits, zone))
    }

    @Test
    fun copyTextForExitsOnlyAndForACrashOnlyAndTheTimesFollowTheZone() {
        val exitsOnly = CrashReadings.copyText(null, LogFixtures.exits, zone)
        assertTrue(exitsOnly.startsWith("Recent exits\n2025-12-31T23:00:04.480Z  Crash"))
        assertFalse(exitsOnly.contains("Last crash"))
        assertFalse(exitsOnly.endsWith("\n"))
        val crashOnly = CrashReadings.copyText(crash, emptyList(), zone)
        assertTrue(crashOnly.startsWith("Last crash\nWhen: "))
        assertFalse(crashOnly.contains("Recent exits"))
        assertFalse(crashOnly.endsWith("\n"))
        assertTrue(CrashReadings.copyText(crash, emptyList(), ZoneId.of("Asia/Kolkata")).contains("When: 2026-01-01T04:30:04.250+05:30\n"))
        assertEquals("", CrashReadings.copyText(null, emptyList(), zone))
    }

    @Test
    fun copyTextKeepsTheStackVerbatimAndTrimsTrailingBreaksAndAMultiLineDescription() {
        val noisy = crash.copy(stack = crash.stack + "\n\n\n")
        val text = CrashReadings.copyText(noisy, listOf(ProcessExit(crash.timeMs, 6, 100, 0, 7, "line one\nline two")), zone)
        assertTrue(text.contains("... 12 more\n\nRecent exits\n"))
        assertTrue(text.endsWith("Not responding (ANR) (REASON_ANR) · in foreground (importance 100) · pid 7 · line one line two"))
        assertEquals(Locale.US, locale)
    }
}
