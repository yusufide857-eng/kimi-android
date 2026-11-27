package com.kimi.assistant

import com.kimi.assistant.voice.VoiceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class VoiceProfileTest {

    /** Sentetik "ses": belirli frekanslarda sinüs karışımı + gürültü. */
    private fun synth(freqs: List<Double>, seconds: Double = 2.0, seed: Int = 1): ShortArray {
        val n = (VoiceProfile.SAMPLE_RATE * seconds).toInt()
        val rnd = java.util.Random(seed.toLong())
        return ShortArray(n) { i ->
            var v = 0.0
            freqs.forEachIndexed { k, f -> v += sin(2 * Math.PI * f * i / VoiceProfile.SAMPLE_RATE) / (k + 1) }
            v += (rnd.nextDouble() - 0.5) * 0.05
            (v * 8000).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun `ayni ses kendine benzer`() {
        val a = VoiceProfile.embed(synth(listOf(180.0, 360.0, 540.0), seed = 1))
        val b = VoiceProfile.embed(synth(listOf(180.0, 360.0, 540.0), seed = 2))
        assertNotNull(a)
        assertNotNull(b)
        val sim = VoiceProfile.similarity(a!!.vector, b!!.vector)
        assertTrue("aynı ses benzerliği yüksek olmalı: $sim", sim > 0.9f)
    }

    @Test
    fun `farkli sesler ayrilir`() {
        val a = VoiceProfile.embed(synth(listOf(180.0, 360.0, 540.0)))!!
        val b = VoiceProfile.embed(synth(listOf(900.0, 1800.0, 2700.0)))!!
        val sim = VoiceProfile.similarity(a.vector, b.vector)
        assertTrue("farklı sesler daha düşük benzerlik: $sim", sim < 0.85f)
    }

    @Test
    fun `sessiz kayit null dondurur`() {
        val silent = ShortArray(VoiceProfile.SAMPLE_RATE) { 0 }
        assertEquals(null, VoiceProfile.embed(silent))
    }
}
