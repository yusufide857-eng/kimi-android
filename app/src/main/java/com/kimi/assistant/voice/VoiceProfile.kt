package com.kimi.assistant.voice

import android.content.Context
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * "Sadece benim sesimle çalışsın" özelliğinin kalbi.
 *
 * Ses tanıma metne çevirir; metnin *kimin* söylediğini söylemez. Bu yüzden sesin
 * kendisinden bir "parmak izi" (speaker embedding) çıkarıp kayıtlı profil ile
 * karşılaştırıyoruz.
 *
 * Burada kullanılan yöntem hafif bir akustik özet:
 *  - 16 kHz mono PCM
 *  - 25 ms pencere / 10 ms adım, her pencereye FFT
 *  - mel-benzeri 24 bant enerjisi
 *  - bantlar arası delta (cepstral benzeri) katsayılar
 *  - her katsayı için ortalama + standart sapma
 *  - enerjisi düşük (sessiz) kareler atılır
 *
 * Bu, nöral bir d-talk/ECAPA modeli kadar güçlü değildir; aynı evdeki iki farklı
 * sesi ayırmaya yeter, radyo/TV sesini elemeye yeter, ama profesyonel güvenlik
 * seviyesi değildir. Kullanıcıya bunu söylemek gerekir.
 */
object VoiceProfile {

    const val SAMPLE_RATE = 16_000
    private const val FRAME = 400          // 25 ms
    private const val HOP = 160            // 10 ms
    private const val BANDS = 24
    private const val FFT_SIZE = 512

    data class Embedding(val vector: FloatArray) {
        fun toJson(): String {
            val a = JSONArray()
            vector.forEach { a.put(it.toDouble()) }
            return a.toString()
        }

        companion object {
            fun fromJson(s: String): Embedding? = runCatching {
                val a = JSONArray(s)
                Embedding(FloatArray(a.length()) { a.getDouble(it).toFloat() })
            }.getOrNull()
        }
    }

    /** Kısa bir kayıttan ses parmak izi çıkarır. Ses yoksa null. */
    fun embed(pcm16: ShortArray): Embedding? {
        val samples = pcm16.map { it / 32768f }.toFloatArray()
        if (samples.size < FRAME * 10) return null

        // Sessiz kareleri ele: RMS eşiği, kaydın en yüksek karesine göre
        val frames = ArrayList<FloatArray>()
        val rmsList = ArrayList<Float>()
        var i = 0
        while (i + FRAME <= samples.size) {
            val f = samples.copyOfRange(i, i + FRAME)
            // Hann penceresi
            for (n in 0 until FRAME) {
                f[n] *= (0.5f - 0.5f * kotlin.math.cos(2.0 * Math.PI * n / (FRAME - 1)).toFloat())
            }
            var sum = 0f
            for (v in f) sum += v * v
            val rms = sqrt(sum / FRAME)
            rmsList.add(rms)
            frames.add(f)
            i += HOP
        }
        if (frames.isEmpty()) return null
        val maxRms = rmsList.max()
        if (maxRms < 0.004f) return null

        val bandVectors = ArrayList<FloatArray>()
        for ((idx, f) in frames.withIndex()) {
            if (rmsList[idx] < maxRms * 0.25f) continue     // sessiz kareyi at
            val mag = magnitudeSpectrum(f)
            val bands = melBands(mag)
            // delta (ardışık bant farkı) -> konuşmacıya özgü spektral eğim
            val delta = FloatArray(BANDS - 1) { bands[it + 1] - bands[it] }
            bandVectors.add(bands + delta)
        }
        if (bandVectors.size < 8) return null

        val dim = bandVectors[0].size
        val mean = FloatArray(dim)
        for (v in bandVectors) for (d in 0 until dim) mean[d] += v[d]
        for (d in 0 until dim) mean[d] /= bandVectors.size
        val sd = FloatArray(dim)
        for (v in bandVectors) for (d in 0 until dim) {
            val diff = v[d] - mean[d]
            sd[d] += diff * diff
        }
        for (d in 0 until dim) sd[d] = sqrt(sd[d] / bandVectors.size)

        // Ortalamayı birleştir, ölçeği normalize et (ses seviyesinden bağımsız olsun)
        val out = FloatArray(dim * 2)
        for (d in 0 until dim) {
            out[d] = mean[d]
            out[dim + d] = sd[d]
        }
        val norm = sqrt(out.fold(0f) { acc, x -> acc + x * x })
        if (norm > 1e-6f) for (d in out.indices) out[d] /= norm
        return Embedding(out)
    }

