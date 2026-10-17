package com.rafeeq.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rafeeq.assistant.MainActivity
import com.rafeeq.assistant.R

class RafeeqBackgroundService : Service() {
    companion object {
        private const val TAG = "RafeeqBgService"
        const val CHANNEL_ID = "rafeeq_background_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.rafeeq.assistant.START_BACKGROUND"
        const val ACTION_STOP = "com.rafeeq.assistant.STOP_BACKGROUND"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return try {
            startForeground(NOTIFICATION_ID, buildNotification())
            START_STICKY
        } catch (e: Exception) {
            // e.g. SecurityException when a microphone-type service is launched
            // without RECORD_AUDIO (Android 14+). Stand down instead of crashing.
            Log.e(TAG, "Unable to start foreground service", e)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rafeeq Background",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Rafeeq running in background"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("رفيق")
            .setContentText("رفيق يعمل في الخلفية")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
