package ru.dvedev.me.cupola.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * «Дать тон» (owner 2026‑09‑16): plays the pinned note through the speaker as a piano-like
 * note rather than a beep, so it is pleasant to tune to. Additive synthesis of what makes a
 * piano sound like one:
 *
 * - **inharmonic partials** — stiff steel strings put partial k at `k·f0·√(1 + B·k²)`
 *   ([inharmonicity] B ≈ 0.0004 in the middle register), the slight sharpness of the upper
 *   partials is the metallic "life" of the tone;
 * - **partials decay faster the higher they are** — partial k dies with a time constant
 *   `τ₁ / (1 + 0.12·k^1.6)`, so the note starts bright and mellows;
 * - **hammer attack** — a 15 ms raised-cosine rise plus a quiet burst of noise for the hammer's thump;
 * - **two strings per note**, detuned by ±1.5 ¢, whose beating gives the sustain its shimmer.
 */
class ReferenceTone(private val sampleRate: Int = 48_000) {
    @Volatile private var track: AudioTrack? = null

    var inharmonicity: Double = 0.0004
    var partials: Int = 14
    var seconds: Double = 2.4
    /** Decay time constant of the fundamental, seconds. */
    var fundamentalDecay: Double = 1.1

    fun play(hz: Double) {
        stop()
        val pcm = synthesize(hz)
        val n = pcm.size
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(n * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        at.write(pcm, 0, n)
        track = at
        at.play()
        thread(name = "cupola-tone", isDaemon = true) {
            Thread.sleep((seconds * 1000).toLong() + 100)
            if (track === at) { runCatching { at.stop(); at.release() }; track = null }
        }
    }

    /** The note as 16‑bit PCM; pure Kotlin so it can be rendered and inspected off-device. */
    fun synthesize(hz: Double): ShortArray {
        val n = (seconds * sampleRate).toInt()
        val out = DoubleArray(n)
        val nyquist = sampleRate / 2.0
        val strings = doubleArrayOf(2.0.pow(-1.5 / 1200), 2.0.pow(1.5 / 1200))
        for (k in 1..partials) {
            val fk = k * hz * sqrt(1 + inharmonicity * k * k)
            if (fk >= nyquist * 0.9) break
            // spectral tilt with a softer 2nd/3rd partial than a sawtooth and a little randomness per partial
            val amp = 1.0 / k.toDouble().pow(1.25) * (if (k % 2 == 0) 0.85 else 1.0)
            val tau = fundamentalDecay / (1 + 0.12 * k.toDouble().pow(1.6))
            for (s in strings) {
                val w = 2 * PI * fk * s
                val phase = Random(k * 31 + s.hashCode()).nextDouble() * 2 * PI
                for (i in 0 until n) {
                    val t = i.toDouble() / sampleRate
                    out[i] += amp * exp(-t / tau) * sin(w * t + phase)
                }
            }
        }
        // hammer: a 15 ms raised-cosine attack (a linear 4 ms ramp clicked through the
        // speakers — owner 2026‑09‑16) plus a quiet, smoothly windowed thump of noise
        val attack = (0.015 * sampleRate).toInt()
        val rnd = Random(7)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val a = if (i < attack) 0.5 - 0.5 * kotlin.math.cos(PI * i / attack) else 1.0
            val thump = if (i < attack) 0.06 * (rnd.nextDouble() * 2 - 1) * sin(PI * i / attack) * exp(-t / 0.006) else 0.0
            out[i] = out[i] * a + thump
        }
        // release over the last 60 ms so the sample never ends on a click
        val rel = (0.06 * sampleRate).toInt()
        for (i in n - rel until n) out[i] *= (n - i).toDouble() / rel
        var peak = 1e-9
        for (v in out) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
        val gain = 0.7 * Short.MAX_VALUE / peak
        return ShortArray(n) { (out[it] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
    }

    fun stop() {
        track?.let { runCatching { it.stop(); it.release() } }
        track = null
    }
}
