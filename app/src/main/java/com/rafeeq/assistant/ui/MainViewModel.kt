package com.rafeeq.assistant.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rafeeq.assistant.skills.OrganizeImagesSkill
import com.rafeeq.assistant.voice.VoiceRecognitionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * High level state of the assistant, rendered by [MainScreen].
 */
sealed interface AssistantState {
    /** Idle and ready for a new command. */
    data object Ready : AssistantState

    /** The speech recognizer is capturing audio. */
    data object Listening : AssistantState

    /** A command was recognized and is being handled. */
    data object Processing : AssistantState
}

/**
 * Holds the UI state and orchestrates speech recognition plus the assistant skills.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val voiceRecognitionManager = VoiceRecognitionManager(application)
    private val organizeImagesSkill = OrganizeImagesSkill(application)

    private val _state = MutableStateFlow<AssistantState>(AssistantState.Ready)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            voiceRecognitionManager.recognizedText.collect { text ->
                processRecognizedText(text)
            }
        }
        viewModelScope.launch {
            voiceRecognitionManager.recognitionFailed.collect {
                if (_state.value == AssistantState.Listening) {
                    _state.value = AssistantState.Ready
                }
            }
        }
    }

    /** Called when the "listen" button is tapped. */
    fun startListening() {
        if (_state.value != AssistantState.Ready) return
        _state.value = AssistantState.Listening
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

    private suspend fun processRecognizedText(text: String) {
        Log.i(TAG, "Recognized text: $text")
        _state.value = AssistantState.Processing
        try {
            if (organizeImagesSkill.matches(text)) {
                organizeImagesSkill.execute()
            } else {
                Log.i(TAG, "No skill matched the recognized text")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle recognized text", e)
        } finally {
            _state.value = AssistantState.Ready
        }
    }

    override fun onCleared() {
        voiceRecognitionManager.destroy()
        super.onCleared()
    }

    private companion object {
        const val TAG = "MainViewModel"
    }
}
