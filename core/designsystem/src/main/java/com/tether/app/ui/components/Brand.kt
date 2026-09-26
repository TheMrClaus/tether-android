package com.tether.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherWeights

/** The Tether brand mark: 18.4dp ring with two violet bars rotated 32°. */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Box(
        modifier = modifier
            .size(18.4.dp)
            .border(1.dp, t.lineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.graphicsLayer { rotationZ = 32f },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 2.2.dp, height = 7.dp).background(t.violet, RoundedCornerShape(1.dp)))
            Box(Modifier.size(width = 2.2.dp, height = 7.dp).background(t.violet, RoundedCornerShape(1.dp)))
        }
    }
}

/** "TETHER" wordmark: 12.5sp, w720, tracking .2em. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Text(
        text = "TETHER",
        modifier = modifier,
        color = t.white,
        fontFamily = Manrope,
        fontWeight = TetherWeights.wordmark,
        fontSize = 12.5.sp,
        letterSpacing = 0.2.em,
        maxLines = 1,
    )
}
