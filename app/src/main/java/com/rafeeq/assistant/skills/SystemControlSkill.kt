package com.rafeeq.assistant.skills

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real system-control actions that respect Android's platform restrictions.
 *
 * - Wi-Fi cannot be silently toggled on modern Android, so the relevant Settings panel is opened.
 * - Arbitrary app launching uses a launch intent and rejects unsafe inputs.
 * - A device-wide screenshot requires explicit user consent; that cannot be faked.
 */
class SystemControlSkill(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager

    suspend fun openSystemSetting(setting: String): SkillResult = withContext(Dispatchers.Main) {
        val name = setting.lowercase()
        return@withContext when (name) {
            "wifi" -> openWifiSettings()
            "bluetooth" -> openBluetoothSettings()
            else -> SkillResult.failure("إعداد النظام المطلوب غير مدعوم")
        }
    }

    suspend fun openWifiSettings(): SkillResult = withContext(Dispatchers.Main) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (intent.resolveActivity(packageManager) == null) {
            // Fallback to the plain Wi-Fi settings activity.
            val fallback = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return@withContext tryLaunch(fallback, "تم فتح إعدادات Wi-Fi. أكمل التغيير من إعدادات النظام.")
        }

        tryLaunch(intent, "تم فتح إعدادات Wi-Fi. أكمل التغيير من إعدادات النظام.")
    }

    suspend fun openBluetoothSettings(): SkillResult = withContext(Dispatchers.Main) {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        tryLaunch(intent, "تم فتح إعدادات البلوتوث. أكمل التغيير من إعدادات النظام.")
    }

    /**
     * Opens an installed application by package name. Only launchable apps (MAIN/LAUNCHER)
     * will have a result from [PackageManager.getLaunchIntentForPackage].
     */
    suspend fun openApp(packageName: String): SkillResult = withContext(Dispatchers.Main) {
        if (packageName.isBlank()) {
            return@withContext SkillResult.failure("اسم التطبيق غير صالح.")
        }

        val canonical = packageName.trim()
        if (!isValidPackageName(canonical)) {
            return@withContext SkillResult.failure("اسم التطبيق غير صالح.")
        }

        val launchIntent = try {
            packageManager.getLaunchIntentForPackage(canonical)
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve launch intent for $canonical", e)
            return@withContext SkillResult.failure("التطبيق المطلوب غير مثبت أو لا يمكن فتحه.")
        } ?: return@withContext SkillResult.failure("التطبيق المطلوب غير مثبت أو لا يمكن فتحه.")

        try {
            appContext.startActivity(launchIntent)
            SkillResult.screenOpened("تم فتح التطبيق.")
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No handler for $canonical", e)
            SkillResult.failure("تعذّر فتح التطبيق المطلوب.")
        } catch (e: SecurityException) {
            Log.e(TAG, "Launch of $canonical blocked", e)
            SkillResult.failure("تعذّر فتح التطبيق المطلوب بسبب قيود النظام.")
        }
    }

    /**
     * A true device-wide screenshot requires a MediaProjection consent flow and is not something
     * an ordinary Compose activity can do silently. Rather than faking success, the user is told
     * the exact, honest limitation so they can act.
     */
    suspend fun takeScreenshot(): SkillResult = withContext(Dispatchers.Main) {
        SkillResult.userActionRequired(
            "لقطة شاشة الجهاز تتطلب موافقتك من النظام ولم يتم تفعيلها بعد. " +
                "يمكنك التقاط لقطة شاشة يدوياً باستخدام أزرار جهازك."
        )
    }

    private fun isValidPackageName(candidate: String): Boolean {
        if (candidate.isEmpty()) return false
        // A sensible filter: Android package names match [A-Za-z][A-Za-z0-9_.]* and at least one dot.
        if (!candidate.matches(PACKAGE_PATTERN)) return false
        return candidate.contains('.')
    }

    private fun tryLaunch(intent: Intent, successMessage: String): SkillResult {
        if (intent.resolveActivity(packageManager) == null) {
            return SkillResult.failure("تعذّر فتح إعدادات النظام")
        }
        return try {
            appContext.startActivity(intent)
            SkillResult.screenOpened(successMessage)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Settings activity missing", e)
            SkillResult.failure("تعذّر فتح إعدادات النظام")
        } catch (e: SecurityException) {
            Log.e(TAG, "Launching settings was blocked", e)
            SkillResult.userActionRequired("لا يمكن فتح إعدادات النظام تلقائياً من هنا.")
        }
    }

    private companion object {
        const val TAG = "SystemControlSkill"
        val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_.]*$")
    }
}
