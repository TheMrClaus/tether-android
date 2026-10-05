package com.tether.app.ui.setup

import com.tether.app.client.HttpSetupApi
import com.tether.app.client.SetupCall
import com.tether.app.client.SetupCopy
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T10.6 (ta-pqui): the GitHub and Claude accounts routes of [HttpSetupApi] against a fake first-run server
 * shaped from setup-server.mjs :857-1020 ([SetupServer]); no real server. Request shapes (every POST JSON,
 * bodiless ones too; DELETE JSON-typed; no Origin / cookie / credential), the replies as page.tsx reads them,
 * polling, cancel, the errors, and the 403 once setup is completed.
 */
class SetupAccountsApiTest {
    private val fake = SetupServer()
    private val web = MockWebServer().apply {
        dispatcher = fake
        start()
    }
    private val api = HttpSetupApi(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(), web.url("/"))

    @After fun tearDown() = web.shutdown()

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    private fun assertJsonNoCredential(path: String, method: String) {
        val seen = fake.calls(path).first { it.method == method }
        assertTrue("$method $path is JSON", seen.contentType!!.startsWith("application/json"))
        assertNull(seen.origin)
        assertNull(seen.cookie)
        assertNull(seen.authorization)
    }

    // ---- GitHub -------------------------------------------------------------------------------

    @Test fun githubStatusIsReadAsThePageReadsIt() {
        fake.githubStatus = """{"ghInstalled":true,"ghVersion":"2.60.0","authenticated":true,"account":"octo","scopes":["repo","gist"],"managedToken":true}"""
        val status = (run { api.githubStatus() } as SetupCall.Ok).value
        assertTrue(status.authenticated)
        assertEquals("octo", status.account)
        assertEquals(listOf("repo", "gist"), status.scopes)
        assertTrue(status.managedToken)
        assertEquals("2.60.0", status.ghVersion)
        val seen = fake.calls("/api/setup/github/status").single()
        assertEquals("GET", seen.method)
        assertNull(seen.cookie)
        assertNull(seen.origin)
    }

    @Test fun githubStatusThatDoesNotAnswerIsThePagesSentence() {
        fake.githubStatus = "<html>gateway</html>"
        assertEquals(SetupCopy.GITHUB_STATUS_FAILED, (run { api.githubStatus() } as SetupCall.Failed).message)
    }

    @Test fun theDeviceFlowStartsPollsAndCancelsWithJsonWrites() {
        assertTrue(run { api.githubLoginStart() } is SetupCall.Ok)
        assertJsonNoCredential("/api/setup/github/login", "POST")
        assertEquals("", fake.calls("/api/setup/github/login").single().body)

        fake.githubPolls += """{"ok":true,"status":"pending","deviceCode":"ABCD-1234","verificationUri":"https://github.com/login/device","error":null}"""
        val poll = (run { api.githubLoginPoll() } as SetupCall.Ok).value
        assertEquals("pending", poll.status)
        assertEquals("ABCD-1234", poll.deviceCode)
        assertEquals("https://github.com/login/device", poll.verificationUri)

        run { api.githubLoginCancel() }
        assertJsonNoCredential("/api/setup/github/login/cancel", "POST")
    }

    @Test fun aPollWithNoBodyIsTheNoResponseError() {
        fake.githubPolls += "not json"
        val poll = (run { api.githubLoginPoll() } as SetupCall.Ok).value
        assertFalse(poll.ok)
        assertEquals("error", poll.status)
        assertEquals("no response", poll.error)
    }

    @Test fun aLoginThatCannotStartCarriesTheServersWords() {
        fake.githubLoginStatus = 500
        val failed = run { api.githubLoginStart() } as SetupCall.Failed
        assertEquals("gh is not installed.", failed.message)
    }

    @Test fun theTokenTravelsOnlyInTheJsonBodyAndAFailureNeverEchoesIt() {
        assertTrue(run { api.githubSaveToken("ghp_secret123") } is SetupCall.Ok)
        val seen = fake.calls("/api/setup/github/token").single()
        assertEquals("""{"token":"ghp_secret123"}""", seen.body)
        assertJsonNoCredential("/api/setup/github/token", "POST")
        // Nowhere else: not in the URL, not in a header.
        assertFalse(seen.target.contains("ghp_"))

        fake.githubTokenReply = { SetupServer.json(400, """{"ok":false,"error":"Bad credentials"}""") }
        val failed = run { api.githubSaveToken("ghp_secret123") } as SetupCall.Failed
        assertEquals("Bad credentials", failed.message)
        assertFalse(failed.message.contains("ghp_"))
        fake.githubTokenReply = { SetupServer.json(400, "{}") }
        assertEquals(SetupCopy.GITHUB_TOKEN_FAILED, (run { api.githubSaveToken("x") } as SetupCall.Failed).message)
    }

    // ---- Claude accounts ----------------------------------------------------------------------

