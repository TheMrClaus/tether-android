package com.tether.app.ui.draft

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.20: the draft composer's model browser and phone settings sheet keep their typed search,
 * the Add-model field and where they are across a rotation (a browser resize keeps them).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DraftStateSaversTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theBrowserAndTheSettingsSheetRoundTripTheirTypedState() {
        val scope = SaverScope { true }
        val browser = ModelBrowserState(open = true, view = BrowserView.Provider("claude"), search = "opus", settingsOpen = true, addDraft = "my-model")
        val back = with(ModelBrowserState.Saver) { restore(scope.save(browser)!!)!! }
        assertTrue(back.open)
        assertEquals(BrowserView.Provider("claude"), back.view)
        assertEquals("opus", back.search)
        assertTrue(back.settingsOpen)
        assertEquals("my-model", back.addDraft)

        val sheet = DraftSettingsState(open = true, view = DraftSettingsView.Effort, entry = DraftSettingsView.Root, search = "son", browser = ModelBrowserState(addDraft = "x", view = BrowserView.All))
        val sheetBack = with(DraftSettingsState.Saver) { restore(scope.save(sheet)!!)!! }
        assertEquals(DraftSettingsView.Effort, sheetBack.view)
        assertEquals(DraftSettingsView.Root, sheetBack.entry)
        assertEquals("son", sheetBack.search)
        assertEquals("x", sheetBack.browser.addDraft)
        assertEquals(BrowserView.All, sheetBack.browser.view)
    }

    @Test fun aRotationKeepsTheBrowsersSearch() {
        val restoration = StateRestorationTester(rule)
        var browser: ModelBrowserState? = null
        restoration.setContent {
            browser = rememberSaveable("server-a", saver = ModelBrowserState.Saver) { ModelBrowserState() }
        }
        rule.waitForIdle()
        rule.runOnIdle { browser!!.search = "typed"; browser!!.addDraft = "my-model" }
        rule.waitForIdle()
        browser = null
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals("typed", browser!!.search)
        assertEquals("my-model", browser!!.addDraft)
    }
}
