package com.tether.app.ui.shell

import com.tether.app.testsupport.runPrefsWrite

import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.prefs.UiPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The expanded shell's commit path end to end: [PanelPrefs.applyTo] through the app's preference
 * store ([UiPrefs], the web's per-device `tether.preferences.v1`) and back — what MainShell does on
 * every settled drag, and what it reads on the next launch.
 */
@RunWith(RobolectricTestRunner::class)
class PanelPrefsPersistenceTest {
    @Test fun widthsAndCollapseRoundTripThroughTheStore() = runPrefsWrite {
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        prefs.updatePreferences { it.copy(showThinking = true) }
        val committed = PanelPrefs(sidebarWidth = 336, inspectorWidth = 300, sidebarCollapsed = true)
        prefs.updatePreferences { committed.applyTo(it) }
        val back = prefs.preferences.first()
        assertEquals(committed, PanelPrefs.from(back))
        assertEquals(true, back.showThinking) // the other fields are untouched

        // A reset (null) removes the width, which reads back as "the theme default".
        prefs.updatePreferences { committed.withWidth(PanelKind.Rail, null).copy(sidebarCollapsed = false).applyTo(it) }
        assertEquals(PanelPrefs(sidebarWidth = null, inspectorWidth = 300), PanelPrefs.from(prefs.preferences.first()))
    }
}
