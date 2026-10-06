package com.tether.app.ui.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ta-coik.64: every copy puts the EXACT source on the clipboard, as the web's writeText does; the
 * screen alone draws hidden characters as tokens. The notice only informs.
 */
@RunWith(RobolectricTestRunner::class)
class CopyExactTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // bidi override and pop, an isolate, a zero-width space, an NBSP, an LRM, an ESC, a tab
    private val hostile = "run‮/lanif‬ ⁦x⁩ a​b c d e‎f\u001Bg\th"

    private fun clip(): String? = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    @Test fun copyExactWritesTheOriginalNotTheVisibleForm() {
        for (rule in listOf(SafeText.Rule.Prose, SafeText.Rule.Code, SafeText.Rule.Line, SafeText.Rule.Exact)) {
            val notices = CopyNotices()
            val shown = SafeText.encode(hostile, rule)
            // The screen draws tokens...
            assertEquals(true, shown.contains("⁠⟨U+202E⟩"))
            // ...the clipboard carries the exact string, with the source decoded from the display alone too.
            assertEquals(true, copyExact(context, shown, notices))
            assertEquals("$rule", hostile, clip())
            assertNotNull(notices.current)
            assertEquals(true, copyExact(context, shown, notices, raw = hostile, strict = true))
            assertEquals("$rule raw", hostile, clip())
        }
    }

    @Test fun aCleanCopyHasNoNotice() {
        val notices = CopyNotices()
        assertEquals(true, copyExact(context, SafeText.code("/srv/app"), notices))
        assertEquals("/srv/app", clip())
        assertNull(notices.current)
    }

    private class Recorder(val manager: ClipboardManager) : Clipboard {
        var last: ClipEntry? = null
        override suspend fun getClipEntry(): ClipEntry? = last
        override suspend fun setClipEntry(clipEntry: ClipEntry?) { last = clipEntry }
        override val nativeClipboard get() = manager
    }

    @Test fun aSelectionCopyDecodesTheDrawnTokensToTheExactSource() = runBlocking {
        val base = Recorder(context.getSystemService(ClipboardManager::class.java))
        val notices = CopyNotices()
        val clipboard = SafeCopyClipboard(base, notices)
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("t", SafeText.code(hostile))))
        assertEquals(hostile, base.last!!.clipData.getItemAt(0).text.toString())
        assertNotNull(notices.current)
        // Nothing drawn in it: passes through untouched and clears the notice.
        val clean = ClipEntry(ClipData.newPlainText("t", "plain text"))
        clipboard.setClipEntry(clean)
        assertEquals(true, base.last === clean)
        assertNull(notices.current)
    }
}
