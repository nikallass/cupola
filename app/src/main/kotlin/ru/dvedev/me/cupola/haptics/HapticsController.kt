package ru.dvedev.me.cupola.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Score → vibration during a session (SPEC §6.2, §15.5, T-057).
 *
 * With amplitude control: 100 ms segments whose amplitude is `30 + 225·score`, each
 * starting and ending at 0 for LRA motors. Without it (KENSHI E11): 40 ms pulses every
 * `600 − 500·score` ms. Nothing below score 0.2.
 */
class HapticsController(context: Context, private val scope: CoroutineScope) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    val available: Boolean get() = vibrator?.hasVibrator() == true
    val hasAmplitudeControl: Boolean get() = vibrator?.hasAmplitudeControl() == true

    private var job: Job? = null

    /** [score] is polled every tick; [enabled] gates the whole loop. */
    fun start(score: () -> Double, enabled: () -> Boolean) {
        if (!available) return
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                if (!enabled()) { delay(200); continue }
                val s = score().coerceIn(0.0, 1.0)
                if (s < MIN_SCORE) { delay(100); continue }
                if (hasAmplitudeControl) {
                    val amp = (30 + 225 * s).roundToInt().coerceIn(1, 255)
                    vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(20, 60, 20), intArrayOf(amp / 3, amp, amp / 3), -1))
                    delay(100)
                } else {
                    vibrator?.vibrate(VibrationEffect.createOneShot(PULSE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
                    delay((600 - 500 * s).toLong().coerceAtLeast(PULSE_MS + 20))
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        vibrator?.cancel()
    }

    companion object {
        const val MIN_SCORE = 0.2
        const val PULSE_MS = 40L
    }
}
