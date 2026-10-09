package com.tether.app.ui.chat

import com.tether.app.protocol.reduce.ev
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ta-8hcc: which files a session's tool calls touched (the server's ledger, lib/session-brief.mjs classifyToolBlock, from the
 * RAW input strings), and which of them a relative mention names. Pure: no UI.
 */
class TouchedFilesTest {
    private fun paths(folded: ChatFixtures.Folded) = touchedFilesOf(folded.tree).touches.map { it.path }

    // --- the ledger: one test per provider shape -----------------------------------------------------------------------

    @Test fun claudeFileToolsTouchTheirFilePath() {
        val folded = TouchFixtures.fold(
            TouchFixtures.turn(
                "t1",
                listOf(
                    TouchFixtures.tool("t1", "a", "Write", "file_path", "/ws-B/w.md"),
                    TouchFixtures.tool("t1", "b", "Edit", "file_path", "/ws-B/e.md"),
                    TouchFixtures.tool("t1", "c", "MultiEdit", "file_path", "/ws-B/m.md"),
                    TouchFixtures.tool("t1", "d", "NotebookEdit", "notebook_path", "/ws-B/n.ipynb"),
                    TouchFixtures.tool("t1", "e", "Read", "file_path", "/ws-B/r.md"),
                ),
                "done",
            ),
        )
        assertEquals(listOf("/ws-B/w.md", "/ws-B/e.md", "/ws-B/m.md", "/ws-B/n.ipynb", "/ws-B/r.md"), paths(folded))
    }

    @Test fun codexFileChangeTouchesEveryChangeAndPrefersTheOutputsChanges() {
        val folded = TouchFixtures.fold(
            TouchFixtures.turn(
                "t1",
                listOf(
                    TouchFixtures.fileChange("t1", "fc1", "/ws-B/one.md", "/ws-B/two/deep.md", "/ws-B/three.kt"),
                    TouchFixtures.fileChange("t1", "fc2", "/ws-B/from-input.md"),
                    ev("tool_end", "t1", ts = 1L) {
                        put("toolId", "fc2")
                        putJsonObject("output") { putJsonArray("changes") { addJsonObject { put("path", "/ws-B/from-output.md"); put("kind", "update") } } }
                    },
                ),
                "done",
            ),
        )
        assertEquals(listOf("/ws-B/one.md", "/ws-B/two/deep.md", "/ws-B/three.kt", "/ws-B/from-output.md"), paths(folded))
    }

    @Test fun openCodeLowercaseToolsTouchTheirFilePathKey() {
        val folded = TouchFixtures.fold(
            TouchFixtures.turn(
                "t1",
                listOf(
                    TouchFixtures.tool("t1", "a", "write", "filePath", "/ws-B/w.md"),
                    TouchFixtures.tool("t1", "b", "edit", "filePath", "/ws-B/e.md"),
                    TouchFixtures.tool("t1", "c", "read", "filePath", "/ws-B/r.md"),
                    TouchFixtures.tool("t1", "d", "patch", "filePath", "/ws-B/p.md"),
                ),
                "done",
            ),
        )
        assertEquals(listOf("/ws-B/w.md", "/ws-B/e.md", "/ws-B/r.md", "/ws-B/p.md"), paths(folded))
    }

    @Test fun searchesAndShellsNameDirectoriesOrPatternsNeverFiles() {
        val folded = TouchFixtures.fold(
            TouchFixtures.turn(
                "t1",
                listOf(
                    TouchFixtures.tool("t1", "a", "Grep", "path", "/ws-B/src"),
                    TouchFixtures.tool("t1", "b", "Glob", "path", "/ws-B/docs"),
                    TouchFixtures.tool("t1", "c", "Bash", "command", "/ws-B/run.sh"),
                    TouchFixtures.tool("t1", "d", "WebFetch", "url", "/ws-B/x.md"),
                ),
                "done",
            ),
        )
        assertEquals(emptyList<String>(), paths(folded))
    }

