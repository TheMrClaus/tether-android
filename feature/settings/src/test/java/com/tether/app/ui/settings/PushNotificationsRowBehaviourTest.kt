package com.tether.app.ui.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tether.app.push.PushRegistrationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T12.2: the Devices tab's push row does what the web's Web Push row does (tether 90fbb9f
 * settings-dialog.tsx:2073-2096): it refreshes on opening, Re-enable re-registers a stale
 * registration, Disable turns push off (the pipeline then DELETEs the row), Enable turns it on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PushNotificationsRowBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState()

    private fun show(push: FakePushRegistration) {
        grantNotificationPermission()
        compose.setContent { SettingsUnderTest(store.prefs, state, push = push) }
        compose.waitUntil(5_000) { state.draft != null }
        compose.onNodeWithTag(SettingsDialogTags.tab(SettingsTab.Devices)).performClick()
        compose.waitForIdle()
    }

    private fun pushEnabled() = runBlocking(Dispatchers.IO) { store.prefs.pushEnabled.first() }

    private fun waitText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun openingRefreshesTheRegistration() {
        val push = FakePushRegistration()
        show(push)
        compose.waitUntil(5_000) { push.refreshes == 1 }
        compose.onNodeWithText("On for this device, including when Tether is closed.").performScrollTo()
    }

    @Test fun reEnableReRegistersAStaleRegistration() {
        val push = FakePushRegistration(PushRegistrationStatus.ProjectChanged)
        show(push)
        waitText("The server’s push key changed. Re-enable notifications on this device.")
        compose.onNodeWithTag(PushTags.Action).performScrollTo().assertTextEquals("Re-enable").performClick()
        compose.waitForIdle()
        assertEquals(1, push.reEnables)
        // Busy until the registration settles, as the web's "Enabling…".
        compose.onNodeWithTag(PushTags.Action).assertTextEquals("Enabling…").assertIsNotEnabled()
        push.status.value = PushRegistrationStatus.Registering
        compose.waitForIdle()
        push.status.value = PushRegistrationStatus.Registered
        waitText("On for this device, including when Tether is closed.")
        compose.onNodeWithTag(PushTags.Action).assertTextEquals("Disable")
        assertTrue(pushEnabled())
    }

    @Test fun disableTurnsPushOffAndEnableTurnsItBackOn() {
        val push = FakePushRegistration()
        show(push)
        compose.onNodeWithTag(PushTags.Action).performScrollTo().assertTextEquals("Disable").performClick()
        compose.waitUntil(5_000) { !pushEnabled() }
        // The pipeline answers a disable by DELETEing the row and going idle.
        push.status.value = PushRegistrationStatus.Idle
        waitText("Off on this device. Enable to receive approval and turn-completion alerts.")
        compose.onNodeWithTag(PushTags.Action).assertTextEquals("Enable").performClick()
        compose.waitUntil(5_000) { pushEnabled() }
        compose.onNodeWithTag(PushTags.Action).assertTextEquals("Enabling…")
        push.status.value = PushRegistrationStatus.Registered
        waitText("On for this device, including when Tether is closed.")
        assertFalse(push.reEnables > 0)
    }

    @Test fun anErrorShowsTheFailureWithDisable() {
        show(FakePushRegistration(PushRegistrationStatus.Failed("Push register returned HTTP 500.")))
        waitText("Push register returned HTTP 500.")
        compose.onNodeWithTag(PushTags.Action).performScrollTo().assertTextEquals("Disable")
    }

    @Test fun anUnpairedSignInHasNoButton() {
        show(FakePushRegistration(PushRegistrationStatus.NoDevice))
        waitText(PushCopy.NO_DEVICE)
        assertTrue(compose.onAllNodesWithText("Enable").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Disable").fetchSemanticsNodes().isEmpty())
    }
}
