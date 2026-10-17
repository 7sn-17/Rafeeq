package com.rafeeq.assistant.ai

import android.util.Log
import com.google.gson.annotations.SerializedName
import com.rafeeq.assistant.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * The decision the model made: say something, or invoke one of our skills.
 */
sealed interface AssistantRequest {
    data class CallFunction(val name: String, val args: Map<String, String>) : AssistantRequest
    data class Talk(val text: String) : AssistantRequest
    data object Unknown : AssistantRequest
}

/* -------------------------------------------------------------------------------------
 * OpenAI / Groq request DTOs (serialized by Gson).
 * ----------------------------------------------------------------------------------- */

data class ChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<ChatMessageDto>,
    @SerializedName("tools") val tools: List<ToolDefinition>? = null,
    @SerializedName("tool_choice") val toolChoice: String? = null
)

data class ChatMessageDto(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class ToolDefinition(
    @SerializedName("type") val type: String = "function",
    @SerializedName("function") val function: FunctionDefinition
)

data class FunctionDefinition(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String,
    @SerializedName("parameters") val parameters: Map<String, Any>
)

/* -------------------------------------------------------------------------------------
 * OpenAI / Groq response DTOs (deserialized by Gson).
 * ----------------------------------------------------------------------------------- */

data class ChatResponse(
    @SerializedName("choices") val choices: List<Choice>? = null
)

data class Choice(
    @SerializedName("index") val index: Int? = null,
    @SerializedName("message") val message: AssistantMessage? = null,
    @SerializedName("finish_reason") val finishReason: String? = null
)

data class AssistantMessage(
    @SerializedName("role") val role: String? = null,
    @SerializedName("content") val content: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null
)

data class ToolCall(
    @SerializedName("id") val id: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("function") val function: FunctionCall? = null
)

data class FunctionCall(
    @SerializedName("name") val name: String? = null,
    @SerializedName("arguments") val arguments: String? = null
)

/** Groq's chat-completion endpoint. Groq is wire-compatible with the OpenAI API. */
interface GroqApi {
    @POST("chat/completions")
    suspend fun chatCompletions(@Body request: ChatRequest): Response<ChatResponse>
}

/**
 * The app's brain, backed by the Groq API (OpenAI-compatible).
 *
 * Google's endpoint is geo-blocked, so every request is tried against an ordered list of
 * models: if one model answers with a retryable HTTP status (429/500/...) or fails at the
 * transport layer, the loop moves straight on to the next model. Only when every model has
 * failed does the caller see a graceful Arabic message.
 */
class AiService {
    private val apiKey: String = BuildConfig.GROQ_API_KEY.trim()

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(
                Interceptor { chain ->
                    val authorized = chain.request().newBuilder()
                        .header(AUTHORIZATION_HEADER, "Bearer $apiKey")
                        .header(CONTENT_TYPE_HEADER, "application/json")
                        .build()
                    chain.proceed(authorized)
                }
            )
            .build()
    }

    private val api: GroqApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqApi::class.java)
    }

    /**
     * Turns the conversation so far into an [AssistantRequest].
     *
     * [conversation] carries the last exchanges (most recent last) so the model can resolve
     * references such as "اجعله أقوى" that depend on earlier context. The final entry must be
     * the user's new utterance; anything earlier is background only.
     *
     * Falls back through [FALLBACK_MODELS] in order; returns a user-facing message when
     * every model has failed.
     */
    suspend fun interpret(conversation: List<ChatMessageDto>): AssistantRequest {
        val userSpeech = conversation.lastOrNull()?.takeIf { it.role == ROLE_USER }?.content
        if (userSpeech.isNullOrBlank()) return AssistantRequest.Unknown

        if (apiKey.isEmpty() || apiKey == NULL_LITERAL) {
            Log.w(TAG, "GROQ_API_KEY is missing")
            return AssistantRequest.Talk(MISSING_KEY_MESSAGE)
        }

        val messages = buildList {
            add(ChatMessageDto(role = ROLE_SYSTEM, content = SYSTEM_PROMPT))
            addAll(conversation.takeLast(CONTEXT_WINDOW_MESSAGES))
        }

        for (model in FALLBACK_MODELS) {
            try {
                val response = api.chatCompletions(
                    ChatRequest(
                        model = model,
                        messages = messages,
                        tools = TOOLS,
                        toolChoice = "auto"
                    )
                )

                if (!response.isSuccessful) {
                    // 429 Too Many Requests, 500 Server Error, 404 bad model id, ...
                    Log.w(TAG, "$model -> HTTP ${response.code()} ${response.message()}")
                    continue
                }

                val body = response.body()
                if (body == null) {
                    Log.w(TAG, "$model -> empty response body")
                    continue
                }
                return parseResponse(body)
            } catch (e: Exception) {
                // Network timeout, DNS failure, malformed payload, ...
                Log.w(TAG, "$model -> ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        Log.e(TAG, "All Groq models failed")
        return AssistantRequest.Talk(ALL_MODELS_FAILED_MESSAGE)
    }

    /** Convenience overload for a single utterance with no prior context. */
    suspend fun interpret(userSpeech: String): AssistantRequest =
        interpret(listOf(ChatMessageDto(role = ROLE_USER, content = userSpeech)))

    /** Reads `choices[0].message.tool_calls[0]`, falling back to plain `content`. */
    private fun parseResponse(body: ChatResponse): AssistantRequest {
        val message = body.choices?.firstOrNull()?.message
        val functionCall = message?.toolCalls?.firstOrNull()?.function
        val name = functionCall?.name

        if (!name.isNullOrEmpty()) {
            return AssistantRequest.CallFunction(
                name = name,
                args = parseArguments(functionCall?.arguments)
            )
        }

        val text = message?.content?.trim().orEmpty()
        return if (text.isEmpty()) AssistantRequest.Unknown else AssistantRequest.Talk(text)
    }

    /**
     * `tool_calls[].function.arguments` arrives as a JSON *string* (e.g. `{"sortBy":"date"}`).
     * Skills only ever expect strings, so every value is flattened with `toString()`.
     */
    private fun parseArguments(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.opt(key)
                if (value == null || value === JSONObject.NULL) continue
                result[key] = value.toString()
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse tool arguments: $raw", e)
            emptyMap()
        }
    }

    private companion object {
        const val TAG = "AiService"
        const val BASE_URL = "https://api.groq.com/openai/v1/"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val CONTENT_TYPE_HEADER = "Content-Type"
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val READ_TIMEOUT_SECONDS = 45L
        const val NULL_LITERAL = "null"

        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"

        /** How many recent turns are sent as context, including the current utterance. */
        const val CONTEXT_WINDOW_MESSAGES = 5

        /** Ordered fallback chain: fastest first, heaviest last. */
        val FALLBACK_MODELS = listOf(
            "openai/gpt-oss-20b",
            "qwen/qwen3.8-27b",
            "openai/gpt-oss-120b"
        )

        const val SYSTEM_PROMPT =
            "أنت رفيق، مساعد صوتي ذكي باللغة العربية. " +
                "عندما يرى المستخدم أمرًا يتطلب استخدام أداة معينة، استخدم أدواتك المتاحة. " +
                "لا تستخدم ردًا أخيريًا طويلًا إذا كان الأمر بسيطًا. " +
                "إذا لم يتطلب الأمر أي أداة، أجِب مباشرة بالعربية. " +
                "لا تدّعي نجاحًا تلقائيًا: إذا فتحت شاشة إعدادات أو خرجت من التطبيق، صِف ما فعلته بدقة وبالعربية. " +
                "لديك ذاكرة محادثة من الرسائل السابقة؛ استخدمها لفهم الإشارة والتتابع، " +
                "مثل ربط ضمائر وأسماء الإشارة بما سبقها، دون تكرار ما طلبه المستخدم سابقاً."

        const val MISSING_KEY_MESSAGE =
            "يحتاج التطبيق لمفتاح GROQ_API_KEY في local.properties"

        const val ALL_MODELS_FAILED_MESSAGE =
            "عذراً، خوادم الذكاء الاصطناعي مشغولة حالياً، يرجى المحاولة بعد قليل"

        val TOOLS = listOf(
            // organizeImages(sortBy: date | size)
            ToolDefinition(
                function = FunctionDefinition(
                    name = "organizeImages",
                    description = "ترتيب الصور في المعرض حسب التاريخ أو الحجم",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "sortBy" to mapOf(
                                "type" to "string",
                                "enum" to listOf("date", "size"),
                                "description" to "آلية الترتيب: date أو size"
                            )
                        ),
                        "required" to listOf("sortBy")
                    )
                )
            ),

            // callContact(name)
            ToolDefinition(
                function = FunctionDefinition(
                    name = "callContact",
                    description = "الاتصال باسم جهة اتصال معينة",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "name" to mapOf(
                                "type" to "string",
                                "description" to "اسم جهة الاتصال المطلوب الاتصال بها"
                            )
                        ),
                        "required" to listOf("name")
                    )
                )
            ),

            // sendWhatsAppMessage(contact, message)
            ToolDefinition(
                function = FunctionDefinition(
                    name = "sendWhatsAppMessage",
                    description = "إرسال رسالة واتساب لجهة اتصال معينة",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "contact" to mapOf(
                                "type" to "string",
                                "description" to "اسم جهة الاتصال أو رقم الهاتف"
                            ),
                            "message" to mapOf(
                                "type" to "string",
                                "description" to "نص الرسالة"
                            )
                        ),
                        "required" to listOf("contact", "message")
                    )
                )
            ),

            // changeDeviceSetting(setting, state)
            ToolDefinition(
                function = FunctionDefinition(
                    name = "changeDeviceSetting",
                    description = "تغيير إعدادات الجهاز مثل مستوى الصوت أو البلوتوث",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "setting" to mapOf(
                                "type" to "string",
                                "enum" to listOf("volume", "bluetooth"),
                                "description" to "اسم الإعداد: volume أو bluetooth"
                            ),
                            "state" to mapOf(
                                "type" to "string",
                                "enum" to listOf("up", "down", "on", "off", "mute", "unmute"),
                                "description" to "حالة الإعداد"
                            )
                        ),
                        "required" to listOf("setting", "state")
                    )
                )
            ),

            // changeSystemSetting(action, state) - the comprehensive system-control tool.
            ToolDefinition(
                function = FunctionDefinition(
                    name = "changeSystemSetting",
                    description = "التحكم في إعدادات النظام: وضع الطيران، نقطة الاتصال، NFC، عدم الإزعاج، تدوير الشاشة، الوضع الداكن",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "action" to mapOf(
                                "type" to "string",
                                "enum" to listOf(
                                    "airplane_mode",
                                    "hotspot",
                                    "nfc",
                                    "dnd",
                                    "rotation",
                                    "dark_mode"
                                ),
                                "description" to "اسم الإعداد المطلوب تغييره"
                            ),
                            "state" to mapOf(
                                "type" to "string",
                                "enum" to listOf("on", "off"),
                                "description" to "الحالة المطلوبة: on أو off"
                            )
                        ),
                        "required" to listOf("action", "state")
                    )
                )
            ),

            // openSystemSetting(setting) - mainly for wifi/bluetooth settings panel
            ToolDefinition(
                function = FunctionDefinition(
                    name = "openSystemSetting",
                    description = "فتح إعدادات النظام لعرض خيارات البلوتوث أو الواي فاي بدلاً من تغييرها مباشرة",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "setting" to mapOf(
                                "type" to "string",
                                "enum" to listOf("wifi", "bluetooth"),
                                "description" to "اسم الإعداد"
                            )
                        ),
                        "required" to listOf("setting")
                    )
                )
            ),

            // openApp(package)
            ToolDefinition(
                function = FunctionDefinition(
                    name = "openApp",
                    description = "فتح تطبيق مثبت على الجهاز باستخدام اسم حزمه",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "package" to mapOf(
                                "type" to "string",
                                "description" to "اسم حزمة التطبيق"
                            )
                        ),
                        "required" to listOf("package")
                    )
                )
            ),

            // takeScreenshot
            ToolDefinition(
                function = FunctionDefinition(
                    name = "takeScreenshot",
                    description = "التقاط لقطة شاشة. لا يمكن التقاطها بالكامل تلقائياً في هذا الإصدار؛ يُعاد توجيه المستخدم لإجرائها يدويًا",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to emptyMap<String, Any>(),
                        "required" to emptyList<String>()
                    )
                )
            )
        )
    }
}
