package com.rafeeq.assistant.skills

import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
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
                "flashlight", "torch" -> toggleFlashlight(desired)
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
        val intent = Intent("android.settings.TETHER_SETTINGS")
        return if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
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
        val uiModeManager = appContext.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            ?: return SkillResult.failure("خدمة الأوضاع غير متوفرة على هذا الجهاز.")

        return try {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                if (enable) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            )
            SkillResult.success(
                if (enable) "تم تفعيل الوضع الداكن للنظام." else "تم تفعيل الوضع الفاتح للنظام."
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not change dark mode", e)
            openDisplaySettings()
        }
    }

    /* -------------------------------------------------------------------------------------
     * Flashlight (torch)
     *
     * setTorchMode needs no runtime permission and is supported since API 23. The torch is
     * busy while the camera app uses it, in which case CameraAccessException is surfaced as an
     * honest failure instead of a false success.
     * ----------------------------------------------------------------------------------- */

    private fun toggleFlashlight(enable: Boolean): SkillResult {
        val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return SkillResult.failure("خدمة الكاميرا غير متوفرة على هذا الجهاز.")

        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Could not list cameras", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Could not inspect camera flash", e)
            null
        } ?: return SkillResult.failure("لا يوجد كشاف (فلاش) متاح على هذا الجهاز.")

        return try {
            cameraManager.setTorchMode(cameraId, enable)
            SkillResult.success(if (enable) "تم تشغيل الكشاف." else "تم إيقاف الكشاف.")
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Torch toggle was rejected", e)
            SkillResult.failure("تعذّر التحكم في الكشاف. قد يكون مستخدَماً من تطبيق آخر.")
        } catch (e: Exception) {
            Log.w(TAG, "Torch toggle failed", e)
            SkillResult.failure("تعذّر التحكم في الكشاف.")
        }
    }

    /* -------------------------------------------------------------------------------------
     * Web search
     * ----------------------------------------------------------------------------------- */

    /**
     * Opens the device's search UI pre-filled with [query]. Nothing is scraped or sent to the AI
     * backend; the OS search app (or the default browser as a fallback) performs the search.
     */
    suspend fun webSearch(query: String): SkillResult = withContext(Dispatchers.Main) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return@withContext SkillResult.failure("لم أتعرف على ما تريد البحث عنه.")
        }

        val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, trimmed)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (packageManager.resolveActivity(searchIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            return@withContext tryLaunch(searchIntent, "تم فتح البحث عن \"$trimmed\".")
        }

        // Some devices expose no ACTION_WEB_SEARCH handler; the browser is an honest fallback.
        val browserIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=" + Uri.encode(trimmed))
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        tryLaunch(browserIntent, "تم فتح البحث عن \"$trimmed\" في المتصفح.")
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

        if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
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
     * Opens an installed application.
     *
     * [appIdentifier] may be an exact package name (`com.google.android.youtube`) or the name
     * the user actually said (`يوتيوب`, `YouTube`, `واتساب`). Names are resolved against the
     * installed launcher apps, with a small alias table for popular apps whose Arabic and
     * English names differ from their label, so "افتح يوتيوب" really launches YouTube instead of
     * failing on a guessed package name.
     */
    suspend fun openApp(appIdentifier: String): SkillResult = withContext(Dispatchers.Main) {
        val query = appIdentifier.trim()
        if (query.isEmpty()) {
            return@withContext SkillResult.failure("لم أتعرف على اسم التطبيق. اذكر اسم التطبيق من فضلك.")
        }

        val resolved = try {
            resolveApp(query)
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve app '$query'", e)
            null
        } ?: return@withContext openStoreSearch(
            query,
            "لم يتم العثور على تطبيق باسم \"$query\" على هذا الجهاز."
        )

        val launchIntent = try {
            packageManager.getLaunchIntentForPackage(resolved.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve launch intent for ${resolved.packageName}", e)
            null
        } ?: return@withContext SkillResult.failure(
            "التطبيق \"${resolved.label}\" غير قابل للفتح."
        )

        // Skills run from the application context, so the launched activity needs its own task.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            appContext.startActivity(launchIntent)
            SkillResult.screenOpened("تم فتح ${resolved.label}.")
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No handler for ${resolved.packageName}", e)
            SkillResult.failure("تعذّر فتح التطبيق \"${resolved.label}\".")
        } catch (e: SecurityException) {
            Log.e(TAG, "Launch of ${resolved.packageName} blocked", e)
            SkillResult.failure("تعذّر فتح التطبيق \"${resolved.label}\" بسبب قيود النظام.")
        }
    }

    /**
     * When the app the user asked for is not installed, opens the app store on that app's page
     * (for a known alias) or on a name search. Returns the honest not-found message when no
     * store is available, so a missing app never looks like a successful launch.
     */
    private fun openStoreSearch(query: String, notFoundMessage: String): SkillResult {
        val knownPackage = APP_ALIASES[normalizeAppName(query)]
        val marketUri = if (knownPackage != null && !isInstalled(knownPackage)) {
            Uri.parse("market://details?id=$knownPackage")
        } else {
            Uri.parse("market://search?q=" + Uri.encode(query))
        }
        val marketIntent = Intent(Intent.ACTION_VIEW, marketUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (packageManager.resolveActivity(marketIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            return tryLaunch(
                marketIntent,
                "التطبيق غير مثبت على جهازك. تم فتح متجر التطبيقات لتثبيته."
            )
        }
        return SkillResult.failure(notFoundMessage)
    }

    /** A launchable app found on the device. */
    private data class ResolvedApp(val packageName: String, val label: String)

    /**
     * Resolves a spoken/typed app name to an installed package.
     *
     * Order matters: an explicit package name wins, then a known alias that is actually
     * installed, then an exact label match, then a prefix/contains match on the label, and
     * finally a match on the package name itself.
     */
    private fun resolveApp(query: String): ResolvedApp? {
        if (isValidPackageName(query)) {
            return ResolvedApp(query, applicationLabel(query) ?: query)
        }

        val normalized = normalizeAppName(query)

        APP_ALIASES[normalized]
            ?.takeIf { isInstalled(it) }
            ?.let { return ResolvedApp(it, applicationLabel(it) ?: query) }

        val launchable = launchableApps()

        launchable.firstOrNull { normalizeAppName(it.label) == normalized }?.let { return it }
        launchable.firstOrNull { normalizeAppName(it.label).startsWith(normalized) }?.let { return it }
        launchable.firstOrNull { normalizeAppName(it.label).contains(normalized) }?.let { return it }
        launchable.firstOrNull { normalizeAppName(it.packageName).contains(normalized) }?.let { return it }

        return null
    }

    /** Every app that exposes a launcher entry, de-duplicated by package. */
    private fun launchableApps(): List<ResolvedApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                ResolvedApp(packageName, info.loadLabel(packageManager).toString())
            }
            .distinctBy { it.packageName }
    }

    private fun isInstalled(packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun applicationLabel(packageName: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        null
    }

    /**
     * Normalises a name for comparison: lower-case, no diacritics or tatweel, unified alef
     * forms, no spaces or punctuation. This lets "إنستغرام" match "انستغرام" and "YouTube"
     * match "youtube".
     */
    private fun normalizeAppName(value: String): String = buildString {
        for (ch in value.lowercase()) {
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> append('ا')
                'ى' -> append('ي')
                'ة' -> append('ه')
                'ـ' -> Unit // tatweel
                in '\u064B'..'\u0652' -> Unit // Arabic diacritics
                ' ', '-', '_', '.', '\'', '’', '(', ')', '!', '،', ',' -> Unit
                else -> append(ch)
            }
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
        if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
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

        /**
         * Popular apps whose package name cannot be guessed from what the user says. Keys are
         * already normalised the same way as [normalizeAppName]; an alias only wins when the
         * app is actually installed, so a stale entry can never produce a wrong launch.
         */
        val APP_ALIASES: Map<String, String> = mapOf(
            "يوتيوب" to "com.google.android.youtube",
            "youtube" to "com.google.android.youtube",
            "يوتيوبميوزك" to "com.google.android.apps.youtube.music",
            "youtubemusic" to "com.google.android.apps.youtube.music",
            "واتساب" to "com.whatsapp",
            "whatsapp" to "com.whatsapp",
            "واتساباعمال" to "com.whatsapp.w4b",
            "فيسبوك" to "com.facebook.katana",
            "facebook" to "com.facebook.katana",
            "انستغرام" to "com.instagram.android",
            "instagram" to "com.instagram.android",
            "تويتر" to "com.twitter.android",
            "twitter" to "com.twitter.android",
            "تليجرام" to "org.telegram.messenger",
            "telegram" to "org.telegram.messenger",
            "تيكتوك" to "com.zhiliaoapp.musically",
            "tiktok" to "com.zhiliaoapp.musically",
            "سنابشات" to "com.snapchat.android",
            "snapchat" to "com.snapchat.android",
            "جيميل" to "com.google.android.gm",
            "gmail" to "com.google.android.gm",
            "خرائط" to "com.google.android.apps.maps",
            "خرائطجوجل" to "com.google.android.apps.maps",
            "maps" to "com.google.android.apps.maps",
            "كروم" to "com.android.chrome",
            "chrome" to "com.android.chrome",
            "سبوتيفاي" to "com.spotify.music",
            "spotify" to "com.spotify.music",
            "نتفليكس" to "com.netflix.mediaclient",
            "netflix" to "com.netflix.mediaclient",
            "زووم" to "us.zoom.videomeetings",
            "zoom" to "us.zoom.videomeetings",
            "ديسكورد" to "com.discord",
            "discord" to "com.discord",
            "ريديت" to "com.reddit.frontpage",
            "reddit" to "com.reddit.frontpage",
            "جوجل" to "com.google.android.googlequicksearchbox",
            "google" to "com.google.android.googlequicksearchbox",
            "المعرض" to "com.google.android.apps.photos",
            "الصور" to "com.google.android.apps.photos",
            "photos" to "com.google.android.apps.photos",
            "playstore" to "com.android.vending",
            "متجرجوجل" to "com.android.vending",
            "متجرplay" to "com.android.vending",
            "الاعدادات" to "com.android.settings",
            "اعدادات" to "com.android.settings",
            "settings" to "com.android.settings",
            "الرسائل" to "com.google.android.apps.messaging",
            "messages" to "com.google.android.apps.messaging",
            "الهاتف" to "com.google.android.dialer",
            "phone" to "com.google.android.dialer",
            "المتجر" to "com.android.vending",
            "تلجرام" to "org.telegram.messenger",
            "تلغرام" to "org.telegram.messenger",
            "اكس" to "com.twitter.android",
            "x" to "com.twitter.android",
            "سناب" to "com.snapchat.android",
            "لينكدان" to "com.linkedin.android",
            "linkedin" to "com.linkedin.android",
            "الحاسبة" to "com.google.android.calculator",
            "الةالحاسبة" to "com.google.android.calculator",
            "calculator" to "com.google.android.calculator",
            "الساعة" to "com.google.android.deskclock",
            "المنبه" to "com.google.android.deskclock",
            "clock" to "com.google.android.deskclock",
            "التقويم" to "com.google.android.calendar",
            "calendar" to "com.google.android.calendar",
            "الملاحظات" to "com.google.android.keep",
            "keep" to "com.google.android.keep"
        )
    }
}
