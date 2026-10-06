package com.tether.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import com.tether.app.client.ConnectionState
import com.tether.app.nav.NavTestClient
import com.tether.app.ui.shell.ShellTags
import com.tether.app.ui.shell.TopBarDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ta-coik.36: no sign-in screen before the stored settings are known. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-240dpi")
class RootSurfaceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val connecting = ConnectionState.Connecting

    @Test fun nothingBeforeTheSettingsLoadIsTheSignInScreenWhateverTheClientSays() {
        // Before the first read the client says "signed out" (configured = false), the truth or not.
        for (configured in listOf(false, true)) {
            for (connection in listOf(ConnectionState.Disconnected, connecting, ConnectionState.Connected, ConnectionState.AuthRequired)) {
                assertEquals(RootSurface.Loading, rootSurfaceFor(settingsLoaded = false, configured = configured, connection = connection))
            }
        }
    }

    @Test fun onceLoadedTheSurfaceFollowsTheSignIn() {
        assertEquals(RootSurface.Setup, rootSurfaceFor(true, configured = false, connection = ConnectionState.Disconnected))
        assertEquals(RootSurface.Setup, rootSurfaceFor(true, configured = true, connection = ConnectionState.AuthRequired))
        assertEquals(RootSurface.Shell, rootSurfaceFor(true, configured = true, connection = connecting))
        assertEquals(RootSurface.Shell, rootSurfaceFor(true, configured = true, connection = ConnectionState.Connected))
    }

    private fun loginShown() =
        rule.onAllNodesWithText("Tether.", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("Tether console", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun loadingShown() = rule.onAllNodesWithTagCount(ROOT_LOADING_TAG) > 0

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun shellShown() =
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag(ShellTags.nav(TopBarDestination.Sessions)), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    @Test fun aSignedInColdStartShowsLoadingThenTheShellAndNeverTheSignInScreen() {
        // The real client's order: signed out and unloaded, then configured, then loaded.
        val client = NavTestClient(configured = false, connection = ConnectionState.Disconnected, loaded = false)
        rule.setContent { UiRoot(client = client) }
        rule.waitForIdle()
        assertTrue("a neutral loading state", loadingShown())
        assertFalse("no sign-in screen", loginShown())
        assertFalse("no shell", shellShown())

        rule.runOnIdle { client.configuredFlow.value = true }
        rule.waitForIdle()
        assertTrue(loadingShown())
        assertFalse("configured but not loaded: still no sign-in screen", loginShown())
        assertFalse(shellShown())

        rule.runOnIdle {
            client.connectionFlow.value = ConnectionState.Connecting
            client.loadedFlow.value = true
        }
        rule.waitUntil(5_000) { shellShown() }
        assertFalse("the shell replaced the loading state", loadingShown())
        assertFalse("never the sign-in screen", loginShown())
    }

    @Test fun aSignedOutColdStartShowsLoadingThenTheSignInScreen() {
        val client = NavTestClient(configured = false, connection = ConnectionState.Disconnected, loaded = false)
        rule.setContent { UiRoot(client = client) }
        rule.waitForIdle()
        assertTrue(loadingShown())
        assertFalse(loginShown())
        rule.runOnIdle { client.loadedFlow.value = true }
        rule.waitUntil(5_000) { loginShown() }
        assertFalse(loadingShown())
        assertFalse(shellShown())
    }

    /** T14.2: the web's `<section aria-busy="true" aria-label="Loading Tether">`. */
    @Test fun theColdStartSurfaceIsNamedLoadingTetherAndBusy() {
        val client = NavTestClient(configured = false, connection = ConnectionState.Disconnected, loaded = false)
        rule.setContent { UiRoot(client = client) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Loading Tether", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(ROOT_LOADING_TAG, useUnmergedTree = true).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
        )
        // Once the shell replaces it, the busy region is gone (nothing says "loading" over a loaded console).
        rule.runOnIdle { client.configuredFlow.value = true; client.connectionFlow.value = ConnectionState.Connecting; client.loadedFlow.value = true }
        rule.waitUntil(5_000) { shellShown() }
        rule.onNodeWithContentDescription("Loading Tether", useUnmergedTree = true).assertDoesNotExist()
    }
}
