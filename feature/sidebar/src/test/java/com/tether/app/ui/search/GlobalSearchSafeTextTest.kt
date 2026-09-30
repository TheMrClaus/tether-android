package com.tether.app.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.SearchHit
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.sidebar.RecordingClient
import com.tether.app.ui.sidebar.SidebarFixtures
import com.tether.app.ui.sidebar.choiceFor
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-28i: a global-search hit draws its title and snippet by the LABEL rule and its path as CODE
 * (every control a token, LTR); TalkBack reads what is drawn; a legitimate Hebrew title stays whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class GlobalSearchSafeTextTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    private companion object {
        const val RLO = "\u202E"
        const val PDF = "\u202C"
        const val LRI = "\u2066"
        const val PDI = "\u2069"
        const val HEBREW = "\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD"
        val BIDI = ('\u202A'..'\u202E') + ('\u2066'..'\u2069') + listOf('\u200E', '\u200F', '\u061C')
        fun tok(cp: Int) = "\u2060\u27E8U+%04X\u27E9".format(cp)
    }

    private fun spoken(): List<String> = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { n ->
        n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } +
            n.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty()
    }

    private fun layoutOf(text: String): Pair<String, TextLayoutResult> {
        val node = rule.onNodeWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first().let { it.layoutInput.text.text to it }
    }

    private fun hit(id: String, name: String, cwd: String, snippet: String) =
        SearchHit(historyId = id, provider = "codex", name = name, cwd = cwd, updatedAt = System.currentTimeMillis() - 60_000, snippet = snippet, matchCount = 1, profileId = null)

    @Test fun aHitsTitleAndSnippetAreLabelsAndItsPathIsCode() {
        val client = RecordingClient(sessions = emptyList())
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            val list by client.sessions.collectAsStateWithLifecycle()
            TetherTheme(choiceFor(TetherSkin.Machine)) {
                androidx.compose.runtime.CompositionLocalProvider(com.tether.app.ui.theme.LocalReducedMotion provides true) {
                    GlobalSearchHost(vm = vm, prefs = prefs, sessions = list, workspaceRoot = F.ROOT, onCloseDrawer = {})
                }
            }
        }
        rule.waitForIdle()
        vm.openGlobalSearch()
        rule.waitForIdle()
        rule.onNodeWithTag(GlobalSearchTags.Input).performTextInput("parity")
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        client.globalReply(
            client.globalSearchResults.value.requestId,
            "parity",
            listOf(
                hit("h-1", "Run the ${RLO}ytirap$PDF suite", "${F.ROOT}/sr${LRI}c$PDI", "the parity\n\nnotes$RLO here"),
                hit("h-2", HEBREW, F.APP, "\u200Bparity"),
            ),
        )
        rule.waitForIdle()
        val shown = spoken()
        assertTrue(shown.contains("Run the ytirap suite"))
        assertTrue(shown.contains("the parity notes here"))
        val path = "sr${tok(0x2066)}c${tok(0x2069)}"
        assertTrue("path in $shown", shown.any { it.contains(path) })
        assertEquals(ResolvedTextDirection.Ltr, layoutOf("sr").second.getParagraphDirection(0))
        // TalkBack's sentence for the hit reads the drawn forms.
        assertTrue(shown.any { it.startsWith("Run the ytirap suite, ") && it.contains(path) && it.endsWith("the parity notes here") })
        for (s in shown) for (c in BIDI) assertFalse("raw U+%04X in \"$s\"".format(c.code), s.contains(c))
        val (text, layout) = layoutOf(HEBREW)
        assertEquals(HEBREW, text)
        assertEquals(ResolvedTextDirection.Rtl, layout.getParagraphDirection(0))
    }
}
