package com.tether.app.ui.setup

import com.tether.app.client.ClaudeAccountProfile
import com.tether.app.client.ClaudeLoginPoll
import com.tether.app.client.ClaudeLoginStarted
import com.tether.app.client.GitHubStatus
import com.tether.app.client.SetupCall
import com.tether.app.client.SetupClaudeStatus
import com.tether.app.client.SetupCopy
import com.tether.app.client.SetupGitHubPoll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T10.6 (ta-pqui): the GitHub and Claude accounts stations' rules (page.tsx StepGitHub / StepClaudeAccounts), no screen. */
class SetupAccountModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fast = SetupAccountTiming(initialMs = 1, pollMs = 5, retryMs = 5)
    private val api = FakeSetupApi()

    @After fun tearDown() = scope.cancel()

    private fun eventually(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out: $what" }
            Thread.sleep(2)
        }
    }

    private fun github() = SetupGitHubModel(api, scope, fast)
    private fun claude(available: Boolean = true) = SetupClaudeModel(api, scope, { available }, fast)

    private fun poll(status: String, code: String? = null, error: String? = null) =
        SetupCall.Ok(SetupGitHubPoll(true, status, code, if (code != null) "https://github.com/login/device" else null, error))

    // ---- GitHub -------------------------------------------------------------------------------

    @Test fun enteringReadsTheHostLoginOnceAndARotationDoesNotStartOver() {
        val m = github()
        m.enter()
        eventually("status") { m.status != null }
        assertFalse(m.loading)
        assertFalse(m.connected)
        m.enter()
        assertEquals(false, m.loading)
    }

    @Test fun anExistingLoginShowsConnectedAndAStatusFailureIsThePagesSentence() {
        api.githubStatus = SetupCall.Ok(GitHubStatus(true, "2.60.0", true, "octo", listOf("repo"), false))
        val m = github()
        runBlocking { m.loadStatusNow() }
        assertTrue(m.connected)
        api.githubStatus = SetupCall.Failed(null, "")
        runBlocking { m.loadStatusNow() }
        assertEquals(SetupCopy.GITHUB_STATUS_FAILED, m.error)
    }

    @Test fun theDeviceFlowShowsTheCodeThenSettlesAndRefreshesTheStatus() {
        api.githubPolls += poll("pending")
        api.githubPolls += poll("pending", code = "ABCD-1234")
        val m = github()
        runBlocking { m.loadStatusNow() }
        runBlocking { m.startDeviceNow() }
        assertEquals(GitHubMethod.Device, m.method)
        eventually("code") { m.poll?.deviceCode == "ABCD-1234" }
        api.githubStatus = SetupCall.Ok(GitHubStatus(true, "2.60.0", true, "octo", emptyList(), false))
        api.githubPolls += poll("complete", code = "ABCD-1234")
        eventually("settled") { m.method == null && m.connected }
        assertEquals("complete", m.poll?.status)
    }

    @Test fun aPollThatDidNotAnswerIsRetriedNotShownAsAFailure() {
        api.githubPolls += SetupCall.Failed(null, "down")
        api.githubPolls += poll("pending", code = "WXYZ-0000")
        val m = github()
        runBlocking { m.startDeviceNow() }
        eventually("code after retry") { m.poll?.deviceCode == "WXYZ-0000" }
        assertEquals(GitHubMethod.Device, m.method)
    }

    @Test fun aLoginThatCannotStartSaysSoAndStartsNoPoll() {
        api.githubStart = SetupCall.Failed(500, "gh is not installed.")
        val m = github()
        runBlocking { m.startDeviceNow() }
        assertEquals("gh is not installed.", m.error)
        assertNull(m.method)
        assertNull(m.poll)
    }

    @Test fun cancelStopsTheWatchTellsTheServerAndForgetsTheFlow() {
        api.githubPolls += poll("pending", code = "ABCD-1234")
        val m = github()
        runBlocking { m.startDeviceNow() }
        eventually("code") { m.poll?.deviceCode != null }
        m.cancelDevice()
        eventually("cancelled on the server") { api.githubCancels == 1 }
        assertNull(m.method)
        assertNull(m.poll)
    }

    @Test fun theTokenIsClearedOnlyOnceTheServerHasItAndNeverShownInAnError() {
        val m = github()
        m.chooseToken()
        assertEquals(GitHubMethod.Token, m.method)
        m.typeToken("  ghp_secret  ")
        api.githubToken = SetupCall.Failed(400, "Bad credentials")
        runBlocking { m.saveTokenNow() }
        // Trimmed on the way out; kept in the field for another try; the error is the server's words.
        assertEquals(listOf("ghp_secret"), api.githubTokens)
        assertEquals("  ghp_secret  ", m.tokenInput)
        assertEquals("Bad credentials", m.tokenError)
        assertFalse(m.tokenError.contains("ghp_"))
        // Typing again clears the error.
        m.typeToken("ghp_secret2")
        assertEquals("", m.tokenError)

        api.githubToken = SetupCall.Ok(Unit)
        api.githubStatus = SetupCall.Ok(GitHubStatus(true, null, true, "octo", listOf("repo"), true))
        runBlocking { m.saveTokenNow() }
        assertEquals("", m.tokenInput)
        assertTrue(m.tokenSaved)
        assertTrue(m.connected)
    }

    @Test fun anEmptyTokenIsNotSent() {
        val m = github()
        m.chooseToken()
        m.typeToken("   ")
        m.saveToken()
        Thread.sleep(30)
        assertTrue(api.githubTokens.isEmpty())
    }

    @Test fun leavingTheStationForgetsEverythingOnIt() {
        val m = github()
        runBlocking { m.loadStatusNow() }
        m.chooseToken()
        m.typeToken("ghp_secret")
        m.leave()
        assertEquals("", m.tokenInput)
        assertNull(m.method)
        assertNull(m.status)
    }

    // ---- Claude accounts ----------------------------------------------------------------------

    @Test fun withNoClaudeHarnessTheStationMakesNoRequestAtAll() {
        val m = claude(available = false)
        m.enter()
        Thread.sleep(30)
        assertEquals(0, api.claudeListCalls)
        assertFalse(m.available)
    }

    @Test fun accountsAreListedWithEachOnesLoginState() {
        api.claudeList = SetupCall.Ok(listOf(ClaudeAccountProfile("default", "Default", imported = true), ClaudeAccountProfile("claude-work", "work", imported = false)))
        api.claudeStatuses = mapOf("default" to SetupClaudeStatus(true, "op@example.com"))
        val m = claude()
        runBlocking { m.loadAccountsNow() }
        assertEquals(listOf("default", "claude-work"), m.accounts.map { it.id })
        assertEquals(true, m.statuses["default"]?.loggedIn)
        assertEquals("op@example.com", m.statuses["default"]?.email)
        assertEquals(false, m.statuses["claude-work"]?.loggedIn)
    }

    @Test fun aListThatClosedWithThe403CarriesTheSettingsPointer() {
        api.claudeList = SetupCall.Failed(403, "Setup already completed — restart Tether and use the Settings dialog.")
        val m = claude()
        runBlocking { m.loadAccountsNow() }
        assertEquals("Setup already completed — restart Tether and use the Settings dialog.", m.listError)
    }

    @Test fun addingAnAccountClearsTheNicknameRefreshesAndGoesStraightToItsLogin() {
        api.claudeAdd = { SetupCall.Ok("claude-work") }
        api.claudeStart = SetupCall.Ok(ClaudeLoginStarted("pending-url", "https://claude.ai/oauth/authorize?x=1"))
        val m = claude()
        m.typeNickname("  work  ")
        runBlocking { m.addAccountNow() }
        assertEquals(listOf("work"), api.claudeAdded)
        assertEquals("", m.nickname)
        eventually("login started") { m.activeLoginId == "claude-work" && m.loginUrl != null }
        assertEquals(listOf("claude-work"), api.claudeStarted)
        assertEquals(ClaudeLoginStage.PendingUrl, m.loginStage)
    }

    @Test fun anAddThatFailsShowsTheServersWordsAndKeepsTheNickname() {
        api.claudeAdd = { SetupCall.Failed(400, "That nickname is taken.") }
        val m = claude()
        m.typeNickname("work")
        runBlocking { m.addAccountNow() }
        assertEquals("That nickname is taken.", m.addError)
        assertEquals("work", m.nickname)
        assertNull(m.activeLoginId)
        m.typeNickname("work2")
        assertEquals("", m.addError)
    }

    @Test fun aLoginAlreadyRunningIsThe409Sentence() {
        api.claudeStart = SetupCall.Failed(409, "already-in-progress")
        val m = claude()
        runBlocking { m.startLoginNow("claude-work") }
        assertEquals(ClaudeLoginStage.Error, m.loginStage)
        assertEquals(SetupCopy.CLAUDE_LOGIN_BUSY, m.loginError)
    }

    @Test fun theCodeIsClearedOnlyOnceTheServerAcceptsItThenTheLoginSucceeds() {
        api.claudeList = SetupCall.Ok(listOf(ClaudeAccountProfile("claude-work", "work", false)))
        val m = claude()
        runBlocking { m.startLoginNow("claude-work") }
        m.typeCode("  abc#def  ")
        api.claudeCode = SetupCall.Failed(409, "That code was not accepted.")
        runBlocking { m.submitCodeNow() }
        assertEquals(listOf("claude-work" to "abc#def"), api.claudeCodes)
        assertEquals("  abc#def  ", m.codeInput)
        assertEquals("That code was not accepted.", m.loginError)
        assertFalse(m.loginError.contains("abc"))

        api.claudeCode = SetupCall.Ok(Unit)
        api.claudeStatuses = mapOf("claude-work" to SetupClaudeStatus(true, "op@example.com"))
        api.claudePolls += SetupCall.Ok(ClaudeLoginPoll(true, "success", null, null))
        runBlocking { m.submitCodeNow() }
        assertEquals("", m.codeInput)
        eventually("success") { m.loginStage == ClaudeLoginStage.Success && m.statuses["claude-work"]?.loggedIn == true }
    }

    @Test fun aPollThatIsNotOkIsAFailedLoginWithTheServersWordsOrTheDefault() {
        api.claudePolls += SetupCall.Ok(ClaudeLoginPoll(false, "error", null, "The CLI exited."))
        val m = claude()
        runBlocking { m.startLoginNow("claude-work") }
        eventually("error") { m.loginStage == ClaudeLoginStage.Error }
        assertEquals("The CLI exited.", m.loginError)

        api.claudePolls.clear()
        api.claudePolls += SetupCall.Ok(ClaudeLoginPoll(false, "error", null, null))
        runBlocking { m.startLoginNow("claude-work") }
        eventually("default error") { m.loginStage == ClaudeLoginStage.Error && m.loginError == "The login failed." }
    }

    @Test fun theUrlAppearsWhenThePollFindsItAndAPollThatDidNotAnswerIsRetried() {
        api.claudePolls += SetupCall.Failed(null, "down")
        api.claudePolls += SetupCall.Ok(ClaudeLoginPoll(true, "awaiting-code", "https://claude.ai/oauth/authorize?x=1", null))
        val m = claude()
        runBlocking { m.startLoginNow("claude-work") }
        eventually("url") { m.loginUrl != null }
        assertEquals(ClaudeLoginStage.AwaitingCode, m.loginStage)
    }

    @Test fun cancelTellsTheServerAndForgetsTheLoginAndTheCode() {
        val m = claude()
        runBlocking { m.startLoginNow("claude-work") }
        m.typeCode("abc")
        m.cancelLogin()
        eventually("cancel sent") { api.claudeCancels == listOf("claude-work") }
        assertNull(m.activeLoginId)
        assertEquals(ClaudeLoginStage.Idle, m.loginStage)
        assertEquals("", m.codeInput)
    }

    @Test fun anAccountWithALoginOnScreenCannotBeStartedAgainWhileItIsThere() {
        val m = claude()
        runBlocking { m.startLoginNow("claude-work") }
        m.startLogin("claude-work")
        Thread.sleep(30)
        assertEquals(listOf("claude-work"), api.claudeStarted)
    }

    @Test fun leavingTheClaudeStationForgetsEverythingOnIt() {
        val m = claude()
        runBlocking { m.startLoginNow("claude-work") }
        m.typeCode("abc")
        m.typeNickname("work")
        m.leave()
        assertEquals("", m.codeInput)
        assertEquals("", m.nickname)
        assertNull(m.activeLoginId)
        assertTrue(m.accounts.isEmpty())
    }
}
