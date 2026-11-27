package com.tether.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun scaffold(title: String, content: @androidx.compose.runtime.Composable () -> Unit = {}): @androidx.compose.runtime.Composable () -> Unit = {
    val items = listOf(RuntimeDeskNavItem("limits", title, "Accounts & reset grants"))
    RuntimeDeskScaffold(
        items = items,
        selected = "limits",
        onSelect = {},
        entities = emptyList(),
        selectedEntity = null,
        onEntity = {},
        level = RuntimeDeskLevel.Detail,
        onBack = {},
        onReturn = {},
    ) { content() }
}

/** P8E 7 (Q-A): every Settings page bar is 54dp, its title never clipped. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class RuntimeDeskHeaderHeightTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun theBarIs54dpAtNormalType() {
        rule.setContent { TetherTheme(TetherSkin.Studio.mode, content = scaffold("Limits & balance")) }
        rule.waitForIdle()
        val px = rule.onNodeWithTag(RuntimeDeskTags.TopBar).fetchSemanticsNode().size.height
        val density = rule.activity.resources.displayMetrics.density
        println("TOPBAR height=${px}px = ${px / density}dp")
        assertEquals(54f, px / density, 0.5f)
        assertEquals(54.dp, RuntimeDeskStyle.HeaderHeight)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class RuntimeDeskHeaderLargeTypeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun theTitleIsNotClippedAt1_3x() {
        rule.setContent { TetherTheme(TetherSkin.Studio.mode, content = scaffold("Limits & balance")) }
        rule.waitForIdle()
        val bar = rule.onNodeWithTag(RuntimeDeskTags.TopBar).fetchSemanticsNode().size.height / rule.activity.resources.displayMetrics.density
        println("TOPBAR 1.3x height=${bar}dp")
        assertTrue("the bar is at least 54dp ($bar)", bar >= 53.5f)
        val title = rule.onNode(hasText("Limits & balance") and hasAnyAncestor(hasTestTag(RuntimeDeskTags.TopBar)), useUnmergedTree = true)
        val results = mutableListOf<TextLayoutResult>()
        rule.runOnUiThread { title.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results) }
        assertTrue("one layout result", results.isNotEmpty())
        assertTrue("no ellipsis or clip: ${results[0].hasVisualOverflow}", !results[0].hasVisualOverflow)
        assertEquals("one line", 1, results[0].lineCount)
    }
}

/** P8E 11: the Appearance cards: side by side, equal, the selected one an accent outline >= 3:1 vs the panel, labels >= 4.5:1. */
abstract class AppearanceCardsBase(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val names = listOf("Light", "Dark", "System")

    protected fun check() {
        var t: TetherTokens? = null
        var selected by mutableIntStateOf(1)
        rule.setContent {
            TetherTheme(skin.mode) {
                t = LocalTetherTokens.current
                Box(Modifier.fillMaxSize().background(LocalTetherTokens.current.graphite).padding(20.dp).testTag("panel")) {
                    ThemeCards(Modifier, 3) { i, m ->
                        ThemeCard(TetherIcons.Sun, names[i], "Hint for ${names[i]}", selected == i, { selected = i }, m.testTag("card-$i"))
                    }
                }
            }
        }
        rule.waitForIdle()
        val tk = t!!
        val nodes = (0..2).map { rule.onNodeWithTag("card-$it").fetchSemanticsNode() }
        assertEquals("one row: equal tops", 1, nodes.map { it.positionInRoot.y.toInt() }.toSet().size)
        assertTrue("equal widths: ${nodes.map { it.size.width }}", nodes.map { it.size.width }.let { w -> w.max() - w.min() <= 1 })
        assertTrue("side by side", nodes[0].positionInRoot.x < nodes[1].positionInRoot.x && nodes[1].positionInRoot.x < nodes[2].positionInRoot.x)

        val img = rule.onNodeWithTag("card-1").captureToImage()
        val px = img.toPixelMap()
        val outline = px[1, px.height / 2]
        val panel = tk.graphite
        val ro = ratio(outline, panel)
        val selFace = px[px.width - 14, px.height - 14]
        println("APPEARANCE $skin outline=${outline.hex()} vs panel=${panel.hex()} ${"%.2f".format(ro)}:1")
        assertTrue("selected outline vs panel $ro ($skin)", ro >= 3.0)
        assertEquals("selected face is the accent-soft wash ($skin)", tk.css.accentSoft.toArgbInt(), selFace.toArgbInt())

        (0..2).forEach { i ->
            val card = rule.onNodeWithTag("card-$i").captureToImage()
            val face = card.toPixelMap().let { it[it.width - 14, it.height - 14] }
            val b = nodes[i].boundsInRoot
            listOf(names[i], "Hint for ${names[i]}").forEach { txt ->
                val tb = rule.onNode(hasText(txt) and hasAnyAncestor(hasTestTag("card-$i")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("'$txt' sits inside its card, unclipped ($skin)", tb.left >= b.left && tb.right <= b.right)
                val (g, n) = farthest(card, face, (tb.left - b.left).toInt(), (tb.top - b.top).toInt(), (tb.right - b.left).toInt(), (tb.bottom - b.top).toInt())
                val r = ratio(g, face)
                println("APPEARANCE $skin card$i '$txt' ${g.hex()} on ${face.hex()} ${"%.2f".format(r)}:1")
                assertTrue("'$txt' has pixels", n > 0)
                assertTrue("'$txt' vs its face $r ($skin)", r >= 4.5)
            }
        }
        rule.onNodeWithTag("card-2").performClick()
        rule.waitForIdle()
        assertEquals("a press selects", 2, selected)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class AppearanceCardsLightTest : AppearanceCardsBase(TetherSkin.Studio) { @Test fun cards() = check() }

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class AppearanceCardsDarkTest : AppearanceCardsBase(TetherSkin.StudioDark) { @Test fun cards() = check() }

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class AppearanceCardsLargeTypeLightTest : AppearanceCardsBase(TetherSkin.Studio) { @Test fun cards() = check() }

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class AppearanceCardsLargeTypeDarkTest : AppearanceCardsBase(TetherSkin.StudioDark) { @Test fun cards() = check() }
