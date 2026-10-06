package com.tether.app.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.tether.app.ui.settings.AccountsFixtures.ORIGIN
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ta-coik.20: a rotation keeps the Add Claude account nickname being typed (saved; nothing is sent). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ClaudeAccountsRotationTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    @Test fun aRotationKeepsTheNicknameBeingAdded() {
        val actions = FakeAccountActions()
        val binding = ClaudeAccountsBinding(FakeAccounts(), ORIGIN, AccountsFixtures.TIME, actions = actions, pace = LoginPollPace(20, 20, 20))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val state = androidx.compose.runtime.saveable.rememberSaveable(saver = SettingsDialogState.Saver) { SettingsDialogState(SettingsTab.Engines) }
            SettingsUnderTest(store.prefs, state, claudeAccounts = binding)
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(ClaudeAccountsTags.Add, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        tag(ClaudeAccountsTags.Add).performScrollTo().performClick()
        tag(ClaudeAccountsTags.AddField).performTextReplacement("work-two")
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(ClaudeAccountsTags.AddField, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("work-two", tag(ClaudeAccountsTags.AddField).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
        assertEquals("a rotation sends no add", emptyList<Any>(), actions.calls.filter { it.toString().startsWith("add") })
    }
}
