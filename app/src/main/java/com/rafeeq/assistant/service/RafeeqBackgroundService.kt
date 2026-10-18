package com.rafeeq.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rafeeq.assistant.MainActivity
import com.rafeeq.assistant.R
import com.rafeeq.assistant.notifications.RafeeqNotificationManager

/**
 * Keeps Rafeeq alive with an interactive foreground notification.
 *
 * Important honesty rule: this service does not capture audio itself. Microphone capture happens
 * only in the foreground UI through the permission-aware speech pipeline. The notification
 * therefore says "رفيق جاهز لمساعدتك", never "يستمع الآن".
 */
class RafeeqBackgroundService : Service() {

    private val notifications by lazy { RafeeqNotificationManager(this) }

    override fun onCreate() {
        super.onCreate()
        // Channels must exist before the foreground notification is posted.
        notifications.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // This is a real stop of the service's own work, not just a dismissal, and it
                // removes the ongoing notification as part of the normal service lifecycle.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OPEN -> {
                // Deliver the user to the app. The content intent does the same on tap; an
                // explicit action keeps the two PendingIntents distinct.
                openApp()
                return START_STICKY
            }
        }

        return try {
            startForeground(NOTIFICATION_ID, buildNotification())
            START_STICKY
        } catch (e: SecurityException) {
            // e.g. a microphone-type service launched without RECORD_AUDIO (Android 14+).
            Log.e(TAG, "Unable to start foreground service", e)
            stopSelf()
            START_NOT_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected failure starting foreground service", e)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun openApp() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open Rafeeq from notification", e)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingFlags()
        )

        // A distinct action identifier + request code keeps the Stop PendingIntent from
        // colliding with the Open one.
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, RafeeqBackgroundService::class.java).apply {
                action = ACTION_STOP
            },
            pendingFlags()
        )

        return NotificationCompat.Builder(this, RafeeqNotificationManager.SERVICE_CHANNEL_ID)
            .setContentTitle("رفيق")
            .setContentText("رفيق جاهز لمساعدتك")
            .setStyle(NotificationCompat.BigTextStyle().bigText("رفيق جاهز لمساعدتك"))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .addAction(0, "فتح", contentIntent)
            .addAction(0, "إيقاف", stopIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        private const val TAG = "RafeeqBgService"

        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.rafeeq.assistant.START_BACKGROUND"
        const val ACTION_STOP = "com.rafeeq.assistant.STOP_BACKGROUND"
        const val ACTION_OPEN = "com.rafeeq.assistant.OPEN_APP"

        private const val REQUEST_OPEN = 11
        private const val REQUEST_STOP = 12
    }
}