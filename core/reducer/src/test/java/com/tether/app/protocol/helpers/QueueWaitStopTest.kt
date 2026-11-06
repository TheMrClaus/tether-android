package com.tether.app.protocol.helpers

import com.tether.app.protocol.helpers.QueueWait.BackgroundStoppable
import com.tether.app.protocol.helpers.QueueWait.BackgroundTail
import com.tether.app.protocol.helpers.QueueWait.ClaudeStopBand
import com.tether.app.protocol.helpers.QueueWait.LiveWork
import com.tether.app.protocol.helpers.QueueWait.SpawnedRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** tth-fqjc A-stop-core: the twin of tether lib/queue-wait.ts F.1 / F.3 / B.1 / B.3 and lib/claude-stop-band.ts. */
class QueueWaitStopTest {

    @Test
    fun theClaudeStopBandIsSparesFrom2_1_246() {
        assertEquals(ClaudeStopBand.Spares, QueueWait.claudeStopBand("2.1.246"))
        assertEquals(ClaudeStopBand.Spares, QueueWait.claudeStopBand("2.1.300"))
        assertEquals(ClaudeStopBand.Spares, QueueWait.claudeStopBand("2.2.0"))
        assertEquals(ClaudeStopBand.EndsAgents, QueueWait.claudeStopBand("2.1.245"))
        assertEquals(ClaudeStopBand.EndsAgents, QueueWait.claudeStopBand("2.0.999"))
        assertEquals(ClaudeStopBand.Unknown, QueueWait.claudeStopBand(null))
        assertEquals(ClaudeStopBand.Unknown, QueueWait.claudeStopBand(""))
        assertEquals("a prerelease is unparseable, never guessed", ClaudeStopBand.Unknown, QueueWait.claudeStopBand("2.1.246-beta"))
        assertEquals(ClaudeStopBand.Unknown, QueueWait.claudeStopBand("2.1"))
    }

    @Test
    fun stopFactsForNeedsTheCliBandToSpare() {
        val spares = QueueWait.stopFactsFor("claude", persistentSessions = true, cliVersion = "2.1.246")
        assertTrue(spares.backgroundSpared)
        assertTrue(spares.backgroundVisible)
        assertTrue(spares.foregroundCertain)
        assertFalse("below the band", QueueWait.stopFactsFor("claude", true, "2.1.245").backgroundSpared)
        assertFalse("no version reported", QueueWait.stopFactsFor("claude", true, null).backgroundSpared)
        assertFalse("per-turn Claude", QueueWait.stopFactsFor("claude", false, "2.1.300").backgroundSpared)
        assertFalse("another engine", QueueWait.stopFactsFor("codex", true, "2.1.300").backgroundSpared)
    }

    @Test
    fun theBackgroundTailKeepsRunningOnlyOnReasonixAndCodexAppServer() {
        assertEquals(BackgroundTail.KeepsRunning, QueueWait.stopFactsFor("reasonix").backgroundTail)
        assertEquals(BackgroundTail.KeepsRunning, QueueWait.stopFactsFor("codex", engineGeneration = "codex-app-server-v2").backgroundTail)
        assertEquals(BackgroundTail.MayStop, QueueWait.stopFactsFor("codex", engineGeneration = "codex-exec").backgroundTail)
        assertEquals(BackgroundTail.MayStop, QueueWait.stopFactsFor("codex").backgroundTail)
        assertEquals(BackgroundTail.MayStop, QueueWait.stopFactsFor("opencode").backgroundTail)
        assertEquals(BackgroundTail.MayStop, QueueWait.stopFactsFor(null).backgroundTail)
        assertTrue(QueueWait.stopFactsFor("reasonix").foregroundCertain)
    }

