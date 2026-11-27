package com.kimi.assistant.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Tüm ayarlar tek yerde. API anahtarı cihazda saklanır, hiçbir yere gönderilmez
 * (yalnızca seçtiğin sağlayıcıya istek atılırken kullanılır).
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("kimi_prefs", Context.MODE_PRIVATE)

    // --- Yapay zekâ sağlayıcısı ---
    var provider: String
        get() = sp.getString(K_PROVIDER, "openai") ?: "openai"
        set(v) = sp.edit().putString(K_PROVIDER, v).apply()

    var apiKey: String
        get() = sp.getString(K_API_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_API_KEY, v).apply()

    var model: String
        get() = sp.getString(K_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"
        set(v) = sp.edit().putString(K_MODEL, v).apply()

    var baseUrl: String
        get() = sp.getString(K_BASE_URL, "") ?: ""
        set(v) = sp.edit().putString(K_BASE_URL, v).apply()

    // --- Ses ---
    /** Yalnızca kayıtlı ses sahibinin komutları çalışsın mı? */
    var voiceLockEnabled: Boolean
        get() = sp.getBoolean(K_VOICE_LOCK, true)
        set(v) = sp.edit().putBoolean(K_VOICE_LOCK, v).apply()

    /** Ses eşleşme eşiği (0..1). Yüksek = daha katı. */
    var voiceThreshold: Float
        get() = sp.getFloat(K_VOICE_THRESHOLD, 0.72f)
        set(v) = sp.edit().putFloat(K_VOICE_THRESHOLD, v).apply()

    /** Uyandırma kelimesi. Boşsa uyandırma kelimesi beklenmez. */
    var wakeWord: String
        get() = sp.getString(K_WAKE_WORD, "kimi") ?: "kimi"
        set(v) = sp.edit().putString(K_WAKE_WORD, v).apply()

    var wakeWordEnabled: Boolean
        get() = sp.getBoolean(K_WAKE_WORD_ON, false)
        set(v) = sp.edit().putBoolean(K_WAKE_WORD_ON, v).apply()

    /** Cevap sesli okunsun mu? */
    var speakReplies: Boolean
        get() = sp.getBoolean(K_SPEAK, true)
        set(v) = sp.edit().putBoolean(K_SPEAK, v).apply()

    /** Uyandırma kelimesinden sonra kaç saniye komut dinlensin. */
    var listenSeconds: Int
        get() = sp.getInt(K_LISTEN_SECS, 6)
        set(v) = sp.edit().putInt(K_LISTEN_SECS, v).apply()


    // --- Sağlayıcıya özel anahtarlar (çoklu model için) ---
    var geminiKey: String
        get() = sp.getString(K_GEMINI, "") ?: ""
        set(v) = sp.edit().putString(K_GEMINI, v).apply()

    var openAiKey: String
        get() = sp.getString(K_OPENAI, "") ?: ""
        set(v) = sp.edit().putString(K_OPENAI, v).apply()

    var anthropicKey: String
        get() = sp.getString(K_ANTHROPIC, "") ?: ""
        set(v) = sp.edit().putString(K_ANTHROPIC, v).apply()

    var groqKey: String
        get() = sp.getString(K_GROQ, "") ?: ""
        set(v) = sp.edit().putString(K_GROQ, v).apply()

    var openRouterKey: String
        get() = sp.getString(K_OPENROUTER, "") ?: ""
        set(v) = sp.edit().putString(K_OPENROUTER, v).apply()

    var deepSeekKey: String
        get() = sp.getString(K_DEEPSEEK, "") ?: ""
        set(v) = sp.edit().putString(K_DEEPSEEK, v).apply()

    // --- Çoklu model (aynı anda birden fazla yapay zekâ) ---
    var multiModelEnabled: Boolean
        get() = sp.getBoolean(K_MULTI_ON, false)
        set(v) = sp.edit().putBoolean(K_MULTI_ON, v).apply()

    /** "gemini:gemini-2.5-flash, openai:gpt-4o-mini, groq:llama-3.3-70b-versatile" */
    var multiModels: String
        get() = sp.getString(K_MULTI_MODELS, "") ?: ""
        set(v) = sp.edit().putString(K_MULTI_MODELS, v).apply()

    /** true = hakem model cevapları birleştirir (council), false = en hızlı cevap. */
    var councilMode: Boolean
        get() = sp.getBoolean(K_COUNCIL, true)
        set(v) = sp.edit().putBoolean(K_COUNCIL, v).apply()

    var judgeModel: String
        get() = sp.getString(K_JUDGE, "") ?: ""
        set(v) = sp.edit().putString(K_JUDGE, v).apply()

    /** Son çoklu model yanıtları (gösterim için). */
    var lastMultiSummary: String
        get() = sp.getString(K_MULTI_SUMMARY, "") ?: ""
        set(v) = sp.edit().putString(K_MULTI_SUMMARY, v).apply()

    // --- Onay ---
    /** Geri alınamaz işlemler (mesaj, arama, SMS) her zaman onay ister. Sabit true bırakılır. */
    val alwaysConfirm: Boolean get() = true

    // --- Sessiz saatler ---
    var quietStart: Int
        get() = sp.getInt(K_QUIET_START, -1)
        set(v) = sp.edit().putInt(K_QUIET_START, v).apply()

    var quietEnd: Int
        get() = sp.getInt(K_QUIET_END, -1)
        set(v) = sp.edit().putInt(K_QUIET_END, v).apply()

    // --- Asistan kişiliği ---
    var assistantName: String
        get() = sp.getString(K_NAME, "Kimi") ?: "Kimi"
        set(v) = sp.edit().putString(K_NAME, v).apply()

    var systemPrompt: String
        get() = sp.getString(K_SYSTEM, DEFAULT_SYSTEM) ?: DEFAULT_SYSTEM
        set(v) = sp.edit().putString(K_SYSTEM, v).apply()

    /** Son işlem kaydı (JSON satırları, yeniden eskiye). */
    fun appendLog(line: String) {
        val old = sp.getString(K_LOG, "") ?: ""
        val lines = (listOf(line) + old.split("\n").filter { it.isNotBlank() })
            .take(100)
        sp.edit().putString(K_LOG, lines.joinToString("\n")).apply()
    }

    fun readLog(): List<String> =
        (sp.getString(K_LOG, "") ?: "").split("\n").filter { it.isNotBlank() }

    fun clearLog() = sp.edit().putString(K_LOG, "").apply()

    companion object {

        private const val K_GEMINI = "key_gemini"
        private const val K_OPENAI = "key_openai"
        private const val K_ANTHROPIC = "key_anthropic"
        private const val K_GROQ = "key_groq"
        private const val K_OPENROUTER = "key_openrouter"
        private const val K_DEEPSEEK = "key_deepseek"
        private const val K_MULTI_ON = "multi_on"
        private const val K_MULTI_MODELS = "multi_models"
        private const val K_COUNCIL = "council_mode"
        private const val K_JUDGE = "judge_model"
        private const val K_MULTI_SUMMARY = "multi_summary"
        private const val K_PROVIDER = "provider"
        private const val K_API_KEY = "api_key"
        private const val K_MODEL = "model"
        private const val K_BASE_URL = "base_url"
        private const val K_VOICE_LOCK = "voice_lock"
        private const val K_VOICE_THRESHOLD = "voice_threshold"
        private const val K_WAKE_WORD = "wake_word"
        private const val K_WAKE_WORD_ON = "wake_word_on"
        private const val K_SPEAK = "speak_replies"
        private const val K_LISTEN_SECS = "listen_secs"
        private const val K_QUIET_START = "quiet_start"
        private const val K_QUIET_END = "quiet_end"
        private const val K_NAME = "assistant_name"
        private const val K_SYSTEM = "system_prompt"
        private const val K_LOG = "command_log"

        const val DEFAULT_SYSTEM =
            "Sen Kimi adlı Türkçe konuşan bir telefon asistanısın. Kısa, net ve doğal konuş. " +
                "Cevapların en fazla iki cümle olsun çünkü sesli okunacak."
    }
}
