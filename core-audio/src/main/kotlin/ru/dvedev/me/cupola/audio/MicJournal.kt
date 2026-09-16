package ru.dvedev.me.cupola.audio

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-memory journal of microphone events (owner 2026‑09‑16: a OnePlus kept the app silenced
 * for a long time, but its logcat rotates within seconds, so the exported log only held the
 * last 8 s). Every entry also goes to logcat under `CupolaAudio`; «Сохранить лог» writes the
 * whole journal, so the history since the app started survives.
 */
object MicJournal {
    private const val CAPACITY = 400
    private val entries = ArrayDeque<String>(CAPACITY)
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    @Synchronized
    fun add(message: String, warn: Boolean = false) {
        if (warn) Log.w(TAG, message) else Log.i(TAG, message)
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(clock.format(Date()) + "  " + message)
    }

    @Synchronized
    fun dump(): String = entries.joinToString("\n")

    private const val TAG = "CupolaAudio"
}
