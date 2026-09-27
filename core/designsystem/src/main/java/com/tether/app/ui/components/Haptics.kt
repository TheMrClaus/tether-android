package com.tether.app.ui.components

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The moments the app vibrates, mapped to the web's. The web has exactly ONE haptic moment:
 * `navigator.vibrate(7)` each time a conversation-timeline scrub crosses into a new story point
 * (components/conversation-timeline.tsx:190-205). The app keeps its richer native feel, but every
 * haptic belongs to a named moment:
 *
 * | Moment                 | Web                         | App                                   |
 * |------------------------|-----------------------------|---------------------------------------|
 * | [HapticMoment.ScrubStep] | vibrate(7) per new scrub index | PRIMITIVE_TICK (fallback 20ms pulse) |
 * | [HapticMoment.KeyDown]   | none (the key's CSS travel is the feedback) | PRIMITIVE_QUICK_RISE, full scale |
 * | [HapticMoment.KeyUp]     | none                          | PRIMITIVE_THUD, full scale           |
 *
 * KeyDown/KeyUp are native additions (a physical key bites back); they fire only on an ENABLED
 * key's press edge and release edge — never on composition, never for a disabled key.
 */
enum class HapticMoment { ScrubStep, KeyDown, KeyUp }

/** Plays a [HapticMoment]. [LocalTetherHaptics] lets tests (and previews) observe the moments. */
open class TetherHaptics(private val vibrator: Vibrator?) {
    open fun perform(moment: HapticMoment) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val (primitive, fallback) = when (moment) {
            HapticMoment.ScrubStep -> VibrationEffect.Composition.PRIMITIVE_TICK to longArrayOf(0, 20, 15, 20)
            HapticMoment.KeyDown -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE to longArrayOf(0, 35, 20, 35)
            HapticMoment.KeyUp -> VibrationEffect.Composition.PRIMITIVE_THUD to longArrayOf(0, 45, 25, 45)
        }
        if (v.areAllPrimitivesSupported(primitive)) {
            v.vibrate(VibrationEffect.startComposition().addPrimitive(primitive, 1.0f).compose())
        } else {
            v.vibrate(VibrationEffect.createWaveform(fallback, -1))
        }
    }

    fun keyDown() = perform(HapticMoment.KeyDown)
    fun keyUp() = perform(HapticMoment.KeyUp)

    /** The web's single haptic: a timeline scrub crossed into a new story point. */
    fun scrubStep() = perform(HapticMoment.ScrubStep)
}

/** Null: use the device vibrator. Provide a [TetherHaptics] subclass to intercept moments. */
val LocalTetherHaptics = compositionLocalOf<TetherHaptics?> { null }

@Composable
fun rememberTetherHaptics(): TetherHaptics {
    LocalTetherHaptics.current?.let { return it }
    val context = LocalContext.current
    return remember(context) { TetherHaptics(defaultVibrator(context)) }
}

private fun defaultVibrator(context: Context): Vibrator? =
    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator

/** Pre-T3.3 name for the key's haptics; kept as an adapter for existing call sites. */
class KeyHaptics internal constructor(private val haptics: TetherHaptics) {
    fun press() = haptics.keyDown()
    fun release() = haptics.keyUp()
}

@Composable
fun rememberKeyHaptics(): KeyHaptics {
    val haptics = rememberTetherHaptics()
    return remember(haptics) { KeyHaptics(haptics) }
}
