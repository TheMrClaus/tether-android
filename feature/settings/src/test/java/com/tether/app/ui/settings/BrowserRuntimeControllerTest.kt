package com.tether.app.ui.settings

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

const val BR_ORIGIN = "https://tether.example.test"
const val BR_COMMAND = "npx playwright install chromium"
private const val BR_PATH = "/home/u/.cache/ms-playwright/chromium-1187/chrome-linux/chrome"

/** The two states the server reports (lib/browser-runtime.ts `browserRuntimeReport`). */
object BrowserFixtures {
    val FOUND = BrowserRuntimeStatus(true, true, "1.55.0", "1187", BR_PATH, true, null, null)
    val MISSING = BrowserRuntimeStatus(false, true, "1.55.0", "1187", BR_PATH, false, null, BR_COMMAND)
    val NO_PACKAGE = BrowserRuntimeStatus(false, false, null, null, null, false, "Cannot find package 'playwright'", BR_COMMAND)
}

/** A scripted source: each call takes the next answer queued (or the found one). */
class FakeBrowser : BrowserRuntimeSource {
    val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
    val answers = java.util.concurrent.ConcurrentLinkedDeque<SecurityResult<BrowserRuntimeStatus>>()

    override suspend fun status(origin: String): SecurityResult<BrowserRuntimeStatus> {
        calls += origin
        return answers.poll() ?: SecurityResult.Ok(BrowserFixtures.FOUND, origin, null)
    }
}

/** BrowserRuntimeController against the web's BrowserRuntimeSection: the read when shown, Refresh, and every failure as one quiet line. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserRuntimeControllerTest {
    private fun TestScope.controller(source: FakeBrowser, origin: String? = BR_ORIGIN) = BrowserRuntimeController(source, origin, backgroundScope)

    @Test fun theStatusIsReadWhenTheSectionIsShown() = runTest {
        val b = FakeBrowser()
        b.answers += SecurityResult.Ok(BrowserFixtures.MISSING, BR_ORIGIN, null)
        val c = controller(b)
        assertTrue(c.loading)
        assertTrue(b.calls.isEmpty())
        c.shown()
        runCurrent()
        assertFalse(c.loading)
        assertEquals(BrowserFixtures.MISSING, c.status)
        assertNull(c.error)
        assertEquals(listOf(BR_ORIGIN), b.calls)
    }

    @Test fun refreshReadsAgain() = runTest {
        val b = FakeBrowser()
        b.answers += SecurityResult.Ok(BrowserFixtures.MISSING, BR_ORIGIN, null)
        val c = controller(b)
        c.shown()
        runCurrent()
        c.load()
        assertTrue(c.loading)
        runCurrent()
        assertEquals(BrowserFixtures.FOUND, c.status)
        assertEquals(2, b.calls.size)
    }

    @Test fun everyRefusalIsOneQuietLineNeverAStuckSpinner() = runTest {
        val refusals = listOf<SecurityResult<BrowserRuntimeStatus>>(
            SecurityResult.OwnerSignInNeeded(BR_ORIGIN),
            SecurityResult.Refused(404, "Not found", BR_ORIGIN),
            SecurityResult.Unavailable(404, BR_ORIGIN),
            SecurityResult.Unavailable(null, BR_ORIGIN),
            SecurityResult.Blocked(302, BR_ORIGIN),
            SecurityResult.LocalNetworkBlocked,
            SecurityResult.NotSent(BR_ORIGIN),
        )
        for (r in refusals) {
            val b = FakeBrowser()
            b.answers += r
            val c = controller(b)
            c.shown()
            runCurrent()
            assertFalse("$r", c.loading)
            assertNull(c.status)
            assertEquals("$r", BrowserCopy.FAILED, c.error)
        }
    }

    @Test fun aSourceThatThrowsIsTheSameQuietLine() = runTest {
        val c = BrowserRuntimeController(object : BrowserRuntimeSource {
            override suspend fun status(origin: String): SecurityResult<BrowserRuntimeStatus> = error("boom")
        }, BR_ORIGIN, backgroundScope)
        c.shown()
        runCurrent()
        assertFalse(c.loading)
        assertEquals(BrowserCopy.FAILED, c.error)
    }

    @Test fun aFailedRefreshKeepsTheLastStatusAndAGoodOneClearsTheError() = runTest {
        val b = FakeBrowser()
        b.answers += SecurityResult.Ok(BrowserFixtures.MISSING, BR_ORIGIN, null)
        b.answers += SecurityResult.Unavailable(null, BR_ORIGIN)
        val c = controller(b)
        c.shown()
        runCurrent()
        c.load()
        runCurrent()
        assertEquals(BrowserFixtures.MISSING, c.status)
        assertEquals(BrowserCopy.FAILED, c.error)
        c.load()
        runCurrent()
        assertEquals(BrowserFixtures.FOUND, c.status)
        assertNull(c.error)
    }

    @Test fun signedOutReadsNothingAndSaysSo() = runTest {
        val b = FakeBrowser()
        val c = controller(b, origin = null)
        c.shown()
        c.load()
        advanceTimeBy(10_000)
        assertEquals(BrowserCopy.SIGNED_OUT, c.error)
        assertTrue(b.calls.isEmpty())
    }

    @Test fun anAnswerThatLandsAfterDisposeChangesNothing() = runTest {
        val b = FakeBrowser()
        val c = controller(b)
        c.shown()
        c.dispose()
        runCurrent()
        assertNull(c.status)
    }

    @Test fun theWordsAreTheWebs() {
        assertEquals("Ready: Playwright and its Chromium are installed.", BrowserCopy.headline(BrowserFixtures.FOUND))
        assertEquals("Not ready: the browser (Chromium) is missing.", BrowserCopy.headline(BrowserFixtures.MISSING))
        assertEquals("Not ready: the Playwright package could not be loaded.", BrowserCopy.headline(BrowserFixtures.NO_PACKAGE))
        assertEquals("Loaded, version 1.55.0", BrowserCopy.playwrightLine(BrowserFixtures.FOUND))
        assertEquals("Not loaded: Cannot find package 'playwright'", BrowserCopy.playwrightLine(BrowserFixtures.NO_PACKAGE))
        assertEquals("Revision 1187 expected · found at ", BrowserCopy.chromiumLine(BrowserFixtures.FOUND))
        assertEquals("Revision 1187 expected · not found at ", BrowserCopy.chromiumLine(BrowserFixtures.MISSING))
        assertEquals("Revision unknown", BrowserCopy.chromiumLine(BrowserFixtures.NO_PACKAGE))
    }

    @Test fun theSuffixShowsOnlyForTheBinaryTetherInstalled() {
        assertTrue(BrowserCopy.installedByTether(InstallFixtures.INSTALLED))
        assertFalse(BrowserCopy.installedByTether(BrowserFixtures.FOUND.copy(location = "default")))
        assertFalse(BrowserCopy.installedByTether(BrowserFixtures.FOUND.copy(location = null)))
        assertFalse(BrowserCopy.installedByTether(BrowserFixtures.MISSING.copy(location = "state")))
        assertEquals(" · installed by Tether", BrowserCopy.INSTALLED_BY_TETHER)
    }

    @Test fun anOutputLineKeepsItsLeadingIndent() {
        val job = InstallFixtures.FAILED.copy(output = listOf("Error: boom", "    at Object.run (/a/b.js:1:2)", "\tat next", "plain  words"))
        assertEquals(listOf("Error: boom", "    at Object.run (/a/b.js:1:2)", "    at next", "plain words"), BrowserCopy.outputTail(job))
    }
}
