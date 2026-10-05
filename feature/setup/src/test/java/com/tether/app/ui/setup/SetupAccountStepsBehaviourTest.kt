package com.tether.app.ui.setup

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.HttpSetupApi
import com.tether.app.ui.settings.LoginLinkOpener
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeMode
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * T10.6 (ta-pqui): the GitHub and Claude accounts stations on the JVM, the real screen over the real
 * [HttpSetupApi] against the fake first-run server ([SetupServer], shaped from setup-server.mjs); no real
 * server. Skip, the device flow (code, open, copy, settle, cancel), the token, add / log in / code / cancel
 * an account, a host with no Claude harness, and the 403 once setup is completed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SetupAccountStepsBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private val fake = SetupServer()
    private val web = MockWebServer().apply {
        dispatcher = fake
        start()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opened = CopyOnWriteArrayList<String>()
    private lateinit var model: SetupWizardModel

    @After fun tearDown() {
        scope.cancel()
        web.shutdown()
    }

    /** The wizard on the station [step] (4 GitHub, 5 Claude accounts), the way the operator gets there. */
    private fun launchAt(step: Int, claudeTicked: Boolean = true) {
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        model = SetupWizardModel(HttpSetupApi(http, web.url("/")), scope, accountTiming = SetupAccountTiming(initialMs = 20, pollMs = 40, retryMs = 40))
        rule.setContent {
            TetherTheme(ThemeMode.Light) {
                CompositionLocalProvider(
                    LocalReducedMotion provides true,
                    LocalSetupLinkOpener provides LoginLinkOpener { link -> opened += link.url; true },
                ) { SetupWizardScreen(model) }
            }
        }
        model.load()
        waitFor { model.state != null && model.detected.isNotEmpty() }
        if (!claudeTicked) model.toggleEngine("claude")
        model.begin()
        repeat(step - 1) { model.next() }
        rule.waitForIdle()
    }

    private fun waitFor(timeout: Long = 10_000, condition: () -> Boolean) = rule.waitUntil(timeout) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    private fun tag(t: String): SemanticsNodeInteraction = rule.onNodeWithTag(t, useUnmergedTree = true)
    private fun exists(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun shows(text: String) = rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tap(t: String) {
        tag(t).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun type(t: String, text: String) {
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(t)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }
        rule.waitForIdle()
    }

    private val devicePending = """{"ok":true,"status":"pending","deviceCode":"ABCD-1234","verificationUri":"https://github.com/login/device","error":null}"""

    // ---- GitHub -------------------------------------------------------------------------------

    @Test fun theGitHubStationIsSkippableAndMakesNoWriteUntilAskedTo() {
        launchAt(4)
        waitFor { shows("No GitHub login detected on this host.") && exists(SetupTags.GitHubDevice) }
        assertTrue(shows("Optional — skip and set up later from Settings if you prefer."))
        tag(SetupTags.Continue).assertIsEnabled()
        tap(SetupTags.Continue)
        waitFor { exists(SetupTags.ClaudeAccounts) }
        assertTrue(fake.seen.none { it.path.startsWith("/api/setup/github/") && it.method != "GET" })
    }

    @Test fun aHostAlreadyLoggedInShowsItAndOffersNoSetup() {
        fake.githubStatus = """{"ghInstalled":true,"ghVersion":"2.60.0","authenticated":true,"account":"octo","scopes":["repo","gist"],"managedToken":true}"""
        launchAt(4)
        waitFor { shows("Using existing GitHub login:") }
        assertTrue(shows("@octo"))
        assertTrue(shows("scopes: repo, gist"))
        assertTrue(shows("managed token"))
        assertTrue(shows("gh 2.60.0"))
        assertTrue(shows("no setup needed."))
        assertTrue(!exists(SetupTags.GitHubDevice))
    }

    @Test fun withNoGhTheStationSaysSoAndStillOffersAToken() {
        fake.githubStatus = """{"ghInstalled":false,"ghVersion":null,"authenticated":false,"account":null,"scopes":[],"managedToken":false}"""
        launchAt(4)
        waitFor { shows("CLI is not installed.") }
        assertTrue(exists(SetupTags.GitHubToken))
    }

    @Test fun theDeviceFlowShowsTheCodeOpensTheVerificationPageCopiesAndSettles() {
        fake.githubPolls += devicePending
        launchAt(4)
        waitFor { exists(SetupTags.GitHubDevice) }
        tap(SetupTags.GitHubDevice)
        waitFor { shows("ABCD-1234") }
        val start = fake.calls("/api/setup/github/login").single()
        assertEquals("POST", start.method)
        assertTrue(start.contentType!!.startsWith("application/json"))
        assertNull(start.origin)
        assertTrue(shows("Waiting for you to authorize…"))

        tap(SetupTags.GitHubOpen)
        assertEquals(listOf("https://github.com/login/device"), opened.toList())

        tap(SetupTags.GitHubCopy)
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        waitFor { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "ABCD-1234" }

        // Authorized in the browser: the poll settles and the station reads the new login.
        fake.githubStatus = """{"ghInstalled":true,"ghVersion":"2.60.0","authenticated":true,"account":"octo","scopes":["repo"],"managedToken":false}"""
        fake.githubPolls.clear()
        fake.githubPolls += """{"ok":true,"status":"complete","deviceCode":"ABCD-1234","verificationUri":null,"error":null}"""
        waitFor { shows("Using existing GitHub login:") }
        assertTrue(!exists(SetupTags.GitHubCode))
    }

    @Test fun cancellingTheDeviceFlowTellsTheServerAndClearsTheCode() {
        fake.githubPolls += devicePending
        launchAt(4)
        waitFor { exists(SetupTags.GitHubDevice) }
        tap(SetupTags.GitHubDevice)
        waitFor { exists(SetupTags.GitHubCancel) }
        tap(SetupTags.GitHubCancel)
        waitFor { fake.calls("/api/setup/github/login/cancel").isNotEmpty() }
        assertEquals("POST", fake.calls("/api/setup/github/login/cancel").single().method)
        waitFor { !exists(SetupTags.GitHubCancel) }
        assertTrue(!shows("ABCD-1234"))
    }

    @Test fun aLoginThatCannotStartShowsTheServersWords() {
        fake.githubLoginStatus = 500
        launchAt(4)
        waitFor { exists(SetupTags.GitHubDevice) }
        tap(SetupTags.GitHubDevice)
        waitFor { shows("gh is not installed.") }
        assertTrue(!exists(SetupTags.GitHubCancel))
    }

    @Test fun aStatusFailureOffersTryAgainAndStillLetsTheOperatorContinue() {
        fake.githubStatus = "<html>gateway</html>"
        launchAt(4)
        waitFor { shows("Could not reach the GitHub status service.") }
        tag(SetupTags.Continue).assertIsEnabled()
        fake.githubStatus = """{"ghInstalled":true,"ghVersion":null,"authenticated":false,"account":null,"scopes":[],"managedToken":false}"""
        tap(SetupTags.GitHubRetry)
        waitFor { shows("No GitHub login detected on this host.") }
    }

    @Test fun aPersonalAccessTokenIsVerifiedAndSavedThroughTheJsonWriteAndKeptOnFailure() {
        fake.githubTokenReply = { SetupServer.json(400, """{"ok":false,"error":"Bad credentials"}""") }
        launchAt(4)
        waitFor { exists(SetupTags.GitHubToken) }
        tap(SetupTags.GitHubToken)
        waitFor { exists(SetupTags.GitHubTokenSave) }
        tag(SetupTags.GitHubTokenSave).assertIsNotEnabled()
        type(SetupTags.GitHubTokenField, "ghp_secret")
        tag(SetupTags.GitHubTokenSave).assertIsEnabled()
        tap(SetupTags.GitHubTokenSave)
        waitFor { shows("Bad credentials") }
        assertTrue(!model.github.tokenError.contains("ghp_"))
        assertEquals("ghp_secret", model.github.tokenInput)

        fake.githubTokenReply = { SetupServer.json(200, """{"ok":true,"account":"octo","scopes":["repo"]}""") }
        tap(SetupTags.GitHubTokenSave)
        waitFor { shows("Token verified and saved.") }
        assertEquals("", model.github.tokenInput)
        val saves = fake.calls("/api/setup/github/token")
        assertEquals(2, saves.size)
        saves.forEach {
            assertEquals("""{"token":"ghp_secret"}""", it.body)
            assertTrue(it.contentType!!.startsWith("application/json"))
            assertNull(it.origin)
            assertNull(it.cookie)
        }
    }

    // ---- Claude accounts ----------------------------------------------------------------------

    @Test fun theClaudeStationListsAccountsAndIsSkippable() {
        fake.claudeAccounts += "default" to "Default"
        fake.claudeAccounts += "claude-work" to "work"
        fake.claudeLoggedIn["default"] = "op@example.com"
        launchAt(5)
        waitFor { shows("Logged in as op@example.com") }
        assertTrue(shows("Default"))
        assertTrue(exists(SetupTags.claudeLogin("claude-work")))
        assertTrue(!exists(SetupTags.claudeLogin("default")))
        assertTrue(shows("Optional — skip and add accounts later from Settings if you prefer."))
        tag(SetupTags.Continue).assertIsEnabled()
        tap(SetupTags.Continue)
        waitFor { shows("Review and switch on.") }
    }

    @Test fun addingAnAccountGoesStraightToItsLoginThenTheCodeSucceedsIt() {
        launchAt(5)
        waitFor { shows("No additional accounts configured yet.") }
        tag(SetupTags.ClaudeAdd).assertIsNotEnabled()
        type(SetupTags.ClaudeNickname, "work")
        tag(SetupTags.ClaudeAdd).assertIsEnabled()
        fake.claudePolls += """{"ok":true,"status":"awaiting-code","url":"https://claude.ai/oauth/authorize?x=1"}"""
        tap(SetupTags.ClaudeAdd)
        waitFor { exists(SetupTags.ClaudeCode) && exists(SetupTags.ClaudeOpen) }
        assertTrue(shows("work"))
        val add = fake.calls("/api/setup/claude-accounts").single { it.method == "POST" }
        assertEquals("""{"nickname":"work"}""", add.body)
        assertTrue(add.contentType!!.startsWith("application/json"))
        assertEquals("POST", fake.calls("/api/setup/claude-accounts/claude-work/login").single().method)

        tap(SetupTags.ClaudeOpen)
        assertEquals(listOf("https://claude.ai/oauth/authorize?x=1"), opened.toList())

        tag(SetupTags.ClaudeSubmit).assertIsNotEnabled()
        type(SetupTags.ClaudeCode, "abc#def")
        tag(SetupTags.ClaudeSubmit).assertIsEnabled()
        tap(SetupTags.ClaudeSubmit)
        waitFor { fake.calls("/api/setup/claude-accounts/claude-work/login/code").isNotEmpty() }
        val code = fake.calls("/api/setup/claude-accounts/claude-work/login/code").single()
        assertEquals("""{"code":"abc#def"}""", code.body)
        assertTrue(code.contentType!!.startsWith("application/json"))
        waitFor { model.claude.codeInput.isEmpty() }

        fake.claudeLoggedIn["claude-work"] = "op@example.com"
        fake.claudePolls.clear()
        fake.claudePolls += """{"ok":true,"status":"success"}"""
        waitFor { shows("Logged in — this account is ready to use.") }
        waitFor { shows("Logged in as op@example.com") }
        assertTrue(fake.seen.none { it.body.contains("abc#def") && !it.path.endsWith("/login/code") })
    }

    @Test fun aRefusedCodeShowsTheServersWordsAndKeepsTheCodeForAnotherTry() {
        fake.claudeAccounts += "claude-work" to "work"
        fake.claudeCodeReply = { SetupServer.json(409, """{"ok":false,"error":"That code has expired."}""") }
        launchAt(5)
        waitFor { exists(SetupTags.claudeLogin("claude-work")) }
        tap(SetupTags.claudeLogin("claude-work"))
        waitFor { exists(SetupTags.ClaudeCode) }
        type(SetupTags.ClaudeCode, "abc")
        tap(SetupTags.ClaudeSubmit)
        // The refusal replaces the waiting panel only through the poll; the line is the page's `loginError`.
        waitFor { model.claude.loginError == "That code has expired." }
        assertEquals("abc", model.claude.codeInput)
    }

    @Test fun cancellingALoginSendsADeleteAndClosesThePanel() {
        fake.claudeAccounts += "claude-work" to "work"
        launchAt(5)
        waitFor { exists(SetupTags.claudeLogin("claude-work")) }
        tap(SetupTags.claudeLogin("claude-work"))
        waitFor { exists(SetupTags.ClaudeCancel) }
        tap(SetupTags.ClaudeCancel)
        waitFor { fake.calls("/api/setup/claude-accounts/claude-work/login").any { it.method == "DELETE" } }
        val delete = fake.calls("/api/setup/claude-accounts/claude-work/login").single { it.method == "DELETE" }
        assertTrue(delete.contentType!!.startsWith("application/json"))
        assertNull(delete.origin)
        waitFor { !exists(SetupTags.ClaudeCode) }
        // The account can be logged in again (a cancel during the start leaves nothing behind).
        waitFor { !model.claude.loginBusy }
        assertNull(model.claude.activeLoginId)
        tag(SetupTags.claudeLogin("claude-work")).assertIsEnabled()
    }

    @Test fun aLoginAlreadyRunningOnTheServerIsTheInProgressSentence() {
        fake.claudeAccounts += "claude-work" to "work"
        fake.claudeLoginsRunning += "claude-work"
        launchAt(5)
        waitFor { exists(SetupTags.claudeLogin("claude-work")) }
        tap(SetupTags.claudeLogin("claude-work"))
        waitFor { shows("A login is already in progress for this account.") }
    }

    @Test fun withNoClaudeHarnessTheStationExplainsAndMakesNoRequest() {
        launchAt(5, claudeTicked = false)
        waitFor { shows("This step only applies when Claude Code is one of your harnesses.") }
        assertTrue(shows("Optional — you can enable Claude Code and add accounts anytime from Settings."))
        assertTrue(fake.seen.none { it.path.startsWith("/api/setup/claude-accounts") })
        tag(SetupTags.Continue).assertIsEnabled()
    }

    @Test fun onceSetupIsCompletedTheStationShowsThe403PointerAndStillLetsTheOperatorContinue() {
        fake.accountRoutesClosed = true
        launchAt(5)
        waitFor { shows("Setup already completed — restart Tether and use the Settings dialog.") }
        assertTrue(exists(SetupTags.ClaudeRetry))
        tag(SetupTags.Continue).assertIsEnabled()
        tap(SetupTags.Continue)
        waitFor { shows("Review and switch on.") }
    }

    @Test fun nothingOnAStationOutlivesLeavingIt() {
        launchAt(4)
        waitFor { exists(SetupTags.GitHubToken) }
        tap(SetupTags.GitHubToken)
        type(SetupTags.GitHubTokenField, "ghp_secret")
        tap(SetupTags.Continue)
        waitFor { exists(SetupTags.ClaudeAccounts) }
        tap(SetupTags.Back)
        waitFor { exists(SetupTags.GitHubToken) }
        assertEquals("", model.github.tokenInput)
        assertNull(model.github.method)
    }
}
