package ru.dvedev.me.cupola.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.dvedev.me.cupola.MainActivity
import ru.dvedev.me.cupola.R
import ru.dvedev.me.cupola.appGraph
import ru.dvedev.me.cupola.notation.NoteNames
import ru.dvedev.me.cupola.notation.NotationMode

/**
 * Keeps the microphone alive while a session runs in the background (SPEC §15.5, T-042):
 * a foreground service of type `microphone` with a «Купол слушает · <нота> · Стоп»
 * notification. Started by [ru.dvedev.me.cupola.AppGraph.startSession], stopped with it.
 */
class AnalysisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updater: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            appGraph.stopSession()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(null), type)
        updater?.cancel()
        updater = scope.launch {
            val graph = appGraph
            while (true) {
                delay(1000)
                if (!graph.session.isActive) break
                val m = graph.engine.metrics.value
                val s = graph.settingsState.value
                val note = if (m != null && m.voiced) NoteNames.label(m.note, s.notation.takeIf { it != NotationMode.BOTH } ?: NotationMode.RU, s.accidentals).joined else null
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(note))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        updater?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(note: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AnalysisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (note != null) getString(R.string.notif_listening_note, note) else getString(R.string.notif_listening)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.notif_channel_desc)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "session"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "ru.dvedev.me.cupola.action.STOP_SESSION"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AnalysisService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AnalysisService::class.java))
        }
    }
}
