package com.tether.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import com.tether.app.client.BrowseStatus
import com.tether.app.client.WorkspaceSelectStatus
import com.tether.app.protocol.model.DirectoryEntry
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T8.2: components/folder-picker-dialog.tsx (tether 90fbb9f) through the semantics tree: "Create a
 * new folder" (:76-97), the "couldn't load this folder" state (:101-106), and after "Use this folder"
 * the `opening` / `stalled` states, the dialog closing only once the selection resolves (:46-53,
 * :116-135).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class FolderPickerDialogTest {
    @get:Rule val compose = createComposeRule()

    private val listing = DirectoryListing("/srv/work", parent = "/srv", entries = listOf(DirectoryEntry("app", "/srv/work/app")))
    private var open by mutableStateOf(true)
    private var browseStatus by mutableStateOf<BrowseStatus?>(null)
    private val created = mutableListOf<Pair<String, String>>()
    private val chosen = mutableListOf<String>()
    private val browsed = mutableListOf<String>()

    private fun show(selectStatus: StateFlow<WorkspaceSelectStatus?>? = null, onChoose: (String) -> Unit = { chosen += it }) {
        compose.setContent {
            TetherTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    if (open) {
                        FolderPickerDialog(
                            directories = listing,
                            current = "/srv",
                            onDismiss = { open = false },
                            onBrowse = { browsed += it },
                            onChoose = onChoose,
                            onCreateFolder = { cwd, name -> created += cwd to name },
                            browseStatus = browseStatus,
                            selectStatus = selectStatus,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun exists(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun tap(t: String) {
        tag(t).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** The editable field inside [t] (the well carries the tag). */
    private fun editable(t: String) = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(t)), useUnmergedTree = true)

    private fun type(t: String, text: String) {
        editable(t).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }
        compose.waitForIdle()
    }

    private fun label(t: String) = tag(t).fetchSemanticsNode().config.let { c ->
        c.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString { it.text }
            ?: c.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.joinToString()
    }

    private fun keyText(t: String): String = compose.onNodeWithTag(t).fetchSemanticsNode().let { node ->
        (node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString { it.text }).orEmpty() +
            node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.joinToString().orEmpty()
    }

    @Test fun createAFolderSendsTheNameInTheFolderBeingBrowsed() {
        show()
        assertFalse(exists(FolderPickerTags.NewFolderName))
        assertEquals("Create a new folder", label(FolderPickerTags.CreateToggle))
        tap(FolderPickerTags.CreateToggle)
        // :93 `disabled={!newFolderName.trim()}`.
        tag(FolderPickerTags.Create).assertIsNotEnabled()
        type(FolderPickerTags.NewFolderName, "   ")
        tag(FolderPickerTags.Create).assertIsNotEnabled()
        type(FolderPickerTags.NewFolderName, "  reports ")
        tag(FolderPickerTags.Create).assertIsEnabled()
        tap(FolderPickerTags.Create)
        // :58-66: trimmed, in the listing's folder; the form closes, the picker stays.
        assertEquals(listOf("/srv/work" to "reports"), created)
        assertFalse(exists(FolderPickerTags.NewFolderName))
        assertTrue(open)
    }

    @Test fun theNameIsCappedAndCancelClosesTheForm() {
        show()
        tap(FolderPickerTags.CreateToggle)
        type(FolderPickerTags.NewFolderName, "x".repeat(250))
        val text = editable(FolderPickerTags.NewFolderName).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(200, text.length)
        tap(FolderPickerTags.CreateCancel)
        assertFalse(exists(FolderPickerTags.NewFolderName))
        assertTrue(created.isEmpty())
    }

    @Test fun aLostBrowseShowsCouldNotLoadThisFolder() {
        show()
        assertFalse(exists(FolderPickerTags.LoadError))
        browseStatus = BrowseStatus("r1", "/srv/work/app", BrowseStatus.Phase.Loading)
        compose.waitForIdle()
        assertFalse(exists(FolderPickerTags.LoadError))
        browseStatus = BrowseStatus("r1", "/srv/work/app", BrowseStatus.Phase.Error)
        compose.waitForIdle()
        assertTrue(exists(FolderPickerTags.LoadError))
        assertTrue(shown("Couldn’t load this folder — the secure link is reconnecting. It will load automatically."))
        browseStatus = null
        compose.waitForIdle()
        assertFalse(exists(FolderPickerTags.LoadError))
    }

    @Test fun rowsBrowse() {
        show()
        compose.onAllNodesWithText("app").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        compose.onAllNodesWithText("Parent folder")[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.onAllNodesWithText("app")[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf("/srv", "/srv/work/app"), browsed)
    }

    @Test fun withoutASelectStatusAPickClosesAtOnce() {
        show()
        tap(FolderPickerTags.Use)
        assertEquals(listOf("/srv/work"), chosen)
        assertFalse(open)
    }

    @Test fun aPickStaysOpenWhileOpeningAndClosesOnceConfirmed() {
        val select = MutableStateFlow<WorkspaceSelectStatus?>(null)
        show(select, onChoose = { cwd ->
            chosen += cwd
            select.value = WorkspaceSelectStatus(cwd, "req-1", WorkspaceSelectStatus.Phase.Opening)
        })
        assertTrue(keyText(FolderPickerTags.Use).contains("Use this folder"))
        tap(FolderPickerTags.Use)
        assertTrue("still open while opening", open)
        assertTrue(keyText(FolderPickerTags.Use).contains("Opening…"))
        tag(FolderPickerTags.Use).assertIsNotEnabled()
        assertTrue(keyText(FolderPickerTags.Cancel).contains("Close"))
        assertFalse(exists(FolderPickerTags.OpenError))
        // :116-121: the link went quiet — "couldn't open — retrying", Use enabled again.
        select.value = WorkspaceSelectStatus("/srv/work", "req-1", WorkspaceSelectStatus.Phase.Stalled)
        compose.waitForIdle()
        assertTrue(open)
        assertTrue(exists(FolderPickerTags.OpenError))
        assertTrue(shown("Couldn’t open this workspace yet — the secure link is reconnecting. It will open automatically."))
        tag(FolderPickerTags.Use).assertIsEnabled()
        assertTrue(keyText(FolderPickerTags.Use).contains("Use this folder"))
        // The server confirmed (the intent cleared): the dialog closes.
        select.value = null
        compose.waitForIdle()
        assertFalse(open)
    }

    @Test fun aSupersededSelectionClosesThePicker() {
        val select = MutableStateFlow<WorkspaceSelectStatus?>(null)
        show(select, onChoose = { cwd -> select.value = WorkspaceSelectStatus(cwd, "req-1", WorkspaceSelectStatus.Phase.Opening) })
        tap(FolderPickerTags.Use)
        assertTrue(open)
        select.value = WorkspaceSelectStatus("/elsewhere", "req-2", WorkspaceSelectStatus.Phase.Opening)
        compose.waitForIdle()
        assertFalse(open)
    }

    @Test fun closeWhileOpeningDismisses() {
        val select = MutableStateFlow<WorkspaceSelectStatus?>(null)
        show(select, onChoose = { cwd -> select.value = WorkspaceSelectStatus(cwd, "req-1", WorkspaceSelectStatus.Phase.Opening) })
        tap(FolderPickerTags.Use)
        tap(FolderPickerTags.Cancel)
        assertFalse(open)
    }
}
