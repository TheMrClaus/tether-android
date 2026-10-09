package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tether.app.protocol.reduce.ev
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-8hcc through the transcript: a tap on a relative inline-code mention opens the file the session touched (the owner's
 * report: the session ran in one repo, wrote in another, and the mention opened "not found" under the session's folder).
 * Parity: the web has no prose file links (markdown.tsx:36-62; tether#264 open); this is owner-directed (ta-9jnm, ta-8hcc).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class FileMentionResolveTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<String>()

    private fun show(folded: ChatFixtures.Folded): WorkspaceFileLinks {
        val opener = FileLinkFixtures.opener(opened, TouchFixtures.CWD)
        rule.setContent { ChatHost(TetherSkin.StudioDark) { FileLinkBoard(folded, opener) } }
        rule.waitForIdle()
        return opener
    }

    /** Taps every link on screen in reading order; the paths the host was asked to open. */
    private fun tapAll(): List<String> {
        opened.clear()
        for ((_, link) in FileLinkFixtures.links(rule)) rule.runOnUiThread { link.linkInteractionListener!!.onClick(link) }
        rule.waitForIdle()
        return opened.toList()
    }

    @Test fun theOwnersShapeTapsOpenTheFilesInTheOtherRepo() {
        show(
            TouchFixtures.fold(
                TouchFixtures.turn(
                    "t1",
                    listOf(
                        TouchFixtures.tool("t1", "a", "Write", "file_path", "/ws-B/digests/cat.md"),
                        TouchFixtures.tool("t1", "b", "Write", "file_path", "/ws-B/notes2.md"),
                    ),
                    "Wrote `cat.md`, `digests/cat.md` and `notes2.md`; `src/none.kt` is unchanged.",
                ),
            ),
        )
        assertEquals(listOf("cat.md", "digests/cat.md", "notes2.md", "src/none.kt"), FileLinkFixtures.links(rule).map { it.first })
        assertEquals(listOf("/ws-B/digests/cat.md", "/ws-B/digests/cat.md", "/ws-B/notes2.md", "/ws-A/src/none.kt"), tapAll())
    }

    @Test fun anAbsoluteMentionAndAMarkdownLinkAreUnchanged() {
        show(
            TouchFixtures.fold(
                TouchFixtures.turn(
                    "t1",
                    listOf(TouchFixtures.tool("t1", "a", "Write", "file_path", "/ws-B/digests/cat.md")),
                    "See `/ws-C/abs.md` and [the digest](/ws-B/other/cat.md) and [rel](digests/cat.md).",
                ),
            ),
        )
        assertEquals(listOf("/ws-C/abs.md", "/ws-B/other/cat.md"), tapAll())
    }

    @Test fun aLaterTouchDoesNotMoveAnEarlierMessagesLink() {
        show(
            TouchFixtures.fold(
                TouchFixtures.turn("t1", listOf(TouchFixtures.tool("t1", "a", "Write", "file_path", "/ws-B/x/doc.md")), "first `doc.md`"),
                TouchFixtures.turn("t2", listOf(TouchFixtures.tool("t2", "b", "Write", "file_path", "/ws-C/doc.md")), "second `doc.md`"),
            ),
        )
        assertEquals(listOf("/ws-B/x/doc.md", "/ws-C/doc.md"), tapAll())
    }

    @Test fun aSessionThatOnlyReadAFileOpensItEvenWhenTheCwdHasOneOfThatName() {
        show(TouchFixtures.fold(TouchFixtures.turn("t1", listOf(TouchFixtures.tool("t1", "a", "Read", "file_path", "/ws-B/README.md")), "See `README.md`.")))
        assertEquals(listOf("/ws-B/README.md"), tapAll())
    }

    @Test fun aFileOnlyASubAgentWroteOpensWhenTheParentNamesIt() {
        show(
            TouchFixtures.fold(
                TouchFixtures.turn(
                    "t1",
                    TouchFixtures.agent("t1", "task-1", Triple("Write", "file_path", "/ws-B/sub/out.md")),
                    "The sub-agent wrote `sub/out.md`.",
                ),
            ),
        )
        assertEquals(listOf("/ws-B/sub/out.md"), tapAll())
    }

    @Test fun noTouchedFileMeansTheCwdExactlyAsToday() {
        show(TouchFixtures.fold(TouchFixtures.turn("t1", emptyList(), "Edit `src/app.kt:12`, `./docs/a.md` and `../up.md`.")))
        assertEquals(listOf("/ws-A/src/app.kt", "/ws-A/docs/a.md", "/up.md"), tapAll())
    }

    @Test fun aDotDotMentionUsesTheCwdOnlyEvenWhenAFileOfThatNameWasTouched() {
        show(TouchFixtures.fold(TouchFixtures.turn("t1", listOf(TouchFixtures.tool("t1", "a", "Write", "file_path", "/ws-B/x.md")), "Look at `../x.md`.")))
        assertEquals(listOf("/x.md"), tapAll())
    }

    @Test fun aLineSuffixedMentionOpensTheTouchedFile() {
        show(TouchFixtures.fold(TouchFixtures.turn("t1", listOf(TouchFixtures.tool("t1", "a", "Edit", "file_path", "/ws-B/src/Main.kt")), "Fixed `src/Main.kt:42`.")))
        assertEquals(listOf("/ws-B/src/Main.kt"), tapAll())
    }

    // --- the cost: nothing is rebuilt per delta ------------------------------------------------------------------------

    /** A finished first turn that wrote a file and named it, then a second turn that streams deltas and later writes. */
    @Test fun deltasWithNoNewToolCallBuildNoProseAndPublishNothingAndOneWritePublishesOnce() {
        val first = TouchFixtures.turn("t1", listOf(TouchFixtures.tool("t1", "a", "Write", "file_path", "/ws-B/digests/cat.md")), "Wrote `digests/cat.md`.")
        var tree by mutableStateOf(TouchFixtures.fold(first))
        val opener = FileLinkFixtures.opener(opened, TouchFixtures.CWD)
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalWorkspaceFileOpener provides opener) {
                    ChatTranscript(
                        projection = tree.projection, tree = tree.tree, showThinking = false, onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone, showTimeline = false,
                    )
                }
            }
        }
        rule.waitForIdle()
        assertEquals(listOf("digests/cat.md"), FileLinkFixtures.links(rule).map { it.first })
        val publishes = opener.touched.publishCount
        val prose = FileLinkDraw.built.get()
        assertEquals("the first index is published once", 1, publishes)

        fun fold(events: List<com.tether.app.protocol.AgentEvent>) = TouchFixtures.fold(first, events)
        val streaming = mutableListOf(
            ev("turn_started", "t2", ts = 2L) { put("idempotencyKey", "k2") },
            ev("user_message_accepted", "t2", ts = 2L) { put("text", "Go on") },
            ev("message_started", "t2", ts = 2L) { put("blockId", "t2:m0") },
        )
        val deltas = 25
        for (n in 1..deltas) {
            streaming += ev("message_delta", "t2", ts = 2L) { put("blockId", "t2:m0"); put("text", "word$n ") }
            rule.runOnIdle { tree = fold(streaming) }
            rule.waitForIdle()
        }
        assertEquals("$deltas deltas with no tool call published no index", publishes, opener.touched.publishCount)
        assertEquals("and rebuilt no prose", prose, FileLinkDraw.built.get())

        // One Write: exactly one publish, and the earlier message's prose is still not rebuilt.
        streaming += TouchFixtures.tool("t2", "w", "Write", "file_path", "/ws-B/new.md")
        rule.runOnIdle { tree = fold(streaming) }
        rule.waitForIdle()
        assertEquals("one Write publishes exactly once", publishes + 1, opener.touched.publishCount)
        assertEquals(prose, FileLinkDraw.built.get())

        // More deltas after it: still nothing.
        for (n in 1..10) {
            streaming += ev("message_delta", "t2", ts = 3L) { put("blockId", "t2:m0"); put("text", "more$n ") }
            rule.runOnIdle { tree = fold(streaming) }
            rule.waitForIdle()
        }
        assertEquals(publishes + 1, opener.touched.publishCount)
        assertEquals(prose, FileLinkDraw.built.get())
        // The new file resolves for a message after it.
        assertEquals("/ws-B/new.md", opener.touched.latest.resolve("new.md", null))
    }
}
