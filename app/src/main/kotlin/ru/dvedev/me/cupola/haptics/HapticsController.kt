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
 * Cupola → vibration during a session (owner decision 2026‑09‑15: the phone vibrates while
 * the two halves of the cupola arc have met, i.e. `ring ≥ MET`). With amplitude control:
 * a continuous soft waveform whose amplitude follows the ring; without it (KENSHI E11):
 * 40 ms pulses every 150 ms.
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

    /** [ring] is polled every tick; [enabled] gates the whole loop. */
    fun start(ring: () -> Double, enabled: () -> Boolean) {
        if (!available) return
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                if (!enabled()) { delay(200); continue }
                val r = ring().coerceIn(0.0, 1.0)
                if (r < MET) { delay(60); continue }
                if (hasAmplitudeControl) {
                    val amp = (120 + 135 * r).roundToInt().coerceIn(1, 255)
                    vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(30, 90, 30), intArrayOf(amp / 2, amp, amp / 2), -1))
                    delay(150)
                } else {
                    vibrator?.vibrate(VibrationEffect.createOneShot(PULSE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
                    delay(PULSE_MS + 110)
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
        /** The cupola halves meet here; the arc sparkles and points flow (see NoteZone). */
        const val MET = 0.9
        const val PULSE_MS = 40L
    }
}
