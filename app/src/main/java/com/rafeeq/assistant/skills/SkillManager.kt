package com.rafeeq.assistant.skills

import android.util.Log
import com.rafeeq.assistant.notifications.BackgroundTaskSummary
import com.rafeeq.assistant.notifications.RafeeqNotificationManager
import com.rafeeq.assistant.permissions.PermissionsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * What a skill actually achieved.
 *
 * The distinctions matter: opening the dialer is not the same as placing a call, and a missing
 * permission is not an ordinary failure. The chat renders these differently so the assistant
 * never claims a system change it did not perform.
 */
enum class SkillOutcome {
    /** The operation completed and its result was verified. */
    SUCCESS,

    /** A system screen or activity was launched; the underlying setting was NOT changed by us. */
    SCREEN_OPENED,

    /** A runtime permission is missing; [SkillResult.requiredPermissions] says which. */
    PERMISSION_REQUIRED,

    /** The user must finish the action themselves (system Settings, a manual step, ...). */
    USER_ACTION_REQUIRED,

    /** The action failed or is not supported on this device. */
    FAILURE
}

data class SkillResult(
    val message: String,
    val success: Boolean,
    val outcome: SkillOutcome = if (success) SkillOutcome.SUCCESS else SkillOutcome.FAILURE,
    val requiredPermissions: List<String> = emptyList()
) {
    companion object {
        fun success(message: String) = SkillResult(message, true, SkillOutcome.SUCCESS)

        fun screenOpened(message: String) = SkillResult(message, true, SkillOutcome.SCREEN_OPENED)

        fun failure(message: String) = SkillResult(message, false, SkillOutcome.FAILURE)

        fun permissionRequired(message: String, permissions: List<String>) =
            SkillResult(message, false, SkillOutcome.PERMISSION_REQUIRED, permissions)

        fun userActionRequired(message: String) =
            SkillResult(message, false, SkillOutcome.USER_ACTION_REQUIRED)
    }
}

/**
 * A runtime permission the UI has to ask the user for. Published on
 * [SkillManager.pendingPermission]; the Compose layer launches the real system dialog for it.
 */
data class PendingPermissionRequest(
    /** Unique per request, so a recomposition can never launch the same dialog twice. */
    val id: String,
    val permissions: List<String>,
    val message: String
)

/** A large or sensitive action that is waiting for the user's explicit yes/no answer. */
data class PendingConfirmation(
    val actionId: String,
    val message: String
)

enum class ProcessingState {
    ANALYZING,
    SEARCHING_FILES,
    MOVING_FILES,
    COMPLETED
}

/**
 * Single entry point for running a skill.
 *
 * Skills never launch a dialog themselves. When a permission is missing, [requestPermissions]
 * publishes a [PendingPermissionRequest]; the Activity-owned Compose layer shows the genuine
 * Android dialog and reports the answer back through [onPermissionResult]. The grant is then
 * revalidated rather than trusted, and a denial surfaces as a structured
 * [SkillOutcome.PERMISSION_REQUIRED] instead of an opaque failure.
 */