    @Test fun aRelativeTouchIsDroppedAndAnAbsoluteOneIsNormalized() {
        val folded = TouchFixtures.fold(
            TouchFixtures.turn(
                "t1",
                listOf(
                    TouchFixtures.tool("t1", "a", "Write", "file_path", "docs/rel.md"),
                    TouchFixtures.tool("t1", "b", "Write", "file_path", "/ws-B/x/../y//z.md"),
                ),
                "done",
            ),
        )
        assertEquals(listOf("/ws-B/y/z.md"), paths(folded))
    }

    @Test fun theRawInputIsReadNotTheRowsFoldedTwoHundredCharacterArgument() {
        val spaced = "/ws-B/two  spaces/note  v2.md"
        val long = "/ws-B/" + "d".repeat(150) + "/" + "f".repeat(120) + ".md"
        assertEquals(true, long.length > 200)
        val folded = TouchFixtures.fold(
            TouchFixtures.turn(
                "t1",
                listOf(TouchFixtures.tool("t1", "a", "Write", "file_path", spaced), TouchFixtures.tool("t1", "b", "Write", "file_path", long)),
                "done",
            ),
        )
        assertEquals(listOf(spaced, long), paths(folded))
        val index = touchedFilesOf(folded.tree)
        assertEquals(spaced, index.resolve("two  spaces/note  v2.md", null))
        assertEquals(long, index.resolve("d".repeat(150) + "/" + "f".repeat(120) + ".md", null))
    }

    @Test fun aTruncatedOrMissingInputTouchesNothingAndNeverThrows() {
        val folded = TouchFixtures.fold(
            TouchFixtures.turn(
                "t1",
                listOf(
                    TouchFixtures.bare("t1", "a", "Write"),
                    TouchFixtures.tool("t1", "b", "Write", null),
                    ev("tool_start", "t1", ts = 1L) { put("toolId", "c"); put("name", "file_change"); putJsonObject("input") { put("changes", "not-an-array") } },
                    ev("tool_start", "t1", ts = 1L) {
                        put("toolId", "d"); put("name", "file_change")
                        putJsonObject("input") { putJsonArray("changes") { add("a string"); addJsonObject { put("kind", "add") } } }
                    },
                ),
                "done",
            ),
        )
        assertEquals(emptyList<String>(), paths(folded))
        assertNull(touchedFilesOf(folded.tree).resolve("a.md", null))
    }

    @Test fun aSubAgentsOwnCallsAreInTheIndexAtItsLaunchersPlace() {
        val folded = TouchFixtures.fold(
            TouchFixtures.turn("t1", TouchFixtures.agent("t1", "task-1", Triple("Write", "file_path", "/ws-B/digests/cat.md")), "Wrote it."),
        )
        assertEquals(listOf("/ws-B/digests/cat.md"), paths(folded))
        assertEquals("/ws-B/digests/cat.md", TouchFixtures.resolve(folded, "cat.md", "t1"))
    }

    // --- resolving a mention -------------------------------------------------------------------------------------------

    @Test fun theOwnersShapeABareAndAOneFolderMentionOpenTheFileInTheOtherRepo() {
        val folded = TouchFixtures.fold(
            TouchFixtures.turn(
                "t1",
                listOf(
                    TouchFixtures.tool("t1", "a", "Write", "file_path", "/ws-B/digests/catalog-config.md"),
                    TouchFixtures.tool("t1", "b", "Write", "file_path", "/ws-B/digests/study.md"),
                ),
                "Wrote `catalog-config.md` and `digests/study.md`.",
            ),
        )
        assertEquals("/ws-B/digests/catalog-config.md", TouchFixtures.resolve(folded, "catalog-config.md", "t1"))
        assertEquals("/ws-B/digests/study.md", TouchFixtures.resolve(folded, "digests/study.md", "t1"))
        assertNull("a file nothing touched is the cwd's, as before", TouchFixtures.resolve(folded, "other.md", "t1"))
    }

