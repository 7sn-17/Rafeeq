package com.rafeeq.assistant.skills

import android.util.Log
import com.rafeeq.assistant.permissions.PermissionsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    private suspend fun requestPermissionWithMessage(permission: String, errorMessage: String): Boolean {
        return if (permissionsManager.hasRecordAudioPermission()) {
            // just check
            true
        } else {
            // Simplified: assume granted via external flow; return false gracefully
            Log.w(TAG, "Permission $permission not granted")
            false
        }
    }

    fun onPermissionResult(permission: String, isGranted: Boolean) {
        val pending = pendingAction.getAndSet(null)
        pending?.let {
            if (it.permission == permission) {
                scope.launch {
                    it.continuation.resume(isGranted)
                }
            }
        }
    }

    companion object {
        const val TAG = "SkillManager"
    }
}
