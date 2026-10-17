package com.rafeeq.assistant.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rafeeq.assistant.ai.GeminiService
import com.rafeeq.assistant.skills.OrganizeImagesSkill
import com.rafeeq.assistant.skills.SkillManager
import com.rafeeq.assistant.skills.SkillResult
import com.rafeeq.assistant.tts.TtsManager
import com.rafeeq.assistant.voice.VoiceRecognitionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** High level state of the assistant, rendered by [MainScreen]. */
enum class AssistantState {
    /** Waiting for the user to start a command. */
    IDLE,

    /** The speech recognizer is capturing audio. */
    LISTENING,

    /** The command is being analysed / executed. */
    PROCESSING,

    /** A skill ran successfully. */
    SUCCESS,

    /** Something went wrong (permission denied, recognizer error, unknown intent). */
    ERROR
}

/**
 * Holds the UI state and orchestrates the whole assistant pipeline:
 * speech recognition → Gemini intent analysis → skill execution → spoken response.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val voiceRecognitionManager = VoiceRecognitionManager(application)
    private val geminiService = GeminiService()
    private val skillManager = SkillManager(OrganizeImagesSkill(application))
    private val ttsManager = TtsManager(application)

    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _responseText = MutableStateFlow("")
    val responseText: StateFlow<String> = _responseText.asStateFlow()

    init {
        viewModelScope.launch {
            voiceRecognitionManager.recognizedText.collect { speech ->
                handleUserSpeech(speech)
            }
        }
        viewModelScope.launch {
            voiceRecognitionManager.recognitionFailed.collect {
                handleRecognitionFailure()
            }
        }
    }

    /** Called when the microphone button is tapped. */
    fun startListening() {
        if (_state.value == AssistantState.LISTENING || _state.value == AssistantState.PROCESSING) {
            return
        }
        _recognizedText.value = ""
        _responseText.value = ""
        _state.value = AssistantState.LISTENING
        voiceRecognitionManager.startListening()
    }

    /** Binds the activity owned permission launcher to the recognition manager. */
    fun setAudioPermissionRequester(requester: (String) -> Unit) {
        voiceRecognitionManager.permissionRequester = requester
    }

    /** Result of the runtime RECORD_AUDIO permission dialog. */
    fun onAudioPermissionResult(isGranted: Boolean) {
        voiceRecognitionManager.onPermissionResult(isGranted)
    }

    private suspend fun handleUserSpeech(speech: String) {
        Log.i(TAG, "Recognized speech: $speech")
        _recognizedText.value = speech
        _state.value = AssistantState.PROCESSING

        try {
            val intentJson = geminiService.analyzeIntent(speech)
            Log.i(TAG, "Gemini intent: $intentJson")
            respond(skillManager.handle(intentJson))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle the recognized command", e)
            respond(SkillResult(UNEXPECTED_ERROR_MESSAGE, success = false))
        }
    }

    private fun respond(result: SkillResult) {
        _responseText.value = result.message
        _state.value = if (result.success) AssistantState.SUCCESS else AssistantState.ERROR
        ttsManager.speak(result.message)
    }

    private fun handleRecognitionFailure() {
        if (_state.value != AssistantState.LISTENING) return
        Log.w(TAG, "Speech recognition failed or was cancelled")
        _state.value = AssistantState.ERROR
        _responseText.value = LISTENING_FAILED_MESSAGE
    }

    override fun onCleared() {
        voiceRecognitionManager.destroy()
        ttsManager.shutdown()
        super.onCleared()
    }

    private companion object {
        const val TAG = "MainViewModel"

        const val LISTENING_FAILED_MESSAGE = "عذراً، لم أتمكن من الاستماع"
        const val UNEXPECTED_ERROR_MESSAGE = "عذراً، حدث خطأ غير متوقع"
    }
}
