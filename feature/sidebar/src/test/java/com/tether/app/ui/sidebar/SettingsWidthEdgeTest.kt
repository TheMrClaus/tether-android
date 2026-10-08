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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-09ca E1 + ta-v8dt: the live Settings dialog takes its narrow metrics at studio.css:957's `(max-width: 640px)`, not at
 * the shell's 768. Since ta-v8dt it is ONE card shape at every width (SettingsShapeEdge640Test / 641Test hold the pixels):
 * at 640 dp a content-sized card 12 dp in from each side (`100vw - 24`, radius 14), from 641 dp `min(880, 100vw - 48)`.
 * This reads the width through the live SessionDrawer -> SettingsDialog path.
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

    @Test fun at640TheCaseIsTheCardTwelveInFromEachSide() {
        val w = caseWidth()
        assertEquals("the narrow card is 100vw - 24", 640f - 24f, w, 1f)
        assertTrue("and is not the full window", w < 640f - 20f)
    }

    @Test @Config(qualifiers = "w641dp-h900dp-mdpi")
    fun at641TheCaseIsTheCentredCard() {
        val w = caseWidth()
        assertEquals("the card is 100vw - 48 (24 in from each side)", 641f - 48f, w, 1f)
        assertTrue("and is narrower than the 640 card", w < 640f - 24f)
    }

    @Test @Config(qualifiers = "w768dp-h900dp-mdpi")
    fun atTheShellsOwnEdgeItIsTheCentredCardToo() {
        assertEquals(768f - 48f, caseWidth(), 1f)
    }
}