class SkillManager(
    private val organizeImagesSkill: OrganizeImagesSkill,
    private val communicationSkill: CommunicationSkill,
    private val deviceSettingsSkill: DeviceSettingsSkill,
    private val systemControlSkill: SystemControlSkill,
    private val permissionsManager: PermissionsManager,
    private val notificationManager: RafeeqNotificationManager? = null
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    /** The permission the UI must show a dialog for right now, if any. */
    private val _pendingPermission = MutableStateFlow<PendingPermissionRequest?>(null)
    val pendingPermission: StateFlow<PendingPermissionRequest?> = _pendingPermission.asStateFlow()

    /** The action awaiting explicit user consent right now, if any. */
    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    private class AwaitingPermission(
        val continuation: Continuation<Boolean>,
        val id: String,
        val permissions: List<String>
    )

    private class AwaitingConfirmation(
        val continuation: Continuation<Boolean>,
        val actionId: String
    )

    private val awaitingPermission = AtomicReference<AwaitingPermission?>(null)
    private val awaitingConfirmation = AtomicReference<AwaitingConfirmation?>(null)
    private val requestSequence = AtomicLong(0)

    /* -------------------------------------------------------------------------------------
     * Function dispatch
     * ----------------------------------------------------------------------------------- */

    suspend fun handleFunction(name: String, args: Map<String, String>): SkillResult {
        return when (name) {
            "organizeImages" -> organizeImages(args)

            "callContact" -> {
                val target = args["name"] ?: args["contact"] ?: ""
                // A dictated number needs no contacts access; a name does.
                if (!communicationSkill.isDirectPhoneNumber(target)) {
                    requireContactsPermission()?.let { return it }
                }
                // Ask for CALL_PHONE so the call is actually placed. If the user declines,
                // callContact falls back to opening the dialer instead of failing.
                if (!permissionsManager.hasCallPhonePermission()) {
                    requestPermissions(
                        listOf(permissionsManager.getCallPhonePermission()),
                        "أحتاج إذن الاتصال لتنفيذ المكالمة فعلياً."
                    )
                }
                communicationSkill.callContact(target)
            }

            "sendWhatsAppMessage" -> {
                val target = args["contact"] ?: args["name"] ?: ""
                if (!communicationSkill.isDirectPhoneNumber(target)) {
                    requireContactsPermission()?.let { return it }
                }
                communicationSkill.sendWhatsAppMessage(
                    name = target,
                    message = args["message"] ?: ""
                )
            }

            "changeDeviceSetting" -> {
                val setting = args["setting"] ?: ""
                if (setting.equals("bluetooth", ignoreCase = true)) {
                    // Only Android 11+ defines BLUETOOTH_CONNECT; older versions need nothing.
                    permissionsManager.getBluetoothConnectPermission()?.let { permission ->
                        requirePermission(
                            permission,
                            "لم يتم منح إذن البلوتوث، لذا لا يمكنني التحكم فيه."
                        )?.let { return it }
                    }
                }
                deviceSettingsSkill.changeSetting(setting, args["state"] ?: "")
            }

            "setVolume" -> {
                val level = args["level"]?.trim()?.toIntOrNull()
                if (level == null) {
                    SkillResult.failure("لم أتعرف على مستوى الصوت المطلوب. حدّد نسبة من 0 إلى 100.")
                } else {
                    deviceSettingsSkill.setVolumePercent(level)
                }
            }

            "webSearch" -> systemControlSkill.webSearch(args["query"] ?: args["q"] ?: "")

            "toggleFlashlight" -> systemControlSkill.changeSystemSetting(
                action = "flashlight",
                state = args["state"] ?: "on"
            )

            "openSystemSetting" -> systemControlSkill.openSystemSetting(args["setting"] ?: "")

            "changeSystemSetting" -> systemControlSkill.changeSystemSetting(
                action = args["action"] ?: "",
                state = args["state"] ?: ""
            )

            "openApp" -> systemControlSkill.openApp(
                args["app"]
                    ?: args["package"]
                    ?: args["packageName"]
                    ?: args["name"]
                    ?: ""
            )
            "takeScreenshot" -> systemControlSkill.takeScreenshot()

            else -> SkillResult.failure("لم أفهم طلبك.")
        }
    }

    /* -------------------------------------------------------------------------------------
     * Permission architecture
     * ----------------------------------------------------------------------------------- */

    private suspend fun requireContactsPermission(): SkillResult? =
        requirePermission(
            permissionsManager.getReadContactsPermission(),
            "أحتاج إذن جهات الاتصال للوصول إلى دفتر هاتفك."
        )

    /**
     * Returns null when [permission] is granted (requesting it first when it is not), or a
     * structured [SkillResult.PermissionRequired] explaining why the action could not run.
     */
    private suspend fun requirePermission(permission: String, deniedMessage: String): SkillResult? {
        if (permissionsManager.hasPermission(permission)) return null

        return if (requestPermissions(listOf(permission), deniedMessage)) {
            // Revalidate the grant instead of trusting the callback on its own.
            if (permissionsManager.hasPermission(permission)) {
                null
            } else {
                SkillResult.permissionRequired(deniedMessage, listOf(permission))
            }
        } else {
            SkillResult.permissionRequired(deniedMessage, listOf(permission))
        }
    }

    /**
     * Publishes a request for [permissions] and suspends until the system dialog is answered.
     *
     * Returns true only when every requested permission ends up granted. While another request
     * is already on screen this returns false rather than stacking or auto-answering dialogs.
     */
    suspend fun requestPermissions(permissions: List<String>, message: String): Boolean {
        val missing = permissions.filter { !permissionsManager.hasPermission(it) }
        if (missing.isEmpty()) return true

        if (awaitingPermission.get() != null || _pendingPermission.value != null) {
            Log.w(TAG, "A permission request is already pending; refusing ${missing.joinToString()}")
            return false
        }

        val id = "perm-${requestSequence.incrementAndGet()}"

        return suspendCancellableCoroutine { continuation ->
            awaitingPermission.set(AwaitingPermission(continuation, id, missing))
            continuation.invokeOnCancellation { clearAwaitingPermission(continuation) }
            _pendingPermission.value = PendingPermissionRequest(id, missing, message)
        }
    }

    /**
     * Called by the UI once the system dialog has actually been launched. Clearing the pending
     * request here is what guarantees a recomposition or rotation cannot launch it a second time;
     * the continuation keeps waiting for [onPermissionResult].
     */
    fun onPermissionDialogShown(id: String) {
        val pending = _pendingPermission.value ?: return
        if (pending.id != id) return
        _pendingPermission.value = null
    }

    /** Delivers the system dialog's result to whoever asked for the permission. */
    fun onPermissionResult(results: Map<String, Boolean>) {
        val awaiting = awaitingPermission.get() ?: return
        if (!awaitingPermission.compareAndSet(awaiting, null)) return
        _pendingPermission.value = null

        val granted = awaiting.permissions.all { results[it] == true }
        if (!granted) {
            Log.i(TAG, "Permission denied: ${awaiting.permissions.joinToString()}")
        }
        scope.launch {
            awaiting.continuation.resume(granted)
        }
    }

    private fun clearAwaitingPermission(continuation: Continuation<Boolean>) {
        awaitingPermission.get()?.let { awaiting ->
            if (awaiting.continuation === continuation) {
                awaitingPermission.compareAndSet(awaiting, null)
                _pendingPermission.value = null
            }
        }
    }

    /* -------------------------------------------------------------------------------------
     * Confirmation for large or destructive operations
     *
     * The pending request is published on [pendingConfirmation]; the UI renders it as a dialog
     * and reports the answer through [onConfirmationResult]. Each request carries a unique id
     * so a stale answer can never resume the wrong (or a repeated) action.
     * ----------------------------------------------------------------------------------- */

    /** Suspends until the user accepts or declines [actionId]. Declining simply returns false. */
    suspend fun requestConfirmation(actionId: String, message: String): Boolean {
        if (awaitingConfirmation.get() != null || _pendingConfirmation.value != null) {
            Log.w(TAG, "A confirmation is already pending; refusing $actionId")
            return false
        }

        return suspendCancellableCoroutine { continuation ->
            awaitingConfirmation.set(AwaitingConfirmation(continuation, actionId))
            continuation.invokeOnCancellation { clearAwaitingConfirmation(continuation) }
            _pendingConfirmation.value = PendingConfirmation(actionId, message)
        }
    }

    fun onConfirmationResult(actionId: String, confirmed: Boolean) {
        val awaiting = awaitingConfirmation.get() ?: return
        if (awaiting.actionId != actionId) return
        if (!awaitingConfirmation.compareAndSet(awaiting, null)) return
        _pendingConfirmation.value = null
        scope.launch {
            awaiting.continuation.resume(confirmed)
        }
    }

    private fun clearAwaitingConfirmation(continuation: Continuation<Boolean>) {
        awaitingConfirmation.get()?.let { awaiting ->
            if (awaiting.continuation === continuation) {
                awaitingConfirmation.compareAndSet(awaiting, null)
                _pendingConfirmation.value = null
            }
        }
    }

    /* -------------------------------------------------------------------------------------
     * organizeImages
     * ----------------------------------------------------------------------------------- */

    private suspend fun organizeImages(args: Map<String, String>): SkillResult {
        val sortBy = args["sortBy"] ?: args["sort_by"] ?: OrganizeImagesSkill.SORT_BY_DATE

        requirePermission(
            permissionsManager.getReadMediaImagesPermission(),
            "لا توجد صلاحية للوصول إلى الصور. يرجى منح الإذن المطلوب."
        )?.let { return it }

        // A query is cheap; a bulk move is not. Counting first lets the user consent to the
        // scope of the change before anything is touched.
        val found = try {
            organizeImagesSkill.countImages(sortBy)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not count images", e)
            return SkillResult.failure("تعذّر الوصول إلى الصور على هذا الجهاز.")
        }

        if (found == 0) {
            return SkillResult.success("لم يتم العثور على صور جديدة لتنظيمها.")
        }

        if (found >= CONFIRMATION_THRESHOLD) {
            val confirmed = requestConfirmation(
                actionId = "organizeImages:$sortBy",
                message = "تم العثور على $found صورة غير مرتبة. هل تريد تنظيمها الآن؟"
            )
            if (!confirmed) {
                return SkillResult.failure("تم إلغاء تنظيم الصور.")
            }
        }

        organizeImagesSkill.progressCallback = { state, extra ->
            when (state) {
                ProcessingState.SEARCHING_FILES ->
                    notificationManager?.notifyTaskStart(TASK_ID_ORGANIZE_IMAGES, "تنظيم الصور")
                ProcessingState.MOVING_FILES -> {
                    val total = extra["count"]?.toIntOrNull() ?: 0
                    val done = extra["moved"]?.toIntOrNull() ?: 0
                    // Progress is throttled inside the notification manager, so a large batch
                    // never floods the notification shade.
                    notificationManager?.notifyTaskProgress(
                        taskId = TASK_ID_ORGANIZE_IMAGES,
                        taskName = "تنظيم الصور",
                        done = done,
                        total = total
                    )
                }
                ProcessingState.COMPLETED -> Unit
            }
            Log.d(TAG, "progress: $state $extra")
        }

        val summary = try {
            organizeImagesSkill.execute(sortBy)
        } catch (e: CancellationException) {
            // Report the real partial outcome before propagating the cancellation.
            val movedSoFar = organizeImagesSkill.lastMovedCount
            val foundSoFar = organizeImagesSkill.lastFoundCount
            notificationManager?.notifyTaskCompletion(
                BackgroundTaskSummary(
                    taskId = TASK_ID_ORGANIZE_IMAGES,
                    taskName = "تنظيم الصور",
                    succeeded = movedSoFar,
                    failed = (foundSoFar - movedSoFar).coerceAtLeast(0),
                    canceled = true
                )
            )
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "OrganizeImagesSkill failed", e)
            return SkillResult.failure("تعذّر تنظيم الصور.")
        }

        // Notify only after the operation reached a verified completion state.
        if (summary.found > 0) {
            notificationManager?.notifyTaskCompletion(
                BackgroundTaskSummary(
                    taskId = TASK_ID_ORGANIZE_IMAGES,
                    taskName = "تنظيم الصور",
                    succeeded = summary.moved,
                    failed = (summary.found - summary.moved).coerceAtLeast(0)
                )
            )
        } else {
            // Nothing was organised after all: clear the ongoing progress notification instead of
            // leaving a permanent "in progress" entry in the shade.
            notificationManager?.cancelTask(TASK_ID_ORGANIZE_IMAGES)
        }

        // A successful query is not the same as successful organization, so the counts are
        // reported from the verified result rather than assumed.
        return when {
            summary.found == 0 -> SkillResult.success("لم يتم العثور على صور جديدة لتنظيمها.")
            summary.moved == summary.found ->
                SkillResult.success("تم تنظيم ${summary.moved} صورة بنجاح.")
            summary.moved > 0 -> SkillResult.success(
                "تم تنظيم ${summary.moved} صورة، وتعذّر نقل ${summary.found - summary.moved} صورة."
            )
            else -> SkillResult.failure(
                "تعذّر نقل أي صورة. تأكد من منح الصلاحيات المطلوبة والمساحة المتاحة."
            )
        }
    }

    /** Resumes anything still waiting as "denied" so no continuation is left dangling. */
    fun dispose() {
        awaitingPermission.getAndSet(null)?.let { awaiting ->
            _pendingPermission.value = null
            awaiting.continuation.resume(false)
        }
        awaitingConfirmation.getAndSet(null)?.let { awaiting ->
            _pendingConfirmation.value = null
            awaiting.continuation.resume(false)
        }
    }

    private companion object {
        const val TAG = "SkillManager"

        /** Bulk operations at or above this size ask for explicit consent first. */
        const val CONFIRMATION_THRESHOLD = 10

        /** Stable task id so repeated organize runs replace the same notification. */
        const val TASK_ID_ORGANIZE_IMAGES = "organizeImages"
    }
}
