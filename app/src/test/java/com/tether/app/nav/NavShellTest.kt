package com.tether.app.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.tether.app.nav.NavTestClient.Companion.LISTED
import com.tether.app.ui.NAV_INPUT_GUARD_MS
import com.tether.app.ui.NAV_INPUT_GUARD_TAG
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.UiRoot
import com.tether.app.ui.shell.ShellTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T4.4 in the shell: a link opens the session like a sidebar pick (the phone drawer closes, the
 * expanded layout shows it), Back then leaves the app (no in-app back stack, as the web's
 * replaceState), and a link-driven switch briefly swallows touches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-420dpi")
class NavShellTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val client = NavTestClient()
    private var launchIntent by mutableStateOf<Intent?>(null)

    @Before
    fun setUp() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        rule.setContent { UiRoot(client = client, launchIntent = launchIntent) }
        rule.waitForIdle()
    }

    @After
    fun noStateChanges() {
        assertEquals(emptyList<String>(), client.stateChanges.toList())
    }

    private val vm: TetherViewModel get() = ViewModelProvider(rule.activity)[TetherViewModel::class.java]

    private fun backIsConsumed(): Boolean {
        rule.waitForIdle()
        return rule.activity.onBackPressedDispatcher.hasEnabledCallbacks()
    }

    private fun link(uri: String) {
        rule.runOnUiThread { launchIntent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)) }
    }

    @Test
    fun onThePhoneALinkClosesTheDrawerAndBackLeavesTheApp() {
        rule.onNodeWithTag(ShellTags.MenuKey).performClick()
        assertTrue("drawer open", backIsConsumed())
        link("tether://session/$LISTED")
        rule.mainClock.advanceTimeBy(NAV_INPUT_GUARD_MS + 100)
        rule.waitForIdle()
        assertEquals(LISTED, vm.selectedSessionId.value)
        rule.onNodeWithTag(ShellTags.DrawerBackdrop).assertDoesNotExist()
        assertFalse("Back must fall through to the system", backIsConsumed())
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-240dpi")
    fun onATabletALinkOpensTheSessionInTheExpandedLayout() {
        rule.onNodeWithTag(ShellTags.Sidebar).assertExists()
        link("tether://session/$LISTED")
        rule.mainClock.advanceTimeBy(NAV_INPUT_GUARD_MS + 100)
        rule.waitForIdle()
        assertEquals(LISTED, vm.selectedSessionId.value)
        rule.onNodeWithTag(ShellTags.Sidebar).assertExists()
        assertFalse("Back must fall through to the system", backIsConsumed())
    }

    @Test
    fun aLinkDrivenSwitchSwallowsTouchesBriefly() {
        link("tether://session/$LISTED")
        rule.waitUntil(timeoutMillis = NAV_INPUT_GUARD_MS / 2) {
            rule.onAllNodesWithTag(NAV_INPUT_GUARD_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(LISTED, vm.selectedSessionId.value)
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag(NAV_INPUT_GUARD_TAG).assertExists()
            // A tap aimed at the shell while the guard is up does nothing.
            rule.onNodeWithTag(ShellTags.MenuKey).performClick()
            rule.mainClock.advanceTimeByFrame()
            assertFalse("the tap reached the shell", rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.mainClock.advanceTimeBy(NAV_INPUT_GUARD_MS + 100)
        rule.waitForIdle()
        rule.onNodeWithTag(NAV_INPUT_GUARD_TAG).assertDoesNotExist()
        rule.onNodeWithTag(ShellTags.MenuKey).performClick()
        assertTrue("after the guard the tap works", backIsConsumed())
    }

    @Test
    fun noGuardWithoutASwitch() {
        link("https://evil.example/?session=$LISTED")
        rule.waitForIdle()
        rule.onNodeWithTag(NAV_INPUT_GUARD_TAG).assertDoesNotExist()
    }
}
