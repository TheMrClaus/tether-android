package com.tether.app.ui.shell

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.text.COPY_NOTICE_TAG
import com.tether.app.ui.text.COPY_RAW_TAG
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-28i through MainShell: the server's working directory and the session id are never copied
 * raw silently. The copy carries every hidden control as its visible token and a notice offers
 * "Copy raw" (the only raw path); the links popover draws the directory as code; the header draws
 * the session's title by the label rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w600dp-h1000dp-mdpi")
class MainShellSafeCopyTest {
    @get:Rule val rule = createComposeRule()

    private companion object {
        const val RLO = "\u202E"
        const val PDF = "\u202C"
        const val CWD = "/srv/app$RLO/lanif$PDF"
        const val ID = "s1${RLO}x"
        fun tok(cp: Int) = "\u2060\u27E8U+%04X\u27E9".format(cp)
        fun vis(cp: Int) = "\u27E8U+%04X\u27E9".format(cp)
    }

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(600, 1000)
    }

    private val session = AgentSession(id = ID, provider = "claude", name = "Fix the ${RLO}lanif$PDF bug", cwd = CWD, status = "active", startedAt = 1, updatedAt = 1)

    private val tree = foldTree(
        freshTree(),
        ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
        ev("user_message_accepted", "t1", ts = 1) { put("text", "Keep going.") },
    )

    private fun host() {
        val client = ShellConsentClient().also { it.show(session, tree) }
        val vm = TetherViewModel(client)
        vm.selectSession(ID)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } }
        }
        rule.waitForIdle()
    }

    private fun clip(): String? =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    private fun spoken(): List<String> = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { n ->
        n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } +
            n.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty()
    }

    private fun openLinks() {
        rule.onNodeWithTag(ShellTags.LinksKey).performClick()
        rule.waitForIdle()
    }

    @Test fun theWorkingDirectoryIsCopiedVisiblyAndOnlyCopyRawCarriesTheSource() {
        host()
        // The header's title is a label: the override is dropped, the words keep their stored order.
        assertTrue(spoken().toString(), spoken().contains("Fix the lanif bug"))
        openLinks()
        assertTrue("the popover's path is code: ${spoken()}", spoken().any { it.contains("app${tok(0x202E)}/lanif${tok(0x202C)}") })
        rule.onNodeWithContentDescription("Copy working directory").performClick()
        rule.waitForIdle()
        val copied = checkNotNull(clip())
        assertEquals("/srv/app${vis(0x202E)}/lanif${vis(0x202C)}", copied)
        assertFalse(copied.contains(RLO) || copied.contains(PDF) || copied.contains("\u2060"))
        rule.onNodeWithTag(COPY_NOTICE_TAG).assertIsDisplayed()
        assertTrue(spoken().contains("2 hidden control characters copied as \u27E8U+\u2026\u27E9"))
        rule.onNodeWithTag(COPY_RAW_TAG).performClick()
        rule.waitForIdle()
        assertEquals(CWD, clip())
    }

    @Test fun theSessionIdIsCopiedVisiblyToo() {
        host()
        openLinks()
        rule.onNodeWithContentDescription("Copy this session's Tether id").performClick()
        rule.waitForIdle()
        assertEquals("s1${vis(0x202E)}x", clip())
        rule.onNodeWithTag(COPY_RAW_TAG).performClick()
        rule.waitForIdle()
        assertEquals(ID, clip())
    }

    @Test fun aCleanDirectoryCopiesExactlyWithNoNotice() {
        val clean = session.copy(cwd = "/srv/app/final")
        val client = ShellConsentClient().also { it.show(clean, tree) }
        val vm = TetherViewModel(client)
        vm.selectSession(ID)
        rule.setContent {
            TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, UiPrefs(ApplicationProvider.getApplicationContext())) } }
        }
        rule.waitForIdle()
        openLinks()
        rule.onNodeWithContentDescription("Copy working directory").performClick()
        rule.waitForIdle()
        assertEquals("/srv/app/final", clip())
        rule.onNodeWithTag(COPY_NOTICE_TAG).assertDoesNotExist()
    }
}
