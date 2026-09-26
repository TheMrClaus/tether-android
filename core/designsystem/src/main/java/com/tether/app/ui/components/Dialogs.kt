package com.tether.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights

/** Themed dialog surface: graphite, 1px line seam, 14dp corners (radius-lg). */
@Composable
fun TetherDialog(
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val t = LocalTetherTokens.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(t.graphite, RoundedCornerShape(TetherDimens.radiusLg))
                .border(1.dp, t.line, RoundedCornerShape(TetherDimens.radiusLg))
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    color = t.white,
                    fontFamily = Manrope,
                    fontWeight = TetherWeights.heading,
                    fontSize = 15.2.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            content()
        }
    }
}
