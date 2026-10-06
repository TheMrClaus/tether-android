package com.tether.app.ui.sidebar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.tether.app.client.ArchiveStaleReply
import com.tether.app.protocol.ArchiveStaleRetention
import com.tether.app.protocol.ArchiveStaleSkipped
import com.tether.app.protocol.ServerMessage
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun result(
    mode: String = "preview",
    days: Int = 30,
    eligible: Int = 0,
    archived: Int = 0,
    failed: Int = 0,
    remaining: Int = 0,
    skipped: ArchiveStaleSkipped = ArchiveStaleSkipped(),
    retention: ArchiveStaleRetention = ArchiveStaleRetention(cap = 100),
) = ServerMessage.ArchiveStaleResult(mode, days, eligible, archived, failed, remaining, skipped, retention)

/** components/archive-stale-dialog.tsx (tether #244 part C), as pure rules. */
class ArchiveStaleModelTest {
    @Test fun theChoicesAreSevenFifteenThirtyAndThirtyIsTheDefault() {
        assertEquals(listOf(7, 15, 30), ArchiveStaleModel.THRESHOLDS)
        assertEquals(30, ArchiveStaleState().days)
    }

    @Test fun openingResetsTheTallyAndAsksForAPreviewOfTheChosenThreshold() {
        val before = ArchiveStaleState(days = 15, running = true, archived = 4, failed = 1, done = true)
        val step = ArchiveStaleModel.open(before)
        assertEquals(ArchiveStaleState(days = 15), step.state)
        assertEquals(ArchiveStaleRequest("preview", 15), step.request)
    }

    @Test fun choosingAThresholdAsksForItsPreviewUnlessARunIsInFlight() {
        val chosen = ArchiveStaleModel.choose(ArchiveStaleState(), 7)
        assertEquals(7, chosen.state.days)
        assertEquals(ArchiveStaleRequest("preview", 7), chosen.request)
        val running = ArchiveStaleState(running = true, days = 30)
        val ignored = ArchiveStaleModel.choose(running, 7)
        assertEquals(running, ignored.state)
        assertNull(ignored.request)
    }

    @Test fun startingRunsTheFirstBatchForTheChosenThreshold() {
        val step = ArchiveStaleModel.start(ArchiveStaleState(days = 15, archived = 9, done = true))
        assertEquals(ArchiveStaleState(days = 15, running = true), step.state)
        assertEquals(ArchiveStaleRequest("run", 15), step.request)
    }

    @Test fun aRunReplyWithMoreRemainingTalliesAndAsksForTheNextBatch() {
        val step = ArchiveStaleModel.onReply(ArchiveStaleState(running = true), result(mode = "run", archived = 25, remaining = 10))
        assertEquals(ArchiveStaleState(running = true, archived = 25), step.state)
        assertEquals(ArchiveStaleRequest("run", 30), step.request)
    }

    @Test fun theLastBatchSettlesTheSummary() {
        val step = ArchiveStaleModel.onReply(ArchiveStaleState(running = true, archived = 25), result(mode = "run", archived = 3, remaining = 0))
        assertEquals(ArchiveStaleState(running = false, archived = 28, done = true), step.state)
        assertNull(step.request)
    }

    @Test fun aFailureOrAnEmptyBatchStopsTheRunEvenWithSomeRemaining() {
        val failed = ArchiveStaleModel.onReply(ArchiveStaleState(running = true), result(mode = "run", archived = 20, failed = 2, remaining = 8))
        assertTrue(failed.state.done)
        assertEquals(2, failed.state.failed)
        assertNull(failed.request)
        val stuck = ArchiveStaleModel.onReply(ArchiveStaleState(running = true), result(mode = "run", archived = 0, remaining = 8))
        assertTrue(stuck.state.done)
        assertNull(stuck.request)
    }

    @Test fun aReplyIsIgnoredUnlessItIsARunReplyWhileRunning() {
        val idle = ArchiveStaleState()
        assertEquals(idle, ArchiveStaleModel.onReply(idle, result(mode = "run", archived = 5)).state)
        val running = ArchiveStaleState(running = true)
        val preview = ArchiveStaleModel.onReply(running, result(mode = "preview", eligible = 5))
        assertEquals(running, preview.state)
        assertNull(preview.request)
    }

    @Test fun stoppingSettlesOnWhatWasDone() {
        assertEquals(ArchiveStaleState(running = false, archived = 25, done = true), ArchiveStaleModel.stop(ArchiveStaleState(running = true, archived = 25)))
    }

