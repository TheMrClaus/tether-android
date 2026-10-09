package com.tether.app.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.FilesResult
import com.tether.app.client.TetherClient
import com.tether.app.client.WorkspaceBreadcrumb
import com.tether.app.client.WorkspaceFileEntry
import com.tether.app.client.WorkspaceFileListing
import com.tether.app.client.WorkspaceFiles
import com.tether.app.client.WorkspaceMutation
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.files.FileBrowserTags
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-9jnm F6: through the whole shell, a file the agent named is a link; a tap opens the file browser on the file: its
 * parent is listed (the breadcrumbs end there) and the file is selected (its name is the preview's title).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1000dp-mdpi")
class ShellFileLinkTest {
    @get:Rule val rule = createComposeRule()

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(1200, 1000)
    }

    private val session = AgentSession(id = "s1", provider = "claude", name = "Notes", cwd = "/w", status = "active", startedAt = 1, updatedAt = 1)

    private val tree = foldTree(
        freshTree(),
        ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
        ev("user_message_accepted", "t1", ts = 1) { put("text", "Where are the notes?") },
        ev("message_started", "t1", ts = 1) { put("blockId", "t1:m0") },
        ev("message_completed", "t1", ts = 1) { put("blockId", "t1:m0"); put("text", "I wrote them to /w/notes.txt for you.") },
        ev("turn_end", "t1", ts = 1) { put("outcome", "ok") },
    )

    private val notes = WorkspaceFileEntry("notes.txt", "/w/notes.txt", 12, 0.0, false)

    private class Files(val notes: WorkspaceFileEntry) : WorkspaceFiles {
        val calls = mutableListOf<String>()
        override suspend fun list(path: String): FilesResult<WorkspaceFileListing> {
            calls += "list $path"
            return if (path == "/w") {
                FilesResult.Ok(WorkspaceFileListing("/w", "/", listOf(WorkspaceBreadcrumb("/", "/"), WorkspaceBreadcrumb("w", "/w")), listOf(notes)))
            } else {
                FilesResult.Failed("Not a folder.", 400)
            }
        }
        override suspend fun readText(path: String, listedSize: Long): FilesResult<String> = FilesResult.Ok("remember the milk")
        override suspend fun mkdir(parent: String, name: String): FilesResult<WorkspaceMutation> = error("unused")
        override suspend fun touch(parent: String, name: String): FilesResult<WorkspaceMutation> = error("unused")
        override suspend fun rename(path: String, name: String): FilesResult<WorkspaceMutation> = error("unused")
        override suspend fun move(path: String, destination: String): FilesResult<WorkspaceMutation> = error("unused")
        override suspend fun copy(path: String, destination: String): FilesResult<WorkspaceMutation> = error("unused")
        override suspend fun delete(path: String): FilesResult<WorkspaceMutation> = error("unused")
        override suspend fun upload(parent: String, name: String, source: com.tether.app.client.UploadSource, overwrite: Boolean): FilesResult<WorkspaceMutation> = error("unused")
        override suspend fun head(path: String): FilesResult<com.tether.app.client.FileHead> = error("unused")
        override suspend fun download(path: String, maxBytes: Long, sink: java.io.OutputStream): FilesResult<Long> = error("unused")
    }

    private val files = Files(notes)

    private class ClientWithFiles(private val base: TetherClient, override val files: WorkspaceFiles) : TetherClient by base

    private fun host() {
        val base = ShellConsentClient().also { it.show(session, tree) }
        val vm = TetherViewModel(ClientWithFiles(base, files))
        vm.selectSession("s1")
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent { TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } } }
        rule.waitForIdle()
    }

    private fun tapTheLink() {
        val node = rule.onNode(
            SemanticsMatcher("has a link") { n -> n.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.getLinkAnnotations(0, it.length).isNotEmpty() } == true },
        ).fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].first { it.getLinkAnnotations(0, it.length).isNotEmpty() }
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals("/w/notes.txt", text.substring(link.start, link.end))
        val clickable = link.item as LinkAnnotation.Clickable
        rule.runOnUiThread { clickable.linkInteractionListener!!.onClick(clickable) }
        rule.waitForIdle()
    }

    @Test fun tappingAMentionedFileOpensTheBrowserOnIt() {
        host()
        tapTheLink()
        rule.onNodeWithTag(FileBrowserTags.Dialog).assertExists()
        // The parent is listed: the breadcrumbs end at it.
        val current = rule.onAllNodes(
            SemanticsMatcher("current crumb") { it.config.getOrElseNullable(SemanticsProperties.StateDescription) { null } == "Current folder" } and
                hasAnyAncestor(hasTestTag(FileBrowserTags.Breadcrumbs)),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertEquals(1, current.size)
        assertTrue(rule.onAllNodes(hasText("w") and hasAnyAncestor(hasTestTag(FileBrowserTags.Breadcrumbs)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        // The file is selected: its name is the preview's title, its text is read.
        assertTrue(rule.onAllNodes(hasText("notes.txt") and hasAnyAncestor(hasTestTag(FileBrowserTags.Preview)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals(listOf("list /w/notes.txt", "list /w"), files.calls)
    }
}
