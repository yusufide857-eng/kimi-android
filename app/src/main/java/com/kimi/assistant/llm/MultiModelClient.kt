package com.kimi.assistant.llm

import com.kimi.assistant.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Birden fazla yapay zekâ modelini aynı anda çalıştırıp cevaplarını birleştirir.
 *
 * İki mod:
 *  - PARALLEL: her model ayrı ayrı cevap verir, en hızlı/başarılı cevap kullanılır.
 *  - COUNCIL: modeller paralel cevap verir, sonra bir "hakem" model bunları
 *    tek ve tutarlı bir cevaba indirir (daha yavaş, daha isabetli).
 */
class MultiModelClient(private val prefs: Prefs) {

    data class ModelSpec(
        val provider: String,
        val model: String,
        val label: String,
    )

    data class ModelAnswer(
        val label: String,
        val text: String,
        val ok: Boolean,
        val millis: Long,
    )

    /** Virgülle ayrılmış model listesini çözer: "gemini:gemini-2.5-flash, openai:gpt-4o-mini" */
    fun parseModels(raw: String): List<ModelSpec> {
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { part ->
            val p = part.trim()
            if (p.isEmpty()) return@mapNotNull null
            val idx = p.indexOf(':')
            if (idx > 0) {
                val prov = p.substring(0, idx).trim().lowercase()
                val mdl = p.substring(idx + 1).trim()
                if (mdl.isEmpty()) null else ModelSpec(prov, mdl, "${prov}/${mdl}")
            } else {
                ModelSpec(prefs.provider, p, p)
            }
        }
    }

    /** Tüm modellerden paralel cevap toplar. */
    suspend fun askAll(
        userText: String,
        system: String,
        specs: List<ModelSpec>,
    ): List<ModelAnswer> = coroutineScope {
        val jobs = specs.map { spec ->
            async(Dispatchers.IO) {
                val started = System.currentTimeMillis()
                val client = LlmClient(prefs, spec.provider, spec.model)
                val result = runCatching { client.chatOnce(userText, system) }
                ModelAnswer(
                    label = spec.label,
                    text = result.getOrElse { "Hata: ${it.message}" },
                    ok = result.isSuccess,
                    millis = System.currentTimeMillis() - started,
                )
            }
        }
        jobs.map { it.await() }.sortedBy { it.millis }
    }

    /**
     * Çoklu model yanıtı üretir.
     * @param councilMode true ise hakem model cevapları birleştirir.
     * @return sesli okunacak nihai metin
     */
    suspend fun answer(
        userText: String,
        system: String,
        specs: List<ModelSpec>,
        councilMode: Boolean,
        judge: ModelSpec?,
    ): String = withContext(Dispatchers.IO) {
        if (specs.isEmpty()) {
            return@withContext LlmClient(prefs).chat(userText, system)
        }

        val answers = askAll(userText, system, specs)
        val good = answers.filter { it.ok && it.text.isNotBlank() }

        if (good.isEmpty()) {
            return@withContext answers.firstOrNull()?.text ?: "Modellerden cevap alamadım."
        }
        if (good.size == 1) return@withContext good.first().text

        if (!councilMode) {
            // En hızlı başarılı cevap
            return@withContext good.first().text
        }

        // COUNCIL: hakem modele özetlet
        val judgeSpec = judge ?: specs.first()
        val judgeClient = LlmClient(prefs, judgeSpec.provider, judgeSpec.model)
        val merged = good.joinToString("\n\n") { "${it.label} cevabı:\n${it.text}" }
        val prompt = buildString {
            appendLine("Kullanıcının sorusu: $userText")
            appendLine()
            appendLine("Farklı yapay zekâ modellerinin cevapları:")
            appendLine(merged)
            appendLine()
            append("Bu cevapları karşılaştır. Çelişki varsa en doğru olanı seç, ")
            append("gereksiz tekrarı at. Tek, kısa ve doğal Türkçe bir cevap yaz. ")
            append("En fazla iki cümle.")
        }
        val result = runCatching { judgeClient.chatOnce(prompt, system) }
        result.getOrElse { good.first().text }
    }

    /** Ayar ekranında göstermek için kısa özet. */
    fun summarize(answers: List<ModelAnswer>): String =
        answers.joinToString(" • ") { "${it.label}: ${if (it.ok) "${it.millis}ms" else "hata"}" }
}
