package com.rafeeq.assistant.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Manages smart contextual permissions. No permissions are requested at startup.
 */
class PermissionsManager(private val context: Context) {
    private val appContext = context.applicationContext

    fun hasRecordAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun getRecordAudioPermission(): String = Manifest.permission.RECORD_AUDIO

    /** Generic check for when the permission string is already known at runtime. */
    fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        permission
    ) == PackageManager.PERMISSION_GRANTED

    fun hasReadMediaImagesPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun getReadMediaImagesPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getBluetoothConnectPermission(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Manifest.permission.BLUETOOTH_CONNECT
    } else {
        null
    }

    fun hasReadContactsPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    fun getReadContactsPermission(): String = Manifest.permission.READ_CONTACTS

    fun hasCallPhonePermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.CALL_PHONE
    ) == PackageManager.PERMISSION_GRANTED

    fun getCallPhonePermission(): String = Manifest.permission.CALL_PHONE

    fun hasSendSmsPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.SEND_SMS
    ) == PackageManager.PERMISSION_GRANTED

    fun getSendSmsPermission(): String = Manifest.permission.SEND_SMS
}
