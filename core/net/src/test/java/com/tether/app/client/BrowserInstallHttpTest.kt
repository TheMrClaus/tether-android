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
 * Wire shapes for Settings' Install Chromium key: `POST /api/browser/install` (200 `{ok:true,status}`, 409
 * `{ok:false,code,message,status}`) and `GET /api/browser/install` (the job), and the two new fields of the
 * runtime status (`canInstall`, `location`). The bodies are the ones the web's server writes
 * (server.mjs `/api/browser/install`, lib/browser-install.ts `BrowserInstallState`, lib/browser-runtime.ts).
 */
class BrowserInstallHttpTest {
    private val server = MockWebServer()
    private val elsewhere = MockWebServer()
    private val noRedirects = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private var authority: FilesAuthority = FilesAuthority.SignedOut
    private lateinit var browser: HttpBrowserRuntime

    private val json = "application/json; charset=utf-8"

    @Before fun setUp() {
        server.start()
        elsewhere.start()
        authority = FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_test") }
        browser = HttpBrowserRuntime(noRedirects, authority = { authority })
    }

    @After fun tearDown() {
        server.shutdown()
        elsewhere.shutdown()
    }

    private val origin get() = "http://${server.hostName}:${server.port}"

    private fun take(): RecordedRequest = server.takeRequest(20, TimeUnit.SECONDS) ?: error("no request reached the server")

    private fun nothingSent(on: MockWebServer = server) = assertNull("nothing may be sent", on.takeRequest(200, TimeUnit.MILLISECONDS))

