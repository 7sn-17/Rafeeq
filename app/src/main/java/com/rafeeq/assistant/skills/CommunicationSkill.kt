package com.rafeeq.assistant.skills

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommunicationSkill(context: Context) {
    private val appContext = context.applicationContext

    suspend fun callContact(name: String): SkillResult = withContext(Dispatchers.Main) {
        val phone = findContactPhoneNumber(name)
        if (phone.isNullOrBlank()) {
            return@withContext SkillResult("عذراً، لم أجد جهة الاتصال \"$name\"", success = false)
        }
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")).apply {
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

    private suspend fun findContactPhoneNumber(name: String): String? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_FILTER_URI,
                Uri.encode(name)
            )
            var phoneNumber: String? = null
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
                        if (phoneCursor.moveToFirst() && phoneNumber == null) {
                            phoneNumber = phoneCursor.getString(
                                phoneCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            )
                        }
                    }
                }
            }
            phoneNumber
        } catch (e: Exception) {
            // READ_CONTACTS may be missing, or the provider may be unavailable.
            Log.e(TAG, "Failed to look up contact \"$name\"", e)
            null
        }
    }

    companion object {
        const val TAG = "CommunicationSkill"
    }
}
