package com.tether.app.ui.files

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import com.tether.app.client.FilesResult
import com.tether.app.ui.files.FilesFixtures.ROOT
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ta-coik.67: the web's workspace Upload is a bare `<input type="file" multiple>`
 * (workspace-file-browser.tsx:480-486 at 29537e0), which Android Chrome answers with a chooser that
 * offers the Camera beside the files. The Upload key opens a chooser of the same two choices: the
 * system picker as before, or the system camera (TakePicture) writing into the module's OWN capture
 * provider; the picture uploads like a picked file and its scratch file is deleted when the upload
 * is over or the capture cancelled. Robolectric has no camera app: the registry plays it, writing
 * through the granted URI as a camera app would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class UploadChooserTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val files = FakeFiles().apply { listings[ROOT] = FilesResult.Ok(FilesFixtures.listing()) }
    private val platform = FakePlatform()
    private lateinit var state: FileBrowserState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** What the "camera app" does with the URI it is handed: null = the operator cancelled. */
    private var camera: ((Uri) -> Unit)? = null
    private val launched = mutableListOf<Pair<ActivityResultContract<*, *>, Any?>>()

    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launched += contract to input
                if (contract !is ActivityResultContracts.TakePicture) return // the document picker: no answer here
                val shoot = camera
                if (shoot == null) {
                    dispatchResult(requestCode, false)
                } else {
                    shoot(input as Uri)
                    dispatchResult(requestCode, true)
                }
            }
        }
    }

    /** FileProvider caches each authority's roots process-wide; Robolectric gives every test a new cache dir. */
    @Before fun forgetFileProviderRoots() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        synchronized(cache.get(null)!!) { (cache.get(null) as MutableMap<*, *>).clear() }
    }

    @After fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        UploadCaptures.dir(rule.activity).deleteRecursively()
    }

    private fun show() {
        state = FileBrowserState(files, platform, scope).apply {
            cwd = ROOT
            sessionName = FilesFixtures.SESSION
        }
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                TetherTheme(ThemeMode.Dark) {
                    CompositionLocalProvider(LocalReducedMotion provides true) { WorkspaceFileBrowser(state) }
                }
            }
        }
        rule.runOnIdle { state.open() }
        waitFor { rule.onAllNodesWithText("README.md").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitFor(timeout: Long = 10_000, condition: () -> Boolean) = rule.waitUntil(timeout) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    private fun pressUpload() {
        rule.onNodeWithContentDescription("Upload files").performClick()
        rule.waitForIdle()
    }

    private fun captureFiles(): List<File> = UploadCaptures.dir(rule.activity).listFiles()?.toList().orEmpty()

    @Test fun theUploadKeyOffersTheFilesAndTheCameraAndOpensNeitherByItself() {
        show()
        pressUpload()
        rule.onNodeWithText(UploadChooserCopy.CHOOSE_FILES).assertExists()
        rule.onNodeWithText(UploadChooserCopy.TAKE_PHOTO).assertExists()
        rule.onNodeWithText("Cancel").assertExists()
        assertTrue("nothing launched until a choice", launched.isEmpty())
    }

    @Test fun chooseFilesOpensTheSystemDocumentPickerForAnyType() {
        show()
        pressUpload()
        rule.onNodeWithText(UploadChooserCopy.CHOOSE_FILES).performClick()
        rule.waitForIdle()
        val (contract, input) = launched.single()
        assertTrue(contract is ActivityResultContracts.OpenMultipleDocuments)
        assertArrayEquals(arrayOf("*/*"), input as Array<*>)
        rule.onAllNodesWithText(UploadChooserCopy.TAKE_PHOTO).assertCountEquals(0)
    }

    @Test fun cancellingTheChooserLaunchesNothing() {
        show()
        pressUpload()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue(launched.isEmpty())
        rule.onAllNodesWithText(UploadChooserCopy.TAKE_PHOTO).assertCountEquals(0)
    }

    @Test fun aTakenPhotoIsUploadedLikeAPickedFileFromTheFolderAndItsFileDeleted() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 5)
        var during = emptyList<File>()
        camera = { uri ->
            // The camera app writes through the granted URI of the module's own capture provider.
            val out = rule.activity.contentResolver.openOutputStream(uri)
            assertNotNull("the capture URI is writable", out)
            out!!.use { it.write(bytes) }
            during = captureFiles()
        }
        show()
        pressUpload()
        rule.onNodeWithText(UploadChooserCopy.TAKE_PHOTO).performClick()
        waitFor { files.uploaded.isNotEmpty() }

        val (contract, input) = launched.single()
        assertTrue("the system camera contract", contract is ActivityResultContracts.TakePicture)
        val uri = input as Uri
        assertEquals("content", uri.scheme)
        assertEquals(UploadCaptures.authority(rule.activity), uri.authority)
        assertEquals("one capture file existed while the camera ran", 1, during.size)

        val (name, body) = files.uploaded.single()
        assertTrue("a camera name, $name", Regex("IMG_\\d{8}_\\d{6}\\.jpg").matches(name))
        assertArrayEquals(bytes, body)
        assertTrue("uploaded into the folder the key was pressed in", files.calls.contains("upload $ROOT $name"))
        waitFor { captureFiles().isEmpty() }
        assertTrue("the scratch photo is deleted after the upload", captureFiles().isEmpty())
    }

    @Test fun aCancelledCaptureUploadsNothingAndLeavesNoFile() {
        camera = null
        show()
        pressUpload()
        rule.onNodeWithText(UploadChooserCopy.TAKE_PHOTO).performClick()
        rule.waitForIdle()
        assertEquals(1, launched.size)
        assertTrue(files.uploaded.isEmpty())
        assertTrue("the cancelled capture's file is deleted", captureFiles().isEmpty())
    }

    @Test fun aCaptureTheCameraLeftEmptyUploadsNothingAndLeavesNoFile() {
        camera = { _ -> }
        show()
        pressUpload()
        rule.onNodeWithText(UploadChooserCopy.TAKE_PHOTO).performClick()
        rule.waitForIdle()
        assertTrue(files.uploaded.isEmpty())
        assertTrue(captureFiles().isEmpty())
    }

    @Test fun aPhotoWhoseUploadFailsIsStillDeleted() {
        camera = { uri -> rule.activity.contentResolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(1, 2, 3)) } }
        files.failures["upload"] = FilesResult.Failed("That file could not be uploaded.")
        show()
        pressUpload()
        rule.onNodeWithText(UploadChooserCopy.TAKE_PHOTO).performClick()
        waitFor { files.calls.any { it.startsWith("upload ") } }
        waitFor { captureFiles().isEmpty() }
        assertTrue(captureFiles().isEmpty())
    }

    @Test fun oldLeftoversAreSweptByTheNextCaptureButAFreshOneIsKept() {
        val dir = UploadCaptures.dir(rule.activity).apply { mkdirs() }
        val stale = File(dir, "stale.jpg").apply { writeText("x"); setLastModified(System.currentTimeMillis() - UploadCaptures.STALE_MS - 1_000) }
        val fresh = File(dir, "fresh.jpg").apply { writeText("x") }
        val target = UploadCaptures.newTarget(rule.activity)
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(target.exists() && target.length() == 0L && target.parentFile == dir)
    }

    @Test fun theCaptureProviderServesOnlyItsOwnFolderAndTheShareProviderOnlyItsOwn() {
        val context: Context = rule.activity
        val capture = UploadCaptures.newTarget(context)
        assertEquals(UploadCaptures.authority(context), UploadCaptures.uriFor(context, capture).authority)
        // Nothing else under the cache, the share folder included, can be granted to the camera.
        val share = FileCache(context.cacheDir).newShareFile("x.txt").apply { writeText("secret") }
        for (other in listOf(share, File(context.cacheDir, "workspace-files/preview.bin"), File(context.filesDir, "x"))) {
            try {
                UploadCaptures.uriFor(context, other)
                throw AssertionError("the capture provider must not serve $other")
            } catch (_: IllegalArgumentException) {
                // not covered by the provider's paths
            }
        }
        // …and the capture folder is not served by the share provider's authority.
        try {
            FileProvider.getUriForFile(context, FileCache.authority(context), capture)
            throw AssertionError("the share provider must not serve captures")
        } catch (_: IllegalArgumentException) {
        }
        FileCache(context.cacheDir).sweepAll()
    }

    @Test fun theManifestAndPathsDeclareOneUnexportedWriteGrantProviderOverOneFolder() {
        val paths = File("src/main/res/xml/workspace_capture_paths.xml").readText()
        assertEquals(1, Regex("<(cache|files|external|root)[-a-z]*-path").findAll(paths).count())
        assertTrue(paths.contains("<cache-path name=\"capture\" path=\"workspace-capture/\" />"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val provider = Regex("<provider[^>]*WorkspaceCaptureProvider[^>]*>", RegexOption.DOT_MATCHES_ALL).find(manifest)!!.value
        assertTrue(provider.contains("android:exported=\"false\""))
        assertTrue(provider.contains("android:authorities=\"\${applicationId}.workspacecapture\""))
        assertFalse("no CAMERA permission is declared", manifest.contains("android.permission.CAMERA"))
    }
}
