package com.tether.app.ui.chat

import com.tether.app.client.ExistsBatches
import com.tether.app.client.FilesResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A relative mention links unless the server explicitly said its file is missing. Every other outcome keeps the link.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MentionExistenceTest {
    private val here = "/w/p/src/here.kt"
    private val gone = "/w/p/src/gone.kt"

    private fun TestScope.existence(files: FakeExistsFiles) = MentionExistence(files, this)

    /** What the detector draws for a code span, given [existence]. */
    private fun drawn(existence: MentionExistence?, text: String = "src/here.kt"): List<String> =
        FileLinks.detect(text, FileLinkSource.Code, "/w/p", exists = existence?.let { e -> { p: String -> e.request(p); e.get(p) } }).map { it.path }

    @Test fun anExistingFileLinksAndAMissingOneIsPlain() = runTest {
        val e = existence(FakeExistsFiles(setOf(here)))
        // Not answered yet: both stay links, as before.
        assertEquals(listOf(here), drawn(e))
        assertEquals(listOf(gone), drawn(e, "src/gone.kt"))
        advanceUntilIdle()
        assertEquals(true, e.get(here))
        assertEquals(false, e.get(gone))
        assertEquals(listOf(here), drawn(e))
        assertEquals(emptyList<String>(), drawn(e, "src/gone.kt"))
    }

    @Test fun askedPathsGoOutTogether() = runTest {
        val files = FakeExistsFiles(setOf(here))
        val e = existence(files)
        e.request(here)
        e.request(gone)
        e.request(here)
        advanceUntilIdle()
        assertEquals(listOf(listOf(here, gone)), files.asked.toList())
    }

    @Test fun everyOtherOutcomeKeepsTheLink() = runTest {
        val failures = listOf(
            FilesResult.Failed("not found", 404),
            FilesResult.Failed("forbidden", 403),
            FilesResult.Failed("unauthorized", 401),
            FilesResult.Failed("Could not reach the server"),
            FilesResult.Failed("not json", 200),
        )
        for (failure in failures) {
            val e = existence(FakeExistsFiles(failure = failure))
            e.request(gone)
            advanceUntilIdle()
            assertNull(failure.message, e.get(gone))
            assertEquals(failure.message, listOf(gone), drawn(e, "src/gone.kt"))
        }
        val unanswered = existence(FakeExistsFiles(unanswered = true))
        unanswered.request(gone)
        advanceUntilIdle()
        assertEquals(listOf(gone), drawn(unanswered, "src/gone.kt"))
    }

    @Test fun aFailedAskIsNotRetriedInItsEpochButIsAskedAgainAfterATurn() = runTest {
        val files = FakeExistsFiles(failure = FilesResult.Failed("down"))
        val e = existence(files)
        e.request(gone)
        advanceUntilIdle()
        e.request(gone)
        advanceUntilIdle()
        assertEquals(1, files.asked.size)
        e.endTurn()
        e.request(gone)
        advanceUntilIdle()
        assertEquals(2, files.asked.size)
    }

    @Test fun anAnswerIsAskedAgainAfterATurnAndTheOldOneStandsUntilThen() = runTest {
        val present = HashSet(setOf(here))
        val files = object : com.tether.app.client.WorkspaceFiles by com.tether.app.client.WorkspaceFiles.Unavailable {
            val asked = ArrayList<List<String>>()
            override suspend fun exists(paths: List<String>): FilesResult<List<Boolean?>> {
                asked += paths
                return FilesResult.Ok(paths.map { it in present })
            }
        }
        val e = MentionExistence(files, this)
        e.request(here)
        advanceUntilIdle()
        assertEquals(true, e.get(here))
        e.request(here) // same epoch: cached
        advanceUntilIdle()
        assertEquals(1, files.asked.size)
        present.clear()
        e.endTurn()
        assertEquals(true, e.get(here))
        e.request(here)
        advanceUntilIdle()
        assertEquals(2, files.asked.size)
        assertEquals(false, e.get(here))
    }

    @Test fun moreThanTwoHundredPathsSplitByCount() = runTest {
        val files = FakeExistsFiles()
        val e = existence(files)
        for (n in 0 until 450) e.request("/w/$n.kt")
        advanceUntilIdle()
        assertEquals(listOf(200, 200, 50), files.asked.map { it.size })
    }

    @Test fun longPathsSplitByBodySizeIncludingNonAscii() = runTest {
        val files = FakeExistsFiles()
        val e = existence(files)
        val paths = (0 until 30).map { n -> "/w/" + "é中".repeat(100) + "$n.kt" }
        paths.forEach(e::request)
        advanceUntilIdle()
        assertTrue(files.asked.size > 1)
        assertEquals(paths.toSet(), files.asked.flatten().toSet())
        for (chunk in files.asked) assertTrue(ExistsBatches.body(chunk).length <= ExistsBatches.MAX_BODY)
    }

    @Test fun anOverLongPathIsNotSentAndStaysALink() = runTest {
        val files = FakeExistsFiles()
        val e = existence(files)
        val long = "/w/p/" + "a".repeat(ExistsBatches.MAX_BODY) + ".kt"
        e.request(long)
        e.request(gone)
        advanceUntilIdle()
        assertEquals(listOf(listOf(gone)), files.asked.toList())
        assertNull(e.get(long))
    }
}
