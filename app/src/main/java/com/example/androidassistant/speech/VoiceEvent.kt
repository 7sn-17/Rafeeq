package com.example.androidassistant.speech

/**
 * Events emitted by [VoiceRecognitionManager] while a recognition session is active.
 */
sealed interface VoiceEvent {
    /** The recognizer is ready and listening for audio. */
    data object ReadyForSpeech : VoiceEvent

    /** The speaker stopped talking; the recognizer is processing the audio. */
    data object EndOfSpeech : VoiceEvent

    /** An intermediate recognition result (only emitted when partial results are requested). */
    data class PartialResult(val text: String) : VoiceEvent

    /** The final recognized text. */
    data class Result(val text: String) : VoiceEvent

    /** Something went wrong. [message] is a human readable description. */
    data class Error(val message: String) : VoiceEvent
}