    @Test fun accountsAreListedAndEachOnesStatusIsRead() {
        fake.claudeAccounts += "claude-work" to "work"
        fake.claudeLoggedIn["claude-work"] = "op@example.com"
        val list = (run { api.claudeAccounts() } as SetupCall.Ok).value
        assertEquals(listOf("claude-work"), list.map { it.id })
        assertEquals("work", list.single().label)
        val status = run { api.claudeAccountStatus("claude-work") }
        assertTrue(status.loggedIn)
        assertEquals("op@example.com", status.email)
        assertFalse(run { api.claudeAccountStatus("claude-other") }.loggedIn)
        assertEquals("GET", fake.calls("/api/setup/claude-accounts").single().method)
    }

    @Test fun addingAnAccountPostsTheNicknameAndReadsTheNewId() {
        val id = (run { api.claudeAccountAdd("work") } as SetupCall.Ok).value
        assertEquals("claude-work", id)
        val seen = fake.calls("/api/setup/claude-accounts").single { it.method == "POST" }
        assertEquals("""{"nickname":"work"}""", seen.body)
        assertJsonNoCredential("/api/setup/claude-accounts", "POST")
        val failed = run { api.claudeAccountAdd("  ") } as SetupCall.Failed
        assertEquals("Give the account a nickname.", failed.message)
    }

    @Test fun theLoginStartsPollsTakesTheCodeAndCancelsWithADelete() {
        val started = (run { api.claudeLoginStart("claude-work") } as SetupCall.Ok).value
        assertEquals("pending-url", started.status)
        assertNull(started.url)
        assertJsonNoCredential("/api/setup/claude-accounts/claude-work/login", "POST")

        fake.claudePolls += """{"ok":true,"status":"awaiting-code","url":"https://claude.ai/oauth/authorize?x=1"}"""
        val poll = (run { api.claudeLoginPoll("claude-work") } as SetupCall.Ok).value
        assertTrue(poll.ok)
        assertEquals("awaiting-code", poll.status)
        assertEquals("https://claude.ai/oauth/authorize?x=1", poll.url)

        assertTrue(run { api.claudeLoginCode("claude-work", "code-123#state") } is SetupCall.Ok)
        val code = fake.calls("/api/setup/claude-accounts/claude-work/login/code").single()
        assertEquals("""{"code":"code-123#state"}""", code.body)
        assertJsonNoCredential("/api/setup/claude-accounts/claude-work/login/code", "POST")
        assertFalse(code.target.contains("code-123"))

        run { api.claudeLoginCancel("claude-work") }
        // The DELETE is JSON-typed (the guard lets a no-Origin DELETE through; a body it reads must be JSON).
        assertJsonNoCredential("/api/setup/claude-accounts/claude-work/login", "DELETE")
    }

    @Test fun aSecondLoginIsTheInProgressSentenceAndARefusedCodeCarriesTheServersWords() {
        run { api.claudeLoginStart("claude-work") }
        val busy = run { api.claudeLoginStart("claude-work") } as SetupCall.Failed
        assertEquals(409, busy.status)
        assertEquals(SetupCopy.CLAUDE_LOGIN_BUSY, busy.message)

        fake.claudeCodeReply = { SetupServer.json(409, """{"ok":false,"error":"No login is waiting for a code."}""") }
        val refused = run { api.claudeLoginCode("claude-work", "nope") } as SetupCall.Failed
        assertEquals("No login is waiting for a code.", refused.message)
        assertFalse(refused.message.contains("nope"))
        fake.claudeCodeReply = { SetupServer.json(409, "{}") }
        assertEquals(SetupCopy.CLAUDE_CODE_FAILED, (run { api.claudeLoginCode("claude-work", "nope") } as SetupCall.Failed).message)
    }

    @Test fun anIdIsEncodedAsOnePathSegment() {
        run { api.claudeLoginStart("a b/../c") }
        val seen = fake.seen.single { it.method == "POST" }
        assertEquals("/api/setup/claude-accounts/a%20b%2F..%2Fc/login", seen.path)
    }

    @Test fun onceSetupIsCompletedTheAccountRoutesAnswer403WithTheSettingsPointer() {
        fake.accountRoutesClosed = true
        val list = run { api.claudeAccounts() } as SetupCall.Failed
        assertEquals(403, list.status)
        assertEquals("Setup already completed — restart Tether and use the Settings dialog.", list.message)
        assertEquals(403, (run { api.claudeAccountAdd("work") } as SetupCall.Failed).status)
        assertEquals(403, (run { api.claudeLoginStart("claude-work") } as SetupCall.Failed).status)
        // A status read is never an error: no `loggedIn: true` is "not logged in".
        assertFalse(run { api.claudeAccountStatus("claude-work") }.loggedIn)
    }

    @Test fun afterTheRestartTheNormalServerAnswers401() {
        fake.configured = true
        assertEquals(401, (run { api.githubStatus() } as SetupCall.Failed).status)
        assertEquals(401, (run { api.claudeAccounts() } as SetupCall.Failed).status)
    }
}
