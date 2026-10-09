package com.tether.app.ui.files

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.tether.app.client.FilesResult
import com.tether.app.ui.files.FilesFixtures.dir
import com.tether.app.ui.files.FilesFixtures.file
import com.tether.app.ui.files.FilesFixtures.listing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ta-9jnm F5: a file path the agent named opens in the browser exactly as the Files key's `open(path)` does: the parent
 * lists and the file is selected; when the server refuses or the file is gone, the server's own words are shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FileOpenFromChatTest {
    private val parent = "/w/p"
    private val png = file("a.png", 18_432, parent)
    private val files = FakeFiles()
    private val platform = FakePlatform()

    private fun TestScope.browser(): FileBrowserState = FileBrowserState(files, platform, CoroutineScope(StandardTestDispatcher(testScheduler))).apply {
        cwd = parent
        sessionName = "session"
    }

    @Test fun anImagePathListsItsParentSelectsItAndLoadsThePicture() = runTest {
        files.listings[parent] = FilesResult.Ok(listing(parent, listOf(dir("src", parent), png)))
        platform.image = ImageLoad.Ok(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap())
        val s = browser()
        s.open(png.path)
        advanceUntilIdle()
        assertEquals(png, s.selected)
        assertNotNull("the picture loaded", s.image)
        assertEquals("", s.error)
        assertEquals("", s.mutationError)
        assertEquals(listOf("list ${png.path}", "list $parent"), files.calls)
    }

    @Test fun aRefusalOnTheFileAndItsParentShowsTheServersWordsVerbatim() = runTest {
        val words = "That folder is not available."
        files.listings[png.path] = FilesResult.Failed(words, 403)
        files.listings[parent] = FilesResult.Failed(words, 403)
        val s = browser()
        s.open(png.path)
        advanceUntilIdle()
        assertEquals(words, s.error)
        assertNull(s.selected)
    }

    @Test fun aFileThatIsGoneIsNamedByTheFileRoutesWordsNotTheFolderListsAndNothingIsSelected() = runTest {
        // The real answers: the folder list refuses "list this file as a folder" with its own words; the file route says its own.
        files.listings[png.path] = FilesResult.Failed("That folder is not available.", 404)
        files.listings[parent] = FilesResult.Ok(listing(parent, listOf(dir("src", parent))))
        files.probes[png.path] = FilesResult.Failed("That file is not available.", 404)
        val s = browser()
        s.open(png.path)
        advanceUntilIdle()
        assertEquals("the parent is listed", parent, s.listing!!.current)
        assertEquals("That file is not available.", s.mutationError)
        assertNull(s.selected)
        assertEquals("", s.error)
        assertEquals(listOf("list ${png.path}", "list $parent", "probe ${png.path}"), files.calls)
    }

    @Test fun aFileOutsideTheAllowedRootsSaysSoInTheServersWords() = runTest {
        files.listings[png.path] = FilesResult.Failed("That folder is not available.", 404)
        files.listings[parent] = FilesResult.Ok(listing(parent, emptyList()))
        files.probes[png.path] = FilesResult.Failed("That file is outside the configured allowed roots.", 403)
        val s = browser()
        s.open(png.path)
        advanceUntilIdle()
        assertEquals("That file is outside the configured allowed roots.", s.mutationError)
        assertNull(s.selected)
    }

    @Test fun aFileTheListingHidesButTheServerServesRaisesNoBanner() = runTest {
        // A dotfile or a symlink: the folder list 404s on it and the listing omits it, the file route serves it.
        val env = file(".env", 10, parent)
        files.listings[env.path] = FilesResult.Failed("That folder is not available.", 404)
        files.listings[parent] = FilesResult.Ok(listing(parent, listOf(dir("src", parent))))
        val s = browser()
        s.open(env.path)
        advanceUntilIdle()
        assertEquals("the parent is listed", parent, s.listing!!.current)
        assertEquals("", s.mutationError)
        assertEquals("", s.error)
        assertNull(s.selected)
        assertEquals("the file route was asked", true, files.calls.contains("probe ${env.path}"))
    }

    @Test fun aFileTheListingHoldsIsNotProbed() = runTest {
        files.listings[parent] = FilesResult.Ok(listing(parent, listOf(png)))
        val s = browser()
        s.open(png.path)
        advanceUntilIdle()
        assertEquals(png, s.selected)
        assertEquals(false, files.calls.any { it.startsWith("probe") })
    }

    @Test fun aLaterOpenDoesNotInheritAnEarlierRefusal() = runTest {
        files.listings[png.path] = FilesResult.Failed("That folder is not available.", 404)
        files.listings[parent] = FilesResult.Ok(listing(parent, emptyList()))
        files.probes[png.path] = FilesResult.Failed("That file is not available.", 404)
        val s = browser()
        s.open(png.path)
        advanceUntilIdle()
        assertEquals("That file is not available.", s.mutationError)
        // The file appears; opening it again selects it and shows no refusal.
        files.listings.remove(png.path)
        files.probes.remove(png.path)
        files.listings[parent] = FilesResult.Ok(listing(parent, listOf(png)))
        s.open(png.path)
        advanceUntilIdle()
        assertEquals(png, s.selected)
        assertEquals("", s.mutationError)
    }
}
