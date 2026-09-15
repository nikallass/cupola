package ru.dvedev.me.cupola.dsp.trace

import ru.dvedev.me.cupola.dsp.Analyzer
import ru.dvedev.me.cupola.dsp.AnalyzerConfig
import ru.dvedev.me.cupola.dsp.pitch.PitchEstimate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test

/**
 * Not a check: prints the pitch tracker's decisions for a 16-bit mono 48 kHz WAV named by
 * the CUPOLA_TRACE_WAV environment variable (skipped when unset). Output: one line per
 * 5th voiced frame — time, YIN, tracked result, top comb candidates, gate, overtones.
 */
class WavTraceTest {
    @Test
    fun `trace a wav`() {
        val path = System.getenv("CUPOLA_TRACE_WAV") ?: return
        val bytes = File(path).readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // find the data chunk
        var pos = 12
        var dataOff = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = bb.getInt(pos + 4)
            if (id == "data") { dataOff = pos + 8; dataLen = len; break }
            pos += 8 + len + (len and 1)
        }
        val n = dataLen / 2
        val signal = DoubleArray(n) { bb.getShort(dataOff + 2 * it) / 32768.0 }
        val analyzer = Analyzer(AnalyzerConfig(sampleRate = 48_000))
        var raw = PitchEstimate.NONE
        analyzer.pitchTrace = { r, _ -> raw = r }
        var i = 0
        val sb = StringBuilder()
        analyzer.push(signal) { m, sp ->
            if (m.voice && ++i % 5 == 0) {
                val t = analyzer.pitchTracker
                sb.append("T %6.2f yin %6.1f/%.2f -> %6.1f/%.2f trk %6.1f/%2.0f %-5s r%d ov=%d |".format(m.timeSec, raw.f0Hz, raw.confidence, m.f0Hz, m.confidence, t.trackedHz, t.trackStrength, t.lastDecision, t.yinReject, m.overtoneCount))
                for (j in 0 until t.candidateCount) sb.append(" %.0f:%.0f/%d".format(t.candidates[j].hz, t.candidates[j].score, t.candidates[j].count))
                sb.append('\n')
            }
        }
        println(sb)
    }
}
