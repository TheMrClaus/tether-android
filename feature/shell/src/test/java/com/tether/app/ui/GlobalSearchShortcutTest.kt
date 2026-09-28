package com.tether.app.ui

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_F
import android.view.KeyEvent.KEYCODE_G
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_META_ON
import android.view.KeyEvent.META_SHIFT_ON
import androidx.compose.ui.input.key.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** T5.3 dashboard.tsx:1186-1194: Ctrl/Cmd+Shift+F, never with Alt, only on key down. */
@RunWith(RobolectricTestRunner::class)
class GlobalSearchShortcutTest {
    private fun key(code: Int, meta: Int, action: Int = ACTION_DOWN) = KeyEvent(android.view.KeyEvent(0, 0, action, code, 0, meta))

    @Test fun ctrlOrCmdShiftFOpensTheGlobalSearch() {
        assertTrue(isGlobalSearchShortcut(key(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON)))
        assertTrue(isGlobalSearchShortcut(key(KEYCODE_F, META_META_ON or META_SHIFT_ON)))
    }

    @Test fun anythingElseDoesNot() {
        // Ctrl+F without Shift is the in-chat find, not the global search.
        assertFalse(isGlobalSearchShortcut(key(KEYCODE_F, META_CTRL_ON)))
        assertFalse(isGlobalSearchShortcut(key(KEYCODE_F, META_SHIFT_ON)))
        assertFalse(isGlobalSearchShortcut(key(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON or META_ALT_ON)))
        assertFalse(isGlobalSearchShortcut(key(KEYCODE_G, META_CTRL_ON or META_SHIFT_ON)))
        assertFalse(isGlobalSearchShortcut(key(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON, action = ACTION_UP)))
    }
}
