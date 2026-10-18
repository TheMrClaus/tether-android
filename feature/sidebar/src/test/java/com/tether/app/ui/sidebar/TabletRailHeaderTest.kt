package com.tether.app.ui.sidebar

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val ATLAS = "/srv/atlas-web"

/** A 9-letter workspace with a session waiting on the user: the header's name, count and waiting dot at the 272 rail. */
private fun atlasState(): SidebarState {
    val sessions = listOf(
        SidebarFixtures.live("w1", "Approve the lint fix", cwd = ATLAS, status = "waiting", ago = 3),
        SidebarFixtures.live("w2", "Migrate the legacy importer", cwd = ATLAS, ago = 30),
        SidebarFixtures.live("w3", "Write the release notes", cwd = ATLAS, ago = 90),
    )
    return SidebarFixtures.state(sessions, current = ATLAS, activeId = "w2")
}

/**
 * W53b (ta-hkk7.8): at the default 272 rail the workspace header keeps its NAME. The web orders the header chevron, badge, name
 * (studio.css:1318 `.workspace-badge` after the toggle's chevron), so the tablet's chevron leads and no trailing chevron column
 * eats the width; the activity dot rides the tile's corner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class TabletRailHeaderTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theNineLetterNameIsInFullAtTheDefaultRail() {
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, atlasState(), TetherLayoutClass.Expanded, SidebarUiSeed(), SidebarActions(), railWidth = 272.dp) }
        rule.waitForIdle()
        val node = rule.onNodeWithText("atlas-web", useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(results)
        val layout = results.single()
        val intrinsic = layout.multiParagraph.maxIntrinsicWidth
        assertFalse("the name must not be ellipsized (width ${node.size.width}, intrinsic $intrinsic)", layout.hasVisualOverflow)
        assertTrue("name width ${node.size.width} < intrinsic $intrinsic", node.size.width >= intrinsic - 0.5f)
    }

    @Test fun theChevronLeadsTheBadgeAndNoTrailingChevronColumnRemains() {
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, atlasState(), TetherLayoutClass.Expanded, SidebarUiSeed(), SidebarActions(), railWidth = 272.dp) }
        rule.waitForIdle()
        val chevron = rule.onNodeWithTag(SidebarTags.RailChevron, useUnmergedTree = true).fetchSemanticsNode()
        val name = rule.onNodeWithText("atlas-web", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("the chevron precedes the name", chevron.positionInRoot.x + chevron.size.width <= name.positionInRoot.x)
        // The count, then the star: nothing but the star and + keys follow the name's column (no trailing chevron slot).
        val count = rule.onNodeWithTag(SidebarTags.workspaceCount(ATLAS), useUnmergedTree = true).fetchSemanticsNode()
        val star = rule.onNodeWithContentDescription("Keep atlas-web in the sidebar", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("count ends before the star", count.positionInRoot.x + count.size.width <= star.positionInRoot.x)
        assertEquals("a 48 dp star key", 48f, star.size.width / 1f, 0.5f)
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class TabletRailHeaderScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun header() = rule.snapContent(skin, "sidebar-rail-header-272", "tablet") {
        SidebarUnderTest(skin, atlasState(), TetherLayoutClass.Expanded, SidebarUiSeed(), SidebarActions(onCollapse = {}), railWidth = 272.dp)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}