    private fun reply(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", json).setBody(body)

    private val running = """{"state":"running","startedAt":1760000000000,"finishedAt":null,"exitCode":null,"reason":null,"output":["Downloading Chromium 140.0 (playwright build v1187)"]}"""
    private val done = """{"state":"done","startedAt":1760000000000,"finishedAt":1760000090000,"exitCode":0,"reason":null,"output":["Chromium downloaded to /state/browsers/chromium-1187"]}"""
    private val failed = """{"state":"failed","startedAt":1760000000000,"finishedAt":1760000005000,"exitCode":1,"reason":"The installer exited with code 1.","output":["Error: getaddrinfo ENOTFOUND cdn.playwright.dev"]}"""
    private val idle = """{"state":"idle","startedAt":null,"finishedAt":null,"exitCode":null,"reason":null,"output":[]}"""
    private val installable = """{"playwrightImportable":true,"playwrightVersion":"1.55.0","chromiumRevision":"1187","executablePath":"/home/u/.cache/ms-playwright/chromium-1187/chrome-linux/chrome","binaryPresent":false,"location":"default","usable":false,"installCommand":"npx playwright install chromium","canInstall":true}"""
    private val installedByTether = """{"playwrightImportable":true,"playwrightVersion":"1.55.0","chromiumRevision":"1187","executablePath":"/state/browsers/chromium-1187/chrome-linux/chrome","binaryPresent":true,"location":"state","usable":true,"installCommand":null,"canInstall":false}"""

    @Test fun theStatusCarriesCanInstallAndWhereChromiumIs() = runBlocking<Unit> {
        server.enqueue(reply(200, installable))
        val a = (browser.status(origin) as SecurityResult.Ok).value
        assertEquals(true, a.canInstall)
        assertEquals("default", a.location)
        server.enqueue(reply(200, installedByTether))
        val b = (browser.status(origin) as SecurityResult.Ok).value
        assertEquals(false, b.canInstall)
        assertEquals("state", b.location)
    }

    @Test fun aServerBeforeTheKeyReportsNeitherField() = runBlocking<Unit> {
        server.enqueue(reply(200, """{"playwrightImportable":true,"playwrightVersion":"1.55.0","chromiumRevision":"1187","executablePath":"/p","binaryPresent":false,"usable":false,"installCommand":"npx playwright install chromium"}"""))
        val a = (browser.status(origin) as SecurityResult.Ok).value
        assertNull(a.canInstall)
        assertNull(a.location)
    }

    @Test fun startingAnInstallIsAPostToItsFixedRoute() = runBlocking<Unit> {
        server.enqueue(reply(200, """{"ok":true,"status":$running}"""))
        val start = (browser.startInstall(origin) as SecurityResult.Ok).value
        assertTrue(start.ok)
        assertEquals(BrowserInstallJob.RUNNING, start.job?.state)
        assertEquals(listOf("Downloading Chromium 140.0 (playwright build v1187)"), start.job?.output)
        val req = take()
        assertEquals("POST", req.method)
        assertEquals("/api/browser/install", req.path)
        assertEquals("Bearer tthr_test", req.getHeader("Authorization"))
    }

    @Test fun aSecondPressAnswers409AlreadyInstallingWithTheServersMessage() = runBlocking<Unit> {
        server.enqueue(reply(409, """{"ok":false,"code":"already_installing","message":"Chromium is already being installed.","status":$running}"""))
        val start = (browser.startInstall(origin) as SecurityResult.Ok).value
        assertFalse(start.ok)
        assertEquals("already_installing", start.code)
        assertEquals("Chromium is already being installed.", start.message)
        assertTrue(start.job?.running == true)
    }

    @Test fun noPlaywrightAnswers409PlaywrightMissing() = runBlocking<Unit> {
        server.enqueue(reply(409, """{"ok":false,"code":"playwright_missing","message":"The Playwright package could not be loaded, so there is nothing to install with.","status":$idle}"""))
        val start = (browser.startInstall(origin) as SecurityResult.Ok).value
        assertFalse(start.ok)
        assertEquals("playwright_missing", start.code)
        assertEquals("The Playwright package could not be loaded, so there is nothing to install with.", start.message)
        assertEquals(BrowserInstallJob.IDLE, start.job?.state)
    }

    @Test fun anInstallThatFailedAtOnceIsAnOkStartWithAFailedJob() = runBlocking<Unit> {
        server.enqueue(reply(200, """{"ok":true,"status":$failed}"""))
        val start = (browser.startInstall(origin) as SecurityResult.Ok).value
        assertTrue(start.ok)
        assertEquals(BrowserInstallJob.FAILED, start.job?.state)
    }

    @Test fun theJobIsPolledByGetInEachOfItsStates() = runBlocking<Unit> {
        server.enqueue(reply(200, running))
        server.enqueue(reply(200, done))
        server.enqueue(reply(200, failed))
        val r = (browser.installJob(origin) as SecurityResult.Ok).value
        assertEquals(BrowserInstallJob(BrowserInstallJob.RUNNING, 1760000000000, null, null, null, listOf("Downloading Chromium 140.0 (playwright build v1187)")), r)
        val req = take()
        assertEquals("GET", req.method)
        assertEquals("/api/browser/install", req.path)
        assertEquals(0L, req.bodySize)
        val d = (browser.installJob(origin) as SecurityResult.Ok).value
        assertEquals(BrowserInstallJob(BrowserInstallJob.DONE, 1760000000000, 1760000090000, 0, null, listOf("Chromium downloaded to /state/browsers/chromium-1187")), d)
        val f = (browser.installJob(origin) as SecurityResult.Ok).value
        assertEquals(BrowserInstallJob.FAILED, f.state)
        assertEquals(1, f.exitCode)
        assertEquals("The installer exited with code 1.", f.reason)
        assertEquals(listOf("Error: getaddrinfo ENOTFOUND cdn.playwright.dev"), f.output)
    }

    @Test fun anAnswerWithoutAKnownStateIsNoJob() = runBlocking<Unit> {
        server.enqueue(reply(200, """{"error":"login required"}"""))
        assertEquals(SecurityResult.Unavailable(200, origin), browser.installJob(origin))
        server.enqueue(reply(200, """{"state":"paused","output":[]}"""))
        assertEquals(SecurityResult.Unavailable(200, origin), browser.installJob(origin))
    }

    @Test fun theOutputKeptIsBoundedByLinesAndLength() = runBlocking<Unit> {
        val lines = (1..60).joinToString(",") { "\"line $it ${"x".repeat(600)}\"" }
        server.enqueue(reply(200, """{"state":"failed","startedAt":1,"finishedAt":2,"exitCode":1,"reason":"r","output":[$lines]}"""))
        val job = (browser.installJob(origin) as SecurityResult.Ok).value
        assertEquals(BrowserRuntimeJson.MAX_LINES, job.output.size)
        assertTrue(job.output.all { it.length <= BrowserRuntimeJson.MAX_LINE })
        assertTrue(job.output.last().startsWith("line 60 "))
    }

    @Test fun theOwnerGradeRefusalIs403ForBothCalls() = runBlocking<Unit> {
        val body = """{"error":"This needs an owner sign-in (password, passkey, the SSO gateway or the paired Tether app)."}"""
        server.enqueue(reply(403, body))
        assertEquals(SecurityResult.OwnerSignInNeeded(origin), browser.startInstall(origin))
        server.enqueue(reply(403, body))
        assertEquals(SecurityResult.OwnerSignInNeeded(origin), browser.installJob(origin))
    }

    @Test fun anOlderServerWithoutTheRouteIsAQuietRefusalNeverAThrow() = runBlocking<Unit> {
        server.enqueue(reply(404, """{"error":"Not found"}"""))
        assertTrue(browser.startInstall(origin) is SecurityResult.Refused)
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "text/plain").setBody("Not Found"))
        assertEquals(SecurityResult.Unavailable(404, origin), browser.installJob(origin))
    }

    @Test fun nothingGoesToAnotherServerThanTheOneDrawn() = runBlocking<Unit> {
        val other = "http://${elsewhere.hostName}:${elsewhere.port}"
        assertEquals(SecurityResult.NotSent(origin), browser.startInstall(other))
        assertEquals(SecurityResult.NotSent(origin), browser.installJob(other))
        nothingSent()
        nothingSent(elsewhere)
    }

    @Test fun theUnavailableSourceInstallsNothingAndSendsNothing() = runBlocking<Unit> {
        assertEquals(SecurityResult.SignedOut(), BrowserRuntimeSource.Unavailable.startInstall(origin))
        assertEquals(SecurityResult.SignedOut(), BrowserRuntimeSource.Unavailable.installJob(origin))
        nothingSent()
    }
}
