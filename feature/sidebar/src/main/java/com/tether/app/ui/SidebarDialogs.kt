package com.tether.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherWeights

// The folder picker the drawer opens is feature/settings' FolderPickerDialog (T8.2), shared with the
// draft composer and Settings' home "Browse folders".

/** 32dp provider glyph circle: key-face, 1px line-strong, mono letter
 *  (.provider-glyph + the material layer's molded-round-cap treatment). */
@Composable
fun ProviderGlyph(glyph: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Box(
        modifier = modifier
            .size(32.dp)
            .background(t.keyFace, CircleShape)
            .border(1.dp, t.lineStrong, CircleShape)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = t.ink,
            fontFamily = JetBrainsMono,
            fontWeight = TetherWeights.glyph,
            fontSize = 12.8.sp,
        )
    }
}
