package com.tether.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.LocalReducedMotion

/**
 * Sanctioned ambient motion (visual-spec §2.5 / §5): 720ms ring spinners,
 * 1s Loader rotation, 2s waiting-ping halo. All freeze under reduced motion.
 */

/** 1.5dp ring with a transparent top gap, rotating 720ms linear. */
@Composable
fun SpinnerRing(color: Color, size: Dp = 10.4.dp, stroke: Dp = 1.5.dp, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val angle: Float = if (reduced) 0f else {
        val transition = rememberInfiniteTransition(label = "spinner")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(720, easing = LinearEasing)),
            label = "spinnerAngle",
        )
        value
    }
    Canvas(modifier.size(size)) {
        val strokePx = stroke.toPx()
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 300f,
            useCenter = false,
            style = Stroke(width = strokePx),
        )
    }
}

/** A lucide icon spun 1s/turn (the Loader treatment). */
@Composable
fun SpinningIcon(icon: ImageVector, tint: Color, size: Dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val reduced = LocalReducedMotion.current
    val angle: Float = if (reduced) 0f else {
        val transition = rememberInfiniteTransition(label = "loader")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
            label = "loaderAngle",
        )
        value
    }
    Icon(
        icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = angle },
    )
}

/** Plain status dot. */
@Composable
fun StatusDot(color: Color, size: Dp = 6.4.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** Waiting dot: violet dot plus a 2s expanding, fading ping halo. */
@Composable
fun WaitingPingDot(color: Color, dotSize: Dp = 6.4.dp, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val haloSize = dotSize * 2.4f
    Box(modifier.size(haloSize), contentAlignment = Alignment.Center) {
        if (!reduced) {
            val transition = rememberInfiniteTransition(label = "ping")
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
                label = "pingProgress",
            )
            Box(
                Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        val scale = 1f + progress * (haloSize / dotSize - 1f)
                        scaleX = scale
                        scaleY = scale
                        alpha = (1f - progress) * 0.5f
                    }
                    .background(color, CircleShape),
            )
        }
        Box(Modifier.size(dotSize).background(color, CircleShape))
    }
}
