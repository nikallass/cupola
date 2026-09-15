package ru.dvedev.me.cupola.dsp

/**
 * Default framing parameters of the analysis pipeline (SPEC §4).
 *
 * The pipeline works on mono PCM at [SAMPLE_RATE] (falls back to
 * [FALLBACK_SAMPLE_RATE] if the device refuses 48 kHz), analysing Hann windows of
 * [FFT_SIZE] samples every [HOP_SIZE] samples, i.e. every 10 ms at 48 kHz.
 */
object AudioFormatDefaults {
    const val SAMPLE_RATE: Int = 48_000
    const val FALLBACK_SAMPLE_RATE: Int = 44_100
    const val FFT_SIZE: Int = 2048
    const val HOP_SIZE: Int = 480

    /** Hop duration in seconds at [sampleRate]. */
    fun hopSeconds(sampleRate: Int = SAMPLE_RATE): Double = HOP_SIZE.toDouble() / sampleRate
}
