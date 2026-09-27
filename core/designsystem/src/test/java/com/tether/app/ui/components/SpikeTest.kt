package com.tether.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.GeneratedTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeChoice
import com.tether.app.ui.theme.ThemeFamily
import com.tether.app.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SpikeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun spike() {
        val t = GeneratedTokens.Tactile
        rule.setContent {
            TetherTheme(ThemeChoice(ThemeFamily.Tactile, ThemeMode.Light)) {
                Box(Modifier.background(t.mineral).padding(24.dp)) {
                    Box(
                        Modifier.size(160.dp, 48.dp).cssSurface(
                            RoundedCornerShape(t.radiusKey), t.keyFace, CssBorder(1.dp, t.keySide),
                            listOf(hardShadow(1.dp, t.litStrong, inset = true), hardShadow(0.dp, t.litSoft, x = 1.dp, inset = true)) + t.shadowKey,
                        ).padding(12.dp),
                    ) { Text("APPROVE", style = LocalTetherTypography.current.keyLabel.style) }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/spike/spike.png")
    }
}
