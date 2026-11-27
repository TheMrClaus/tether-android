package com.tether.app.ui.sidebar

import androidx.compose.ui.test.junit4.createComposeRule
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P8C-FIX1 (ta-8j8c, N10): the merged drawer row "Older (N) · M background CLI runs  Show", one workspace holding an older session
 * and a hidden background CLI run (hideAgentRuns on), phone, both skins.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class OlderAndRunsRowScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shot() = rule.snapContent(skin, "sidebar-older-and-runs-row", "phone") {
        val fx = SidebarFixtures
        val run = fx.history("h-run", "A background run", cwd = fx.ROOT, ago = 30).copy(origin = "agent-cli-child")
        val state = fx.state(
            listOf(fx.live("fresh", "Fresh chat", ago = 5), fx.live("ten", "Ten days chat", ago = 10 * 24L * 60)),
            histories = mapOf(fx.ROOT to listOf(run)),
        )
        SidebarUnderTest(skin, state, TetherLayoutClass.Phone, SidebarUiSeed(), SidebarActions(onCollapse = {}))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}
