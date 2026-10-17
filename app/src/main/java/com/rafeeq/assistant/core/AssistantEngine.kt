package com.rafeeq.assistant.core

import android.content.Context
import com.rafeeq.assistant.ai.AiService
import com.rafeeq.assistant.ai.AssistantRequest
import com.rafeeq.assistant.ai.ChatMessageDto
import com.rafeeq.assistant.permissions.PermissionsManager
import com.rafeeq.assistant.skills.CommunicationSkill
import com.rafeeq.assistant.skills.DeviceSettingsSkill
import com.rafeeq.assistant.skills.OrganizeImagesSkill
import com.rafeeq.assistant.skills.SkillManager
import com.rafeeq.assistant.skills.SkillResult
import com.rafeeq.assistant.skills.SystemControlSkill
import com.rafeeq.assistant.tts.TtsManager
import com.rafeeq.assistant.tts.TtsVoice
import com.rafeeq.assistant.voice.VoiceRecognitionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AssistantState {
    IDLE,
    LISTENING,
    PROCESSING,
    ANALYZING,
    SEARCHING_FILES,
    MOVING_FILES,
    COMPLETED,
    ERROR
}

/** How a chat entry should be rendered: a normal bubble, or a quieter system line. */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val role: MessageRole = if (isUser) MessageRole.USER else MessageRole.ASSISTANT
)

class AssistantEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val permissionsManager = PermissionsManager(context)
    private val voiceRecognitionManager = VoiceRecognitionManager(context)
    private val aiService = AiService()
    private val organizeImagesSkill = OrganizeImagesSkill(context)
    private val communicationSkill = CommunicationSkill(context, permissionsManager)
    private val deviceSettingsSkill = DeviceSettingsSkill(context, permissionsManager)
    private val systemControlSkill = SystemControlSkill(context)
    private val skillManager = SkillManager(
        organizeImagesSkill = organizeImagesSkill,
        communicationSkill = communicationSkill,
        deviceSettingsSkill = deviceSettingsSkill,
        systemControlSkill = systemControlSkill,
        permissionsManager = permissionsManager
    )
    private val ttsManager = TtsManager(context)

    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _responseText = MutableStateFlow("")
    val responseText: StateFlow<String> = _responseText.asStateFlow()

    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage.asStateFlow()

    /**
     * A short-lived, human readable progress line ("جاري تحليل الطلب...", ...). It is cleared
     * as soon as the final answer arrives, so it never lingers in the conversation history.
     */
    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    /** Live mute flag owned by [TtsManager]. */
    val ttsMuted: StateFlow<Boolean> get() = ttsManager.muted

    /** Bumped once the TTS engine has finished initialising, so voices can be listed. */
    val ttsReadyTick: StateFlow<Int> get() = ttsManager.readyTick

    var audioPermissionRequester: ((String) -> Unit)? = null

    init {
        scope.launch {
            voiceRecognitionManager.recognizedText.collect { speech ->
                handleUserSpeech(speech)
            }
        }
        scope.launch {
            voiceRecognitionManager.recognitionFailed.collect {
                handleRecognitionFailure()
            }
        }
    }

    fun startListening() {
        if (_state.value == AssistantState.LISTENING || _state.value == AssistantState.PROCESSING) {
            return
        }
        _errorMessage.value = ""
        _recognizedText.value = ""
        _responseText.value = ""
        _state.value = AssistantState.LISTENING
        if (!permissionsManager.hasRecordAudioPermission()) {
            val perm = permissionsManager.getRecordAudioPermission()
            audioPermissionRequester?.invoke(perm) ?: run {
                // If no requester bound, just try to start (may fail gracefully)
                voiceRecognitionManager.startListening()
            }
        } else {
            voiceRecognitionManager.startListening()
        }
    }

    /** Stops an in-progress listening session because the user tapped the mic again. */
    fun stopListening() {
        if (_state.value != AssistantState.LISTENING) return
        voiceRecognitionManager.stopListening()
        _state.value = AssistantState.IDLE
    }

    /**
     * Sends [text] through exactly the same pipeline as a spoken utterance, so typed and
     * voiced commands behave identically.
     */
    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (_state.value == AssistantState.PROCESSING || _state.value == AssistantState.ANALYZING) {
            return
        }
        scope.launch { handleUserSpeech(trimmed) }
    }

    /** Wipes the visible conversation. */
    fun clearHistory() {
        _messages.value = emptyList()
        _errorMessage.value = ""
        _responseText.value = ""
        _recognizedText.value = ""
        _statusText.value = null
        _state.value = AssistantState.IDLE
    }

    fun setTtsMuted(muted: Boolean) {
        ttsManager.setMuted(muted)
    }

    fun setTtsVoice(name: String?) {
        ttsManager.setPreferredVoice(name)
    }

    fun listTtsVoices(): List<TtsVoice> = ttsManager.listVoices()

    fun onAudioPermissionResult(isGranted: Boolean) {
        voiceRecognitionManager.onPermissionResult(isGranted)
    }

    fun onPermissionResult(results: Map<String, Boolean>) {
        scope.launch {
            skillManager.onPermissionResult(results)
        }
    }

    fun onPermissionResult(permission: String, isGranted: Boolean) {
        onPermissionResult(mapOf(permission to isGranted))
    }

    private suspend fun handleUserSpeech(speech: String) {
        _recognizedText.value = speech
        addMessage(ChatMessage(speech, isUser = true))
        _state.value = AssistantState.PROCESSING
        _statusText.value = STATUS_ANALYZING

        try {
            val request = aiService.interpret(conversationHistory())
            respond(request)
        } catch (e: Exception) {
            respond(SkillResult("عذراً، حدث خطأ غير متوقع", success = false))
        } finally {
            _statusText.value = null
        }
    }

    /**
     * Builds the context window handed to Groq: the last [CONTEXT_WINDOW_MESSAGES] real turns,
     * ending with the utterance that was just appended to [_messages].
     *
     * System bubbles are excluded because they are transient progress markers
     * ("جاري تنفيذ المهارة..."), not part of the conversation.
     */
    private fun conversationHistory(): List<ChatMessageDto> = _messages.value
        .filter { it.role != MessageRole.SYSTEM }
        .map { message ->
            ChatMessageDto(
                role = if (message.isUser) "user" else "assistant",
                content = message.text
            )
        }
        .takeLast(CONTEXT_WINDOW_MESSAGES)

    private fun addMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    private suspend fun respond(request: AssistantRequest) {
        when (request) {
            is AssistantRequest.CallFunction -> {
                _statusText.value = STATUS_EXECUTING
                // A system bubble records which skill is running, before it reports back.
                addMessage(
                    ChatMessage(
                        text = describeAction(request.name),
                        isUser = false,
                        role = MessageRole.SYSTEM
                    )
                )
                try {
                    val result = skillManager.handleFunction(request.name, request.args)
                    respond(result)
                } catch (e: Exception) {
                    respond(SkillResult("عذراً، تعذّر تنفيذ الأمر", success = false))
                }
            }
            is AssistantRequest.Talk -> {
                respond(SkillResult(request.text, success = true))
            }
            is AssistantRequest.Unknown -> {
                respond(SkillResult("عذراً، لم أفهم طلبك", success = false))
            }
        }
    }

    /** Maps a tool name to the Arabic line shown in its system bubble. */
    private fun describeAction(name: String): String = when (name) {
        "organizeImages" -> "جاري تنظيم الصور..."
        "callContact" -> "جاري الاتصال..."
        "sendWhatsAppMessage" -> "جاري إرسال رسالة واتساب..."
        "changeDeviceSetting" -> "جاري تغيير إعدادات الجهاز..."
        "openSystemSetting" -> "جاري فتح إعدادات النظام..."
        "changeSystemSetting" -> "جاري تغيير إعداد النظام..."
        "openApp" -> "جاري فتح التطبيق..."
        "takeScreenshot" -> "جاري الاستعداد لالتقاط لقطة شاشة..."
        else -> "جاري تنفيذ الأمر..."
    }

    val pendingPermissionRequest = skillManager.pendingPermission
    val pendingConfirmation = skillManager.pendingConfirmation

    fun onPermissionDialogShown(id: String) {
        skillManager.onPermissionDialogShown(id)
    }

    fun onConfirmationResult(actionId: String, confirmed: Boolean) {
        scope.launch {
            skillManager.onConfirmationResult(actionId, confirmed)
        }
    }

    fun updateState(state: AssistantState) {
        _state.value = state
    }

    fun showError(message: String) {
        _errorMessage.value = message
        _messages.value = _messages.value + ChatMessage(message, isUser = false)
    }

    private fun respond(result: SkillResult) {
        _statusText.value = null
        _responseText.value = result.message
        _state.value = AssistantState.COMPLETED
        addMessage(ChatMessage(result.message, isUser = false))
        ttsManager.speak(result.message)
        // Reset to idle after a short delay conceptually - let UI decide
    }

    private fun handleRecognitionFailure() {
        if (_state.value != AssistantState.LISTENING) return
        _statusText.value = null
        _state.value = AssistantState.ERROR
        _errorMessage.value = "عذراً، لم أتمكن من الاستماع"
        _messages.value = _messages.value + ChatMessage(_errorMessage.value, isUser = false)
    }

    fun setPermissionRequester(requester: (String) -> Unit) {
        audioPermissionRequester = requester
        voiceRecognitionManager.permissionRequester = requester
    }

    /**
     * Skills publish [PendingPermissionRequest] on [skillManager.pendingPermission]. The Compose
     * UI observes it, launches the system dialog, calls [onPermissionDialogShown], and reports
     * the result via [onPermissionResult]. No launcher is stored in the ViewModel.
     */
    fun setSkillPermissionRequester(requester: (List<String>) -> Unit) {
        // kept for backward compatibility if still referenced; the new flow does not need it
    }

    fun destroy() {
        voiceRecognitionManager.destroy()
        ttsManager.shutdown()
        skillManager.dispose()
    }

    private companion object {
        const val STATUS_ANALYZING = "جاري تحليل الطلب..."
        const val STATUS_EXECUTING = "جاري تنفيذ المهارة..."

        /** Recent turns (user + assistant) sent to Groq as conversational memory. */
        const val CONTEXT_WINDOW_MESSAGES = 5
    }
}
