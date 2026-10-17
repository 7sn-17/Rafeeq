package com.rafeeq.assistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Minimal wrapper around Android's [TextToSpeech] so Rafeeq can speak back to the user.
 *
 * The engine initializes asynchronously; utterances requested before it is ready are queued
 * and spoken as soon as initialization finishes.
 */
class TtsManager(context: Context) {

    private val appContext = context.applicationContext

    private var textToSpeech: TextToSpeech? = null
    private var isReady = false
    private var pendingUtterance: String? = null

    init {
        textToSpeech = TextToSpeech(appContext) { status -> handleInitialisation(status) }
    }

    /** Speaks [text], waiting for the engine to become ready when necessary. */
    fun speak(text: String) {
        if (text.isBlank()) return
        val engine = textToSpeech
        if (isReady && engine != null) {
            speakNow(engine, text)
        } else {
            pendingUtterance = text
        }
    }

    /** Releases the engine; call from the owning ViewModel's onCleared. */
    fun shutdown() {
        pendingUtterance = null
        isReady = false
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    private fun handleInitialisation(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech initialisation failed (status=$status)")
            return
        }

        isReady = true
        val engine = textToSpeech ?: return

        applyArabicLanguage(engine)
        pendingUtterance?.let { utterance ->
            pendingUtterance = null
            speakNow(engine, utterance)
        }
    }

    private fun speakNow(engine: TextToSpeech, text: String) {
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Could not speak: $text")
        }
    }

    private fun applyArabicLanguage(engine: TextToSpeech) {
        val result = engine.setLanguage(Locale("ar"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Arabic voice data unavailable; falling back to the device language")
            engine.setLanguage(Locale.getDefault())
        }
    }

    private companion object {
        const val TAG = "TtsManager"
        const val UTTERANCE_ID = "rafeeq_response"
    }
}
