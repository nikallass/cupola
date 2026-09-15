package ru.dvedev.me.cupola.testdata

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Minimal 16-bit PCM mono WAV writer for listening to synthetic test signals. */
object Wav {
    fun write(file: File, signal: DoubleArray, sampleRate: Int = Signals.SAMPLE_RATE) {
        val dataBytes = signal.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(1) // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)
        buf.putShort(2)
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataBytes)
        for (v in signal) buf.putShort((v.coerceIn(-1.0, 1.0) * 32767.0).roundToInt().toShort())
        file.parentFile?.mkdirs()
        DataOutputStream(FileOutputStream(file)).use { it.write(buf.array()) }
    }
}
