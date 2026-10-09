package com.tether.app.ui.log

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.tether.app.crash.CrashRecord
import com.tether.app.crash.CrashRecordStore
import com.tether.app.crash.ProcessExit
import com.tether.app.ui.statusline.screenshots.choiceFor
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** What the tests in this file share: the dialog frame with a crash and exits, and reads of what it drew. */
internal class CrashShow(val rule: ComposeContentTestRule) {
    val events = mutableListOf<String>()

    fun show(
        crash: CrashRecord? = LogFixtures.crash,
        exits: List<ProcessExit> = LogFixtures.exits,
        rtl: Boolean = false,
        skin: TetherSkin = TetherSkin.StudioDark,
    ) {
        rule.setContent {
            TetherTheme(choiceFor(skin)) {
                CompositionLocalProvider(
                    LocalReducedMotion provides true,
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    LogDialogFrame(
                        entries = LogFixtures.mixed,
                        sessions = LogFixtures.sessions,
                        state = LogDialogState(stats = LogFixtures.stats),
                        onRefresh = {},
                        onClose = {},
                        locale = LogFixtures.locale,
                        zone = LogFixtures.zone,
                        crash = crash,
                        exits = exits,
                        onClearCrash = { events += "clear" },
                    )
                }
            }
        }
    }