    @Test fun theBodyFollowsTheStateAndOnlyAPreviewOfTheChosenThresholdCounts() {
        assertEquals(ArchiveStaleBody.Checking, ArchiveStaleModel.body(ArchiveStaleState(), null))
        assertEquals(ArchiveStaleBody.Checking, ArchiveStaleModel.body(ArchiveStaleState(days = 7), result(days = 30, eligible = 4)))
        val preview = ArchiveStaleModel.body(ArchiveStaleState(), result(eligible = 4)) as ArchiveStaleBody.Preview
        assertEquals(4, preview.eligible)
        assertEquals(
            ArchiveStaleBody.Running("Archiving… 2 done · 5 left"),
            ArchiveStaleModel.body(ArchiveStaleState(running = true, archived = 2), result(mode = "run", remaining = 5)),
        )
        assertEquals(
            ArchiveStaleBody.Running("Archiving… 0 done"),
            ArchiveStaleModel.body(ArchiveStaleState(running = true), result(mode = "preview", eligible = 5)),
        )
        assertEquals(ArchiveStaleBody.Done("Archived 1 session."), ArchiveStaleModel.body(ArchiveStaleState(done = true, archived = 1), null))
    }

    @Test fun theWordsAreTheWebs() {
        assertEquals("Archived 3 sessions.", ArchiveStaleCopy.done(3, 0))
        assertEquals("Archived 0 sessions · 2 failed — see the server log.", ArchiveStaleCopy.done(0, 2))
        assertEquals("1 session idle for more than 7 days will be archived.", ArchiveStaleCopy.preview(1, 7))
        assertEquals("12 sessions idle for more than 30 days will be archived.", ArchiveStaleCopy.preview(12, 30))
        assertEquals("Archive 12", ArchiveStaleCopy.archiveKey(12))
        assertEquals("15 days", ArchiveStaleCopy.dayLabel(15))
        val skipped = result(skipped = ArchiveStaleSkipped(pinned = 2, inFlight = 1, pendingRequest = 0, background = 3, viewing = 1))
        assertEquals("Skipped: 2 pinned · 1 turn in progress · 3 background work running · 1 open now.", ArchiveStaleCopy.skipped(skipped, 30))
        assertNull(ArchiveStaleCopy.skipped(result(), 30))
        assertEquals("Skipped: 1 waiting on you.", ArchiveStaleCopy.skipped(result(skipped = ArchiveStaleSkipped(pendingRequest = 1)), 30))
        assertNull(ArchiveStaleCopy.retention(result()))
        assertEquals(
            "Tether keeps only the newest 100 archived sessions. About 7 of the oldest will be removed from Tether (history and journal). " +
                "Their conversations stay resumable from the CLI's own history.",
            ArchiveStaleCopy.retention(result(retention = ArchiveStaleRetention(cap = 100, retiredNow = 95, willBePruned = 7))),
        )
        assertEquals(
            "Archiving also stops a session's worktree services and removes its checkout when it is clean and merged.",
            ArchiveStaleCopy.WORKTREE_NOTE,
        )
    }
}

