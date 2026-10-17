package com.rafeeq.assistant.skills

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class DeviceSettingsSkill(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    suspend fun changeSetting(setting: String, state: String): SkillResult {
        return when (setting.lowercase()) {
            "volume" -> changeVolume(state.lowercase())
            "bluetooth" -> changeBluetooth(state.lowercase())
            else -> SkillResult("إعداد غير مدعوم", success = false)
        }
    }

    private fun changeVolume(state: String): SkillResult {
        return try {
            when (state) {
                "up" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                    SkillResult("تم رفع مستوى الصوت", success = true)
                }
                "down" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                    SkillResult("تم خفض مستوى الصوت", success = true)
                }
                "mute" -> {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    SkillResult("تم كتم الصوت", success = true)
                }
                "unmute" -> {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
                    SkillResult("تم إلغاء كتم الصوت", success = true)
                }
                else -> SkillResult("حالة صوت غير مدعومة", success = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to change volume", e)
            SkillResult("عذراً، تعذّر تغيير مستوى الصوت", success = false)
        }
    }

    private fun changeBluetooth(state: String): SkillResult {
        if (bluetoothAdapter == null) {
            return SkillResult("جهاز البلوتوث غير متوفر", success = false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasBluetoothConnectPermission()) {
                return SkillResult("أحتاج إذن البلوتوث للتعامل معه", success = false)
            }
        }
        return try {
            when (state) {
                "on" -> {
                    bluetoothAdapter.enable()
                    SkillResult("تم تشغيل البلوتوث", success = true)
                }
                "off" -> {
                    bluetoothAdapter.disable()
                    SkillResult("تم إيقاف البلوتوث", success = true)
                }
                else -> SkillResult("حالة بلوتوث غير مدعومة", success = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to change bluetooth", e)
            SkillResult("عذراً، تعذّر تغيير إعداد البلوتوث", success = false)
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val TAG = "DeviceSettingsSkill"
    }
}
