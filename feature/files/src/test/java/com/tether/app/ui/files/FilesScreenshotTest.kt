package com.tether.app.ui.files

import com.tether.app.ui.video.LocalVideoSurfaceEnabled
import com.tether.app.ui.video.VideoPhase
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
    VideoLoading("video-loading"),
    VideoReady("video-ready"),
    VideoError("video-error"),
    SvgWide("svg"),
    SvgTall("svg-tall"),
    SvgSmall("svg-small"),
    SvgError("svg-error"),
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

    /** ta-9jnm: a path the agent named that is no longer there (its own dir, `file-open-missing`; not in the phone matrix). */
    OpenMissing("open-missing"),
}

private const val ShotTag = "files-shot"

/** What the server says about a path that is not there: shown to the reader as it came. */
private const val MISSING_WORDS = "ENOENT: no such file or directory, stat 'release-notes.md'"

/** The L1 ruling's three SVG cases: a viewBox-only wide one (4:1), a viewBox-only tall one (1:2), a small width/height one. */
private const val SVG_WIDE = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 100"><rect width="400" height="100" fill="#5c6ee6"/><circle cx="50" cy="50" r="30" fill="#ecedf4"/><rect x="110" y="35" width="250" height="30" fill="#ecedf4"/></svg>"""
private const val SVG_TALL = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 200"><rect width="100" height="200" fill="#ecedf4"/><rect x="25" y="20" width="50" height="160" fill="#5c6ee6"/><circle cx="50" cy="40" r="14" fill="#ecedf4"/></svg>"""
private const val SVG_SMALL = """<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20"><rect width="40" height="20" fill="#5c6ee6"/><rect x="4" y="4" width="32" height="12" fill="#ecedf4"/></svg>"""

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
            FilesShot.OpenMissing -> listings["$ROOT/release-notes.md"] = FilesResult.Failed(MISSING_WORDS, 404)
            FilesShot.UploadError -> failures["upload:photo.png"] = FilesResult.Failed("An item with that name already exists here.", 409)
            FilesShot.NamePrompt -> failures["mkdir"] = FilesResult.Failed("An item with that name already exists here.", 409)
            else -> Unit
        }
    }
    val platform = FakePlatform().apply {
        image = ImageLoad.Ok(checkerImage())
        when (shot) {
            FilesShot.SvgWide -> svg = SvgLoad.Ok(checkNotNull(SvgImages.parse(SVG_WIDE)))
            FilesShot.SvgTall -> svg = SvgLoad.Ok(checkNotNull(SvgImages.parse(SVG_TALL)))
            FilesShot.SvgSmall -> svg = SvgLoad.Ok(checkNotNull(SvgImages.parse(SVG_SMALL)))
            else -> Unit
        }
    }
    return FileBrowserState(files, platform, CoroutineScope(Dispatchers.Unconfined)).apply {
        cwd = ROOT
        sessionName = FilesFixtures.SESSION
        if (shot == FilesShot.OpenMissing) open("$ROOT/release-notes.md") else open()
        when (shot) {
            FilesShot.Text -> selectFile(FilesFixtures.readme)
            FilesShot.Image -> selectFile(FilesFixtures.file("screenshot.png", 18_432))
            FilesShot.VideoLoading -> selectFile(FilesFixtures.file("demo.mp4", 48_000_000))
            FilesShot.VideoReady -> {
                selectFile(FilesFixtures.file("demo.mp4", 48_000_000))
                platform.players.single().phase = VideoPhase.Ready(1920, 1080)
            }
            FilesShot.VideoError -> {
                selectFile(FilesFixtures.file("demo.mp4", 48_000_000))
                platform.players.single().fail()
            }
            FilesShot.SvgWide -> selectFile(FilesFixtures.file("banner.svg", 412))
            FilesShot.SvgTall -> selectFile(FilesFixtures.file("tower.svg", 388))
            FilesShot.SvgSmall -> selectFile(FilesFixtures.file("badge.svg", 301))
            FilesShot.SvgError -> selectFile(FilesFixtures.file("broken.svg", 12))
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
            CompositionLocalProvider(LocalReducedMotion provides true, LocalVideoSurfaceEnabled provides false, LocalSvgDispatcher provides Dispatchers.Unconfined) {
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

/** Every state x both Studio skins at the web's phone viewport (412x915 @2.625). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class FilesPhoneScreenshotTest(private val shot: FilesShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun files() = rule.snapFiles(shot, skin, "files-${shot.id}", "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = FilesShot.entries.filter { it != FilesShot.OpenMissing }.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
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
        fun params(): List<Array<Any>> = listOf(
            FilesShot.List, FilesShot.Text, FilesShot.Image, FilesShot.Destination,
            FilesShot.VideoLoading, FilesShot.VideoReady, FilesShot.VideoError,
            FilesShot.SvgWide, FilesShot.SvgTall, FilesShot.SvgSmall, FilesShot.SvgError,
        ).flatMap { s ->
            TetherSkin.entries.map { arrayOf<Any>(s, it) }
        }
    }
}

/** PLAN §4: 1.3x font scale does not break the browser (Studio light + dark). */
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

/** ta-sk1o: the side-by-side list pane at its 20rem floor (800dp tablet): Name keeps room, Modified is dropped. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1280dp-mdpi")
class FilesMidWidthScreenshotTest(private val shot: FilesShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun files() = rule.snapFiles(shot, skin, "files-${shot.id}", "w800")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(FilesShot.List, FilesShot.Text).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** ta-sk1o: a phone in landscape (914x412dp) is side by side too, with the same squeezed list pane. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w914dp-h412dp-mdpi")
class FilesLandscapeScreenshotTest(private val shot: FilesShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun files() = rule.snapFiles(shot, skin, "files-${shot.id}", "w914-landscape")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(FilesShot.List, FilesShot.Text).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** ta-9jnm: the browser opened on a mentioned file the server cannot find: the parent lists, the server's words in a banner. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class FileOpenMissingScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun missing() = rule.snapFiles(FilesShot.OpenMissing, skin, "file-open-missing", "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}
