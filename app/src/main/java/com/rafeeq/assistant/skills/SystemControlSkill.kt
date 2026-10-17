package com.rafeeq.assistant.skills

import android.app.NotificationManager
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real system-control actions that respect Android's platform restrictions.
 *
 * A few rules apply to everything in this class:
 *
 *  - Nothing reports success without verifying the resulting state.
 *  - Toggles Android refuses to let ordinary apps change silently (hotspot, NFC, airplane
 *    mode on modern Android) open the relevant Settings screen and say so, rather than
 *    pretending the setting flipped.
 *  - Missing privileged permissions (WRITE_SETTINGS, notification-policy access) surface as
 *    an Arabic [SkillResult.permissionRequired] telling the user what is needed.
 */
class SystemControlSkill(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager

    /* -------------------------------------------------------------------------------------
     * Generic dispatcher: one entry point per natural-language command family.
     * ----------------------------------------------------------------------------------- */

    /**
     * Maps a Groq tool call such as `{"action":"airplane_mode","state":"on"}` onto a real
     * device operation. Returns a truthful result, including when Android blocks the change.
     */
    suspend fun changeSystemSetting(action: String, state: String): SkillResult =
        withContext(Dispatchers.Main) {
            val desired = when (state.lowercase()) {
                "on", "enable", "enabled", "true" -> true
                "off", "disable", "disabled", "false" -> false
                else -> return@withContext SkillResult.failure("الحالة المطلوبة غير مدعومة")
            }

            when (action.lowercase().replace('-', '_').replace(' ', '_')) {
                "airplane_mode" -> toggleAirplaneMode(desired)
                "hotspot" -> toggleHotspot(desired)
                "nfc" -> toggleNFC(desired)
                "dnd", "do_not_disturb", "silent" -> toggleDND(desired)
                "rotation", "auto_rotate" -> toggleRotation(desired)
                "dark_mode" -> setSystemDarkMode(desired)
                else -> SkillResult.failure("إعداد النظام المطلوب غير مدعوم")
            }
        }

    /* -------------------------------------------------------------------------------------
     * Airplane mode
     * ----------------------------------------------------------------------------------- */

    private fun toggleAirplaneMode(enable: Boolean): SkillResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            if (!Settings.System.canWrite(appContext)) {
                // Writing AIRPLANE_MODE_ON is privileged; guide the user instead of failing.
                return openWriteSettingsScreen(
                    "أحتاج إذن تعديل الإعدادات لتشغيل أو إيقاف وضع الطيران."
                )
            }
            try {
                Settings.Global.putInt(
                    appContext.contentResolver,
                    Settings.Global.AIRPLANE_MODE_ON,
                    if (enable) 1 else 0
                )
                // The system only reacts after the mode-change broadcast is sent.
                appContext.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                    putExtra("state", enable)
                })
                val written = Settings.Global.getInt(
                    appContext.contentResolver,
                    Settings.Global.AIRPLANE_MODE_ON,
                    if (enable) 0 else 1
                ) == 1
                if (written == enable) {
                    return SkillResult.success(
                        if (enable) "تم تشغيل وضع الطيران." else "تم إيقاف وضع الطيران."
                    )
                }
                Log.w(TAG, "Airplane mode write did not stick (wanted=$enable, got=$written)")
            } catch (e: SecurityException) {
                Log.w(TAG, "Airplane mode write was denied", e)
                return SkillResult.permissionRequired(
                    "أحتاج إذن تعديل الإعدادات لتغيير وضع الطيران.",
                    listOf(WRITE_SETTINGS_PERMISSION)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not change airplane mode", e)
            }
        }
        return openAirplaneSettings()
    }

    private fun openAirplaneSettings(): SkillResult = tryLaunch(
        Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        "تم فتح إعدادات وضع الطيران. أكمل التغيير من إعدادات النظام."
    )

    /* -------------------------------------------------------------------------------------
     * Hotspot / tethering
     * ----------------------------------------------------------------------------------- */

    /**
     * Android exposes no public API for toggling the Wi-Fi hotspot for ordinary apps, so the
     * tethering screen is opened and that limitation is stated honestly.
     */
    private fun toggleHotspot(enable: Boolean): SkillResult {
        val intent = Intent(Settings.ACTION_TETHERING_SETTINGS)
        return if (intent.resolveActivity(packageManager) != null) {
            tryLaunch(
                intent,
                "تم فتح إعدادات نقطة الاتصال. لا يمكن للتطبيقات العادية تغييرها تلقائياً، أكمل من إعدادات النظام."
            )
        } else {
            // Some devices do not expose TETHERING_SETTINGS; the wireless settings page hosts it.
            tryLaunch(
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
                "تم فتح إعدادات الشبكة. أكمل تغيير نقطة الاتصال من إعدادات النظام."
            )
        }
    }

    /* -------------------------------------------------------------------------------------
     * NFC
     * ----------------------------------------------------------------------------------- */

    /** No public enable/disable API exists; the NFC settings screen is opened instead. */
    private fun toggleNFC(enable: Boolean): SkillResult = tryLaunch(
        Intent(Settings.ACTION_NFC_SETTINGS),
        "تم فتح إعدادات NFC. أكمل التغيير من إعدادات النظام."
    )

    /* -------------------------------------------------------------------------------------
     * Do Not Disturb
     * ----------------------------------------------------------------------------------- */

    private fun toggleDND(enable: Boolean): SkillResult {
        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return SkillResult.failure("خدمة الإشعارات غير متوفرة على هذا الجهاز.")

        if (!notificationManager.isNotificationPolicyAccessGranted) {
            return openNotificationPolicySettings()
        }

        return try {
            notificationManager.setInterruptionFilter(
                if (enable) NotificationManager.INTERRUPTION_FILTER_NONE
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            val actual = notificationManager.currentInterruptionFilter
            val ok = if (enable) {
                actual == NotificationManager.INTERRUPTION_FILTER_NONE
            } else {
                actual != NotificationManager.INTERRUPTION_FILTER_NONE
            }
            if (ok) {
                SkillResult.success(
                    if (enable) "تم تشغيل وضع عدم الإزعاج." else "تم إيقاف وضع عدم الإزعاج."
                )
            } else {
                openNotificationPolicySettings()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Do Not Disturb change was denied", e)
            openNotificationPolicySettings()
        } catch (e: Exception) {
            Log.w(TAG, "Could not change Do Not Disturb", e)
            SkillResult.failure("تعذّر تغيير وضع عدم الإزعاج.")
        }
    }

    private fun openNotificationPolicySettings(): SkillResult = tryLaunch(
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
        "أحتاج إذن الوصول إلى سياسة الإشعارات. تم فتح صفحة الصلاحيات، أكمل من إعدادات النظام."
    )

    /* -------------------------------------------------------------------------------------
     * Auto-rotation
     * ----------------------------------------------------------------------------------- */

    private fun toggleRotation(enable: Boolean): SkillResult {
        if (!Settings.System.canWrite(appContext)) {
            return openWriteSettingsScreen(
                "أحتاج إذن تعديل الإعدادات للتحكم في تدوير الشاشة."
            )
        }
        return try {
            Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (enable) 1 else 0
            )
            val actual = Settings.System.getInt(
                appContext.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (enable) 0 else 1
            ) == 1
            if (actual == enable) {
                SkillResult.success(
                    if (enable) "تم تشغيل التدوير التلقائي للشاشة." else "تم إيقاف التدوير التلقائي للشاشة."
                )
            } else {
                openDisplaySettings()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Rotation change was denied", e)
            SkillResult.permissionRequired(
                "أحتاج إذن تعديل الإعدادات لتغيير تدوير الشاشة.",
                listOf(WRITE_SETTINGS_PERMISSION)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not change rotation", e)
            openDisplaySettings()
        }
    }

    private fun openDisplaySettings(): SkillResult = tryLaunch(
        Intent(Settings.ACTION_DISPLAY_SETTINGS),
        "تم فتح إعدادات العرض. أكمل تغيير التدوير من إعدادات النظام."
    )

    /* -------------------------------------------------------------------------------------
     * Dark mode
     * ----------------------------------------------------------------------------------- */

    private fun setSystemDarkMode(enable: Boolean): SkillResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return SkillResult.userActionRequired(
                "الوضع الداكن على مستوى النظام غير مدعوم على هذا الإصدار من أندرويد. يمكنك تغييره يدوياً من إعدادات العرض."
            )
        }
        val uiModeManager = appContext.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            ?: return SkillResult.failure("خدمة الأوضاع غير متوفرة على هذا الجهاز.")

        return try {
            uiModeManager.applicationNightMode =
                if (enable) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
            val actual = uiModeManager.nightMode
            val ok = if (enable) actual == UiModeManager.MODE_NIGHT_YES
            else actual == UiModeManager.MODE_NIGHT_NO
            if (ok) {
                SkillResult.success(
                    if (enable) "تم تفعيل الوضع الداكن للنظام." else "تم تفعيل الوضع الفاتح للنظام."
                )
            } else {
                openDisplaySettings()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Dark mode change was denied", e)
            openDisplaySettings()
        } catch (e: Exception) {
            Log.w(TAG, "Could not change dark mode", e)
            openDisplaySettings()
        }
    }

    /* -------------------------------------------------------------------------------------
     * Existing Phase 1 actions (Wi-Fi, Bluetooth, apps, screenshot)
     * ----------------------------------------------------------------------------------- */

    suspend fun openSystemSetting(setting: String): SkillResult = withContext(Dispatchers.Main) {
        when (setting.lowercase()) {
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
            val fallback = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return@withContext tryLaunch(fallback, "تم فتح إعدادات Wi-Fi. أكمل التغيير من إعدادات النظام.")
        }

        tryLaunch(intent, "تم فتح إعدادات Wi-Fi. أكمل التغيير من إعدادات النظام.")
    }

    suspend fun openBluetoothSettings(): SkillResult = withContext(Dispatchers.Main) {
        tryLaunch(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            "تم فتح إعدادات البلوتوث. أكمل التغيير من إعدادات النظام."
        )
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

    /* -------------------------------------------------------------------------------------
     * Helpers
     * ----------------------------------------------------------------------------------- */

    private fun isValidPackageName(candidate: String): Boolean {
        if (candidate.isEmpty()) return false
        if (!candidate.matches(PACKAGE_PATTERN)) return false
        return candidate.contains('.')
    }

    /** Screens the user must reach to grant WRITE_SETTINGS, with a clear Arabic reason. */
    private fun openWriteSettingsScreen(reason: String): SkillResult = tryLaunch(
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${appContext.packageName}")
        },
        "$reason تم فتح صفحة صلاحية تعديل الإعدادات."
    )

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
        const val WRITE_SETTINGS_PERMISSION = "android.permission.WRITE_SETTINGS"
        val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_.]*$")
    }
}
