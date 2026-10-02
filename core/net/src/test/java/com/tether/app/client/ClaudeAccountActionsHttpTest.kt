package com.tether.app.client

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * ta-7rh wire shapes for the Claude account changes, against tether 90fbb9f server.mjs ~8265-8475
 * (the routes) and lib/claude-accounts.mjs (the answers): each on its fixed route, by its own method,
 * with a JSON body of exactly the web's fields, the credential, and only for the server the screen
 * drew from; Tether's refusals told apart (the owner-grade 403 of a server without #236 included); a
 * redirect never followed; an id outside the registry's shape never put in a path; the code never in
 * a toString; a login link opened only when it is plain https.
 */
class ClaudeAccountActionsHttpTest {
    private val server = MockWebServer()
    private val elsewhere = MockWebServer()
    private val noRedirects = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private var authority: FilesAuthority = FilesAuthority.SignedOut
    private lateinit var actions: HttpClaudeAccountActions

    private val json = "application/json; charset=utf-8"

    @Before fun setUp() {
        server.start()
        elsewhere.start()
        authority = FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_test") }
        actions = HttpClaudeAccountActions(noRedirects, authority = { authority })
    }

    @After fun tearDown() {
        server.shutdown()
        elsewhere.shutdown()
    }

    private val origin get() = "http://${server.hostName}:${server.port}"

    private fun take(): RecordedRequest = server.takeRequest(20, TimeUnit.SECONDS) ?: error("no request reached the server")

    private fun nothingSent() = assertNull("nothing may be sent", server.takeRequest(200, TimeUnit.MILLISECONDS))