/** The dialog as drawn and driven in the sidebar (phone drawer and expanded rail). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ArchiveStaleDialogBehaviourTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    private data class Sent(val mode: String, val days: Int, val except: String?, val origin: String?)

    private val sent = mutableListOf<Sent>()
    private var cleared = 0
    private var seq = 0L
    private var state by mutableStateOf(F.state(F.drawerSessions.take(3), activeId = "s02").copy(origin = "https://a.example"))

    private val actions = SidebarActions(
        onArchiveStale = { mode, days, except, origin -> sent += Sent(mode, days, except, origin); true },
        onClearArchiveStale = { cleared += 1; state = state.copy(archiveStale = null) },
    )

    private fun show(layout: TetherLayoutClass = TetherLayoutClass.Phone) {
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, state, layout, SidebarUiSeed(), actions) }
        rule.waitForIdle()
    }

    private fun reply(r: ServerMessage.ArchiveStaleResult) {
        state = state.copy(archiveStale = ArchiveStaleReply(++seq, r))
        rule.waitForIdle()
    }

    private fun open() {
        rule.onNodeWithTag(ArchiveStaleTags.Entry).performClick()
        rule.waitForIdle()
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    // The dialog's scrim is clickable, which merges what is under it: the text is read from the unmerged tree.
    private fun status() = rule.onNodeWithTag(ArchiveStaleTags.Status, useUnmergedTree = true)

    @Test fun theEntryRowOpensTheDialogAndAsksForAThirtyDayPreviewExemptingTheOpenSession() {
        show()
        rule.onNodeWithTag(ArchiveStaleTags.Entry).assertExists()
        open()
        assertEquals(listOf(Sent("preview", 30, "s02", "https://a.example")), sent)
        assertEquals(1, cleared)
        status().assertTextEquals("Checking…")
        rule.onNodeWithTag(ArchiveStaleTags.Run).assertIsNotEnabled()
        rule.onNodeWithTag(ArchiveStaleTags.day(30)).assertExists()
        rule.onNodeWithTag(ArchiveStaleTags.day(7)).assertExists()
        rule.onNodeWithTag(ArchiveStaleTags.day(15)).assertExists()
    }

    @Test fun thePreviewNamesTheCountWhatWasSkippedTheRetentionAndTheWorktreeNote() {
        show()
        open()
        reply(
            result(
                eligible = 3,
                skipped = ArchiveStaleSkipped(pinned = 1, viewing = 1),
                retention = ArchiveStaleRetention(cap = 100, retiredNow = 99, willBePruned = 2),
            ),
        )
        status().assertTextEquals("3 sessions idle for more than 30 days will be archived.")
        rule.onNodeWithTag(ArchiveStaleTags.Skipped, useUnmergedTree = true).assertTextEquals("Skipped: 1 pinned · 1 open now.")
        rule.onNodeWithTag(ArchiveStaleTags.Warning, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(ArchiveStaleTags.Note, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(ArchiveStaleTags.Run).assertIsEnabled().assertTextEquals("Archive 3")
    }

    @Test fun anEmptyPreviewOffersNothingToArchive() {
        show()
        open()
        reply(result(eligible = 0))
        status().assertTextEquals("0 sessions idle for more than 30 days will be archived.")
        rule.onNodeWithTag(ArchiveStaleTags.Run).assertIsNotEnabled().assertTextEquals("Nothing to archive")
    }

    @Test fun choosingAnotherThresholdAsksForItsPreviewAndWaitsForIt() {
        show()
        open()
        reply(result(eligible = 3))
        tap(ArchiveStaleTags.day(15))
        assertEquals(Sent("preview", 15, "s02", "https://a.example"), sent.last())
        // The 30-day preview still on screen is not the 15-day answer.
        status().assertTextEquals("Checking…")
        rule.onNodeWithTag(ArchiveStaleTags.Run).assertIsNotEnabled()
        reply(result(days = 15, eligible = 8))
        status().assertTextEquals("8 sessions idle for more than 15 days will be archived.")
        rule.onNodeWithTag(ArchiveStaleTags.Run).assertTextEquals("Archive 8")
    }

    @Test fun aRunGoesBatchByBatchAndSettlesOnTheSummary() {
        show()
        open()
        reply(result(eligible = 30))
        tap(ArchiveStaleTags.Run)
        assertEquals(Sent("run", 30, "s02", "https://a.example"), sent.last())
        status().assertTextEquals("Archiving… 0 done")
        rule.onNodeWithTag(ArchiveStaleTags.Stop).assertExists()

        reply(result(mode = "run", archived = 25, remaining = 5))
        assertEquals("the next batch is asked for, one at a time", listOf("preview", "run", "run"), sent.map { it.mode })
        status().assertTextEquals("Archiving… 25 done · 5 left")

        // An equal reply is still a reply (the client numbers them): the run goes on to its end.
        reply(result(mode = "run", archived = 25, remaining = 5))
        assertEquals(listOf("preview", "run", "run", "run"), sent.map { it.mode })
        status().assertTextEquals("Archiving… 50 done · 5 left")
        reply(result(mode = "run", archived = 3, remaining = 0))
        assertEquals(4, sent.size)
        status().assertTextEquals("Archived 53 sessions.")
        rule.onNodeWithTag(ArchiveStaleTags.Done).assertExists()
    }

    @Test fun aFailedBatchSettlesWithTheCountAndPointsAtTheServerLog() {
        show()
        open()
        reply(result(eligible = 4))
        tap(ArchiveStaleTags.Run)
        reply(result(mode = "run", archived = 1, failed = 1, remaining = 2))
        assertEquals(2, sent.size)
        status().assertTextEquals("Archived 1 session · 1 failed — see the server log.")
    }

    @Test fun stoppingBetweenBatchesSettlesAndLaterRepliesAreIgnored() {
        show()
        open()
        reply(result(eligible = 60))
        tap(ArchiveStaleTags.Run)
        reply(result(mode = "run", archived = 25, remaining = 35))
        tap(ArchiveStaleTags.Stop)
        status().assertTextEquals("Archived 25 sessions.")
        val frames = sent.size
        reply(result(mode = "run", archived = 25, remaining = 10))
        assertEquals("a batch in flight when Stop was pressed does not start another", frames, sent.size)
        status().assertTextEquals("Archived 25 sessions.")
    }

    @Test fun theDaysAreInertWhileARunIsInFlight() {
        show()
        open()
        reply(result(eligible = 4))
        tap(ArchiveStaleTags.Run)
        rule.onNodeWithTag(ArchiveStaleTags.day(7)).assertIsNotEnabled()
    }

    @Test fun cancelClosesAndTheThresholdChosenLastIsOfferedNextTime() {
        show()
        open()
        reply(result(eligible = 2))
        tap(ArchiveStaleTags.day(7))
        reply(result(days = 7, eligible = 1))
        tap(ArchiveStaleTags.Cancel)
        rule.onNodeWithTag(ArchiveStaleTags.Dialog).assertDoesNotExist()
        open()
        assertEquals(Sent("preview", 7, "s02", "https://a.example"), sent.last())
        assertFalse(sent.any { it.mode == "run" })
    }

    @Test fun itWorksOnTheExpandedRailToo() {
        show(TetherLayoutClass.Expanded)
        open()
        reply(result(eligible = 1))
        status().assertTextEquals("1 session idle for more than 30 days will be archived.")
        rule.onNodeWithTag(ArchiveStaleTags.Run).assertTextEquals("Archive 1")
    }
}
