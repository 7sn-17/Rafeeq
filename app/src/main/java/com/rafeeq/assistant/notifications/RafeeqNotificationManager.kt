package com.rafeeq.assistant.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rafeeq.assistant.MainActivity
import com.rafeeq.assistant.R
import kotlin.math.abs

/**
 * Verified outcome of a long-running background task, reported to the user as a notification.
 * [succeeded], [failed] and [canceled] come from the real operation, never from assumed values.
 */
data class BackgroundTaskSummary(
    val taskId: String,
    val taskName: String,
    val succeeded: Int,
    val failed: Int,
    val canceled: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Single place for Rafeeq's notification channels and background-task result notifications.
 *
 * The Android system [NotificationManager] and this project-specific helper have deliberately
 * different names so call sites do not confuse the two.
 */
class RafeeqNotificationManager(context: Context) {

    private val appContext = context.applicationContext

    /** Creates the two app channels. Safe to call repeatedly; existing channels are untouched. */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return

        // createNotificationChannel is idempotent, but we still guard so a channel is only ever
        // created when it is genuinely missing, avoiding any chance of resetting user choices.
        if (manager.getNotificationChannel(SERVICE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    "خدمة رفيق",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "إشعارات تشغيل خدمة رفيق."
                    setShowBadge(false)
                }
            )
        }
        if (manager.getNotificationChannel(TASKS_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    TASKS_CHANNEL_ID,
                    "إشعارات المهام",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "نتائج المهام التي ينفذها رفيق."
                    setShowBadge(true)
                }
            )
        }
    }

    /**
     * Posts a verified task-result notification. Returns true only when the notification was
     * actually handed to the system; returns false when Android blocked it, so callers never
     * claim a notification was shown when it was not.
     */
    fun notifyTaskCompletion(summary: BackgroundTaskSummary): Boolean {
        if (!notificationsAllowed()) {
            Log.i(TAG, "Task notification suppressed (permission denied or channel disabled)")
            return false
        }
        ensureChannels()

        val copy = describe(summary) ?: return false
        val notificationId = TASK_NOTIFICATION_ID_BASE + (abs(summary.taskId.hashCode()) % 1000)

        val notification = NotificationCompat.Builder(appContext, TASKS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(copy.title)
            .setContentText(copy.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(summary.taskId.hashCode()))
            .build()

        return try {
            NotificationManagerCompat.from(appContext).notify(notificationId, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not post task notification", e)
            false
        }
    }

    /** True only when notification permission is granted and neither the app nor the channel is off. */
    private fun notificationsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return false

        // Respect a channel the user has explicitly turned off.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = appContext.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(TASKS_CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }
        return true
    }

    /** Maps a real result to accurate Arabic copy; null means "nothing worth showing". */
    private fun describe(summary: BackgroundTaskSummary): TaskCopy? {
        val succeeded = summary.succeeded.coerceAtLeast(0)
        val failed = summary.failed.coerceAtLeast(0)

        return when {
            summary.canceled -> TaskCopy(
                title = "تم إيقاف عملية ${summary.taskName}",
                body = if (succeeded > 0) {
                    "تم إيقاف العملية. تم تنفيذ $succeeded بنجاح قبل الإيقاف."
                } else {
                    "تم إيقاف العملية قبل إنجاز أي عنصر."
                }
            )
            succeeded == 0 && failed > 0 -> TaskCopy(
                title = "تعذّر إكمال ${summary.taskName}",
                body = "تعذّر تنفيذ $failed عنصراً. لم تكتمل أي عملية."
            )
            failed > 0 -> TaskCopy(
                title = "اكتمل ${summary.taskName} جزئيًا",
                body = "تم تنفيذ $succeeded بنجاح، وتعذّر تنفيذ $failed."
            )
            succeeded > 0 -> TaskCopy(
                title = "اكتملت عملية ${summary.taskName}",
                body = "تم تنفيذ $succeeded بنجاح."
            )
            else -> null
        }
    }

    private fun openAppIntent(requestCode: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private data class TaskCopy(val title: String, val body: String)

    companion object {
        private const val TAG = "RafeeqNotifications"

        /** Stable channel IDs; the service channel is shared with RafeeqBackgroundService. */
        const val SERVICE_CHANNEL_ID = "rafeeq_background_channel"
        const val TASKS_CHANNEL_ID = "rafeeq_tasks_channel"

        private const val TASK_NOTIFICATION_ID_BASE = 2000
    }
}