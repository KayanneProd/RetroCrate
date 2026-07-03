package com.kayanne.retrocrate.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.kayanne.retrocrate.MainActivity
import com.kayanne.retrocrate.domain.model.Game

// One-shot "your download finished" notifications, separate from DownloadService's ongoing progress
// notification. The user starts a download and walks away (screen off, playing another game), so a
// snackbar they'll never see isn't enough — this posts a real, dismissible notification the moment a
// download+extraction completes or fails. Its own DEFAULT-importance channel so it actually alerts
// (the progress channel is LOW/silent). No content is sent anywhere — it's a local notification.
object DownloadNotifier {

    private const val CHANNEL_ID = "retrocrate_download_results"

    fun notifyComplete(context: Context, game: Game, filename: String?) {
        val text = filename?.let { "$it is ready in your ${game.platform.displayName} folder." }
            ?: "${game.title} is ready in your ${game.platform.displayName} folder."
        post(context, game, "Download complete", text)
    }

    fun notifyFailed(context: Context, game: Game, reason: String) {
        post(context, game, "Download failed", reason)
    }

    private fun post(context: Context, game: Game, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("${game.title} · $text")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${game.title}\n$text"))
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // A stable per-game id (offset well past the ongoing progress notification) so results for
        // different games stack instead of overwriting each other.
        manager.notify(RESULT_ID_BASE + (game.id.hashCode() and 0xFFFF), notification)
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Download results",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Completed and failed downloads" }
        manager.createNotificationChannel(channel)
    }

    private const val RESULT_ID_BASE = 2000
}
