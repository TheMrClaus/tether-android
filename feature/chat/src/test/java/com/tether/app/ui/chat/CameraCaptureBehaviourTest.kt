package com.tether.app.ui.chat

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import com.tether.app.client.AttachmentSendResult
import com.tether.app.client.StagedAttachment
import com.tether.app.client.StagedAttachments
import com.tether.app.ui.theme.TetherSkin
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.3: the attach sheet's "Take photo" row. The web reaches the camera through Chrome's
 * file-input chooser (chat-view.tsx pickImages/pickFiles, draft-composer.tsx's bare input); the app
 * opens the system camera (TakePicture) on a capture file it made, served by its own FileProvider,
 * and stages what the camera wrote through the same intake as a pick: nothing is sent, the capture
 * file is deleted once read, and a cancelled capture stages nothing. Robolectric has no camera app:
 * the registry plays it, writing through the granted URI as a camera app would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class CameraCaptureBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val store = StagedAttachments()
    private val stager = AttachmentStager(store, { ORIGIN })
    private var staged by mutableStateOf(emptyList<StagedAttachment>())
    private val attachmentSends = mutableListOf<String>()
    private val textSends = mutableListOf<String>()

    /** What the "camera app" does with the URI it is handed: null = the operator cancelled. */
    private var camera: ((Uri) -> Unit)? = null
    private val launched = mutableListOf<Pair<ActivityResultContract<*, *>, Any?>>()

    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launched += contract to input
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

    /**
     * FileProvider caches each authority's roots in a process-wide map; Robolectric gives every test a
     * new cache directory in the same JVM, so the map is emptied first (one process on a device).
     */
    @Before
    fun forgetFileProviderRoots() {
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        synchronized(cache.get(null)!!) { (cache.get(null) as MutableMap<*, *>).clear() }
    }

    private fun show() {
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                ComposerHost(TetherSkin.StudioDark) {
                    Composer(
                        session = ComposerFixtures.session,
                        projection = ComposerFixtures.idle.projection,
                        controls = null,
                        serverNow = { ComposerFixtures.BUSY_NOW },
                        onSend = { text, _ ->
                            textSends += text
                            true
                        },
                        onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                        onQueueEdit = { _, _ -> },
                        onQueueRemove = {},
                        onRequestControls = {},
                        liveness = ComposerLiveness.Live,
                        attachments = ComposerAttachments(
                            staged = staged,
                            stage = { sources -> stager.stage(ComposerFixtures.SESSION_ID, sources).also { staged = store.items(ORIGIN, ComposerFixtures.SESSION_ID) } },
                            onRemove = { id -> store.remove(ORIGIN, ComposerFixtures.SESSION_ID, id); staged = store.items(ORIGIN, ComposerFixtures.SESSION_ID) },
                            send = { text, _ ->
                                attachmentSends += text
                                AttachmentSendResult.Sent
                            },
                        ),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun tapTakePhoto() {
        rule.onNodeWithContentDescription("Add attachment").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription(ATTACH_ROW_CAMERA).performClick()
        rule.waitForIdle()
    }

    private fun pngBytes(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(20, 10, android.graphics.Bitmap.Config.ARGB_8888)
        return java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun captureFiles(): List<File> = CameraCaptures.dir(rule.activity).listFiles()?.toList().orEmpty()

    @Test
    fun theSheetOffersTheCameraRowBesideTheWebsRows() {
        show()
        rule.onNodeWithContentDescription("Add attachment").performClick()
        rule.waitForIdle()
        for (row in listOf(ATTACH_ROW_IMAGES, ATTACH_ROW_CAMERA, ATTACH_ROW_PASTE, ATTACH_ROW_FILES)) {
            rule.onNodeWithContentDescription(row).assertExists()
        }
    }

    @Test
    fun aTakenPhotoIsStagedThroughTheIntakeNeverSentAndItsFileDeleted() {
        val bytes = pngBytes()
        camera = { uri ->
            // The camera app writes through the granted URI of the app's own capture provider.
            val out = rule.activity.contentResolver.openOutputStream(uri)
            assertNotNull("the capture URI is writable", out)
            out!!.use { it.write(bytes) }
        }
        show()
        tapTakePhoto()
        rule.waitUntil(20_000) { store.items(ORIGIN, ComposerFixtures.SESSION_ID).isNotEmpty() }

        val (contract, input) = launched.single()
        assertTrue("the system camera contract", contract is ActivityResultContracts.TakePicture)
        val uri = input as Uri
        assertEquals("content", uri.scheme)
        assertEquals(CameraCaptures.authority(rule.activity), uri.authority)

        val item = store.items(ORIGIN, ComposerFixtures.SESSION_ID).single()
        assertEquals(CameraCaptures.PHOTO_NAME, item.attachment.name)
        assertEquals("typed from its bytes", "image/png", item.attachment.mediaType)
        assertTrue("staged only: nothing sent", attachmentSends.isEmpty() && textSends.isEmpty())
        rule.waitUntil(5_000) { captureFiles().isEmpty() }
        assertTrue("the capture file is deleted once read", captureFiles().isEmpty())
    }

    @Test
    fun aCancelledCaptureStagesNothingAndLeavesNoFile() {
        camera = null
        show()
        tapTakePhoto()
        assertEquals(1, launched.size)
        assertTrue(store.items(ORIGIN, ComposerFixtures.SESSION_ID).isEmpty())
        assertTrue(captureFiles().isEmpty())
        assertTrue(attachmentSends.isEmpty() && textSends.isEmpty())
    }

    @Test
    fun aCaptureTheCameraLeftEmptyStagesNothing() {
        camera = { _ -> }
        show()
        tapTakePhoto()
        assertTrue(store.items(ORIGIN, ComposerFixtures.SESSION_ID).isEmpty())
        assertTrue(captureFiles().isEmpty())
    }

    @Test
    fun aTakenPictureOverTheWebsPerFileCapIsRefusedWithTheWebsWords() {
        camera = { uri -> rule.activity.contentResolver.openOutputStream(uri)!!.use { it.write(ByteArray(9 * 1024 * 1024 + 1)) } }
        show()
        tapTakePhoto()
        rule.waitUntil(20_000) { rule.onAllNodesWithText(AttachmentCopy.tooLarge(CameraCaptures.PHOTO_NAME)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(store.items(ORIGIN, ComposerFixtures.SESSION_ID).isEmpty())
        // ta-d8oy F2 (was VerifyCameraScratchTest.overCapCaptureFileIsLeftBehind): the rejected capture's file is deleted at once.
        assertTrue("the over-cap capture file must be deleted, left: ${captureFiles()}", captureFiles().isEmpty())
    }

    @Test
    fun oldLeftoversAreSweptByTheNextCaptureButAFreshOneIsKept() {
        val dir = CameraCaptures.dir(rule.activity).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val old = File(dir, "old.jpg").apply { writeText("x"); setLastModified(now - CameraCaptures.STALE_MS - 1000) }
        val fresh = File(dir, "fresh.jpg").apply { writeText("x"); setLastModified(now) }
        val target = CameraCaptures.newTarget(rule.activity, now)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertTrue(target.exists() && target.length() == 0L && target.parentFile == dir)
    }

    private companion object {
        const val ORIGIN = "https://tether.test:443"
    }
}
