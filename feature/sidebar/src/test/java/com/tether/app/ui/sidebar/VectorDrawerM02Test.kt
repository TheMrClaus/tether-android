package com.tether.app.ui.sidebar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.TetherTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val DAY_MIN_M02 = 24L * 60

/**
 * Vector m02 (P8C): contrast of what the drawer REALLY draws (chip text, meta text, footer text, scope pill >= 4.5:1; the current
 * card's outline >= 3:1 against the drawer's own face), Light and Dark. Each number is measured on the pixels the widget drew: the
 * pixel farthest in luminance from the face it sits on (Light ink is lighter than its face in places, so never "darkest").
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class VectorDrawerM02ContrastTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    private fun lum(c: Color): Double {
        fun ch(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }
    private fun ratio(a: Color, b: Color): Double = lum(a).let { x -> lum(b).let { y -> (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) } }

    private val dp: Float get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density

    /** Box of [tag] in the root bitmap, in px. */
    private fun boundsOf(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** The farthest-from-[face] pixel's contrast inside the px box, and how many pixels are not the face. */
    private fun measure(m: androidx.compose.ui.graphics.PixelMap, l: Int, t: Int, r: Int, b: Int, face: Color): Pair<Double, Int> {
        var best = 1.0
        var glyph = 0
        for (y in t until b) for (x in l until r) {
            val c = m[x, y]
            if (c.toArgb() != face.toArgb()) glyph++
            best = maxOf(best, ratio(c, face))
        }
        return best to glyph
    }

    private fun mostFrequent(m: androidx.compose.ui.graphics.PixelMap, l: Int, t: Int, r: Int, b: Int): Color {
        val counts = HashMap<Int, Int>()
        for (y in t until b) for (x in l until r) counts.merge(m[x, y].toArgb(), 1, Int::plus)
        return Color(counts.maxByOrNull { it.value }!!.key)
    }

    /** The inner part of [tag]'s box (the centre [fx] x [fy] share), face = its most frequent pixel unless [face] is given. */
    private fun inner(tag: String, fx: Float, fy: Float, face: Color? = null): Triple<String, Double, Int> {
        val b = boundsOf(tag)
        val m = rule.onRoot().captureToImage().toPixelMap()
        val cx = (b.left + b.right) / 2; val cy = (b.top + b.bottom) / 2
        val hw = b.width * fx / 2; val hh = b.height * fy / 2
        val l = (cx - hw).toInt(); val r = (cx + hw).toInt(); val t = (cy - hh).toInt(); val bt = (cy + hh).toInt()
        val f = face ?: mostFrequent(m, l, t, r, bt)
        val (best, n) = measure(m, l, t, r, bt, f)
        return Triple(tag, best, n)
    }

    private fun check(skin: TetherSkin) {
        var tk: TetherTokens? = null
        rule.setContent {
            TetherTheme(choiceFor(skin)) { tk = LocalTetherTokens.current }
            SidebarUnderTest(
                skin,
                F.state(listOf(F.live("c1", "Current one", ago = 0), F.live("c2", "Other one", ago = 5)), activeId = "c1", activeOnly = false),
            )
        }
        rule.waitForIdle()
        val t = tk!!
        fun report(name: String, v: Double) = println("P8C-M02 $name $skin ${"%.2f".format(v)}:1")

        // chips: an off chip (Unread) and a latched one is checked in the second state below; here "All" is latched.
        val allOn = inner(PhoneChromeTags.chip("all"), 0.55f, 0.4f)
        report("chip-on-text", allOn.second)
        assertTrue("latched chip text vs its face ${allOn.second} ($skin)", allOn.second >= 4.5 && allOn.third > 0)
        val off = inner(PhoneChromeTags.chip("unread"), 0.55f, 0.4f)
        report("chip-off-text", off.second)
        assertTrue("chip text vs its face ${off.second} ($skin)", off.second >= 4.5 && off.third > 0)

        // the scope pill: accent words on the accent wash
        val pill = inner(PhoneChromeTags.SearchScope, 0.6f, 0.4f)
        report("scope-pill-text", pill.second)
        assertTrue("scope pill text vs its face ${pill.second} ($skin)", pill.second >= 4.5 && pill.third > 0)

        // meta text: the idle row's "Ready · 5m" line (below the title, from the title column), on the card's face
        val rowB = boundsOf(SidebarTags.row("live:c2"))
        val m = rule.onRoot().captureToImage().toPixelMap()
        val l = (rowB.left + 70 * dp).toInt(); val r = (rowB.left + 140 * dp).toInt()
        val tp = (rowB.top + 33 * dp).toInt(); val bt = (rowB.top + 47 * dp).toInt()
        val (meta, metaN) = measure(m, l, tp, r, bt, t.css.bgPanel)
        report("meta-text", meta)
        assertTrue("meta text vs the card face $meta ($skin)", meta >= 4.5 && metaN > 0)

        // footer link line, on the footer band's own (panel) face
        val fb = boundsOf(PhoneChromeTags.FooterStatus)
        val fm = rule.onRoot().captureToImage().toPixelMap()
        val faceF = mostFrequent(fm, (fb.left + 24 * dp).toInt(), fb.top.toInt() + 4, (fb.left + 150 * dp).toInt(), fb.bottom.toInt() - 4)
        val (foot, footN) = measure(fm, (fb.left + 24 * dp).toInt(), fb.top.toInt() + 4, (fb.left + 150 * dp).toInt(), fb.bottom.toInt() - 4, faceF)
        report("footer-text", foot)
        assertEquals("the footer sits on the panel face ($skin)", t.css.bgPanel.toArgb(), faceF.toArgb())
        assertTrue("footer text vs the drawer face $foot ($skin)", foot >= 4.5 && footN > 0)

        // the current card's outline vs the drawer's face (non-text contrast >= 3:1): the border pixel on the card's top edge, the drawer face in the gutter beside it
        val card = boundsOf(SidebarTags.CurrentCard)
        val cm = rule.onRoot().captureToImage().toPixelMap()
        val edge = cm[((card.left + card.right) / 2).toInt(), card.top.toInt() + 1]
        val drawer = cm[(card.left - 3 * dp).toInt(), ((card.top + card.bottom) / 2).toInt()]
        val outline = ratio(edge, drawer)
        report("current-outline", outline)
        assertEquals("the outline is the accent ($skin)", t.violet.toArgb(), edge.toArgb())
        assertTrue("current outline vs the drawer face $outline ($skin)", outline >= 3.0)
    }

    /** B1 pixel control: between two workspace blocks the drawer really paints the `--line` hairline across the card x-span. */
    private fun hairline(skin: TetherSkin) {
        var tk: TetherTokens? = null
        rule.setContent {
            TetherTheme(choiceFor(skin)) { tk = LocalTetherTokens.current }
            SidebarUnderTest(skin, F.state(F.groupSessions + F.live("d3", "Draft the changelog", cwd = F.DOCS, ago = 20), pinned = listOf(F.DOCS, F.ROOT), current = F.APP))
        }
        rule.waitForIdle()
        val line = tk!!.line
        val b = rule.onAllNodesWithTag(PhoneChromeTags.BlockRule)[0].fetchSemanticsNode().boundsInRoot
        val m = rule.onRoot().captureToImage().toPixelMap()
        val y = ((b.top + b.bottom) / 2).toInt()
        val x0 = (b.left + 8 * dp).toInt(); val x1 = (b.right - 8 * dp).toInt()
        var off = 0
        for (x in x0 until x1 step 3) if (m[x, y].toArgb() != line.toArgb()) off++
        assertEquals("the hairline pixel row is t.line across the card x-span ($skin)", 0, off)
    }

    @Test fun lightHairlinePaintsLine() = hairline(TetherSkin.Studio)
    @Test fun darkHairlinePaintsLine() = hairline(TetherSkin.StudioDark)

    /** B2: the footer band face reaches the drawer's left and right edges and the bottom (no host insets under test, so the design minimums). */
    private fun band(skin: TetherSkin) {
        var tk: TetherTokens? = null
        rule.setContent {
            TetherTheme(choiceFor(skin)) { tk = LocalTetherTokens.current }
            SidebarUnderTest(skin, F.state(listOf(F.live("c1", "Current one", ago = 0)), activeId = "c1", activeOnly = false))
        }
        rule.waitForIdle()
        val face = tk!!.css.bgPanel.toArgb()
        val fb = boundsOf(PhoneChromeTags.FooterStatus)
        val m = rule.onRoot().captureToImage().toPixelMap()
        val y = fb.top.toInt() + 6
        assertEquals("band at the left drawer edge ($skin)", face, m[1, y].toArgb())
        assertEquals("band at the right drawer edge ($skin)", face, m[m.width - 2, y].toArgb())
        assertEquals("band at the bottom edge ($skin)", face, m[m.width / 2, m.height - 2].toArgb())
    }

    @Test fun lightBandReachesEdges() = band(TetherSkin.Studio)
    @Test fun darkBandReachesEdges() = band(TetherSkin.StudioDark)

    @Test fun lightKeepsContrast() = check(TetherSkin.Studio)
    @Test fun darkKeepsContrast() = check(TetherSkin.StudioDark)
}