    @Test fun aMentionMatchesWholeSegmentsOnly() {
        val folded = TouchFixtures.fold(
            TouchFixtures.turn("t1", listOf(TouchFixtures.tool("t1", "a", "Write", "file_path", "/x/aa/b.md")), "ok"),
        )
        assertNull("a/b.md is not a suffix of /x/aa/b.md by segments", TouchFixtures.resolve(folded, "a/b.md", "t1"))
        assertEquals("/x/aa/b.md", TouchFixtures.resolve(folded, "aa/b.md", "t1"))
        assertEquals("/x/aa/b.md", TouchFixtures.resolve(folded, "b.md", "t1"))
        assertNull("part of a file name is not a segment", TouchFixtures.resolve(folded, "a.md", "t1"))
        assertNull("a mention longer than the path", TouchFixtures.resolve(folded, "y/x/aa/b.md", "t1"))
    }

    @Test fun matchingIsCaseSensitive() {
        val folded = TouchFixtures.fold(
            TouchFixtures.turn("t1", listOf(TouchFixtures.tool("t1", "a", "Write", "file_path", "/ws-B/Docs/Cat.md")), "ok"),
        )
        assertNull(TouchFixtures.resolve(folded, "docs/cat.md", "t1"))
        assertEquals("/ws-B/Docs/Cat.md", TouchFixtures.resolve(folded, "Docs/Cat.md", "t1"))
    }

    @Test fun theNewestTouchAtOrBeforeTheMentioningMessageWins() {
        val folded = TouchFixtures.fold(
            TouchFixtures.turn("t1", listOf(TouchFixtures.tool("t1", "a", "Write", "file_path", "/ws-B/doc.md")), "first `doc.md`"),
            TouchFixtures.turn("t2", listOf(TouchFixtures.tool("t2", "b", "Write", "file_path", "/ws-C/doc.md")), "second `doc.md`"),
            TouchFixtures.turn("t3", listOf(TouchFixtures.tool("t3", "c", "Edit", "file_path", "/ws-B/doc.md")), "third `doc.md`"),
        )
        assertEquals("/ws-B/doc.md", TouchFixtures.resolve(folded, "doc.md", "t1"))
        assertEquals("a later touch (t2, /ws-C) does not move t1's link", "/ws-B/doc.md", TouchFixtures.resolve(folded, "doc.md", "t1"))
        assertEquals("/ws-C/doc.md", TouchFixtures.resolve(folded, "doc.md", "t2"))
        assertEquals("/ws-B/doc.md", TouchFixtures.resolve(folded, "doc.md", "t3"))
        assertEquals("a block the index has not seen is newer than all of it", "/ws-B/doc.md", TouchFixtures.resolve(folded, "doc.md", "t9"))
        assertEquals("no anchor at all: the newest overall", "/ws-B/doc.md", TouchFixtures.resolve(folded, "doc.md", null))
    }

    @Test fun aTouchLaterInTheSameTurnAsTheMessageIsAfterItAndDoesNotCount() {
        // The message is the turn's block 1 (before the Write that follows it).
        val folded = TouchFixtures.fold(
            listOf(
                ev("turn_started", "t1", ts = 1L) { put("idempotencyKey", "k") },
                ev("user_message_accepted", "t1", ts = 1L) { put("text", "go") },
                ev("message_started", "t1", ts = 1L) { put("blockId", "t1:m0") },
                ev("message_completed", "t1", ts = 1L) { put("blockId", "t1:m0"); put("text", "about `doc.md`") },
                TouchFixtures.tool("t1", "a", "Write", "file_path", "/ws-B/doc.md"),
                ev("turn_end", "t1", ts = 1L) { put("outcome", "ok") },
            ),
        )
        assertNull(TouchFixtures.resolve(folded, "doc.md", "t1"))
    }

