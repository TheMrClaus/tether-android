package com.tether.app.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.30 (owner report): opening the Sessions drawer with the keyboard up must put it away —
 * focus leaves the composer and the IME hide is requested — on every path that opens the drawer.
 * The phone drawer has no edge swipe, so the paths are the top bar's key and a direct
 * [PhoneShellState.openDrawer] (the chat's empty-stage "Sessions" key, MainShell's `shell::openDrawer`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PhoneShellDrawerImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class RecordingKeyboard : SoftwareKeyboardController {
        val calls = mutableListOf<String>()
        override fun show() { calls += "show" }
        override fun hide() { calls += "hide" }
    }

    private val keyboard = RecordingKeyboard()

    private fun show(state: PhoneShellState) {
        val slots = placeholderSlots().let { base ->
            PhoneShellSlots(
                drawer = base.drawer,
                inspector = base.inspector,
                chat = {
                    Box(Modifier.fillMaxSize()) {
                        var draft by rememberSaveable { mutableStateOf("") }
                        BasicTextField(draft, { draft = it }, Modifier.fillMaxWidth().testTag(ComposerTag))
                    }
                },
            )
        }
        rule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                ShellUnderTest(TetherSkin.StudioDark, state, ShellFixtures.idle, slots = slots)
            }
        }
    }

    private fun composer() = rule.onNodeWithTag(ComposerTag)

    private fun composerText(): String =
        composer().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /** Focuses the composer and types a draft; returns with the keyboard log cleared. */
    private fun typeADraft() {
        composer().requestFocus()
        composer().performTextInput("half a thought")
        composer().assertIsFocused()
        rule.waitForIdle()
        keyboard.calls.clear()
    }

    private fun assertKeyboardPutAway(state: PhoneShellState) {
        rule.waitForIdle()
        assertTrue(state.drawerOpen)
        composer().assertIsNotFocused()
        assertTrue("the IME hide was not requested: ${keyboard.calls}", "hide" in keyboard.calls)
        assertEquals("half a thought", composerText())
    }

    /** Closing the drawer keeps the keyboard down and the draft in place. */
    private fun assertClosingLeavesTheKeyboardDown(state: PhoneShellState) {
        keyboard.calls.clear()
        rule.runOnUiThread { state.closeDrawer() }
        rule.waitForIdle()
        assertFalse(state.drawerOpen)
        composer().assertIsNotFocused()
        assertFalse("closing the drawer raised the keyboard: ${keyboard.calls}", "show" in keyboard.calls)
        assertEquals("half a thought", composerText())
    }

    @Test fun theTopBarKeyPutsTheKeyboardAway() {
        val state = PhoneShellState()
        show(state)
        typeADraft()
        rule.onNodeWithContentDescription("Open sessions").performClick()
        assertKeyboardPutAway(state)
        assertClosingLeavesTheKeyboardDown(state)
    }

    @Test fun anyOtherOpenDrawerPutsTheKeyboardAway() {
        val state = PhoneShellState()
        show(state)
        typeADraft()
        rule.runOnUiThread { state.openDrawer() } // the chat's "Sessions" key, MainShell's shell::openDrawer
        assertKeyboardPutAway(state)
        assertClosingLeavesTheKeyboardDown(state)
    }

    /** ta-coik.31: the top bar's right-hand tools menu takes the keyboard away too (the browser's focus move). */
    @Test fun theToolsMenuKeyPutsTheKeyboardAway() {
        val state = PhoneShellState()
        show(state)
        typeADraft()
        rule.onNodeWithContentDescription("Menu: navigation and tools", substring = true).performClick()
        rule.waitForIdle()
        assertTrue(state.menuOpen)
        composer().assertIsNotFocused()
        assertTrue("the IME hide was not requested: ${keyboard.calls}", "hide" in keyboard.calls)
        assertEquals("half a thought", composerText())
        // Closing the menu keeps the keyboard down and the draft in place.
        keyboard.calls.clear()
        rule.runOnUiThread { state.closeMenu() }
        rule.waitForIdle()
        assertFalse(state.menuOpen)
        composer().assertIsNotFocused()
        assertFalse("closing the menu raised the keyboard: ${keyboard.calls}", "show" in keyboard.calls)
        assertEquals("half a thought", composerText())
    }

    @Test fun anyOtherOpenMenuPutsTheKeyboardAway() {
        val state = PhoneShellState()
        show(state)
        typeADraft()
        rule.runOnUiThread { state.toggleMenu() }
        rule.waitForIdle()
        assertTrue(state.menuOpen)
        composer().assertIsNotFocused()
        assertTrue("the IME hide was not requested: ${keyboard.calls}", "hide" in keyboard.calls)
        assertEquals("half a thought", composerText())
    }

    private companion object {
        const val ComposerTag = "test-composer"
    }
}
