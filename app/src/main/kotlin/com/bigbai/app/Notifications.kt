package com.bigbai.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class Notifications(private val context: Context) {

    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val download = NotificationChannel(
            CHANNEL_DOWNLOAD,
            context.getString(R.string.channel_downloads),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.channel_downloads_desc) }
        val backup = NotificationChannel(
            CHANNEL_BACKUP,
            context.getString(R.string.channel_backup),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.channel_backup_desc) }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.channel_alerts_desc) }
        manager.createNotificationChannels(listOf(download, backup, alerts))
    }

    fun canNotify(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun download(
        title: String,
        text: String,
        progress: Int?,
        ongoing: Boolean = true,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (progress != null) {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_DOWNLOAD, builder.build())
    }

    fun backup(success: Boolean, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_BACKUP)
            .setSmallIcon(if (success) R.drawable.ic_stat_cloud_done else R.drawable.ic_stat_error)
            .setContentTitle(context.getString(if (success) R.string.notification_backup_done else R.string.notification_backup_failed))
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        NotificationManagerCompat.from(context).notify(NOTIFICATION_BACKUP, builder.build())
    }

    fun alert(title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_error)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ALERT, builder.build())
    }

    fun cancelDownload() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_DOWNLOAD)

    companion object {
        const val CHANNEL_DOWNLOAD = "downloads"
        const val CHANNEL_BACKUP = "backup"
        const val CHANNEL_ALERTS = "alerts"
        const val NOTIFICATION_DOWNLOAD = 1001
        const val NOTIFICATION_BACKUP = 1002
        const val NOTIFICATION_ALERT = 1003
    }
}
