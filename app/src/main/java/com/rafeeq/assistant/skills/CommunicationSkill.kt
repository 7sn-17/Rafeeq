package com.rafeeq.assistant.skills

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.rafeeq.assistant.permissions.PermissionsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real communication actions backed by Android intents.
 *
 * Nothing here claims a message was sent or a call was placed: [Intent.ACTION_DIAL] only opens
 * the dialer, and the WhatsApp intent only opens a conversation. Both report that distinction
 * through [SkillOutcome.SCREEN_OPENED].
 */
class CommunicationSkill(
    context: Context,
    private val permissionsManager: PermissionsManager
) {
    private val appContext = context.applicationContext

    /* -------------------------------------------------------------------------------------
     * Phone calls
     * ----------------------------------------------------------------------------------- */

    /**
     * Opens the system dialer pre-filled with the contact's number, leaving the actual call to
     * the user. Direct calling is deliberately not attempted.
     */
    suspend fun callContact(name: String): SkillResult = withContext(Dispatchers.Main) {
        if (name.isBlank()) {
            return@withContext SkillResult.failure("لم أتعرف على اسم جهة الاتصال. اذكر الاسم من فضلك.")
        }

        val phone = when (val lookup = lookupContact(name)) {
            is ContactLookup.Found -> lookup.phoneNumber
            is ContactLookup.NotFound ->
                return@withContext SkillResult.failure("لم يتم العثور على جهة الاتصال.")
            is ContactLookup.Ambiguous ->
                return@withContext SkillResult.failure(
                    "توجد عدة جهات اتصال باسم \"${lookup.query}\": " +
                        "${lookup.matches.joinToString("، ")}. حدّد اسمًا أدق."
                )
            ContactLookup.PermissionRequired ->
                return@withContext SkillResult.permissionRequired(
                    "لا توجد صلاحية للوصول إلى جهات الاتصال. يرجى منح الإذن المطلوب.",
                    listOf(permissionsManager.getReadContactsPermission())
                )
        }

        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.fromParts("tel", phone, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (intent.resolveActivity(appContext.packageManager) == null) {
            return@withContext SkillResult.failure("تعذّر فتح تطبيق الاتصال على هذا الجهاز.")
        }

        try {
            appContext.startActivity(intent)
            SkillResult.screenOpened("تم فتح تطبيق الاتصال.")
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No dialer available", e)
            SkillResult.failure("تعذّر فتح تطبيق الاتصال على هذا الجهاز.")
        } catch (e: SecurityException) {
            Log.e(TAG, "Launching the dialer was blocked", e)
            SkillResult.userActionRequired("تم رفض فتح تطبيق الاتصال. يمكنك الاتصال يدويًا.")
        }
    }

    /* -------------------------------------------------------------------------------------
     * WhatsApp
     * ----------------------------------------------------------------------------------- */

    /**
     * Opens a WhatsApp chat with the contact and pre-fills [message]. Delivery is never
     * reported as done, because WhatsApp is the one that decides whether it is actually sent.
     */
    suspend fun sendWhatsAppMessage(name: String, message: String): SkillResult =
        withContext(Dispatchers.Main) {
            if (message.isBlank()) {
                return@withContext SkillResult.failure("لم أتعرف على نص الرسالة. اذكر الرسالة من فضلك.")
            }
            if (name.isBlank()) {
                return@withContext SkillResult.failure("لم أتعرف على اسم جهة الاتصال. اذكر الاسم من فضلك.")
            }

            val phone = when (val lookup = lookupContact(name)) {
                is ContactLookup.Found -> lookup.phoneNumber
                is ContactLookup.NotFound ->
                    return@withContext SkillResult.failure("لم يتم العثور على جهة الاتصال.")
                is ContactLookup.Ambiguous ->
                    return@withContext SkillResult.failure(
                        "توجد عدة جهات اتصال باسم \"${lookup.query}\": " +
                            "${lookup.matches.joinToString("، ")}. حدّد اسمًا أدق."
                    )
                ContactLookup.PermissionRequired ->
                    return@withContext SkillResult.permissionRequired(
                        "لا توجد صلاحية للوصول إلى جهات الاتصال. يرجى منح الإذن المطلوب.",
                        listOf(permissionsManager.getReadContactsPermission())
                    )
            }

            val uri = Uri.fromParts("smsto", phone, null)
            val packageManager = appContext.packageManager

            for (packageName in WHATSAPP_PACKAGES) {
                val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                    setPackage(packageName)
                    putExtra("sms_body", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(packageManager) == null) continue

                try {
                    appContext.startActivity(intent)
                    return@withContext SkillResult.screenOpened(
                        "تم فتح واتساب، لكن لم يتم التحقق من إرسال الرسالة."
                    )
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "$packageName could not be launched", e)
                } catch (e: SecurityException) {
                    Log.w(TAG, "$packageName launch was blocked", e)
                }
            }

            SkillResult.failure("تعذّر فتح واتساب. تأكد من تثبيته على جهازك.")
        }

    /* -------------------------------------------------------------------------------------
     * Contact lookup
     * ----------------------------------------------------------------------------------- */

    private sealed interface ContactLookup {
        data class Found(val displayName: String, val phoneNumber: String) : ContactLookup
        data class NotFound(val query: String) : ContactLookup
        data class Ambiguous(val query: String, val matches: List<String>) : ContactLookup
        data object PermissionRequired : ContactLookup
    }

    /**
     * Resolves a single contact.
     *
     * Only the minimum projection is read and cursors are closed with [use]; nothing is ever
     * sent to the AI backend. Ambiguous names are reported so the user can disambiguate rather
     * than silently calling an arbitrary match.
     */
    private suspend fun lookupContact(query: String): ContactLookup =
        withContext(Dispatchers.IO) {
            if (!permissionsManager.hasReadContactsPermission()) {
                return@withContext ContactLookup.PermissionRequired
            }

            // Exact-name matches win; the filtered URI is only a fallback, so a partial
            // hit cannot silently stand in for a name the user actually said.
            val exact = readMatchingContacts(query, exactOnly = true)
            val matches = exact.ifEmpty { readMatchingContacts(query, exactOnly = false) }

            when {
                matches.isEmpty() -> ContactLookup.NotFound(query)
                matches.size > 1 -> ContactLookup.Ambiguous(
                    query,
                    matches.map { it.displayName }.distinct()
                )
                else -> {
                    val contact = matches.first()
                    val phone = readPhoneNumber(contact.contactId)
                    if (phone.isNullOrBlank()) {
                        ContactLookup.NotFound(query)
                    } else {
                        ContactLookup.Found(contact.displayName, phone)
                    }
                }
            }
        }

    private data class ContactRef(val contactId: Long, val displayName: String)

    private fun readMatchingContacts(query: String, exactOnly: Boolean): List<ContactRef> {
        val uri = if (exactOnly) {
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI.buildUpon()
                .appendQueryParameter(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    query
                )
                .build()
        } else {
            Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_FILTER_URI,
                Uri.encode(query)
            )
        }

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME
        )

        val selection = if (exactOnly) {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
        } else {
            null
        }
        val selectionArgs = if (exactOnly) arrayOf(query) else null

        return try {
            val found = ArrayList<ContactRef>()
            appContext.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use {
                val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                while (it.moveToNext()) {
                    val displayName = it.getString(nameIndex) ?: continue
                    found.add(ContactRef(it.getLong(idIndex), displayName))
                }
            }
            found
        } catch (e: SecurityException) {
            Log.w(TAG, "Contact query was denied", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid contact query", e)
            emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Contact provider is unavailable", e)
            emptyList()
        }
    }

    /**
     * Returns the contact's preferred number, favouring the mobile one because that is what a
     * voice assistant's user most often means.
     */
    private fun readPhoneNumber(contactId: Long): String? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        val selectionArgs = arrayOf(contactId.toString())

        return try {
            var fallback: String? = null
            var mobile: String? = null
            appContext.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use {
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                while (it.moveToNext()) {
                    val number = it.getString(numberIndex)?.trim().orEmpty()
                    if (number.isEmpty()) continue
                    if (it.getInt(typeIndex) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                        mobile = number
                    }
                    if (fallback == null) fallback = number
                }
            }
            mobile ?: fallback
        } catch (e: SecurityException) {
            Log.w(TAG, "Contact phone query was denied", e)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not read the contact's phone number", e)
            null
        }
    }

    private companion object {
        const val TAG = "CommunicationSkill"

        /** WhatsApp Business uses the same intents under a different package. */
        val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
