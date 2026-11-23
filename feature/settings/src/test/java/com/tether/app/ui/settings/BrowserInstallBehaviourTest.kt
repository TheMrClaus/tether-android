package com.tether.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tether.app.client.BrowserInstallJob
import com.tether.app.client.BrowserRuntimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings → Advanced → Browser, the Install Chromium key, through the semantics tree, against the web's
 * BrowserRuntimeStatusRows: the key only where Chromium is missing and the server says it can install; the
 * running state; a finished job turning the section to found; a failure's reason, output and manual command.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BrowserInstallBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Advanced)
    private val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
    private lateinit var c: BrowserRuntimeController

    private fun show(source: BrowserRuntimeSource) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { BrowserRuntimeController(source, ServerFixtures.ORIGIN, scope).also { c = it } }
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                SettingsUnderTest(store.prefs, state, serverSettings = ServerFixtures.binding(), browser = BrowserBinding(controller))
            }
        }
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitUntil(5_000) { !c.loading && c.status != null }
        compose.waitForIdle()
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun exists(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun sectionTexts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out.drop(out.indexOf(BrowserCopy.TITLE)).fold(mutableListOf()) { acc, s -> if (acc.lastOrNull() != s) acc += s; acc }
    }

    private fun installer(status: com.tether.app.client.BrowserRuntimeStatus, job: BrowserInstallJob = InstallFixtures.IDLE) =
        FakeInstaller(ServerFixtures.ORIGIN).status(status).job(job)

    @Test fun theKeyShowsOnlyWhereChromiumIsMissingAndTheServerCanInstall() {
        show(installer(InstallFixtures.INSTALLABLE))
        assertTrue(exists(BrowserTags.InstallKey))
        tag(BrowserTags.InstallKey).performScrollTo().assertIsEnabled()
        val t = sectionTexts()
        assertEquals(BrowserCopy.CAPTION_INSTALLABLE, t[1])
        assertTrue(t.containsAll(listOf(BrowserCopy.INSTALL, BrowserCopy.INSTALL_IDLE, BrowserCopy.BY_HAND, BrowserCopy.COPY)))
        assertTrue(exists(BrowserTags.Copy))
    }

    private fun assertNoKey(status: com.tether.app.client.BrowserRuntimeStatus) {
        val f = FakeInstaller(ServerFixtures.ORIGIN).status(status)
        f.jobs += com.tether.app.client.SecurityResult.Unavailable(404, ServerFixtures.ORIGIN)
        show(f)
        assertFalse(exists(BrowserTags.InstallKey))
        assertFalse(exists(BrowserTags.InstallRow))
        assertFalse(sectionTexts().contains(BrowserCopy.INSTALL))
        if (status.canInstall == null) assertEquals(BrowserCopy.CAPTION, sectionTexts()[1])
    }

    @Test fun noKeyFromAServerOlderThanTheKey() {
        assertNoKey(BrowserFixtures.MISSING)
        val t = sectionTexts()
        assertTrue("the older server keeps the one-line label", t.contains(BrowserCopy.TO_INSTALL))
        assertFalse(t.contains(BrowserCopy.BY_HAND))
    }

    @Test fun noKeyWhenPlaywrightCannotLoad() {
        val status = BrowserFixtures.NO_PACKAGE.copy(canInstall = false)
        assertTrue("the fixture carries the command", status.installCommand != null)
        assertNoKey(status)
        val t = sectionTexts()
        assertTrue("a server that sends canInstall labels the command row by hand", t.contains(BrowserCopy.BY_HAND))
        assertFalse(t.contains(BrowserCopy.TO_INSTALL))
    }

    @Test fun noKeyWhenChromiumIsFound() = assertNoKey(BrowserFixtures.FOUND)

    @Test fun noKeyOnceTetherInstalledIt() = assertNoKey(InstallFixtures.INSTALLED)

    @Test fun aRunningJobShowsProgressAndADisabledKey() {
        show(installer(InstallFixtures.INSTALLABLE, InstallFixtures.RUNNING))
        compose.waitUntil(5_000) { c.install?.running == true }
        compose.waitForIdle()
        tag(BrowserTags.InstallKey).performScrollTo().assertIsNotEnabled()
        val t = sectionTexts()
        assertTrue(t.contains(BrowserCopy.INSTALLING_CAPTION))
        assertTrue(t.contains(BrowserCopy.INSTALLING))
        assertFalse(t.contains(BrowserCopy.INSTALL_IDLE))
        assertTrue("the manual command stays", exists(BrowserTags.Copy))
    }

    @Test fun pressingInstallRunsItAndTheSectionTurnsToFoundInPlace() {
        val f = installer(InstallFixtures.INSTALLABLE).start(true, InstallFixtures.RUNNING)
        f.job(InstallFixtures.RUNNING).job(InstallFixtures.DONE)
        f.status(InstallFixtures.INSTALLED)
        show(f)
        tag(BrowserTags.InstallKey).performScrollTo().performClick()
        compose.waitUntil(5_000) { c.install?.running == true }
        compose.waitUntil(15_000) { c.install?.state == BrowserInstallJob.DONE }
        compose.waitForIdle()
        val t = sectionTexts()
        assertTrue(t.contains(BrowserCopy.READY))
        assertTrue(t.any { it.startsWith("Revision 1187 expected · found at ") })
        assertFalse(exists(BrowserTags.InstallKey))
        assertFalse(exists(BrowserTags.Copy))
        assertEquals(1, f.calls.count { it == "start" })
    }

    @Test fun aFailureShowsTheReasonTheLastOutputAndTheCommandWithTryAgain() {
        val f = installer(InstallFixtures.INSTALLABLE).start(true, InstallFixtures.RUNNING)
        f.job(InstallFixtures.RUNNING).job(InstallFixtures.FAILED)
        f.status(InstallFixtures.INSTALLABLE)
        show(f)
        tag(BrowserTags.InstallKey).performScrollTo().performClick()
        compose.waitUntil(15_000) { c.install?.state == BrowserInstallJob.FAILED }
        compose.waitForIdle()
        val t = sectionTexts()
        assertTrue(t.contains(BrowserCopy.INSTALL_FAILED))
        assertTrue(t.contains(BrowserCopy.TRY_AGAIN))
        val caption = t.first { it.startsWith("The installer exited with code 1.") }
        assertTrue(caption.contains("Error: getaddrinfo ENOTFOUND cdn.playwright.dev"))
        assertTrue(caption.contains("Failed to install browsers"))
        assertTrue(t.contains(BrowserCopy.INSTALL_CAPTION + BR_COMMAND))
        assertTrue(exists(BrowserTags.Copy))
        tag(BrowserTags.InstallKey).assertIsEnabled()
    }

    @Test fun aFinishedJobWithChromiumStillMissingShowsTheFailureView() {
        val f = installer(InstallFixtures.INSTALLABLE).start(true, InstallFixtures.RUNNING)
        f.job(InstallFixtures.DONE)
        f.status(InstallFixtures.INSTALLABLE)
        show(f)
        tag(BrowserTags.InstallKey).performScrollTo().performClick()
        compose.waitUntil(15_000) { c.install?.state == BrowserInstallJob.DONE }
        compose.waitForIdle()
        val t = sectionTexts()
        assertTrue(t.contains(BrowserCopy.INSTALL_FAILED))
        assertTrue(t.contains(BrowserCopy.TRY_AGAIN))
        assertTrue(t.any { it.startsWith("The install finished, but Chromium was still not found at /home/u/") })
        assertTrue(exists(BrowserTags.Copy))
        assertFalse(t.contains(BrowserCopy.READY))
    }

    @Test fun a409ShowsItsMessageUnderTheKey() {
        val f = installer(InstallFixtures.INSTALLABLE)
        f.start(false, InstallFixtures.IDLE, "playwright_missing", "The Playwright package could not be loaded, so there is nothing to install with.")
        show(f)
        tag(BrowserTags.InstallKey).performScrollTo().performClick()
        compose.waitUntil(5_000) { c.installNote.isNotEmpty() }
        compose.waitForIdle()
        assertTrue(sectionTexts().contains(BrowserCopy.INSTALL_IDLE + " The Playwright package could not be loaded, so there is nothing to install with."))
        assertTrue(exists(BrowserTags.InstallKey))
    }
}
