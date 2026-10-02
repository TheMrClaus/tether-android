package com.tether.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.contextmenu.data.ProcessTextKey
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.platform.AndroidClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ta-78a / ta-oqx: the secret field's no-copy guard ([NoCopyScope]), layer by layer. Each layer is
 * tested ALONE next to a control field without it (the control shows the harness can see a copy,
 * a cut, a menu item), so a layer that stops working fails its own test even while another
 * layer still covers it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", shadows = [NoMagnifier::class, DeviceKeyCharacterMap::class])
class NoCopyGuardTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** The clipboard the fields write to: records every write. */
    @Suppress("VisibleForTests")
    private class RecordingClipboard(override val clipboardManager: ClipboardManager) : AndroidClipboard {
        val writes = mutableListOf<String?>()
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            writes += clipEntry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }
    }

    /** A host clipboard that is NOT an [AndroidClipboard] (the type the old guard fell back on). */
    private class PlainClipboard : Clipboard {
        val writes = mutableListOf<ClipEntry?>()
        var entry: ClipEntry? = null
        override suspend fun getClipEntry(): ClipEntry? = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            writes += clipEntry
        }
    }

    /** The new text context menu, as the platform toolbar would get it. */
    private class MenuSpy : TextContextMenuProvider {
        var shown: TextContextMenuDataProvider? = null
        var opened = 0

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            shown = dataProvider
            opened++
            try {
                awaitCancellation()
            } finally {
                if (shown === dataProvider) shown = null
            }
        }

        fun keys(): List<Any> = checkNotNull(shown) { "no text menu is open" }.data().components.map { it.key }
    }

    /** The old [TextToolbar]: counts every menu it is asked to show. */
    private class ToolbarSpy : TextToolbar {
        var shows = 0
        override val status = TextToolbarStatus.Hidden
        override fun hide() = Unit
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            shows++
        }
    }

    private var control by mutableStateOf(TEXT)
    private var guarded by mutableStateOf(TEXT)
    private var view: View? = null

    /**
     * Two fields under [clipboard]: [CONTROL] with no guard, [GUARDED] with [guardedField] (by
     * default the source guard ALONE, [NoCopyGuard], with no clipboard wrapper behind it).
     */
    private fun show(
        clipboard: Clipboard,
        menu: TextContextMenuProvider? = null,
        toolbar: TextToolbar? = null,
        guardedField: @Composable () -> Unit = { BasicTextField(guarded, { guarded = it }, NoCopyGuard.fillMaxWidth().testTag(GUARDED)) },
    ) {
        compose.setContent {
            val v = LocalView.current
            SideEffect { view = v }
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides (menu ?: LocalTextContextMenuToolbarProvider.current)) {
                    CompositionLocalProvider(LocalTextToolbar provides (toolbar ?: LocalTextToolbar.current)) {
                        Column {
                            BasicTextField(control, { control = it }, Modifier.fillMaxWidth().testTag(CONTROL))
                            guardedField()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun recording() = RecordingClipboard(context.getSystemService(ClipboardManager::class.java))

    // ---- the clipboard: fails closed ----------------------------------------------------------------

    /**
     * ta-78a (1): whatever clipboard the host provides, the guard's drops every write; reads still
     * pass. (The old guard handed a non-[AndroidClipboard] host clipboard through unguarded. A text
     * field under one cannot run in foundation 1.12 anyway, its paste check throws, so this is the
     * wrapper's own test.)
     */
    @Test fun theGuardsClipboardDropsEveryWriteWhateverTheHostsClipboardIs() = runBlocking {
        val clip = ClipEntry(ClipData.newPlainText("x", TEXT))
        val plain = PlainClipboard().apply { entry = ClipEntry(ClipData.newPlainText("paste", "pasted")) }
        val guardedPlain = noCopyClipboard(plain)
        guardedPlain.setClipEntry(clip)
        assertTrue("a write reached a plain host clipboard", plain.writes.isEmpty())
        assertEquals("pasted", guardedPlain.getClipEntry()?.clipData?.getItemAt(0)?.text)
        val android = recording()
        val guardedAndroid = noCopyClipboard(android)
        guardedAndroid.setClipEntry(clip)
        assertTrue("a write reached the platform clipboard", android.writes.isEmpty())
        // The text field's paste check needs the platform manager: the guard stays an AndroidClipboard.
        assertTrue(guardedAndroid is AndroidClipboard)
        assertTrue((guardedAndroid as AndroidClipboard).clipboardManager === android.clipboardManager)
    }

    // ---- the keys -----------------------------------------------------------------------------------

    /** The keys foundation 1.12's KeyMapping turns into COPY or CUT are blocked; paste, undo, select-all and deletions are not. */
    @Test fun theBlockedKeysAreTheFieldsCopyAndCutKeysAndOnlyThose() {
        fun ev(key: Key, meta: Int = 0) = KeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, key.nativeKeyCode, 0, meta))
        val ctrl = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
        val shift = android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
        val meta = android.view.KeyEvent.META_META_ON or android.view.KeyEvent.META_META_LEFT_ON
        val blocked = listOf(
            ev(Key.C, ctrl), ev(Key.Insert, ctrl), ev(Key.NumPadInsert, ctrl), ev(Key.X, ctrl),
            ev(Key.Copy), ev(Key.Cut), ev(Key.C, meta), ev(Key.X, meta),
        )
        for (e in blocked) assertTrue("not blocked: ${e.nativeKeyEvent}", NoCopyKeys.copiesOrCuts(e))
        val passed = listOf(
            ev(Key.C), ev(Key.X), ev(Key.Insert), ev(Key.V, ctrl), ev(Key.A, ctrl), ev(Key.Z, ctrl),
            ev(Key.Insert, shift), ev(Key.Delete, shift), ev(Key.Paste), ev(Key.Backspace),
        )
        for (e in passed) assertFalse("blocked: ${e.nativeKeyEvent}", NoCopyKeys.copiesOrCuts(e))
    }

    // ---- the source guard ALONE (no clipboard wrapper behind it) ------------------------------------

    /** ta-78a (1): Ctrl+C, Ctrl+Insert and KEYCODE_COPY copy nothing; the unguarded control copies each time. */
    @Test fun theCopyKeysCopyNothingFromTheGuardedField() {
        val clipboard = recording()
        show(clipboard)
        for (keys in ClipKeys.entries.filter { !it.cuts }) {
            tag(CONTROL).performClick()
            tag(CONTROL).selectAllAndPress(keys)
            compose.waitForIdle()
            assertEquals("the control did not copy on $keys", listOf<String?>(TEXT), clipboard.writes)
            clipboard.writes.clear()
            tag(GUARDED).performClick()
            tag(GUARDED).selectAllAndPress(keys)
            compose.waitForIdle()
            assertEquals("$keys copied from the guarded field", emptyList<String?>(), clipboard.writes)
        }
    }

    /** ta-oqx N1: Ctrl+X and KEYCODE_CUT neither copy nor delete; the control's cut does both. */
    @Test fun theCutKeysNeitherCopyNorDeleteInTheGuardedField() {
        val clipboard = recording()
        show(clipboard)
        for (keys in ClipKeys.entries.filter { it.cuts }) {
            control = TEXT
            compose.waitForIdle()
            tag(CONTROL).performClick()
            tag(CONTROL).selectAllAndPress(keys)
            compose.waitForIdle()
            assertEquals("the control did not cut on $keys", "", control)
            assertEquals(listOf<String?>(TEXT), clipboard.writes)
            clipboard.writes.clear()
            tag(GUARDED).performClick()
            tag(GUARDED).selectAllAndPress(keys)
            compose.waitForIdle()
            assertEquals("$keys deleted from the guarded field", TEXT, guarded)
            assertEquals("$keys copied from the guarded field", emptyList<String?>(), clipboard.writes)
        }
    }

    /** ta-78a (1), ta-oqx N1: the accessibility Copy and Cut do nothing in the guarded field. */
    @Test fun theAccessibilityCopyAndCutDoNothingInTheGuardedField() {
        val clipboard = recording()
        show(clipboard)
        for (t in listOf(CONTROL, GUARDED)) {
            tag(t).performClick()
            tag(t).performSemanticsAction(SemanticsActions.SetSelection) { it(0, TEXT.length, false) }
            compose.waitForIdle()
            assertTrue(tag(t).fetchSemanticsNode().config.contains(SemanticsActions.CopyText))
            tag(t).performSemanticsAction(SemanticsActions.CopyText)
            compose.waitForIdle()
            tag(t).performSemanticsAction(SemanticsActions.SetSelection) { it(0, TEXT.length, false) }
            compose.waitForIdle()
            assertTrue(tag(t).fetchSemanticsNode().config.contains(SemanticsActions.CutText))
            tag(t).performSemanticsAction(SemanticsActions.CutText)
            compose.waitForIdle()
        }
        assertEquals("the control copies then cuts", listOf<String?>(TEXT, TEXT), clipboard.writes)
        assertEquals("", control)
        assertEquals("the guarded field lost its text", TEXT, guarded)
    }

    /** ta-78a (1): an IME's Cut and Copy (InputConnection.performContextMenuAction) do nothing either. */
    @Test fun anImesCutAndCopyDoNothingInTheGuardedField() {
        val clipboard = recording()
        show(clipboard)
        for (t in listOf(CONTROL, GUARDED)) {
            tag(t).performClick()
            tag(t).performSemanticsAction(SemanticsActions.SetSelection) { it(0, TEXT.length, false) }
            compose.waitForIdle()
            val connection = compose.runOnIdle { checkNotNull(view).onCreateInputConnection(EditorInfo()) }
            compose.runOnIdle { connection.performContextMenuAction(android.R.id.copy) }
            compose.waitForIdle()
            compose.runOnIdle { connection.performContextMenuAction(android.R.id.cut) }
            compose.waitForIdle()
        }
        assertEquals("the control copies then cuts", listOf<String?>(TEXT, TEXT), clipboard.writes)
        assertEquals("", control)
        assertEquals(TEXT, guarded)
    }

    // ---- the menu ------------------------------------------------------------------------------------

    /**
     * ta-78a (2): the field's real menu in this Compose (foundation 1.12.1) is the NEW text context
     * menu, never the [TextToolbar] that [NoCopyToolbar] guards; and the guarded field's keeps only
     * Paste, Select all and Autofill: no Copy, no Cut, no process-text app (a translate or share
     * app would receive the selected text), no smart-selection item. The control offers all of
     * them, so the menu read here is the one that would be drawn.
     *
     * Fails on a Compose upgrade that moves the field back to [LocalTextToolbar] or changes how the
     * menu is built: re-check the guard then.
     */
    @Test fun theFieldsRealMenuIsTheNewContextMenuAndTheGuardedOneKeepsNothingThatReadsTheText() {
        assertTrue("the new context menu is off: NoCopyToolbar is the guard again", ComposeFoundationFlags.isNewContextMenuEnabled)
        installProcessTextApp()
        val menu = MenuSpy()
        val toolbar = ToolbarSpy()
        show(recording(), menu, toolbar, guardedField = { NoCopyScope(true) { g -> BasicTextField(guarded, { guarded = it }, g.fillMaxWidth().testTag(GUARDED)) } })
        tag(CONTROL).performTouchInput { longClick(centerLeft + androidx.compose.ui.geometry.Offset(12f, 0f)) }
        compose.waitForIdle()
        val controlKeys = menu.keys()
        assertTrue("control: $controlKeys", controlKeys.containsAll(listOf(TextContextMenuKeys.CopyKey, TextContextMenuKeys.CutKey)))
        assertTrue("no process-text item in the control: $controlKeys", controlKeys.any { it is ProcessTextKey })
        tag(GUARDED).performTouchInput { longClick(centerLeft + androidx.compose.ui.geometry.Offset(12f, 0f)) }
        compose.waitForIdle()
        assertNotNull("the guarded field opened no menu", menu.shown)
        val keys = menu.keys()
        val allowed = setOf(TextContextMenuKeys.PasteKey, TextContextMenuKeys.SelectAllKey, TextContextMenuKeys.AutofillKey)
        assertTrue("the guarded field's menu offers $keys", keys.all { it in allowed })
        assertTrue("the guarded field's menu lost Select all: $keys", TextContextMenuKeys.SelectAllKey in keys)
        assertEquals("the old TextToolbar was used", 0, toolbar.shows)
        assertEquals(2, menu.opened)
    }

    /** A translate-like app that takes ACTION_PROCESS_TEXT (what a real device offers in the menu). */
    private fun installProcessTextApp() {
        val app = ApplicationInfo().apply { packageName = PROCESS_APP }
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = PROCESS_APP
                name = "$PROCESS_APP.Translate"
                exported = true
                applicationInfo = app
            }
            nonLocalizedLabel = "Translate"
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), info)
    }

    private companion object {
        const val CONTROL = "nocopy-control"
        const val GUARDED = "nocopy-guarded"
        const val PROCESS_APP = "test.process.text"
        const val TEXT = "FAKE secret words"
    }
}
