package com.tether.app.ui.files

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.FilesResult
import com.tether.app.client.WorkspaceFiles
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.files.FilesFixtures.ROOT
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeMode
import com.tether.app.ui.theme.mode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The browser's visual states (T11.1 DoD). `list` is the S0.4 web scenario `file-browser`
 * (parity-app opened from the top bar, nothing selected); the rest are the component's other
 * states, seeded with the same project: a text preview (README.md), an image preview, loading,
 * an empty folder, a failed listing, the metadata-only and too-large panels, an upload refusal,
 * and the four sub-dialogs (actions sheet, name prompt with a refusal, delete confirm, the
 * Move to… picker).
 */
enum class FilesShot(val id: String) {
    List("list"),
    Text("text"),
    Image("image"),
    Loading("loading"),
    Empty("empty"),
    Error("error"),
    Unsupported("unsupported"),
    TooLarge("too-large"),
    UploadError("upload-error"),
    Actions("actions"),
    NamePrompt("name-prompt"),
    Delete("delete"),
    Destination("destination"),
}

private const val ShotTag = "files-shot"

private fun checkerImage() = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888).apply {
    // parity-seed.mjs fixturePng's tiles, scaled up: 80px squares of two tones.
    for (y in 0 until height) for (x in 0 until width) {
        setPixel(x, y, if ((x / 80 + y / 80) % 2 == 1) AColor.rgb(92, 110, 230) else AColor.rgb(236, 238, 244))
    }
}.asImageBitmap()

/** A browser in [shot]'s state; the fakes answer synchronously so the state is settled at once. */
private fun stateFor(shot: FilesShot): FileBrowserState {
    val files = FakeFiles().apply {
        listings[ROOT] = FilesResult.Ok(FilesFixtures.listing())
        texts[FilesFixtures.readme.path] = FilesResult.Ok(FilesFixtures.README_TEXT)
        when (shot) {
            FilesShot.Loading -> gates["list"] = CompletableDeferred()
            FilesShot.Empty -> listings[ROOT] = FilesResult.Ok(FilesFixtures.listing(entries = emptyList()))
            FilesShot.Error -> listings.remove(ROOT)
            FilesShot.UploadError -> failures["upload:photo.png"] = FilesResult.Failed("An item with that name already exists here.", 409)
            FilesShot.NamePrompt -> failures["mkdir"] = FilesResult.Failed("An item with that name already exists here.", 409)
            else -> Unit
        }
    }
    val platform = FakePlatform().apply { image = ImageLoad.Ok(checkerImage()) }
    return FileBrowserState(files, platform, CoroutineScope(Dispatchers.Unconfined)).apply {
        cwd = ROOT
        sessionName = FilesFixtures.SESSION
        open()
        when (shot) {
            FilesShot.Text -> selectFile(FilesFixtures.readme)
            FilesShot.Image -> selectFile(FilesFixtures.file("screenshot.png", 18_432))
            FilesShot.Unsupported -> selectFile(FilesFixtures.file("release.zip", 5_347_738))
            FilesShot.TooLarge -> selectFile(FilesFixtures.file("server.log", WorkspaceFiles.MAX_TEXT_PREVIEW_BYTES * 3))
            FilesShot.UploadError -> upload(listOf(PickedUpload("photo.png", bytesSource(byteArrayOf(1)))))
            FilesShot.Actions -> openItemActions(FilesFixtures.readme)
            FilesShot.NamePrompt -> {
                openNamePrompt(NamePromptMode.NewFolder)
                updateNamePrompt("src")
                submitNamePrompt()
            }
            FilesShot.Delete -> openDeleteConfirm(FilesFixtures.docs)
            FilesShot.Destination -> openDestPicker(FilesFixtures.readme, DestinationMode.Move)
            else -> Unit
        }
    }
}

/** The inline sub-dialog over the browser, with its own scrim (a nested <dialog>'s ::backdrop). */
@Composable
private fun Overlay(state: FileBrowserState) {
    val t = LocalTetherTokens.current
    state.itemActions?.let { entry ->
        Box(Modifier.fillMaxSize().background(dialogScrim(t)), contentAlignment = Alignment.Center) {
            ItemActionsContent(entry, {}, {}, {}, onSave = {}, onShare = {}, onDelete = {}, onCancel = {})
        }
    }
    state.namePrompt?.let { prompt ->
        Box(Modifier.fillMaxSize().background(dialogScrim(t)), contentAlignment = Alignment.Center) {
            NamePromptContent(prompt, state.namePromptError, submitting = false, onValueChange = {}, onSubmit = {}, onCancel = {})
        }
    }
    state.deleteTarget?.let { target ->
        Box(Modifier.fillMaxSize().background(dialogScrim(t)), contentAlignment = Alignment.Center) {
            DeleteConfirmContent(target, state.deleteError, submitting = false, onConfirm = {}, onCancel = {})
        }
    }
    state.destPicker?.let { picker -> DestinationPickerFrame(picker, submitting = false, onBrowse = {}, onConfirm = {}, onClose = {}) }
}

fun ComposeContentTestRule.snapFiles(shot: FilesShot, skin: TetherSkin, name: String, size: String) {
    val state = stateFor(shot)
    setContent {
        TetherTheme(skin.mode) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                // The console floor behind the scrim (the shell is there in the app, as on the web).
                Box(Modifier.fillMaxSize().background(LocalTetherTokens.current.mineral).testTag(ShotTag)) {
                    FileBrowserFrame(state, onClose = {}, onUpload = {}, env = FilesFixtures.env)
                    Overlay(state)
                }
            }
        }
    }
    waitForIdle()
    onNodeWithTag(ShotTag).captureRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

/** Every state x all 6 skins at the web's phone viewport (412x915 @2.625). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class FilesPhoneScreenshotTest(private val shot: FilesShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun files() = rule.snapFiles(shot, skin, "files-${shot.id}", "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = FilesShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** The web's desktop layout (1280x800): list and preview side by side, the Modified column. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class FilesTabletScreenshotTest(private val shot: FilesShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun files() = rule.snapFiles(shot, skin, "files-${shot.id}", "tablet")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(FilesShot.List, FilesShot.Text, FilesShot.Image, FilesShot.Destination).flatMap { s ->
            TetherSkin.entries.map { arrayOf<Any>(s, it) }
        }
    }
}

/** PLAN §4: 1.3x font scale does not break the browser (Machine + Studio). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class FilesFontScaleScreenshotTest(private val shot: FilesShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun files() = rule.snapFiles(shot, skin, "files-${shot.id}-font-1.3x", "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(FilesShot.List, FilesShot.Text, FilesShot.Actions, FilesShot.NamePrompt).flatMap { s ->
            listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(s, it) }
        }
    }
}
