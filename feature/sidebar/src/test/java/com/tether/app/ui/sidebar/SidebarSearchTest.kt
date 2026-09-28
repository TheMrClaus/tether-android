package com.tether.app.ui.sidebar

import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.SearchResults
import com.tether.app.protocol.SearchHit
import com.tether.app.ui.SessionDrawer
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.SidebarSort
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T5.3: the sidebar filter's workspace content search (dashboard.tsx:835-893, use-tether.ts
 * 1390-1399, session-sidebar.tsx:384-389) — the merge rules and the debounced frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SidebarSearchTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    private fun rows(): List<SidebarEntry> = SidebarModel.sidebarSessions(
        visible = F.statusSessions,
        historiesByCwd = F.statusHistories,
        workspaces = listOf(F.ROOT),
        lastSeen = emptyMap(),
        sessionOrders = emptyMap(),
        sort = SidebarSort.Created,
        activeId = null,
        openingHistoryId = null,
        collator = F.collator,
    )

    private fun hit(id: String, name: String, provider: String = "claude", cwd: String = F.APP, ago: Long = 90, snippet: String = "…a match…", matchCount: Int = 2) =
        SearchHit(historyId = id, provider = provider, name = name, cwd = cwd, updatedAt = F.NOW - ago * 60_000, createdAt = F.NOW - ago * 60_000, snippet = snippet, matchCount = matchCount)

    private fun filter(query: String, hits: SearchResults, harness: String? = null) =
        SidebarModel.filteredSessions(rows(), query, harness, hits, listOf(F.ROOT, F.DOCS), F.ROOT, SidebarSort.Created, F.collator)

    @Test fun aListedRowThatMatchesByContentKeepsItsPlaceAndGainsTheSnippet() {
        val results = SearchResults("backoff", listOf(hit("h-away", "Finished while you were away", snippet = "the backoff is 250ms", matchCount = 3)))
        val out = filter("backoff", results)
        assertEquals(listOf("history:h-away"), out.map { it.key })
        assertEquals("the backoff is 250ms", out.single().snippet)
        assertEquals(3, out.single().matchCount)
        // Still the live row it was (badge and state kept).
        assertEquals("a3", out.single().live?.id)
    }

    @Test fun hitsOutsideTheListBecomeHistoryRowsUnderTheBlockThatOwnsThem() {
        val results = SearchResults(
            "retry",
            listOf(
                hit("h-new", "Beyond the discovery cap", cwd = "${F.DOCS}/guide", ago = 30),
                hit("h-older", "Older beyond the cap", ago = 300),
            ),
        )
        val out = filter("retry", results)
        // "Refactor the retry loop" matches by title first, then the extras newest-first.
        assertEquals(listOf("live:a1", "history:h-new", "history:h-older"), out.map { it.key })
        assertEquals(F.DOCS, out[1].workspace)
        assertEquals(F.ROOT, out[2].workspace)
        assertNull(out[1].live)
        assertEquals("Beyond the discovery cap", out[1].history?.name)
    }

    @Test fun hitsCountOnlyForTheQueryTypedNow() {
        // A reply for "retr" while "retry" is typed: not trusted (it would flash stale rows).
        val stale = SearchResults("retr", listOf(hit("h-new", "Beyond the discovery cap")))
        assertEquals(listOf("live:a1"), filter("retry", stale).map { it.key })
        // Case and surrounding space do not matter (the web compares trim().toLowerCase()).
        val same = SearchResults(" RETRY", listOf(hit("h-new", "Beyond the discovery cap")))
        assertEquals(listOf("live:a1", "history:h-new"), filter("retry ", same).map { it.key })
    }

    @Test fun theHarnessFilterStillGatesTheExtras() {
        val results = SearchResults("thread", listOf(hit("h-x", "Some thread", provider = "codex"), hit("h-y", "Other thread")))
        assertEquals(listOf("history:h-old", "history:h-x"), filter("thread", results, harness = "codex").map { it.key })
    }

    @Test fun anEmptyQueryIgnoresTheHits() {
        val results = SearchResults("", listOf(hit("h-new", "x")))
        assertEquals(rows().map { it.key }, filter("  ", results).map { it.key })
    }

    // ---- the drawer: debounce, frame, merged row ------------------------------------------------

    private fun row(prefix: String) = rule.onNode(
        SemanticsMatcher("row $prefix") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true },
    )

    @Test fun theDrawerSearchesTheTypedFilter250msAfterTheLastKeystroke() {
        val client = RecordingClient(sessions = F.drawerSessions)
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            val sessions by client.sessions.collectAsStateWithLifecycle()
            TetherTheme(choiceFor(TetherSkin.Machine)) {
                SessionDrawer(vm = vm, prefs = prefs, sessions = sessions, selectedId = null, workspaceRoot = F.ROOT, onSelect = {}, onClose = {})
            }
        }
        rule.waitForIdle()
        fun searches(): List<JsonObject> = client.frames.filter { (it["type"] as JsonPrimitive).content == "search" }
        val filter = rule.onNode(SemanticsMatcher("filter") { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Filter sessions") == true })
        rule.mainClock.autoAdvance = false
        filter.performTextInput("rea")
        rule.mainClock.advanceTimeBy(100)
        filter.performTextReplacement("readme")
        rule.mainClock.advanceTimeBy(230)
        assertEquals("nothing within the debounce", emptyList<JsonObject>(), searches())
        rule.mainClock.advanceTimeBy(40)
        assertEquals(listOf(frame("""{"type":"search","cwd":"${F.ROOT}","query":"readme"}""")), searches())
        rule.mainClock.autoAdvance = true

        // The reply merges in: the listed title match stays, a content hit outside the list joins
        // with its snippet line (and says so to TalkBack).
        client.searchResults.value = SearchResults("readme", listOf(hit("h-far", "Docs pass from last month", snippet = "…updated the README table…", matchCount = 4)))
        rule.waitForIdle()
        row("Summarize the README in one line.").fetchSemanticsNode()
        val described = row("Docs pass from last month").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(described, described.endsWith(", matched: …updated the README table…, 4 matches"))

        // Under two characters: cleared locally, no frame.
        client.frames.clear()
        filter.performTextReplacement("r")
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        assertEquals(emptyList<JsonObject>(), searches())
        assertEquals(SearchResults(), client.searchResults.value)
    }
}
