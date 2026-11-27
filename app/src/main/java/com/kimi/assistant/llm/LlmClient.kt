package com.kimi.assistant.llm

import com.kimi.assistant.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kullanıcının kendi API anahtarıyla çalışan yapay zekâ istemcisi.
 * OpenAI uyumlu /chat/completions uç noktalarını destekler; ayrıca Gemini ve
 * Anthropic için kendi biçimlerine çevirir.
 *
 * Anahtar yalnızca cihazda (SharedPreferences) tutulur ve sadece seçilen
 * sağlayıcıya gönderilir.
 */
class LlmClient(private val prefs: Prefs) {

    data class Msg(val role: String, val content: String)

    private val history = ArrayList<Msg>()

    fun resetHistory() = history.clear()

    fun historySnapshot(): List<Msg> = history.toList()

    /**
     * Modelden cevap alır. Ağ hatasında anlaşılır bir Türkçe hata döndürür.
     */
    suspend fun chat(userText: String, system: String): String = withContext(Dispatchers.IO) {
        val key = prefs.apiKey.trim()
        if (key.isEmpty()) {
            return@withContext "Yapay zekâ anahtarı ayarlanmamış. Ayarlar ekranından ekleyebilirsin."
        }

        history.add(Msg("user", userText))
        val trimmed = history.takeLast(12)

        val result = runCatching {
            when (prefs.provider.lowercase()) {
                "gemini", "google" -> callGemini(key, system, trimmed)
                "anthropic", "claude" -> callAnthropic(key, system, trimmed)
                else -> callOpenAiCompatible(key, system, trimmed)
            }
        }

        result.fold(
            onSuccess = { text ->
                history.add(Msg("assistant", text))
                if (history.size > 40) history.subList(0, history.size - 40).clear()
                text
            },
            onFailure = { e ->
                history.removeAt(history.size - 1)
                "Bağlanamadım: ${e.message ?: "bilinmeyen hata"}"
            }
        )
    }

    private fun callOpenAiCompatible(key: String, system: String, msgs: List<Msg>): String {
        val base = when {
            prefs.baseUrl.isNotBlank() -> prefs.baseUrl.trimEnd('/')
            prefs.provider.equals("groq", true) -> "https://api.groq.com/openai/v1"
            prefs.provider.equals("openrouter", true) -> "https://openrouter.ai/api/v1"
            prefs.provider.equals("deepseek", true) -> "https://api.deepseek.com/v1"
            else -> "https://api.openai.com/v1"
        }
        val body = JSONObject().apply {
            put("model", prefs.model)
            put("temperature", 0.4)
            put("max_tokens", 400)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                msgs.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
        }
        val raw = post("$base/chat/completions", key, body.toString(), mapOf())
        val json = JSONObject(raw)
        val choices = json.optJSONArray("choices") ?: throw IllegalStateException("boş cevap")
        if (choices.length() == 0) throw IllegalStateException("boş cevap")
        return choices.getJSONObject(0)
            .optJSONObject("message")?.optString("content")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("boş metin")
    }

    private fun callGemini(key: String, system: String, msgs: List<Msg>): String {
        val model = prefs.model.ifBlank { "gemini-2.0-flash" }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
        val body = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(
                JSONObject().put("text", system))))
            put("contents", JSONArray().apply {
                msgs.forEach { m ->
                    put(JSONObject().apply {
                        put("role", if (m.role == "assistant") "model" else "user")
                        put("parts", JSONArray().put(JSONObject().put("text", m.content)))
                    })
                }
            })
            put("generationConfig", JSONObject().put("temperature", 0.4).put("maxOutputTokens", 400))
        }
        val raw = post(url, null, body.toString(), mapOf())
        val json = JSONObject(raw)
        val cands = json.optJSONArray("candidates") ?: throw IllegalStateException("boş cevap")
        if (cands.length() == 0) throw IllegalStateException("boş cevap")
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("boş cevap")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString().trim().ifEmpty { throw IllegalStateException("boş metin") }
    }

    private fun callAnthropic(key: String, system: String, msgs: List<Msg>): String {
        val body = JSONObject().apply {
            put("model", prefs.model.ifBlank { "claude-3-5-haiku-20241022" })
            put("max_tokens", 400)
            put("system", system)
            put("messages", JSONArray().apply {
                msgs.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
        }
        val raw = post(
            "https://api.anthropic.com/v1/messages",
            key,
            body.toString(),
            mapOf("anthropic-version" to "2023-06-01")
        )
        val json = JSONObject(raw)
        val content = json.optJSONArray("content") ?: throw IllegalStateException("boş cevap")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val c = content.getJSONObject(i)
            if (c.optString("type") == "text") sb.append(c.optString("text"))
        }
        return sb.toString().trim().ifEmpty { throw IllegalStateException("boş metin") }
    }

    private fun post(
        url: String,
        bearer: String?,
        body: String,
        extraHeaders: Map<String, String>,
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 45_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (url.contains("anthropic.com")) conn.setRequestProperty("x-api-key", bearer ?: "")
            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream ?: return "{}", Charsets.UTF_8))
                .use { it.readText() }
            if (code !in 200..299) {
                val msg = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull() ?: "HTTP $code"
                throw IllegalStateException(msg.take(180))
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
