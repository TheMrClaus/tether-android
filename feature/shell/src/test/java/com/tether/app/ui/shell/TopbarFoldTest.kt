package com.tether.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * T15.4 r2: the expanded bar never clips or hides a destination or tool. At every window width
 * (768 / 1023 / 1024 / 1280dp; 768 is the web's 48rem floor, ta-09ca) and font scale (1.0 / 1.3 / 2.0) each of Overview, Sessions,
 * Scheduled, Usage, Files and Accounts is EITHER fully on the bar (whole, between the brand and the
 * link state) OR listed in the open utility menu, never both and never neither; and the brand,
 * link state, Settings and menu trigger are whole on the bar. Also: the warning badge never covers
 * most of the menu trigger's glyph, at any font scale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1400dp-h1200dp-mdpi")
class TopbarFoldTest {
    @get:Rule val rule = createComposeRule()

    private data class Case(val width: Int, val scale: Float, val wide: Boolean = true)

    private var case by mutableStateOf(Case(768, 1f))

    private fun show() {
        rule.setContent {
            val base = LocalDensity.current
            val c = case
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                CompositionLocalProvider(LocalReducedMotion provides true, LocalDensity provides Density(base.density, c.scale)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                        key(c) {
                            BoxWithConstraints(Modifier.width(c.width.dp).fillMaxHeight()) {
                                val state = TopbarState(
                                    current = TopBarDestination.Sessions,
                                    link = LinkReadout.Reconnecting,
                                    wide = c.wide,
                                    drawerKey = !c.wide,
                                    menuOpen = true,
                                    viewportWidth = maxWidth.value.roundToInt(),
                                    unseenWarnings = 12,
                                )
                                val actions = TopbarActions(onOpenDrawer = {}, onOpenFiles = {}, onOpenLog = {}, onLogout = {}, onOpenSettings = {}, onNavigate = {})
                                val fold = remember { TopbarFold() }
                                TetherTopbar(actions, state, onToggleMenu = {}, fold = fold)
                                TopbarMenu(actions, state, onDismiss = {}, fold = fold)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun nodes(tag: String): List<SemanticsNode> = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    private fun one(tag: String): SemanticsNode = nodes(tag).single()

    private fun whole(n: SemanticsNode, bar: Rect): Boolean {
        val b = n.boundsInRoot
        return n.size.width > 0 && b.width >= n.size.width - 1f && b.left >= bar.left - 0.5f && b.right <= bar.right + 0.5f
    }

    private val items = listOf(
        ShellTags.nav(TopBarDestination.Overview) to ShellTags.menuNav(TopBarDestination.Overview),
        ShellTags.nav(TopBarDestination.Sessions) to ShellTags.menuNav(TopBarDestination.Sessions),
        ShellTags.nav(TopBarDestination.Scheduled) to ShellTags.menuNav(TopBarDestination.Scheduled),
        ShellTags.nav(TopBarDestination.Usage) to ShellTags.menuNav(TopBarDestination.Usage),
        ShellTags.FilesKey to ShellTags.MenuFiles,
        ShellTags.AccountsKey to ShellTags.MenuAccounts,
    )

    @Test fun everyControlIsWholeOnTheBarOrInTheMenu() {
        show()
        for (width in listOf(768, 1023, 1024, 1280)) {
            for (scale in listOf(1f, 1.3f, 2f)) {
                rule.runOnIdle { case = Case(width, scale) }
                rule.waitForIdle()
                val name = "${width}dp @${scale}x"
                val bar = one(ShellTags.Topbar).boundsInRoot
                val brand = one(ShellTags.Brand).boundsInRoot
                val readout = one(ShellTags.ConnectionReadout)
                for (tag in listOf(ShellTags.Brand, ShellTags.ConnectionReadout, ShellTags.SettingsKey, ShellTags.ToolsMenuKey)) {
                    assertTrue("$name: $tag whole on the bar", whole(one(tag), bar))
                }
                // ta-09ca A4: the receipt line, which destinations stay on the bar and which fold into the menu.
                val stay = items.filter { nodes(it.first).isNotEmpty() }.map { it.first.removePrefix("shell-") }
                val folded = items.filter { nodes(it.second).isNotEmpty() }.map { it.second.removePrefix("shell-menu-") }
                println("A4-FOLD $name: on the bar $stay; in the menu $folded; Settings and More tools whole on the bar")
                for ((onBar, inMenu) in items) {
                    val barNode = nodes(onBar).singleOrNull()
                    val menuNode = nodes(inMenu).singleOrNull()
                    assertTrue("$name: $onBar is on the bar XOR in the menu", (barNode != null) != (menuNode != null))
                    if (barNode != null) {
                        val b = barNode.boundsInRoot
                        assertTrue("$name: $onBar whole (${b.width} of ${barNode.size.width})", whole(barNode, bar))
                        assertTrue("$name: $onBar between the brand and the link state", b.left >= brand.right && b.right <= readout.boundsInRoot.left)
                    }
                }
            }
        }
    }

    /** r2: at 2× the badge keeps its 1× size, so most of the trigger's glyph stays visible. */
    @Test fun theWarningBadgeLeavesTheMenuGlyphVisible() {
        show()
        for (c in listOf(Case(320, 2f, wide = false), Case(412, 1f, wide = false), Case(768, 2f), Case(1280, 1f))) {
            rule.runOnIdle { case = c }
            rule.waitForIdle()
            val trigger = one(ShellTags.ToolsMenuKey).boundsInRoot
            val badge = one(ShellTags.WarningBadge).boundsInRoot
            val half = 9.5f // the 19dp glyph, centred (mdpi: 1dp = 1px)
            val glyph = Rect(trigger.center.x - half, trigger.center.y - half, trigger.center.x + half, trigger.center.y + half)
            val overlap = max(0f, min(glyph.right, badge.right) - max(glyph.left, badge.left)) * max(0f, min(glyph.bottom, badge.bottom) - max(glyph.top, badge.top))
            val covered = overlap / (glyph.width * glyph.height)
            assertTrue("$c: the badge covers ${(covered * 100).roundToInt()}% of the glyph", covered < 0.5f)
        }
    }
}
