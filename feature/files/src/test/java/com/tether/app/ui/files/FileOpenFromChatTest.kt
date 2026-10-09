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
        val words = "That path is outside the folders Tether may read."
        files.listings[png.path] = FilesResult.Failed(words, 403)
        files.listings[parent] = FilesResult.Failed(words, 403)
        val s = browser()
        s.open(png.path)
        advanceUntilIdle()
        assertEquals(words, s.error)
        assertNull(s.selected)
    }

    @Test fun aFileThatIsGoneIsNamedByTheServersOwnWordsAndNothingIsSelected() = runTest {
        val words = "ENOENT: no such file or directory, stat '/w/p/a.png'"
        files.listings[png.path] = FilesResult.Failed(words, 404)
        files.listings[parent] = FilesResult.Ok(listing(parent, listOf(dir("src", parent))))
        val s = browser()
        s.open(png.path)
        advanceUntilIdle()
        assertEquals("the parent is listed", parent, s.listing!!.current)
        assertEquals(words, s.mutationError)
        assertNull(s.selected)
        assertEquals("", s.error)
    }

    @Test fun aLaterOpenDoesNotInheritAnEarlierRefusal() = runTest {
        files.listings[png.path] = FilesResult.Failed("gone", 404)
        files.listings[parent] = FilesResult.Ok(listing(parent, emptyList()))
        val s = browser()
        s.open(png.path)
        advanceUntilIdle()
        assertEquals("gone", s.mutationError)
        // The file appears; opening it again selects it and shows no refusal.
        files.listings.remove(png.path)
        files.listings[parent] = FilesResult.Ok(listing(parent, listOf(png)))
        s.open(png.path)
        advanceUntilIdle()
        assertEquals(png, s.selected)
        assertEquals("", s.mutationError)
    }
}
