package com.tether.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.BrowseStatus
import com.tether.app.client.WorkspaceSelectStatus
import com.tether.app.protocol.model.DirectoryEntry
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** T8.2: the folder picker's states (folder-picker-dialog.tsx 90fbb9f), one golden per state and skin. */
enum class FolderPickerShot(val id: String) {
    /** The listing, "Create a new folder" offered. */
    Listing("folder-picker"),
    /** :84-97 the new-folder form, a name typed. */
    NewFolder("folder-picker-new-folder"),
    /** :101-106 "Couldn't load this folder". */
    LoadError("folder-picker-load-error"),
    /** :128-134 "Opening…" after Use this folder. */
    Opening("folder-picker-opening"),
    /** :116-121 "Couldn't open this workspace yet". */
    Stalled("folder-picker-stalled"),
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class FolderPickerScreenshotTest(private val shot: FolderPickerShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    private val listing = DirectoryListing(
        "/home/op/work",
        parent = "/home/op",
        entries = listOf(DirectoryEntry("tether", "/home/op/work/tether"), DirectoryEntry("tether-android", "/home/op/work/tether-android")),
    )

    @Test fun picker() {
        val state = FolderPickerState(
            newFolderOpen = shot == FolderPickerShot.NewFolder,
            newFolderName = if (shot == FolderPickerShot.NewFolder) "reports" else "",
            awaitingCwd = if (shot == FolderPickerShot.Opening || shot == FolderPickerShot.Stalled) listing.current else null,
        )
        val select = when (shot) {
            FolderPickerShot.Opening -> WorkspaceSelectStatus(listing.current, "r", WorkspaceSelectStatus.Phase.Opening)
            FolderPickerShot.Stalled -> WorkspaceSelectStatus(listing.current, "r", WorkspaceSelectStatus.Phase.Stalled)
            else -> null
        }
        val browse = if (shot == FolderPickerShot.LoadError) BrowseStatus("r", "/home/op/work/tether", BrowseStatus.Phase.Error) else null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TetherTheme(skin.mode) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    FolderPickerFrame(listing, "/home/op", browseStatus = browse, select = select, state = state)
                }
            }
        }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(
            "src/test/screenshots/${shot.id}/${skin.id}-phone.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = FolderPickerShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
