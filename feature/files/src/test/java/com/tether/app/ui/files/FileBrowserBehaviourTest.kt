package com.tether.app.ui.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.FilesAuthority
import com.tether.app.client.HttpWorkspaceFiles
import com.tether.app.protocol.TetherJson
import com.tether.app.ui.files.FilesFixtures.ROOT
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeChoice
import com.tether.app.ui.theme.ThemeMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T11.1 end to end on the JVM (emulators are owner-deferred; PLAN §4's behaviour run): the real
 * host ([rememberFileBrowserState] + [WorkspaceFileBrowser] + [AndroidBrowserPlatform]) over the
 * real HTTP client against a MockWebServer "Tether" seeded like the web's parity-app scenario.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class FileBrowserBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private val server = MockWebServer()
    private val seen = ConcurrentLinkedQueue<RecordedRequest>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var state: FileBrowserState
    private val identity = kotlinx.coroutines.flow.MutableStateFlow<String?>("paired-server")
    private val png by lazy {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(AColor.rgb(92, 110, 230)) }
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun entry(name: String, size: Long, dir: Boolean = false) =
        """{"name":"$name","path":"$ROOT/$name","size":$size,"mtime":${FilesFixtures.EPOCH_MS},"isDirectory":$dir}"""

    private val listingJson get() = """{"current":"$ROOT","parent":"${ROOT.substringBeforeLast('/')}","breadcrumbs":[{"name":"/","path":"/"},{"name":"parity-app","path":"$ROOT"}],
        "entries":[${entry("docs", 4096, true)},${entry("page.html", 25)},${entry("README.md", 65)},${entry("shot.png", png.size.toLong())}]}"""

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.requestUrl?.queryParameter("path")
                return when {
                    request.path!!.startsWith("/api/files/list") -> MockResponse().setBody(listingJson)
                    request.method == "GET" && path == "$ROOT/README.md" -> MockResponse().setResponseCode(206).setBody(FilesFixtures.README_TEXT)
                    request.method == "GET" && path == "$ROOT/page.html" -> MockResponse().setBody("<script>alert(1)</script>")
                    request.method == "GET" && path == "$ROOT/shot.png" -> MockResponse().setBody(Buffer().write(png))
                    request.method == "DELETE" || request.method == "POST" -> MockResponse().setBody("""{"ok":true,"parent":"$ROOT"}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"That file is not available."}""")
                }
            }
        }
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun launch(show: () -> Boolean = { true }) {
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        val files = HttpWorkspaceFiles(http, authority = { FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_e2e") } })
        rule.setContent {
            TetherTheme(ThemeChoice(TetherSkin.Machine.family, ThemeMode.Dark)) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    if (show()) {
                        state = rememberFileBrowserState(files, identity)
                        state.cwd = ROOT
                        state.sessionName = FilesFixtures.SESSION
                        WorkspaceFileBrowser(state)
                    }
                }
            }
        }
        rule.runOnIdle { state.open() }
        waitFor { rule.onAllNodesWithText("README.md").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun requests(method: String) = seen.filter { it.method == method }

    /**
     * The browser's jobs run on the main looper (viewModelScope), which compose's waitUntil does
     * not always drain under Robolectric's paused looper: run it on every poll, as a device would.
     */
    private fun waitFor(timeout: Long = 5_000, condition: () -> Boolean) = rule.waitUntil(timeout) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    @Test fun theListingComesFromThePairedServerWithTheCredential() {
        launch()
        val list = requests("GET").first { it.path!!.startsWith("/api/files/list") }
        assertEquals("/api/files/list?path=${HttpWorkspaceFiles.encodeUriComponent(ROOT)}", list.path)
        assertEquals("Bearer tthr_e2e", list.getHeader("Authorization"))
        rule.onNodeWithText("4 items").assertIsDisplayed()
        rule.onNodeWithText("Workspace files").assertIsDisplayed()
        rule.onNodeWithText("Browse Summarize the README without leaving the console.").assertExists()
        // The case swallows taps without semantics: TalkBack reaches every control on its own.
        assertFalse(rule.onNodeWithTag(FileBrowserTags.Dialog).fetchSemanticsNode().config.isMergingSemanticsOfDescendants)
        rule.onNodeWithContentDescription("Close file browser").assertIsDisplayed()
    }

    @Test fun aTextFileOpensInThePreviewAndBackReturnsToTheList() {
        launch()
        rule.onNodeWithText("README.md").performClick()
        waitFor { rule.onAllNodesWithText("A tiny fixture project for the parity screenshots.").fetchSemanticsNodes().isNotEmpty() }
        val get = requests("GET").first { it.requestUrl?.queryParameter("path") == "$ROOT/README.md" }
        assertEquals("bytes=0-1048575", get.getHeader("Range"))
        // Phone = master-detail: the list is gone while previewing.
        rule.onNodeWithTag(FileBrowserTags.ListPane).assertDoesNotExist()
        rule.onNodeWithContentDescription("Back to file list").performClick()
        rule.onNodeWithTag(FileBrowserTags.ListPane).assertIsDisplayed()
    }

    @Test fun markupIsShownAsLiteralTextNeverRun() {
        launch()
        rule.onNodeWithText("page.html").performClick()
        waitFor { rule.onAllNodesWithText("<script>alert(1)</script>").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasTestTag(FileBrowserTags.Text)).assertExists()
    }

    @Test fun anImageIsDecodedFromAScratchCopyThatIsDeletedAtOnce() {
        launch()
        rule.onNodeWithText("shot.png").performClick()
        waitFor { rule.onAllNodes(hasTestTag(FileBrowserTags.Image)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Preview of shot.png").assertIsDisplayed()
        val scratch = File(context.cacheDir, FileCache.DIR).listFiles().orEmpty().filter { it.name.startsWith("preview-") }
        assertTrue("no preview copy outlives its decode: $scratch", scratch.isEmpty())
    }

    @Test fun deleteGoesThroughItsConfirmationThenRelists() {
        launch()
        val listsBefore = requests("GET").count { it.path!!.startsWith("/api/files/list") }
        rule.onNodeWithContentDescription("Actions for README.md").performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Delete README.md?").assertIsDisplayed()
        rule.onNodeWithText("This file will be permanently deleted.").assertIsDisplayed()
        assertTrue("nothing deleted before the confirm", requests("DELETE").isEmpty())
        rule.onNode(hasText("Delete") and hasContentDescription("Delete")).performClick()
        waitFor { requests("DELETE").isNotEmpty() }
        assertEquals("/api/files?path=${HttpWorkspaceFiles.encodeUriComponent("$ROOT/README.md")}", requests("DELETE").single().path)
        waitFor { requests("GET").count { it.path!!.startsWith("/api/files/list") } > listsBefore }
        rule.onNodeWithText("Delete README.md?").assertDoesNotExist()
    }

    @Test fun aFolderDeleteSaysEverythingInsideGoesToo() {
        launch()
        rule.onNodeWithContentDescription("Actions for docs").performClick()
        // A folder has nothing to save or share.
        rule.onNodeWithText("Save to device…").assertDoesNotExist()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("This folder and everything inside it will be permanently deleted.").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue(requests("DELETE").isEmpty())
    }

    @Test fun newFolderPostsTheNameToMkdir() {
        launch()
        rule.onNodeWithContentDescription("New folder").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("notes")
        rule.onNodeWithText("Create").performClick()
        waitFor { requests("POST").isNotEmpty() }
        val post = requests("POST").single()
        assertEquals("/api/files/mkdir", post.path)
        val body = TetherJson.parseToJsonElement(post.body.readUtf8()) as JsonObject
        assertEquals(ROOT, body["path"]!!.jsonPrimitive.content)
        assertEquals("notes", body["name"]!!.jsonPrimitive.content)
    }

    @Test fun renameFromTheActionsSheetPostsTheEntryAndTheNewName() {
        launch()
        rule.onNodeWithContentDescription("Actions for README.md").performClick()
        rule.onNodeWithText("Rename").performClick()
        rule.onNodeWithText("Rename README.md").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextReplacement("GUIDE.md")
        rule.onNode(hasText("Rename") and hasContentDescription("Rename")).performClick()
        waitFor { requests("POST").isNotEmpty() }
        val body = TetherJson.parseToJsonElement(requests("POST").single().body.readUtf8()) as JsonObject
        assertEquals("/api/files/rename", requests("POST").single().path)
        assertEquals("$ROOT/README.md", body["path"]!!.jsonPrimitive.content)
        assertEquals("GUIDE.md", body["name"]!!.jsonPrimitive.content)
    }

    @Test fun leavingCompositionKeepsSharedCopiesAndSignOutSweepsThem() {
        var shown by mutableStateOf(true)
        launch { shown }
        val share = FileCache(context.cacheDir).newShareFile("secret.txt").apply { writeText("workspace content") }
        // Composition going away (a rotation does this) is not the end of the session…
        shown = false
        rule.waitForIdle()
        Thread.sleep(300)
        assertTrue("a copy inside its window survives", share.exists())
        // …signing out is: nothing that session downloaded outlives it.
        identity.value = null
        waitFor { !share.exists() }
        assertFalse("sign-out leaves nothing a session downloaded", share.exists())
    }
}
