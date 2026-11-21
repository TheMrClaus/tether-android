package com.tether.app.ui.settings

import com.tether.app.client.BrowserInstallJob
import com.tether.app.client.BrowserInstallStart
import com.tether.app.client.BrowserRuntimeSource
import com.tether.app.client.BrowserRuntimeStatus
import com.tether.app.client.SecurityResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

object InstallFixtures {
    val INSTALLABLE = BrowserFixtures.MISSING.copy(canInstall = true, location = "default")
    val INSTALLED = BrowserFixtures.FOUND.copy(canInstall = false, location = "state")
    val RUNNING = BrowserInstallJob(BrowserInstallJob.RUNNING, 1L, null, null, null, listOf("Downloading Chromium 140.0 (playwright build v1187)"))
    val DONE = BrowserInstallJob(BrowserInstallJob.DONE, 1L, 2L, 0, null, listOf("Chromium downloaded"))
    val FAILED = BrowserInstallJob(
        BrowserInstallJob.FAILED, 1L, 2L, 1, "The installer exited with code 1.",
        listOf("Downloading Chromium 140.0 (playwright build v1187)", "Error: getaddrinfo ENOTFOUND cdn.playwright.dev", "Failed to install browsers"),
    )
    val LONG_OUTPUT = BrowserInstallJob(
        BrowserInstallJob.FAILED, 1L, 2L, 1, "The installer exited with code 1.",
        listOf(
            "Downloading Chromium 140.0 (playwright build v1187) from the mirror",
            "Error: getaddrinfo ENOTFOUND cdn.playwright.dev",
            "Failed to install browsers",
            "Error: Failed to download Chromium 140.0 (playwright build v1187), caused by Error: connect ETIMEDOUT 203.0.113.7:443 while fetching the archive",
            "    at ClientRequest.<anonymous> (/srv/tether/node_modules/playwright-core/lib/server/registry/browserFetcher.js:112:19)",
            "    at Object.onceWrapper (node:events:633:26)",
            "    at ClientRequest.emit (node:events:518:28)",
            "    at TLSSocket.socketErrorListener (node:_http_client:495:9)",
            "    at TLSSocket.emit (node:events:518:28)",
            "    at emitErrorNT (node:internal/streams/destroy:170:8)",
            "    at process.processTicksAndRejections (node:internal/process/task_queues:82:21)",
            "WARNING: /var/lib/tether/state/browsers/chromium-1187/chrome-linux/chrome-headless-shell-archive-with-a-very-long-name-and-no-break-points.zip.partial",
            "Retrying in 5 seconds",
            "Giving up after 3 attempts",
        ),
    )
    val IDLE = BrowserInstallJob(BrowserInstallJob.IDLE, null, null, null, null, emptyList())
}

/** A scripted source: status, start and job answers each come from their own queue; the last job answer repeats. */
class FakeInstaller(private val origin: String = BR_ORIGIN) : BrowserRuntimeSource {
    val statuses = java.util.concurrent.ConcurrentLinkedDeque<SecurityResult<BrowserRuntimeStatus>>()
    val starts = java.util.concurrent.ConcurrentLinkedDeque<SecurityResult<BrowserInstallStart>>()
    val jobs = java.util.concurrent.ConcurrentLinkedDeque<SecurityResult<BrowserInstallJob>>()
    val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var lastJob: SecurityResult<BrowserInstallJob> = SecurityResult.Ok(InstallFixtures.IDLE, origin, null)

    fun status(s: BrowserRuntimeStatus) = apply { statuses += SecurityResult.Ok(s, origin, null) }
    fun job(j: BrowserInstallJob) = apply { jobs += SecurityResult.Ok(j, origin, null) }
    fun start(ok: Boolean, job: BrowserInstallJob?, code: String? = null, message: String? = null) = apply { starts += SecurityResult.Ok(BrowserInstallStart(ok, code, message, job), origin, null) }

    /** When set, the next status read waits for it (to look at the screen while the re-read is in flight). */
    @Volatile var statusGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun status(origin: String): SecurityResult<BrowserRuntimeStatus> {
        calls += "status"
        statusGate?.let { statusGate = null; it.await() }
        return statuses.poll() ?: SecurityResult.Ok(BrowserFixtures.FOUND, origin, null)
    }

    override suspend fun startInstall(origin: String): SecurityResult<BrowserInstallStart> {
        calls += "start"
        return starts.poll() ?: SecurityResult.Unavailable(404, origin)
    }

    override suspend fun installJob(origin: String): SecurityResult<BrowserInstallJob> {
        calls += "job"
        lastJob = jobs.poll() ?: lastJob
        return lastJob
    }
}

