package ru.dvedev.me.cupola.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Сохранить лог» (owner 2026‑09‑16): the app's own logcat (an app may read its own process
 * lines without any permission) with a device header, written to a file the user picks.
 * Nothing leaves the device unless the user shares the file.
 */
object LogExport {
    fun suggestedName(): String = "cupola-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt"

    fun build(context: Context): String {
        val sb = StringBuilder()
        val pm = context.packageManager
        val version = runCatching { pm.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        sb.append("Cupola ").append(version).append('\n')
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n")
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append('\n')
        sb.append("time: ").append(Date()).append("\n\n")
        runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
            BufferedReader(InputStreamReader(p.inputStream)).use { r -> r.lineSequence().forEach { sb.append(it).append('\n') } }
            p.waitFor()
        }.onFailure { sb.append("logcat unavailable: ").append(it).append('\n') }
        return sb.toString()
    }

    fun writeTo(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(build(context).toByteArray()) } != null
    }.getOrDefault(false)
}
