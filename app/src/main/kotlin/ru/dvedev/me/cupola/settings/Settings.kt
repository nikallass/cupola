package ru.dvedev.me.cupola.settings

import ru.dvedev.me.cupola.audio.AudioSourcePreference
import ru.dvedev.me.cupola.dsp.metrics.RingBand
import ru.dvedev.me.cupola.dsp.metrics.VibratoThresholds
import ru.dvedev.me.cupola.dsp.metrics.VoiceType
import ru.dvedev.me.cupola.dsp.score.ScoreParams
import ru.dvedev.me.cupola.dsp.score.ScoreWeights
import ru.dvedev.me.cupola.notation.Accidentals
import ru.dvedev.me.cupola.notation.NotationMode
import ru.dvedev.me.cupola.notation.Tuning
import ru.dvedev.me.cupola.ui.theme.ThemeMode

enum class Language { SYSTEM, RU, EN }

/** All user settings (SPEC §15.5). Persisted by [SettingsRepository]. */
data class Settings(
    val voiceType: VoiceType = VoiceType.UNSET,
    /** Custom cupola band; used instead of [voiceType]'s band when [useCustomBand]. */
    val customLoHz: Int = 2400,
    val customHiHz: Int = 3200,
    val useCustomBand: Boolean = false,
    val a4Hz: Int = Tuning.DEFAULT_A4_HZ.toInt(),
    /** Global fine tuning, cents (owner 2026‑09‑16: the piano at home sits +10 ¢): shifts the note reference and the reference tone. */
    val tuningCents: Int = 0,
    val notation: NotationMode = NotationMode.BOTH,
    val accidentals: Accidentals = Accidentals.SHARPS,
    val language: Language = Language.SYSTEM,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val logFrequencyAxis: Boolean = true,
    /** Spectrogram contrast, % (owner 2026‑09‑16): a power curve on the level — low shows half-tones, high keeps only peaks. */
    val spectrogramContrast: Int = 100,
    val fftSize: Int = 2048,
    val audioSource: AudioSourcePreference = AudioSourcePreference.AUTO,
    /** Null = device default (phones on, tablets off). */
    val haptics: Boolean? = null,
    val pointsAnimation: Boolean = true,
    val hints: Boolean = true,
    /** Training mode (owner 2026‑09‑16): the top bar with Start/Pause/time/points; off = analysis only, a floating settings button. */
    val trainingMode: Boolean = true,
    // advanced
    val centsOk: Int = 10,
    val centsWarn: Int = 25,
    val confidenceMin: Double = 0.7,
    /** Share of the voice energy in the band that earns the full cupola, %. */
    val ringShareFullPct: Int = 12,
    /** Hump over the flanks that earns the full cupola, dB. */
    val ringHumpFullDb: Int = 6,
    /** Vibrato classification borders (advanced): straight below, tremolo above, wobble outside. */
    val straightMaxCents: Int = 15,
    val vibratoMinHz: Double = 4.0,
    val vibratoMaxHz: Double = 7.5,
    val vibratoMaxCents: Int = 120,
    /** Quiet time the background-noise profile covers, minutes (owner 2026‑09‑16: default 3). */
    val noiseWindowMinutes: Int = 3,
    /** Averaging of the note readouts (cents pin, cents, Hz), milliseconds (default 100). */
    val displayAveragingMs: Int = 100,
    val ringWeight: Double = ScoreWeights.RING,
    val pitchWeight: Double = ScoreWeights.PITCH,
    val steadyWeight: Double = ScoreWeights.STEADY,
    val onboardingDone: Boolean = false,
) {
    /** A4 with the fine tuning applied — what the analyzer and the reference tone use. */
    val effectiveA4Hz: Double
        get() = a4Hz * Math.pow(2.0, tuningCents / 1200.0)

    val band: RingBand
        get() = if (useCustomBand) RingBand(customLoHz.toDouble(), customHiHz.toDouble()) else voiceType.band

    fun vibratoThresholds(): VibratoThresholds = VibratoThresholds(
        straightMaxCents = straightMaxCents.toDouble(),
        vibratoMinHz = vibratoMinHz,
        vibratoMaxHz = vibratoMaxHz,
        vibratoMaxCents = vibratoMaxCents.toDouble(),
    )

    fun scoreParams(): ScoreParams = ScoreParams(
        ringWeight = ringWeight,
        pitchWeight = pitchWeight,
        steadyWeight = steadyWeight,
        confidenceMin = confidenceMin,
        shareFullPct = ringShareFullPct.toDouble(),
        shareZeroPct = ringShareFullPct / 6.0,
        humpFullDb = ringHumpFullDb.toDouble(),
        humpZeroDb = -ringHumpFullDb.toDouble(),
    )

    companion object {
        val FFT_SIZES = listOf(2048, 4096)
        const val CUSTOM_MIN_HZ = 1500
        const val CUSTOM_MAX_HZ = 5000
        const val CUSTOM_STEP_HZ = 50
        const val CUSTOM_MIN_WIDTH_HZ = 200
    }
}
