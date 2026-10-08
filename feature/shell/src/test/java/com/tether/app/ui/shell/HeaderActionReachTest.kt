package com.tether.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-7njx (W23, L4 ruling Rev 2 + B1-B3): at a text scale above 1.0 the expanded header keeps every web action reachable
 * and End session whole in the window, by the ruled ladder (S1 Telemetry drops its word, S2 Pin moves into the "Session
 * links" menu, S3 End session drops its word); at 1.0 it never yields, as the web does not. The shell's way: a 272 rail
 * then the header (Studio's 28 dp side padding), the menu a [SessionLinksPopover] under the same [HeaderFit]. dp = px.
 */
private class ReachHost(
    val session: AgentSession,
    val width: Int,
    val rail: Float,
    val expanded: Boolean = true,
) {
    val events = mutableListOf<String>()
    val fit = HeaderFit()

    @Composable
    fun Content(tag: String, withMenu: Boolean) {
        var linksOpen by remember { mutableStateOf(false) }
        val actions = WorkspaceHeaderActions(
            onRename = { events += "rename" },
            onEndSession = { events += "end" },
            onTogglePinned = { events += "pin" },
            onCopyPath = {},
            onCopyTetherId = {},
        )
        Box(Modifier.requiredSize(width.dp, BoxHeight.dp).testTag(tag)) {
            Row {
                Spacer(Modifier.width(rail.dp))
                Box(Modifier.width((width - rail).dp)) {
                    WorkspaceHeader(
                        session = session,
                        telemetryOpen = false,
                        linksOpen = linksOpen,
                        onToggleTelemetry = { events += "telemetry" },
                        onToggleLinks = { events += "links"; linksOpen = !linksOpen },
                        actions = actions,
                        gauge = expandedSlots().gauge,
                        expanded = expanded,
                        gaugeIsHandle = true,
                        dial = if (expanded) expandedSlots().dial else null,
                        fit = fit,
                    )
                }
            }
            if (withMenu && linksOpen) {
                SessionLinksPopover(
                    session = session,
                    workspaceRoot = null,
                    copiedPath = false,
                    copiedTetherId = false,
                    actions = actions,
                    onDismiss = { linksOpen = false },
                    expanded = expanded,
                    pinInMenu = fit.pinInMenu,
                )
            }
        }
    }

    companion object {
        /** Each header sits in its own box of this height. */
        const val BoxHeight = 520
    }
}

private val gaugeMatcher = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not pressed")

/** The harness header's node for [tag], or the reference's ([reference]). */
@OptIn(ExperimentalTestApi::class)
private fun ComposeContentTestRule.nodes(tag: String, reference: Boolean = false) =
    onAllNodesWithTag(tag).fetchSemanticsNodes().filter { inReference(it) == reference }

/** Whether the node sits under the reference header's box (by its test tag, not by position). */
private fun inReference(node: SemanticsNode): Boolean {
    var n: SemanticsNode? = node
    while (n != null) {
        if (n.config.getOrNull(SemanticsProperties.TestTag) == "reference") return true
        n = n.parent
    }
    return false
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeContentTestRule.boundsOf(tag: String, reference: Boolean = false): Rect = nodes(tag, reference).first().boundsInRoot

@OptIn(ExperimentalTestApi::class)
private fun ComposeContentTestRule.count(tag: String): Int = nodes(tag).size

private fun ComposeContentTestRule.tapCentre(r: Rect) {
    onRoot().performTouchInput { click(Offset((r.left + r.right) / 2f, (r.top + r.bottom) / 2f)) }
    waitForIdle()
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeContentTestRule.hasWord(text: String): Boolean =
    onAllNodesWithText(text).fetchSemanticsNodes().any { !inReference(it) }

private fun SemanticsNode.contentDescription(): String? = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", ")

private fun ComposeContentTestRule.mount(fontScale: Float, session: AgentSession, vararg hosts: ReachHost) {
    setContent {
        TetherTheme(choiceFor(TetherSkin.StudioDark)) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                LiveUnlessProvided(session) {
                    Column(Modifier.requiredSize(1400.dp, (2 * ReachHost.BoxHeight).dp)) {
                        hosts.forEachIndexed { i, h -> h.Content(if (i == 0) "harness" else "reference", withMenu = i == 0) }
                    }
                }
            }
        }
    }
    waitForIdle()
}

/** What the header kept on the bar, read from the semantics tree (never from [HeaderFit]). */
private class Observed(val teleWord: Boolean, val pinOnBar: Boolean, val endWord: Boolean) {
    override fun toString() = "tele=$teleWord pin=$pinOnBar end=$endWord"
}

private fun ComposeContentTestRule.observe() = Observed(
    teleWord = hasWord("Telemetry"),
    pinOnBar = count(ShellTags.PinKey) > 0,
    endWord = hasWord("End session"),
)

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1400dp-h900dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class HeaderActionReachTest(
    private val w: Int,
    private val fontScale: Float,
    private val status: String,
) {
    @get:Rule val rule = createComposeRule()

    private val session: AgentSession = ExpandedFixtures.idle.copy(name = "Approval fixture", status = status)
    private val host = ReachHost(session, w, 272f)
    private val reference = ReachHost(session, 1280, 272f)

    private fun show() = rule.mount(fontScale, session, host, reference)

    @Test fun endSessionIsWholeInTheWindowAndTappable() {
        show()
        val end = rule.boundsOf(ShellTags.EndSessionKey)
        val limit = if (fontScale > 1.0f) w - 8f else w.toFloat()
        assertTrue("End session whole in [0, $limit]: $end", end.left >= -0.5f && end.right <= limit + 0.5f)
        val touch = rule.nodes(ShellTags.EndSessionKey).first().touchBoundsInRoot
        assertTrue("touch target >= 48 x 48: ${touch.width} x ${touch.height}", touch.width >= 47.5f && touch.height >= 47.5f)
        rule.tapCentre(end)
        assertEquals("a tap on End session's centre ends the session once", listOf("end"), host.events.toList())
    }

    @Test fun everyWebHeaderActionIsReachable() {
        show()
        // The names stay at every step.
        assertEquals("End session", rule.nodes(ShellTags.EndSessionKey).first().contentDescription())
        assertEquals("Session links", rule.nodes(ShellTags.LinksKey).first().contentDescription())
        // Telemetry: the gauge is the handle, with or without its word.
        val gauge = rule.onAllNodes(gaugeMatcher).fetchSemanticsNodes().first { !inReference(it) }
        rule.tapCentre(gauge.boundsInRoot)
        // The pencil: its visible strip (the dial's plate covers the rest where they overlap).
        val key = rule.boundsOf(ShellTags.RenameKey)
        val strip = minOf(rule.boundsOf(ShellTags.Dial).left - key.left, key.width)
        assertTrue("pencil strip $strip >= 9.5", strip >= 9.5f)
        rule.onRoot().performTouchInput { click(Offset(key.left + strip / 2f, (key.top + key.bottom) / 2f)) }
        rule.waitForIdle()
        // Pin: exactly one of the bar key and the menu row.
        val onBar = rule.count(ShellTags.PinKey)
        assertTrue("at most one bar Pin: $onBar", onBar <= 1)
        if (onBar == 1) rule.tapCentre(rule.boundsOf(ShellTags.PinKey))
        rule.tapCentre(rule.boundsOf(ShellTags.LinksKey))
        // The menu row's own tag is cleared by its clearAndSetSemantics (as on the phone): it is found by its name.
        val rowName = if (session.pinned) "Unpin session" else "Pin session"
        val menuRows = rule.onAllNodesWithContentDescription(rowName).fetchSemanticsNodes().filter { !inReference(it) }
        assertEquals("exactly one Pin (bar xor menu row) with the menu open", 1, rule.count(ShellTags.PinKey) + menuRows.size)
        if (onBar == 0) {
            assertEquals("the menu row is the one Pin", 1, menuRows.size)
            rule.tapCentre(menuRows.first().boundsInRoot)
        }
        val expected = if (onBar == 1) listOf("telemetry", "rename", "pin", "links") else listOf("telemetry", "rename", "links", "pin")
        assertEquals("telemetry, rename, Pin and the menu each reached their callback", expected, host.events.toList())
    }

    @Test fun yieldsInTheWebsOrderAndOnlyWhenNeeded() {
        show()
        val o = rule.observe()
        // Monotonic: End wordless implies Pin in the menu implies Telemetry wordless.
        if (!o.endWord) assertTrue("End wordless implies Pin off the bar ($o)", !o.pinOnBar)
        if (!o.pinOnBar) assertTrue("Pin off the bar implies Telemetry wordless ($o)", !o.teleWord)
        if (fontScale <= 1.0f) {
            assertTrue("at 1.0 every width keeps every word and the bar Pin ($o)", o.teleWord && o.pinOnBar && o.endWord)
            return
        }
        val room = (w - 272f - 8f) - (28f + 16f)
        val width = rule.boundsOf(ShellTags.EndSessionKey).right - rule.boundsOf(ShellTags.Dial).left
        val step = when {
            o.teleWord -> 0
            o.pinOnBar -> 1
            o.endWord -> 2
            else -> 3
        }
        if (step < 3) assertTrue("the kept step $step fits the room: $width <= $room", width <= room + 0.5f)
        // The step before it did not fit: add back what the yield removed, from the reference header's own widths.
        val full = rule.boundsOf(ShellTags.EndSessionKey, reference = true).right - rule.boundsOf(ShellTags.Dial, reference = true).left
        val before = when (step) {
            0 -> null
            1 -> full
            2 -> width + rule.boundsOf(ShellTags.PinKey, reference = true).width + 8f
            else -> width + (rule.boundsOf(ShellTags.EndSessionKey, reference = true).width - rule.boundsOf(ShellTags.EndSessionKey).width)
        }
        if (before != null) assertTrue("the step before ($before) did not fit the room $room", before > room - 0.5f)
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "w{0} x{1} {2}")
        fun params(): List<Array<Any>> = listOf(768, 780, 800, 845, 846, 900, 1280).flatMap { w ->
            listOf(1.0f, 1.3f, 2.0f).flatMap { s -> listOf("ready", "waiting").map { arrayOf<Any>(w, s, it) } }
        }
    }
}

