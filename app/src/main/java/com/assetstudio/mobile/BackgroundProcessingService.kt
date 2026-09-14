package com.assetstudio.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the AssetStudio process alive while a user-started heavy operation is running.
 *
 * The actual parsing/export coroutine remains in MainViewModel. This service deliberately
 * owns no AssetStudio state; its job is to raise the process importance and expose progress
 * through a persistent notification while the app is in the background.
 */
class BackgroundProcessingService : Service() {

    companion object {
        const val ACTION_START = "com.assetstudio.mobile.action.START_BACKGROUND_JOB"
        const val ACTION_STOP = "com.assetstudio.mobile.action.STOP_BACKGROUND_JOB"
        const val ACTION_UPDATE = "com.assetstudio.mobile.action.UPDATE_BACKGROUND_JOB"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "assetstudio_processing"
        private const val NOTIFICATION_ID = 1001

        fun start(context: android.content.Context, title: String = "AssetStudio processing") {
            val intent = Intent(context, BackgroundProcessingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, BackgroundProcessingService::class.java).apply {
                action = ACTION_STOP
            })
        }

        fun update(context: android.content.Context, text: String) {
            context.startService(Intent(context, BackgroundProcessingService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TEXT, text)
            })
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Processing in background…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "AssetStudio processing"
                updateNotification(title, "Processing in background…")
            }
            ACTION_UPDATE -> updateNotification(
                "AssetStudio processing",
                intent.getStringExtra(EXTRA_TEXT) ?: "Processing…"
            )
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AssetStudio background processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while AssetStudio processes files in the background."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AssetStudio")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    private fun updateNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
