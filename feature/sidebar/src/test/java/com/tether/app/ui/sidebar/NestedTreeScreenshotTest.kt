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
 * ta-q2t6 slice 2 (W44, WC11): the open tree on a 412 phone, light and dark. An open chain with a codex member (branded mark), a `zzz`
 * member (letter-fallback mark) and a same-provider member (no mark); a delegate group open under member n1 and under the head h.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class NestedTreeScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun openTree() = rule.snapContent(skin, "sidebar-nested-open-tree", "phone") {
        SidebarUnderTest(skin, NestedFixture.state, TetherLayoutClass.Phone, NestedFixture.seed, SidebarActions(onCollapse = {}))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}
