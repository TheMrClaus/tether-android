package com.tether.app.ui.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.FilesAuthority
import com.tether.app.client.HttpWorkspaceFiles
import com.tether.app.ui.files.FilesFixtures.ROOT
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeMode
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ta-u2n: a rotation (the activity recreated, as MainActivity declares no configChanges) leaves
 * the browser where it was — open, in its folder, a claimed shared copy still inside its window,
 * a picked upload going to the folder it was picked in — while a real sign-out still ends the
 * session's browser and sweeps everything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class FileBrowserRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val server = MockWebServer()
    private val seen = ConcurrentLinkedQueue<RecordedRequest>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val identity = MutableStateFlow<String?>("paired-server")
    private lateinit var files: HttpWorkspaceFiles
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private var state: FileBrowserState? = null
    private val docs = "$ROOT/docs"

    private fun entry(name: String, parent: String, dir: Boolean = false) =
        """{"name":"$name","path":"$parent/$name","size":${if (dir) 4096 else 65},"mtime":${FilesFixtures.EPOCH_MS},"isDirectory":$dir}"""

    private fun listing(path: String, entries: String) =
        """{"current":"$path","parent":"${path.substringBeforeLast('/')}","breadcrumbs":[{"name":"${path.substringAfterLast('/')}","path":"$path"}],"entries":[$entries]}"""

    @Before fun setUp() {
        // FileProvider caches its paths per authority for the life of the process (one cache dir in
        // the app); Robolectric gives every test its own cache dir, so start each test uncached.
        (androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>).clear()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.requestUrl?.queryParameter("path")
                return when {
                    request.path!!.startsWith("/api/files/list") && path == docs -> MockResponse().setBody(listing(docs, entry("notes.md", docs)))
                    request.path!!.startsWith("/api/files/list") -> MockResponse().setBody(listing(ROOT, entry("docs", ROOT, dir = true) + "," + entry("README.md", ROOT)))
                    request.method == "GET" -> MockResponse().setBody(FilesFixtures.README_TEXT)
                    request.method == "PUT" -> MockResponse().setBody("""{"ok":true,"parent":"$path"}""")
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        server.start()
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        files = HttpWorkspaceFiles(http, authority = { FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_rot") } })
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        host()
    }

    @After fun tearDown() {
        scenario.close()
        server.shutdown()
        FileCache(context.cacheDir).sweepAll()
    }

    /** The shell's composition, set again after every recreation (as MainActivity's onCreate does). */
    private fun host() {
        scenario.onActivity { activity ->
            activity.setContent {
                TetherTheme(ThemeMode.Dark) {
                    CompositionLocalProvider(LocalReducedMotion provides true) {
                        val s = rememberFileBrowserState(files, identity)
                        s.cwd = ROOT
                        s.sessionName = FilesFixtures.SESSION
                        state = s
                        WorkspaceFileBrowser(s)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun rotate() {
        scenario.recreate()
        host()
    }

    /**
     * Polls with the main looper drained each time (see FileBrowserBehaviourTest.waitFor). The budget is
     * generous because the fake server is real sockets on a shared, often heavily loaded host; a passing
     * wait returns as soon as its condition holds, so this costs nothing when green (ta-u2n verifier, low 1).
     */
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(WAIT_MS) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    private fun waitForText(text: String) = waitFor { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun openInDocs() {
        compose.runOnIdle { state!!.open() }
        waitForText("README.md")
        compose.onNodeWithText("docs").performClick()
        waitForText("notes.md")
    }

    private fun shareDirs() = File(FileCache(context.cacheDir).root, FileCache.SHARE_DIR).listFiles().orEmpty().toList()

    private fun chooserStarted(): Boolean {
        var started = false
        scenario.onActivity { started = shadowOf(it).peekNextStartedActivity()?.action == Intent.ACTION_CHOOSER }
        return started
    }

    private fun shareReadme() {
        compose.runOnIdle { state!!.open() }
        waitForText("README.md")
        compose.onNodeWithContentDescription("Actions for README.md").performClick()
        compose.onNodeWithText("Share…").performClick()
        // The copy is made and the share sheet started; the claim runs in the same main-thread step.
        waitFor { chooserStarted() && state!!.pendingShare == null }
        assertEquals(1, shareDirs().size)
    }

    @Test fun aRotationKeepsTheBrowserOpenInItsFolderAndAClaimedCopyInItsWindow() {
        shareReadme()
        compose.onNodeWithText("docs").performClick()
        waitForText("notes.md")
        val before = state
        rotate()
        assertTrue("the same browser, not a fresh one", before === state)
        assertTrue(state!!.isOpen)
        assertEquals(docs, state!!.currentDir)
        compose.onNodeWithText("notes.md").assertExists()
        Thread.sleep(300) // any sweep would run on its own thread
        assertEquals("the receiving app can still read it", 1, shareDirs().size)
    }

    /** Starts the system picker from the Upload key and returns its request code. */
    private fun pickUpload(): Int {
        compose.onNodeWithContentDescription("Upload files").performClick()
        // ta-coik.67: the key opens the chooser; "Choose files" is the system picker.
        compose.onNodeWithText(UploadChooserCopy.CHOOSE_FILES).performClick()
        var code = -1
        scenario.onActivity { code = shadowOf(it).nextStartedActivityForResult.requestCode }
        return code
    }

    private fun deliverPick(requestCode: Int) {
        val picked = Uri.parse("content://picker.example/document/42")
        shadowOf(context.contentResolver).registerInputStream(picked, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        scenario.onActivity { it.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(picked)) }
        waitFor { seen.any { it.method == "PUT" } }
    }

    private fun putFolder() = seen.first { it.method == "PUT" }.requestUrl!!.queryParameter("path")

    @Test fun anUploadPickedBeforeARotationGoesToTheFolderItWasPickedIn() {
        openInDocs()
        val code = pickUpload()
        rotate()
        deliverPick(code)
        assertEquals(docs, putFolder())
    }

    @Test fun anUploadPickedBeforeTheStateIsLostStillGoesToThatFolder() {
        // As after process death: the in-memory browser is gone, only the saved state remains.
        openInDocs()
        val code = pickUpload()
        scenario.onActivity { it.viewModelStore.clear() }
        rotate()
        assertFalse("a fresh browser", state!!.isOpen)
        deliverPick(code)
        assertEquals("not the session root", docs, putFolder())
    }

    @Test fun aSignOutStillEndsTheBrowserAndSweepsEverything() {
        shareReadme()
        val before = state
        identity.value = null
        waitFor { shareDirs().isEmpty() }
        compose.waitForIdle()
        assertNotSame("the signed-out session's browser is gone", before, state)
        assertFalse(state!!.isOpen)
        assertTrue(compose.onAllNodesWithText("notes.md").fetchSemanticsNodes().isEmpty())
    }

    @Test fun anotherServerAlsoEndsTheSession() {
        shareReadme()
        identity.value = "another-server"
        waitFor { shareDirs().isEmpty() }
    }
}

private const val WAIT_MS = 20_000L