    /** Kosinüs benzerliği: 1.0 aynı ses, 0.0 ilgisiz. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na < 1e-9f || nb < 1e-9f) return 0f
        return dot / (sqrt(na) * sqrt(nb))
    }

    private fun magnitudeSpectrum(frame: FloatArray): FloatArray {
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        for (n in frame.indices) re[n] = frame[n]
        fft(re, im)
        val mag = FloatArray(FFT_SIZE / 2)
        for (k in 0 until FFT_SIZE / 2) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
        return mag
    }

    /** 24 mel-benzeri bant: 80 Hz – 7600 Hz arası logaritmik. */
    private fun melBands(mag: FloatArray): FloatArray {
        val bins = mag.size
        val out = FloatArray(BANDS)
        val nyquist = SAMPLE_RATE / 2.0
        for (b in 0 until BANDS) {
            val f0 = 80.0 * Math.pow(7600.0 / 80.0, b.toDouble() / BANDS)
            val f1 = 80.0 * Math.pow(7600.0 / 80.0, (b + 1).toDouble() / BANDS)
            val k0 = ((f0 / nyquist) * bins).toInt().coerceIn(0, bins - 1)
            val k1 = ((f1 / nyquist) * bins).toInt().coerceIn(k0 + 1, bins)
            var e = 0f
            for (k in k0 until k1) e += mag[k]
            out[b] = ln(1.0 + e).toFloat()
        }
        return out
    }

    /** Yerinde (in-place) yinelemeli olmayan FFT. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = kotlin.math.cos(ang).toFloat()
            val wi = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curR = 1f
                var curI = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * curR - im[i + k + len / 2] * curI
                    val vi = re[i + k + len / 2] * curI + im[i + k + len / 2] * curR
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nr = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** İki kaydın aynı kişi olma olasılığını kaba bir güvenle döndürür. */
    fun verdict(sim: Float, threshold: Float): String = when {
        sim >= threshold + 0.08f -> "kesin"
        sim >= threshold -> "muhtemel"
        sim >= threshold - 0.10f -> "şüpheli"
        else -> "başkası"
    }

    fun describe(sim: Float): String {
        val pct = (abs(sim) * 100).toInt()
        return "benzerlik %$pct"
    }
}

/**
 * Kayıtlı ses profili deposu. Birden fazla örnek saklanır; en iyi eşleşme kullanılır.
 */
class VoiceProfileStore(context: Context) {

    private val sp = context.getSharedPreferences("kimi_voice", Context.MODE_PRIVATE)

    var samples: List<VoiceProfile.Embedding>
        get() {
            val raw = sp.getString(KEY, null) ?: return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull {
                    VoiceProfile.Embedding.fromJson(arr.getString(it))
                }
            }.getOrDefault(emptyList())
        }
        private set(value) {
            val arr = JSONArray()
            value.forEach { arr.put(it.toJson()) }
            sp.edit().putString(KEY, arr.toString()).apply()
        }

    val hasProfile: Boolean get() = samples.isNotEmpty()

    fun addSample(e: VoiceProfile.Embedding) {
        samples = (samples + e).takeLast(MAX_SAMPLES)
    }

    fun clear() = sp.edit().remove(KEY).apply()

    /** Kayıtlı örneklerle en yüksek benzerlik. Profil yoksa -1. */
    fun bestMatch(e: VoiceProfile.Embedding): Float {
        val all = samples
        if (all.isEmpty()) return -1f
        return all.maxOf { VoiceProfile.similarity(it.vector, e.vector) }
    }

    companion object {
        private const val KEY = "samples"
        private const val MAX_SAMPLES = 5
    }
}