/** The install key's state, against the web's BrowserRuntimeSection (settings-dialog.tsx 1384-1447). */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserInstallControllerTest {
    private fun TestScope.controller(source: BrowserRuntimeSource) = BrowserRuntimeController(source, BR_ORIGIN, backgroundScope)

    @Test fun showingTheSectionReadsTheStatusAndTheJobAndInstallsNothing() = runTest {
        val s = FakeInstaller().status(InstallFixtures.INSTALLABLE)
        val c = controller(s)
        c.shown()
        runCurrent()
        assertEquals(InstallFixtures.INSTALLABLE, c.status)
        assertEquals(InstallFixtures.IDLE, c.install)
        assertFalse("start" in s.calls)
    }

    @Test fun aPressStartsTheJobAndTheSpinnerStateFollowsTheServer() = runTest {
        val s = FakeInstaller().status(InstallFixtures.INSTALLABLE).start(true, InstallFixtures.RUNNING)
        val c = controller(s)
        c.shown()
        runCurrent()
        c.startInstall()
        runCurrent()
        assertTrue(c.install?.running == true)
        assertEquals("", c.installNote)
        assertEquals(1, s.calls.count { it == "start" })
    }

    @Test fun aJobThatEndsIsShownOnlyAfterTheStatusIsReadAgain() = runTest {
        val s = FakeInstaller().status(InstallFixtures.INSTALLABLE).start(true, InstallFixtures.RUNNING)
        s.job(InstallFixtures.IDLE).job(InstallFixtures.RUNNING).job(InstallFixtures.DONE)
        s.status(InstallFixtures.INSTALLED)
        val c = controller(s)
        c.shown()
        runCurrent()
        c.startInstall()
        runCurrent()
        assertTrue(c.install?.running == true)
        advanceTimeBy(1_400)
        runCurrent()
        assertEquals("not yet polled", 1, s.calls.count { it == "job" })
        advanceTimeBy(200)
        runCurrent()
        assertTrue(c.install?.running == true)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        s.statusGate = gate
        advanceTimeBy(1_500)
        runCurrent()
        // the finished job has been read, the status is being read again: the screen still shows the running state
        assertEquals(listOf("job", "status"), s.calls.takeLast(2))
        assertTrue(c.install?.running == true)
        gate.complete(Unit)
        runCurrent()
        assertEquals(InstallFixtures.DONE, c.install)
        assertEquals(InstallFixtures.INSTALLED, c.status)
        assertFalse(BrowserCopy.installFailed(c.status!!, c.install))
    }

    @Test fun aFinishedJobWithChromiumStillMissingIsAFailure() = runTest {
        val s = FakeInstaller().status(InstallFixtures.INSTALLABLE).start(true, InstallFixtures.RUNNING)
        s.job(InstallFixtures.IDLE).job(InstallFixtures.DONE)
        s.status(InstallFixtures.INSTALLABLE)
        val c = controller(s)
        c.shown()
        runCurrent()
        c.startInstall()
        runCurrent()
        advanceTimeBy(1_600)
        runCurrent()
        assertEquals(InstallFixtures.DONE, c.install)
        assertTrue(BrowserCopy.installFailed(c.status!!, c.install))
        assertTrue(BrowserCopy.installCaption(c.status!!, c.install, "").startsWith("The install finished, but Chromium was still not found at /home/u/"))
    }

    @Test fun aFailedJobGivesItsReasonAndTheLastOutputLines() {
        val s = InstallFixtures.INSTALLABLE
        assertTrue(BrowserCopy.installFailed(s, InstallFixtures.FAILED))
        assertEquals("The installer exited with code 1.", BrowserCopy.installCaption(s, InstallFixtures.FAILED, ""))
        assertEquals(InstallFixtures.FAILED.output, BrowserCopy.outputTail(InstallFixtures.FAILED))
        val long = InstallFixtures.FAILED.copy(output = (1..30).map { "line $it" })
        assertEquals((19..30).map { "line $it" }, BrowserCopy.outputTail(long))
        assertEquals(BrowserCopy.INSTALL_DID_NOT_FINISH, BrowserCopy.installCaption(s, InstallFixtures.FAILED.copy(reason = null), ""))
    }

    @Test fun aStartThatAnswersWithAnEndedJobReadsTheStatusAtOnce() = runTest {
        val s = FakeInstaller().status(InstallFixtures.INSTALLABLE).start(true, InstallFixtures.DONE)
        s.status(InstallFixtures.INSTALLED)
        val c = controller(s)
        c.shown()
        runCurrent()
        c.startInstall()
        runCurrent()
        assertEquals(InstallFixtures.DONE, c.install)
        assertEquals(InstallFixtures.INSTALLED, c.status)
        assertEquals(2, s.calls.count { it == "status" })
        c.dispose()
    }

    @Test fun a409ShowsTheServersMessageAndKeepsTheRunningJob() = runTest {
        val s = FakeInstaller().status(InstallFixtures.INSTALLABLE)
        s.start(false, InstallFixtures.RUNNING, "already_installing", "Chromium is already being installed.")
        val c = controller(s)
        c.shown()
        runCurrent()
        c.startInstall()
        runCurrent()
        assertEquals("Chromium is already being installed.", c.installNote)
        assertTrue(c.install?.running == true)
        s.start(false, InstallFixtures.IDLE, "playwright_missing", "The Playwright package could not be loaded, so there is nothing to install with.")
        c.dispose()
    }

    @Test fun aRefusedOrOlderServersStartIsANoteNeverASpinner() = runTest {
        val cases = listOf<Pair<SecurityResult<BrowserInstallStart>, String>>(
            SecurityResult.OwnerSignInNeeded(BR_ORIGIN) to "The install could not start (HTTP 403).",
            SecurityResult.Refused(404, "Not found", BR_ORIGIN) to "The install could not start (HTTP 404).",
            SecurityResult.Unavailable(null, BR_ORIGIN) to BrowserCopy.NOT_STARTED,
            SecurityResult.NotSent(BR_ORIGIN) to BrowserCopy.NOT_STARTED,
        )
        for ((answer, note) in cases) {
            val s = FakeInstaller().status(InstallFixtures.INSTALLABLE)
            s.starts += answer
            val c = controller(s)
            c.shown()
            runCurrent()
            c.startInstall()
            runCurrent()
            assertEquals("$answer", note, c.installNote)
            assertFalse("$answer", c.install?.running == true)
            c.dispose()
        }
    }

    @Test fun aServerWithoutTheInstallRouteLeavesNoJobAndNoSpinner() = runTest {
        val s = FakeInstaller().status(BrowserFixtures.MISSING)
        s.jobs += SecurityResult.Unavailable(404, BR_ORIGIN)
        val c = controller(s)
        c.shown()
        runCurrent()
        assertNull(c.install)
        assertNull(c.status?.canInstall)
        advanceTimeBy(10_000)
        assertEquals(1, s.calls.count { it == "job" })
    }

    @Test fun aRefusalWhilePollingEndsTheSpinner() = runTest {
        val s = FakeInstaller().status(InstallFixtures.INSTALLABLE).job(InstallFixtures.RUNNING)
        s.jobs += SecurityResult.OwnerSignInNeeded(BR_ORIGIN)
        val c = controller(s)
        c.shown()
        runCurrent()
        assertTrue(c.install?.running == true)
        advanceTimeBy(1_600)
        runCurrent()
        assertNull(c.install)
        val polls = s.calls.count { it == "job" }
        advanceTimeBy(10_000)
        assertEquals(polls, s.calls.count { it == "job" })
    }

    @Test fun aJobAlreadyRunningWhenTheSectionOpensIsPolledToTheEnd() = runTest {
        val s = FakeInstaller().status(InstallFixtures.INSTALLABLE).job(InstallFixtures.RUNNING).job(InstallFixtures.DONE)
        s.status(InstallFixtures.INSTALLED)
        val c = controller(s)
        c.shown()
        runCurrent()
        assertTrue(c.install?.running == true)
        advanceTimeBy(1_600)
        runCurrent()
        assertEquals(InstallFixtures.INSTALLED, c.status)
        assertEquals(InstallFixtures.DONE, c.install)
    }

    @Test fun theWordsAreTheWebs() {
        assertEquals("Install Chromium", BrowserCopy.INSTALL)
        assertEquals("Downloads Chromium into Tether's state folder, as the service user. Nothing installs until you press this.", BrowserCopy.INSTALL_IDLE)
        assertEquals("Installing Chromium on the server. This can take a few minutes; you can leave this page open.", BrowserCopy.INSTALLING_CAPTION)
        assertEquals("Installing…", BrowserCopy.INSTALLING)
        assertEquals("Install failed", BrowserCopy.INSTALL_FAILED)
        assertEquals("Try again", BrowserCopy.TRY_AGAIN)
        assertEquals("Or install by hand", BrowserCopy.BY_HAND)
        assertEquals("Whether the in-console browser can open on this server. Chromium installs only when you press Install Chromium.", BrowserCopy.CAPTION_INSTALLABLE)
    }
}
