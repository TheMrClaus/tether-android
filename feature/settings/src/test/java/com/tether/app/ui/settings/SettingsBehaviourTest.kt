package com.tether.app.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import com.tether.app.ui.prefs.PreferenceKeys
import com.tether.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T10.1 (components/settings-dialog.tsx): the tabs, General's Save draft (Save writes it, Cancel
 * and Close drop it), Appearance applied on click (T15.5's mode setting and the sign-in screen),
 * the restart banner, and the panels the later slices fill in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SettingsBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private var closes = 0
    private val state = SettingsDialogState()

    private fun show(restartRequired: Boolean = false, current: String = CURRENT) {
        compose.setContent {
            SettingsUnderTest(store.prefs, state, restartRequired = restartRequired, currentWorkspace = current, onClose = { closes++ })
        }
        // The draft is seeded from the store's first read.
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitForIdle()
    }

    private fun tab(tab: SettingsTab) = compose.onNodeWithTag(SettingsDialogTags.tab(tab))
    private fun toggle(toggle: GeneralToggle) = compose.onNodeWithTag(SettingsPanelTags.toggle(toggle))

    private fun waitClosed(count: Int = 1) = compose.waitUntil(5_000) { closes == count }

    /**
     * r3: flip a General switch by its own click action (not a tap at the row's centre, whose
     * neighbour is the tip's 48dp touch area), then wait until the draft holds the flip.
     */
    private fun flip(t: GeneralToggle) {
        val before = state.draft?.isOn(t)
        toggle(t).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { state.draft?.isOn(t) == (before == false) }
    }

    /** r3: wait until the store holds every one of [expected], rather than reading it once right after the close. */
    private fun waitStored(vararg expected: Pair<String, Any?>) {
        compose.waitUntil(5_000) { store.stored().let { raw -> expected.all { (k, v) -> raw[k] == v } } }
        val raw = store.stored()
        for ((k, v) in expected) assertEquals(k, v, raw[k])
    }

    @Test fun theSevenTabsShowGeneralFirstAndSwitchPanels() {
        show()
        SettingsTab.entries.forEach { t ->
            tab(t).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        }
        tab(SettingsTab.General).assertIsSelected()
        compose.onNodeWithTag(SettingsDialogTags.panel(SettingsTab.General)).assertExists()
        compose.onNodeWithText("Startup").assertExists()

        tab(SettingsTab.Appearance).performClick()
        compose.waitForIdle()
        tab(SettingsTab.Appearance).assertIsSelected()
        tab(SettingsTab.General).assertIsNotSelected()
        compose.onNodeWithTag(SettingsDialogTags.panel(SettingsTab.General)).assertDoesNotExist()
        compose.onNodeWithText("Sign-in screen").assertExists()

        tab(SettingsTab.Devices).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sessions opened on this phone").assertExists()
        compose.onNodeWithText("Paired devices").assertExists()
        compose.onNodeWithText("Sign-in security").assertExists()
    }

    /**
     * Engines, Metadata, Advanced and Nodes say what is coming, they are never empty. Engines (ta-9q2)
     * holds Claude accounts between three slots: the engines, Custom providers and Host config.
     */
    @Test fun theLaterPanelsSayWhatIsComing() {
        show()
        // ta-t7l: Metadata and Advanced are drawn now (here, before any server reply, they wait for it).
        mapOf(SettingsTab.Nodes to 1, SettingsTab.Engines to 3, SettingsTab.Metadata to 0, SettingsTab.Advanced to 0).forEach { (t, slots) ->
            tab(t).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(SettingsDialogTags.panel(t)).assertExists()
            assertEquals("$t", slots, compose.onAllNodesWithTag(SettingsTags.ComingSoon).fetchSemanticsNodes().size)
        }
    }

    @Test fun aToggleEditsTheDraftAndOnlySaveWritesIt() {
        show()
        toggle(GeneralToggle.ShowEndedSessions).assertIsOn()
        flip(GeneralToggle.ShowEndedSessions)
        flip(GeneralToggle.ConfirmBeforeEnd)
        flip(GeneralToggle.ShowThinking)
        compose.waitForIdle()
        toggle(GeneralToggle.ShowEndedSessions).assertIsOff()
        toggle(GeneralToggle.ConfirmBeforeEnd).assertIsOff()
        toggle(GeneralToggle.ShowThinking).assertIsOn()
        // Nothing is stored before Save.
        assertEquals(emptyMap<String, Any>(), store.stored())

        compose.onNodeWithTag(SettingsDialogTags.Save).performClick()
        waitClosed()
        waitStored(PreferenceKeys.SHOW_ENDED_SESSIONS to false, PreferenceKeys.CONFIRM_BEFORE_END to false, PreferenceKeys.SHOW_THINKING to true)
    }

    @Test fun cancelAndCloseDropTheDraft() {
        show()
        flip(GeneralToggle.ConfirmBeforeEnd)
        compose.onNodeWithTag(SettingsDialogTags.Cancel).performClick()
        waitClosed(1)
        compose.onNodeWithContentDescription("Close").performClick()
        waitClosed(2)
        assertEquals(emptyMap<String, Any>(), store.stored())
    }

    /** settings-dialog.tsx:2005: the caption shows the kept folder, else "Workspace root". */
    @Test fun useCurrentTakesTheCurrentWorkspaceIntoTheDraft() {
        store.seed(PreferenceKeys.DEFAULT_WORKSPACE to "")
        show()
        compose.onNodeWithText("Workspace root").assertExists()
        compose.onNodeWithTag(SettingsPanelTags.UseCurrent).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(CURRENT, substring = true).assertExists()
        compose.onNodeWithText("Workspace root").assertDoesNotExist()
        assertEquals("", store.stored()[PreferenceKeys.DEFAULT_WORKSPACE])
        compose.onNodeWithTag(SettingsDialogTags.Save).performClick()
        waitClosed()
        waitStored(PreferenceKeys.DEFAULT_WORKSPACE to CURRENT)
    }

    /** A stored folder is server text: drawn by the code-label rule, a hidden control as a token. */
    @Test fun theStoredFolderIsDrawnSafely() {
        store.seed(PreferenceKeys.DEFAULT_WORKSPACE to "/srv/re\u202Epo")
        show()
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text].map { t -> t.text } }
        assertTrue("the folder is shown: $texts", texts.any { it.startsWith("/srv/re") })
        assertTrue("the bidi override must not reach the screen raw: $texts", texts.none { '\u202E' in it })
    }

    /**
     * settings-dialog.tsx:2015-2064, 2452-2456: Appearance applies at once and Save keeps it (the
     * draft never rolls the theme back to the one showing when Settings opened).
     */
    @Test fun appearanceAppliesAtOnceAndSaveKeepsIt() {
        show()
        flip(GeneralToggle.ShowThinking)
        tab(SettingsTab.Appearance).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsPanelTags.themeMode(ThemeMode.Dark)).performClick()
        compose.waitUntil(5_000) { store.stored()[PreferenceKeys.THEME_MODE] == "dark" }
        compose.onNodeWithTag(SettingsPanelTags.themeMode(ThemeMode.Dark)).assertIsSelected()
        compose.onNodeWithTag(SettingsPanelTags.loginVariant("retro")).performClick()
        compose.waitUntil(5_000) { store.stored()[PreferenceKeys.LOGIN_VARIANT] == "retro" }
        // The General draft is still unsaved (the appearance write keeps the stored value).
        assertEquals(false, store.stored()[PreferenceKeys.SHOW_THINKING])

        compose.onNodeWithTag(SettingsDialogTags.Save).performClick()
        waitClosed()
        waitStored(PreferenceKeys.THEME_MODE to "dark", PreferenceKeys.LOGIN_VARIANT to "retro", PreferenceKeys.SHOW_THINKING to true)
    }

    /**
     * T15.5 (moved from the interim sheet's AppearanceSettingsTest): only Studio's lighting is
     * offered, a fresh install follows the system, and a 0.7.x-0.8.0 family + mode reads as its
     * mode; a pick stores the mode alone.
     */
    @Test fun appearanceOffersTheThreeModesAndMigratesARetiredFamily() {
        store.seed(PreferenceKeys.THEME_FAMILY to "tactile", PreferenceKeys.THEME_MODE to "dark", PreferenceKeys.LEGACY_THEME to "night")
        show()
        tab(SettingsTab.Appearance).performClick()
        compose.waitForIdle()
        for (mode in listOf("Light", "Dark", "Follow system")) compose.onNodeWithText(mode).assertExists()
        for (retired in listOf("Tactile Console", "Precision Machine", "THEME")) {
            assertEquals(retired, 0, compose.onAllNodesWithText(retired).fetchSemanticsNodes().size)
        }
        compose.onNodeWithTag(SettingsPanelTags.themeMode(ThemeMode.Dark)).assertIsSelected()
        compose.onNodeWithTag(SettingsPanelTags.themeMode(ThemeMode.Light)).performClick()
        compose.waitUntil(5_000) { store.stored()[PreferenceKeys.THEME_MODE] == "light" }
        val raw = store.stored()
        assertEquals(null, raw[PreferenceKeys.THEME_FAMILY])
        assertEquals(null, raw[PreferenceKeys.LEGACY_THEME])
        compose.onNodeWithTag(SettingsPanelTags.themeMode(ThemeMode.Light)).assertIsSelected()
    }

    @Test fun aFreshInstallFollowsTheSystemAndTheDefaultSignIn() {
        show()
        tab(SettingsTab.Appearance).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsPanelTags.themeMode(ThemeMode.System)).assertIsSelected()
        compose.onNodeWithTag(SettingsPanelTags.loginVariant("default")).assertIsSelected()
        compose.onNodeWithTag(SettingsPanelTags.loginVariant("retro")).assertIsNotSelected()
    }

    /** settings-dialog.tsx:1998-2002: shown only while the server says a restart is needed, on every tab. */
    @Test fun theRestartBannerFollowsRestartRequired() {
        show(restartRequired = true)
        compose.onNodeWithTag(SettingsDialogTags.RestartBanner).assertExists()
        compose.onNodeWithText(RESTART_REQUIRED).assertExists()
        tab(SettingsTab.Advanced).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsDialogTags.RestartBanner).assertExists()
    }

    @Test fun noBannerWithoutARestartPending() {
        show(restartRequired = false)
        compose.onNodeWithTag(SettingsDialogTags.RestartBanner).assertDoesNotExist()
    }

    /** The tip is the web's words, announced as the control's name, and opens on a tap. */
    @Test fun aTipOpensOnATap() {
        show()
        val tip = GeneralToggle.ConfirmBeforeEnd.tip
        compose.onNodeWithTag(SettingsTags.TipBubble, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(tip).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.TipBubble, useUnmergedTree = true).assertExists()
        // Tapping the tip does not flip the switch it sits in.
        toggle(GeneralToggle.ConfirmBeforeEnd).assertIsOn()
    }
}
