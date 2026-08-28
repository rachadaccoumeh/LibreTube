package com.github.libretube.helpers

import android.util.Log
import com.github.libretube.constants.PreferenceKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible chat completions client.
 * Works with OpenAI, Gemini (OpenAI-compatible endpoint), Ollama, and any custom OpenAI-compatible provider.
 */
object AiHelper {
    private const val TAG = "AiHelper"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Serializable
    data class ChatMessage(
        val role: String,
        val content: String
    )

    data class AiConfig(
        val apiUrl: String,
        val apiKey: String,
        val model: String
    )

    fun getConfig(): AiConfig? {
        val apiUrl = PreferenceHelper.getString(PreferenceKeys.AI_API_URL, "")
        val apiKey = PreferenceHelper.getString(PreferenceKeys.AI_API_KEY, "")
        val model = PreferenceHelper.getString(PreferenceKeys.AI_MODEL, "")

        if (apiUrl.isBlank() || model.isBlank()) return null
        // API key can be empty for local providers like Ollama
        return AiConfig(apiUrl, apiKey, model)
    }

    fun isConfigured(): Boolean = getConfig() != null

    suspend fun chat(
        messages: List<ChatMessage>,
        config: AiConfig
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val json = Json { ignoreUnknownKeys = true }

            val requestBody = buildJsonObject {
                put("model", config.model)
                put("messages", json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()),
                    messages
                ))
                put("temperature", 0.7)
                put("max_tokens", 2048)
            }

            val url = config.apiUrl.trimEnd('/') + "/chat/completions"
            Log.i(TAG, "chat: requesting URL=$url, model=${config.model}")

            val requestBuilder = Request.Builder()
                .url(url)
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))

            if (config.apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                Log.e(TAG, "API error ${response.code}: $responseBody")
                return@withContext Result.failure(Exception("API error ${response.code} [url=$url]"))
            }

            val jsonResponse = json.parseToJsonElement(responseBody ?: "")
            val content = jsonResponse.jsonObject["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.content
                ?: ""

            Result.success(content)
        } catch (e: Exception) {
            Log.e(TAG, "chat() failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun summarize(transcript: String, videoTitle: String, description: String = "", uploader: String = "", transcriptLanguage: String? = null): Result<String> {
        val config = getConfig() ?: return Result.failure(IllegalStateException("AI not configured"))
        val langInstruction = if (transcriptLanguage != null) {
            "IMPORTANT: Respond in the same language as the transcript ($transcriptLanguage). " +
                "If the user asks you to respond in a different language, follow their request instead. "
        } else {
            "IMPORTANT: Respond in the same language as the transcript. " +
                "If the user asks you to respond in a different language, follow their request instead. "
        }
        val systemMsg = ChatMessage(
            "system",
            "You are a helpful assistant that summarizes videos based on their transcript and description. " +
                langInstruction +
                "Write in simple, clear language that anyone can understand — even someone not familiar with the topic. " +
                "If the video uses technical or specialized terms, briefly explain them in plain words when first mentioned. " +
                "Provide a concise, informative summary of the video content. " +
                "IMPORTANT: Use the video description as additional context — it contains sources, references, links, and background info that may not be spoken in the transcript. " +
                "When mentioning sources or references, incorporate information from both the transcript and the description. " +
                "When referencing specific parts of the video, ALWAYS include timestamps in the exact format [MM:SS] — one timestamp per bracket, never combine multiple timestamps in one bracket. " +
                "For example, write [01:30] not [01:30, 02:15] and never write timestamps without brackets."
        )
        val contextPart = buildString {
            if (uploader.isNotBlank()) append("\nChannel: $uploader")
            if (description.isNotBlank()) append("\n\nVideo description:\n$description")
        }
        val userMsg = ChatMessage(
            "user",
            "Please summarize this video titled \"$videoTitle\" based on the following transcript:$contextPart\n\n$transcript"
        )
        return chat(listOf(systemMsg, userMsg), config)
    }

    suspend fun ask(
        question: String,
        transcript: String,
        videoTitle: String,
        conversationHistory: List<ChatMessage> = emptyList(),
        description: String = "",
        uploader: String = "",
        transcriptLanguage: String? = null
    ): Result<String> {
        val config = getConfig() ?: return Result.failure(IllegalStateException("AI not configured"))
        val contextPart = buildString {
            if (uploader.isNotBlank()) append("\nChannel: $uploader")
            if (description.isNotBlank()) append("\n\nVideo description:\n$description")
        }
        val langInstruction = if (transcriptLanguage != null) {
            "IMPORTANT: Respond in the same language as the transcript ($transcriptLanguage). " +
                "If the user asks you to respond in a different language, follow their request instead. "
        } else {
            "IMPORTANT: Respond in the same language as the transcript. " +
                "If the user asks you to respond in a different language, follow their request instead. "
        }
        val systemMsg = ChatMessage(
            "system",
            "You are a helpful assistant that answers questions about a video based on its transcript and description. " +
                langInstruction +
                "Write in simple, clear language that anyone can understand — even someone not familiar with the topic. " +
                "If the video or your answer uses technical or specialized terms, briefly explain them in plain words when first mentioned. " +
                "The video is titled \"$videoTitle\". " +
                "IMPORTANT: Use the video description as additional context — it contains sources, references, links, and background info that may not be spoken in the transcript. " +
                "When answering questions about sources, references, or background, check both the transcript and the description. " +
                "When referencing specific parts of the video, ALWAYS include timestamps in the exact format [MM:SS] — one timestamp per bracket, never combine multiple timestamps in one bracket. " +
                "For example, write [01:30] not [01:30, 02:15] and never write timestamps without brackets. " +
                "Keep answers concise and relevant to the video content.$contextPart\n\n" +
                "Transcript:\n$transcript"
        )
        val messages = mutableListOf(systemMsg)
        messages.addAll(conversationHistory)
        messages.add(ChatMessage("user", question))
        return chat(messages, config)
    }
}
