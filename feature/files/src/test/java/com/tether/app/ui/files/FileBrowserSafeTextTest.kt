package com.tether.app.ui.files

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.FilesResult
import com.tether.app.client.WorkspaceFileEntry
import com.tether.app.ui.files.FilesFixtures.ROOT
import com.tether.app.ui.text.COPY_NOTICE_TAG
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-28i: the file browser draws server text by the shared rules. A file body is CODE (the Trojan
 * Source case: every bidi / invisible control a visible token, every line LTR, in an LTR and an RTL
 * UI), a copy from it is the exact source (ta-coik.64) with an informational notice; file names and paths
 * are code everywhere they show (rows, preview head, actions, delete and rename titles, TalkBack
 * words); a server's error is prose; the session title is a label. Real Hebrew and Arabic stay
 * letters, in their order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", shadows = [NoMagnifier::class])
class FileBrowserSafeTextTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val RLO = "\u202E"
        const val PDF = "\u202C"
        const val LRI = "\u2066"
        const val RLI = "\u2067"
        const val PDI = "\u2069"
        const val ZWSP = "\u200B"
        const val HEBREW = "\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD"
        const val ARABIC = "\u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645"

        /** The Trojan Source "commenting-out" form: the check reads as a comment. */
        const val LINE1 = "if (accessLevel != \"user$RLO $LRI// Check if admin$PDI $LRI\") {"
        const val LINE2 = "    grant();$ZWSP"
        const val TROJAN = "$LINE1\r\n$LINE2\r\n// $HEBREW\n// $ARABIC\nlone\rcr\n}"
        const val HOSTILE_NAME = "invoice${RLO}fdp.exe"
        val BIDI = ('\u202A'..'\u202E') + ('\u2066'..'\u2069') + listOf('\u200E', '\u200F', '\u061C')
        fun tok(cp: Int) = "\u2060\u27E8U+%04X\u27E9".format(cp)
        fun vis(cp: Int) = "\u27E8U+%04X\u27E9".format(cp)
    }

    private val source = FilesFixtures.file("access.js", TROJAN.length.toLong())
    private val hostile = FilesFixtures.file(HOSTILE_NAME, 1_024)

    private fun state(select: WorkspaceFileEntry? = source, session: String = FilesFixtures.SESSION): FileBrowserState {
        val files = FakeFiles().apply {
            listings[ROOT] = FilesResult.Ok(FilesFixtures.listing(entries = listOf(FilesFixtures.docs, source, hostile)))
            texts[source.path] = FilesResult.Ok(TROJAN)
        }
        return FileBrowserState(files, FakePlatform(), CoroutineScope(Dispatchers.Unconfined)).apply {
            cwd = ROOT
            sessionName = session
            open()
            select?.let { selectFile(it) }
        }
    }

    private fun show(state: FileBrowserState, rtl: Boolean = false, overlay: @Composable () -> Unit = {}) {
        rule.setContent {
            TetherTheme(ThemeMode.Dark) {
                CompositionLocalProvider(
                    LocalReducedMotion provides true,
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        FileBrowserFrame(state, onClose = {}, onUpload = {}, env = FilesFixtures.env)
                        overlay()
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** Every text and description TalkBack could read, unmerged. */
    private fun spoken(): List<String> = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { n ->
        n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } +
            n.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty()
    }

    private fun assertNoRawBidi() {
        for (s in spoken()) for (c in BIDI) assertFalse("raw U+%04X in \"$s\"".format(c.code), s.contains(c))
    }

    private fun layoutOf(text: String): Pair<String, TextLayoutResult> {
        val node = rule.onNodeWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first().let { drawn(it.layoutInput.text.text) to it }
    }

    /** A drawn preview line without its r2 copy markers (zero-width; [PreviewCopy]). */
    private fun drawn(s: String) = s.removeSuffix(PreviewCopy.CRLF_END).removeSuffix(PreviewCopy.CONT_END).removeSuffix(PreviewCopy.LF_END)

    /** The drawn ASCII letters and digits outside tokens: do they read left to right, line by line? */
    private fun latinReadsInOrder(shown: String, layout: TextLayoutResult): Boolean {
        val content = ArrayList<Int>()
        var i = 0
        while (i < shown.length) {
            val u = if (shown[i] == SafeText.MARK) SafeText.unitAt(shown, i) else null
            if (u != null) {
                i = u.end
                continue
            }
            if (shown[i] in 'a'..'z' || shown[i] in 'A'..'Z' || shown[i] in '0'..'9') content.add(i)
            i++
        }
        val at = content.map { layout.getLineForOffset(it) to layout.getBoundingBox(it).left }
        return at.zipWithNext().all { (a, b) -> a.first < b.first || (a.first == b.first && a.second < b.second) }
    }

    private fun clip(): String? =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    private fun hex(s: String) = s.map { "%04X".format(it.code) }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    private class MenuSpy : androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider {
        @Volatile var shown: androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider? = null

        override suspend fun showTextContextMenu(dataProvider: androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider) {
            shown = dataProvider
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                if (shown === dataProvider) shown = null
            }
        }

        fun press(key: Any) {
            val menu = checkNotNull(shown) { "no text toolbar is open" }
            val item = menu.data().components.filterIsInstance<androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem>().first { it.key == key }
            item.onClick(object : androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession { override fun close() = Unit })
        }
    }

    // ---- the text preview (Trojan Source) -------------------------------------------------------

    private fun assertTrojanSourceIsShownLtr(rtl: Boolean) {
        show(state(), rtl = rtl)
        val (shown, layout) = layoutOf("accessLevel")
        assertEquals(
            "if (accessLevel != \"user${tok(0x202E)} ${tok(0x2066)}// Check if admin${tok(0x2069)} ${tok(0x2066)}\") {",
            shown,
        )
        assertEquals("rtl UI = $rtl", ResolvedTextDirection.Ltr, layout.getParagraphDirection(0))
        assertTrue("the words read in their stored order (rtl UI = $rtl)", latinReadsInOrder(shown, layout))
        // The ZWSP after grant(); is a token too; CRLF line ends never are.
        val (grant, _) = layoutOf("grant()")
        assertEquals("    grant();${tok(0x200B)}", grant)
        assertFalse(spoken().any { it.contains(vis(0x000D)) && !it.startsWith("lone") })
        // r2: the CRLF lines carry their line break as the (invisible) marker, the LF lines theirs.
        assertTrue(spoken().any { it == "    grant();${tok(0x200B)}${PreviewCopy.CRLF_END}" })
        // A lone CR (not a line break's) is shown.
        assertEquals("lone${tok(0x000D)}cr", layoutOf("lone").first)
        assertNoRawBidi()
    }

    @Test fun everyBidiAndInvisibleControlInAFileBodyIsAVisibleTokenAndTheLineReadsLtr() = assertTrojanSourceIsShownLtr(rtl = false)

    @Test fun inAnRtlUiAFileBodyStillReadsLtr() = assertTrojanSourceIsShownLtr(rtl = true)

    @Test fun realHebrewAndArabicInAFileStayLettersAndReadRightToLeft() {
        show(state())
        for (word in listOf(HEBREW, ARABIC)) {
            val (shown, layout) = layoutOf(word)
            assertEquals("// $word", shown)
            assertEquals(ResolvedTextDirection.Ltr, layout.getParagraphDirection(0))
            // Inside the LTR line the RTL word runs right to left, as in every editor.
            val first = shown.indexOf(word)
            assertTrue(layout.getBoundingBox(first).left > layout.getBoundingBox(first + 3).left)
        }
    }

    @Test fun aCopyFromThePreviewIsTheExactSourceWhileTheScreenKeepsItsTokens() {
        val menu = MenuSpy()
        val browser = state()
        rule.setContent {
            CompositionLocalProvider(androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider provides menu) {
                TetherTheme(ThemeMode.Dark) {
                    CompositionLocalProvider(LocalReducedMotion provides true) { FileBrowserFrame(browser, onClose = {}, onUpload = {}, env = FilesFixtures.env) }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("accessLevel", substring = true, useUnmergedTree = true).performTouchInput { longClick(center) }
        // ta-9dpl: wait for the menu itself (as NoCopyProbe.longPressForMenu does): an idle frame after
        // the long press is not always enough on a loaded machine, and the press then finds no menu.
        rule.waitUntil(20_000) { menu.shown != null }
        menu.press(androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys.SelectAllKey)
        rule.waitForIdle()
        menu.press(androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys.CopyKey)
        rule.waitForIdle()
        // ta-coik.64: one copy, the file byte for byte (hidden controls, line breaks, CRLFs, the lone CR),
        // with no visible-token form and no second step.
        assertEquals("the exact source: ${hex(clip().orEmpty())}", TROJAN, clip())
        rule.onNodeWithTag(COPY_NOTICE_TAG).assertIsDisplayed()
        assertTrue(spoken().any { Regex("^Copied text has \\d+ hidden control characters, drawn as \u27E8U\\+\u2026\u27E9$").matches(it) })
        // The screen still draws the tokens.
        assertTrue(spoken().toString(), spoken().any { it.contains(tok(0x202E)) })
    }

    /** r2 (security L2): a selection over two lines copies them WITH their line break and tabs. */
    @Test fun aTwoLineCopyKeepsItsLineBreakAndTabs() {
        val menu = MenuSpy()
        val two = FilesFixtures.file("two.txt", 32)
        val files = FakeFiles().apply {
            listings[ROOT] = FilesResult.Ok(FilesFixtures.listing(entries = listOf(two)))
            texts[two.path] = FilesResult.Ok("alpha\tone\nbeta\r\ngamma")
        }
        val browser = FileBrowserState(files, FakePlatform(), CoroutineScope(Dispatchers.Unconfined)).apply { cwd = ROOT; open(); selectFile(two) }
        rule.setContent {
            CompositionLocalProvider(androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider provides menu) {
                TetherTheme(ThemeMode.Dark) {
                    CompositionLocalProvider(LocalReducedMotion provides true) { FileBrowserFrame(browser, onClose = {}, onUpload = {}, env = FilesFixtures.env) }
                }
            }
        }
        rule.waitForIdle()
        // The tab still draws at two columns.
        assertEquals("alpha  one", layoutOf("alpha").first.replace(PreviewCopy.TAB, "  "))
        rule.onNodeWithText("alpha", substring = true, useUnmergedTree = true).performTouchInput { longClick(centerLeft + androidx.compose.ui.geometry.Offset(6f, 0f)) }
        // ta-9dpl: wait for the menu itself (as NoCopyProbe.longPressForMenu does): an idle frame after
        // the long press is not always enough on a loaded machine, and the press then finds no menu.
        rule.waitUntil(20_000) { menu.shown != null }
        menu.press(androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys.SelectAllKey)
        rule.waitForIdle()
        menu.press(androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys.CopyKey)
        rule.waitForIdle()
        assertEquals("alpha\tone\nbeta\r\ngamma", clip())
        // Nothing hidden was in it: no notice.
        rule.onNodeWithTag(COPY_NOTICE_TAG).assertDoesNotExist()
    }

    @Test fun theCopyMarkersDecodeAndHalfAMarkIsDropped() {
        val pieces = previewPieces("a\tb\r\nc\nd")
        assertEquals(listOf(PreviewPiece("a\tb", "\r\n"), PreviewPiece("c", "\n"), PreviewPiece("d", "")), pieces)
        // As a selection hands it over: each Text's part, a "\n" between them.
        val selected = pieces.mapIndexed { i, p -> PreviewCopy.display(p, last = i == pieces.lastIndex) }.joinToString("\n")
        assertEquals("a\tb\r\nc\nd", PreviewCopy.decode(selected))
        // A long line split into pieces copies as ONE line again.
        val long = "x".repeat(9_000) + "\nend"
        val longPieces = previewPieces(long)
        assertEquals(4, longPieces.size)
        assertEquals(long, PreviewCopy.decode(longPieces.mapIndexed { i, p -> PreviewCopy.display(p, last = i == longPieces.lastIndex) }.joinToString("\n")))
        // A selection that starts or ends inside a marker never copies half of it.
        assertEquals("c\n", PreviewCopy.decode("c\u2060\u200C"))
        assertEquals("c\nd", PreviewCopy.decode("c\u2060\u200C\nd"))
        assertEquals("b", PreviewCopy.decode("b\u2060"))
        assertEquals("  b", PreviewCopy.decode("\u200D  b"))
        // File content can never pass for a marker: the code rule draws its WJ / ZWNJ / ZWJ as tokens.
        val hostile = PreviewCopy.display(PreviewPiece("x\u2060\u200Cy\u2060\u200D  z"))
        assertEquals("x\u2060\u200Cy\u2060\u200D  z", com.tether.app.ui.text.SafeText.original(PreviewCopy.decode(hostile)))
        assertFalse(PreviewCopy.decode(hostile).contains('\n') || PreviewCopy.decode(hostile).contains('\t'))
    }

    @Test fun previewPiecesNeverSplitAPairOrAnAccentAndCrlfIsALineBreak() {
        val piece = 4_000
        // A surrogate pair and a combining accent straddling the piece edge.
        val pair = previewLines("a".repeat(piece - 1) + "\uD83D\uDE00" + "b".repeat(10))
        assertEquals("a".repeat(piece - 1), pair[0])
        assertEquals("\uD83D\uDE00" + "b".repeat(10), pair[1])
        val accent = previewLines("a".repeat(piece - 1) + "e\u0301" + "b".repeat(10))
        assertEquals("a".repeat(piece - 1), accent[0])
        assertTrue(accent[1].startsWith("e\u0301"))
        for (line in pair + accent) for (i in line.indices) {
            if (Character.isHighSurrogate(line[i])) assertTrue(i + 1 < line.length && Character.isLowSurrogate(line[i + 1]))
            if (Character.isLowSurrogate(line[i])) assertTrue(i > 0 && Character.isHighSurrogate(line[i - 1]))
        }
        // One cluster longer than a piece still makes progress, never splitting a pair.
        val flood = previewLines("x" + "\u0301".repeat(piece * 2 + 5))
        assertEquals(piece * 2 + 6, flood.sumOf { it.length })
        assertEquals(listOf("a", "b", "c", "d\r"), previewLines("a\r\nb\nc\r\nd\r"))
        assertEquals(listOf("a", "lone\rcr"), previewLines("a\r\nlone\rcr"))
    }

    // ---- file names, paths and the session title ------------------------------------------------

    @Test fun aHostileFileNameIsCodeInTheRowAndItsActionsAndTheTitleIsALabel() {
        show(state(select = null, session = "Fix ${RLO}lanif$PDF bug$ZWSP"))
        val shownName = "invoice${tok(0x202E)}fdp.exe"
        assertEquals(shownName, layoutOf("invoice").first.let { it.substring(it.indexOf("invoice")) })
        assertTrue(spoken().contains("Actions for $shownName"))
        assertTrue(spoken().any { it == "Browse Fix lanif bug without leaving the console." })
        val (_, layout) = layoutOf("invoice")
        assertEquals(ResolvedTextDirection.Ltr, layout.getParagraphDirection(0))
        assertNoRawBidi()
    }

    @Test fun theDeleteAndRenameTitlesNameTheFileAsItIs() {
        show(state(select = null)) {
            DeleteConfirmContent(hostile, error = "Could not delete: $RLO" + "evil$PDF", submitting = false, onConfirm = {}, onCancel = {})
        }
        assertTrue(spoken().contains("Delete invoice${tok(0x202E)}fdp.exe?"))
        assertTrue(spoken().contains("Could not delete: ${tok(0x202E)}evil${tok(0x202C)}"))
        assertNoRawBidi()
    }

    @Test fun theActionsSheetTitleIsCode() {
        show(state(select = null)) {
            ItemActionsContent(hostile, {}, {}, {}, onSave = null, onShare = null, onDelete = {}, onCancel = {})
        }
        assertTrue(spoken().contains("invoice${tok(0x202E)}fdp.exe"))
        assertNoRawBidi()
    }

    @Test fun theRenamePromptTitleIsCode() {
        show(state(select = null)) {
            NamePromptContent(NamePrompt(NamePromptMode.Rename, hostile, "x"), error = "", submitting = false, onValueChange = {}, onSubmit = {}, onCancel = {})
        }
        assertTrue(spoken().contains("Rename invoice${tok(0x202E)}fdp.exe"))
    }

    @Test fun aHostileFolderInTheBreadcrumbsAndDestinationPickerIsCode() {
        val dir = "$ROOT/sr${RLI}c$PDI"
        val files = FakeFiles().apply {
            listings[dir] = FilesResult.Ok(FilesFixtures.listing(dir, entries = listOf(FilesFixtures.dir("in${LRI}ner$PDI", dir))))
        }
        val s = FileBrowserState(files, FakePlatform(), CoroutineScope(Dispatchers.Unconfined)).apply {
            cwd = dir
            open()
            openDestPicker(FilesFixtures.file("a.txt", 1, dir), DestinationMode.Move)
        }
        show(s) { s.destPicker?.let { DestinationPickerFrame(it, submitting = false, onBrowse = {}, onConfirm = {}, onClose = {}) } }
        assertTrue(spoken().any { it == "sr${tok(0x2067)}c${tok(0x2069)}" })
        assertTrue(spoken().any { it.contains("in${tok(0x2066)}ner${tok(0x2069)}") })
        assertNoRawBidi()
    }

    /** r2 (M1): two rows "invoice.pdf" + LF + ".sh" and "invoice.pdf" never look alike; TalkBack says so too. */
    @Test fun aLineBreakOrTabInAFileNameIsATokenInItsRowAndDescription() {
        val lf = FilesFixtures.file("invoice.pdf\n.sh", 10)
        val plain = FilesFixtures.file("invoice.pdf", 10)
        val tab = FilesFixtures.file("a\tb.txt", 10)
        val files = FakeFiles().apply { listings[ROOT] = FilesResult.Ok(FilesFixtures.listing(entries = listOf(lf, plain, tab))) }
        val s = FileBrowserState(files, FakePlatform(), CoroutineScope(Dispatchers.Unconfined)).apply { cwd = ROOT; open() }
        show(s)
        val shown = spoken()
        assertTrue(shown.toString(), shown.contains("invoice.pdf${tok(0x0A)}.sh"))
        assertTrue(shown.contains("invoice.pdf"))
        assertTrue(shown.contains("a${tok(0x09)}b.txt"))
        assertTrue(shown.contains("Actions for invoice.pdf${tok(0x0A)}.sh"))
        assertTrue(shown.contains("Actions for a${tok(0x09)}b.txt"))
        for (t in shown) assertFalse("raw line break or tab in \"$t\"", t.contains('\n') || t.contains('\t'))
    }

    @Test fun aLineBreakInADestinationFolderIsAToken() {
        val dir = "$ROOT/sr\nc"
        val files = FakeFiles().apply {
            listings[dir] = FilesResult.Ok(FilesFixtures.listing(dir, entries = listOf(FilesFixtures.dir("in\nner", dir))))
        }
        val s = FileBrowserState(files, FakePlatform(), CoroutineScope(Dispatchers.Unconfined)).apply {
            cwd = dir
            open()
            openDestPicker(FilesFixtures.file("a.txt", 1, dir), DestinationMode.Move)
        }
        show(s) { s.destPicker?.let { DestinationPickerFrame(it, submitting = false, onBrowse = {}, onConfirm = {}, onClose = {}) } }
        val shown = spoken()
        assertTrue(shown.toString(), shown.contains("in${tok(0x0A)}ner"))
        assertTrue(shown.any { it == "Destination: $dir".replace("\n", tok(0x0A)) })
        for (t in shown) assertFalse("raw line break in \"$t\"", t.contains('\n'))
    }

    /** r2: splitting a hostile single line at the real 1 MiB preview cap is linear (boundary tests counted). */
    @Test fun previewLinesIsLinearOnAHostileMegabyteLine() {
        val n = com.tether.app.client.WorkspaceFiles.MAX_TEXT_PREVIEW_BYTES.toInt()
        for (text in listOf("x" + "\u0301".repeat(n - 1), "\uD83D\uDC68\u200D".repeat(n / 3), "\uD83C\uDDE9".repeat(n / 2))) {
            val probe = java.util.concurrent.atomic.AtomicLong()
            com.tether.app.client.TextCut.stepProbe = probe
            val lines = try { previewLines(text) } finally { com.tether.app.client.TextCut.stepProbe = null }
            assertEquals(text, lines.joinToString(""))
            assertTrue("${probe.get()} steps for ${text.length}", probe.get() <= 8L * text.length)
            for (line in lines) {
                assertFalse(line.isNotEmpty() && Character.isLowSurrogate(line[0]))
                assertFalse(line.isNotEmpty() && Character.isHighSurrogate(line[line.length - 1]))
            }
        }
    }

    /** r2 (L3): the Rename field never holds a hidden control the reader cannot see; it says what it removed. */
    @Test fun theRenameFieldIsPrefilledWithoutHiddenCharactersAndSaysSo() {
        val s = state(select = null)
        s.openNamePrompt(NamePromptMode.Rename, FilesFixtures.file("invoice${RLO}fdp.exe\u200B", 1))
        assertEquals("invoicefdp.exe", s.namePrompt!!.value)
        assertTrue(s.namePrompt!!.hiddenRemoved)
        show(s) { s.namePrompt?.let { NamePromptContent(it, error = "", submitting = false, onValueChange = s::updateNamePrompt, onSubmit = {}, onCancel = {}) } }
        rule.onNodeWithTag(FileBrowserTags.HiddenRemoved).assertIsDisplayed()
        val field = rule.onNode(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("invoicefdp.exe", field)
        // The title still names the file as it is.
        assertTrue(spoken().contains("Rename invoice${tok(0x202E)}fdp.exe${tok(0x200B)}"))
        // Typing is cut at the web's 200, never inside a surrogate pair.
        s.updateNamePrompt("a".repeat(199) + "\uD83D\uDE00")
        assertEquals("a".repeat(199), s.namePrompt!!.value)
    }

    @Test fun aCleanNameIsPrefilledAsItIsWithNoNote() {
        val s = state(select = null)
        s.openNamePrompt(NamePromptMode.Rename, FilesFixtures.readme)
        assertEquals("README.md", s.namePrompt!!.value)
        assertFalse(s.namePrompt!!.hiddenRemoved)
        show(s) { s.namePrompt?.let { NamePromptContent(it, error = "", submitting = false, onValueChange = {}, onSubmit = {}, onCancel = {}) } }
        rule.onNodeWithTag(FileBrowserTags.HiddenRemoved).assertDoesNotExist()
    }

    /** r2: the "Saved" notice names the file by the one-line code rule. */
    @Test fun theSavedNoticeNamesTheFileAsCode() {
        val platform = FakePlatform()
        val files = FakeFiles().apply { listings[ROOT] = FilesResult.Ok(FilesFixtures.listing()) }
        val s = FileBrowserState(files, platform, CoroutineScope(Dispatchers.Unconfined)).apply { cwd = ROOT; open() }
        s.saveTo(FilesFixtures.file("invoice${RLO}fdp.exe\n.sh", 1), android.net.Uri.parse("content://test/doc"))
        assertEquals(BrowserNotice("Saved \u201C", "invoice${RLO}fdp.exe\n.sh", "\u201D."), s.notice)
        show(s)
        assertTrue(spoken().toString(), spoken().contains("Saved \u201Cinvoice${tok(0x202E)}fdp.exe${tok(0x0A)}.sh\u201D."))
        assertNoRawBidi()
    }
}
