package com.rafeeq.assistant.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rafeeq.assistant.core.AssistantEngine
import com.rafeeq.assistant.core.AssistantState
import com.rafeeq.assistant.core.ChatMessage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = AssistantEngine(application)

    val state: StateFlow<AssistantState> = engine.state
    val messages: StateFlow<List<ChatMessage>> = engine.messages
    val recognizedText: StateFlow<String> = engine.recognizedText
    val responseText: StateFlow<String> = engine.responseText
    val errorMessage: StateFlow<String> = engine.errorMessage

    init {
        viewModelScope.launch {
            engine.permissionRequest.collect { permission ->
                engine.audioPermissionRequester?.invoke(permission)
            }
        }
    }

    fun startListening() {
        engine.startListening()
    }

    fun setPermissionRequester(requester: (String) -> Unit) {
        engine.setPermissionRequester(requester)
    }

    fun onAudioPermissionResult(isGranted: Boolean) {
        engine.onAudioPermissionResult(isGranted)
    }

    fun onPermissionResult(permission: String, isGranted: Boolean) {
        engine.onPermissionResult(permission, isGranted)
    }

    override fun onCleared() {
        engine.destroy()
        super.onCleared()
    }
}
