package com.tether.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.onAllNodesWithTag
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
 * ta-0af6 (W21): the session title row at the web's 768-800 against the web at tether 29537e0 (L1 spec section 1,
 * ruling R1; no guard). One header the shell's way (a 272 rail, then the header with Studio's 28 side padding, so the
 * content spans x 300 to W - 28) at W = 768 / 772 / 780 / 800, at text scale 1.0 / 1.3 / 2.0, Ready and Needs you, with
 * a second header at W = 1280 on the same screen as the reference for every intrinsic width. dp = px (mdpi).
 *
 * The web: only the title shrinks (to 0) at text scale 1.0, and so does the app; the pencil, the pill and the badge keep their size and run under the action
 * cluster, which keeps its own width at x = max(contentL + 16, contentR - clusterW) and draws over them. The pencil's
 * centre is under the dial where the web's is (768-773 dp at 1.0x, to 800+ at 1.3x / 2.0x); the web keeps 10 dp of it
 * visible and tappable, so does the app.
 *
 * ta-7njx (W23, ruling B1-B3): above text scale 1.0 the cluster yields (S1 Telemetry drops its word, S2 Pin moves into the
 * "Session links" menu, S3 End session drops its word) so that End session stays whole in the window; at 1.0 it never
 * does. The step at each (width, scale) is HARD-CODED below ([stepAt]) from the ruled table, never read from the header's
 * own [HeaderFit], so a wrong yield cannot switch its own check off. The 1.0 checks stay unconditional.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1400dp-h900dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class WorkspaceHeaderRowTest(
    private val w: Int,
    private val fontScale: Float,
    private val status: String,
) {
    @get:Rule val rule = createComposeRule()

    private val events = mutableListOf<String>()
    private val session: AgentSession = ExpandedFixtures.idle.copy(name = "Approval fixture", status = status)

    @Composable
    private fun Harness(width: Int, tag: String, withBadge: Boolean) {
        Box(Modifier.requiredSize(width.dp, 120.dp).testTag(tag)) {
            Row {
                Spacer(Modifier.width(272.dp)) // the rail
                Box(Modifier.width((width - 272).dp)) {
                    WorkspaceHeader(
                        session = session,
                        telemetryOpen = false,
                        linksOpen = false,
                        onToggleTelemetry = {},
                        onToggleLinks = {},
                        actions = WorkspaceHeaderActions(
                            onRename = { events += "rename@$width" },
                            onEndSession = { events += "end@$width" },
                            onTogglePinned = {},
                            onCopyPath = {},
                            onCopyTetherId = {},
                        ),
                        gauge = expandedSlots().gauge,
                        expanded = true,
                        gaugeIsHandle = true,
                        dial = expandedSlots().dial,
                        badge = if (withBadge) ({ Text("DeepSeek peak", modifier = Modifier.testTag("badge")) }) else null,
                    )
                }
            }
        }
    }

    private fun show(withBadge: Boolean = false) {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    LiveUnlessProvided(session) {
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.align(Alignment.TopStart)) {
                                Harness(w, "harness", withBadge)
                                Harness(1280, "reference", withBadge)
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun rect(tag: String, index: Int): Rect = rule.onAllNodesWithTag(tag)[index].fetchSemanticsNode().boundsInRoot
    private fun here(tag: String) = rect(tag, 0)
    private fun ref(tag: String) = rect(tag, 1)

    /** The reference header sits below the first: the y of a node there, relative to its own header top. */
    private val refTop get() = rule.onAllNodesWithTag("reference")[0].fetchSemanticsNode().boundsInRoot.top

    private fun expectedClusterLeft(): Float {
        val dial = here(ShellTags.Dial)
        val clusterW = here(ShellTags.EndSessionKey).right - dial.left
        return maxOf(300f + 16f, (w - 28f) - clusterW)
    }

    /**
     * The ruled step for this (width, scale) at the 272 rail, hard-coded (L4 ruling Rev 2, B2): the cluster's room is
     * W - 324 dp; 1.0 never yields; 1.3 is S1 up to ~798 and whole above; 2.0 is S2 across 768-800.
     */
    private fun stepAt(): Int = when {
        fontScale == 1.0f -> 0
        fontScale == 1.3f -> if (w < 798) 1 else 0
        else -> 2
    }

    @Test fun thePencilIsWholeAndItsVisibleStripTakesATouchAndNothingElseDoes() {
        show()
        val key = here(ShellTags.RenameKey)
        assertEquals("pencil width", 23.96f, key.width, 0.5f)
        assertEquals("pencil height", 23.96f, key.height, 0.5f)
        assertTrue("pencil inside the window: $key", key.left >= 0f && key.right <= w && key.top >= 0f)
        val dialLeft = here(ShellTags.Dial).left
        assertEquals("cluster left = the web's max(316, contentR - clusterW)", expectedClusterLeft(), dialLeft, 0.5f)
        if (fontScale == 1.0f) {
            val web = mapOf(768 to 327f, 772 to 331f, 780 to 339f, 800 to 359f).getValue(w)
            assertEquals("L_web at 1.0x", web, dialLeft, 0.5f)
        }
        val strip = dialLeft - key.left
        assertTrue("visible strip $strip >= 9.5", strip >= 9.5f)
        val y = (key.top + key.bottom) / 2f
        rule.onRoot().performTouchInput { click(Offset(key.left + strip / 2f, y)) }
        rule.waitForIdle()
        assertEquals("a tap on the strip renames and reaches nothing else", listOf("rename@$w"), events.toList())
        // Recorded, not asserted (the web's pencil centre is under the dial from 768 to 773 at 1.0x and wider at large text).
        events.clear()
        rule.onRoot().performTouchInput { click(Offset((key.left + key.right) / 2f, y)) }
        rule.waitForIdle()
        println("W21-RECORD w=$w x$fontScale $status centre-tap=${events.toList()} key=${key.left}-${key.right} cluster=$dialLeft strip=$strip")
    }

    @Test fun thePillAndTheBadgeKeepTheirSizeAndTheClusterItsWidth() {
        show(withBadge = true)
        val step = stepAt()
        val yielded = buildList {
            if (step >= HeaderFit.STEP_PIN_IN_MENU) add(ShellTags.PinKey)
            if (step >= HeaderFit.STEP_END_WORDLESS) add(ShellTags.EndSessionKey)
        }
        // Pin is off the bar from S2: the harness has none (index 0 would be the reference's), so it is asserted absent.
        if (step >= HeaderFit.STEP_PIN_IN_MENU) {
            assertEquals("no bar Pin in the harness header at step $step", 1, rule.onAllNodesWithTag(ShellTags.PinKey).fetchSemanticsNodes().size)
        }
        for (tag in listOf(ShellTags.StatusPill, "badge", ShellTags.Dial, ShellTags.LinksKey, ShellTags.PinKey, ShellTags.EndSessionKey, ShellTags.RenameKey)) {
            if (tag in yielded) continue
            assertEquals("$tag width equals its width at 1280", ref(tag).width, here(tag).width, 0.5f)
        }
        if (step == 0) {
            assertEquals("cluster width", ref(ShellTags.EndSessionKey).right - ref(ShellTags.Dial).left, here(ShellTags.EndSessionKey).right - here(ShellTags.Dial).left, 0.5f)
        }
        val endRight = here(ShellTags.EndSessionKey).right
        if (fontScale == 1.0f) assertTrue("End session ends inside the window", endRight <= w + 0.5f)
        else assertTrue("End session ends inside [0, W - 8] above 1.0: $endRight", endRight <= w - 8f + 0.5f)
        println("W21-RECORD end-session w=$w x$fontScale $status left=${here(ShellTags.EndSessionKey).left} right=${here(ShellTags.EndSessionKey).right} width=${here(ShellTags.EndSessionKey).width}")
    }

    @Test fun theTitleIsTheOnlyThingThatGivesWay() {
        show()
        val titles = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
        assertEquals("both headers have a title", 2, titles.size)
        val mine = titles.minBy { it.boundsInRoot.top }
        assertEquals("title width (0 at 768-800)", 0f, mine.boundsInRoot.width, 0.5f)
        // The reference header is wide enough for the whole name.
        assertTrue("title at 1280 has its width", titles.maxBy { it.boundsInRoot.top }.boundsInRoot.width > 40f)
    }

    @Test fun theDialsPlateCoversThePencilWhereTheyOverlap() {
        show()
        val key = here(ShellTags.RenameKey)
        val dialLeft = here(ShellTags.Dial).left
        if (dialLeft >= key.right - 2f) return // no overlap at this width/scale
        val a = rule.onAllNodesWithTag("harness")[0].captureToImage().toPixelMap()
        val b = rule.onAllNodesWithTag("reference")[0].captureToImage().toPixelMap()
        // The cluster has the same width and height in both headers, so the dial's pixels sit at the same offset
        // from the cluster's left: where the pencil overlaps them, they must be the reference's (plate only).
        val x0 = (dialLeft + 1f).toInt()
        val x1 = (key.right - 1f).toInt()
        val refDialLeft = ref(ShellTags.Dial).left
        val y0 = key.top.toInt()
        val y1 = key.bottom.toInt()
        var differing = 0
        for (x in x0..x1) for (y in y0..y1) {
            if (a[x, y] != b[(x - dialLeft + refDialLeft).toInt(), y]) differing++
        }
        assertEquals("pixels where the pencil shows through the dial", 0, differing)
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "w{0} x{1} {2}")
        fun params(): List<Array<Any>> = listOf(768, 772, 780, 800).flatMap { w ->
            listOf(1.0f, 1.3f, 2.0f).flatMap { s -> listOf("ready", "waiting").map { arrayOf<Any>(w, s, it) } }
        }
    }
}

/** The foldable (W = 900, 1.0x): the title has room again and the pill is whole beside it (the web: 914 shows the name). */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(qualifiers = "w1000dp-h600dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class WorkspaceHeaderFoldableRowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theTitleHasRoomAndThePillIsWholeAt900() {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                LiveUnlessProvided(ExpandedFixtures.idle) {
                    Box(Modifier.requiredSize(900.dp, 120.dp)) {
                        Row {
                            Spacer(Modifier.width(272.dp))
                            Box(Modifier.width(628.dp)) {
                                WorkspaceHeader(
                                    session = ExpandedFixtures.idle,
                                    telemetryOpen = false, linksOpen = false, onToggleTelemetry = {}, onToggleLinks = {},
                                    actions = WorkspaceHeaderActions({}, {}, {}, {}, {}),
                                    gauge = expandedSlots().gauge, expanded = true, dial = expandedSlots().dial,
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val title = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes().single().boundsInRoot
        assertTrue("title ${title.width} > 0", title.width > 20f)
        val pill = rule.onAllNodesWithTag(ShellTags.StatusPill)[0].fetchSemanticsNode().boundsInRoot
        val dial = rule.onAllNodesWithTag(ShellTags.Dial)[0].fetchSemanticsNode().boundsInRoot
        assertTrue("pill ${pill.right} whole, left of the dial ${dial.left}", pill.right <= dial.left)
        println("W21-RECORD w=900 title=${title.width} pill=${pill.left}-${pill.right} dial=${dial.left}")
    }
}