/** B1: at font scale 1.0 the header is the web's at every rail width (the web's cluster overruns rather than yields). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1400dp-h900dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class HeaderFontScale1RailTest(private val w: Int, private val rail: Float) {
    @get:Rule val rule = createComposeRule()

    @Test fun atFontScale1TheHeaderIsTheWebsAtEveryRailWidth() {
        val session = ExpandedFixtures.idle.copy(name = "Approval fixture", status = "waiting")
        val host = ReachHost(session, w, rail)
        rule.mount(1.0f, session, host, ReachHost(session, w, rail))
        val o = rule.observe()
        assertTrue("w$w rail $rail at 1.0 is Y0: every word and the bar Pin ($o)", o.teleWord && o.pinOnBar && o.endWord)
        assertEquals("the header did not yield", HeaderFit.STEP_FULL, host.fit.step)
        rule.tapCentre(rule.boundsOf(ShellTags.EndSessionKey))
        assertEquals("w$w rail $rail: End session's centre is owned by the button", listOf("end"), host.events.toList())
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "w{0} rail{1}")
        fun params(): List<Array<Any>> = listOf(768, 780, 800).flatMap { w -> listOf(272f, 307.2f, 320f).map { arrayOf<Any>(w, it) } }
    }
}

/** The rail and phone cases. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(qualifiers = "w1400dp-h900dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class HeaderYieldRailTest {
    @get:Rule val rule = createComposeRule()

    private val session: AgentSession = ExpandedFixtures.idle.copy(name = "Approval fixture", status = "waiting")

    @Test fun aMaxWidthRailStillLeavesEndSessionWhole() {
        // 768 at 2.0 with the stored rail width 480, clamped to min(30rem, 40vw) = 307.2: the header is 460.8 wide.
        val w = 768
        val host = ReachHost(session, w, 307.2f)
        runCell(host, 2.0f) {
            val o = observe()
            assertTrue("S3: End session wordless, Pin in the menu, Telemetry wordless ($o)", !o.endWord && !o.pinOnBar && !o.teleWord)
            val end = boundsOf(ShellTags.EndSessionKey)
            assertTrue("End session whole in [0, W - 8]: $end", end.left >= 0f && end.right <= w - 8f + 0.5f)
            val touch = nodes(ShellTags.EndSessionKey).first().touchBoundsInRoot
            assertTrue("touch target >= 48 x 48: ${touch.width} x ${touch.height}", touch.width >= 47.5f && touch.height >= 47.5f)
            assertEquals("End session", nodes(ShellTags.EndSessionKey).first().contentDescription())
            tapCentre(end)
            assertEquals(listOf("end"), host.events.toList())
        }
    }

    @Test fun thePhoneHeaderIsUnchanged() {
        val host = ReachHost(session, 412, 0f, expanded = false)
        runCell(host, 2.0f) {
            assertEquals("no dial on the phone header", 0, count(ShellTags.Dial))
            assertEquals("no bar Pin on the phone header", 0, count(ShellTags.PinKey))
            assertTrue("no Telemetry word", !hasWord("Telemetry"))
            assertTrue("no End session word", !hasWord("End session"))
            assertEquals("the phone header never yields", HeaderFit.STEP_FULL, host.fit.step)
            val end = boundsOf(ShellTags.EndSessionKey)
            assertTrue("End session inside the window: $end", end.left >= 0f && end.right <= 412f + 0.5f)
        }
    }

    private fun runCell(host: ReachHost, fontScale: Float, block: ComposeContentTestRule.() -> Unit) {
        rule.mount(fontScale, session, host, ReachHost(session, host.width, host.rail, host.expanded))
        rule.block()
    }
}
