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

    /** Last progress percentage posted per task, used to throttle notification updates. */
    private val lastProgressPercent = java.util.concurrent.ConcurrentHashMap<String, Int>()

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
        // A finished task supersedes any progress notification for the same task.
        lastProgressPercent.remove(summary.taskId)
        val notificationId = taskNotificationId(summary.taskId)

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

    /**
     * Announces that [taskName] has started. Resets any progress previously recorded for the
     * task so a re-run reports from zero again. Returns true only when it was actually shown.
     */
    fun notifyTaskStart(taskId: String, taskName: String): Boolean {
        lastProgressPercent.remove(taskId)
        if (!notificationsAllowed()) return false
        ensureChannels()

        val notification = NotificationCompat.Builder(appContext, TASKS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(taskName)
            .setContentText("جاري التنفيذ...")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(taskId.hashCode()))
            .build()

        return post(taskId, notification, "start")
    }

    /**
     * Updates the progress notification for [taskId]. Reports are throttled to at most one per
     * [PROGRESS_STEP_PERCENT] points so a large batch does not flood the notification shade.
     */
    fun notifyTaskProgress(taskId: String, taskName: String, done: Int, total: Int): Boolean {
        if (total <= 0) return false
        if (!notificationsAllowed()) return false
        val safeDone = done.coerceIn(0, total)
        val percent = safeDone * 100 / total
        if (!shouldPostProgress(taskId, percent)) return false
        ensureChannels()

        val notification = NotificationCompat.Builder(appContext, TASKS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("جاري $taskName")
            .setContentText("تم إنجاز $safeDone من $total")
            .setProgress(total, safeDone, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(taskId.hashCode()))
            .build()

        return post(taskId, notification, "progress")
    }

    /**
     * Removes any progress/completion notification for [taskId]. Used when an operation finishes
     * with nothing to report, so an ongoing progress notification is not left behind.
     */
    fun cancelTask(taskId: String) {
        lastProgressPercent.remove(taskId)
        try {
            NotificationManagerCompat.from(appContext).cancel(taskNotificationId(taskId))
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel the task notification", e)
        }
    }

    private fun post(taskId: String, notification: android.app.Notification, kind: String): Boolean =
        try {
            NotificationManagerCompat.from(appContext).notify(taskNotificationId(taskId), notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not post $kind notification", e)
            false
        }

    private fun shouldPostProgress(taskId: String, percent: Int): Boolean {
        val previous = lastProgressPercent[taskId]
        if (previous != null && percent < 100 && percent - previous < PROGRESS_STEP_PERCENT) {
            return false
        }
        lastProgressPercent[taskId] = percent
        return true
    }

    private fun taskNotificationId(taskId: String): Int =
        TASK_NOTIFICATION_ID_BASE + (abs(taskId.hashCode()) % 1000)

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

        /** Minimum percentage change between two progress notifications for the same task. */
        private const val PROGRESS_STEP_PERCENT = 10
    }
}