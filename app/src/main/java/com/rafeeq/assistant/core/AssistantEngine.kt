package com.rafeeq.assistant.core

import android.content.Context
import com.rafeeq.assistant.ai.GeminiService
import com.rafeeq.assistant.permissions.PermissionsManager
import com.rafeeq.assistant.skills.CommunicationSkill
import com.rafeeq.assistant.skills.DeviceSettingsSkill
import com.rafeeq.assistant.skills.OrganizeImagesSkill
import com.rafeeq.assistant.skills.SkillManager
import com.rafeeq.assistant.skills.SkillResult
import com.rafeeq.assistant.tts.TtsManager
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

data class ChatMessage(
    val text: String,
    val isUser: Boolean
)

class AssistantEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val permissionsManager = PermissionsManager(context)
    private val voiceRecognitionManager = VoiceRecognitionManager(context)
    private val geminiService = GeminiService()
    private val organizeImagesSkill = OrganizeImagesSkill(context)
    private val communicationSkill = CommunicationSkill(context)
    private val deviceSettingsSkill = DeviceSettingsSkill(context)
    private val skillManager = SkillManager(
        organizeImagesSkill = organizeImagesSkill,
        communicationSkill = communicationSkill,
        deviceSettingsSkill = deviceSettingsSkill,
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

    private val _permissionRequest = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val permissionRequest = _permissionRequest.asSharedFlow()

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

    fun onAudioPermissionResult(isGranted: Boolean) {
        voiceRecognitionManager.onPermissionResult(isGranted)
    }

    fun onPermissionResult(permission: String, isGranted: Boolean) {
        scope.launch {
            skillManager.onPermissionResult(permission, isGranted)
        }
    }

    private suspend fun handleUserSpeech(speech: String) {
        _recognizedText.value = speech
        addMessage(ChatMessage(speech, isUser = true))
        _state.value = AssistantState.PROCESSING
        _state.value = AssistantState.ANALYZING

        try {
            val request = geminiService.interpret(speech)
            respond(request)
        } catch (e: Exception) {
            respond(SkillResult("عذراً، حدث خطأ غير متوقع", success = false))
        }
    }

    private fun addMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    private suspend fun respond(request: com.rafeeq.assistant.ai.AssistantRequest) {
        when (request) {
            is com.rafeeq.assistant.ai.AssistantRequest.CallFunction -> {
                // SkillManager may request permissions - let it handle via callbacks if needed
                try {
                    val result = skillManager.handleFunction(request.name, request.args)
                    respond(result)
                } catch (e: Exception) {
                    respond(SkillResult("عذراً، تعذّر تنفيذ الأمر", success = false))
                }
            }
            is com.rafeeq.assistant.ai.AssistantRequest.Talk -> {
                respond(SkillResult(request.text, success = true))
            }
            is com.rafeeq.assistant.ai.AssistantRequest.Unknown -> {
                respond(SkillResult("عذراً، لم أفهم طلبك", success = false))
            }
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
        _responseText.value = result.message
        _state.value = AssistantState.COMPLETED
        addMessage(ChatMessage(result.message, isUser = false))
        ttsManager.speak(result.message)
        // Reset to idle after a short delay conceptually - let UI decide
    }

    private fun handleRecognitionFailure() {
        if (_state.value != AssistantState.LISTENING) return
        _state.value = AssistantState.ERROR
        _errorMessage.value = "عذراً، لم أتمكن من الاستماع"
        _messages.value = _messages.value + ChatMessage(_errorMessage.value, isUser = false)
    }

    fun setPermissionRequester(requester: (String) -> Unit) {
        audioPermissionRequester = requester
        voiceRecognitionManager.permissionRequester = requester
    }

    fun destroy() {
        voiceRecognitionManager.destroy()
        ttsManager.shutdown()
    }
}
