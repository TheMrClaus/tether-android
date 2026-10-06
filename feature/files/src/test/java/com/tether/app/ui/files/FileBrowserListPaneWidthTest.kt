package com.tether.app.ui.files

import com.tether.app.ui.video.LocalVideoSurfaceEnabled
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tether.app.client.FilesResult
import com.tether.app.ui.files.FilesFixtures.ROOT
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-sk1o: in the side-by-side layout the list pane is squeezed to its 20rem floor at ~560-1013dp
 * (800dp tablet, 914dp phone landscape). The Name column must keep room (ellipsis, never a sliver),
 * the whole Name cell must select the row, and New folder / New file / Upload must stay on screen.
 */
@RunWith(RobolectricTestRunner::class)
class FileBrowserListPaneWidthTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var state: FileBrowserState

    private fun launch() {
        val files = FakeFiles().apply {
            listings[ROOT] = FilesResult.Ok(
                FilesFixtures.listing(
                    entries = listOf(
                        FilesFixtures.docs,
                        FilesFixtures.file("a-rather-long-file-name-that-must-ellipsize-in-the-name-column.txt", 1234),
                        FilesFixtures.readme,
                    ),
                ),
            )
            texts[FilesFixtures.readme.path] = FilesResult.Ok(FilesFixtures.README_TEXT)
        }
        state = FileBrowserState(files, FakePlatform(), CoroutineScope(Dispatchers.Unconfined)).apply {
            cwd = ROOT
            sessionName = FilesFixtures.SESSION
            open()
        }
        rule.setContent {
            TetherTheme(TetherSkin.Studio.mode) {
                CompositionLocalProvider(LocalReducedMotion provides true, LocalVideoSurfaceEnabled provides false) {
                    FileBrowserFrame(state, onClose = {}, onUpload = {}, env = FilesFixtures.env)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun checkWidth(expectModified: Boolean) {
        launch()
        val density = rule.density.density
        val pane = rule.onNodeWithTag(FileBrowserTags.ListPane).fetchSemanticsNode().boundsInRoot
        // The Name cell is a real column: wide enough to read, and its text is on screen.
        val name = rule.onNodeWithText("README.md").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Name cell is ${name.width / density}dp wide", name.width / density >= 80f)
        // The toolbar keys sit fully inside the list pane (nothing clipped off its right edge).
        for (key in listOf("New folder", "New file", "Upload files")) {
            val bounds = rule.onNodeWithContentDescription(key).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$key right ${bounds.right} <= pane ${pane.right}", bounds.right <= pane.right + 0.5f)
            assertTrue("$key left ${bounds.left} >= pane ${pane.left}", bounds.left >= pane.left - 0.5f)
        }
        // The Parent folder key is on screen: labelled in full, or icon-only (same words as its description) when squeezed.
        if (expectModified) rule.onNodeWithText("Parent folder").assertIsDisplayed() else rule.onNodeWithContentDescription("Parent folder").assertIsDisplayed()
        if (expectModified) rule.onNodeWithText("Modified").assertIsDisplayed() else rule.onNodeWithText("Modified").assertIsNotDisplayed()
        // Tapping the visible Name cell selects the row.
        rule.onNodeWithText("README.md").performClick()
        rule.waitForIdle()
        assertEquals(FilesFixtures.readme.path, state.selected?.path)
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-mdpi")
    fun at800dpTheNameColumnKeepsRoomAndTheKeysAreOnScreen() = checkWidth(expectModified = false)

    @Test @Config(qualifiers = "w914dp-h412dp-mdpi")
    fun at914dpLandscapeTheNameColumnKeepsRoomAndTheKeysAreOnScreen() = checkWidth(expectModified = false)

    @Test @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun at1280dpTheFullTemplateStaysAndTheKeysAreOnScreen() = checkWidth(expectModified = true)
}
