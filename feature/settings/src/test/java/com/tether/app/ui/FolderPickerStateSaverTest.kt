package com.tether.app.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ta-coik.20: the folder picker's typed new-folder name (and its form) survive a rotation. */
class FolderPickerStateSaverTest {
    @Test fun theNewFolderFormRoundTrips() {
        val scope = SaverScope { true }
        val state = FolderPickerState(newFolderOpen = true, newFolderName = "projects-2", awaitingCwd = "/home/me")
        val back = with(FolderPickerState.Saver) { restore(scope.save(state)!!)!! }
        assertTrue(back.newFolderOpen)
        assertEquals("projects-2", back.newFolderName)
        assertEquals("/home/me", back.awaitingCwd)
        val none = with(FolderPickerState.Saver) { restore(scope.save(FolderPickerState())!!)!! }
        assertNull(none.awaitingCwd)
        assertEquals("", none.newFolderName)
    }
}