    @Test
    fun theTailWordsFollowTheEngine() {
        val none = LiveWork.None
        assertEquals(
            "Stops the current response. Background work the agent started keeps running.",
            QueueWait.interruptCostCopy(none, foregroundCertain = true, backgroundVisible = false, backgroundTail = BackgroundTail.KeepsRunning),
        )
        assertEquals(
            "Stops the current response. It may also stop background work the agent started.",
            QueueWait.interruptCostCopy(none, foregroundCertain = false, backgroundVisible = false),
        )
        val box = BackgroundStoppable(terminals = 2)
        assertEquals(
            "Stops the current response. It also stops 2 background terminals. Other background work the agent started keeps running.",
            QueueWait.interruptCostCopy(none, true, false, backgroundTail = BackgroundTail.KeepsRunning, alsoStop = box),
        )
        assertEquals(
            "Stops the current response. It also stops 2 background terminals. It may also stop other background work the agent started.",
            QueueWait.interruptCostCopy(none, false, false, alsoStop = box),
        )
    }

    @Test
    fun theBoxSentenceCoversTasksAndSparedWordsDropWhenChecked() {
        val work = LiveWork(openTools = 0, outstandingBackground = 2)
        assertEquals(
            "Stops the current response. 2 background tasks keep running.",
            QueueWait.interruptCostCopy(work, true, true, backgroundSpared = true),
        )
        assertEquals(
            "Stops the current response. It also stops 2 background tasks. Claude is told the tasks stopped and may reply right away.",
            QueueWait.interruptCostCopy(work, true, true, backgroundSpared = true, alsoStop = BackgroundStoppable(tasks = 2)),
        )
        assertEquals(
            "Stops the current response. It also stops 1 background task. Claude is told the task stopped and may reply right away.",
            QueueWait.interruptCostCopy(LiveWork(0, 1), true, true, backgroundSpared = false, alsoStop = BackgroundStoppable(tasks = 1)),
        )
        // an unchecked box on a CLI that ends agents keeps the "may stop" words
        assertEquals(
            "Stops the current response, and may stop 2 background tasks.",
            QueueWait.interruptCostCopy(work, true, true, backgroundSpared = false),
        )
    }

    @Test
    fun backgroundStoppableCountsPerKindOnTheServersBasis() {
        val runs = listOf(
            SpawnedRun("running", "spawned"), SpawnedRun("running", "discovered"), SpawnedRun("completed", "spawned"), SpawnedRun("running", "spawned"),
        )
        assertEquals(BackgroundStoppable(3, 1, 2), QueueWait.backgroundStoppable("claude", true, 3, 1, runs))
        assertEquals("per-turn Claude has no stoppable tasks", 0, QueueWait.backgroundStoppable("claude", false, 3, 0, runs).tasks)
        assertEquals("only Claude persistent counts tasks", 0, QueueWait.backgroundStoppable("codex", true, 3, 0, runs).tasks)
        assertEquals(4, QueueWait.backgroundStoppable("codex", false, 3, 4, emptyList()).terminals)
        assertEquals(0, QueueWait.stoppableTotal(null))
        assertEquals(6, QueueWait.stoppableTotal(BackgroundStoppable(3, 1, 2)))
        assertEquals(0, QueueWait.stoppableTotal(QueueWait.backgroundStoppable("pi", false, 5, 0, emptyList())))
    }

    @Test
    fun stopItemsAndTheBoxLabel() {
        assertEquals("", QueueWait.stopItems(null))
        assertEquals("", QueueWait.stopItems(BackgroundStoppable()))
        assertEquals("1 background task", QueueWait.stopItems(BackgroundStoppable(tasks = 1)))
        assertEquals("1 background task and 2 spawned agents", QueueWait.stopItems(BackgroundStoppable(tasks = 1, spawned = 2)))
        assertEquals(
            "2 background tasks, 1 background terminal and 3 spawned agents",
            QueueWait.stopItems(BackgroundStoppable(2, 1, 3)),
        )
        assertEquals("Also stop 1 background terminal", QueueWait.alsoStopLabel(BackgroundStoppable(terminals = 1)))
        assertEquals("Stops it. Extra.", QueueWait.stopDescription("Stops it.", "Extra."))
        assertEquals("Stops it.", QueueWait.stopDescription("Stops it.", ""))
    }

    @Test
    fun theStopBackgroundGateIsServerProtocol156() {
        assertFalse(QueueWait.stopBackgroundSupported(null))
        assertFalse(QueueWait.stopBackgroundSupported(155))
        assertTrue(QueueWait.stopBackgroundSupported(156))
        assertTrue(QueueWait.stopBackgroundSupported(157))
    }
}
