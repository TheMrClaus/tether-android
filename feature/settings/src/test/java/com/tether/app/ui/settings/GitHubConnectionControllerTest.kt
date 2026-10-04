package com.tether.app.ui.settings

import com.tether.app.client.GitHubConnectionSource
import com.tether.app.client.GitHubConnectionStatus
import com.tether.app.client.GitHubDevicePoll
import com.tether.app.client.GitHubDeviceStatus
import com.tether.app.client.GitHubToken
import com.tether.app.client.GitHubTokenSaved
import com.tether.app.client.SecurityResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

const val GH_ORIGIN = "https://tether.example.test"
const val GH_OTHER = "https://other.example.test"

/** A status the server sends (lib/github-auth.mjs `statusGitHubConnection`). */
object GitHubFixtures {
    val NOT_INSTALLED = GitHubConnectionStatus(ghInstalled = false, ghVersion = null, authenticated = false, account = null, scopes = emptyList(), managedToken = false)
    val NOT_LOGGED_IN = GitHubConnectionStatus(ghInstalled = true, ghVersion = "2.97.0", authenticated = false, account = null, scopes = emptyList(), managedToken = false)
    val HOST = GitHubConnectionStatus(ghInstalled = true, ghVersion = "2.97.0", authenticated = true, account = "octocat", scopes = listOf("gist", "read:org", "repo"), managedToken = false)
    val MANAGED = GitHubConnectionStatus(ghInstalled = true, ghVersion = "2.97.0", authenticated = true, account = "octocat", scopes = listOf("repo"), managedToken = true)
    fun pending(code: String? = null, uri: String? = null) = GitHubDevicePoll(ok = true, status = GitHubDeviceStatus.Pending, deviceCode = code, verificationUri = uri, error = null)

    /** An obviously fake token. */
    const val FAKE_TOKEN = "ghp_FAKE0000TOKEN0000FOR0000TESTS"
}

/**
 * A scripted source: each call takes the next answer queued for it (or a default), and is recorded
 * with the virtual time it was made at. A token is recorded only as its toString (`***`): the
 * section never gets to read it, and neither does this fake.
 */
class FakeGitHub(private val clock: () -> Long = { 0L }) : GitHubConnectionSource {
    // Thread-safe: the behaviour tests queue answers from the test thread while the screen calls on main.
    val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Long>>()
    val statuses = java.util.concurrent.ConcurrentLinkedDeque<SecurityResult<GitHubConnectionStatus>>()
    val starts = java.util.concurrent.ConcurrentLinkedDeque<SecurityResult<Unit>>()
    val polls = java.util.concurrent.ConcurrentLinkedDeque<SecurityResult<GitHubDevicePoll>>()
    val saves = java.util.concurrent.ConcurrentLinkedDeque<SecurityResult<GitHubTokenSaved>>()
    val logouts = java.util.concurrent.ConcurrentLinkedDeque<SecurityResult<Unit>>()
    @Volatile var gate: CompletableDeferred<Unit>? = null
    val tokens = java.util.concurrent.CopyOnWriteArrayList<GitHubToken>()

    fun names() = calls.map { it.first }
    fun count(name: String) = calls.count { it.first == name }

    private suspend fun record(name: String, origin: String) {
        calls += "$name@$origin" to clock()
        gate?.await()
    }

    override suspend fun status(origin: String): SecurityResult<GitHubConnectionStatus> {
        record("status", origin)
        return statuses.poll() ?: SecurityResult.Ok(GitHubFixtures.NOT_LOGGED_IN, origin, null)
    }

    override suspend fun startLogin(origin: String): SecurityResult<Unit> {
        record("start", origin)
        return starts.poll() ?: SecurityResult.Ok(Unit, origin, null)
    }

    override suspend fun pollLogin(origin: String): SecurityResult<GitHubDevicePoll> {
        record("poll", origin)
        return polls.poll() ?: SecurityResult.Ok(GitHubFixtures.pending("ABCD-1234", "https://github.com/login/device"), origin, null)
    }

    override suspend fun cancelLogin(origin: String): SecurityResult<Unit> {
        record("cancel", origin)
        return SecurityResult.Ok(Unit, origin, null)
    }

    override suspend fun saveToken(origin: String, token: GitHubToken): SecurityResult<GitHubTokenSaved> {
        tokens += token
        record("token", origin)
        return saves.poll() ?: SecurityResult.Ok(GitHubTokenSaved("octocat", listOf("repo")), origin, null)
    }

