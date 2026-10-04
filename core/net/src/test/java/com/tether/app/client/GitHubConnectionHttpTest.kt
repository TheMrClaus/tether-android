package com.tether.app.client

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ta-coik.21 wire shapes for Settings' GitHub connection, against tether 90fbb9f server.mjs
 * :8213-8263 (the six method + path pairs) and lib/github-auth.mjs (the answers: `statusGitHubConnection`
 * :146-184, `startDeviceLogin` :242-305, `pollDeviceLogin` :314-325, `verifyGitHubToken` :190-202,
 * `requireOwnerGrade` server.mjs :3321-3327, the /api/ gate's 401 :7411-7412), as the web sends them
 * (components/settings-dialog.tsx :980-1078): each on its fixed route, by its own method, with the
 * credential, only for the server the screen drew from; the token only in its one body, never in a
 * toString; a redirect never followed.
 */
class GitHubConnectionHttpTest {
    private val server = MockWebServer()
    private val elsewhere = MockWebServer()
    private val noRedirects = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private var authority: FilesAuthority = FilesAuthority.SignedOut
    private lateinit var github: HttpGitHubConnection

    private val json = "application/json; charset=utf-8"

    @Before fun setUp() {
        server.start()
        elsewhere.start()
        authority = FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_test") }
        github = HttpGitHubConnection(noRedirects, authority = { authority })
    }

    @After fun tearDown() {
        server.shutdown()
        elsewhere.shutdown()
    }

    private val origin get() = "http://${server.hostName}:${server.port}"

    private fun take(): RecordedRequest = server.takeRequest(20, TimeUnit.SECONDS) ?: error("no request reached the server")

    private fun nothingSent(on: MockWebServer = server) = assertNull("nothing may be sent", on.takeRequest(200, TimeUnit.MILLISECONDS))

    private fun reply(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", json).setBody(body)

    private fun assertSent(req: RecordedRequest, method: String, path: String, body: String?) {
        assertEquals(method, req.method)
        assertEquals(path, req.path)
        assertEquals("Bearer tthr_test", req.getHeader("Authorization"))
        assertEquals("application/json", req.getHeader("Accept"))
        if (body == null) {
            assertEquals(0L, req.bodySize)
        } else {
            assertTrue(req.getHeader("Content-Type").orEmpty().startsWith("application/json"))
            assertEquals(FixedRouteHttp.parseObject(body), FixedRouteHttp.parseObject(req.body.readUtf8()))
        }
    }

    // ---- the status read (any authenticated principal) -----------------------------------------------

    @Test fun statusReadsALoggedInHost() = runBlocking<Unit> {
        // github-auth.test.mjs :112-137: the host gh login, its version and its token's scopes.
        server.enqueue(reply(200, """{"ghInstalled":true,"ghVersion":"2.97.0","authenticated":true,"account":"octocat","scopes":["gist","read:org","repo"],"managedToken":false}"""))
        val status = (github.status(origin) as SecurityResult.Ok).value
        assertEquals(GitHubConnectionStatus(ghInstalled = true, ghVersion = "2.97.0", authenticated = true, account = "octocat", scopes = listOf("gist", "read:org", "repo"), managedToken = false), status)
        assertSent(take(), "GET", "/api/github/connection", null)
    }

    @Test fun statusReadsGhNotInstalledWithItsNulls() = runBlocking<Unit> {
        // github-auth.mjs :155-162.
        server.enqueue(reply(200, """{"ghInstalled":false,"ghVersion":null,"authenticated":false,"account":null,"scopes":[],"managedToken":true}"""))
        assertEquals(
            GitHubConnectionStatus(ghInstalled = false, ghVersion = null, authenticated = false, account = null, scopes = emptyList(), managedToken = true),
            (github.status(origin) as SecurityResult.Ok).value,
        )
    }

    @Test fun aStatusWithoutTheRoutesShapeIsNoAnswer() = runBlocking<Unit> {
        server.enqueue(reply(200, """{"error":"login required"}"""))
        assertEquals(SecurityResult.Unavailable(200, origin), github.status(origin))
    }

    @Test fun theApiGates401IsSignedOut() = runBlocking<Unit> {
        server.enqueue(reply(401, """{"error":"Authentication required."}"""))
        assertEquals(SecurityResult.SignedOut(origin), github.status(origin))
    }

    /**
     * r2 (verifier): any other status with a JSON object is the web's `data.error` (a 5xx's too, e.g.
     * server.mjs's generic 500 handler), empty when it has none (the web's `|| fallback`); a refusal that
     * is not JSON is unavailable (the web's failed `res.json()`, its fallback too).
     */
    @Test fun aJsonRefusalOfAnyStatusIsTheServersSentence() = runBlocking<Unit> {
        server.enqueue(reply(500, """{"error":"Internal server error."}"""))
        assertEquals(SecurityResult.Refused(500, "Internal server error.", origin), github.status(origin))
        server.enqueue(reply(502, """{"error":"upstream said something"}"""))
        assertEquals(SecurityResult.Refused(502, "upstream said something", origin), github.logout(origin))
        server.enqueue(reply(503, """{}"""))
        assertEquals(SecurityResult.Refused(503, "", origin), github.pollLogin(origin))
        server.enqueue(reply(422, """{"error":"x"}"""))
        assertEquals(SecurityResult.Refused(422, "x", origin), github.startLogin(origin))
        server.enqueue(MockResponse().setResponseCode(500).setHeader("Content-Type", "text/plain").setBody("oops"))
        assertEquals(SecurityResult.Unavailable(500, origin), github.status(origin))
    }

    /** r2 (verifier): start, cancel, token and logout succeed on `res.ok` alone (:1029, :1053, :1068), whatever the body. */
    @Test fun aTwoHundredThatIsNotJsonIsSuccessWhereTheWebChecksOnlyResOk() = runBlocking<Unit> {
        fun plain() = MockResponse().setResponseCode(200).setHeader("Content-Type", "text/plain").setBody("ok")
        server.enqueue(plain())
        assertEquals(SecurityResult.Ok(Unit, origin, null), github.startLogin(origin))
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(SecurityResult.Ok(Unit, origin, null), github.logout(origin))
        server.enqueue(plain())
        assertEquals(SecurityResult.Ok(Unit, origin, null), github.cancelLogin(origin))
        server.enqueue(plain())
        assertEquals(SecurityResult.Ok(GitHubTokenSaved(null, emptyList()), origin, null), github.saveToken(origin, GitHubToken("ghp_FAKE")))
        // The poll and the status need their body: not JSON is no answer (the poll's "no response").
        server.enqueue(plain())
        assertEquals(SecurityResult.Unavailable(200, origin), github.pollLogin(origin))
        server.enqueue(plain())
        assertEquals(SecurityResult.Unavailable(200, origin), github.status(origin))
    }

    // ---- the device flow ------------------------------------------------------------------------------

    @Test fun theDeviceFlowStartsPollsAndCancels() = runBlocking<Unit> {
        server.enqueue(reply(200, """{"ok":true}"""))
        assertEquals(SecurityResult.Ok(Unit, origin, null), github.startLogin(origin))
        assertSent(take(), "POST", "/api/github/connection/login", "{}")

        // pollDeviceLogin before gh printed the code, then with it.
        server.enqueue(reply(200, """{"ok":true,"status":"pending","deviceCode":null,"verificationUri":null,"error":null}"""))
        assertEquals(GitHubDevicePoll.Started, (github.pollLogin(origin) as SecurityResult.Ok).value)
        assertSent(take(), "GET", "/api/github/connection/login/poll", null)
        server.enqueue(reply(200, """{"ok":true,"status":"pending","deviceCode":"ABCD-1234","verificationUri":"https://github.com/login/device","error":null}"""))
        assertEquals(
            GitHubDevicePoll(ok = true, status = GitHubDeviceStatus.Pending, deviceCode = "ABCD-1234", verificationUri = "https://github.com/login/device", error = null),
            (github.pollLogin(origin) as SecurityResult.Ok).value,
        )
        take()

        server.enqueue(reply(200, """{"ok":true}"""))
        assertEquals(SecurityResult.Ok(Unit, origin, null), github.cancelLogin(origin))
        assertSent(take(), "DELETE", "/api/github/connection/login", null)
    }

    @Test fun thePollReadsEveryStatusTheServerHasAndOneItDoesNot() = runBlocking<Unit> {
        // github-auth.mjs :316 idle (nothing in flight), :295 complete, :296-300 error with its sentence.
        server.enqueue(reply(200, """{"ok":false,"status":"idle","deviceCode":null,"verificationUri":null,"error":null}"""))
        assertEquals(GitHubDevicePoll(false, GitHubDeviceStatus.Idle, null, null, null), (github.pollLogin(origin) as SecurityResult.Ok).value)
        server.enqueue(reply(200, """{"ok":true,"status":"complete","deviceCode":"ABCD-1234","verificationUri":"https://github.com/login/device","error":null}"""))
        assertEquals(GitHubDeviceStatus.Complete, (github.pollLogin(origin) as SecurityResult.Ok).value.status)
        server.enqueue(reply(200, """{"ok":true,"status":"error","deviceCode":null,"verificationUri":null,"error":"gh auth login exited with code 1."}"""))
        val failed = (github.pollLogin(origin) as SecurityResult.Ok).value
        assertEquals(GitHubDeviceStatus.Error, failed.status)
        assertEquals("gh auth login exited with code 1.", failed.error)
        server.enqueue(reply(200, """{"ok":true,"status":"expired"}"""))
        assertEquals(GitHubDeviceStatus.Unknown, (github.pollLogin(origin) as SecurityResult.Ok).value.status)
    }

    /**
     * r3 (owner rule): a verification address of any length is kept WHOLE (never cut, never dropped), as
     * the web's `<a href>` takes any length; only the reply's 64 KiB cap bounds it.
     */
    @Test fun aLongVerificationAddressIsKeptWhole() = runBlocking<Unit> {
        val long = "https://github.com/login/device?" + "a".repeat(20_000)
        server.enqueue(reply(200, """{"ok":true,"status":"pending","deviceCode":"ABCD-1234","verificationUri":"$long","error":null}"""))
        val polled = (github.pollLogin(origin) as SecurityResult.Ok).value
        assertEquals(long, polled.verificationUri)
        assertEquals("ABCD-1234", polled.deviceCode)
        assertEquals(GitHubDeviceStatus.Pending, polled.status)
    }

    @Test fun aSecondStartIsTheServersRefusalWithItsSentence() = runBlocking<Unit> {
        // server.mjs :8235 `started.ok ? 200 : 409`, github-auth.mjs :244.
        server.enqueue(reply(409, """{"ok":false,"error":"A GitHub login is already in progress. Cancel it first."}"""))
        assertEquals(SecurityResult.Refused(409, "A GitHub login is already in progress. Cancel it first.", origin), github.startLogin(origin))
    }

    // ---- the token -----------------------------------------------------------------------------------------

    @Test fun aTokenIsSentTrimmedInItsOneBodyAndNeverPrinted() = runBlocking<Unit> {
        val token = GitHubToken("  ghp_FAKE_TOKEN_FOR_TESTS_ONLY \n")
        assertEquals("GitHubToken(***)", token.toString())
        assertFalse(token.toString().contains("FAKE"))
        server.enqueue(reply(200, """{"ok":true,"account":"octocat","scopes":["repo","read:org","gist"]}"""))
        val saved = github.saveToken(origin, token)
        assertEquals(SecurityResult.Ok(GitHubTokenSaved("octocat", listOf("repo", "read:org", "gist")), origin, null), saved)
        // Nothing the caller holds afterwards carries it.
        assertFalse(saved.toString().contains("FAKE_TOKEN"))
        assertSent(take(), "POST", "/api/github/connection/token", """{"token":"ghp_FAKE_TOKEN_FOR_TESTS_ONLY"}""")
    }

    @Test fun aRefusedTokenIsTheServersSentence() = runBlocking<Unit> {
        // server.mjs :8254 `json(res, 400, verified)`, github-auth.mjs :199.
        server.enqueue(reply(400, """{"ok":false,"error":"That token could not be verified. Check the value and its scopes."}"""))
        assertEquals(
            SecurityResult.Refused(400, "That token could not be verified. Check the value and its scopes.", origin),
            github.saveToken(origin, GitHubToken("ghp_FAKE")),
        )
    }

    @Test fun aBlankTokenIsNeverSent() = runBlocking<Unit> {
        assertEquals(SecurityResult.NotSent(origin), github.saveToken(origin, GitHubToken(" \n\t")))
        nothingSent()
    }

    @Test fun logoutPostsAndTheServerForgetsOnlyTethersToken() = runBlocking<Unit> {
        server.enqueue(reply(200, """{"ok":true}"""))
        assertEquals(SecurityResult.Ok(Unit, origin, null), github.logout(origin))
        assertSent(take(), "POST", "/api/github/connection/logout", "{}")
    }

    // ---- refusals and the credential's reach --------------------------------------------------------

    @Test fun theOwnerGradeRefusalIsRecognisedByItsOpening() = runBlocking<Unit> {
        val owner = """{"error":"This needs an owner sign-in (password, passkey, the SSO gateway or the paired Tether app)."}"""
        server.enqueue(reply(403, owner))
        assertEquals(SecurityResult.OwnerSignInNeeded(origin), github.startLogin(origin))
        server.enqueue(reply(403, owner))
        assertEquals(SecurityResult.OwnerSignInNeeded(origin), github.pollLogin(origin))
        server.enqueue(reply(403, owner))
        assertEquals(SecurityResult.OwnerSignInNeeded(origin), github.saveToken(origin, GitHubToken("ghp_FAKE")))
        server.enqueue(reply(403, owner))
        assertEquals(SecurityResult.OwnerSignInNeeded(origin), github.logout(origin))
        // The origin guard's own 403 is a plain refusal with its sentence (server.mjs :7147-7150).
        server.enqueue(reply(403, """{"error":"This request must come from the Tether console itself.","code":"cross_origin_refused"}"""))
        assertEquals(SecurityResult.Refused(403, "This request must come from the Tether console itself.", origin), github.logout(origin))
    }

    @Test fun nothingGoesToAnotherServerThanTheOneDrawn() = runBlocking<Unit> {
        val other = "http://${elsewhere.hostName}:${elsewhere.port}"
        assertEquals(SecurityResult.NotSent(origin), github.saveToken(other, GitHubToken("ghp_FAKE")))
        assertEquals(SecurityResult.NotSent(origin), github.status(other))
        assertEquals(SecurityResult.NotSent(origin), github.startLogin(other))
        nothingSent()
        nothingSent(elsewhere)
        authority = FilesAuthority.SignedOut
        assertEquals(SecurityResult.SignedOut(), github.saveToken(origin, GitHubToken("ghp_FAKE")))
        nothingSent()
    }

    @Test fun aRedirectIsNeverFollowed() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/sso").toString()))
        assertEquals(SecurityResult.Blocked(302, origin), github.saveToken(origin, GitHubToken("ghp_FAKE")))
        take()
        nothingSent(elsewhere)
    }

    @Test fun aSignInPageIsBlockedNotAnAnswer() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>sign in</html>"))
        assertEquals(SecurityResult.Blocked(200, origin), github.pollLogin(origin))
    }

    @Test fun anUnreachableServerIsUnavailableWithNoCode() = runBlocking<Unit> {
        server.shutdown()
        assertEquals(SecurityResult.Unavailable(null, origin), github.pollLogin(origin))
    }

    @Test fun theUnavailableSourceSendsNothing() = runBlocking<Unit> {
        assertEquals(SecurityResult.SignedOut(), GitHubConnectionSource.Unavailable.saveToken(origin, GitHubToken("ghp_FAKE")))
        assertTrue(GitHubConnectionSource.Unavailable.status(origin) is SecurityResult.SignedOut)
    }

    @Test fun answersAreBounded() {
        val long = "x".repeat(10_000)
        val status = GitHubConnectionJson.status(FixedRouteHttp.parseObject("""{"authenticated":true,"account":"$long","ghVersion":"$long","scopes":[${(1..500).joinToString(",") { "\"s$it\"" }}]}""")!!)!!
        assertTrue(status.account!!.length <= GitHubConnectionJson.MAX_TEXT)
        assertTrue(status.ghVersion!!.length <= GitHubConnectionJson.MAX_CODE)
        assertEquals(GitHubConnectionJson.MAX_SCOPES, status.scopes.size)
        // Wrong types are absent, never coerced.
        val odd = GitHubConnectionJson.status(FixedRouteHttp.parseObject("""{"authenticated":"true"}""")!!)
        assertNull(odd)
        val oddPoll = GitHubConnectionJson.poll(FixedRouteHttp.parseObject("""{"status":7,"deviceCode":{"a":1}}""")!!)
        assertEquals(GitHubDevicePoll(false, GitHubDeviceStatus.Unknown, null, null, null), oddPoll)
    }
}
