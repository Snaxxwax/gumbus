package com.cheeseschool.game

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

class HapticManager(private val context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Crisp, tactile pulse when an inventory item is used (e.g. Catnip, Cheese-Soda, Zesty Bar).
     */
    fun performItemUseHaptic(view: View? = null) {
        try {
            view?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        } catch (_: Throwable) {}
        vibratePattern(longArrayOf(0, 35), intArrayOf(0, 220), 40)
    }

    /**
     * Snappy click when picking up an item or buying from the vending machine.
     */
    fun performItemPickupHaptic(view: View? = null) {
        try {
            view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Throwable) {}
        vibratePattern(longArrayOf(0, 22), intArrayOf(0, 180), 25)
    }

    /**
     * Alerting tactile pulse when a math question / notebook is encountered.
     */
    fun performMathQuestionHaptic(view: View? = null) {
        try {
            view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        } catch (_: Throwable) {}
        vibratePattern(longArrayOf(0, 45, 40, 70), intArrayOf(0, 230, 0, 255), 90)
    }

    /**
     * Heavy vibrating jolt when a math problem is answered incorrectly.
     */
    fun performMathWrongHaptic(view: View? = null) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view?.performHapticFeedback(HapticFeedbackConstants.REJECT)
            } else {
                view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        } catch (_: Throwable) {}
        vibratePattern(longArrayOf(0, 70, 50, 120), intArrayOf(0, 255, 0, 255), 180)
    }

    /**
     * Warning buzz for inventory full or unable to buy.
     */
    fun performWarningHaptic(view: View? = null) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view?.performHapticFeedback(HapticFeedbackConstants.REJECT)
            } else {
                view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        } catch (_: Throwable) {}
        vibratePattern(longArrayOf(0, 30, 40, 30), intArrayOf(0, 190, 0, 190), 60)
    }

    private fun vibratePattern(timings: LongArray, amplitudes: IntArray, fallbackMs: Long) {
        try {
            if (vibrator?.hasVibrator() != true) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(fallbackMs)
            }
        } catch (_: Throwable) {
            // Guard against devices with restricted or absent haptic hardware
        }
    }
}
