package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ActivityScenario
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.video.LocalVideoSurfaceEnabled
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ta-coik.68: a rotation keeps the clip. The registry lives in a ViewModel, so a recreated activity
 * finds the inline clip's player, the lightbox's player and the one download where they were: no
 * second GET, no release. Closing the viewer releases its player; the activity going away for good
 * releases the rest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ToolClipsRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val item = ToolMediaItem("video", "video/mp4", ClipFixtures.src)
    private val server = ClipServer(park = true)
    private val players = mutableListOf<StubVideoPlayer>()
    private val identity = MutableStateFlow<String?>("${ClipFixtures.ORIGIN}#1")
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private var registry: ToolClipRegistry? = null

    @Before fun setUp() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        host()
    }

    @After fun tearDown() {
        scenario.close()
    }

    private var shell by androidx.compose.runtime.mutableStateOf(0)

    private fun host() {
        scenario.onActivity { activity ->
            activity.setContent {
                ChatHost(TetherSkin.StudioDark) {
                  // Each shell's chat screen is a composition of its own: the key stands for PhoneShell / ExpandedShell.
                  androidx.compose.runtime.key(shell) {
                    val vm = viewModel<ToolClipsViewModel>(key = "tool-clips") {
                        ToolClipsViewModel(
                            create = { scope ->
                                ToolClipRegistry(
                                    server, RuntimeEnvironment.getApplication().cacheDir, { ClipFixtures.ORIGIN }, scope,
                                    makePlayer = { reader, failed -> StubVideoPlayer(reader, failed).also { players += it } },
                                )
                            },
                            identity = identity,
                        )
                    }
                    registry = vm.registry
                    CompositionLocalProvider(
                        LocalToolMediaLoader provides ToolFixtures.FakeLoader(),
                        LocalToolClips provides vm.registry,
                        LocalVideoSurfaceEnabled provides false,
                    ) {
                        if (shell % 2 == 0) ToolMediaRow(listOf(item))
                        ToolViewerHost()
                    }
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

    @Test fun aRotationKeepsTheRowsPlayerTheViewerAndTheOneDownload() {
        compose.onNodeWithContentDescription("Play video").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("View video full size").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Video viewer").assertIsDisplayed()
        assertEquals(2, players.size)
        compose.waitUntil(20_000) { server.calls.get() == 1 }
        val before = registry
        rotate()
        assertSame("the same registry", before, registry)
        assertEquals("no player was made, none released", 2, players.size)
        assertTrue("nothing released by the rotation: ${players.map { it.released }}", players.none { it.released })
        // The viewer reopened (openIndex is saved) over the SAME viewer player; the row is not idle.
        compose.onNodeWithContentDescription("Video viewer").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithContentDescription("Play video").fetchSemanticsNodes().size)
        assertEquals("still one GET: the download continued", 1, server.calls.get())
        // Closing the viewer releases the viewer's player only.
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertTrue(players[1].released)
        assertFalse(players[0].released)
        // The activity going away for good releases the rest and stops the download.
        scenario.close()
        assertTrue("released with the activity", players[0].released)
    }

    @Test fun theOwnerLookupIsTheActivitysSoTheOtherShellsChatScreenFindsTheSameRegistryAndViewer() {
        compose.onNodeWithContentDescription("Play video").performClick()
        compose.onNodeWithContentDescription("View video full size").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Video viewer").assertIsDisplayed()
        val before = registry
        compose.runOnIdle { shell = 1 }
        compose.waitForIdle()
        assertSame("the same ViewModel in the other shell's chat screen", before, registry)
        compose.onNodeWithContentDescription("Video viewer").assertIsDisplayed()
        assertEquals("no player was made or released", 2, players.size)
        assertTrue(players.none { it.released })
        compose.runOnIdle { shell = 0 }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Video viewer").assertIsDisplayed()
        assertTrue(players.none { it.released })
    }
}
