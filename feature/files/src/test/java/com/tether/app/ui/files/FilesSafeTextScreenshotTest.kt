package com.tether.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.FilesResult
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.files.FilesFixtures.ROOT
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeChoice
import com.tether.app.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-28i: the browser over hostile server text. `preview`: a Trojan Source file (a bidi override
 * and isolates that make a check read as a comment, a zero-width space, CRLF line ends, a lone CR,
 * real Hebrew and Arabic) next to a list holding a file named with an RLO; every control shows as a
 * `--warning` token and every line reads LTR. `delete`: the delete confirmation names that file as
 * it is.
 */
private const val TROJAN = "if (accessLevel != \"user\u202E \u2066// Check if admin\u2069 \u2066\") {\r\n" +
    "    grant();\u200B\r\n" +
    "}\r\n" +
    "// \u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD\n" +
    "// \u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645\n" +
    "lone\rcr\n"

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class FilesSafeTextScreenshotTest(private val shot: String, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun files() {
        val source = FilesFixtures.file("access.js", TROJAN.length.toLong())
        val hostile = FilesFixtures.file("invoice\u202Efdp.exe", 1_024)
        val files = FakeFiles().apply {
            listings[ROOT] = FilesResult.Ok(FilesFixtures.listing(entries = listOf(FilesFixtures.docs, source, hostile)))
            texts[source.path] = FilesResult.Ok(TROJAN)
        }
        val state = FileBrowserState(files, FakePlatform(), CoroutineScope(Dispatchers.Unconfined)).apply {
            cwd = ROOT
            sessionName = "Fix the \u202Elanif\u202C bug"
            open()
            selectFile(source)
            if (shot == "delete") openDeleteConfirm(hostile)
        }
        rule.setContent {
            TetherTheme(ThemeChoice(skin.family, if (skin.isDark) ThemeMode.Dark else ThemeMode.Light)) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    val t = LocalTetherTokens.current
                    Box(Modifier.fillMaxSize().background(t.mineral).testTag("files-safe-shot")) {
                        FileBrowserFrame(state, onClose = {}, onUpload = {}, env = FilesFixtures.env)
                        state.deleteTarget?.let { target ->
                            Box(Modifier.fillMaxSize().background(dialogScrim(t)), contentAlignment = Alignment.Center) {
                                DeleteConfirmContent(target, "", submitting = false, onConfirm = {}, onCancel = {})
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("files-safe-shot").captureRoboImage(
            "src/test/screenshots/files-safe-text-$shot/${skin.id}-tablet.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf("preview", "delete").flatMap { s -> listOf(TetherSkin.Machine, TetherSkin.Studio).map { arrayOf<Any>(s, it) } }
    }
}
