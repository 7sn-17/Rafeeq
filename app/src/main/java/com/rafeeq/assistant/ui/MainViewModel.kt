package com.rafeeq.assistant.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rafeeq.assistant.core.AssistantEngine
import com.rafeeq.assistant.core.AssistantState
import com.rafeeq.assistant.core.ChatMessage
import com.rafeeq.assistant.tts.TtsVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** User-selectable day/night mode; persisted across launches. */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = AssistantEngine(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val state: StateFlow<AssistantState> = engine.state
    val messages: StateFlow<List<ChatMessage>> = engine.messages
    val recognizedText: StateFlow<String> = engine.recognizedText
    val responseText: StateFlow<String> = engine.responseText
    val errorMessage: StateFlow<String> = engine.errorMessage
    val statusText: StateFlow<String?> = engine.statusText
    val ttsMuted: StateFlow<Boolean> = engine.ttsMuted

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _ttsEnabled = MutableStateFlow(prefs.getBoolean(KEY_TTS_ENABLED, true))
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    private val _ttsVoices = MutableStateFlow<List<TtsVoice>>(emptyList())
    val ttsVoices: StateFlow<List<TtsVoice>> = _ttsVoices.asStateFlow()

    private val _selectedVoice = MutableStateFlow(prefs.getString(KEY_TTS_VOICE, null))
    val selectedVoice: StateFlow<String?> = _selectedVoice.asStateFlow()

    val appVersion: String = com.rafeeq.assistant.BuildConfig.VERSION_NAME

    init {
        viewModelScope.launch {
            engine.permissionRequest.collect { permission ->
                engine.audioPermissionRequester?.invoke(permission)
            }
        }
        viewModelScope.launch {
            engine.ttsReadyTick.collect {
                refreshVoices()
            }
        }
        engine.setTtsVoice(_selectedVoice.value)
    }

    fun toggleRecording() {
        if (state.value == AssistantState.LISTENING) {
            engine.stopListening()
        } else {
            engine.startListening()
        }
    }

    fun sendText(text: String) {
        engine.sendText(text)
    }

    fun newChat() {
        engine.clearHistory()
    }

    fun clearHistory() {
        engine.clearHistory()
    }

    fun setTtsMuted(muted: Boolean) {
        engine.setTtsMuted(muted)
    }

    fun toggleTtsMuted() {
        engine.setTtsMuted(!ttsMuted.value)
    }

    fun setTtsEnabled(enabled: Boolean) {
        _ttsEnabled.value = enabled
        prefs.edit().putBoolean(KEY_TTS_ENABLED, enabled).apply()
        // Turning speech off also mutes anything currently being spoken.
        if (!enabled) engine.setTtsMuted(true)
    }

    /** Re-reads the Arabic voices installed on this device. */
    fun refreshVoices() {
        _ttsVoices.value = engine.listTtsVoices()
    }

    fun selectVoice(name: String?) {
        _selectedVoice.value = name
        prefs.edit().putString(KEY_TTS_VOICE, name).apply()
        engine.setTtsVoice(name)
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun setPermissionRequester(requester: (String) -> Unit) {
        engine.setPermissionRequester(requester)
    }

    fun setSkillPermissionRequester(requester: (String) -> Unit) {
        engine.setSkillPermissionRequester(requester)
    }

    fun onAudioPermissionResult(isGranted: Boolean) {
        engine.onAudioPermissionResult(isGranted)
    }

    fun onPermissionResult(permission: String, isGranted: Boolean) {
        engine.onPermissionResult(permission, isGranted)
    }

    private fun loadThemeMode(): ThemeMode {
        val stored = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
    }

    override fun onCleared() {
        engine.destroy()
        super.onCleared()
    }

    private companion object {
        const val PREFS_NAME = "rafeeq_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_TTS_ENABLED = "tts_enabled"
        const val KEY_TTS_VOICE = "tts_voice"
    }
}
