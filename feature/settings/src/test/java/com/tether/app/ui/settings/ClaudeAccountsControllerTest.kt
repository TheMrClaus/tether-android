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
 * ta-7rh: [ClaudeAccountsController] on a virtual clock (the verifier's controller probes made
 * permanent). r3 (owner rule): the login poll runs as the web's, with no limit, a refusal polled
 * again, and only Cancel telling the server; a Log in or a sync save that sends nothing changes
 * nothing, and two sync toggles in one frame both land; Add needs no list. The code goes on cancel,
 * handover and close; each key is busy on its own; Remove is
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

    // ---- the poll, as the web's (r3, owner rule): no limit, nothing cancelled but by Cancel ---------------

    /**
     * Inverts r2's instantPollsEndAtFifteenMinutesAndTheServerIsTold: the web polls with no limit
     * (settings-dialog.tsx `pollLogin` reschedules itself) and the server has none, so a login still
     * waiting after 20 minutes is still polled and its server login is never dropped. The control:
     * Cancel ends it and tells the server.
     */
    @Test fun pollsRunPastFifteenMinutesAndNothingIsCancelled() = runTest {
        val a = Scripted(poll = { pending() }, others = ::okOthers)
        val c = controller(a)
        assertTrue(c.startLogin(work()))
        runCurrent()
        advanceTimeBy(20 * 60_000L)
        runCurrent()
        val polls = a.calls.count { it == "poll" }
        assertTrue("still polling at 20 min: $polls", polls > 15 * 60_000 / 1_500)
        assertEquals(ClaudeLoginStatus.PendingUrl, c.logins["claude-work"]?.status)
        assertNull(c.logins["claude-work"]?.error)
        assertFalse(cancelWork in a.calls)
        advanceTimeBy(1_600)
        runCurrent()
        assertTrue("and on", a.calls.count { it == "poll" } > polls)
        c.cancelLogin("claude-work")
        runCurrent()
        assertEquals(1, a.calls.count { it == cancelWork })
        c.dispose()
    }

    /** Inverts r2's slowPollsAreBoundedByWallClock: 30 s polls go on past 20 minutes, as the web's. */
    @Test fun slowPollsGoOnAsTheWebsDo() = runTest {
        val a = Scripted(poll = { delay(30_000); SecurityResult.Unavailable(null, ORIGIN) }, others = ::okOthers)
        val c = controller(a)
        assertTrue(c.startLogin(work()))
        runCurrent()
        advanceTimeBy(20 * 60_000L)
        runCurrent()
        val polls = a.calls.count { it == "poll" }
        assertTrue("polled on past 15 min: $polls", polls > 15 * 60 / 32 + 1)
        assertNull(c.logins["claude-work"]?.error)
        assertFalse(cancelWork in a.calls)
        c.dispose()
    }

    /** Inverts r2's aHungPollIsCutAtTheDeadline: a poll that never answers is not cut here (the web's fetch is not either). */
    @Test fun aHungPollIsNotCut() = runTest {
        val a = Scripted(poll = { awaitCancellation() }, others = ::okOthers)
        val c = controller(a)
        assertTrue(c.startLogin(work()))
        runCurrent()
        advanceTimeBy(30 * 60_000L)
        runCurrent()
        assertEquals(1, a.calls.count { it == "poll" })
        assertEquals(ClaudeLoginStatus.PendingUrl, c.logins["claude-work"]?.status)
        assertFalse(cancelWork in a.calls)
        c.dispose()
    }

    /**
     * settings-dialog.tsx :1565-1577: an answer without a status (a 4xx refusal: 401, 403, 404, 409,
     * 429) is read as "pending-url" with the link kept, and polled again after 1.5 s; a failed fetch
     * after 2 s. Nothing ends, nothing is cancelled. The control: an awaiting-code answer is then taken.
     */
    @Test fun aRefusedPollIsPolledAgainAsOnTheWeb() = runTest {
        val refusals: List<SecurityResult<*>> = listOf(
            SecurityResult.Refused(404, "No such Claude account.", ORIGIN),
            SecurityResult.Refused(429, "Too many requests.", ORIGIN),
            SecurityResult.SignedOut(ORIGIN),
            SecurityResult.OwnerSignInNeeded(ORIGIN),
        )
        var next: SecurityResult<*> = SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, link, false, null), ORIGIN, null)
        val a = Scripted(poll = { next }, others = ::okOthers)
        val c = controller(a)
        c.startLogin(work())
        runCurrent()
        advanceTimeBy(600)
        runCurrent()
        assertEquals("the control", ClaudeLoginStatus.AwaitingCode, c.logins["claude-work"]?.status)
        for (r in refusals) {
            next = r
            val n = a.calls.count { it == "poll" }
            advanceTimeBy(1_600)
            runCurrent()
            assertEquals("$r polled once more", n + 1, a.calls.count { it == "poll" })
            val panel = c.logins["claude-work"]
            assertEquals("$r", ClaudeLoginStatus.PendingUrl, panel?.status)
            assertEquals("$r keeps the link", link, panel?.link)
            assertNull("$r", panel?.error)
            assertFalse("$r: nothing said about an owner", c.ownerNeeded)
        }
        assertFalse(cancelWork in a.calls)
        next = SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, null, false, null), ORIGIN, null)
        advanceTimeBy(1_600)
        runCurrent()
        assertEquals(ClaudeLoginStatus.AwaitingCode, c.logins["claude-work"]?.status)
        assertEquals(link, c.logins["claude-work"]?.link)
        c.dispose()
    }

    /** The server's error ends the panel and idle ends it; neither tells the server anything (the web sends nothing then). */
    @Test fun anErrorAndIdleEndThePanelAndSendNothing() = runTest {
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
        assertFalse(cancelWork in a.calls)
        val n = a.calls.count { it == "poll" }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("no poll after the end", n, a.calls.count { it == "poll" })
        // The control: Cancel tells the server.
        c.cancelLogin("claude-work")
        runCurrent()
        assertEquals(1, a.calls.count { it == cancelWork })
        next = pending()
        c.startLogin(work())
        runCurrent()
        advanceTimeBy(600)
        runCurrent()
        next = state(ClaudeLoginStatus.Idle)
        advanceTimeBy(1_600)
        runCurrent()
        assertEquals(ClaudeAccountsCopy.LOGIN_GONE, c.logins["claude-work"]?.error)
        assertEquals(1, a.calls.count { it == cancelWork })
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

    // ---- r3: security F1, a Log in that sends nothing changes nothing; a restart as the web's ---------------

    /** Signed out (or a second tap on an open panel), Log in sends nothing and leaves everything as it was. */
    @Test fun aLogInThatSendsNothingChangesNothing() = runTest {
        val a = Scripted(poll = { pending() }, others = ::okOthers)
        val out = ClaudeAccountsController(FakeAccounts(), a, null, CoroutineScope(coroutineContext + Now), seed = loaded, pace = LoginPollPace())
        assertFalse(out.startLogin(work()))
        assertTrue(out.logins.isEmpty())
        out.dispose()
        val c = controller(a)
        assertTrue("the control", c.startLogin(work()))
        val panel = c.logins["claude-work"]
        assertFalse("a second tap on an open panel", c.startLogin(work()))
        assertEquals(panel, c.logins["claude-work"])
        runCurrent()
        assertEquals(1, a.calls.count { it == "startLogin" })
        c.dispose()
    }

    /**
     * Security F1's case: Log in, Cancel while the start is in flight, Log in again. As on the web, the
     * second start is sent (it is its own start); the first one's answer lands on nothing and nothing
     * is cancelled for it; the second one's answer is taken. Never stuck on "Starting…".
     */
    @Test fun logInAgainAfterACancelWhileTheStartIsInFlightStartsAgain() = runTest {
        val first = CompletableDeferred<SecurityResult<ClaudeLoginState>>()
        val second = CompletableDeferred<SecurityResult<ClaudeLoginState>>()
        val a = Scripted(poll = { awaitCancellation() }, others = ::okOthers)
        var starts = 0
        val gated = object : com.tether.app.client.ClaudeAccountActions by a {
            override suspend fun startLogin(origin: String, accountId: String): SecurityResult<ClaudeLoginState> {
                a.calls += "startLogin"
                return if (++starts == 1) first.await() else second.await()
            }
        }
        val c = controller(gated)
        assertTrue(c.startLogin(work()))
        runCurrent()
        c.cancelLogin("claude-work")
        runCurrent()
        assertEquals(1, a.calls.count { it == cancelWork })
        assertTrue("the second tap is sent", c.startLogin(work()))
        runCurrent()
        assertEquals(2, a.calls.count { it == "startLogin" })
        assertTrue(c.logins["claude-work"]?.starting == true)
        first.complete(pending())
        runCurrent()
        assertTrue("the stale answer is dropped", c.logins["claude-work"]?.starting == true)
        assertEquals("and nothing is cancelled for it", 1, a.calls.count { it == cancelWork })
        second.complete(SecurityResult.Ok(ClaudeLoginState(ClaudeLoginStatus.AwaitingCode, link, false, null), ORIGIN, null))
        runCurrent()
        assertEquals(ClaudeLoginStatus.AwaitingCode, c.logins["claude-work"]?.status)
        assertFalse(c.logins["claude-work"]!!.starting)
        c.dispose()
    }

    // ---- r3: security F2, two sync toggles in one frame both land ------------------------------------------

    @Test fun twoSyncTogglesInOneFrameBothLand() = runTest {
        val a = Scripted(poll = { pending() }, others = { SecurityResult.Unavailable(null, ORIGIN) })
        val sent = mutableListOf<com.tether.app.client.ClaudeSyncConfig>()
        val recording = object : com.tether.app.client.ClaudeAccountActions by a {
            override suspend fun saveSync(origin: String, config: com.tether.app.client.ClaudeSyncConfig): SecurityResult<ClaudeSyncSaved> {
                sent += config
                return SecurityResult.Ok(ClaudeSyncSaved(config, null), ORIGIN, null)
            }
        }
        val d = controller(recording)
        val drawn = AccountsFixtures.SYNC.config
        assertTrue(d.saveSync { it.copy(categories = it.categories.copy(hooks = true)) })
        // Shown at once, in the tap's frame (nothing has run yet).
        assertTrue(d.state.sync!!.config.categories.hooks)
        assertTrue(d.saveSync { it.copy(categories = it.categories.copy(skills = false)) })
        assertTrue(d.state.sync!!.config.categories.hooks)
        assertFalse(d.state.sync!!.config.categories.skills)
        runCurrent()
        assertEquals(2, sent.size)
        assertEquals("the first", drawn.copy(categories = drawn.categories.copy(hooks = true)), sent[0])
        assertEquals("the second builds on the first", drawn.copy(categories = drawn.categories.copy(hooks = true, skills = false)), sent[1])
        assertEquals(sent[1], d.state.sync!!.config)
        d.dispose()
    }

    /** A save that sends nothing (signed out) shows nothing either. */
    @Test fun aSyncSaveThatSendsNothingChangesNothing() = runTest {
        val a = Scripted(poll = { pending() }, others = ::okOthers)
        val out = ClaudeAccountsController(FakeAccounts(), a, null, CoroutineScope(coroutineContext + Now), seed = loaded, pace = LoginPollPace())
        assertFalse(out.saveSync { it.copy(categories = it.categories.copy(hooks = true)) })
        assertEquals(AccountsFixtures.SYNC.config, out.state.sync!!.config)
        out.dispose()
    }

    // ---- r3: Add without a list, as the web's -----------------------------------------------------------

    @Test fun addIsSentWhileTheListLoadsAndAfterItFailed() = runTest {
        for (seed in listOf(ClaudeAccountsState(origin = ORIGIN), ClaudeAccountsState(origin = ORIGIN, listFault = AccountsFault.Unavailable(500)))) {
            val a = Scripted(poll = { pending() }, others = ::okOthers)
            val c = ClaudeAccountsController(FakeAccounts(), a, ORIGIN, CoroutineScope(coroutineContext + Now), seed = seed, pace = LoginPollPace())
            assertTrue("$seed", c.canChange)
            c.openAdd()
            c.editAdd("home")
            assertTrue("$seed", c.submitAdd())
            runCurrent()
            assertEquals(listOf("add"), a.calls.toList())
            c.dispose()
        }
        // The control: signed out, nothing.
        val a = Scripted(poll = { pending() }, others = ::okOthers)
        val out = ClaudeAccountsController(FakeAccounts(), a, null, CoroutineScope(coroutineContext + Now), seed = ClaudeAccountsState(), pace = LoginPollPace())
        out.openAdd()
        out.editAdd("home")
        assertFalse(out.submitAdd())
        out.dispose()
    }
}
