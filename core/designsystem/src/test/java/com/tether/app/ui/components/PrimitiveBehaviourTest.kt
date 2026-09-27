package com.tether.app.ui.components

import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class RecordingHaptics : TetherHaptics(null) {
    val moments = mutableListOf<HapticMoment>()
    override fun perform(moment: HapticMoment) {
        moments += moment
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PrimitiveBehaviourTest {
    @get:Rule val rule = createComposeRule()

    @Test fun keyPressAndReleaseAreTheTwoKeyMoments() {
        val haptics = RecordingHaptics()
        var clicks = 0
        rule.setContent {
            TetherTheme {
                CompositionLocalProvider(LocalTetherHaptics provides haptics) {
                    TetherKey(onClick = { clicks++ }, label = "Send")
                }
            }
        }
        rule.onNodeWithContentDescription("Send").performTouchInput { down(center) }
        rule.waitForIdle()
        assertEquals(listOf(HapticMoment.KeyDown), haptics.moments)
        rule.onNodeWithContentDescription("Send").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(listOf(HapticMoment.KeyDown, HapticMoment.KeyUp), haptics.moments)
        assertEquals(1, clicks)
    }

    @Test fun disabledKeysNeverVibrate() {
        val haptics = RecordingHaptics()
        rule.setContent {
            TetherTheme {
                CompositionLocalProvider(LocalTetherHaptics provides haptics) {
                    TetherKey(onClick = {}, label = "Send", enabled = false)
                }
            }
        }
        rule.onNodeWithContentDescription("Send").performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertTrue(haptics.moments.isEmpty())
    }

    @Test fun removeAnimationsTurnsOnReducedMotion() {
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var seen: Boolean? = null
        rule.setContent { TetherTheme { seen = LocalReducedMotion.current } }
        rule.waitForIdle()
        assertEquals(true, seen)
    }

    @Test fun animationsOnByDefault() {
        var seen: Boolean? = null
        rule.setContent { TetherTheme { seen = LocalReducedMotion.current } }
        rule.waitForIdle()
        assertEquals(false, seen)
    }

    @Test fun expandableShowsItsToggleOnlyWhenThereIsMore() {
        rule.setContent {
            TetherTheme {
                androidx.compose.foundation.layout.Column {
                    TetherExpandablePre((1..40).joinToString("\n") { "line $it" }, clamp = 100.dp)
                    TetherExpandablePre("short", contentDescription = "short block")
                }
            }
        }
        rule.waitForIdle()
        val toggle = rule.onNodeWithContentDescription("more lines", substring = true)
        toggle.assertExists()
        toggle.performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Show less").assertExists()
        rule.onNodeWithContentDescription("Show less").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("more lines", substring = true).assertExists()
        // Exactly one toggle: the short block has none.
        assertEquals(1, rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Show", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun revealOpensOnItsRisingEdgeAndNeverRecollapses() {
        var reveal by mutableStateOf(false)
        rule.setContent {
            TetherTheme {
                TetherExpandableBlock(clamp = 40.dp, reveal = reveal) {
                    androidx.compose.material3.Text((1..30).joinToString("\n") { "row $it" })
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Show more").assertExists()
        reveal = true
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Show less").assertExists()
        reveal = false
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Show less").assertExists()
    }

    @Test fun rockerToggles() {
        rule.setContent {
            TetherTheme {
                var on by remember { mutableStateOf(false) }
                TetherRocker(checked = on, onCheckedChange = { on = it }, contentDescription = "Confirm before ending")
            }
        }
        val node = rule.onNodeWithContentDescription("Confirm before ending")
        node.assertIsOff()
        node.performClick()
        node.assertIsOn()
    }
}
