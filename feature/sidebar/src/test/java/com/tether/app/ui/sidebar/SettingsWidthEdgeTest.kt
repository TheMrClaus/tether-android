package com.tether.app.ui.sidebar

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.tether.app.ui.SessionDrawer
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.settings.SettingsDialogTags
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The live Runtime Desk is a full-page Activity branch at the contract widths. This reads the
 * exact surface width through the live SessionDrawer -> Settings path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w640dp-h900dp-mdpi")
class SettingsWidthEdgeTest {
    val tmp = TemporaryFolder()
    val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(rule)
    private val storeJob = Job()

    @After fun closeStore() = storeJob.cancel()

    private fun caseWidth(): Float {
        val client = RecordingClient()
        val vm = TetherViewModel(client)
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob)) { File(tmp.root, "ui.preferences_pb") }
        val prefs = UiPrefs.on(store)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                SessionDrawer(vm = vm, prefs = prefs, sessions = emptyList(), selectedId = null, workspaceRoot = SidebarFixtures.ROOT, onSelect = {}, onClose = {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Open settings").performClick()
        rule.waitForIdle()
        return rule.onNodeWithTag(SettingsDialogTags.Dialog).fetchSemanticsNode().boundsInRoot.width
    }

    @Test fun at640TheSurfaceFillsTheWindow() {
        assertEquals("the Runtime Desk fills 640dp", 640f, caseWidth(), 1f)
    }

    @Test @Config(qualifiers = "w641dp-h900dp-mdpi")
    fun at641TheSurfaceFillsTheWindow() {
        assertEquals("the Runtime Desk fills 641dp", 641f, caseWidth(), 1f)
    }

    @Test @Config(qualifiers = "w768dp-h900dp-mdpi")
    fun atTheExpandedBreakpointTheSurfaceFillsTheWindow() {
        assertEquals("the Runtime Desk fills 768dp", 768f, caseWidth(), 1f)
    }
}