    fun scrollTo(tag: String) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
    }

    fun textOf(tag: String): String =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    fun direction(node: androidx.compose.ui.test.SemanticsNodeInteraction): ResolvedTextDirection {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first().multiParagraph.getParagraphDirection(0)
    }

    fun heading(text: String) = hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    fun clipboardText(): String? {
        val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        return manager.primaryClip?.getItemAt(0)?.text?.toString()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class CrashSectionTest {
    @get:Rule val rule = createComposeRule()
    private val s by lazy { CrashShow(rule) }

    @Test
    fun theSectionIsAbsentWithNoRecordAndNoExits() {
        s.show(crash = null, exits = emptyList())
        for (tag in listOf(LogDialogTags.Crash, LogDialogTags.Exits, LogDialogTags.CrashEnd, LogDialogTags.CrashCopy, LogDialogTags.CrashClear)) {
            assertEquals(tag, 0, rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size)
        }
        assertEquals(0, rule.onAllNodes(s.heading("Last crash")).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(s.heading("Recent exits")).fetchSemanticsNodes().size)
        // the existing body is there, and nothing says "no crash"
        rule.onNodeWithTag(LogDialogTags.Stats).assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(hasText("no crash", substring = true, ignoreCase = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun theRecordShowsItsHeadMetaSummaryAndTheFirstEightLines() {
        s.show()
        rule.onNode(s.heading("Last crash")).assertIsDisplayed()
        rule.onNodeWithTag(LogDialogTags.CrashCopy).assertIsDisplayed()
        rule.onNodeWithTag(LogDialogTags.CrashClear).assertIsDisplayed()
        val meta = rule.onNodeWithTag(LogDialogTags.CrashMeta).fetchSemanticsNode()
        val described = meta.children.map { it.config[SemanticsProperties.ContentDescription].single() }
        assertEquals(listOf("When: Dec 31, 2025, 11:00:04 PM", "App version: 0.6.0 (16)", "Android: 16 (API 36)", "Thread: main"), described)
        assertEquals(
            "IllegalStateException: Synthetic failure while restoring the session list after resume",
            s.textOf(LogDialogTags.CrashException),
        )
        val stack = s.textOf(LogDialogTags.crashStack(0))
        assertEquals(LogFixtures.crash.stack.lines().take(8).map { it.replace("\t", "  ") }, stack.lines())
        assertTrue(stack.contains("step07"))
        assertFalse(stack.contains("step08"))
        assertEquals(0, rule.onAllNodesWithTag(LogDialogTags.crashStack(1)).fetchSemanticsNodes().size)
    }

    @Test
    fun theToggleOpensEveryChunkAndShowLessReturnsToTheHead() {
        s.show()
        s.scrollTo(LogDialogTags.CrashToggle)
        rule.onNodeWithContentDescription("Show 34 more lines").assertExists()
        rule.onNodeWithTag(LogDialogTags.CrashToggle).performClick()
        rule.waitForIdle()
        s.scrollTo(LogDialogTags.crashStack(1))
        assertTrue(s.textOf(LogDialogTags.crashStack(1)).endsWith("... 12 more"))
        s.scrollTo(LogDialogTags.CrashToggle)
        rule.onNodeWithContentDescription("Show less").assertExists()
        rule.onNodeWithTag(LogDialogTags.CrashToggle).performClick()
        rule.waitForIdle()
        // back at the record's head, collapsed again
        rule.onNode(s.heading("Last crash")).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag(LogDialogTags.crashStack(1)).fetchSemanticsNodes().size)
        s.scrollTo(LogDialogTags.CrashToggle)
        rule.onNodeWithContentDescription("Show 34 more lines").assertExists()
    }

    @Test
    fun copyPutsTheWholeRecordOnTheClipboardThenReadsCopiedThenCopyAgain() {
        s.show()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithContentDescription("Copy crash record").performClick()
        rule.waitForIdle()
        assertEquals(CrashReadings.copyText(LogFixtures.crash, LogFixtures.exits, LogFixtures.zone), s.clipboardText())
        assertTrue(s.clipboardText()!!.contains("Retrace with the mapping file of version 0.6.0 (16)."))
        rule.onNodeWithContentDescription("Copied").assertExists()
        rule.mainClock.advanceTimeBy(1_600)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Copy crash record").assertExists()
    }

    @Test
    fun clearAsksTheHostToDeleteAndNeedsNoConfirmation() {
        s.show()
        rule.onNodeWithContentDescription("Clear crash record").performClick()
        assertEquals(listOf("clear"), s.events)
    }

    @Test
    fun exitsAloneShowTheirHeadWithCopyAndNoClear() {
        s.show(crash = null)
        rule.onNode(s.heading("Recent exits")).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag(LogDialogTags.CrashClear).fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Copy recent exits").performClick()
        rule.waitForIdle()
        val text = s.clipboardText()!!
        assertTrue(text, text.startsWith("Recent exits\n"))
        assertFalse(text.contains("Last crash"))
        val rows = rule.onAllNodesWithTag(LogDialogTags.Exit).fetchSemanticsNodes().map { it.config[SemanticsProperties.ContentDescription].single() }
        assertEquals(
            listOf("Dec 31, 2025, 11:00:04 PM, Crash, in foreground · crash", "Dec 30, 2025, 06:42:17 PM, Low memory, in background"),
            rows,
        )
    }

    @Test
    fun aRecordAloneHasNoExitsBlock() {
        s.show(exits = emptyList())
        assertEquals(0, rule.onAllNodesWithTag(LogDialogTags.Exits).fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Copy crash record").assertExists()
    }

    @Test
    fun hebrewThreadMessageAndStackStillDrawLeftToRightUnderRtl() {
        val hebrew = "\u05E9\u05D2\u05D9\u05D0\u05D4"
        s.show(rtl = true, crash = LogFixtures.crash.copy(thread = hebrew, message = hebrew, stack = "$hebrew\n\tat a.B.c(B.kt:1)"))
        assertEquals(ResolvedTextDirection.Ltr, s.direction(rule.onNode(hasText(hebrew), useUnmergedTree = true)))
        assertEquals(ResolvedTextDirection.Ltr, s.direction(rule.onNodeWithTag(LogDialogTags.CrashException)))
        assertEquals(ResolvedTextDirection.Ltr, s.direction(rule.onNodeWithTag(LogDialogTags.crashStack(0))))
    }

    @Test
    fun underRtlTheChromeMirrorsAndTheCodeStaysLeftToRight() {
        s.show(rtl = true)
        val heading = rule.onNode(s.heading("Last crash")).fetchSemanticsNode().boundsInRoot
        val copy = rule.onNodeWithTag(LogDialogTags.CrashCopy).fetchSemanticsNode().boundsInRoot
        assertTrue("heading $heading sits right of Copy $copy", heading.right > copy.right)
        assertEquals(ResolvedTextDirection.Ltr, s.direction(rule.onNodeWithTag(LogDialogTags.crashStack(0))))
        assertEquals(ResolvedTextDirection.Ltr, s.direction(rule.onNodeWithTag(LogDialogTags.CrashException)))
        for (value in listOf("0.6.0 (16)", "16 (API 36)", "main")) {
            val node = rule.onNode(hasText(value), useUnmergedTree = true)
            assertEquals(value, ResolvedTextDirection.Ltr, s.direction(node))
        }
        // the labels are chrome: the meta label sits on the right edge of its row
        val label = rule.onNode(hasText("When"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val value = rule.onNode(hasText("Dec 31, 2025, 11:00:04 PM"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(label.right > value.right)
    }
}

/** 360 dp at 2.0x font: the keys wrap under the heading, and a hostile record neither throws nor stops scrolling. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-420dpi", fontScale = 2.0f)
class CrashSectionNarrowTest {
    @get:Rule val rule = createComposeRule()
    private val s by lazy { CrashShow(rule) }

    @Test
    fun theKeysDropUnderTheHeading() {
        s.show()
        val heading = rule.onNode(s.heading("Last crash")).fetchSemanticsNode().boundsInRoot
        val copy = rule.onNodeWithTag(LogDialogTags.CrashCopy).fetchSemanticsNode().boundsInRoot
        assertTrue("Copy $copy under heading $heading", copy.top >= heading.bottom)
    }

    @Test fun aSixtyFourKiBMultiLineRecordComposesScrollsAndCopies() = hostile(HostileStacks.multiLine())

    @Test fun aSixtyFourKiBSingleLineWithNoSpacesComposesScrollsAndCopies() = hostile(HostileStacks.oneLine())

    @Test fun aThreeThousandFrameRecordComposesScrollsAndCopies() = hostile(HostileStacks.frames())

    private fun hostile(stack: String) = HostileStacks.run(rule, s, stack)
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class CrashSectionTabletTest {
    @get:Rule val rule = createComposeRule()
    private val s by lazy { CrashShow(rule) }

    @Test fun aSixtyFourKiBMultiLineRecordComposesScrollsAndCopies() = HostileStacks.run(rule, s, HostileStacks.multiLine())

    @Test fun aSixtyFourKiBSingleLineWithNoSpacesComposesScrollsAndCopies() = HostileStacks.run(rule, s, HostileStacks.oneLine())

    @Test fun aThreeThousandFrameRecordComposesScrollsAndCopies() = HostileStacks.run(rule, s, HostileStacks.frames())

    @Test
    fun theWholeSectionFitsOneScreenWithTwoMetaColumns() {
        s.show()
        rule.onNodeWithTag(LogDialogTags.Crash).assertIsDisplayed()
        val when_ = rule.onNode(hasText("When"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val version = rule.onNode(hasText("App version"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("two columns: the first row holds both", when_.top, version.top, 2f)
    }
}

internal object HostileStacks {
    private const val SIXTY_FOUR_KIB = 64 * 1024

    fun multiLine(): String = buildString {
        var n = 0
        while (length < SIXTY_FOUR_KIB) append("\tat com.example.deep.Frame").append(n++).append(".call(Frame.kt:").append(n).append(")\n")
    }.take(SIXTY_FOUR_KIB)

    fun oneLine(): String = "q".repeat(SIXTY_FOUR_KIB)

    fun frames(): String = (1..3_000).joinToString("\n") { "\tat com.example.Frame$it.call(Frame.kt:$it)" }

    /** Composes the record, opens the whole stack, scrolls to the end and presses Copy: no exception anywhere. */
    fun run(rule: ComposeContentTestRule, s: CrashShow, stack: String) {
        val crash = LogFixtures.crash.copy(stack = stack)
        s.show(crash = crash)
        val chunks = CrashReadings.chunks(stack)
        assertTrue(chunks.size > 1)
        s.scrollTo(LogDialogTags.CrashToggle)
        rule.onNodeWithTag(LogDialogTags.CrashToggle).performClick()
        rule.waitForIdle()
        s.scrollTo(LogDialogTags.crashStack(chunks.lastIndex))
        assertNotNull(s.textOf(LogDialogTags.crashStack(chunks.lastIndex)))
        s.scrollTo(LogDialogTags.CrashEnd)
        s.scrollTo(LogDialogTags.CrashCopy)
        rule.onNodeWithTag(LogDialogTags.CrashCopy).performClick()
        rule.waitForIdle()
        assertEquals(CrashReadings.copyText(crash, LogFixtures.exits, LogFixtures.zone), s.clipboardText())
    }
}

/** The host: the record is read each time the dialog opens, Clear deletes only the record, and the exits stay. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DeviceLogDialogTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val dir = TemporaryFolder()

    @Test
    fun clearDeletesTheFileAndKeepsTheOtherFilesAndTheExitsBlock() {
        val other = File(dir.root, "mirror.key").also { it.writeText("keep") }
        val store = CrashRecordStore(File(dir.root, CrashRecordStore.FILE_NAME))
        store.write(LogFixtures.crash)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    DeviceLogDialog(
                        entries = LogFixtures.mixed, sessions = LogFixtures.sessions, state = LogDialogState(stats = LogFixtures.stats),
                        onRefresh = {}, onDismiss = {}, store = store, readExits = { LogFixtures.exits }, io = Dispatchers.Unconfined,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(LogDialogTags.CrashClear).fetchSemanticsNodes().isNotEmpty() }
        // The record arrives after the dialog opens, above the first visible item: the list still shows its head.
        rule.onNodeWithTag(LogDialogTags.Crash).assertIsDisplayed()
        rule.onNodeWithContentDescription("Clear crash record").performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { store.read() == null }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag(LogDialogTags.CrashClear).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasText("Last crash")).fetchSemanticsNodes().size)
        rule.onNodeWithTag(LogDialogTags.Exits).assertExists()
        assertTrue(other.isFile)
    }

    @Test
    fun withNoRecordAndNoExitsTheSectionNeverAppears() {
        val store = CrashRecordStore(File(dir.root, CrashRecordStore.FILE_NAME))
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    DeviceLogDialog(
                        entries = LogFixtures.mixed, sessions = LogFixtures.sessions, state = LogDialogState(stats = LogFixtures.stats),
                        onRefresh = {}, onDismiss = {}, store = store, readExits = { emptyList() }, io = Dispatchers.Unconfined,
                    )
                }
            }
        }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag(LogDialogTags.Crash).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag(LogDialogTags.CrashEnd).fetchSemanticsNodes().size)
    }
}
