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
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tether.app.client.BrowserRuntimeSource
import com.tether.app.client.SecurityResult
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
 * Settings → Advanced → Browser through the semantics tree, against the web's BrowserRuntimeSection:
 * the rows of each state in the web's order and words; the install command, and its Copy key, only
 * while something is missing; Refresh reads again; a refusal or an older server is one quiet line
 * with Refresh, never a stuck "Checking…".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BrowserRuntimeBehaviourTest {
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
        compose.waitUntil(5_000) { !c.loading }
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

    private fun fake(vararg answers: com.tether.app.client.BrowserRuntimeStatus) = FakeBrowser().also { b ->
        answers.forEach { b.answers += SecurityResult.Ok(it, ServerFixtures.ORIGIN, null) }
    }

    @Test fun aFoundRuntimeDrawsReadyAndNoCommand() {
        show(fake(BrowserFixtures.FOUND))
        assertEquals(
            listOf(
                BrowserCopy.TITLE, BrowserCopy.CAPTION,
                BrowserCopy.STATUS, BrowserCopy.READY, BrowserCopy.REFRESH,
                BrowserCopy.PLAYWRIGHT, "Loaded, version 1.55.0",
                BrowserCopy.CHROMIUM, "Revision 1187 expected · found at /home/u/.cache/ms-playwright/chromium-1187/chrome-linux/chrome",
            ),
            sectionTexts(),
        )
        assertFalse(exists(BrowserTags.Install))
        assertFalse(exists(BrowserTags.Copy))
        assertFalse(sectionTexts().any { it.contains(BR_COMMAND) })
    }

    @Test fun aMissingChromiumDrawsWhyAndTheOneCopyableCommand() {
        show(fake(BrowserFixtures.MISSING))
        assertEquals(
            listOf(
                BrowserCopy.TITLE, BrowserCopy.CAPTION,
                BrowserCopy.STATUS, BrowserCopy.CHROMIUM_MISSING, BrowserCopy.REFRESH,
                BrowserCopy.PLAYWRIGHT, "Loaded, version 1.55.0",
                BrowserCopy.CHROMIUM, "Revision 1187 expected · not found at /home/u/.cache/ms-playwright/chromium-1187/chrome-linux/chrome",
                BrowserCopy.TO_INSTALL, BrowserCopy.INSTALL_CAPTION + BR_COMMAND, BrowserCopy.COPY,
            ),
            sectionTexts(),
        )
        tag(BrowserTags.Copy).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { sectionTexts().contains(BrowserCopy.COPIED) }
    }

    @Test fun anUnloadablePlaywrightSaysSoWithItsReason() {
        show(fake(BrowserFixtures.NO_PACKAGE))
        val t = sectionTexts()
        assertTrue(t.contains(BrowserCopy.PLAYWRIGHT_MISSING))
        assertTrue(t.contains("Not loaded: Cannot find package 'playwright'"))
        assertTrue(t.contains("Revision unknown"))
        assertTrue(exists(BrowserTags.Copy))
    }

    @Test fun refreshReadsAgainAndTheCommandGoesWhenTheBrowserIsThere() {
        val b = fake(BrowserFixtures.MISSING, BrowserFixtures.FOUND)
        show(b)
        assertTrue(exists(BrowserTags.Copy))
        tag(BrowserTags.Refresh).performScrollTo().performClick()
        compose.waitUntil(5_000) { b.calls.size == 2 && !c.loading }
        compose.waitForIdle()
        assertTrue(sectionTexts().contains(BrowserCopy.READY))
        assertFalse(exists(BrowserTags.Copy))
    }

    @Test fun aRefusalOrAnOlderServerIsOneQuietLineWithRefresh() {
        val b = FakeBrowser()
        b.answers += SecurityResult.OwnerSignInNeeded(ServerFixtures.ORIGIN)
        b.answers += SecurityResult.Refused(404, "Not found", ServerFixtures.ORIGIN)
        show(b)
        assertEquals(listOf(BrowserCopy.TITLE, BrowserCopy.CAPTION, BrowserCopy.STATUS, BrowserCopy.FAILED, BrowserCopy.REFRESH), sectionTexts())
        assertFalse(exists(BrowserTags.Copy))
        tag(BrowserTags.Refresh).performScrollTo().performClick()
        compose.waitUntil(5_000) { b.calls.size == 2 && !c.loading }
        compose.waitForIdle()
        assertTrue(sectionTexts().contains(BrowserCopy.FAILED))
    }
}
