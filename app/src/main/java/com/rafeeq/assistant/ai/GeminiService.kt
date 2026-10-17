package com.rafeeq.assistant.ai

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.rafeeq.assistant.BuildConfig

/**
 * Thin wrapper around the Google Generative AI SDK.
 *
 * The API key is injected at build time as [BuildConfig.GEMINI_API_KEY] and is read from
 * `local.properties`, so it never ends up in the repository.
 */
class GeminiService {

    private val model: GenerativeModel? = BuildConfig.GEMINI_API_KEY
        .takeIf { it.isNotBlank() && it != NULL_LITERAL }
        ?.let { apiKey ->
            GenerativeModel(
                modelName = MODEL_NAME,
                apiKey = apiKey
            )
        }
        ?: run {
            Log.w(TAG, "GEMINI_API_KEY is missing; add it to local.properties and rebuild")
            null
        }

    /**
     * Asks Gemini to classify [userSpeech] into one of the assistant intents.
     *
     * @return the raw JSON string returned by the model, or a safe fallback
     *         (`{"intent": "UNKNOWN"}`) when the request cannot be made.
     */
    suspend fun analyzeIntent(userSpeech: String): String {
        val generativeModel = model ?: return UNKNOWN_INTENT_JSON
        return try {
            val response = generativeModel.generateContent(buildPrompt(userSpeech))
            response.text?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_INTENT_JSON
        } catch (e: Exception) {
            Log.e(TAG, "Gemini request failed", e)
            UNKNOWN_INTENT_JSON
        }
    }

    private fun buildPrompt(userSpeech: String): String = PROMPT_TEMPLATE.format(userSpeech)

    private companion object {
        const val TAG = "GeminiService"

        const val MODEL_NAME = "gemini-1.5-flash"

        /** Value produced by the build when the key is absent from local.properties. */
        const val NULL_LITERAL = "null"

        const val UNKNOWN_INTENT_JSON = """{"intent": "UNKNOWN"}"""

        /** [%s] is replaced with the user's speech. */
        const val PROMPT_TEMPLATE = "You are Rafeeq, an Arabic smart assistant. " +
            "Analyze this user command: \"%s\". " +
            "If the user wants to organize images, return exactly: " +
            "{\"intent\": \"ORGANIZE_IMAGES\", \"sort_by\": \"date\"} or \"size\". " +
            "If you don't understand, return {\"intent\": \"UNKNOWN\"}. " +
            "Return ONLY valid JSON."
    }
}
