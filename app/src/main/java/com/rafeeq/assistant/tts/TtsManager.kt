package com.rafeeq.assistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** A single installed text-to-speech voice, as exposed to the settings screen. */
data class TtsVoice(
    val name: String,
    val displayName: String
)

/**
 * Wrapper around Android's [TextToSpeech] so Rafeeq can speak back to the user.
 *
 * - Prefers a high quality Arabic locale (`ar-SA`, then `ar-EG`, then plain `ar`).
 * - Lets the user pin a specific installed voice by name.
 * - Supports muting, which stops any utterance in flight immediately.
 *
 * The engine initializes asynchronously; utterances requested before it is ready are queued
 * and spoken as soon as initialization finishes.
 */
class TtsManager(context: Context) {

    private val appContext = context.applicationContext

    private var textToSpeech: TextToSpeech? = null
    private var isReady = false
    private var pendingUtterance: String? = null
    private var preferredVoiceName: String? = null

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    /** Bumped once the engine has finished initialising, so installed voices can be listed. */
    private val _readyTick = MutableStateFlow(0)
    val readyTick: StateFlow<Int> = _readyTick.asStateFlow()

    init {
        textToSpeech = TextToSpeech(appContext) { status -> handleInitialisation(status) }
    }

    /** Mutes or unmutes speech; muting stops whatever is being spoken right now. */
    fun setMuted(value: Boolean) {
        _muted.value = value
        if (value) {
            textToSpeech?.stop()
        }
    }

    /** Pins the installed voice named [name], or falls back to the engine default when null. */
    fun setPreferredVoice(name: String?) {
        preferredVoiceName = name
        textToSpeech?.let { applyVoice(it) }
    }

    fun preferredVoice(): String? = preferredVoiceName

    /**
     * Lists the Arabic voices currently installed on this device. Some engines return an
     * empty (or null) voice list, in which case the caller simply sees no choices.
     */
    fun listVoices(): List<TtsVoice> {
        val engine = textToSpeech ?: return emptyList()
        val voices = engine.voices ?: return emptyList()
        return voices
            .filter { it.isArabic() }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .map { TtsVoice(name = it.name, displayName = it.name) }
    }

    /** Speaks [text], waiting for the engine to become ready when necessary. */
    fun speak(text: String) {
        if (text.isBlank() || _muted.value) return
        val engine = textToSpeech
        if (isReady && engine != null) {
            speakNow(engine, text)
        } else {
            pendingUtterance = text
        }
    }

    /** Stops any in-flight utterance. */
    fun stop() {
        textToSpeech?.stop()
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
        _readyTick.value = _readyTick.value + 1
        val engine = textToSpeech ?: return

        applyArabicLanguage(engine)
        applyVoice(engine)
        pendingUtterance?.let { utterance ->
            pendingUtterance = null
            speakNow(engine, utterance)
        }
    }

    private fun speakNow(engine: TextToSpeech, text: String) {
        if (_muted.value) return
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Could not speak: $text")
        }
    }

    /**
     * Picks the best available Arabic locale. `ar-SA` and `ar-EG` ship the most natural
     * voices, so they are tried first; a generic `ar` is the last resort before giving up.
     */
    private fun applyArabicLanguage(engine: TextToSpeech) {
        val candidates = listOf(
            Locale("ar", "SA"),
            Locale("ar", "EG"),
            Locale("ar")
        )
        for (locale in candidates) {
            val availability = engine.isLanguageAvailable(locale)
            if (availability >= 0) {
                engine.setLanguage(locale)
                Log.i(TAG, "Using Arabic locale ${locale.toLanguageTag()} (score=$availability)")
                return
            }
        }
        Log.w(TAG, "Arabic voice data unavailable; falling back to the device language")
        engine.setLanguage(Locale.getDefault())
    }

    /** Applies the user's pinned voice, when it is still installed. */
    private fun applyVoice(engine: TextToSpeech) {
        val name = preferredVoiceName ?: return
        val installed: Voice? = engine.voices?.firstOrNull { it.name == name }
        if (installed != null) {
            engine.setVoice(installed)
        } else {
            Log.w(TAG, "Preferred voice '$name' is no longer installed")
        }
    }

    private fun Voice.isArabic(): Boolean = locale.language.equals("ar", ignoreCase = true)

    private companion object {
        const val TAG = "TtsManager"
        const val UTTERANCE_ID = "rafeeq_response"
    }
}
