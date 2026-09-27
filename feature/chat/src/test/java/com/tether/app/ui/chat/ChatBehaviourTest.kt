package com.tether.app.ui.chat

import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.AnnotatedString
import com.tether.app.ui.theme.TetherSkin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Behaviour of the transcript (T6.1 DoD): copy puts the exact fence body on the clipboard; a link
 * opens a Custom Tab intent (never a WebView); a bounded snapshot's top asks for the trimmed turns
 * with the web's `fetch-turns` params; thinking toggles; follow mode; TalkBack names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ChatBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        fixture: ChatFixtures.Folded,
        showThinking: Boolean = false,
        listState: LazyListState = LazyListState(),
        onFetchTurns: (Int, Int) -> Unit = { _, _ -> },
        opener: LinkOpener? = null,
    ) {
        rule.setContent {
            ChatHost(TetherSkin.Machine) {
                val body = @androidx.compose.runtime.Composable {
                    ChatTranscript(
                        projection = fixture.projection,
                        tree = fixture.tree,
                        showThinking = showThinking,
                        onFetchTurns = onFetchTurns,
                        onApproval = { _, _, _ -> },
                        onAnswer = { _, _, _ -> },
                        zone = ChatFixtures.zone,
                        listState = listState,
                    )
                }
                if (opener != null) CompositionLocalProvider(LocalLinkOpener provides opener) { body() } else body()
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun copyPutsTheExactFenceBodyOnTheClipboardAndSaysCopied() {
        show(ChatFixtures.markdown)
        rule.onAllNodesWithContentDescription("Copy code").onFirst().performClick()
        val clipboard = rule.activity.getSystemService(ClipboardManager::class.java)
        assertEquals(
            "export function retry<T>(fn: () => Promise<T>, attempts = 3): Promise<T> {\n" +
                "  return fn().catch((error) => (attempts > 1 ? retry(fn, attempts - 1) : Promise.reject(error)));\n}",
            clipboard.primaryClip!!.getItemAt(0).text.toString(),
        )
        rule.onNodeWithContentDescription("Copied").assertIsDisplayed()
        // markdown.tsx:54 — the check reverts after 1.5s.
        rule.mainClock.advanceTimeBy(COPIED_RESET_MS + 100)
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithContentDescription("Copied").fetchSemanticsNodes().size)
    }

    @Test
    fun theSecondFenceCopiesItsOwnBody() {
        show(ChatFixtures.markdown)
        val keys = rule.onAllNodesWithContentDescription("Copy code")
        keys[1].performClick()
        val clipboard = rule.activity.getSystemService(ClipboardManager::class.java)
        assertEquals("npm run test:unit && npm run lint", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun aLinkOpensACustomTabIntentNeverAWebView() {
        show(ChatFixtures.markdown)
        rule.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() }
        rule.waitForIdle()
        // Click the link annotation through its semantics (TalkBack's path too).
        val node = rule.onNode(SemanticsMatcher("has link") { n ->
            n.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { s: AnnotatedString -> s.getLinkAnnotations(0, s.length).isNotEmpty() } == true
        }).fetchSemanticsNode()
        val text: AnnotatedString = node.config[SemanticsProperties.Text].first { it.getLinkAnnotations(0, it.length).isNotEmpty() }
        val link = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Clickable
        assertEquals("https://example.test/docs", link.tag)
        rule.runOnUiThread { link.linkInteractionListener!!.onClick(link) }

        val started: Intent = shadowOf(rule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.test/docs", started.dataString)
        assertTrue("a Custom Tabs session extra", started.extras!!.containsKey(CustomTabLinkOpener.EXTRA_SESSION))
        assertNull("no explicit in-app component (no WebView activity)", started.component)
    }

    @Test
    fun unsafeSchemesNeverBecomeIntents() {
        assertNull(CustomTabLinkOpener.intentFor("javascript:alert(1)", androidx.compose.ui.graphics.Color.Black))
        assertNull(CustomTabLinkOpener.intentFor("intent://x#Intent;end", androidx.compose.ui.graphics.Color.Black))
        val mail = CustomTabLinkOpener.intentFor("mailto:ops@example.test", androidx.compose.ui.graphics.Color.Black)!!
        assertFalse("mailto goes to the mail app, not a tab", mail.hasExtra(CustomTabLinkOpener.EXTRA_SESSION))
    }

    @Test
    fun scrollingToTheTopOfABoundedSnapshotAsksForTheTrimmedTurns() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val listState = LazyListState()
        show(ChatFixtures.boundedOf(turns = 40, trimmed = 30), listState = listState, onFetchTurns = { a, b -> calls += a to b })
        // Starts at the bottom (follow mode); the key is not composed yet.
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasTestTag(ChatItem.LOAD_EARLIER_KEY)).fetchSemanticsNodes().size)
        rule.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() }
        rule.runOnIdle { runBlocking { listState.scrollToItem(0) } }
        rule.onNodeWithText("Load 30 earlier turns").assertIsDisplayed().performClick()
        // chat-view.tsx:3503 fetchTurns(session.id, 0, trimmedCount) — 0-based, exclusive end.
        assertEquals(listOf(0 to 30), calls)
    }

    @Test
    fun thinkingIsCollapsedByDefaultAndTogglesInPlace() {
        show(ChatFixtures.thinking, showThinking = true)
        val head = rule.onNodeWithContentDescription("Thinking")
        head.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("attempt 1 fails", substring = true)).fetchSemanticsNodes().size)
        head.performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Thinking").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        rule.onNodeWithText("attempt 1 fails", substring = true).assertIsDisplayed()
    }

    @Test
    fun thinkingHiddenWhenThePreferenceIsOff() {
        show(ChatFixtures.thinking, showThinking = false)
        assertEquals(0, rule.onAllNodesWithContentDescription("Thinking").fetchSemanticsNodes().size)
    }

    @Test
    fun aStreamingMessageShowsTheCaretAndNoTimeUntilDone() {
        show(ChatFixtures.streaming)
        rule.onNodeWithContentDescription("Streaming").assertIsDisplayed()
        // The streaming text is plain: the backticks are still visible (no markdown yet).
        rule.onNodeWithText("`reducer`", substring = true).assertIsDisplayed()
        // One finished reply + the user bubble carry 00:01; the streaming one carries none.
        assertEquals(2, rule.onAllNodes(androidx.compose.ui.test.hasText("00:01")).fetchSemanticsNodes().size)
    }

    @Test
    fun readingUpShowsJumpToLatestWhichReEngagesFollowing() {
        val listState = LazyListState()
        show(ChatFixtures.markdown, listState = listState)
        assertEquals(0, rule.onAllNodesWithContentDescription("Jump to latest").fetchSemanticsNodes().size)
        rule.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Jump to latest").assert(hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithContentDescription("Jump to latest").fetchSemanticsNodes().size)
        assertFalse(listState.canScrollForward)
    }

    @Test
    fun talkBackNamesTheTranscriptControls() {
        show(ChatFixtures.markdown)
        assertEquals(2, rule.onAllNodes(hasContentDescription("Copy code") and hasClickAction()).fetchSemanticsNodes().size)
        rule.onAllNodes(hasContentDescription("Copy code")).onFirst()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
        // The streaming caret is named, so TalkBack does not meet an unlabelled violet block.
        assertNotNull(rule.onNodeWithText("That's everything for this release.").fetchSemanticsNode())
    }
}
