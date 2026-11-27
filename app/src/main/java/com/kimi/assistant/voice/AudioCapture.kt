package com.kimi.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ses kaydı (kayıt sırasında hem PCM hem seviye) ve metin okuma (TTS).
 */
class AudioCapture(private val context: Context) {

    @Volatile private var running = false
    private var record: AudioRecord? = null

    /**
     * Belirtilen süre boyunca (veya [stopWhen] true dönene kadar) kayıt yapar.
     * Dönen PCM 16 kHz mono, 16-bit.
     */
    suspend fun record(
        maxMillis: Long,
        silenceStopMillis: Long = 1200,
        onLevel: ((Float) -> Unit)? = null,
        stopWhen: (() -> Boolean)? = null,
    ): ShortArray = withContext(Dispatchers.IO) {
        val sampleRate = VoiceProfile.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, sampleRate / 2)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            return@withContext ShortArray(0)
        }

        record = rec
        running = true
        val out = ArrayList<Short>(sampleRate * 3)
        val chunk = ShortArray(1024)
        val started = System.currentTimeMillis()
        var lastLoud = started

        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    for (i in 0 until n) out.add(chunk[i])
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = chunk[i] / 32768.0
                        sum += v * v
                    }
                    val rms = kotlin.math.sqrt(sum / n)
                    onLevel?.invoke(rms.toFloat().coerceIn(0f, 1f))
                    if (rms > 0.008) lastLoud = System.currentTimeMillis()
                }
                val now = System.currentTimeMillis()
                if (stopWhen?.invoke() == true) break
                if (now - started > maxMillis) break
                if (now - lastLoud > silenceStopMillis && now - started > 700) break
            }
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
            record = null
            running = false
        }
        out.toShortArray()
    }

    /** Kısa bir "ses var mı" ölçümü. */
    suspend fun peakLevel(millis: Long = 300): Float = withContext(Dispatchers.IO) {
        val pcm = record(maxMillis = millis, silenceStopMillis = millis)
        if (pcm.isEmpty()) return@withContext 0f
        var sum = 0.0
        for (s in pcm) {
            val v = s / 32768.0
            sum += v * v
        }
        kotlin.math.sqrt(sum / pcm.size).toFloat()
    }

    fun stop() {
        running = false
    }

    companion object {
        fun hasMicPermission(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }
}

/** Basit TTS sarmalayıcı; hazır olana kadar bekler. */
class Speaker(context: Context) {

    private var tts: TextToSpeech? = null
    private val ready = CountDownLatch(1)

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching {
                    tts?.language = Locale.forLanguageTag("tr-TR")
                    tts?.setSpeechRate(1.02f)
                    tts?.setPitch(1.0f)
                }
            }
            ready.countDown()
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        Thread {
            runCatching { ready.await(3, TimeUnit.SECONDS) }
            runCatching {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kimi-${System.currentTimeMillis()}")
            }
        }.start()
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }
}
