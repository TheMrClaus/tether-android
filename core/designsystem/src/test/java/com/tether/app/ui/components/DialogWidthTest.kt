package com.tether.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** W34: the dialog family is 440dp; only a caller that asks (the three-key Hand over dialog) gets another width. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class DialogWidthTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun theDefaultIs440AndAnOptionalWidthIs500CappedByTheWindow() {
        rule.setContent {
            TetherTheme {
                Column {
                    TetherDialogSurface(Modifier.testTag("default"), title = "a") {}
                    TetherDialogSurface(Modifier.testTag("wide"), title = "b", width = 500.dp) {}
                }
            }
        }
        rule.waitForIdle()
        assertEquals(440.dp, rule.onNodeWithTag("default").getBoundsInRoot().width)
        assertEquals(500.dp, rule.onNodeWithTag("wide").getBoundsInRoot().width)
        assertEquals(440.dp, DEFAULT_DIALOG_WIDTH)
    }

    @Test
    fun aCircleXAliasExists() {
        org.junit.Assert.assertTrue(com.tether.app.ui.icons.TetherIcons.CircleX.name.isNotEmpty())
    }
}