    private fun reply(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", json).setBody(body)

    private fun body(req: RecordedRequest): JsonObject = FixedRouteHttp.parseObject(req.body.readUtf8())!!

    private fun assertSent(req: RecordedRequest, method: String, path: String, body: String?) {
        assertEquals(method, req.method)
        assertEquals(path, req.path)
        assertEquals("Bearer tthr_test", req.getHeader("Authorization"))
        assertEquals("application/json", req.getHeader("Accept"))
        if (body == null) {
            assertEquals(0L, req.bodySize)
        } else {
            assertTrue(req.getHeader("Content-Type").orEmpty().startsWith("application/json"))
            assertEquals(FixedRouteHttp.parseObject(body), body(req))
        }
    }

    // ---- each change: its route, method and body; its answer ------------------------------------------

    @Test fun addPostsTheTrimmedNickname() = runBlocking<Unit> {
        server.enqueue(reply(201, """{"profile":{"id":"claude-work","extends":"claude","label":"Claude Code (work)","env":{"CLAUDE_CONFIG_DIR":"/srv/s/claude-accounts/claude-work"},"enabled":true}}"""))
        assertEquals(SecurityResult.Ok(Unit, origin, null), actions.add(origin, "  work "))
        assertSent(take(), "POST", "/api/claude-accounts", """{"nickname":"work"}""")
    }

    @Test fun renamePostsTheNicknameOnTheAccountsRoute() = runBlocking<Unit> {
        server.enqueue(reply(200, """{"ok":true,"id":"claude-work","label":"Claude Code (job)"}"""))
        assertEquals(SecurityResult.Ok(Unit, origin, null), actions.rename(origin, "claude-work", "job"))
        assertSent(take(), "POST", "/api/claude-accounts/claude-work/rename", """{"nickname":"job"}""")
    }

    @Test fun removeDeletesWithTheCredentialsChoiceAndSaysWhatWentAway() = runBlocking<Unit> {
        server.enqueue(reply(200, """{"ok":true,"removed":true,"credentialsDeleted":true}"""))
        assertEquals(SecurityResult.Ok(ClaudeAccountRemoved(removed = true, credentialsDeleted = true), origin, null), actions.remove(origin, "claude-work", deleteCredentials = true))
        assertSent(take(), "DELETE", "/api/claude-accounts/claude-work", """{"deleteCredentials":true}""")
        // The host default: a registry no-op, and its login is never deleted (removeAccount).
        server.enqueue(reply(200, """{"ok":true,"removed":false,"credentialsDeleted":false}"""))
        assertEquals(SecurityResult.Ok(ClaudeAccountRemoved(removed = false, credentialsDeleted = false), origin, null), actions.remove(origin, "claude-default", deleteCredentials = false))
        assertSent(take(), "DELETE", "/api/claude-accounts/claude-default", """{"deleteCredentials":false}""")
    }

    @Test fun logoutPostsAnEmptyObject() = runBlocking<Unit> {
        server.enqueue(reply(200, """{"ok":true,"id":"claude-work","loggedOut":true}"""))
        assertEquals(SecurityResult.Ok(Unit, origin, null), actions.logout(origin, "claude-work"))
        assertSent(take(), "POST", "/api/claude-accounts/claude-work/logout", "{}")
    }

    @Test fun theLoginStartsPollsTakesTheCodeAndCancels() = runBlocking<Unit> {
        val link = "https://claude.ai/oauth/authorize?code=true&client_id=FAKE&response_type=code&redirect_uri=https%3A%2F%2Fconsole.anthropic.com%2Foauth%2Fcode%2Fcallback&state=FAKE-STATE"
        server.enqueue(reply(200, """{"ok":true,"status":"pending-url","url":null}"""))
        val started = actions.startLogin(origin, "claude-work") as SecurityResult.Ok
        assertEquals(ClaudeLoginState(ClaudeLoginStatus.PendingUrl, null, linkRefused = false, error = null), started.value)
        assertSent(take(), "POST", "/api/claude-accounts/claude-work/login", "{}")

        server.enqueue(reply(200, """{"ok":true,"status":"awaiting-code","url":"$link","error":null}"""))
        val polled = (actions.pollLogin(origin, "claude-work") as SecurityResult.Ok).value
        assertEquals(ClaudeLoginStatus.AwaitingCode, polled.status)
        assertEquals("claude.ai", polled.link!!.host)
        assertEquals(link, polled.link!!.url)
        assertSent(take(), "GET", "/api/claude-accounts/claude-work/login/poll", null)

        server.enqueue(reply(200, """{"ok":true,"status":"awaiting-code"}"""))
        val code = ClaudeLoginCode("  FAKE-CODE-1234#FAKE-STATE \n")
        assertEquals(SecurityResult.Ok(Unit, origin, null), actions.submitCode(origin, "claude-work", code))
        assertSent(take(), "POST", "/api/claude-accounts/claude-work/login/code", """{"code":"FAKE-CODE-1234#FAKE-STATE"}""")

        server.enqueue(reply(200, """{"ok":true}"""))
        assertEquals(SecurityResult.Ok(Unit, origin, null), actions.cancelLogin(origin, "claude-work"))
        assertSent(take(), "DELETE", "/api/claude-accounts/claude-work/login", null)
    }

    @Test fun aFinishedOrFailedLoginIsReadAsSuch() = runBlocking<Unit> {
        server.enqueue(reply(200, """{"ok":true,"status":"success","url":null,"error":null}"""))
        assertEquals(ClaudeLoginStatus.Success, (actions.pollLogin(origin, "claude-work") as SecurityResult.Ok).value.status)
        server.enqueue(reply(200, """{"ok":true,"status":"error","url":null,"error":"Claude login did not complete (exit code 1)."}"""))
        val failed = (actions.pollLogin(origin, "claude-work") as SecurityResult.Ok).value
        assertEquals(ClaudeLoginStatus.Error, failed.status)
        assertEquals("Claude login did not complete (exit code 1).", failed.error)
        server.enqueue(reply(200, """{"ok":true,"status":"idle","url":null,"error":null}"""))
        assertEquals(ClaudeLoginStatus.Idle, (actions.pollLogin(origin, "claude-work") as SecurityResult.Ok).value.status)
        server.enqueue(reply(200, """{"ok":true,"status":"some-new-state"}"""))
        assertEquals(ClaudeLoginStatus.Unknown, (actions.pollLogin(origin, "claude-work") as SecurityResult.Ok).value.status)
    }

    @Test fun syncIsSavedWithTheWholeConfigAndRunOnItsOwnRoute() = runBlocking<Unit> {
        val reply = """{"config":{"mode":"selected","categories":{"plugins":true,"skills":false,"hooks":false,"mcp":true},"primaryAccountId":"claude-work"},
            "result":{"ranAt":1790000000000,"status":"ok","entries":[{"status":"linked"},{"status":"up-to-date"}]}}"""
        server.enqueue(reply(200, reply))
        val config = ClaudeSyncConfig(ClaudeSyncMode.Selected, ClaudeSyncCategories(plugins = true, skills = false, mcp = true, hooks = false), "claude-work")
        val saved = (actions.saveSync(origin, config) as SecurityResult.Ok).value
        assertEquals(config, saved.config)
        assertEquals(ClaudeSyncResult(1790000000000, "ok", changed = 1, upToDate = 1, error = null), saved.result)
        assertSent(take(), "PUT", "/api/claude-accounts/sync", """{"mode":"selected","categories":{"plugins":true,"skills":false,"hooks":false,"mcp":true},"primaryAccountId":"claude-work"}""")

        server.enqueue(reply(200, """{"config":{"mode":"none","categories":{"plugins":true,"skills":true,"hooks":true,"mcp":true},"primaryAccountId":null},"result":null}"""))
        val none = ClaudeSyncConfig(ClaudeSyncMode.None, ClaudeSyncCategories(true, true, true, true), null)
        assertEquals(SecurityResult.Ok(ClaudeSyncSaved(none, null), origin, null), actions.saveSync(origin, none))
        assertSent(take(), "PUT", "/api/claude-accounts/sync", """{"mode":"none","categories":{"plugins":true,"skills":true,"hooks":true,"mcp":true},"primaryAccountId":null}""")

        server.enqueue(reply(200, reply))
        assertTrue(actions.runSync(origin) is SecurityResult.Ok)
        assertSent(take(), "POST", "/api/claude-accounts/sync/run", "{}")
    }

    // ---- Tether's refusals ----------------------------------------------------------------------------

    /** A server without #236 (887c222) and one with it (90fbb9f) refuse a non-owner with the same opening. */
    @Test fun theOwnerGradeRefusalOfEitherServerIsNamed() = runBlocking<Unit> {
        for (sentence in listOf(
            "This needs an owner sign-in (password or passkey in a browser).",
            "This needs an owner sign-in (password, passkey, the SSO gateway or the paired Tether app).",
        )) {
            server.enqueue(reply(403, """{"error":"$sentence"}"""))
            assertEquals(SecurityResult.OwnerSignInNeeded(origin), actions.add(origin, "work"))
            take()
        }
        server.enqueue(reply(403, """{"error":"This needs an owner sign-in (password or passkey in a browser)."}"""))
        assertEquals(SecurityResult.OwnerSignInNeeded(origin), actions.remove(origin, "claude-work", false))
        take()
        server.enqueue(reply(403, """{"error":"This needs an owner sign-in (password or passkey in a browser)."}"""))
        assertEquals(SecurityResult.OwnerSignInNeeded(origin), actions.runSync(origin))
        take()
    }

    @Test fun theRoutesOwnRefusalCodesBecomeFixedSentences() = runBlocking<Unit> {
        server.enqueue(reply(409, """{"ok":false,"error":"already-in-progress"}"""))
        assertEquals(SecurityResult.Refused(409, ClaudeAccountActionCopy.ALREADY_IN_PROGRESS, origin), actions.startLogin(origin, "claude-work"))
        take()
        server.enqueue(reply(409, """{"error":"That profile's CLAUDE_CONFIG_DIR is not a Tether-managed account directory, so Tether will not sign it in or out. Edit it in Settings → Engines instead.","error_code":"not-managed"}"""))
        assertEquals(SecurityResult.Refused(409, ClaudeAccountRefusal.NotManaged.sentence, origin), actions.logout(origin, "claude-x"))
        take()
        server.enqueue(reply(409, """{"error":"That profile is not a Claude account.","error_code":"not-a-claude-account"}"""))
        assertEquals(SecurityResult.Refused(409, ClaudeAccountActionCopy.NOT_A_CLAUDE_ACCOUNT, origin), actions.remove(origin, "codex-x", false))
        take()
        server.enqueue(reply(404, """{"error":"No such Claude account."}"""))
        assertEquals(SecurityResult.Refused(404, ClaudeAccountActionCopy.NO_SUCH_ACCOUNT, origin), actions.rename(origin, "claude-gone", "x"))
        take()
        server.enqueue(reply(409, """{"ok":false,"error":"no-active-login"}"""))
        assertEquals(SecurityResult.Refused(409, ClaudeAccountActionCopy.NO_ACTIVE_LOGIN, origin), actions.submitCode(origin, "claude-work", ClaudeLoginCode("FAKE")))
        take()
        server.enqueue(reply(409, """{"ok":false,"error":"code-not-delivered"}"""))
        assertEquals(SecurityResult.Refused(409, ClaudeAccountActionCopy.CODE_NOT_DELIVERED, origin), actions.submitCode(origin, "claude-work", ClaudeLoginCode("FAKE")))
        take()
        // The nickname validation's own sentence (a 400 from addAccount) is Tether's: shown (by the label rule, later).
        server.enqueue(reply(400, """{"error":"An account nickname must be 64 characters or fewer."}"""))
        assertEquals(SecurityResult.Refused(400, "An account nickname must be 64 characters or fewer.", origin), actions.add(origin, "x"))
        take()
        // The code route's 200 with ok:false is a refusal too, never a success.
        server.enqueue(reply(200, """{"ok":false,"error":"empty-code"}"""))
        assertEquals(SecurityResult.Refused(200, ClaudeAccountActionCopy.EMPTY_CODE, origin), actions.submitCode(origin, "claude-work", ClaudeLoginCode("FAKE")))
        take()
    }

    @Test fun signedOutAProxysErrorAndAnUnusableBodyAreNotTethersWords() = runBlocking<Unit> {
        server.enqueue(reply(401, """{"error":"Unauthorized"}"""))
        assertEquals(SecurityResult.SignedOut(origin), actions.add(origin, "work"))
        take()
        server.enqueue(reply(502, """{"error":"upstream said something"}"""))
        assertEquals(SecurityResult.Unavailable(502, origin), actions.logout(origin, "claude-work"))
        take()
        server.enqueue(reply(200, """{"something":"else"}"""))
        assertEquals(SecurityResult.Unavailable(200, origin), actions.rename(origin, "claude-work", "x"))
        take()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>sign in</html>"))
        assertTrue(actions.runSync(origin) is SecurityResult.Blocked)
        take()
    }

    // ---- what is never sent -------------------------------------------------------------------------

    @Test fun nothingGoesToAServerOtherThanTheOneDrawnFrom() = runBlocking<Unit> {
        val drawn = "http://${elsewhere.hostName}:${elsewhere.port}"
        assertEquals(SecurityResult.NotSent(origin), actions.add(drawn, "work"))
        assertEquals(SecurityResult.NotSent(origin), actions.remove(drawn, "claude-work", true))
        assertEquals(SecurityResult.NotSent(origin), actions.submitCode(drawn, "claude-work", ClaudeLoginCode("FAKE")))
        assertEquals(SecurityResult.NotSent(origin), actions.runSync(drawn))
        nothingSent()
        assertNull(elsewhere.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun signedOutSendsNothing() = runBlocking<Unit> {
        authority = FilesAuthority.SignedOut
        assertEquals(SecurityResult.SignedOut(), actions.logout(origin, "claude-work"))
        nothingSent()
    }

    @Test fun anIdOutsideTheRegistrysShapeIsNeverPutInAPath() = runBlocking<Unit> {
        for (id in listOf("../devices", "claude-work/../../devices", "sync/run", "Claude-Work", "claude%2Fwork", "", "a".repeat(65), "claude work", "-x")) {
            assertEquals(id, SecurityResult.NotSent(origin), actions.remove(origin, id, true))
            assertEquals(id, SecurityResult.NotSent(origin), actions.startLogin(origin, id))
            assertEquals(id, SecurityResult.NotSent(origin), actions.submitCode(origin, id, ClaudeLoginCode("FAKE")))
        }
        nothingSent()
    }

    @Test fun anEmptyOrOverlongNicknameOrCodeAndAnUnknownSyncModeAreNotSent() = runBlocking<Unit> {
        assertEquals(SecurityResult.NotSent(origin), actions.add(origin, "   "))
        assertEquals(SecurityResult.NotSent(origin), actions.add(origin, "x".repeat(ClaudeAccountActions.NICKNAME_MAX + 1)))
        assertEquals(SecurityResult.NotSent(origin), actions.rename(origin, "claude-work", ""))
        assertEquals(SecurityResult.NotSent(origin), actions.submitCode(origin, "claude-work", ClaudeLoginCode(" \n ")))
        assertEquals(SecurityResult.NotSent(origin), actions.submitCode(origin, "claude-work", ClaudeLoginCode("x".repeat(ClaudeAccountActions.CODE_MAX + 1))))
        assertEquals(SecurityResult.NotSent(origin), actions.saveSync(origin, ClaudeSyncConfig(ClaudeSyncMode.Unknown, ClaudeSyncCategories(true, true, true, true), null)))
        assertEquals(SecurityResult.NotSent(origin), actions.saveSync(origin, ClaudeSyncConfig(ClaudeSyncMode.All, ClaudeSyncCategories(true, true, true, true), "x".repeat(129))))
        nothingSent()
    }

    /** A redirect is a gateway's: never followed, so the credential and the body never reach another host. */
    @Test fun aRedirectIsNeverFollowed() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", elsewhere.url("/api/claude-accounts/claude-work/login/code")))
        assertTrue(actions.submitCode(origin, "claude-work", ClaudeLoginCode("FAKE-CODE")) is SecurityResult.Blocked)
        take()
        assertNull(elsewhere.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    // ---- the code and the link -----------------------------------------------------------------------

    @Test fun theCodePrintsNothing() {
        val code = ClaudeLoginCode("FAKE-SECRET-CODE")
        assertFalse(code.toString().contains("FAKE-SECRET-CODE"))
        assertFalse("$code".contains("SECRET"))
    }

    @Test fun onlyAPlainHttpsLinkIsKept() {
        assertEquals("claude.ai", ClaudeLoginLink.parse("https://claude.ai/oauth/authorize?x=1")!!.host)
        for (bad in listOf(
            "http://claude.ai/oauth/authorize",
            "javascript:alert(1)",
            "intent://claude.ai#Intent;scheme=https;end",
            "file:///sdcard/x",
            "https://user:pass@claude.ai/oauth",
            "https://claude.ai/oauth\nauthorize",
            "https://claude.ai/o auth",
            "https://claude.ai/\u202Eoauth",
            "https://",
            "https://claude.ai/" + "a".repeat(ClaudeLoginLink.MAX_LENGTH),
            "",
        )) {
            assertNull(bad, ClaudeLoginLink.parse(bad))
        }
        assertFalse(ClaudeLoginLink.parse("https://claude.ai/oauth?state=S")!!.toString().contains("state"))
    }

    @Test fun aLinkTheClientWillNotOpenIsFlaggedNotKept() = runBlocking<Unit> {
        server.enqueue(reply(200, """{"ok":true,"status":"awaiting-code","url":"http://evil.test/phish"}"""))
        val state = (actions.pollLogin(origin, "claude-work") as SecurityResult.Ok).value
        assertNull(state.link)
        assertTrue(state.linkRefused)
        take()
    }

    /** The server keeps the pasted code to itself; the reply carries nothing of it, and nothing here echoes it. */
    @Test fun theCodeTravelsOnlyInItsOneBody() = runBlocking<Unit> {
        server.enqueue(reply(200, """{"ok":true,"status":"awaiting-code"}"""))
        val result = actions.submitCode(origin, "claude-work", ClaudeLoginCode("FAKE-ONLY-ONCE"))
        assertFalse(result.toString().contains("FAKE-ONLY-ONCE"))
        val req = take()
        assertEquals("FAKE-ONLY-ONCE", body(req)["code"]!!.jsonPrimitive.content)
        assertFalse(req.path!!.contains("FAKE-ONLY-ONCE"))
        assertFalse(req.headers.toString().contains("FAKE-ONLY-ONCE"))
    }
}