    override suspend fun logout(origin: String): SecurityResult<Unit> {
        record("logout", origin)
        return logouts.poll() ?: SecurityResult.Ok(Unit, origin, null)
    }
}

/**
 * ta-coik.21: GitHubConnectionController against settings-dialog.tsx 90fbb9f :968-1078, on virtual time:
 * the status read and its failures, the device flow's pace (500 ms, 1.5 s, 2 s after a failed fetch,
 * no client limit) and how each poll answer ends it, Cancel, the token's save and its refusals, the
 * one shared busy flag, Remove Tether token, and the token's lifetime (memory only, wiped on dispose
 * and on another server).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GitHubConnectionControllerTest {
    private fun TestScope.fake() = FakeGitHub { testScheduler.currentTime }

    private fun TestScope.controller(source: FakeGitHub, origin: String? = GH_ORIGIN) =
        GitHubConnectionController(source, origin, backgroundScope)

    // ---- the status ----------------------------------------------------------------------------------

    @Test fun theStatusIsReadOnceOnCreation() = runTest {
        val gh = fake()
        gh.statuses += SecurityResult.Ok(GitHubFixtures.HOST, GH_ORIGIN, null)
        val c = controller(gh)
        assertTrue(c.loading)
        runCurrent()
        assertFalse(c.loading)
        assertEquals(GitHubFixtures.HOST, c.status)
        assertTrue(c.connected)
        assertEquals(listOf("status@$GH_ORIGIN"), gh.names())
        assertEquals("Connected as @octocat · host gh login · gh 2.97.0 · gist, read:org, repo", GitHubCopy.statusLine(c.status!!))
    }

    @Test fun signedOutReadsNothingAndSaysSo() = runTest {
        val gh = fake()
        val c = controller(gh, origin = null)
        advanceTimeBy(10_000)
        assertEquals(GitHubCopy.SIGNED_OUT_STATUS, c.error)
        assertFalse(c.startDevice())
        c.editToken(GitHubFixtures.FAKE_TOKEN)
        assertFalse(c.saveToken())
        assertFalse(c.disconnect())
        assertTrue(gh.calls.isEmpty())
    }

    @Test fun aFailedReadSaysWhyAndKeepsTheLastStatus() = runTest {
        val gh = fake()
        gh.statuses += SecurityResult.Ok(GitHubFixtures.MANAGED, GH_ORIGIN, null)
        gh.statuses += SecurityResult.Unavailable(502, GH_ORIGIN)
        gh.statuses += SecurityResult.Refused(400, "Something the server said.", GH_ORIGIN)
        gh.statuses += SecurityResult.Blocked(302, GH_ORIGIN)
        val c = controller(gh)
        runCurrent()
        c.loadStatus()
        runCurrent()
        // :985-988: `data.error || "Could not reach the GitHub status service."`; the status stays (:1080).
        assertEquals(GitHubCopy.STATUS_FAILED, c.error)
        assertTrue(c.connected)
        c.loadStatus()
        runCurrent()
        assertEquals("Something the server said.", c.error)
        c.loadStatus()
        runCurrent()
        assertEquals(GitHubCopy.blockedStatus(302), c.error)
        // r2 (verifier): a 5xx's JSON sentence is shown as the web shows `data.error`; one with none is the fallback.
        gh.statuses += SecurityResult.Refused(500, "Internal server error.", GH_ORIGIN)
        gh.statuses += SecurityResult.Refused(503, "", GH_ORIGIN)
        c.loadStatus()
        runCurrent()
        assertEquals("Internal server error.", c.error)
        c.loadStatus()
        runCurrent()
        assertEquals(GitHubCopy.STATUS_FAILED, c.error)
    }

    @Test fun theStatusLineIsTheWebsForEachCase() {
        assertEquals(GitHubCopy.NOT_INSTALLED, GitHubCopy.statusLine(GitHubFixtures.NOT_INSTALLED))
        assertEquals(GitHubCopy.NOT_LOGGED_IN, GitHubCopy.statusLine(GitHubFixtures.NOT_LOGGED_IN))
        assertEquals("Connected as @octocat · managed token · gh 2.97.0 · repo", GitHubCopy.statusLine(GitHubFixtures.MANAGED))
        // `status.account || "unknown"`, no version, no scopes.
        assertEquals(
            "Connected as @unknown · host gh login",
            GitHubCopy.statusLine(GitHubConnectionStatus(true, null, true, null, emptyList(), false)),
        )
    }

    // ---- the device flow -------------------------------------------------------------------------------

    @Test fun theDeviceFlowPollsAtTheWebsPaceUntilItCompletes() = runTest {
        val gh = fake()
        val c = controller(gh)
        runCurrent()
        assertTrue(c.startDevice())
        // The key is held in the tap's own frame: a second tap sends nothing.
        assertTrue(c.busy)
        assertFalse(c.startDevice())
        runCurrent()
        assertTrue(c.deviceFlow)
        assertEquals(GitHubDevicePoll.Started, c.poll)
        gh.polls += SecurityResult.Ok(GitHubFixtures.pending(), GH_ORIGIN, null)
        gh.polls += SecurityResult.Ok(GitHubFixtures.pending("ABCD-1234", "https://github.com/login/device"), GH_ORIGIN, null)
        gh.polls += SecurityResult.Ok(GitHubDevicePoll(true, GitHubDeviceStatus.Complete, "ABCD-1234", "https://github.com/login/device", null), GH_ORIGIN, null)
        gh.statuses += SecurityResult.Ok(GitHubFixtures.HOST, GH_ORIGIN, null)
        advanceTimeBy(10_000)
        val polls = gh.calls.filter { it.first.startsWith("poll") }.map { it.second }
        // :1020 first at 500 ms, :1011 then every 1.5 s while pending.
        assertEquals(listOf(500L, 2_000L, 3_500L), polls)
        assertFalse(c.deviceFlow)
        assertTrue(c.connected)
        assertEquals(listOf("status", "start", "poll", "poll", "poll", "status"), gh.names().map { it.substringBefore('@') })
    }

    @Test fun theCodeIsShownWhileItIsPending() = runTest {
        val gh = fake()
        val c = controller(gh)
        runCurrent()
        c.startDevice()
        runCurrent()
        advanceTimeBy(501)
        assertEquals(GitHubFixtures.pending("ABCD-1234", "https://github.com/login/device"), c.poll)
        assertTrue(c.deviceFlow)
    }

    @Test fun thePollHasNoClientLimit() = runTest {
        val gh = fake()
        val c = controller(gh)
        runCurrent()
        c.startDevice()
        runCurrent()
        // An hour of "pending" (the default answer): still polling, as the web's loop would be.
        advanceTimeBy(3_600_000)
        assertTrue(c.deviceFlow)
        assertTrue(gh.count("poll@$GH_ORIGIN") > 2_000)
    }

    @Test fun aFailedFetchIsPolledAgainAfterTwoSeconds() = runTest {
        val gh = fake()
        val c = controller(gh)
        runCurrent()
        c.startDevice()
        runCurrent()
        gh.polls += SecurityResult.Unavailable(null, GH_ORIGIN)
        gh.polls += SecurityResult.LocalNetworkBlocked
        advanceTimeBy(500 + 2_000 + 2_000 + 1)
        assertEquals(listOf(500L, 2_500L, 4_500L), gh.calls.filter { it.first.startsWith("poll") }.map { it.second })
        assertTrue(c.deviceFlow)
    }

    @Test fun anErrorEndsTheFlowWithItsSentence() = runTest {
        val gh = fake()
        val c = controller(gh)
        runCurrent()
        c.startDevice()
        runCurrent()
        gh.polls += SecurityResult.Ok(GitHubDevicePoll(true, GitHubDeviceStatus.Error, null, null, "gh auth login exited with code 1."), GH_ORIGIN, null)
        advanceTimeBy(10_000)
        assertFalse(c.deviceFlow)
        assertEquals(GitHubDeviceStatus.Error, c.poll!!.status)
        assertEquals("gh auth login exited with code 1.", c.poll!!.error)
        // :1013 the status is read again.
        assertEquals(2, gh.count("status@$GH_ORIGIN"))
    }

    @Test fun anAnswerThatIsNotJsonEndsTheFlowAsTheWebsFailedParseDoes() = runTest {
        val gh = fake()
        val c = controller(gh)
        runCurrent()
        c.startDevice()
        runCurrent()
        gh.polls += SecurityResult.Unavailable(200, GH_ORIGIN)
        advanceTimeBy(10_000)
        assertFalse(c.deviceFlow)
        assertEquals(GitHubCopy.NO_RESPONSE, c.poll!!.error)
        assertEquals(GitHubDeviceStatus.Error, c.poll!!.status)
    }

    @Test fun aJsonRefusalOrIdleEndsTheFlowQuietly() = runTest {
        for (answer in listOf<SecurityResult<GitHubDevicePoll>>(
            SecurityResult.OwnerSignInNeeded(GH_ORIGIN),
            SecurityResult.Refused(409, "x", GH_ORIGIN),
            SecurityResult.Refused(500, "Internal server error.", GH_ORIGIN),
            SecurityResult.Refused(503, "", GH_ORIGIN),
            SecurityResult.Ok(GitHubDevicePoll(false, GitHubDeviceStatus.Idle, null, null, null), GH_ORIGIN, null),
        )) {
            val gh = fake()
            val c = controller(gh)
            runCurrent()
            c.startDevice()
            runCurrent()
            gh.polls += answer
            advanceTimeBy(10_000)
            assertFalse("$answer", c.deviceFlow)
            assertTrue("$answer", c.poll!!.status != GitHubDeviceStatus.Error)
            assertEquals("$answer", 2, gh.count("status@$GH_ORIGIN"))
        }
    }

    @Test fun cancelStopsThePollTellsTheServerAndClearsTheRows() = runTest {
        val gh = fake()
        val c = controller(gh)
        runCurrent()
        c.startDevice()
        runCurrent()
        advanceTimeBy(501)
        assertEquals(1, gh.count("poll@$GH_ORIGIN"))
        c.cancelDevice()
        runCurrent()
        assertFalse(c.deviceFlow)
        assertNull(c.poll)
        assertEquals(1, gh.count("cancel@$GH_ORIGIN"))
        advanceTimeBy(60_000)
        assertEquals(1, gh.count("poll@$GH_ORIGIN"))
    }

    @Test fun aRefusedStartSaysTheServersSentenceOrTheOwnerRefusal() = runTest {
        val gh = fake()
        gh.starts += SecurityResult.Refused(409, "A GitHub login is already in progress. Cancel it first.", GH_ORIGIN)
        gh.starts += SecurityResult.OwnerSignInNeeded(GH_ORIGIN)
        gh.starts += SecurityResult.Unavailable(500, GH_ORIGIN)
        val c = controller(gh)
        runCurrent()
        c.startDevice()
        runCurrent()
        assertEquals("A GitHub login is already in progress. Cancel it first.", c.actionError)
        assertFalse(c.deviceFlow)
        c.startDevice()
        runCurrent()
        assertEquals(GitHubCopy.OWNER_NEEDED, c.actionError)
        c.startDevice()
        runCurrent()
        assertEquals(GitHubCopy.START_FAILED, c.actionError)
        assertFalse(c.busy)
    }

    // ---- the token -------------------------------------------------------------------------------------

    @Test fun aBlankTokenSendsNothing() = runTest {
        val gh = fake()
        val c = controller(gh)
        runCurrent()
        c.editToken("   ")
        assertFalse(c.saveToken())
        assertEquals(0, gh.count("token@$GH_ORIGIN"))
    }

    @Test fun aSavedTokenLeavesTheFieldEmptySaysSoAndReadsTheStatus() = runTest {
        val gh = fake()
        gh.statuses += SecurityResult.Ok(GitHubFixtures.NOT_INSTALLED, GH_ORIGIN, null)
        gh.statuses += SecurityResult.Ok(GitHubFixtures.MANAGED, GH_ORIGIN, null)
        val c = controller(gh)
        runCurrent()
        c.editToken("  ${GitHubFixtures.FAKE_TOKEN}  ")
        assertTrue(c.saveToken())
        assertTrue(c.busy)
        // :1158 one busy flag: nothing else starts meanwhile.
        assertFalse(c.saveToken())
        assertFalse(c.startDevice())
        assertFalse(c.disconnect())
        runCurrent()
        assertTrue(c.tokenSaved)
        assertEquals("", c.tokenInput)
        assertTrue(c.connected)
        assertEquals(1, gh.tokens.size)
        assertEquals("GitHubToken(***)", gh.tokens.single().toString())
        // An edit drops "saved" (:1155).
        c.editToken("g")
        assertFalse(c.tokenSaved)
    }

    @Test fun aRefusedTokenKeepsTheTextAndSaysWhy() = runTest {
        val gh = fake()
        gh.saves += SecurityResult.Refused(400, "That token could not be verified. Check the value and its scopes.", GH_ORIGIN)
        gh.saves += SecurityResult.OwnerSignInNeeded(GH_ORIGIN)
        gh.saves += SecurityResult.Blocked(302, GH_ORIGIN)
        val c = controller(gh)
        runCurrent()
        c.editToken(GitHubFixtures.FAKE_TOKEN)
        c.saveToken()
        runCurrent()
        assertEquals("That token could not be verified. Check the value and its scopes.", c.tokenError)
        assertEquals(GitHubFixtures.FAKE_TOKEN, c.tokenInput)
        assertFalse(c.tokenSaved)
        c.saveToken()
        runCurrent()
        assertEquals(GitHubCopy.OWNER_NEEDED, c.tokenError)
        c.saveToken()
        runCurrent()
        assertEquals(GitHubCopy.blocked(302), c.tokenError)
        // An edit clears the error (:1155).
        c.editToken(GitHubFixtures.FAKE_TOKEN + "x")
        assertNull(c.tokenError)
    }

    @Test fun removeTetherTokenIsSentAtOnceThenTheStatusIsRead() = runTest {
        val gh = fake()
        gh.statuses += SecurityResult.Ok(GitHubFixtures.MANAGED, GH_ORIGIN, null)
        gh.statuses += SecurityResult.Ok(GitHubFixtures.NOT_LOGGED_IN, GH_ORIGIN, null)
        val c = controller(gh)
        runCurrent()
        assertTrue(c.disconnect())
        runCurrent()
        assertFalse(c.connected)
        assertEquals(listOf("status", "logout", "status"), gh.names().map { it.substringBefore('@') })
        gh.logouts += SecurityResult.Refused(403, "This request must come from the Tether console itself.", GH_ORIGIN)
        c.disconnect()
        runCurrent()
        assertEquals("This request must come from the Tether console itself.", c.actionError)
    }

    // ---- lifetime --------------------------------------------------------------------------------------

    @Test fun disposeWipesTheTokenAndEndsThePoll() = runTest {
        val gh = fake()
        val c = controller(gh)
        runCurrent()
        c.startDevice()
        runCurrent()
        c.editToken(GitHubFixtures.FAKE_TOKEN)
        c.dispose()
        assertEquals("", c.tokenInput)
        advanceTimeBy(60_000)
        assertEquals(0, gh.count("poll@$GH_ORIGIN"))
        c.editToken(GitHubFixtures.FAKE_TOKEN)
        assertEquals("", c.tokenInput)
        assertFalse(c.saveToken())
    }

    @Test fun theViewModelFollowsTheSignedInServerAndCredentialAndWipesTheTokenOnAChange() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val gh = FakeGitHub()
        val identities = MutableStateFlow(GitHubIdentity(GH_ORIGIN, 1))
        val vm = GitHubConnectionViewModel(gh, identities.value, identities)
        main.scheduler.runCurrent()
        val first = vm.controller
        assertEquals(GH_ORIGIN, first.origin)
        assertEquals(1, gh.count("status@$GH_ORIGIN"))
        first.editToken(GitHubFixtures.FAKE_TOKEN)
        // The same server and sign-in: the same controller (the token stays, as in the web's mounted section).
        identities.value = GitHubIdentity(GH_ORIGIN, 1)
        main.scheduler.runCurrent()
        assertTrue(vm.controller === first)
        assertEquals(GitHubFixtures.FAKE_TOKEN, first.tokenInput)
        // r2: the same server, ANOTHER credential: disposed (token wiped), a fresh one reads the status again.
        identities.value = GitHubIdentity(GH_ORIGIN, 2)
        main.scheduler.runCurrent()
        assertNotSame(first, vm.controller)
        assertEquals("", first.tokenInput)
        assertEquals(GH_ORIGIN, vm.controller.origin)
        assertEquals(2, gh.count("status@$GH_ORIGIN"))
        // Another server: the same again.
        val second = vm.controller
        second.editToken(GitHubFixtures.FAKE_TOKEN)
        identities.value = GitHubIdentity(GH_OTHER, 2)
        main.scheduler.runCurrent()
        assertNotSame(second, vm.controller)
        assertEquals("", second.tokenInput)
        assertEquals(GH_OTHER, vm.controller.origin)
        assertTrue(gh.names().contains("status@$GH_OTHER"))
        // Signed out: nothing is held for nobody.
        vm.controller.editToken(GitHubFixtures.FAKE_TOKEN)
        val third = vm.controller
        identities.value = GitHubIdentity(null, 3)
        main.scheduler.runCurrent()
        assertEquals("", third.tokenInput)
        assertNull(vm.controller.origin)
    }

    /**
     * r2 (security review): a poll answer and a save answer still in flight when the controller is
     * disposed land nowhere: no "saved", no flow ended, no status read, no state written.
     */
    @Test fun answersHeldUntilAfterDisposeLandNowhere() = runTest {
        val gh = fake()
        val c = controller(gh)
        runCurrent()
        c.startDevice()
        runCurrent()
        val hold = CompletableDeferred<Unit>()
        gh.gate = hold
        // Replies that WOULD end the flow, read the status again and say "saved".
        gh.polls += SecurityResult.Ok(GitHubDevicePoll(true, GitHubDeviceStatus.Complete, "ABCD-1234", "https://github.com/login/device", null), GH_ORIGIN, null)
        gh.saves += SecurityResult.Ok(GitHubTokenSaved("octocat", listOf("repo")), GH_ORIGIN, null)
        advanceTimeBy(501)
        c.editToken(GitHubFixtures.FAKE_TOKEN)
        assertTrue(c.saveToken())
        runCurrent()
        assertEquals(1, gh.count("poll@$GH_ORIGIN"))
        assertEquals(1, gh.count("token@$GH_ORIGIN"))
        gh.gate = null
        c.dispose()
        hold.complete(Unit)
        advanceTimeBy(60_000)
        assertFalse(c.tokenSaved)
        assertEquals("", c.tokenInput)
        assertEquals(GitHubDevicePoll.Started, c.poll)
        assertEquals(1, gh.count("status@$GH_ORIGIN"))
        assertEquals(1, gh.count("poll@$GH_ORIGIN"))
    }

    /** r2: the same through the ViewModel, on a credential swap on the same server: the old controller's answers land nowhere, the new one is untouched. */
    @Test fun answersHeldAcrossASignInSwapLandNowhere() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val gh = FakeGitHub()
        val identities = MutableStateFlow(GitHubIdentity(GH_ORIGIN, 1))
        val vm = GitHubConnectionViewModel(gh, identities.value, identities)
        main.scheduler.runCurrent()
        val old = vm.controller
        old.startDevice()
        main.scheduler.runCurrent()
        val hold = CompletableDeferred<Unit>()
        gh.gate = hold
        gh.polls += SecurityResult.Ok(GitHubDevicePoll(true, GitHubDeviceStatus.Complete, null, null, null), GH_ORIGIN, null)
        gh.saves += SecurityResult.Ok(GitHubTokenSaved("octocat", listOf("repo")), GH_ORIGIN, null)
        main.scheduler.advanceTimeBy(501)
        main.scheduler.runCurrent()
        old.editToken(GitHubFixtures.FAKE_TOKEN)
        old.saveToken()
        main.scheduler.runCurrent()
        gh.gate = null
        identities.value = GitHubIdentity(GH_ORIGIN, 2)
        main.scheduler.runCurrent()
        val fresh = vm.controller
        assertNotSame(old, fresh)
        val statusesBefore = gh.count("status@$GH_ORIGIN")
        hold.complete(Unit)
        main.scheduler.advanceTimeBy(60_000)
        main.scheduler.runCurrent()
        assertFalse(old.tokenSaved)
        assertEquals("", old.tokenInput)
        assertEquals(GitHubDevicePoll.Started, old.poll)
        assertFalse(fresh.tokenSaved)
        assertFalse(fresh.deviceFlow)
        assertNull(fresh.poll)
        assertEquals(statusesBefore, gh.count("status@$GH_ORIGIN"))
        assertEquals(1, gh.count("poll@$GH_ORIGIN"))
    }

    /** r2 (security review): the goldens' seed never prints a typed token. */
    @Test fun theSeedNeverPrintsItsToken() {
        val seed = GitHubSeed(tokenInput = GitHubFixtures.FAKE_TOKEN, tokenError = "x")
        assertFalse(seed.toString().contains("FAKE"))
        assertTrue(seed.toString().contains("tokenInput=***"))
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }
}
