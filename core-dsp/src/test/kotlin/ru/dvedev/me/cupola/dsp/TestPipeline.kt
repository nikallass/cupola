package ru.dvedev.me.cupola.dsp

import ru.dvedev.me.cupola.dsp.fft.PowerSpectrum
import ru.dvedev.me.cupola.dsp.frame.Frame
import ru.dvedev.me.cupola.dsp.frame.Framer
import ru.dvedev.me.cupola.dsp.pitch.PitchEstimate
import ru.dvedev.me.cupola.dsp.pitch.YinPitchDetector

/** Frame → spectrum → pitch, for tests that need per-frame intermediate values. */
class TestPipeline(
    val sampleRate: Int = 48_000,
    val frameSize: Int = AudioFormatDefaults.FFT_SIZE,
    val hop: Int = AudioFormatDefaults.HOP_SIZE,
) {
    val framer = Framer(frameSize, hop, sampleRate)
    val spectrum = PowerSpectrum(frameSize, sampleRate)
    val yin = YinPitchDetector(frameSize)
    val hopSeconds: Double = hop.toDouble() / sampleRate

    class Step(val frame: Frame, val spectrum: PowerSpectrum, val pitch: PitchEstimate, val rmsDb: Double)

    fun run(signal: DoubleArray, block: (Step) -> Unit) {
        framer.push(signal) { f ->
            spectrum.compute(f.windowed)
            val pitch = yin.estimate(f.raw, sampleRate)
            var acc = 0.0
            for (v in f.raw) acc += v * v
            val rmsDb = 10.0 * kotlin.math.log10(acc / f.raw.size + 1e-20)
            block(Step(f, spectrum, pitch, rmsDb))
        }
    }
}
