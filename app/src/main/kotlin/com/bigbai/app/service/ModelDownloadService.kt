package com.bigbai.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.bigbai.app.BigBAiApplication
import com.bigbai.app.Notifications
import com.bigbai.app.R
import com.bigbai.llm.DownloadOutcome
import com.bigbai.llm.ModelDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground download service.
 *
 * Downloads are long-running and must survive the user leaving the screen; a
 * dataSync foreground service with a low-importance notification is the Android
 * 12+ supported way to do that. The service never starts on boot and stops as
 * soon as the transfer completes or is paused.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as BigBAiApplication).container
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
        when (intent?.action) {
            ACTION_START -> {
                if (modelId == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val variant = container.catalog.byId(modelId)
                if (variant == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundNotification(variant.family, 0)
                scope.launch {
                    val outcome = container.downloader.download(variant) { downloaded, total ->
                        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                        container.notifications.download(
                            title = variant.family,
                            text = "${ModelDownloadManager.formatBytes(downloaded)} / " +
                                ModelDownloadManager.formatBytes(total),
                            progress = percent,
                        )
                    }
                    when (outcome) {
                        is DownloadOutcome.Success -> {
                            container.notifications.download(
                                title = variant.family,
                                text = contextString(R.string.notification_download_done),
                                progress = 100,
                                ongoing = false,
                            )
                        }
                        DownloadOutcome.Paused -> {
                            container.notifications.download(
                                title = variant.family,
                                text = contextString(R.string.notification_download_paused),
                                progress = null,
                                ongoing = false,
                            )
                        }
                        is DownloadOutcome.Failure -> {
                            container.notifications.alert(
                                title = contextString(R.string.notification_download_failed),
                                message = outcome.message,
                            )
                        }
                    }
                    ServiceCompat.stopForeground(this@ModelDownloadService, ServiceCompat.STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
            ACTION_PAUSE -> {
                modelId?.let { container.downloader.pause(it) }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf()
            }
            ACTION_CANCEL_NOTIFICATION -> {
                container.notifications.cancelDownload()
                stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification(title: String, progress: Int) {
        val notification = androidx.core.app.NotificationCompat.Builder(this, Notifications.CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(contextString(R.string.notification_download_starting))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0)
            .build()
        ServiceCompat.startForeground(
            this,
            Notifications.NOTIFICATION_DOWNLOAD,
            notification,
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun contextString(resource: Int): String = getString(resource)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.bigbai.app.download.START"
        const val ACTION_PAUSE = "com.bigbai.app.download.PAUSE"
        const val ACTION_CANCEL_NOTIFICATION = "com.bigbai.app.download.CANCEL"
        const val EXTRA_MODEL_ID = "model_id"

        fun start(context: Context, modelId: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context, modelId: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            context.startService(intent)
        }
    }
}