/** Vector m02 behaviour: the merged search bar, the merged Older / runs row, the footer link line, the header keys. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class VectorDrawerM02BehaviourTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures
    private val events = mutableListOf<String>()

    private val actions = SidebarActions(
        onNewSession = { events += "new" },
        onOpenScheduledActions = { events += "scheduled" },
        onOpenGlobalSearch = { events += "global" },
        onToggleHideAgentRuns = { events += "runs" },
        onToggleActiveOnly = { events += "toggle-active" },
        onSessionQueryChange = { events += "query:$it" },
        onOpenSettings = { events += "settings" },
    )

    private val sessions = listOf(
        F.live("fresh", "Fresh chat", ago = 5),
        F.live("ten", "Ten days chat", ago = 10 * DAY_MIN_M02),
    )

    private fun show(state: SidebarState, seed: SidebarUiSeed = SidebarUiSeed(), act: SidebarActions = actions) {
        rule.setContent { SidebarUnderTest(TetherSkin.Studio, state, seed = seed, actions = act) }
        rule.waitForIdle()
    }

    @Test fun theHeaderKeysAreLabelledAndCallTheirActionsWithTheScheduledCount() {
        show(F.state(sessions).copy(scheduledActionCount = 3))
        rule.onNodeWithText("New session", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Scheduled", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Scheduled actions, 3").assertExists()
        rule.onNodeWithTag(SidebarTags.NewSession).performClick()
        rule.onNodeWithTag(SidebarTags.Scheduled).performClick()
        assertEquals(listOf("new", "scheduled"), events)
    }

    @Test fun theMergedBarOpensTheGlobalSearchInAllScopeAndFiltersInFilterScope() {
        show(F.state(sessions))
        rule.onNodeWithText("All · 1 ws", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Search all conversations").performClick()
        assertEquals(listOf("global"), events)
        rule.switchToFilterScope()
        rule.onNodeWithText("Filter", useUnmergedTree = true).assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextInput("ab")
        assertEquals(listOf("global", "query:ab"), events)
        rule.onNodeWithTag(PhoneChromeTags.SearchScope).performClick() // back to All
        rule.onNodeWithText("All · 1 ws", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Search all conversations").performClick()
        assertEquals("the typed filter reached the list; the All scope opens the global search again", listOf("global", "query:ab", "global"), events.filter { it != "query:" })
    }

    @Test fun theChipsCarryNoCountsAndAllClearsTheLenses() {
        show(F.state(sessions, activeOnly = true))
        rule.onNodeWithContentDescription("Running").assertExists()
        rule.onNodeWithContentDescription("Running (1)").assertDoesNotExist()
        rule.onNodeWithTag(PhoneChromeTags.chip("all")).performClick()
        assertEquals("All clears the latched Running lens", listOf("toggle-active"), events.also { })
    }

    @Test fun eachTargetOfTheMergedOlderAndRunsRowTogglesItsOwnState() {
        val agentRun = F.history("h-run", "A background run", cwd = F.ROOT, ago = 30).copy(origin = "agent-cli-child")
        val state = F.state(sessions, histories = mapOf(F.ROOT to listOf(agentRun)))
        show(state)
        rule.onNodeWithTag(SidebarTags.row("live:ten")).assertDoesNotExist()
        rule.onNodeWithTag(PhoneChromeTags.HiddenRuns).assertIsDisplayed()
        // the Older part: its own state (the band opens) and no runs toggle
        rule.onNodeWithTag(SidebarTags.older(F.ROOT)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SidebarTags.row("live:ten")).assertExists()
        assertEquals("the Older part never touches the runs toggle", emptyList<String>(), events)
        // the runs part: its own action (onToggleHideAgentRuns) and the band stays open
        rule.onNodeWithTag(PhoneChromeTags.HiddenRuns).performClick()
        rule.waitForIdle()
        assertEquals(listOf("runs"), events)
        rule.onNodeWithTag(SidebarTags.row("live:ten")).assertExists()
        // one row, one place: the older text and the runs words share the row
        val older = boundsOfTag(SidebarTags.older(F.ROOT)); val runs = boundsOfTag(PhoneChromeTags.HiddenRuns)
        assertTrue("one row: $older / $runs", kotlin.math.abs(older.top - runs.top) < 2f && older.right <= runs.left + 1f)
    }

    @Test fun aOneDpHairlineSeparatesWorkspaceBlocksAndNeverSitsAboveTheFirst() {
        show(F.state(F.groupSessions + F.live("d3", "Draft the changelog", cwd = F.DOCS, ago = 20), pinned = listOf(F.DOCS, F.ROOT), current = F.APP))
        val rules = rule.onAllNodesWithTag(PhoneChromeTags.BlockRule)
        val blocks = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("a workspace block") { n ->
            (if (androidx.compose.ui.semantics.SemanticsProperties.TestTag in n.config) n.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] else null)?.startsWith("sidebar-block:") == true
        }).fetchSemanticsNodes().size
        assertTrue("at least two blocks seeded", blocks >= 2)
        assertEquals("one hairline per boundary, none above the first", blocks - 1, rules.fetchSemanticsNodes().size)
        val h = rules[0].fetchSemanticsNode().boundsInRoot.height / androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
        assertEquals("1 dp tall (px rounding)", 1f, h, 0.25f)
    }

    private fun boundsOfTag(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test fun theFooterNamesTheLinkOnlineAndTheDroppedLinkOffline() {
        show(F.state(sessions))
        rule.onNodeWithContentDescription("Secure link · Private runtime").assertIsDisplayed()
        rule.onNodeWithTag(PhoneChromeTags.FooterSettings).performClick()
        assertEquals(listOf("settings"), events)
    }

    @Test fun theFooterNeverSaysSecureLinkWhenTheLinkIsDown() {
        show(F.state(sessions, connected = false))
        rule.onNodeWithContentDescription("Reconnecting · Private runtime").assertIsDisplayed()
        rule.onNodeWithText("Reconnecting · Private runtime", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Secure link · Private runtime").assertDoesNotExist()
    }
}
