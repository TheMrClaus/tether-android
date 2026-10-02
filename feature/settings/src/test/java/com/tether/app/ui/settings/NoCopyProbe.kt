package com.tether.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.test.core.app.ApplicationProvider

/**
 * ta-78a / ta-oqx: a secret field's copy and cut driven every way a user can (the hardware keys
 * the text field maps to COPY / CUT, and the accessibility actions), and what reached the
 * system clipboard. [seed] first: a write of ANY text then shows, not only one holding the secret.
 */
internal object NoCopyProbe {
    const val MARKER = "probe-clipboard-before"

    private fun manager(): ClipboardManager = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)

    fun seed() = manager().setPrimaryClip(ClipData.newPlainText("probe", MARKER))

    /** The system clipboard's text, or null. */
    fun clip(): String? = manager().primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
}

/** The hardware gestures foundation 1.12's KeyMapping turns into COPY or CUT (an IME's cut and copy arrive as KEYCODE_CUT / KEYCODE_COPY). */
@OptIn(ExperimentalTestApi::class)
internal enum class ClipKeys(val cuts: Boolean, val press: KeyInjectionScope.() -> Unit) {
    CtrlC(false, { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }),
    CtrlInsert(false, { withKeyDown(Key.CtrlLeft) { pressKey(Key.Insert) } }),
    CopyKey(false, { pressKey(Key.Copy) }),
    CtrlX(true, { withKeyDown(Key.CtrlLeft) { pressKey(Key.X) } }),
    CutKey(true, { pressKey(Key.Cut) }),
}

internal fun SemanticsNodeInteraction.editableText(): String? = fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

/** Select the whole field (it must hold focus) and press [keys]. */
@OptIn(ExperimentalTestApi::class)
internal fun SemanticsNodeInteraction.selectAllAndPress(keys: ClipKeys) {
    val n = editableText().orEmpty().length
    performSemanticsAction(SemanticsActions.SetSelection) { it(0, n, false) }
    performKeyInput(keys.press)
}
