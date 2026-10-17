package com.rafeeq.assistant.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Wraps Android's [SpeechRecognizer].
 *
 * - [startListening] requests the RECORD_AUDIO permission at runtime when needed.
 * - [stopListening] lets the user cancel, without that being reported as a failure.
 * - Recognized text is published on [recognizedText].
 * - Failures (permission denied, recognizer errors, no results) are published on [recognitionFailed].
 *
 * Because the runtime permission dialog can only be launched from an activity, the activity binds
 * [permissionRequester]; the manager stays the owner of the permission policy and of the result.
 */
class VoiceRecognitionManager(context: Context) {

    private val appContext = context.applicationContext

    private var speechRecognizer: SpeechRecognizer? = null

    /** True while a user-initiated stop is in flight, so it is not reported as an error. */
    @Volatile
    private var cancelRequested = false

    /** Set by the activity; receives the permission that has to be requested. */
    var permissionRequester: ((String) -> Unit)? = null

    private val _recognizedText = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val recognizedText: Flow<String> = _recognizedText.asSharedFlow()

    private val _recognitionFailed = MutableSharedFlow<Unit>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val recognitionFailed: Flow<Unit> = _recognitionFailed.asSharedFlow()

    /** Starts listening, requesting RECORD_AUDIO first if it has not been granted yet. */
    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Log.w(TAG, "Speech recognition is not available on this device")
            fail()
            return
        }

        if (hasAudioPermission()) {
            startListeningInternal()
        } else {
            val requester = permissionRequester
            if (requester != null) {
                Log.i(TAG, "Requesting RECORD_AUDIO permission")
                requester(Manifest.permission.RECORD_AUDIO)
            } else {
                Log.w(TAG, "No permission requester bound; cannot request RECORD_AUDIO")
                fail()
            }
        }
    }

    /**
     * Cancels an in-progress listening session because the user tapped the mic again.
     * The recognizer reports a cancellation through [RecognitionListener.onError]; the
     * [cancelRequested] flag keeps that from surfacing as an error message.
     */
    fun stopListening() {
        cancelRequested = true
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel speech recognition", e)
        }
    }

    /** Called by the activity once the RECORD_AUDIO permission dialog has been answered. */
    fun onPermissionResult(isGranted: Boolean) {
        if (isGranted) {
            startListeningInternal()
        } else {
            Log.w(TAG, "RECORD_AUDIO permission was denied")
            fail()
        }
    }

    private fun startListeningInternal() {
        cancelRequested = false
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()

            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer.setRecognitionListener(recognitionListener)
            recognizer.startListening(buildRecognitionIntent())
            speechRecognizer = recognizer

            Log.i(TAG, "Started listening")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognition", e)
            fail()
        }
    }

    private fun buildRecognitionIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun fail() {
        _recognitionFailed.tryEmit(Unit)
    }

    /** Releases the underlying recognizer; call from the owning ViewModel's onCleared. */
    fun destroy() {
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private val recognitionListener = object : RecognitionListener {

        override fun onResults(results: Bundle) {
            cancelRequested = false
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (text.isNullOrBlank()) {
                Log.w(TAG, "Speech recognizer returned no results")
                fail()
            } else {
                Log.i(TAG, "Recognized: $text")
                _recognizedText.tryEmit(text)
            }
        }

        override fun onError(error: Int) {
            if (cancelRequested) {
                cancelRequested = false
                Log.i(TAG, "Recognition was stopped by the user")
                return
            }
            Log.e(TAG, "Speech recognition error, code=$error")
            fail()
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val TAG = "VoiceRecognitionManager"

        /** The assistant is driven by Arabic voice commands. */
        const val LANGUAGE_TAG = "ar"
    }
}
