package com.rafeeq.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rafeeq.assistant.R

class RafeeqBackgroundService : Service() {
    companion object {
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
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("رفيق")
            .setContentText("رفيق يعمل في الخلفية")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
