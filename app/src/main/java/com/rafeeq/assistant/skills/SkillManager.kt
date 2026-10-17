package com.rafeeq.assistant.skills

import android.util.Log
import com.rafeeq.assistant.permissions.PermissionsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

data class SkillResult(
    val message: String,
    val success: Boolean
)

enum class ProcessingState {
    ANALYZING,
    SEARCHING_FILES,
    MOVING_FILES,
    COMPLETED
}

class SkillManager(
    private val organizeImagesSkill: OrganizeImagesSkill,
    private val communicationSkill: CommunicationSkill,
    private val deviceSettingsSkill: DeviceSettingsSkill,
    private val permissionsManager: PermissionsManager
) {
    private val pendingAction = AtomicReference<PendingAction?>(null)
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    /**
     * Bound by the Activity to an Android permission launcher so a skill that is missing a
     * permission can show the real system dialog and suspend until the user answers.
     * Without this binding, permission requests fail gracefully instead of hanging.
     */
    var permissionRequester: ((String) -> Unit)? = null

    data class PendingAction(
        val continuation: Continuation<Boolean>,
        val permission: String,
        val errorMessage: String
    )

    suspend fun handleFunction(name: String, args: Map<String, String>): SkillResult {
        return when (name) {
            "organizeImages" -> organizeImages(args)
            "callContact" -> {
                val nameArg = args["name"] ?: args["contact"] ?: ""
                communicationSkill.callContact(nameArg)
            }
            "sendWhatsAppMessage" -> {
                val contact = args["contact"] ?: args["name"] ?: ""
                val message = args["message"] ?: ""
                communicationSkill.sendWhatsAppMessage(contact, message)
            }
            "changeDeviceSetting" -> {
                val setting = args["setting"] ?: ""
                val state = args["state"] ?: ""
                deviceSettingsSkill.changeSetting(setting, state)
            }
            else -> SkillResult("عذراً، لم أفهم طلبك", success = false)
        }
    }

    private suspend fun organizeImages(args: Map<String, String>): SkillResult {
        val sortBy = args["sortBy"] ?: args["sort_by"] ?: "date"
        if (!permissionsManager.hasReadMediaImagesPermission()) {
            val errorMsg = "أحتاج إذن الصور لأقوم بترتيبها"
            val perm = permissionsManager.getReadMediaImagesPermission()
            val granted = requestPermissionWithMessage(perm, errorMsg)
            if (!granted) {
                return SkillResult(errorMsg, success = false)
            }
        }
        return try {
            organizeImagesSkill.progressCallback = { state, extra ->
                // progress emitted via callback; engine can subscribe if needed
                Log.d(TAG, "progress: $state $extra")
            }
            val movedCount = organizeImagesSkill.execute(sortBy)
            val message = when (sortBy.lowercase()) {
                "size" -> "جاري تنظيم الصور حسب الحجم"
                else -> "جاري تنظيم الصور حسب التاريخ"
            }
            SkillResult(message, success = true)
        } catch (e: Exception) {
            Log.e(TAG, "OrganizeImagesSkill failed", e)
            SkillResult("عذراً، تعذّر تنظيم الصور", success = false)
        }
    }

    /**
     * Asks for [permission] through the bound Android permission launcher and suspends until
     * the system dialog returns, so the caller can surface [errorMessage] when it is denied.
     */
    private suspend fun requestPermissionWithMessage(permission: String, errorMessage: String): Boolean {
        if (permissionsManager.hasPermission(permission)) return true

        val requester = permissionRequester
        if (requester == null) {
            Log.w(TAG, "No permission requester bound for $permission")
            return false
        }

        return suspendCancellableCoroutine { continuation ->
            pendingAction.set(PendingAction(continuation, permission, errorMessage))
            continuation.invokeOnCancellation {
                pendingAction.get()?.let { pending ->
                    if (pending.continuation === continuation) {
                        pendingAction.compareAndSet(pending, null)
                    }
                }
            }
            try {
                requester(permission)
            } catch (e: Exception) {
                // The dialog could not be shown (e.g. called from the wrong context).
                Log.e(TAG, "Could not launch the request for $permission", e)
                pendingAction.set(null)
                continuation.resume(false)
            }
        }
    }

    fun onPermissionResult(permission: String, isGranted: Boolean) {
        val pending = pendingAction.get() ?: return
        // Only the waiter for this exact permission may resume; anything else is ignored.
        if (pending.permission != permission) return
        if (!pendingAction.compareAndSet(pending, null)) return
        scope.launch {
            pending.continuation.resume(isGranted)
        }
    }

    companion object {
        const val TAG = "SkillManager"
    }
}
