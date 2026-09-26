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
 * to feel like pressing a physical button: a sharp QUICK_RISE on press, a deep
 * THUD on release — both at maximum scale so the key bites back.
 */
class KeyHaptics(private val vibrator: Vibrator?) {
    fun press() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE)) {
                v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 1.0f)
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
            if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f)
                        .compose()
                )
            } else {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 45, 25, 45), -1))
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