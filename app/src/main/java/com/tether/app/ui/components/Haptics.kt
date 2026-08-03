package com.tether.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Strong, mechanical haptic feedback for tactile controls. Used by TetherKey
 * to feel like pressing a physical button: a sharp THUD on press, a softer
 * QUICK_FALL on release.
 */
class KeyHaptics(private val vibrator: Vibrator?) {
    fun press() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.8f)
                        .compose()
                )
            } else {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 35, 20, 35), -1))
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(longArrayOf(0, 35, 20, 35), -1)
        }
    }

    fun release() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)) {
                v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.4f)
                        .compose()
                )
            }
        }
    }
}

@Composable
fun rememberKeyHaptics(): KeyHaptics {
    val context = LocalContext.current
    return remember {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        KeyHaptics(vibrator)
    }
}