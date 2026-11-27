package com.kimi.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Türkçe konuşmayı metne çevirir. Cihazın kendi tanıma motorunu kullanır
 * (cihazda destekleniyorsa internet gerekmez, değilse Google servisi kullanılır).
 */
class SpeechToText(private val context: Context) {

    data class Result(val text: String, val isFinal: Boolean)

    private var recognizer: SpeechRecognizer? = null

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
        }

    /**
     * Tek bir komut dinler. [onPartial] ile ara sonuçlar gelir (uyandırma kelimesini
     * erken yakalamak için). Nihai metin döndürülür; hata olursa boş string.
     */
    suspend fun listenOnce(
        onPartial: ((String) -> Unit)? = null,
        onLevel: ((Float) -> Unit)? = null,
        cancelSignal: (() -> Boolean)? = null,
    ): String = suspendCancellableCoroutine { cont ->
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            cont.resume("")
            return@suspendCancellableCoroutine
        }

        var finished = false
        fun finish(text: String) {
            if (finished) return
            finished = true
            runCatching { recognizer?.destroy() }
            recognizer = null
            if (cont.isActive) cont.resume(text)
        }

        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                onLevel?.invoke((rmsdB.coerceIn(-2f, 12f) + 2f) / 14f)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                finish("")
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                finish(list?.firstOrNull().orEmpty())
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                list?.firstOrNull()?.let { onPartial?.invoke(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        cont.invokeOnCancellation {
            runCatching { r.cancel() }
            runCatching { r.destroy() }
        }

        runCatching { r.startListening(buildIntent()) }.onFailure { finish("") }
    }

    fun destroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    companion object {
        fun locale(): Locale = Locale.forLanguageTag("tr-TR")
    }
}
