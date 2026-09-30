package com.tether.app.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.ConnectionState
import com.tether.app.nav.NavTestClient.Companion.LISTED
import com.tether.app.ui.NAV_INPUT_GUARD_MS
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.UiRoot
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.shell.ShellTags
import com.tether.app.ui.shell.TopBarDestination
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T15.4 r2 through UiRoot, in the real order of a cold start from a session link: the app is
 * launched with the link while the stored settings are still loading and before any session list;
 * then the settings load; then the sessions arrive and the link opens its session. Like the web's
 * `?session=` on a cold load, the link wins the boot: the console shows Sessions throughout, never
 * the remembered Overview, and nothing is behind it, so Back leaves the app (T4.4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-240dpi")
class ColdStartLinkViewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val client = NavTestClient(loaded = false, connection = ConnectionState.Connecting)

    private val vm: TetherViewModel get() = ViewModelProvider(rule.activity)[TetherViewModel::class.java]

    private fun selected(tag: String) =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    private fun onSessionsOnly() {
        rule.waitForIdle()
        assertTrue("Sessions is the view", selected(ShellTags.nav(TopBarDestination.Sessions)))
        assertFalse("the remembered Overview never shows", selected(ShellTags.nav(TopBarDestination.Overview)))
    }

    @Test fun aColdStartLinkWinsTheBootAndBackLeavesTheApp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        // The worst case: this install remembers the Overview, and has kept preferences.
        runBlocking {
            UiPrefs(context).updatePreferences { it.copy(showThinking = true) }
            UiPrefs(context).setLastView("overview")
        }
        val sessions = client.sessionsFlow.value
        client.sessionsFlow.value = emptyList()
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("tether://session/$LISTED"))
        rule.setContent { UiRoot(client = client, launchIntent = link) }
        onSessionsOnly()

        // The stored settings load: the link is offered, and waits for its session.
        rule.runOnIdle { client.loadedFlow.value = true }
        onSessionsOnly()
        assertEquals(null, vm.selectedSessionId.value)

        // The session list arrives: the link opens its session, still in Sessions.
        rule.runOnIdle {
            client.sessionsFlow.value = sessions
            client.connectionFlow.value = ConnectionState.Connected
        }
        rule.mainClock.advanceTimeBy(NAV_INPUT_GUARD_MS + 100)
        onSessionsOnly()
        assertEquals(LISTED, vm.selectedSessionId.value)
        assertFalse("nothing is behind the linked session: Back leaves the app", rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test fun withoutALinkTheRememberedOverviewIsTheBoot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking { UiPrefs(context).setLastView("overview") }
        rule.setContent { UiRoot(client = client, launchIntent = null) }
        rule.runOnIdle { client.loadedFlow.value = true }
        rule.waitUntil(5_000) {
            rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Overview)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true
        }
    }
}
