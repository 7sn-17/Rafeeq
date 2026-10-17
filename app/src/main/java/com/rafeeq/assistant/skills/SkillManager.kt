package com.rafeeq.assistant.skills

import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * Outcome of a routed intent: the [message] is what Rafeeq speaks back to the user and
 * [success] decides whether the UI ends in a success or an error state.
 */
data class SkillResult(
    val message: String,
    val success: Boolean
)

/**
 * Routes the JSON produced by Gemini to the matching skill.
 *
 * Example input:
 * ```
 * {"intent": "ORGANIZE_IMAGES", "sort_by": "date"}
 * ```
 */
class SkillManager(private val organizeImagesSkill: OrganizeImagesSkill) {

    /**
     * Executes the skill described by [rawResponse].
     * Unknown or malformed responses fall back to a polite "did not understand" answer.
     */
    suspend fun handle(rawResponse: String): SkillResult {
        val json = parseJson(rawResponse)
        if (json == null) {
            return SkillResult(UNKNOWN_MESSAGE, success = false)
        }

        val intent = json.optString(KEY_INTENT).orEmpty()
        return when (intent) {
            INTENT_ORGANIZE_IMAGES -> organizeImages(json)
            else -> {
                Log.i(TAG, "Unknown intent: '$intent' (raw: $rawResponse)")
                SkillResult(UNKNOWN_MESSAGE, success = false)
            }
        }
    }

    private suspend fun organizeImages(json: JSONObject): SkillResult {
        val sortBy = json.optString(KEY_SORT_BY, SORT_BY_DATE)
            .trim()
            .lowercase()
            .takeIf { it == SORT_BY_SIZE } ?: SORT_BY_DATE

        return try {
            val movedCount = organizeImagesSkill.execute(sortBy)
            Log.i(TAG, "OrganizeImagesSkill moved $movedCount images sorted by $sortBy")
            val message = when (sortBy) {
                SORT_BY_SIZE -> ORGANIZING_BY_SIZE_MESSAGE
                else -> ORGANIZING_BY_DATE_MESSAGE
            }
            SkillResult(message, success = true)
        } catch (e: Exception) {
            Log.e(TAG, "OrganizeImagesSkill failed", e)
            SkillResult(SKILL_FAILED_MESSAGE, success = false)
        }
    }

    /** Tolerates models that wrap the JSON in prose or markdown code fences. */
    private fun parseJson(rawResponse: String): JSONObject? {
        val start = rawResponse.indexOf('{')
        val end = rawResponse.lastIndexOf('}')
        if (start == -1 || end <= start) {
            Log.w(TAG, "No JSON object found in response: $rawResponse")
            return null
        }
        return try {
            JSONObject(rawResponse.substring(start, end + 1))
        } catch (e: JSONException) {
            Log.e(TAG, "Malformed JSON in response: $rawResponse", e)
            null
        }
    }

    private companion object {
        const val TAG = "SkillManager"

        const val KEY_INTENT = "intent"
        const val KEY_SORT_BY = "sort_by"

        const val INTENT_ORGANIZE_IMAGES = "ORGANIZE_IMAGES"

        const val SORT_BY_DATE = "date"
        const val SORT_BY_SIZE = "size"

        const val ORGANIZING_BY_DATE_MESSAGE = "جاري تنظيم الصور حسب التاريخ"
        const val ORGANIZING_BY_SIZE_MESSAGE = "جاري تنظيم الصور حسب الحجم"
        const val UNKNOWN_MESSAGE = "عذراً، لم أفهم طلبك"
        const val SKILL_FAILED_MESSAGE = "عذراً، تعذّر تنظيم الصور"
    }
}
