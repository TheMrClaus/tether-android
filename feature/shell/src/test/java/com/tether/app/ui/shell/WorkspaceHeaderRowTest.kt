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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.theme.LocalTetherTokens
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
    private var dialOpaqueInsetPx = Float.NaN

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
                    dialOpaqueInsetPx = with(LocalDensity.current) {
                        (LocalTetherTokens.current.radiusKey - 2.dp).toPx()
                    }
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
            val compactClusterLeft = mapOf(768 to 343f, 772 to 347f, 780 to 355f, 800 to 375f).getValue(w)
            assertEquals("compact cluster left at 1.0x", compactClusterLeft, dialLeft, 0.5f)
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
        val dial = here(ShellTags.Dial)
        val dialLeft = dial.left
        if (dialLeft >= key.right - 2f) return // no overlap at this width/scale
        val a = rule.onAllNodesWithTag("harness")[0].captureToImage().toPixelMap()
        val b = rule.onAllNodesWithTag("reference")[0].captureToImage().toPixelMap()
        // The cluster has the same width and height in both headers, so the dial's pixels sit at the same offset
        // from the cluster's left: where the pencil overlaps the rounded plate's guaranteed opaque interior,
        // they must be the reference's (plate only). The inset is the production shape's actual theme radius.
        val opaqueDial = Rect(
            left = dial.left + dialOpaqueInsetPx,
            top = dial.top + dialOpaqueInsetPx,
            right = dial.right - dialOpaqueInsetPx,
            bottom = dial.bottom - dialOpaqueInsetPx,
        )
        val overlap = key.intersect(opaqueDial)
        val x0 = kotlin.math.ceil(overlap.left).toInt()
        val x1 = kotlin.math.floor(overlap.right).toInt()
        val y0 = kotlin.math.ceil(overlap.top).toInt()
        val y1 = kotlin.math.floor(overlap.bottom).toInt()
        assertTrue(
            "opaque dial interior must retain pixel coverage: key=$key dial=$dial inset=$dialOpaqueInsetPx overlap=$overlap",
            x0 < x1 && y0 < y1,
        )
        val refDialLeft = ref(ShellTags.Dial).left
        var differing = 0
        var compared = 0
        for (x in x0 until x1) for (y in y0 until y1) {
            compared++
            if (a[x, y] != b[(x - dialLeft + refDialLeft).toInt(), y]) differing++
        }
        assertTrue("opaque dial comparison must sample pixels", compared > 0)
        assertEquals("pixels where the pencil shows through the opaque dial interior", 0, differing)
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

/** ta-4l2e: the compact base row is exactly the 44dp action floor, with its 1dp rule painted inside. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1400dp-h900dp-mdpi")
class WorkspaceHeaderCompactGeometryTest(
    private val viewport: Int,
    private val rail: Int,
    private val expanded: Boolean,
    private val fontScale: Float,
) {
    @get:Rule val rule = createComposeRule()

    @Test fun ordinaryHeaderBaseIs44DpWithACompactEndSessionFaceAndNoClippedIdentity() {
        var session by mutableStateOf(ExpandedFixtures.idle.copy(name = "Approval fixture", status = "waiting"))
        var endSessionClicks = 0
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    LiveUnlessProvided(session) {
                        Row(Modifier.requiredSize(viewport.dp, 120.dp)) {
                            if (rail > 0) Spacer(Modifier.width(rail.dp))
                            Box(
                                Modifier
                                    .width((viewport - rail).dp)
                                    .testTag("workspace-header-outer-geometry"),
                            ) {
                                WorkspaceHeader(
                                    session = session,
                                    telemetryOpen = false,
                                    linksOpen = false,
                                    onToggleTelemetry = {},
                                    onToggleLinks = {},
                                    actions = WorkspaceHeaderActions(
                                        onRename = {},
                                        onEndSession = {
                                            endSessionClicks += 1
                                            session = session.copy(status = "exited")
                                        },
                                        onTogglePinned = {},
                                        onCopyPath = {},
                                        onCopyTetherId = {},
                                    ),
                                    gauge = expandedSlots().gauge,
                                    expanded = expanded,
                                    gaugeIsHandle = true,
                                    dial = if (expanded) expandedSlots().dial else null,
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val header = rule.onNodeWithTag("workspace-header-outer-geometry").fetchSemanticsNode().boundsInRoot
        val title = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes().single().boundsInRoot
        val pill = rule.onAllNodesWithTag(ShellTags.StatusPill)[0].fetchSemanticsNode().boundsInRoot
        val end = rule.onNodeWithTag(ShellTags.EndSessionKey)
            .assertExists()
            .assertContentDescriptionEquals("End session")
        val endNode = end.fetchSemanticsNode()
        val endFace = endNode.boundsInRoot
        assertEquals("$viewport dp @${fontScale}x header", 44f, header.height, 0.5f)
        assertEquals("End session painted height", 28f, endFace.height, 0.5f)
        if (expanded) {
            assertTrue("labelled End session keeps intrinsic width: ${endFace.width}", endFace.width > 36f)
        } else {
            assertEquals("icon-only End session painted width", 36f, endFace.width, 0.5f)
        }
        assertTrue(
            "End session touch remains at least 44dp: ${endNode.touchBoundsInRoot}",
            endNode.touchBoundsInRoot.width >= 43.5f && endNode.touchBoundsInRoot.height >= 43.5f,
        )
        assertTrue("title stays vertically inside $header: $title", title.top >= header.top && title.bottom <= header.bottom)
        assertTrue("status stays vertically inside $header: $pill", pill.top >= header.top && pill.bottom <= header.bottom)

        end.performClick()
        rule.waitForIdle()
        assertEquals("one End session click invokes the callback once", 1, endSessionClicks)
        val exitedEnd = rule.onNodeWithTag(ShellTags.EndSessionKey)
            .assertExists()
            .assertContentDescriptionEquals("End session")
            .assertIsNotEnabled()
        assertEquals("exited End session painted height", 28f, exitedEnd.fetchSemanticsNode().boundsInRoot.height, 0.5f)
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "w{0} rail{1} expanded={2} x{3}")
        fun params(): List<Array<Any>> = listOf(
            arrayOf(412, 0, false, 1f),
            arrayOf(412, 0, false, 1.3f),
            arrayOf(780, 272, true, 1f),
            arrayOf(1280, 272, true, 1f),
        )
    }
}
