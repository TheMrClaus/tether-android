package com.tether.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.TetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-a5jl A14: the sheet's two new parameters are additive. Without them a card is the attach sheet's 352 dp with no glyph
 * (every existing sheet draws as before); with them the glyph shows and the card takes the width asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class SheetDefaultsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun withoutIconOrCardWidthTheCardIs352AndHasNoGlyph() {
        rule.setContent {
            TetherTheme {
                Box(Modifier.width(1000.dp)) {
                    TetherSheetSurface(title = "Attach", docked = false, onClose = {}, modifier = Modifier.testTag("sheet")) {}
                }
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithTag("sheet-icon", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag("sheet").assertWidthIsEqualTo(352.dp)
    }

    @Test fun anIconAndACardWidthAreHonoured() {
        rule.setContent {
            TetherTheme {
                Box(Modifier.width(1000.dp)) {
                    TetherSheetSurface(title = "Shell", docked = false, onClose = {}, icon = TetherIcons.SquareTerminal, cardWidth = 720.dp, modifier = Modifier.testTag("sheet")) {}
                }
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithTag("sheet-icon", useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithTag("sheet").assertWidthIsEqualTo(720.dp)
    }
}
