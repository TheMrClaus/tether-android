package com.tether.app.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.reduce.tree
import com.tether.app.protocol.TetherJson
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.JsonPrimitive
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
 * ta-blf: the transcript never draws a bidi override raw, code shows every invisible and bidi
 * code point, real RTL text keeps its order, nothing leaks past its line, TalkBack reads tokens as
 * words, and a copy is the original text. Every control here is an escape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", shadows = [NoMagnifier::class])
class TranscriptBidiBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val LRE = "\u202A"
        const val RLE = "\u202B"
        const val PDF = "\u202C"
        const val LRO = "\u202D"
        const val RLO = "\u202E"
        const val LRI = "\u2066"
        const val RLI = "\u2067"
        const val ZWSP = "\u200B"
        const val ARABIC = "\u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645" // "hello world"
        const val HEBREW = "\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD" // "hello world"
        const val SPOOF = "Fix the ${RLO}parser$PDF bug now"
        const val FENCE = "const admin = \"$RLO$LRI x\"; // zwsp$ZWSP"
        val OVERRIDES = listOf(LRE, RLE, PDF, LRO, RLO)
        val ALL_BIDI = OVERRIDES + listOf(LRI, RLI, "\u2068", "\u2069", "\u200E", "\u200F", "\u061C")
        fun tok(cp: Int) = "\u2060\u27E8U+%04X\u27E9".format(cp)
    }

    private fun show(fixture: ChatFixtures.Folded, menu: MenuSpy? = null, extra: (@androidx.compose.runtime.Composable () -> Unit)? = null) {
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider provides (menu ?: androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider.current),
            ) {
                ChatHost(TetherSkin.Machine) {
                    Column {
                        extra?.invoke()
                        ChatTranscript(
                            projection = fixture.projection,
                            tree = fixture.tree,
                            showThinking = true,
                            onFetchTurns = { _, _ -> },
                            zone = ChatFixtures.zone,
                            listState = LazyListState(),
                            showTimeline = false,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun fixture(prompt: String, reply: String, thinking: String? = null) =
        ChatFixtures.fold(*ChatFixtures.turn("t1", prompt, reply, ChatFixtures.T_IDLE, thinking))

    /** Every text and description TalkBack could read, unmerged. */
    private fun spoken(): List<String> = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { n ->
        n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } +
            n.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty()
    }

    private fun assertNoRawOverride() {
        for (s in spoken()) for (c in OVERRIDES) assertFalse("raw U+%04X in \"$s\"".format(c[0].code), s.contains(c))
    }

    private fun layoutOf(text: String, exact: Boolean = false): Pair<String, TextLayoutResult> {
        val node = rule.onNodeWithText(text, substring = !exact, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val r = results.first()
        return r.layoutInput.text.text to r
    }

    private fun clip(): String? =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    private class MenuSpy : androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider {
        @Volatile var shown: androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider? = null

        override suspend fun showTextContextMenu(dataProvider: androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider) {
            shown = dataProvider
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                if (shown === dataProvider) shown = null
            }
        }

        fun press(key: Any) {
            val menu = checkNotNull(shown) { "no text toolbar is open" }
            val item = menu.data().components.filterIsInstance<androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem>().first { it.key == key }
            item.onClick(object : androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession { override fun close() = Unit })
        }
    }

    private fun selectAllAndCopy(menu: MenuSpy, text: String) {
        rule.onNodeWithText(text, substring = true, useUnmergedTree = true).performTouchInput { longClick(center) }
        rule.waitForIdle()
        menu.press(androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys.SelectAllKey)
        rule.waitForIdle()
        menu.press(androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys.CopyKey)
        rule.waitForIdle()
    }

    // ---- prose -------------------------------------------------------------------------------

    @Test fun anOverrideInTheOperatorsWordsIsAVisibleTokenAndTheWordsReadInOrder() {
        show(fixture(SPOOF, "Done."))
        val (shown, layout) = layoutOf("parser")
        assertEquals("Fix the ${tok(0x202E)}parser${tok(0x202C)} bug now", shown)
        // The layout draws "parser" left to right: its p is left of its r (raw, the RLO drew "resrap").
        val p = shown.indexOf("parser")
        assertTrue("p at ${layout.getBoundingBox(p)} vs r at ${layout.getBoundingBox(p + 5)}", layout.getBoundingBox(p).left < layout.getBoundingBox(p + 5).left)
        assertNoRawOverride()
    }

    @Test fun everyOverrideInEveryMarkdownSurfaceIsAToken() {
        val reply = listOf(
            "### Heading ${LRE}one$PDF",
            "",
            "Rename ${RLO}gnp.exe$PDF, see [the ${RLO}label$PDF](https://example.test/x).",
            "",
            "- item ${LRO}two$PDF",
            "",
            "| col |",
            "| --- |",
            "| cell ${RLE}three$PDF |",
            "",
            "> quote ${RLO}four$PDF",
        ).joinToString("\n")
        show(fixture("Show me.", reply, thinking = "think ${RLO}deep$PDF"))
        rule.onNodeWithContentDescription("Thinking").performClick()
        rule.waitForIdle()
        for (expected in listOf(
            "Heading ${tok(0x202A)}one${tok(0x202C)}",
            "Rename ${tok(0x202E)}gnp.exe${tok(0x202C)}",
            "the ${tok(0x202E)}label${tok(0x202C)}",
            "item ${tok(0x202D)}two${tok(0x202C)}",
            "cell ${tok(0x202B)}three${tok(0x202C)}",
            "quote ${tok(0x202E)}four${tok(0x202C)}",
            "think ${tok(0x202E)}deep${tok(0x202C)}",
        )) {
            rule.onNodeWithText(expected, substring = true, useUnmergedTree = true).assertExists()
        }
        assertNoRawOverride()
    }

    @Test fun aStreamingReplyIsProseToo() {
        val f = ChatFixtures.fold(
            ev("turn_started", "t1", ts = ChatFixtures.T_STREAM) { put("idempotencyKey", "k-t1") },
            ev("user_message_accepted", "t1", ts = ChatFixtures.T_STREAM) { put("text", "Go.") },
            ev("message_started", "t1", ts = ChatFixtures.T_STREAM) { put("blockId", "t1:m0") },
            ev("message_delta", "t1", ts = ChatFixtures.T_STREAM) { put("blockId", "t1:m0"); put("text", "typing ${RLO}olleh") },
        )
        show(f)
        rule.onNodeWithText("typing ${tok(0x202E)}olleh", substring = true, useUnmergedTree = true).assertExists()
        assertNoRawOverride()
    }

    // ---- RTL ---------------------------------------------------------------------------------

    @Test fun realArabicAndHebrewKeepTheirLettersAndReadRightToLeft() {
        show(fixture("$HEBREW \u2067$HEBREW\u2069", "$ARABIC\n\n$HEBREW"))
        for (text in listOf(ARABIC, HEBREW)) {
            val (shown, layout) = layoutOf(text, exact = true)
            val start = shown.indexOf(text)
            assertTrue("the letters are all there: $shown", start >= 0)
            assertEquals(ResolvedTextDirection.Rtl, layout.getBidiRunDirection(start))
            // The first letter (logical order) is drawn right of the last one.
            assertTrue(layout.getBoundingBox(start).left > layout.getBoundingBox(start + text.length - 1).left)
        }
        // The operator's isolate is kept raw (legitimate RTL markup), not a token.
        rule.onNodeWithText("$HEBREW \u2067$HEBREW\u2069", useUnmergedTree = true).assertExists()
    }

    /**
     * An unterminated isolate ends at its line: a markdown `<br/>` is a new bidi paragraph, so the
     * next line's "!" is LTR. The control shows the same text with no paragraph break does leak
     * (so this layout really runs the bidi algorithm).
     */
    @Test fun anUnterminatedIsolateNeverReachesTheNextLine() {
        show(fixture("Go.", "${RLI}abc\nok!")) {
            Text("${RLI}abc ok!", modifier = Modifier.testTag("control"))
        }
        val (shown, layout) = layoutOf("${RLI}abc\nok!", exact = true)
        assertEquals("${RLI}abc\nok!", shown)
        assertEquals(ResolvedTextDirection.Ltr, layout.getBidiRunDirection(shown.indexOf('!')))
        val control = rule.onNodeWithTag("control").fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        control.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        assertEquals("the control leaks", ResolvedTextDirection.Rtl, results.first().getBidiRunDirection("${RLI}abc ok!".indexOf('!')))
    }

    // ---- code --------------------------------------------------------------------------------

    @Test fun codeShowsEveryBidiAndInvisibleCodePointAndCopiesTheRawBody() {
        show(fixture("Show me.", "Use `x ${RLI}y` then:\n\n```\n$FENCE\n```"))
        rule.onNodeWithText("x ${tok(0x2067)}y", substring = true, useUnmergedTree = true).assertExists()
        val fence = "const admin = \"${tok(0x202E)}${tok(0x2066)} x\"; // zwsp${tok(0x200B)}"
        rule.onNodeWithText(fence, useUnmergedTree = true).assertExists()
        // TalkBack reads the fence's tokens: no bidi control of any kind is in what it reads.
        val code = spoken().single { it.startsWith("const admin") }
        for (c in ALL_BIDI + ZWSP) assertFalse("raw U+%04X".format(c[0].code), code.contains(c))
        assertTrue(code.contains("U+202E"))
        // The copy key takes the raw fence body.
        rule.onAllNodesWithContentDescription("Copy code").onFirst().performClick()
        assertEquals(FENCE, clip())
    }

    @Test fun toolOutputIsCode() {
        var n = 0
        fun json(s: String) = TetherJson.parseToJsonElement(s)
        val f = ChatFixtures.fold(
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k-t1") },
            ev("user_message_accepted", "t1", ts = 1) { put("text", "Run it.") },
            ev("tool_start", "t1", ts = 1) { put("toolId", "t1:tool${n++}"); put("name", "Bash"); put("input", json("""{"command":"ls"}""")) },
            ev("tool_end", "t1", ts = 1) { put("toolId", "t1:tool0"); put("output", JsonPrimitive("out ${RLO}txt.exe$PDF$ZWSP")); put("isError", false) },
            ev("turn_end", "t1", ts = 1) { put("outcome", "ok") },
        )
        show(f)
        // The lone call sits in its activity group, closed once done: open it.
        rule.onNodeWithTag("tool-activity-group").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("out ${tok(0x202E)}txt.exe${tok(0x202C)}${tok(0x200B)}", substring = true, useUnmergedTree = true).assertExists()
        assertNoRawOverride()
    }

    // ---- copy --------------------------------------------------------------------------------

    @Test fun aRowsSelectionCopiesTheOriginalTextNeverTheTokens() {
        val menu = MenuSpy()
        show(fixture(SPOOF, "Use:\n\n```\n$FENCE\n```"), menu)
        selectAllAndCopy(menu, "parser")
        val prompt = checkNotNull(clip())
        assertTrue("the original prompt: ${prompt.map { "%04X".format(it.code) }}", prompt.contains(SPOOF))
        assertFalse(prompt.contains("\u2060") || prompt.contains("U+202E"))
        selectAllAndCopy(menu, "const admin")
        val reply = checkNotNull(clip())
        assertTrue("the original fence: $reply", reply.contains(FENCE))
        assertFalse(reply.contains("\u2060"))
    }

    // ---- the background command's output sheet -----------------------------------------------

    @Test fun theOutputSheetDropsColourAndShowsEveryOtherControl() {
        var tree = reduce(freshTree(), evNullTurn("background_command_updated", ts = 1) {
            put("commandId", "c"); put("command", "make"); put("cwd", "/w"); put("logFile", "/w/c${RLO}.log"); put("status", "running"); put("startedAt", 1)
        }.tree())
        tree = reduce(tree, evNullTurn("background_command_output", ts = 2) {
            put("commandId", "c"); put("stream", "stdout"); put("text", "\u001B[31mFAIL\u001B[0m ${RLO}exe.txt\n\u001B[2Kerased\n")
        }.tree())
        rule.setContent {
            ChatHost(TetherSkin.Machine) { CommandOutputSurface(runningBackgroundCommands(tree).single(), CommandActions.Unavailable, onClose = {}) }
        }
        rule.mainClock.advanceTimeBy(COMMAND_SHEET_SAMPLE_MS * 2)
        rule.waitForIdle()
        rule.onNodeWithText("FAIL ${tok(0x202E)}exe.txt", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("${tok(0x1B)}[2Kerased", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("c${tok(0x202E)}.log", substring = true, useUnmergedTree = true).assertExists()
        for (s in spoken()) {
            assertFalse("raw ESC in \"$s\"", s.contains('\u001B'))
            for (c in OVERRIDES) assertFalse(s.contains(c))
        }
    }
}
