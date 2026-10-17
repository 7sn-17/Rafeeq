package com.rafeeq.assistant.ai

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.FunctionCallingConfig
import com.google.ai.client.generativeai.type.FunctionCallingMode
import com.google.ai.client.generativeai.type.GenerationConfig
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.ToolConfig
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.defineFunction
import com.google.ai.client.generativeai.type.generationConfig
import com.rafeeq.assistant.BuildConfig
import org.json.JSONObject

sealed interface AssistantRequest {
    data class CallFunction(val name: String, val args: Map<String, String>) : AssistantRequest
    data class Talk(val text: String) : AssistantRequest
    data object Unknown : AssistantRequest
}

/**
 * Gemini service using official FunctionDeclaration for AI function calling.
 */
class GeminiService {
    private val model: GenerativeModel? = BuildConfig.GEMINI_API_KEY
        .takeIf { it.isNotBlank() && it != NULL_LITERAL }
        ?.let { apiKey ->
            GenerativeModel(
                modelName = MODEL_NAME,
                apiKey = apiKey,
                tools = listOf(Tool(rafeeqTools)),
                toolConfig = ToolConfig(FunctionCallingConfig(FunctionCallingMode.AUTO)),
                systemInstruction = content {
                    text(
                        "أنت رفيق، مساعد صوتي ذكي باللغة العربية. " +
                            "عندما يرى المستخدم أمرًا يتطلب استخدام أداة معينة، استخدم أدواتك المتاحة. " +
                            "لا تستخدم ردًا أخيريًا طويلًا إذا كان الأمر بسيطًا. " +
                            "إذا لم يتطلب الأمر أي أداة، أجِب مباشرة بالعربية."
                    )
                },
                generationConfig = generationConfig {
                    temperature = 0.4f
                }
            )
        }
        ?: run {
            Log.w(TAG, "GEMINI_API_KEY is missing")
            null
        }

    suspend fun interpret(userSpeech: String): AssistantRequest {
        val generativeModel = model ?: return AssistantRequest.Talk("يحتاج التطبيق لمفتاح Gemini في local.properties")
        return try {
            val response = generativeModel.generateContent(userSpeech)
            val call = response.functionCalls.firstOrNull()
            if (call != null) {
                return AssistantRequest.CallFunction(call.name, call.args)
            }
            val text = response.text?.trim().orEmpty()
            if (text.isEmpty()) AssistantRequest.Unknown else AssistantRequest.Talk(text)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini request failed", e)
            AssistantRequest.Unknown
        }
    }

    private companion object {
        const val TAG = "GeminiService"
        const val MODEL_NAME = "gemini-1.5-flash"
        const val NULL_LITERAL = "null"

        val organizeImagesFunc = defineFunction(
            name = "organizeImages",
            description = "ترتيب الصور في المعرض حسب التاريخ أو الحجم",
            arg1 = Schema.enum("sortBy", "آلية الترتيب: date أو size", listOf("date", "size"))
        ) { sortBy: String ->
            JSONObject(mapOf("status" to "delegated_to_skill_manager", "sortBy" to sortBy))
        }

        val callContactFunc = defineFunction(
            name = "callContact",
            description = "الاتصال باسم جهة اتصال معينة",
            arg1 = Schema.str("name", "اسم جهة الاتصال المطلوب الاتصال بها")
        ) { name: String ->
            JSONObject(mapOf("status" to "delegated_to_skill_manager", "name" to name))
        }

        val sendWhatsAppMessageFunc = defineFunction(
            name = "sendWhatsAppMessage",
            description = "إرسال رسالة واتساب لجهة اتصال معينة",
            arg1 = Schema.str("contact", "اسم جهة الاتصال أو رقم الهاتف"),
            arg2 = Schema.str("message", "نص الرسالة")
        ) { contact: String, message: String ->
            JSONObject(mapOf("status" to "delegated_to_skill_manager", "contact" to contact, "message" to message))
        }

        val changeDeviceSettingFunc = defineFunction(
            name = "changeDeviceSetting",
            description = "تغيير إعدادات الجهاز مثل مستوى الصوت أو البلوتوث",
            arg1 = Schema.enum(
                "setting",
                "اسم الإعداد: volume أو bluetooth",
                listOf("volume", "bluetooth")
            ),
            arg2 = Schema.enum(
                "state",
                "حالة الإعداد: up/down/on/off",
                listOf("up", "down", "on", "off", "mute", "unmute")
            )
        ) { setting: String, state: String ->
            JSONObject(mapOf("status" to "delegated_to_skill_manager", "setting" to setting, "state" to state))
        }

        val rafeeqTools = listOf(
            organizeImagesFunc,
            callContactFunc,
            sendWhatsAppMessageFunc,
            changeDeviceSettingFunc
        )
    }
}
