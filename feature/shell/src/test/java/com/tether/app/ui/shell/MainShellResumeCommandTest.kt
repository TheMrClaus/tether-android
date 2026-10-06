package com.tether.app.ui.shell

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.text.COPY_NOTICE_TAG
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.62 (protocol v52, workspace-header.tsx:56-70 at tether 29537e0): the "Copy resume command"
 * control in the session-links popover. Present only when the session carries `resumeCommand`; copies
 * that exact string; its legend and accessible name flip to "Copied resume command" after the copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class MainShellResumeCommandTest {
    @get:Rule val rule = createComposeRule()

    private companion object {
        const val ID = "s1"
        const val COMMAND = "cd -- /srv/app && CLAUDE_CONFIG_DIR=/home/x/.claude claude --resume 3f2a9c1e-77aa-4b0d-9d21-5c6f0e8b1a42"
        const val IDLE_NAME = "Copy the command to resume this session in a terminal"
        const val COPIED = "Copied resume command"
    }

    private fun window(w: Int, h: Int) = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(w, h)
    }

    private fun session(resume: String?) =
        AgentSession(id = ID, provider = "claude", name = "Resume me", cwd = "/srv/app", status = "ready", startedAt = 1, updatedAt = 1, resumeCommand = resume)

    private fun host(resume: String?, w: Int = 412, h: Int = 915) {
        val client = ShellConsentClient().also { it.show(session(resume), freshTree()) }
        val vm = TetherViewModel(client)
        vm.selectSession(ID)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window(w, h)) { MainShell(vm, prefs) } }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.LinksKey).performClick()
        rule.waitForIdle()
    }

    private fun clip(): String? =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    @Test fun shownWhenTheSessionCarriesAResumeCommand() {
        host(COMMAND)
        rule.onNodeWithTag(ShellTags.ResumeCommandKey).assertIsDisplayed()
        rule.onNodeWithContentDescription(IDLE_NAME).assertIsDisplayed()
        // The phone's legend (workspace-header.tsx:60-62); the working directory and the id keep their keys.
        rule.onNodeWithText("Tap to copy resume command", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Copy working directory").assertIsDisplayed()
        rule.onNodeWithContentDescription("Copy this session's Tether id").assertIsDisplayed()
    }

    @Test fun hiddenWhenTheSessionHasNoResumeCommand() {
        host(null)
        rule.onNodeWithTag(ShellTags.ResumeCommandKey).assertDoesNotExist()
        rule.onNodeWithContentDescription(IDLE_NAME).assertDoesNotExist()
        rule.onNodeWithContentDescription("Copy working directory").assertIsDisplayed()
    }

    @Test fun tappingCopiesTheExactCommandAndTheLabelFlipsThenReverts() {
        rule.mainClock.autoAdvance = true
        host(COMMAND)
        rule.onNodeWithContentDescription(IDLE_NAME).performClick()
        rule.waitForIdle()
        assertEquals(COMMAND, clip())
        rule.onNodeWithTag(COPY_NOTICE_TAG).assertDoesNotExist()
        rule.onNodeWithContentDescription(COPIED).assertIsDisplayed()
        rule.onNodeWithText(COPIED, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription(IDLE_NAME).assertDoesNotExist()
        // 1.5 s later it is the idle control again (dashboard.tsx copyResumeCommand).
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        rule.onNodeWithContentDescription(IDLE_NAME).assertIsDisplayed()
    }

    @Test fun theTooltipNamesTheExactCommandAndTheEndFirstNote() {
        val title = resumeCommandTitle(COMMAND)
        assertTrue(title, title.startsWith("Copies:\n$COMMAND\n\nEnd this session first"))
        assertTrue(title, title.endsWith("two live turns would share one transcript and working directory."))
    }
}


/** From 48rem the legend swaps to "Copy resume command" (globals.css 3486-3494). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class MainShellResumeCommandExpandedTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theLegendReadsCopyResumeCommand() {
        val session = AgentSession(id = "s1", provider = "claude", name = "Resume me", cwd = "/srv/app", status = "ready", startedAt = 1, updatedAt = 1, resumeCommand = "claude --resume x")
        val client = ShellConsentClient().also { it.show(session, freshTree()) }
        val vm = TetherViewModel(client)
        vm.selectSession("s1")
        val info = object : WindowInfo {
            override val isWindowFocused: Boolean get() = true
            override val containerSize: IntSize get() = IntSize(1280, 800)
        }
        rule.setContent {
            TetherTheme { CompositionLocalProvider(LocalWindowInfo provides info) { MainShell(vm, UiPrefs(ApplicationProvider.getApplicationContext())) } }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.LinksKey).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Copy resume command", useUnmergedTree = true).assertIsDisplayed()
    }
}
