package com.tether.app.client

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wire shapes for Settings' Browser section: `GET /api/browser/runtime-status` (owner grade), read as
 * the web's BrowserRuntimeSection reads it: a fixed route by GET with the credential, only for the
 * server the screen drew from; the found and the missing answers; the owner-grade refusal (403), an
 * older server without the route (404) and a sign-in page are quiet answers, never a throw.
 */
class BrowserRuntimeHttpTest {
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

    private val found = """{"playwrightImportable":true,"playwrightVersion":"1.55.0","chromiumRevision":"1187","executablePath":"/home/u/.cache/ms-playwright/chromium-1187/chrome-linux/chrome","binaryPresent":true,"usable":true,"installCommand":null}"""
    private val missing = """{"playwrightImportable":true,"playwrightVersion":"1.55.0","chromiumRevision":"1187","executablePath":"/home/u/.cache/ms-playwright/chromium-1187/chrome-linux/chrome","binaryPresent":false,"usable":false,"installCommand":"npx playwright install chromium"}"""
    private val noPackage = """{"playwrightImportable":false,"playwrightVersion":null,"chromiumRevision":null,"executablePath":null,"binaryPresent":false,"importError":"Cannot find package 'playwright'","usable":false,"installCommand":"npx playwright install chromium"}"""

    @Test fun aFoundRuntimeIsReadFromItsFixedRoute() = runBlocking<Unit> {
        server.enqueue(reply(200, found))
        val status = (browser.status(origin) as SecurityResult.Ok).value
        assertEquals(
            BrowserRuntimeStatus(
                usable = true, playwrightImportable = true, playwrightVersion = "1.55.0", chromiumRevision = "1187",
                executablePath = "/home/u/.cache/ms-playwright/chromium-1187/chrome-linux/chrome", binaryPresent = true, importError = null, installCommand = null,
            ),
            status,
        )
        val req = take()
        assertEquals("GET", req.method)
        assertEquals("/api/browser/runtime-status", req.path)
        assertEquals("Bearer tthr_test", req.getHeader("Authorization"))
        assertEquals(0L, req.bodySize)
    }

    @Test fun aMissingChromiumCarriesTheInstallCommand() = runBlocking<Unit> {
        server.enqueue(reply(200, missing))
        val status = (browser.status(origin) as SecurityResult.Ok).value
        assertEquals(false, status.usable)
        assertEquals(false, status.binaryPresent)
        assertEquals(true, status.playwrightImportable)
        assertEquals("npx playwright install chromium", status.installCommand)
    }

    @Test fun anUnloadablePlaywrightCarriesItsImportError() = runBlocking<Unit> {
        server.enqueue(reply(200, noPackage))
        val status = (browser.status(origin) as SecurityResult.Ok).value
        assertEquals(false, status.playwrightImportable)
        assertNull(status.playwrightVersion)
        assertNull(status.executablePath)
        assertEquals("Cannot find package 'playwright'", status.importError)
    }

    @Test fun anAnswerWithoutTheRoutesShapeIsNoAnswer() = runBlocking<Unit> {
        server.enqueue(reply(200, """{"error":"login required"}"""))
        assertEquals(SecurityResult.Unavailable(200, origin), browser.status(origin))
    }

    @Test fun theOwnerGradeRefusalIsRecognisedByItsOpening() = runBlocking<Unit> {
        server.enqueue(reply(403, """{"error":"This needs an owner sign-in (password, passkey, the SSO gateway or the paired Tether app)."}"""))
        assertEquals(SecurityResult.OwnerSignInNeeded(origin), browser.status(origin))
    }

    @Test fun anOlderServerWithoutTheRouteIsAQuietRefusal() = runBlocking<Unit> {
        server.enqueue(reply(404, """{"error":"Not found"}"""))
        assertTrue(browser.status(origin) is SecurityResult.Refused)
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "text/plain").setBody("Not Found"))
        assertEquals(SecurityResult.Unavailable(404, origin), browser.status(origin))
    }

    @Test fun theApiGates401IsSignedOut() = runBlocking<Unit> {
        server.enqueue(reply(401, """{"error":"Authentication required."}"""))
        assertEquals(SecurityResult.SignedOut(origin), browser.status(origin))
    }

    @Test fun nothingGoesToAnotherServerThanTheOneDrawn() = runBlocking<Unit> {
        val other = "http://${elsewhere.hostName}:${elsewhere.port}"
        assertEquals(SecurityResult.NotSent(origin), browser.status(other))
        nothingSent()
        nothingSent(elsewhere)
        authority = FilesAuthority.SignedOut
        assertEquals(SecurityResult.SignedOut(), browser.status(origin))
        nothingSent()
    }

    @Test fun aRedirectIsNeverFollowed() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/sso").toString()))
        assertEquals(SecurityResult.Blocked(302, origin), browser.status(origin))
        take()
        nothingSent(elsewhere)
    }

    @Test fun anUnreachableServerIsUnavailableWithNoCode() = runBlocking<Unit> {
        val dead = MockWebServer().also { it.start() }
        val deadOrigin = "http://${dead.hostName}:${dead.port}"
        authority = FilesAuthority.Paired(dead.url("/")) { it.header("Authorization", "Bearer tthr_test") }
        dead.shutdown()
        assertEquals(SecurityResult.Unavailable(null, deadOrigin), browser.status(deadOrigin))
    }

    @Test fun theUnavailableSourceSendsNothing() = runBlocking<Unit> {
        assertEquals(SecurityResult.SignedOut(), BrowserRuntimeSource.Unavailable.status(origin))
        nothingSent()
    }
}
