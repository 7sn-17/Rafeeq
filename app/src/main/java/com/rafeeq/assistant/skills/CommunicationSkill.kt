package com.rafeeq.assistant.skills

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommunicationSkill(context: Context) {
    private val appContext = context.applicationContext

    suspend fun callContact(name: String): SkillResult = withContext(Dispatchers.Main) {
        val contactUri = findContactPhone(name)
        if (contactUri == null) {
            return@withContext SkillResult("عذراً، لم أجد جهة الاتصال \"$name\"", success = false)
        }
        try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = contactUri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            SkillResult("جاري الاتصال بـ $name", success = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate call", e)
            SkillResult("عذراً، تعذّر الاتصال", success = false)
        }
    }

    suspend fun sendWhatsAppMessage(contact: String, message: String): SkillResult = withContext(Dispatchers.Main) {
        val phone = findContactPhoneNumber(contact)
        if (phone.isNullOrBlank()) {
            return@withContext SkillResult("عذراً، لم أجد جهة الاتصال \"$contact\"", success = false)
        }
        try {
            val uri = Uri.parse("smsto:$phone")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                setPackage("com.whatsapp")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (appContext.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
                return@withContext SkillResult("تطبيق واتساب غير مثبت", success = false)
            }
            appContext.startActivity(intent)
            SkillResult("جاري فتح واتساب لـ $contact", success = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send WhatsApp message", e)
            SkillResult("عذراً، تعذّر إرسال الرسالة", success = false)
        }
    }

    private suspend fun findContactPhone(name: String): Uri? = withContext(Dispatchers.IO) {
        val uri = Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_FILTER_URI,
            Uri.encode(name)
        )
        appContext.contentResolver.query(
            uri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.HAS_PHONE_NUMBER),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val hasPhone = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) == "1"
                if (hasPhone) {
                    return@use Uri.parse("tel:$id")
                }
            }
        }
        null
    }

    private suspend fun findContactPhoneNumber(name: String): String? = withContext(Dispatchers.IO) {
        val uri = Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_FILTER_URI,
            Uri.encode(name)
        )
        appContext.contentResolver.query(
            uri,
            arrayOf(ContactsContract.Contacts._ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                appContext.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(id),
                    null
                )?.use { phoneCursor ->
                    if (phoneCursor.moveToFirst()) {
                        return phoneCursor.getString(phoneCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                    }
                }
            }
        }
        null
    }

    private fun hasContactsPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        android.Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val TAG = "CommunicationSkill"
    }
}
