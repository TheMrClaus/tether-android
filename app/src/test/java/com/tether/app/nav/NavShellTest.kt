package com.tether.app.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tether.app.nav.NavTestClient.Companion.OTHER_LISTED
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.tether.app.nav.NavTestClient.Companion.LISTED
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
 * replaceState), and (ta-coik.22) input right after a link-driven switch acts at once, as on the web:
 * there is no timed pause.
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
        // T15.4: these links are opened from Sessions (a fresh install would start on the Overview,
        // where a link pushes Sessions and Back returns there: MainShellNavigationTest).
        kotlinx.coroutines.runBlocking { com.tether.app.ui.prefs.UiPrefs(ApplicationProvider.getApplicationContext<Context>()).setLastView("sessions") }
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
        rule.waitForIdle()
        assertEquals(LISTED, vm.selectedSessionId.value)
        rule.onNodeWithTag(ShellTags.Sidebar).assertExists()
        assertFalse("Back must fall through to the system", backIsConsumed())
    }

    /**
     * ta-coik.22: drives the clock by hand from the moment [uri] is offered until its session is
     * selected (a few frames, well inside the 500 ms the retired guard held), so what follows happens
     * "right after" the switch. Leaves the clock on manual; [settleBriefly] lets a tap land.
     */
    private fun switchByHand(uri: String, id: String) {
        rule.mainClock.autoAdvance = false
        link(uri)
        var frames = 0
        while (vm.selectedSessionId.value != id && frames++ < 12) rule.mainClock.advanceTimeByFrame()
        assertEquals(id, vm.selectedSessionId.value)
        // The new session is drawn (a couple of frames), still well inside the old guard's window.
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
    }

    /** 100 ms of frames: enough for a tap to land, far short of the retired 500 ms pause. */
    private fun settleBriefly() = rule.mainClock.advanceTimeBy(100)

    /**
     * ta-coik.22: the web has no input pause after a switch (dashboard.tsx 90fbb9f: a `?session=`
     * link or a notification just selects the session). A tap made the moment a link switched the
     * session acts at once.
     */
    @Test
    fun aTapRightAfterALinkDrivenSwitchActsAtOnce() {
        try {
            switchByHand("tether://session/$LISTED", LISTED)
            rule.onNodeWithTag(ShellTags.MenuKey).performClick()
            settleBriefly()
            assertTrue("the tap right after the switch was swallowed", rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun aLinkMakesTheSessionsWorkspaceCurrent() {
        // dashboard.tsx focusWorkspaceFor(pendingSession.cwd): the linked session lives outside the
        // current workspace, so its folder becomes current and its block is listed.
        val cwd = client.sessions.value.single { it.id == LISTED }.cwd
        assertFalse(cwd == vm.currentWorkspace.value)
        link("tether://session/$LISTED")
        rule.waitForIdle()
        assertEquals(cwd, vm.currentWorkspace.value)
    }

    private fun openAndSettle(id: String) {
        link("tether://session/$id")
        rule.waitForIdle()
        assertEquals(id, vm.selectedSessionId.value)
    }

    private fun composer() = rule.onAllNodes(hasSetTextAction()).onFirst()

    private fun composerText(): String =
        composer().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun aLinkTakesFocusAndTheKeyboardAwayFromThePreviousComposer() {
        openAndSettle(OTHER_LISTED)
        composer().requestFocus()
        composer().assertIsFocused()
        link("tether://session/$LISTED")
        rule.waitForIdle()
        assertEquals(LISTED, vm.selectedSessionId.value)
        // No text field holds focus, so no keyboard input session carries over (in keyboard mode
        // Compose may park focus on a plain button, which takes no text).
        rule.onAllNodes(isFocused() and hasSetTextAction()).assertCountEquals(0)
    }

    /** ta-coik.22: a hardware key typed the moment a link switched the session reaches the composer. */
    @Test
    fun aHardwareKeyRightAfterALinkDrivenSwitchTypes() {
        openAndSettle(OTHER_LISTED)
        try {
            switchByHand("tether://session/$LISTED", LISTED)
            composer().requestFocus()
            settleBriefly()
            val before = composerText()
            composer().performKeyInput { pressKey(Key.A) }
            settleBriefly()
            val after = composerText()
            assertEquals("a key right after the switch was swallowed: $after", before.length + 1, after.length)
            assertEquals(1, after.count { it == 'a' } - before.count { it == 'a' })
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    /** ta-coik.22: back-to-back link switches leave nothing over the shell either. */
    @Test
    fun aTapRightAfterTwoQuickLinkSwitchesActsAtOnce() {
        try {
            switchByHand("tether://session/$OTHER_LISTED", OTHER_LISTED)
            switchByHand("tether://session/$LISTED", LISTED)
            rule.onNodeWithTag(ShellTags.MenuKey).performClick()
            settleBriefly()
            assertTrue("the tap after the second switch was swallowed", rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }
}
