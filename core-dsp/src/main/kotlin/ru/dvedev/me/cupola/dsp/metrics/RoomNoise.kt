package ru.dvedev.me.cupola.dsp.metrics

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Room noise profile (owner decision 2026‑09‑15: instead of a personal calibration the
 * app measures the room once — a few seconds of silence — and subtracts it from every
 * spectrum, so noise inside the cupola band earns nothing and noise outside it does not
 * dilute the share). [profileDb] is per FFT bin at [sampleRate]/[fftSize].
 */
data class RoomNoise(
    val sampleRate: Int,
    val fftSize: Int,
    val profileDb: DoubleArray,
    /** Median RMS of the measurement, dBFS. */
    val rmsDbfs: Double,
    val createdAtEpochMs: Long,
) {
    val binHz: Double get() = sampleRate.toDouble() / fftSize

    /** The profile resampled (linearly in dB over frequency) for another FFT size / rate. */
    fun resampled(sampleRate: Int, fftSize: Int): DoubleArray {
        val bins = fftSize / 2 + 1
        if (sampleRate == this.sampleRate && fftSize == this.fftSize && profileDb.size == bins) return profileDb
        val targetHz = sampleRate.toDouble() / fftSize
        return DoubleArray(bins) { b ->
            val pos = b * targetHz / binHz
            val i = pos.toInt().coerceIn(0, profileDb.size - 1)
            val j = (i + 1).coerceAtMost(profileDb.size - 1)
            val f = (pos - i).coerceIn(0.0, 1.0)
            profileDb[i] * (1 - f) + profileDb[j] * f
        }
    }

    companion object {
        /** Louder than this the room is flagged as noisy. */
        const val NOISY_ROOM_DBFS = -45.0
    }
}

/** Collects [seconds] of spectra (mean power per bin) and RMS values into a [RoomNoise]. */
class RoomNoiseSession(
    val hopSeconds: Double,
    val sampleRate: Int,
    val fftSize: Int,
    val seconds: Double = 3.0,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val bins = fftSize / 2 + 1
    private val frames = (seconds / hopSeconds).roundToInt().coerceAtLeast(1)
    private val sumPower = DoubleArray(bins)
    private val rms = ArrayList<Double>(frames)
    private var count = 0

    val done: Boolean get() = count >= frames
    val progress: Double get() = count.toDouble() / frames
    val secondsLeft: Double get() = (frames - count) * hopSeconds

    fun push(spectrumDb: DoubleArray, rmsDbfs: Double) {
        if (done) return
        // digital silence (no signal from the microphone yet) is not a room: skip it, the countdown waits
        if (rmsDbfs < DIGITAL_SILENCE_DBFS) return
        for (b in 0 until minOf(bins, spectrumDb.size)) sumPower[b] += 10.0.pow(spectrumDb[b] / 10.0)
        rms += rmsDbfs
        count++
    }

    fun result(): RoomNoise {
        check(done) { "measurement not finished" }
        val profile = DoubleArray(bins) { 10.0 * log10((sumPower[it] / count).coerceAtLeast(1e-14)) }
        val sorted = rms.sorted()
        return RoomNoise(sampleRate, fftSize, profile, sorted[sorted.size / 2], now())
    }

    fun reset() {
        sumPower.fill(0.0)
        rms.clear()
        count = 0
    }

    val noisy: Boolean get() = done && result().rmsDbfs > RoomNoise.NOISY_ROOM_DBFS

    companion object {
        /** Below this RMS the input is digital zero — not a measurement. */
        const val DIGITAL_SILENCE_DBFS = -150.0
    }
}
