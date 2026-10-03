package com.tether.app.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.reduce.tree
import com.tether.app.ui.text.COPY_NOTICE_TAG
import com.tether.app.ui.text.COPY_RAW_TAG
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-blf: the transcript never draws a bidi override raw, code shows every invisible and bidi
 * code point, isolates and marks reorder nothing outside real RTL text, real RTL text keeps its
 * order, nothing leaks past its line, TalkBack reads tokens as words, and a copy never carries a
 * hidden control the reader did not see ("Copy raw" gives the exact text). Every control here is
 * an escape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", shadows = [NoMagnifier::class])
class TranscriptBidiBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val LRE = "\u202A"
        const val RLE = "\u202B"
        const val PDF = "\u202C"
        const val LRO = "\u202D"
        const val RLO = "\u202E"
        const val LRI = "\u2066"
        const val RLI = "\u2067"
        const val PDI = "\u2069"
        const val RLM = "\u200F"
        const val WJ = "\u2060"
        const val ZWSP = "\u200B"
        const val ARABIC = "\u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645" // "hello world"
        const val HEBREW = "\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD" // "hello world"
        const val SPOOF = "Fix the ${RLO}parser$PDF bug now"
        const val FENCE = "const admin = \"$RLO$LRI x\"; // zwsp$ZWSP"
        val OVERRIDES = listOf(LRE, RLE, PDF, LRO, RLO)
        val ALL_BIDI = OVERRIDES + listOf(LRI, RLI, "\u2068", PDI, "\u200E", RLM, "\u061C")
        fun tok(cp: Int) = "\u2060⟨U+%04X⟩".format(cp)
        fun vis(cp: Int) = "⟨U+%04X⟩".format(cp)
        const val NOTICE_2 = "2 hidden control characters copied as ⟨U+…⟩"
    }

    private fun show(
        fixture: ChatFixtures.Folded,
        menu: MenuSpy? = null,
        richCodex: Boolean = false,
        wellHeight: Dp = WellHeightPhone,
        extra: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    ) {
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider provides (menu ?: androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider.current),
            ) {
                ChatHost(TetherSkin.StudioDark, wellHeight = wellHeight) {
                    Column {
                        extra?.invoke()
                        ChatTranscript(
                            projection = fixture.projection,
                            tree = fixture.tree,
                            showThinking = true,
                            onFetchTurns = { _, _ -> },
                            zone = ChatFixtures.zone,
                            listState = LazyListState(),
                            showTimeline = false,
                            richCodex = richCodex,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun fixture(prompt: String, reply: String, thinking: String? = null) =
        ChatFixtures.fold(*ChatFixtures.turn("t1", prompt, reply, ChatFixtures.T_IDLE, thinking))

    /** Every text, description and action label TalkBack could read, unmerged. */
    private fun spoken(): List<String> = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { n ->
        n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } +
            n.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty() +
            listOfNotNull(n.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label)
    }

    private fun assertNoRawOverride() {
        for (s in spoken()) for (c in OVERRIDES) assertFalse("raw U+%04X in \"$s\"".format(c[0].code), s.contains(c))
    }

    private fun layoutOf(text: String, exact: Boolean = false): Pair<String, TextLayoutResult> {
        val node = rule.onNodeWithText(text, substring = !exact, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val r = results.first()
        return r.layoutInput.text.text to r
    }

    private fun clip(): String? =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    private fun putClip(text: String) {
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("t", text))
    }

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

    private fun selectAllAndCopy(menu: MenuSpy, text: String) {
        rule.onNodeWithText(text, substring = true, useUnmergedTree = true).performTouchInput { longClick(center) }
        // ta-9dpl: wait for the menu itself (as NoCopyProbe.longPressForMenu does): an idle frame after
        // the long press is not always enough on a loaded machine, and the press then finds no menu.
        rule.waitUntil(20_000) { menu.shown != null }
        menu.press(androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys.SelectAllKey)
        rule.waitForIdle()
        menu.press(androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys.CopyKey)
        rule.waitForIdle()
    }

    /** The drawn lowercase ASCII letters and digits (never a token's own), in logical order: are they left to right? */
    private fun visualOrderIsLogical(shown: String, layout: TextLayoutResult): Boolean {
        val content = ArrayList<Int>()
        var i = 0
        while (i < shown.length) {
            val u = if (shown[i] == SafeText.MARK) SafeText.unitAt(shown, i) else null
            if (u != null) {
                i = u.end
                continue
            }
            if (shown[i] in 'a'..'z' || shown[i] in '0'..'9') content.add(i)
            i++
        }
        // Reading order: line by line (a long token line wraps), left to right on each.
        val at = content.map { layout.getLineForOffset(it) to layout.getBoundingBox(it).left }
        return at.zipWithNext().all { (a, b) -> a.first < b.first || (a.first == b.first && a.second < b.second) }
    }

    // ---- prose -------------------------------------------------------------------------------

    @Test fun anOverrideInTheOperatorsWordsIsAVisibleTokenAndTheWordsReadInOrder() {
        show(fixture(SPOOF, "Done."))
        val (shown, layout) = layoutOf("parser")
        assertEquals("Fix the ${tok(0x202E)}parser${tok(0x202C)} bug now", shown)
        val p = shown.indexOf("parser")
        assertTrue(layout.getBoundingBox(p).left < layout.getBoundingBox(p + 5).left)
        assertNoRawOverride()
    }

    @Test fun everyOverrideInEveryMarkdownSurfaceIsAToken() {
        val reply = listOf(
            "### Heading ${LRE}one$PDF", "",
            "Rename ${RLO}gnp.exe$PDF, see [the ${RLO}label$PDF](https://example.test/x).", "",
            "- item ${LRO}two$PDF", "",
            "| col |", "| --- |", "| cell ${RLE}three$PDF |", "",
            "> quote ${RLO}four$PDF",
        ).joinToString("\n")
        show(fixture("Show me.", reply, thinking = "think ${RLO}deep$PDF"))
        rule.onNodeWithContentDescription("Thinking").performClick()
        rule.waitForIdle()
        for (expected in listOf(
            "Heading ${tok(0x202A)}one${tok(0x202C)}", "Rename ${tok(0x202E)}gnp.exe${tok(0x202C)}", "the ${tok(0x202E)}label${tok(0x202C)}",
            "item ${tok(0x202D)}two${tok(0x202C)}", "cell ${tok(0x202B)}three${tok(0x202C)}", "quote ${tok(0x202E)}four${tok(0x202C)}",
            "think ${tok(0x202E)}deep${tok(0x202C)}",
        )) rule.onNodeWithText(expected, substring = true, useUnmergedTree = true).assertExists()
        assertNoRawOverride()
    }

    @Test fun aStreamingReplyIsProseToo() {
        val f = ChatFixtures.fold(
            ev("turn_started", "t1", ts = ChatFixtures.T_STREAM) { put("idempotencyKey", "k-t1") },
            ev("user_message_accepted", "t1", ts = ChatFixtures.T_STREAM) { put("text", "Go.") },
            ev("message_started", "t1", ts = ChatFixtures.T_STREAM) { put("blockId", "t1:m0") },
            ev("message_delta", "t1", ts = ChatFixtures.T_STREAM) { put("blockId", "t1:m0"); put("text", "typing ${RLO}olleh") },
        )
        show(f)
        rule.onNodeWithText("typing ${tok(0x202E)}olleh", substring = true, useUnmergedTree = true).assertExists()
        assertNoRawOverride()
    }

    /**
     * r2: every security-review and verifier PoC is drawn, by the real layout, with its letters and
     * digits in logical order (they read what they are), on a Latin line and on a line with Hebrew.
     */
    @Test fun isolateAndMarkPocsNeverReorderTheDrawnText() {
        val pieces = "resrap".map { "$LRI$it$PDI" }.joinToString("")
        val pocs = listOf(
            "one fix the $RLI$pieces$PDI bug",
            "two $RLI" + "resrap".toList().joinToString(RLM) + PDI,
            "${RLM}t${RLM}h${RLM}r${RLM}e${RLM}e",
            "four rm -rf $RLI/ tmp$PDI now",
            "five ${RLI}1 - 2$PDI",
            "$HEBREW six fix the $RLI$pieces$PDI bug",
            "$HEBREW seven $RLI" + "resrap".toList().joinToString(RLM) + PDI,
            "$HEBREW eight rm -rf $RLI/ tmp$PDI now",
        )
        show(fixture("Go.", pocs.joinToString("\n\n")))
        val keys = listOf("one", "two", "${tok(0x200F)}t", "four", "five", "six", "seven", "eight")
        for ((n, poc) in pocs.withIndex()) {
            val (shown, layout) = layoutOf(keys[n])
            assertTrue("PoC $n reordered: ${hex(shown)}", visualOrderIsLogical(shown, layout))
            assertEquals(poc, SafeText.original(shown))
        }
    }

    @Test fun realArabicAndHebrewKeepTheirLettersAndReadRightToLeft() {
        // r4: the prompt's LRI...PDI around a file name is a token (the accepted cost); the name still reads LTR.
        val prompt = "$HEBREW \u05D1$LRI" + "npm$PDI $HEBREW"
        show(fixture(prompt, "$ARABIC\n\n$HEBREW"))
        for (text in listOf(ARABIC, HEBREW)) {
            val (shown, layout) = layoutOf(text, exact = true)
            assertEquals(text, shown)
            assertEquals(ResolvedTextDirection.Rtl, layout.getBidiRunDirection(0))
            assertEquals(ResolvedTextDirection.Rtl, layout.getParagraphDirection(0))
            assertTrue("first ${layout.getBoundingBox(0)} last ${layout.getBoundingBox(text.length - 1)}", layout.getBoundingBox(0).left > layout.getBoundingBox(text.length - 1).left)
        }
        val (shown, layout) = layoutOf("npm")
        assertEquals("$HEBREW \u05D1${tok(0x2066)}npm${tok(0x2069)} $HEBREW", shown)
        val n = shown.indexOf("npm")
        for (k in n until n + 3) assertEquals(ResolvedTextDirection.Ltr, layout.getBidiRunDirection(k))
    }

    /**
     * r4: prose lays out in its CONTENT's direction in an LTR and in an RTL UI alike: a Latin-first
     * line is an LTR paragraph and a Hebrew-first line an RTL one, whatever the UI's direction, in a
     * plain bubble and in markdown. (A Compose Text with no direction would take the UI's.)
     */
    @Test fun proseTakesItsContentsDirectionInBothUiDirections() {
        val ui = androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.LayoutDirection.Ltr)
        val f = fixture("Latin first, then $HEBREW", "$HEBREW first, then Latin\n\nLatin first, then $HEBREW again")
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides ui.value) {
                ChatHost(TetherSkin.StudioDark) {
                    ChatTranscript(projection = f.projection, tree = f.tree, showThinking = false, onFetchTurns = { _, _ -> }, zone = ChatFixtures.zone, listState = LazyListState(), showTimeline = false)
                }
            }
        }
        for (direction in listOf(androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.unit.LayoutDirection.Rtl)) {
            rule.runOnIdle { ui.value = direction }
            rule.waitForIdle()
            for ((text, expected) in listOf(
                "Latin first, then $HEBREW" to ResolvedTextDirection.Ltr, // the user bubble (plain prose)
                "$HEBREW first, then Latin" to ResolvedTextDirection.Rtl, // markdown
                "Latin first, then $HEBREW again" to ResolvedTextDirection.Ltr,
            )) {
                val (_, layout) = layoutOf(text, exact = true)
                assertEquals("$direction UI: \"$text\"", expected, layout.getParagraphDirection(0))
            }
        }
    }

    // ---- r3: ordering by the Unicode Bidi Algorithm (ICU) --------------------------------------

    /**
     * Does [display] read in logical order under the UBA (ICU, the same algorithm Android lays
     * out with)? Its visible ASCII characters, never a token's own, must come out left to right in
     * the order they are held. [rtl]: the paragraph defaults to RTL when it has no strong
     * character (Compose's ContentOrRtl in an RTL layout).
     */
    private fun readsInOrder(display: String, rtl: Boolean): Boolean {
        val content = HashSet<Int>()
        var i = 0
        while (i < display.length) {
            val u = if (display[i] == SafeText.MARK) SafeText.unitAt(display, i) else null
            if (u != null) {
                i = u.end
                continue
            }
            if (display[i] in '!'..'~') content.add(i) // every visible ASCII character: punctuation moves too
            i++
        }
        val bidi = android.icu.text.Bidi()
        bidi.setPara(display, if (rtl) android.icu.text.Bidi.LEVEL_DEFAULT_RTL else android.icu.text.Bidi.LEVEL_DEFAULT_LTR, null)
        // In an RTL paragraph punctuation beside RTL text (a token's own brackets included: UAX #9
        // pairs them) is placed by the neutral rules for an RTL reader; words and numbers still
        // must not trade places, so there only letters and digits are compared.
        val ltr = bidi.paraLevel.toInt() == 0
        val order = bidi.visualMap.filter { it in content && (ltr || display[it].isLetterOrDigit()) }
        return order == order.sorted()
    }

    private val pieces = "resrap".map { "$LRI$it$PDI" }.joinToString("")

    /** [s] without its RAW explicit bidi characters (embeddings, overrides, isolates, marks). */
    private fun withoutRawBidi(s: String) = s.filter { it !in '\u202A'..'\u202E' && it !in '\u2066'..'\u2069' && it != '\u200E' && it != '\u200F' && it != '\u061C' }

    /** The paragraph level (0 LTR, 1 RTL) the UBA gives [s] from its content, defaulting to [rtl]. */
    private fun levelOf(s: String, rtl: Boolean): Int {
        val bidi = android.icu.text.Bidi()
        bidi.setPara(s, if (rtl) android.icu.text.Bidi.LEVEL_DEFAULT_RTL else android.icu.text.Bidi.LEVEL_DEFAULT_LTR, null)
        return bidi.paraLevel.toInt()
    }

    /**
     * How out of order [s]'s Latin letters and digits (never a token's own) are drawn at [level]:
     * the number of pairs the UBA draws in the opposite of their logical order.
     */
    private fun disorder(s: String, level: Int): Int {
        val latin = BooleanArray(s.length)
        var i = 0
        while (i < s.length) {
            val u = if (s[i] == SafeText.MARK) SafeText.unitAt(s, i) else null
            if (u != null) {
                i = u.end
                continue
            }
            latin[i] = s[i] in 'a'..'z' || s[i] in 'A'..'Z' || s[i] in '0'..'9'
            i++
        }
        val bidi = android.icu.text.Bidi()
        bidi.setPara(s, level.toByte(), null)
        val drawn = bidi.visualMap.filter { it >= 0 && latin[it] }
        var inversions = 0
        for (x in drawn.indices) for (y in x + 1 until drawn.size) if (drawn[x] > drawn[y]) inversions++
        return inversions
    }

    /**
     * r4's ordering oracle: the raw explicit bidi characters [display] still draws never make its
     * Latin words or numbers read MORE out of order than its letters alone do (the same text with
     * them removed), at the display's own paragraph direction. A spoof always adds disorder; a kept
     * mark doing its job (an LRM keeping "900" with the Latin after it) can only remove some.
     * Implicit bidi is left alone; the paragraph direction is the content's, which a leading kept
     * mark may set.
     */
    private fun rawBidiNeverDisorders(display: String, rtl: Boolean): Boolean {
        val level = levelOf(display, rtl)
        return disorder(display, level) <= disorder(withoutRawBidi(display), level)
    }

    /**
     * r5, the verifier's stricter oracle: each text laid out at ITS OWN content direction, no pair of
     * Latin letters / digits is drawn inverted with the raw explicit bidi characters that is not
     * also inverted without them.
     */
    private fun rawBidiInvertsNoNewPair(display: String, rtl: Boolean): Boolean {
        fun invertedPairs(s: String, keepIndex: (Int) -> Int): Set<Pair<Int, Int>> {
            val latin = BooleanArray(s.length)
            var i = 0
            while (i < s.length) {
                val u = if (s[i] == SafeText.MARK) SafeText.unitAt(s, i) else null
                if (u != null) {
                    i = u.end
                    continue
                }
                latin[i] = s[i] in 'a'..'z' || s[i] in 'A'..'Z' || s[i] in '0'..'9'
                i++
            }
            val bidi = android.icu.text.Bidi()
            bidi.setPara(s, if (rtl) android.icu.text.Bidi.LEVEL_DEFAULT_RTL else android.icu.text.Bidi.LEVEL_DEFAULT_LTR, null)
            val drawn = bidi.visualMap.filter { it >= 0 && latin[it] }.map(keepIndex)
            val out = HashSet<Pair<Int, Int>>()
            for (x in drawn.indices) for (y in x + 1 until drawn.size) if (drawn[x] > drawn[y]) out.add(drawn[y] to drawn[x])
            return out
        }
        val stripped = withoutRawBidi(display)
        // Stripped index -> display index, so both sets name the same characters.
        val map = IntArray(stripped.length)
        var j = 0
        for (i in display.indices) if (i < display.length && (display[i] !in '\u202A'..'\u202E' && display[i] !in '\u2066'..'\u2069' && display[i] != '\u200E' && display[i] != '\u200F' && display[i] != '\u061C')) map[j++] = i
        return (invertedPairs(display) { it } - invertedPairs(stripped) { map[it] }).isEmpty()
    }

    /** Every PoC of the four review rounds, with and without an RTL letter on its line. */
    private val allPocs: List<String> by lazy {
        val geresh = "\u05F3"
        val tatweel = "\u0640"
        val base = listOf(
            "fix the $RLI$pieces$PDI bug",
            "$RLI" + "resrap".toList().joinToString(RLM) + PDI,
            "${RLM}t${RLM}h${RLM}r${RLM}e${RLM}e",
            "${RLM}1${RLM}2${RLM}3",
            "rm -rf $RLI/ tmp$PDI now",
            "${RLI}1 - 2$PDI",
            "range ${RLI}1 - 2$geresh$PDI ok",
            "rm -rf $RLI/ tmp$geresh$PDI",
            "$geresh" + "resrap".map { "$LRI$it$PDI" }.joinToString("\u200A"),
            "$tatweel " + "resrap".map { "$LRI$it$PDI" }.joinToString(""),
            "$RLM$LRI-rf$PDI rm",
            "$LRI-rf$PDI rm",
            // r4: the verifier's and the security review's swaps.
            "mv ${LRI}old$PDI ${LRI}new$PDI \u05E9",
            "rm ${LRI}-rf$PDI \u05E9",
            "Note: \u05D0 ${LRI}approve$PDI ${LRI}not$PDI ${LRI}do$PDI \u05D1",
            "x ${RLI}abc \u05D0 def$PDI y",
            "pay ${RLI}100 to 900 \u05E9$PDI now",
            "copy ${RLI}a.txt b.txt \u05E9$PDI now",
            "open ${RLI}https://evil.io/a \u05E9$PDI now",
            "total \u2068\u05E9 100 - 900$PDI ok",
            "\u05E9 1\u061C - 2",
            "${RLM}Hello \u05E9",
            // r5: an ALM beside a Hebrew letter.
            "\u05EA\u05D0\u05E8\u05D9\u05DA \u05E9\u061C 2024-01-02",
            "\u05D8\u05DC\u05E4\u05D5\u05DF \u05D1\u061C 555-1234",
            "\u05E2\u05DE\u05D5\u05D3\u05D9\u05DD \u05E9\u061C1-10",
            "\u05DE\u05D7\u05D9\u05E8 \u05E9\u061C 100\$12",
            "\u05DE\u05E1\u05E4\u05E8 \u05E9\u061C1#2",
            "Range: \u05D0\u061C 10-20",
            "\u05E9 50%\u061C",
        )
        base + base.filter { it.none { c -> c in '0'..'9' } }.map { "\u05D0 $it" } + listOf(
            "\u05D0 " + "resrap".map { "$LRI$it$PDI" }.joinToString("\u200A"),
            "\u05D0 $LRI-rf$PDI rm",
            "$RLM$LRI-rf$PDI rm \u05D0",
        )
    }

    @Test fun everyPocDrawsItsWordsUnchangedUnderTheBidiAlgorithmInBothDirections() {
        for (poc in allPocs) for (rtl in listOf(false, true)) {
            val shown = SafeText.prose(poc)
            for (c in listOf(LRE, RLE, PDF, LRO, RLO, LRI, RLI, "\u2068", PDI)) assertFalse("raw %04X: ${hex(poc)}".format(c[0].code), shown.contains(c))
            assertTrue("rtl=$rtl ${hex(poc)} -> ${hex(shown)}", rawBidiNeverDisorders(shown, rtl))
            assertTrue("strict rtl=$rtl ${hex(poc)} -> ${hex(shown)}", rawBidiInvertsNoNewPair(shown, rtl))
            // With no RTL letter on the line, the drawn text reads exactly in logical order.
            if (poc.none { ProsePlanProbe.rtlLetter(it.code) }) assertTrue("rtl=$rtl ${hex(shown)}", readsInOrder(shown, rtl))
        }
        // The oracle is real: raw, the PoCs change the drawing.
        for (raw in listOf("fix the $RLI$pieces$PDI bug", "\u05D0 $LRI-rf$PDI rm", "Note: \u05D0 ${LRI}approve$PDI ${LRI}not$PDI ${LRI}do$PDI \u05D1", "x ${RLI}abc \u05D0 def$PDI y", "pay ${RLI}100 to 900 \u05E9$PDI now")) {
            assertFalse(hex(raw).toString(), rawBidiNeverDisorders(raw, rtl = false))
            assertFalse("strict ${hex(raw)}", rawBidiInvertsNoNewPair(raw, rtl = false))
        }
        for (raw in listOf("\u05EA\u05D0\u05E8\u05D9\u05DA \u05E9\u061C 2024-01-02", "\u05D8\u05DC\u05E4\u05D5\u05DF \u05D1\u061C 555-1234")) for (rtl in listOf(false, true)) {
            assertFalse("strict ALM rtl=$rtl ${hex(raw)}", rawBidiInvertsNoNewPair(raw, rtl))
        }
        assertFalse(readsInOrder("rm -rf $RLI/ tmp\u05F3$PDI", rtl = false))
    }

    /** r3/r4: in an RTL layout (Arabic / Hebrew system language) prose draws every PoC unchanged by its controls. */
    @Test fun anRtlLayoutDrawsEveryPocInOrder() {
        val pocs = allPocs.filter { it.none { c -> c in '0'..'9' } }.mapIndexed { n, p -> "p$n $p" }
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
                ChatHost(TetherSkin.StudioDark, wellHeight = 9_000.dp) {
                    MarkdownBody(parseMarkdown(pocs.joinToString("\n\n")), LocalTetherTypography.current.chatBody, LocalTetherTokens.current.ink)
                }
            }
        }
        rule.waitForIdle()
        val drawn = spoken()
        for ((n, poc) in pocs.withIndex()) {
            val shown = drawn.first { it.startsWith("p$n ") }
            assertEquals(poc, SafeText.original(shown))
            assertTrue("p$n ${hex(shown)}", rawBidiNeverDisorders(shown, rtl = true))
            assertTrue("strict p$n ${hex(shown)}", rawBidiInvertsNoNewPair(shown, rtl = true))
        }
    }

    // ---- code --------------------------------------------------------------------------------

    @Test fun codeShowsEveryBidiAndInvisibleCodePoint() {
        show(fixture("Show me.", "Use `x ${RLI}y` then:\n\n```\n$FENCE\n```"))
        rule.onNodeWithText("x ${tok(0x2067)}y", substring = true, useUnmergedTree = true).assertExists()
        val fence = "const admin = \"${tok(0x202E)}$WJ$ZWSP${tok(0x2066)} x\"; // zwsp${tok(0x200B)}"
        rule.onNodeWithText(fence, useUnmergedTree = true).assertExists()
        val code = spoken().single { it.startsWith("const admin") }
        for (c in ALL_BIDI) assertFalse("raw U+%04X".format(c[0].code), code.contains(c))
        assertTrue(code.contains("U+202E"))
    }

    @Test fun theCopyKeyCopiesSafelyAndOnlyTheNoticeCopiesRaw() {
        show(fixture("Show me.", "```\n$FENCE\n```"))
        rule.onAllNodesWithContentDescription("Copy code").onFirst().performClick()
        rule.waitForIdle()
        assertEquals("const admin = \"${vis(0x202E)}${vis(0x2066)} x\"; // zwsp$ZWSP", clip())
        rule.onNodeWithTag(COPY_NOTICE_TAG).assertIsDisplayed()
        rule.onNodeWithText(NOTICE_2).assertExists()
        rule.onNodeWithTag(COPY_RAW_TAG).performClick()
        rule.waitForIdle()
        assertEquals(FENCE, clip())
    }

    /** r4: the key has no long press (a long press is a tap): the notice's "Copy raw" is the only raw copy path. */
    @Test fun aLongPressOnTheCopyKeyNeverCopiesRaw() {
        show(fixture("Show me.", "```\n$FENCE\n```"))
        val key = rule.onAllNodesWithContentDescription("Copy code").onFirst()
        assertFalse("no long-click action", key.fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
        key.performTouchInput { longClick(center) }
        rule.waitForIdle()
        assertEquals("const admin = \"${vis(0x202E)}${vis(0x2066)} x\"; // zwsp$ZWSP", clip())
        rule.onNodeWithText(NOTICE_2).assertExists()
    }

    @Test fun toolOutputIsCode() {
        var n = 0
        fun json(s: String) = com.tether.app.protocol.TetherJson.parseToJsonElement(s)
        val f = ChatFixtures.fold(
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k-t1") },
            ev("user_message_accepted", "t1", ts = 1) { put("text", "Run it.") },
            ev("tool_start", "t1", ts = 1) { put("toolId", "t1:tool${n++}"); put("name", "Bash"); put("input", json("""{"command":"ls"}""")) },
            ev("tool_end", "t1", ts = 1) { put("toolId", "t1:tool0"); put("output", JsonPrimitive("out ${RLO}txt.exe$PDF$ZWSP")); put("isError", false) },
            ev("turn_end", "t1", ts = 1) { put("outcome", "ok") },
        )
        show(f)
        rule.onNodeWithTag("tool-activity-group").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("out ${tok(0x202E)}txt.exe${tok(0x202C)}$WJ$ZWSP${tok(0x200B)}", substring = true, useUnmergedTree = true).assertExists()
        assertNoRawOverride()
    }

    /** ta-blf r2: a Codex command's output is terminal output (colour dropped, other controls shown). */
    @Test fun aCodexCommandsOutputIsDrawnAsTerminalOutput() {
        val f = ChatFixtures.fold(
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k-t1") },
            ev("user_message_accepted", "t1", ts = 1) { put("text", "Test it.") },
            ev("tool_start", "t1", ts = 1) { put("toolId", "c1"); put("name", "command_execution"); putJsonObject("input") { put("command", "npm test") } },
            ev("tool_end", "t1", ts = 1) {
                put("toolId", "c1"); put("isError", false)
                putJsonObject("output") { put("text", "\u001B[31mFAIL\u001B[0m ${RLO}exe.txt\n\u001B[2Kx\n"); put("exitCode", 1); put("status", "completed") }
            },
            ev("turn_end", "t1", ts = 1) { put("outcome", "ok") },
        )
        show(f, richCodex = true)
        rule.onAllNodesWithTag("tool-activity-group").fetchSemanticsNodes().let { if (it.isNotEmpty()) rule.onNodeWithTag("tool-activity-group").performClick() }
        rule.waitForIdle()
        rule.onNodeWithText("FAIL ${tok(0x202E)}exe.txt", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("${tok(0x1B)}[2Kx", substring = true, useUnmergedTree = true).assertExists()
    }

    // ---- copy --------------------------------------------------------------------------------

    @Test fun aRowsSelectionCopiesHiddenControlsVisiblyAndCopyRawGivesTheOriginal() {
        val menu = MenuSpy()
        show(fixture(SPOOF, "Plain reply with an RTL mark: $HEBREW$RLM."), menu)
        selectAllAndCopy(menu, "parser")
        val prompt = checkNotNull(clip())
        assertTrue("the visible form: ${hex(prompt)}", prompt.contains("Fix the ${vis(0x202E)}parser${vis(0x202C)} bug now"))
        assertFalse(prompt.contains(WJ) || prompt.contains(RLO) || prompt.contains(PDF))
        rule.onNodeWithText(NOTICE_2).assertIsDisplayed()
        rule.onNodeWithTag(COPY_RAW_TAG).performClick()
        rule.waitForIdle()
        assertTrue("Copy raw: ${hex(clip()!!)}", clip()!!.contains(SPOOF))
        rule.onNodeWithTag(COPY_NOTICE_TAG).assertDoesNotExist()
        // Real RTL text copies exactly, with no notice.
        selectAllAndCopy(menu, "Plain reply")
        assertTrue(clip()!!.contains("Plain reply with an RTL mark: $HEBREW$RLM."))
        rule.onNodeWithTag(COPY_NOTICE_TAG).assertDoesNotExist()
    }

    /** r3: every copy clears the notice first: an earlier copy's "Copy raw" can never be left behind. */
    @Test fun aStaleCopyRawIsNeverLeftBehind() {
        val menu = MenuSpy()
        show(
            ChatFixtures.fold(
                *ChatFixtures.turn("t1", SPOOF, "A clean reply.", ChatFixtures.T_IDLE),
                *ChatFixtures.turn("t2", "Code.", "```\n$FENCE\n```\n\n```\nclean code\n```", ChatFixtures.T_IDLE + 60_000),
            ),
            menu,
        )
        selectAllAndCopy(menu, "parser")
        rule.onNodeWithTag(COPY_RAW_TAG).assertExists()
        selectAllAndCopy(menu, "A clean reply")
        rule.onNodeWithTag(COPY_NOTICE_TAG).assertDoesNotExist()
        // The copy key too.
        rule.onAllNodesWithContentDescription("Copy code")[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(COPY_RAW_TAG).assertExists()
        // (The first key now says "Copied": the clean fence's key is the only "Copy code".)
        rule.onAllNodesWithContentDescription("Copy code").onFirst().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(COPY_NOTICE_TAG).assertDoesNotExist()
        assertEquals("clean code", clip())
    }

    /** r3: a bidi mark code shows as a token is copied as the token, and counted. */
    @Test fun aTokenisedMarkIsCopiedVisibly() {
        show(fixture("Show me.", "```\nx = 1${RLM}2${RLM}3\n```"))
        rule.onAllNodesWithContentDescription("Copy code").onFirst().performClick()
        rule.waitForIdle()
        assertEquals("x = 1${vis(0x200F)}2${vis(0x200F)}3", clip())
        rule.onNodeWithText(NOTICE_2).assertExists()
    }

    /** r3: find in a long fence keeps the peek, unless the active match lies past it. */
    @Test fun findKeepsTheFencePeekUnlessTheActiveMatchIsPastIt() {
        val body = (1..200).joinToString("\n") { "line $it of the fence" }
        val blocks = parseMarkdown("```\n$body\n```")
        val active = androidx.compose.runtime.mutableStateOf(0)
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                MarkdownBody(blocks, LocalTetherTypography.current.chatBody, LocalTetherTokens.current.ink, find = FindMarks(if (active.value == 0) "line 3 " else "line 150 ", 0))
            }
        }
        rule.waitForIdle()
        fun laidOut(): Int = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().maxOf { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            results.firstOrNull()?.layoutInput?.text?.length ?: 0
        }
        assertTrue("an active match inside the peek lays out the peek: ${laidOut()}", laidOut() < body.length)
        rule.runOnIdle { active.value = 1 }
        rule.waitForIdle()
        assertEquals("an active match past the peek lays out the whole fence", body.length, laidOut())
    }

    @Test fun aCopiedPathIsThePathNotItsBreakOpportunities() {
        val menu = MenuSpy()
        val path = "/w/src/app/main.kt"
        val f = ChatFixtures.fold(
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k-t1") },
            ev("user_message_accepted", "t1", ts = 1) { put("text", "Edit it.") },
            ev("tool_start", "t1", ts = 1) { put("toolId", "te"); put("name", "Edit"); putJsonObject("input") { put("file_path", path); put("old_string", "a"); put("new_string", "b") } },
            ev("permission_denied", "t1", ts = 1) { put("toolId", "te"); put("name", "Edit"); put("reason", "unknown") },
            ev("tool_end", "t1", ts = 1) { put("toolId", "te"); put("output", "denied"); put("isError", true) },
            ev("turn_end", "t1", ts = 1) { put("outcome", "ok") },
        )
        show(f, menu)
        rule.onNodeWithTag("tool-activity-group").performClick()
        rule.waitForIdle()
        selectAllAndCopy(menu, "DENIED")
        val copied = checkNotNull(clip())
        assertTrue("the path copies exactly: ${hex(copied)}", copied.contains(path))
        assertFalse(copied.contains(WJ) || copied.contains(ZWSP))
    }

    // ---- every selectable row ----------------------------------------------------------------

    /** The kinds that are never selectable (controls, notices): everything else must be in the walk. */
    private val notSelectable = setOf("LoadEarlier", "ToolGroup", "Approval", "Question", "ProviderNotice", "Compaction", "SessionNotice", "RateLimit", "BgCommand")

    /**
     * r2: every selectable row kind, fed hostile words (an override, an isolate, a forged token with
     * its mark, an ESC, no RTL letter): nothing any row draws or describes holds a raw bidi control,
     * a raw control character, or a U+2060 that does not start a real token or break opportunity.
     */
    @Test fun noSelectableRowDrawsRawControlsOrAForgedToken() {
        val h = "x${RLO}y${LRI}z$WJ⟨U+202E⟩w\u001B end"
        val f = ChatFixtures.fold(
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k-t1") },
            ev("user_message_accepted", "t1", ts = 1) { put("text", "user $h") },
            ev("thinking_delta", "t1", ts = 1) { put("blockId", "t1:th0"); put("text", "think $h") },
            ev("thinking_stop", "t1", ts = 1) { put("blockId", "t1:th0") },
            ev("message_started", "t1", ts = 1) { put("blockId", "t1:m0") },
            ev("message_completed", "t1", ts = 1) { put("blockId", "t1:m0"); put("text", "reply $h `code $h`\n\n```\n$h\n```") },
            ev("tool_start", "t1", ts = 1) { put("toolId", "tb"); put("name", "Bash$RLO"); putJsonObject("input") { put("command", h) } },
            ev("permission_denied", "t1", ts = 1) { put("toolId", "tb"); put("name", "Bash$RLO"); put("reason", "unknown"); put("error", "err $h") },
            ev("tool_end", "t1", ts = 1) { put("toolId", "tb"); put("output", "out $h"); put("isError", true) },
            ev("question_answered", "t1", ts = 1) {
                put("requestId", "q-9"); put("toolId", "ask-none")
                putJsonArray("items") { addJsonObject { put("header", "head $h"); put("question", "ask $h"); put("answer", "ans $h") } }
            },
            ev("plan_updated", "t1", ts = 1) {
                put("explanation", "plan $h")
                putJsonArray("steps") { addJsonObject { put("status", "completed"); put("step", "step $h") } }
            },
            ev("diff_updated", "t1", ts = 1) { put("unifiedDiff", "diff --git a/f$RLO b/f$RLO\n--- a/f$RLO\n+++ b/f$RLO\n@@ -1 +1 @@\n-$h\n+$h\n") },
            ev("review_started", "t1", ts = 1) { put("reviewId", "rev-1"); put("target", "target $h") },
            ev("review_completed", "t1", ts = 1) { put("reviewId", "rev-1"); put("status", "completed"); put("result", "result $h") },
            ev("error", "t1", ts = 1) { put("message", "fail $h") },
            ev("turn_end", "t1", ts = 1) { put("outcome", "error") },
            ev("turn_started", "t2", ts = 2) { put("idempotencyKey", "k-t2"); put("continuation", true) },
            ev("user_message_accepted", "t2", ts = 2) { put("text", "again $h") },
            ev("api_retry", "t2", ts = 2) { put("attempt", 2); put("maxRetries", 5); put("delayMs", 4_000); put("error", "overloaded") },
            evNullTurn("error", seq = 99, ts = 3) { put("message", "session $h") },
        )
        // Every selectable kind is in this fixture: a new kind has to join the walk (or the not-selectable list).
        val items = buildChatItems(f.projection, f.tree, showThinking = true, zone = ChatFixtures.zone, richCodex = true, groupOpen = { _, _ -> true })
        val kinds = ChatItem::class.java.declaredClasses.filter { ChatItem::class.java.isAssignableFrom(it) && it != ChatItem::class.java }.map { it.simpleName }.toSet()
        val present = items.map { it::class.java.simpleName }.toSet()
        assertEquals("selectable kinds missing from the walk", emptySet<String>(), kinds - notSelectable - present)
        for (item in items) assertEquals(item::class.java.simpleName, item::class.java.simpleName !in notSelectable, item.selectableText)

        show(f, richCodex = true)
        // Row by row (the well is a lazy list): open every closed group and thinking card, read everything.
        val collapsed = androidx.compose.ui.test.SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")
        val all = LinkedHashSet<String>()
        for (index in 0 until items.size + 8) {
            runCatching { rule.onNodeWithTag("chat-transcript").performScrollToIndex(index) }
            rule.waitForIdle()
            val toggles = androidx.compose.ui.test.hasTestTag("tool-activity-group") or androidx.compose.ui.test.hasContentDescription("Thinking")
            var guard = 0
            while (guard++ < 10 && rule.onAllNodes(toggles and collapsed).fetchSemanticsNodes().isNotEmpty()) {
                rule.onAllNodes(toggles and collapsed).onFirst().performClick()
                rule.waitForIdle()
            }
            all += spoken()
        }
        val forbidden = (ALL_BIDI.map { it[0] } + (0x00..0x1F).filter { it != 0x09 && it != 0x0A }.map { it.toChar() } + (0x7F..0x9F).map { it.toChar() }).toSet()
        for (word in listOf("user", "think", "reply", "code", "err", "head", "ask", "ans", "plan", "step", "target", "result", "again", "out")) {
            assertTrue("no row drew \"$word\": $all", all.any { it.contains("$word x", ignoreCase = true) }) // the answered header is uppercase
        }
        for (s in all) {
            for (c in s) assertFalse("raw U+%04X in ${hex(s)}".format(c.code), c in forbidden)
            var at = s.indexOf(SafeText.MARK)
            while (at >= 0) {
                val u = SafeText.unitAt(s, at)
                assertTrue("a U+2060 that starts no token in ${hex(s)}", u != null)
                at = s.indexOf(SafeText.MARK, u!!.end)
            }
            assertFalse("a forged token drew as one: ${hex(s)}", s.contains("$WJ⟨U+202E⟩w"))
        }
    }

    // ---- todo bar, find --------------------------------------------------------------------

    @Test fun theTodoBarsAgentWordsAreProse() {
        val progress = ProgressView(listOf(ProgressItem("Fix the ${RLO}parser$PDF", ProgressStatus.IN_PROGRESS)), "Fix the ${RLO}parser$PDF", 0, 1)
        rule.setContent { ChatHost(TetherSkin.StudioDark) { TodoBar(progress, "s1") } }
        rule.onNodeWithTag("todo-bar-head").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasText("Fix the ${tok(0x202E)}parser${tok(0x202C)}"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertNoRawOverride()
    }

    @Test fun aFindMatchInsideAFlagOrACrlfKeepsItWhole() {
        val flag = "\uD83C\uDFF4" + "gbsct".map { String(Character.toChars(0xE0000 + it.code)) }.joinToString("") + String(Character.toChars(0xE007F))
        var checked = false
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                val t = LocalTetherTokens.current
                val shown = markedPlain("flag $flag and more", FindMarks(String(Character.toChars(0xE0073)), 0), t)
                assertEquals("flag $flag and more", shown.text)
                val crlf = markedPlain("a\r\nb", FindMarks("\r", 0), t)
                assertEquals("a\r\nb", crlf.text)
                assertEquals(1, crlf.getStringAnnotations(FIND_TAG, 0, crlf.length).size)
                checked = true
                Text(shown)
            }
        }
        rule.waitForIdle()
        assertTrue(checked)
    }

    // ---- the background command's output sheet -----------------------------------------------

    @Test fun theOutputSheetDropsColourAndShowsEveryOtherControl() {
        var tree = reduce(freshTree(), evNullTurn("background_command_updated", ts = 1) {
            put("commandId", "c"); put("command", "make"); put("cwd", "/w"); put("logFile", "/w/c${RLO}.log"); put("status", "running"); put("startedAt", 1)
        }.tree())
        tree = reduce(tree, evNullTurn("background_command_output", ts = 2) {
            put("commandId", "c"); put("stream", "stdout"); put("text", "\u001B[31mFAIL\u001B[0m ${RLO}exe.txt\n\u001B[2Kerased\n")
        }.tree())
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) { CommandOutputSurface(runningBackgroundCommands(tree).single(), CommandActions.Unavailable, onClose = {}) }
        }
        rule.mainClock.advanceTimeBy(COMMAND_SHEET_SAMPLE_MS * 2)
        rule.waitForIdle()
        rule.onNodeWithText("FAIL ${tok(0x202E)}exe.txt", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("${tok(0x1B)}[2Kerased", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("c${tok(0x202E)}.log", substring = true, useUnmergedTree = true).assertExists()
        for (s in spoken()) {
            assertFalse("raw ESC in \"$s\"", s.contains('\u001B'))
            for (c in OVERRIDES) assertFalse(s.contains(c))
        }
    }

    // ---- r4: fuzz ---------------------------------------------------------------------------

    /**
     * r4 (the verifier's fuzzer, bounded): random mixes of commands, paths, numbers, Hebrew, Arabic,
     * punctuation, isolates and marks. Prose never draws an isolate raw, and the marks it keeps
     * never change how the Latin words and numbers are drawn ([rawBidiNeverDisorders]), in an LTR
     * and an RTL UI. (A kept mark may set its line's paragraph direction, as the UBA intends; the
     * paragraph direction test covers how the layout takes it.)
     */
    @Test fun fuzzNoKeptControlReordersTheWords() {
        val pieces = listOf(
            "rm", "-rf", "/", "tmp", "100", "to", "900", "1", "2", "a.txt", "https://x.io/a?b=1", "v1.2", "mv", "old", "new", "pay",
            "\u05E9\u05DC\u05D5\u05DD", "\u05E9", "\u05E2\u05D5\u05DC\u05DD", "\u0645\u0631\u062D\u0628\u0627", "\u0661\u0662\u0663",
            "\u05F3", "\u0640", " - ", ", ", ".", ":", "(", ")", " ", " ", "\u200A",
            "$", "#", "%", "+", "1-2", "2024-01-02", "\u05D0", "\u05D1", "\u0635\u0641\u062D\u0629", "\u061C", "\u061C",
            LRI, RLI, "\u2068", PDI, PDI, "\u200E", RLM, "\u061C",
        )
        val random = kotlin.random.Random(2026)
        var kept = 0
        repeat(20_000) {
            val src = (0 until random.nextInt(3, 10)).joinToString("") { pieces[random.nextInt(pieces.size)] }
            val shown = SafeText.prose(src)
            for (c in listOf(LRI, RLI, "\u2068", PDI)) assertFalse("an isolate drawn raw: ${hex(src)}", shown.contains(c))
            if (withoutRawBidi(shown) == shown) return@repeat
            kept++
            for (rtl in listOf(false, true)) {
                assertTrue("rtl=$rtl ${hex(src)} -> ${hex(shown)}", rawBidiNeverDisorders(shown, rtl))
                assertTrue("strict rtl=$rtl ${hex(src)} -> ${hex(shown)}", rawBidiInvertsNoNewPair(shown, rtl))
            }
        }
        assertTrue("the fuzzer reached marks kept raw ($kept)", kept > 100)
    }

    // ---- cost (r2) ---------------------------------------------------------------------------

    /** r2: composition + layout of a 200k-tag fence and a 1 MB fence stay within the render budget (a clamped fence lays out its peek). */
    @Test fun aFloodedOrHugeFenceRendersWithinBudget() {
        val payload = "hidden instruction number one two three ".repeat(5_000).take(200_000)
        val tagFence = "```\n" + payload.map { String(Character.toChars(0xE0000 + it.code)) }.joinToString("") + "\n```"
        val bigFence = "```\n" + "val x = compute(a, b) // a line of ordinary code\n".repeat(21_000) + "```"
        val which = androidx.compose.runtime.mutableStateOf(0)
        val fences = listOf(emptyList(), parseMarkdown(tagFence), parseMarkdown(bigFence))
        rule.setContent { ChatHost(TetherSkin.StudioDark) { MarkdownBody(fences[which.value], LocalTetherTypography.current.chatBody, LocalTetherTokens.current.ink) } }
        rule.waitForIdle()
        for (n in 1..2) {
            val started = System.nanoTime()
            rule.runOnIdle { which.value = n }
            rule.waitForIdle()
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("fence $n took $ms ms", ms < 5_000)
            // While clamped only the peek is laid out: what the layout holds stays bounded, whatever the fence.
            val laidOut = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().maxOf { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
                results.firstOrNull()?.layoutInput?.text?.length ?: 0
            }
            assertTrue("fence $n laid out $laidOut characters", laidOut <= 2 * com.tether.app.ui.components.ExpandPeekChars)
            assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Show", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
    }
}

/** The rule's own RTL-letter class (ProsePlan's), for the tests. */
private object ProsePlanProbe {
    fun rtlLetter(cp: Int): Boolean {
        val t = Character.getType(cp)
        val letter = t == Character.UPPERCASE_LETTER.toInt() || t == Character.LOWERCASE_LETTER.toInt() || t == Character.TITLECASE_LETTER.toInt() || t == Character.OTHER_LETTER.toInt()
        val d = Character.getDirectionality(cp)
        return letter && (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC)
    }
}
