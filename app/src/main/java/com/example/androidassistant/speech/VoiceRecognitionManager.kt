package com.example.androidassistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

/**
 * Thin, lifecycle-aware wrapper around Android's built-in [SpeechRecognizer].
 *
 * Call [listen] to obtain a cold [Flow] of [VoiceEvent]s. The flow starts a recognition
 * session when collected and tears the recognizer down when the collector is cancelled,
 * which makes it safe to collect from a `viewModelScope`.
 *
 * The caller is responsible for ensuring [android.Manifest.permission.RECORD_AUDIO] has
 * been granted before collecting the flow.
 */
class VoiceRecognitionManager(private val context: Context) {

    /** Whether a speech recognition service is installed and available on this device. */
    fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Starts a listening session and emits recognition events until the session ends
     * (a final result, an error, or cancellation by the collector).
     *
     * @param languageTag BCP-47 language tag passed to the recognizer. Defaults to Arabic.
     */
    fun listen(languageTag: String = DEFAULT_LANGUAGE_TAG): Flow<VoiceEvent> = callbackFlow {
        if (!isRecognitionAvailable()) {
            trySend(VoiceEvent.Error("Speech recognition is not available on this device"))
            close()
            return@callbackFlow
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(VoiceEvent.ReadyForSpeech)
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                trySend(VoiceEvent.EndOfSpeech)
            }

            override fun onError(error: Int) {
                trySend(VoiceEvent.Error(describeError(error)))
                close()
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                trySend(VoiceEvent.Result(text))
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotEmpty()) {
                    trySend(VoiceEvent.PartialResult(text))
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        recognizer.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer.startListening(intent)

        awaitClose {
            recognizer.stopListening()
            recognizer.cancel()
            recognizer.destroy()
        }
    }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing RECORD_AUDIO permission"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
        else -> "Unknown recognition error ($error)"
    }

    private companion object {
        const val DEFAULT_LANGUAGE_TAG = "ar"
    }
}
