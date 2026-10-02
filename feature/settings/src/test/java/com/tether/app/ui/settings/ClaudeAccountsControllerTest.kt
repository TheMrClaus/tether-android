package com.tether.app.ui.settings

import androidx.compose.runtime.MonotonicFrameClock
import com.tether.app.client.ClaudeAccountRemoved
import com.tether.app.client.ClaudeLoginCode
import com.tether.app.client.ClaudeLoginLink
import com.tether.app.client.ClaudeLoginState
import com.tether.app.client.ClaudeLoginStatus
import com.tether.app.client.ClaudeSyncResult
import com.tether.app.client.ClaudeSyncSaved
import com.tether.app.client.SecurityResult
import com.tether.app.ui.settings.AccountsFixtures.ORIGIN
import com.tether.app.ui.settings.AccountsFixtures.OTHER_ORIGIN
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-7rh r2: [ClaudeAccountsController] on a virtual clock (the verifier's controller probes made
 * permanent). The login poll ends on a deadline of the whole loop, request time included (a slow or
 * hung poll cannot stretch it), and a login that ends on its deadline or an error tells the server
 * to drop it; the code goes on cancel, handover and close; each key is busy on its own; Remove is
 * the web's two taps within 4 s and Log out is sent at once; answers about another server are
 * dropped; an owner refusal is said and disables nothing; a status answer folds into the state as it
 * is when it lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeAccountsControllerTest {
    private object Now : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(0L)
    }

    private val link = ClaudeLoginLink.parse("https://claude.ai/oauth/authorize?x=1")!!
    private fun pending() = SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.PendingUrl, link, false, null), ORIGIN, null)
    private fun state(status: ClaudeLoginStatus, error: String? = null) = SecurityResult.Ok(ClaudeLoginState(status, null, false, error), ORIGIN, null)
    private val loaded = ClaudeAccountsState(origin = ORIGIN, accounts = AccountsFixtures.LIST, sync = AccountsFixtures.SYNC)

    /** Every call recorded by name; [poll] scripts the poll (it may take time, or never answer). */
    private class Scripted(val poll: suspend () -> SecurityResult<*>, val others: (String) -> SecurityResult<*>) : com.tether.app.client.ClaudeAccountActions {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        var lastCode: ClaudeLoginCode? = null
        @Suppress("UNCHECKED_CAST") private fun <T> r(n: String): SecurityResult<T> {
            calls += n
            return others(n) as SecurityResult<T>
        }
        override suspend fun add(origin: String, nickname: String) = r<Unit>("add")
        override suspend fun rename(origin: String, accountId: String, nickname: String) = r<Unit>("rename")
        override suspend fun remove(origin: String, accountId: String, deleteCredentials: Boolean) = r<ClaudeAccountRemoved>("remove:$accountId:$deleteCredentials")
        override suspend fun logout(origin: String, accountId: String) = r<Unit>("logout:$accountId")
        override suspend fun startLogin(origin: String, accountId: String) = r<ClaudeLoginState>("startLogin")
        @Suppress("UNCHECKED_CAST") override suspend fun pollLogin(origin: String, accountId: String): SecurityResult<ClaudeLoginState> {
            calls += "poll"
            return poll() as SecurityResult<ClaudeLoginState>
        }
        override suspend fun submitCode(origin: String, accountId: String, code: ClaudeLoginCode): SecurityResult<Unit> {
            lastCode = code
            return r("submitCode")
        }
        override suspend fun cancelLogin(origin: String, accountId: String) = r<Unit>("cancelLogin:$origin:$accountId")
        override suspend fun saveSync(origin: String, config: com.tether.app.client.ClaudeSyncConfig) = r<ClaudeSyncSaved>("saveSync")
        override suspend fun runSync(origin: String) = r<ClaudeSyncSaved>("runSync")
    }

    private fun okOthers(n: String): SecurityResult<*> = when {
        n == "startLogin" -> pending()
        n.startsWith("remove") -> SecurityResult.Ok(ClaudeAccountRemoved(true, true), ORIGIN, null)
        else -> SecurityResult.Ok(Unit, ORIGIN, null)
    }

    private fun TestScope.controller(actions: com.tether.app.client.ClaudeAccountActions, reads: FakeAccounts = FakeAccounts()) =
        ClaudeAccountsController(reads, actions, ORIGIN, CoroutineScope(coroutineContext + Now), seed = loaded, pace = LoginPollPace())

    private fun work() = AccountsFixtures.LIST.first { it.id == "claude-work" }
    private fun fresh() = AccountsFixtures.LIST.first { it.id == "claude-fresh" }
    private val cancelWork = "cancelLogin:$ORIGIN:claude-work"

    // ---- the poll's deadline (verifier P4, security P4-1 and P4-3) ----------------------------------------

    /** Positive control: instant polls run until 15 minutes, then the panel ends and the server is told. */
    @Test fun instantPollsEndAtFifteenMinutesAndTheServerIsTold() = runTest {
        val a = Scripted(poll = { pending() }, others = ::okOthers)
        val c = controller(a)
        assertTrue(c.startLogin(work()))
        runCurrent()
        advanceTimeBy(14 * 60_000L)
        runCurrent()
        assertEquals(ClaudeLoginStatus.PendingUrl, c.logins["claude-work"]?.status)
        assertFalse(cancelWork in a.calls)
        advanceTimeBy(2 * 60_000L)
        runCurrent()
        assertEquals(ClaudeAccountsCopy.LOGIN_TOO_LONG, c.logins["claude-work"]?.error)
        assertEquals(1, a.calls.count { it == cancelWork })
        c.dispose()
    }

    /**
     * The verifier's probe, now asserting the fix: each poll takes 30 s (an unreachable server timing
     * out) and the loop still ends at 15 minutes of the clock, not 15 minutes of waits (it ran 38 polls
     * past 20 minutes before).
     */
    @Test fun slowPollsAreBoundedByWallClock() = runTest {
        val a = Scripted(poll = { delay(30_000); SecurityResult.Unavailable(null, ORIGIN) }, others = ::okOthers)
        val c = controller(a)
        assertTrue(c.startLogin(work()))
        runCurrent()
        advanceTimeBy(14 * 60_000L)
        runCurrent()
        assertNull("still running at 14 min (the control)", c.logins["claude-work"]?.error)
        advanceTimeBy(60_000L + 1_000L)
        runCurrent()
        val polls = a.calls.count { it == "poll" }
        assertEquals("ended at 15 min of the clock (polls=$polls)", ClaudeAccountsCopy.LOGIN_TOO_LONG, c.logins["claude-work"]?.error)
        assertTrue("at most one poll per 32 s fits in 15 min: $polls", polls <= 15 * 60 / 32 + 1)
        assertEquals(1, a.calls.count { it == cancelWork })
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals("nothing polls after the end", polls, a.calls.count { it == "poll" })
        c.dispose()
    }

    /** A poll that never answers is cut at the deadline (request time counts), and the server is told. */
    @Test fun aHungPollIsCutAtTheDeadline() = runTest {
        val a = Scripted(poll = { awaitCancellation() }, others = ::okOthers)
        val c = controller(a)
        assertTrue(c.startLogin(work()))
        runCurrent()
        advanceTimeBy(15 * 60_000L - 1_000L)
        runCurrent()
        assertEquals("one poll, hung (the control: not yet ended)", 1, a.calls.count { it == "poll" })
        assertNull(c.logins["claude-work"]?.error)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(ClaudeAccountsCopy.LOGIN_TOO_LONG, c.logins["claude-work"]?.error)
        assertEquals(1, a.calls.count { it == cancelWork })
        c.dispose()
    }

    @Test fun anErrorEndsThePanelAndTellsTheServerAndIdleEndsItWithoutTelling() = runTest {
        var next: SecurityResult<*> = pending()
        val a = Scripted(poll = { next }, others = ::okOthers)
        val c = controller(a)
        c.startLogin(work())
        runCurrent()
        advanceTimeBy(600)
        runCurrent()
        assertEquals(ClaudeLoginStatus.PendingUrl, c.logins["claude-work"]?.status)
        next = state(ClaudeLoginStatus.Error, "Claude login did not complete (exit code 1).")
        advanceTimeBy(1_600)
        runCurrent()
        assertEquals("Claude login did not complete (exit code 1).", c.logins["claude-work"]?.error)
        assertEquals(1, a.calls.count { it == cancelWork })
        val n = a.calls.count { it == "poll" }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("no poll after the end", n, a.calls.count { it == "poll" })
        // Idle: the server runs nothing any more, so nothing is sent to it.
        c.cancelLogin("claude-work")
        runCurrent()
        val cancels = a.calls.count { it == cancelWork }
        next = pending()
        c.startLogin(work())
        runCurrent()
        advanceTimeBy(600)
        runCurrent()
        next = state(ClaudeLoginStatus.Idle)
        advanceTimeBy(1_600)
        runCurrent()
        assertEquals(ClaudeAccountsCopy.LOGIN_GONE, c.logins["claude-work"]?.error)
        assertEquals(cancels, a.calls.count { it == cancelWork })
        c.dispose()
    }

    @Test fun theCodeGoesOnCancelOnCloseAndOnHandover() = runTest {
        val a = Scripted(poll = { pending() }, others = ::okOthers)
        val c = controller(a)
        c.startLogin(work())
        runCurrent()
        c.editCode("claude-work", "SECRET-1")
        assertEquals("SECRET-1", c.codes["claude-work"])
        c.cancelLogin("claude-work")
        runCurrent()
        assertTrue(c.codes.isEmpty())
        assertNull(c.logins["claude-work"])
        assertTrue(cancelWork in a.calls)
        val n = a.calls.count { it == "poll" }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("cancel stops polling", n, a.calls.count { it == "poll" })
        c.startLogin(work())
        runCurrent()
        c.editCode("claude-work", "SECRET-2")
        assertTrue(c.submitCode("claude-work"))
        runCurrent()
        assertTrue(c.codes.isEmpty())
        assertEquals(ClaudeLoginCode("SECRET-2"), a.lastCode)
        c.editCode("claude-work", "SECRET-3")
        assertEquals("SECRET-3", c.codes["claude-work"])
        c.dispose()
        assertTrue(c.codes.isEmpty())
        val m = a.calls.count { it == "poll" }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("close stops polling", m, a.calls.count { it == "poll" })
        assertFalse(c.logins.values.any { it.toString().contains("SECRET") })
    }

    // ---- busy keys, Remove's two taps, Log out at once (parity, r2) ------------------------------------------

    @Test fun eachKeyIsBusyOnItsOwnAndRemoveIsTheWebsTwoTaps() = runTest {
        val gate = CompletableDeferred<Unit>()
        val a = Scripted(poll = { pending() }, others = ::okOthers)
        val slowAdd = object : com.tether.app.client.ClaudeAccountActions by a {
            override suspend fun add(origin: String, nickname: String): SecurityResult<Unit> {
                a.calls += "add"
                gate.await()
                return SecurityResult.Ok(Unit, ORIGIN, null)
            }
        }
        val c = controller(slowAdd)
        c.openAdd()
        c.editAdd("x")
        assertTrue(c.submitAdd())
        assertFalse("the same key twice in a frame", c.submitAdd())
        assertTrue(c.busy(AccountsAction.Add))
        // The web keeps the other rows usable while an Add is in flight.
        assertTrue(c.runSync())
        runCurrent()
        assertEquals(listOf("add", "runSync"), a.calls.toList())
        // Remove: the first tap only arms; the second sends the box as it is THEN (performRemove).
        c.setDeleteCredentials("claude-work", false)
        assertFalse(c.tapRemove(work(), "Claude Code (work)"))
        assertEquals("claude-work", c.armedRemove)
        runCurrent()
        assertFalse(a.calls.any { it.startsWith("remove") })
        c.setDeleteCredentials("claude-work", true)
        assertTrue(c.tapRemove(work(), "Claude Code (work)"))
        assertNull(c.armedRemove)
        runCurrent()
        assertEquals(listOf("remove:claude-work:true"), a.calls.filter { it.startsWith("remove") })
        // Log out: sent on the tap, nothing asked first.
        assertTrue(c.logout(work()))
        runCurrent()
        assertTrue("logout:claude-work" in a.calls)
        gate.complete(Unit)
        runCurrent()
        assertFalse(c.busy(AccountsAction.Add))
        c.dispose()
    }

    @Test fun anArmLapsesAfterFourSecondsAndArmingAnotherMovesIt() = runTest {
        val a = Scripted(poll = { pending() }, others = ::okOthers)
        val c = controller(a)
        assertFalse(c.tapRemove(work(), "w"))
        advanceTimeBy(ClaudeAccountsCopy.REMOVE_ARM_MS - 100)
        runCurrent()
        assertEquals("still armed inside the window (the control)", "claude-work", c.armedRemove)
        advanceTimeBy(200)
        runCurrent()
        assertNull("lapsed", c.armedRemove)
        assertFalse("a tap after the lapse only arms", c.tapRemove(work(), "w"))
        assertFalse(c.tapRemove(fresh(), "f"))
        assertEquals("the arm moved", "claude-fresh", c.armedRemove)
        assertFalse("claude-work is no longer armed", c.tapRemove(work(), "w"))
        runCurrent()
        assertFalse(a.calls.any { it.startsWith("remove") })
        c.dispose()
    }

    @Test fun anAnswerAboutAnotherServerIsDropped() = runTest {
        val a = Scripted(poll = { pending() }, others = { SecurityResult.Ok(Unit, OTHER_ORIGIN, null) })
        val c = controller(a)
        c.openAdd()
        c.editAdd("x")
        c.submitAdd()
        runCurrent()
        assertTrue("still adding: the other server's Ok was not taken", c.adding)
        assertEquals(0, c.reloads)
        c.dispose()
        val b = Scripted(poll = { pending() }, others = { SecurityResult.Ok(Unit, ORIGIN, null) })
        val d = controller(b)
        d.openAdd()
        d.editAdd("x")
        d.submitAdd()
        runCurrent()
        assertFalse(d.adding)
        assertEquals(1, d.reloads)
        d.dispose()
    }

    /** No app-only gate on a refusal: it is said, every change stays offered, reads go, and the next change clears it. */
    @Test fun anOwnerRefusalIsSaidAndDisablesNothing() = runTest {
        var owner = true
        val a = Scripted(poll = { pending() }, others = { if (owner) SecurityResult.OwnerSignInNeeded(ORIGIN) else SecurityResult.Ok(Unit, ORIGIN, null) })
        val reads = FakeAccounts()
        val c = controller(a, reads)
        assertTrue(c.canChange)
        c.openAdd()
        c.editAdd("x")
        c.submitAdd()
        runCurrent()
        assertTrue(c.ownerNeeded)
        assertTrue(c.canChange)
        c.check("claude-work")
        runCurrent()
        assertTrue("reads still go", reads.calls.contains("status:claude-work"))
        owner = false
        assertTrue(c.submitAdd())
        assertFalse("the next change clears the note", c.ownerNeeded)
        runCurrent()
        assertFalse(c.ownerNeeded)
        c.dispose()
    }

    // ---- fold on arrival (verifier P2) --------------------------------------------------------------------

    /** A status answer held while a Sync now lands keeps the Sync now result (it folds into the state as it is then). */
    @Test fun aStatusAnswerFoldsIntoTheStateAsItIsWhenItLands() = runTest {
        val gate = CompletableDeferred<Unit>()
        val ran = ClaudeSyncSaved(AccountsFixtures.SYNC.config, ClaudeSyncResult(1790000000000, "ok", changed = 0, upToDate = 0, error = null))
        val a = Scripted(poll = { pending() }, others = { if (it == "runSync") SecurityResult.Ok(ran, ORIGIN, null) else SecurityResult.Ok(Unit, ORIGIN, null) })
        val c = controller(a, FakeAccounts(statusGate = { gate.await() }))
        c.check("claude-work")
        runCurrent()
        assertTrue(c.runSync())
        runCurrent()
        assertEquals("the control: the run's result is in", ran.result, c.state.sync?.lastResult)
        gate.complete(Unit)
        runCurrent()
        assertEquals("the status landed", AccountStatusState.Known(AccountsFixtures.LOGGED_IN), c.state.statuses["claude-work"])
        assertEquals("and the run's result is kept", ran.result, c.state.sync?.lastResult)
        c.dispose()
    }
}
