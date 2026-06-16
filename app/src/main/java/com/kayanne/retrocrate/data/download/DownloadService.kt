package com.kayanne.retrocrate.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kayanne.retrocrate.MainActivity
import com.kayanne.retrocrate.domain.model.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// Keeps active downloads alive when the user leaves the app or the screen turns off. A plain
// background coroutine would be frozen by Doze / background limits; a foreground service plus a
// partial wake lock and a Wi-Fi lock keeps the CPU and radio running until the transfer finishes.
//
// The actual download work still lives in DownloadCoordinator — this service just holds the OS
// awake, shows progress, and stops itself the moment nothing is downloading.
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(notificationFor(DownloadCoordinator.downloads.value))
        if (observer == null) {
            observer = scope.launch {
                DownloadCoordinator.downloads.collectLatest { downloads ->
                    val active = downloads.values.count {
                        it is DownloadState.InProgress || it is DownloadState.Queued
                    }
                    if (active == 0) {
                        stopSelf()
                    } else {
                        notificationManager().notify(NOTIFICATION_ID, notificationFor(downloads))
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun notificationFor(downloads: Map<String, DownloadState>): Notification {
        val inProgress = downloads.values.filterIsInstance<DownloadState.InProgress>()
        val active = downloads.values.count {
            it is DownloadState.InProgress || it is DownloadState.Queued
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (active == 1) "Downloading 1 game" else "Downloading $active games")
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val single = inProgress.singleOrNull()
        val total = single?.bytesTotal
        if (single != null && total != null && total > 0) {
            val pct = ((single.bytesDone * 100) / total).toInt().coerceIn(0, 100)
            builder.setContentText("$pct%  ·  ${formatBytes(single.bytesDone)} / ${formatBytes(total)}")
            builder.setProgress(100, pct, false)
        } else {
            builder.setContentText("Working…")
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun acquireLocks() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RetroCrate:download").apply {
            setReferenceCounted(false)
            acquire(MAX_LOCK_MS)
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RetroCrate:download").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Active game downloads" }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "retrocrate_downloads"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_LOCK_MS = 6L * 60 * 60 * 1000 // safety cap; released when idle anyway

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "%.0f KB".format(kb)
            val mb = kb / 1024.0
            if (mb < 1024) return "%.1f MB".format(mb)
            return "%.2f GB".format(mb / 1024.0)
        }
    }
}
