package com.example.androidassistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.androidassistant.skills.OrganizeImagesSkill
import com.example.androidassistant.speech.VoiceEvent
import com.example.androidassistant.speech.VoiceRecognitionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable snapshot of everything the UI needs to render. */
data class MainUiState(
    val isListening: Boolean = false,
    val isProcessing: Boolean = false,
    val statusMessage: String = "",
    val recognizedText: String = ""
)

/**
 * Holds the assistant state and wires the UI to the speech recognizer and the skills.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val resources = application.resources
    private val voiceRecognitionManager = VoiceRecognitionManager(application)
    private val organizeImagesSkill = OrganizeImagesSkill(application)

    private val _uiState = MutableStateFlow(
        MainUiState(statusMessage = resources.getString(R.string.status_ready))
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var listeningJob: Job? = null

    /** Invoked when the user taps the "استمع" (Listen) button. */
    fun onListenClicked() {
        startListening()
    }

    /** Invoked when the RECORD_AUDIO permission request is denied. */
    fun onRecordAudioPermissionDenied() {
        _uiState.update {
            it.copy(
                isListening = false,
                isProcessing = false,
                statusMessage = resources.getString(R.string.status_permission_denied)
            )
        }
    }

    private fun startListening() {
        if (!voiceRecognitionManager.isRecognitionAvailable()) {
            _uiState.update {
                it.copy(
                    isListening = false,
                    statusMessage = resources.getString(R.string.status_recognition_unavailable)
                )
            }
            return
        }

        listeningJob?.cancel()
        listeningJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isListening = true,
                    isProcessing = false,
                    recognizedText = "",
                    statusMessage = resources.getString(R.string.status_listening)
                )
            }

            voiceRecognitionManager.listen().collect(::handleVoiceEvent)
        }
    }

    private fun handleVoiceEvent(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.ReadyForSpeech ->
                _uiState.update { it.copy(statusMessage = resources.getString(R.string.status_listening)) }

            is VoiceEvent.EndOfSpeech ->
                _uiState.update { it.copy(isListening = false) }

            is VoiceEvent.PartialResult ->
                _uiState.update { it.copy(recognizedText = event.text) }

            is VoiceEvent.Result ->
                onTextRecognized(event.text)

            is VoiceEvent.Error ->
                _uiState.update {
                    it.copy(
                        isListening = false,
                        isProcessing = false,
                        statusMessage = event.message
                    )
                }
        }
    }

    private fun onTextRecognized(text: String) {
        _uiState.update {
            it.copy(
                isListening = false,
                recognizedText = text,
                statusMessage = resources.getString(R.string.status_recognized_prefix) + text
            )
        }

        if (text.isBlank()) {
            _uiState.update { it.copy(statusMessage = resources.getString(R.string.status_no_command)) }
            return
        }

        if (matchesOrganizeImagesTrigger(text)) {
            runOrganizeImagesSkill()
        }
    }

    private fun runOrganizeImagesSkill() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isProcessing = true,
                    statusMessage = resources.getString(R.string.status_processing)
                )
            }

            val movedCount = organizeImagesSkill.organize()

            _uiState.update {
                it.copy(
                    isProcessing = false,
                    statusMessage = resources.getString(R.string.status_moved_format, movedCount)
                )
            }
        }
    }

    private fun matchesOrganizeImagesTrigger(text: String): Boolean {
        val normalizedText = normalize(text)
        return OrganizeImagesSkill.TRIGGERS.any { normalizedText.contains(normalize(it)) }
    }

    /** Removes Arabic diacritics and collapses whitespace so phrases match regardless of tashkeel. */
    private fun normalize(value: String): String = value
        .replace(ARABIC_DIACRITICS, "")
        .replace(WHITESPACE, " ")
        .trim()

    private companion object {
        val ARABIC_DIACRITICS = Regex("[\\u0617-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06DC\\u06DF-\\u06E8\\u06EA-\\u06ED\\u0640]")
        val WHITESPACE = Regex("\\s+")
    }
}
