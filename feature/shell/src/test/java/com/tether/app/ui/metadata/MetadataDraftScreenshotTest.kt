package com.tether.app.ui.metadata

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.PendingMetadataDraft
import com.tether.app.protocol.MetadataDraft
import com.tether.app.ui.statusline.screenshots.ScreenSize
import com.tether.app.ui.statusline.screenshots.choiceFor
import com.tether.app.ui.statusline.screenshots.goldenPath
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T8.5: the metadata draft panel (components/metadata-draft-panel.tsx 90fbb9f) over the scrim.
 * `commit` = a commit message just copied ("Copied"); `pull-request` = a PR draft's Title and Body,
 * the body's copy failed ("Failed"); `failed` = a server failure in its own words.
 */
enum class MetadataDraftShot(val id: String, val draft: PendingMetadataDraft, val copied: String? = null, val copyError: String? = null) {
    Commit(
        "metadata-draft-commit",
        PendingMetadataDraft("c1", MetadataDraft.CommitMessage("feat(inspector): add metadata draft keys\n\nThe Repository band asks the server for a\ncommit message or a pull request draft.")),
        copied = "c1::text",
    ),
    PullRequest(
        "metadata-draft-pull-request",
        PendingMetadataDraft(
            "p1",
            MetadataDraft.PullRequest(
                "Add metadata draft keys to the inspector",
                "## Summary\n- Draft commit message\n- Draft pull request\n\n## Testing\n- unit and Compose tests",
            ),
        ),
        copyError = "p1::body",
    ),
    Failed("metadata-draft-failed", PendingMetadataDraft("e1", MetadataDraft.Failure("Metadata generation is disabled on this server."))),
}

private fun ComposeContentTestRule.snapMetadataDraft(shot: MetadataDraftShot, skin: TetherSkin, size: ScreenSize) {
    setContent {
        TetherTheme(choiceFor(skin)) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                MetadataDraftPanelFrame(shot.draft, shot.copied, shot.copyError)
            }
        }
    }
    waitForIdle()
    onRoot().captureRoboImage(
        goldenPath(shot.id, skin, size),
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

private fun metadataDraftParams(): List<Array<Any>> = MetadataDraftShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class MetadataDraftPhoneScreenshotTest(private val shot: MetadataDraftShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun panel() = rule.snapMetadataDraft(shot, skin, ScreenSize.Phone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = metadataDraftParams()
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class MetadataDraftTabletScreenshotTest(private val shot: MetadataDraftShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun panel() = rule.snapMetadataDraft(shot, skin, ScreenSize.Tablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = metadataDraftParams()
    }
}