    @Test fun aSessionThatOnlyReadAFileResolvesItsNameToThatFileEvenIfTheCwdHasOne() {
        // cwd /ws-A has a README.md on disk (the index cannot know); the session only Read /ws-B/README.md: touched wins.
        val folded = TouchFixtures.fold(
            TouchFixtures.turn("t1", listOf(TouchFixtures.tool("t1", "a", "Read", "file_path", "/ws-B/README.md")), "See `README.md`."),
        )
        assertEquals("/ws-B/README.md", TouchFixtures.resolve(folded, "README.md", "t1"))
    }

    @Test fun aTouchOutsideTheLoadedTurnsFallsBackToTheCwd() {
        val full = TouchFixtures.fold(
            TouchFixtures.turn("t1", listOf(TouchFixtures.tool("t1", "a", "Write", "file_path", "/ws-B/old.md")), "ok"),
            TouchFixtures.turn("t2", emptyList(), "now `old.md`"),
        )
        assertEquals("/ws-B/old.md", TouchFixtures.resolve(full, "old.md", "t2"))
        // The server trimmed t1's blocks (older turns not loaded): the file is unknown here, so the cwd answers (null).
        val trimmed = com.tether.app.protocol.tree.JsObj.let {
            val turns = full.tree["turnsById"] as com.tether.app.protocol.tree.JsObj
            val t1 = turns["t1"] as com.tether.app.protocol.tree.JsObj
            val cut = t1.put("blocks", com.tether.app.protocol.tree.JsArr.EMPTY).put("blocksById", com.tether.app.protocol.tree.JsObj.EMPTY)
            full.tree.put("turnsById", turns.put("t1", cut))
        }
        assertNull(touchedFilesOf(trimmed).resolve("old.md", FileMentionAnchor("t2", "t2:m0")))
    }

    // --- the mention as the detector reads it -------------------------------------------------------------------------

    private fun mentionOfCode(text: String): String? =
        FileLinks.detect(text, FileLinkSource.Code, TouchFixtures.CWD).singleOrNull()?.mention

    @Test fun theMentionDropsADotSlashAndALineSuffixAndABareNameStaysBare() {
        assertEquals("digests/cat.md", mentionOfCode("./digests/cat.md"))
        assertEquals("digests/cat.md", mentionOfCode("digests/cat.md:12"))
        assertEquals("digests/cat.md", mentionOfCode("digests/cat.md:12:3"))
        assertEquals("cat.md", mentionOfCode("cat.md"))
        assertEquals("a/b.md", mentionOfCode("a/./b.md"))
    }

    @Test fun aMentionWithDotDotUsesTheCwdOnlyAndAnAbsoluteMentionIsUnchanged() {
        val up = FileLinks.detect("../x.md", FileLinkSource.Code, TouchFixtures.CWD).single()
        assertNull(up.mention)
        assertEquals("/x.md", up.path)
        val inner = FileLinks.detect("a/../b.md", FileLinkSource.Code, TouchFixtures.CWD).single()
        assertNull(inner.mention)
        assertEquals("/ws-A/b.md", inner.path)
        val abs = FileLinks.detect("/ws-B/digests/cat.md", FileLinkSource.Code, TouchFixtures.CWD).single()
        assertNull(abs.mention)
        assertEquals("/ws-B/digests/cat.md", abs.path)
    }

    @Test fun theLineSuffixAndDotSlashMentionsResolveThroughTheIndex() {
        val folded = TouchFixtures.fold(
            TouchFixtures.turn("t1", listOf(TouchFixtures.tool("t1", "a", "Write", "file_path", "/ws-B/digests/cat.md")), "ok"),
        )
        for (text in listOf("digests/cat.md:12", "./digests/cat.md", "cat.md:3:4")) {
            val range = FileLinks.detect(text, FileLinkSource.Code, TouchFixtures.CWD).single()
            assertEquals(text, "/ws-B/digests/cat.md", TouchFixtures.resolve(folded, range.mention!!, "t1"))
        }
    }
}
