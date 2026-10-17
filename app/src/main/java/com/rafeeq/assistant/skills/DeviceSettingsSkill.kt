package com.rafeeq.assistant.skills

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.rafeeq.assistant.permissions.PermissionsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Uses real Android APIs where permitted. Anything that requires a system confirmation or is
 * restricted by Android's security model is reported as [SkillOutcome.SCREEN_OPENED] rather than
 * a fake "success" at toggling the setting.
 */
class DeviceSettingsSkill(
    context: Context,
    private val permissionsManager: PermissionsManager
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    suspend fun changeSetting(setting: String, state: String): SkillResult = withContext(Dispatchers.Main) {
        when (setting.lowercase()) {
            "volume" -> changeVolume(state.lowercase())
            "bluetooth" -> changeBluetooth(state.lowercase())
            else -> SkillResult.failure("إعداد غير مدعوم")
        }
    }

    private fun changeVolume(state: String): SkillResult {
        val stream = AudioManager.STREAM_MUSIC
        val current = audioManager.getStreamVolume(stream)
        val max = audioManager.getStreamMaxVolume(stream)
        if (max <= 0) {
            return SkillResult.failure("لا يمكن ضبط مستوى الصوت على هذا الجهاز.")
        }

        return try {
            when (state) {
                "up" -> audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
                "down" -> audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)
                "mute" -> audioManager.setStreamVolume(stream, 0, 0)
                "unmute" -> {
                    val restored = when {
                        current > 0 -> current
                        else -> (max / 3).coerceAtLeast(1)
                    }.coerceIn(1, max)
                    audioManager.setStreamVolume(stream, restored, 0)
                }
                else -> return SkillResult.failure("حالة صوت غير مدعومة")
            }

            val after = audioManager.getStreamVolume(stream)
            when {
                after == 0 && state == "mute" -> SkillResult.success("تم كتم مستوى صوت الوسائط.")
                after == 0 && state != "mute" -> SkillResult.success("تم ضبط مستوى صوت الوسائط على $after من $max.")
                state == "up" && after == max && current < max ->
                    SkillResult.success("تم رفع مستوى صوت الوسائط إلى الحد الأقصى ($after من $max).")
                state == "down" && after < current ->
                    SkillResult.success("تم خفض مستوى صوت الوسائط إلى $after من $max.")
                state == "unmute" ->
                    SkillResult.success("تم إلغاء كتم الصوت وضبط مستوى الوسائط على $after من $max.")
                else -> SkillResult.success("تم ضبط مستوى صوت الوسائط على $after من $max.")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot change volume", e)
            SkillResult.userActionRequired("تعذّر تغيير مستوى الصوت تلقائياً. يمكنك تغييره من إعدادات الصوت.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to change volume", e)
            SkillResult.failure("عذراً، تعذّر تغيير مستوى الصوت")
        }
    }

    @Suppress("DEPRECATION")
    @Suppress("DEPRECATION")
    private fun changeBluetooth(state: String): SkillResult {
        val adapter = bluetoothAdapter ?: return SkillResult.failure("جهاز البلوتوث غير متوفر على هذا الجهاز.")

        val requestedOn = when (state) {
            "on" -> true
            "off" -> false
            else -> return SkillResult.failure("حالة بلوتوث غير مدعومة")
        }

        // Android 13+ no longer allows apps to toggle Bluetooth directly; enable()/disable()
        // are deprecated no-ops. Open Settings instead and report that the change must finish there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return openBluetoothSettings()
        }

        try {
            val accepted = if (requestedOn) {
                adapter.enable()
            } else {
                adapter.disable()
            }
            val actual = adapter.isEnabled
            if (actual == requestedOn) {
                return SkillResult.success(
                    if (requestedOn) "تم تشغيل البلوتوث." else "تم إيقاف البلوتوث."
                )
            }
            Log.w(TAG, "Bluetooth toggle ignored (accepted=$accepted, enabled=$actual, requested=$requestedOn)")
            openBluetoothSettings()
        } catch (e: SecurityException) {
            Log.e(TAG, "BLUETOOTH_CONNECT missing or restricted", e)
            SkillResult.permissionRequired(
                "لا يمكن تغيير إعداد البلوتوث دون إذن البلوتوث.",
                permissionsManager.getBluetoothConnectPermission()?.let { listOf(it) } ?: emptyList()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to change bluetooth", e)
            SkillResult.failure("تعذّر تغيير إعداد البلوتوث")
        }
    }

    private fun openBluetoothSettings(): SkillResult {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(appContext.packageManager) != null) {
            try {
                appContext.startActivity(intent)
                SkillResult.screenOpened("تم فتح إعدادات البلوتوث. أكمل التغيير من إعدادات النظام.")
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "Bluetooth Settings not available", e)
                SkillResult.failure("تعذّر فتح إعدادات البلوتوث")
            } catch (e: SecurityException) {
                Log.e(TAG, "Launching Bluetooth Settings was blocked", e)
                SkillResult.userActionRequired("لا يمكن فتح إعدادات البلوتوث تلقائياً من هنا.")
            }
        } else {
            SkillResult.failure("تعذّر فتح إعدادات البلوتوث")
        }
    }

    companion object {
        const val TAG = "DeviceSettingsSkill"
    }
}
