package com.tether.app.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.ConnectionState
import com.tether.app.client.serverOrigin
import com.tether.app.nav.NavTestClient
import com.tether.app.ui.prefs.LoginVariant
import com.tether.app.ui.prefs.UiPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.52: the sign-in screen is the configured server's own choice (the web's sign-in page reads
 * its origin's `tether.preferences.v1`): Retro on the server that picked it, Studio's on another.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-420dpi")
class LoginVariantPerServerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val a = "https://a.example"
    private val b = "https://b.example"
    private val client = NavTestClient(configured = false, connection = ConnectionState.Disconnected, serverUrl = a)

    private fun retroShown() = rule.onAllNodesWithText("Tether console", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Studio's sign-in: its wordmark (studio-login.tsx). */
    private fun studioShown() = rule.onAllNodesWithText("Tether.", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun theSignInScreenIsTheServersOwnChoice() {
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext<Context>())
        runBlocking { prefs.setLoginVariant(serverOrigin(a), LoginVariant.Retro) }
        rule.setContent { UiRoot(client = client) }
        rule.waitUntil(5_000) { retroShown() }
        rule.runOnIdle { client.serverUrlFlow.value = b }
        rule.waitUntil(5_000) { studioShown() && !retroShown() }
        rule.runOnIdle { client.serverUrlFlow.value = a }
        rule.waitUntil(5_000) { retroShown() }
    }
}

/** ta-coik.52: the theme is the configured server's own (Dark on A; B keeps the web default, the system's light here). */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-420dpi")
class ThemePerServerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val client = NavTestClient(configured = false, connection = ConnectionState.Disconnected, serverUrl = "https://a.example")

    /** The background's luminance at the screen's bottom-left corner. */
    private fun luminance(): Float {
        val px = rule.onRoot().captureToImage().toPixelMap()
        return px[2, px.height - 3].luminance()
    }

    @Test fun theThemeIsTheServersOwn() {
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext<Context>())
        runBlocking { prefs.setThemeMode(serverOrigin("https://a.example"), com.tether.app.ui.theme.ThemeMode.Dark) }
        rule.setContent { UiRoot(client = client) }
        rule.waitUntil(5_000) { luminance() < 0.3f }
        rule.runOnIdle { client.serverUrlFlow.value = "https://b.example" }
        rule.waitUntil(5_000) { luminance() > 0.6f }
    }
}
