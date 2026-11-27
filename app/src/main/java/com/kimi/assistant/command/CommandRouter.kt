package com.kimi.assistant.command

import android.content.Context
import com.kimi.assistant.llm.LlmClient
import com.kimi.assistant.service.AccessibilityBridge
import com.kimi.assistant.service.ConfirmBus
import com.kimi.assistant.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Calendar
import java.util.regex.Pattern

/**
 * Komutları iki katmanda çözer:
 *  1) Yerel niyetler — hızlı, internet gerektirmez, cihaz işlevlerini doğrudan çağırır.
 *  2) Yerel çözülemeyenler — LLM'e gönderilir; cevap sesli okunur.
 *
 * Geri alınamaz işlemler (mesaj gönderme, arama, SMS) daima onay ister.
 */
class CommandRouter(
    private val context: Context,
    private val prefs: Prefs,
    private val llm: LlmClient,
) {

    private val device = DeviceActions(context)

    data class Reply(
        val say: String,
        /** Onay bekleyen bir işlem varsa tarifi; null ise işlem tamam. */
        val pending: PendingAction? = null,
        val speak: Boolean = true,
    )

    sealed class PendingAction {
        data class WhatsAppMessage(val contact: String, val message: String) : PendingAction()
        data class Call(val contact: String, val number: String) : PendingAction()
        data class Sms(val contact: String, val number: String, val message: String) : PendingAction()
    }

    /** Kayıtlı ses profili varsa ve eşik altındaysa komut çalıştırılmaz. */
    fun isOwner(similarity: Float): Boolean {
        if (!prefs.voiceLockEnabled) return true
        return similarity >= prefs.voiceThreshold
    }

    suspend fun handle(rawText: String): Reply = withContext(Dispatchers.Default) {
        val text = rawText.trim()
        if (text.isEmpty()) return@withContext Reply("Seni duyamadım.", speak = true)

        val t = norm(text)

        // --- Uygulama gezinme / ekran kontrolü (erişilebilirlik) ---
        screenCommand(t)?.let { return@withContext it }

        // --- WhatsApp mesajı ---
        parseWhatsApp(t, text)?.let { return@withContext it }

        // --- Arama ---
        parseCall(t)?.let { return@withContext it }

        // --- SMS ---
        parseSms(t, text)?.let { return@withContext it }

        // --- Alarm / zamanlayıcı ---
        parseAlarm(t)?.let { return@withContext it }
        parseTimer(t)?.let { return@withContext it }

        // --- Uygulama aç ---
        parseOpenApp(t)?.let { r ->
            val outcome = device.openApp(r)
            return@withContext Reply(outcome.say)
        }

        // --- Medya ---
        parseMedia(t)?.let { action ->
            val o = device.mediaControl(action)
            return@withContext Reply(if (o.ok) "Tamam." else o.say)
        }

        // --- Sistem anahtarları ---
        parseSystem(t)?.let { return@withContext it }

        // --- Takvim ---
        parseCalendar(t, text)?.let { return@withContext it }

        // --- Yerel olarak çözülemedi: yapay zekâ ---
        val answer = llm.chat(text, prefs.systemPrompt)
        Reply(answer)
    }

    // ================= EKRAN / GEZİNME =================

    private fun screenCommand(t: String): Reply? {
        val bridge = AccessibilityBridge.instance
        fun need(): Reply? =
            if (bridge == null) Reply("Bunun için erişilebilirlik iznini açman gerekiyor. Ayarlar ekranından açabilirsin.") else null

        return when {
            t.contains("reels") && (t.contains("gec") || t.contains("sonraki") || t.contains("kaydir")) -> {
                need() ?: run {
                    bridge!!.swipeUp()
                    Reply("Sonraki reel.")
                }
            }
            t.contains("reels") && (t.contains("geri") || t.contains("onceki")) -> {
                need() ?: run {
                    bridge!!.swipeDown()
                    Reply("Önceki reel.")
                }
            }
            t.contains("kaydir") && t.contains("yukari") -> {
                need() ?: run { bridge!!.swipeUp(); Reply("Kaydırdım.") }
            }
            t.contains("kaydir") && t.contains("asagi") -> {
                need() ?: run { bridge!!.swipeDown(); Reply("Kaydırdım.") }
            }
            t.contains("geri don") || t == "geri" || t.contains("geri tusu") -> {
                need() ?: run { bridge!!.back(); Reply("Geri döndüm.") }
            }
            t.contains("ana ekran") || t.contains("home tusu") -> {
                need() ?: run { bridge!!.home(); Reply("Ana ekrana döndüm.") }
            }
            t.contains("son uygulama") || t.contains("uygulamalar arasi") -> {
                need() ?: run { bridge!!.recents(); Reply("Son uygulamalar.") }
            }
            t.contains("bildirim") && t.contains("ac") -> {
                need() ?: run { bridge!!.openNotifications(); Reply("Bildirimleri açtım.") }
            }
            t.contains("begen") || t.contains("like") -> {
                need() ?: run {
                    val ok = bridge!!.tapByText(listOf("Like", "Beğen", "Begen"))
                    Reply(if (ok) "Beğendim." else "Beğen düğmesini bulamadım.")
                }
            }
            t.contains("yorum") && t.contains("ac") -> {
                need() ?: run {
                    val ok = bridge!!.tapByText(listOf("Comment", "Yorum"))
                    Reply(if (ok) "Yorumları açtım." else "Yorum düğmesini bulamadım.")
                }
            }
            t.contains("paylas") -> {
                need() ?: run {
                    val ok = bridge!!.tapByText(listOf("Share", "Paylaş", "Paylas"))
                    Reply(if (ok) "Paylaşımı açtım." else "Paylaş düğmesini bulamadım.")
                }
            }
            else -> null
        }
    }

    // ================= WHATSAPP =================

    private val whatsappPatterns = listOf(
        Pattern.compile("(?:whatsapp|vatsap|wp)\\s*(?:dan|den|'dan|'den)?\\s*(.+?)\\s*(?:kisisine|kisisine|e|a|ya|ye)?\\s*(?:mesaj)?\\s*(?:yaz|gonder|soyle|at)\\s*[:,]?\\s*(.+)"),
        Pattern.compile("(.+?)\\s*(?:kisisine|kisisine)\\s*(?:whatsapp|vatsap)\\s*(?:dan|den)?\\s*(?:mesaj)?\\s*(?:yaz|gonder|at)\\s*[:,]?\\s*(.+)"),
    )

    private fun parseWhatsApp(t: String, original: String): Reply? {
        if (!t.contains("whatsapp") && !t.contains("vatsap") && !t.contains("wp'")) return null
        for (p in whatsappPatterns) {
            val m = p.matcher(t)
            if (m.find()) {
                val contact = cleanName(m.group(1) ?: "")
                val message = extractMessage(original, contact)
                if (contact.isNotBlank() && message.isNotBlank()) {
                    return Reply(
                        say = "$contact kişisine “$message” göndermek üzereyim. Onaylıyor musun?",
                        pending = PendingAction.WhatsAppMessage(contact, message)
                    )
                }
            }
        }
        // "WhatsApp'ta Ali'ye yaz: yarın görüşelim"
        val simple = Pattern.compile("(?:whatsapp|vatsap)(?:'?ta|'?da)?\\s+(.+?)(?:'?(?:ye|ya|e|a))?\\s+(?:mesaj\\s+)?(?:yaz|gonder|at)\\s*[:,]?\\s*(.+)")
            .matcher(t)
        if (simple.find()) {
            val contact = cleanName(simple.group(1) ?: "")
            val message = simple.group(2)?.trim().orEmpty()
            if (contact.isNotBlank() && message.isNotBlank()) {
                return Reply(
                    say = "$contact kişisine “$message” göndermek üzereyim. Onaylıyor musun?",
                    pending = PendingAction.WhatsAppMessage(contact, message)
                )
            }
        }
        return null
    }

    /** Orijinal (noktalı) metinden mesaj gövdesini çıkarır. */
    private fun extractMessage(original: String, contact: String): String {
        val idx = original.indexOf(':')
        if (idx > 0 && idx < original.length - 1) return original.substring(idx + 1).trim()
        val t = norm(original)
        val k = t.indexOf(contact)
        if (k >= 0) {
            val after = original.substring(minOf(original.length, k + contact.length)).trim()
            return after.replaceFirst(Regex("^[^ ]*\\s*(kişisine|kisisine|mesaj|yaz|gönder|gonder|at|[:,])*\\s*"), "").trim()
        }
        return ""
    }

    // ================= ARAMA =================

    private fun parseCall(t: String): Reply? {
        val p = Pattern.compile("(.+?)\\s*(?:'?(?:yi|yu|i|u|yi|ı)?\\s*)?(?:ara|telefon et|arar misin)")
        if (!t.contains("ara") && !t.contains("telefon et")) return null
        if (t.contains("arama")) return null
        val m = p.matcher(t)
        if (!m.find()) return null
        val name = cleanName(m.group(1) ?: "")
        if (name.isBlank() || name.length < 2) return null
        return Reply(
            say = "$name kişisini aramak üzereyim. Onaylıyor musun?",
            pending = PendingAction.Call(name, "")
        )
    }

    // ================= SMS =================

    private fun parseSms(t: String, original: String): Reply? {
        if (!t.contains("sms") && !t.contains("mesaj gonder") && !t.contains("mesaj at")) return null
        if (t.contains("whatsapp") || t.contains("vatsap")) return null
        val p = Pattern.compile("(.+?)\\s*(?:'?(?:ye|ya|e|a))?\\s*sms\\s*(?:mesaj)?\\s*(?:yaz|gonder|at)\\s*[:,]?\\s*(.+)")
        val m = p.matcher(t)
        if (m.find()) {
            val contact = cleanName(m.group(1) ?: "")
            val message = original.substringAfter(':', m.group(2) ?: "").trim()
            if (contact.isNotBlank() && message.isNotBlank()) {
                return Reply(
                    say = "$contact kişisine SMS olarak “$message” göndermek üzereyim. Onaylıyor musun?",
                    pending = PendingAction.Sms(contact, "", message)
                )
            }
        }
        return null
    }

    // ================= ALARM / ZAMANLAYICI =================

    private fun parseAlarm(t: String): Reply? {
        if (!t.contains("alarm")) return null
        val (h, m) = parseTime(t) ?: return null
        val o = device.setAlarm(h, m)
        return Reply(if (o.ok) "Alarmı ${"%02d:%02d".format(h, m)} için kurdum." else o.say)
    }

    private fun parseTimer(t: String): Reply? {
        if (!t.contains("zamanlayici") && !t.contains("kronometre") && !t.contains("sayac")) return null
        val p = Pattern.compile("(\\d+)\\s*(saniye|dakika|saat)")
        val m = p.matcher(t)
        if (!m.find()) return null
        val n = m.group(1)?.toIntOrNull() ?: return null
        val secs = when (m.group(2)) {
            "saniye" -> n
            "dakika" -> n * 60
            "saat" -> n * 3600
            else -> n
        }
        val o = device.setTimer(secs)
        return Reply(if (o.ok) "Zamanlayıcıyı ${secs / 60} dakikaya kurdum." else o.say)
    }

    private fun parseTime(t: String): Pair<Int, Int>? {
        val p = Pattern.compile("(\\d{1,2})[:.]?(\\d{2})?")
        val m = p.matcher(t)
        while (m.find()) {
            val h = m.group(1)?.toIntOrNull() ?: continue
            val mm = m.group(2)?.toIntOrNull() ?: 0
            if (h in 0..23 && mm in 0..59) return h to mm
        }
        // "yediyi çeyrek geçe" gibi karmaşık ifadeler LLM'e kalsın
        return null
    }

    // ================= UYGULAMA =================

    private fun parseOpenApp(t: String): String? {
        val p = Pattern.compile("(?:uygulamasini\\s+)?(?:ac|baslat)\\s*[:,]?\\s*(.+)|(.+?)\\s*(?:uygulamasini)?\\s*(?:ac|baslat)$")
        if (!t.contains("ac") && !t.contains("baslat")) return null
        val m = p.matcher(t)
        if (m.find()) {
            val name = (m.group(1) ?: m.group(2))?.trim().orEmpty()
                .replace(Regex("^(uygulamasini|uygulama)\\s+"), "")
            if (name.isNotBlank()) return name
        }
        return null
    }

    // ================= MEDYA =================

    private fun parseMedia(t: String): String? = when {
        t.contains("muzigi durdur") || t.contains("sarkiyi durdur") || t.contains("duraklat") -> "pause"
        t.contains("devam et") || t.contains("oynat") || t.contains("muzigi ac") -> "play"
        t.contains("sonraki sarki") || t.contains("siradaki") -> "next"
        t.contains("onceki sarki") || t.contains("geri sarki") -> "prev"
        else -> null
    }

    // ================= SİSTEM =================

    private fun parseSystem(t: String): Reply? = when {
        t.contains("fener") && (t.contains("ac") || t.contains("yak")) -> {
            val o = device.setFlashlight(true); Reply(if (o.ok) "Fener açık." else o.say)
        }
        t.contains("fener") && (t.contains("kapat") || t.contains("sondur")) -> {
            val o = device.setFlashlight(false); Reply(if (o.ok) "Fener kapalı." else o.say)
        }
        t.contains("wifi") && (t.contains("ac") || t.contains("kapat")) -> {
            val o = device.setWifi(t.contains("ac") && !t.contains("kapat")); Reply(o.say)
        }
        t.contains("ses") && (t.contains("yukari") || t.contains("artir")) -> {
            device.volume("up"); Reply("Sesi yükselttim.")
        }
        t.contains("ses") && (t.contains("asagi") || t.contains("azalt") || t.contains("kis")) -> {
            device.volume("down"); Reply("Sesi kıstım.")
        }
        t.contains("sesi kapat") || t.contains("sessize al") -> {
            device.volume("mute"); Reply("Sessize aldım.")
        }
        t.contains("sesi ac") || t.contains("sesten cikar") -> {
            device.volume("unmute"); Reply("Sesi açtım.")
        }
        t.contains("bluetooth") -> { device.openSystemPanel("bluetooth"); Reply("Bluetooth ayarlarını açtım.") }
        t.contains("ayarlar") && t.contains("ac") -> { device.openSystemPanel("settings"); Reply("Ayarları açtım.") }
        else -> null
    }

    // ================= TAKVİM =================

    private fun parseCalendar(t: String, original: String): Reply? {
        if (!t.contains("takvim") && !t.contains("etkinlik") && !t.contains("randevu")) return null
        if (!t.contains("ekle") && !t.contains("olustur") && !t.contains("kaydet")) return null
        val title = original
            .replace(Regex("(?i)(takvime|takvim|etkinlik|randevu|ekle|oluştur|olustur|kaydet)"), "")
            .trim()
            .ifBlank { "Etkinlik" }
        val cal = Calendar.getInstance()
        parseTime(t)?.let { (h, m) ->
            cal.set(Calendar.HOUR_OF_DAY, h)
            cal.set(Calendar.MINUTE, m)
        }
        val begin = cal.timeInMillis
        val end = begin + 60 * 60 * 1000
        val o = device.addEvent(title, begin, end)
        return Reply(if (o.ok) "“$title” etkinliğini hazırladım." else o.say)
    }

    // ================= YARDIMCILAR =================

    /** Türkçe karakterleri sadeleştirip küçük harfe çevirir (eşleştirme için). */
    fun norm(s: String): String {
        val tmp = Normalizer.normalize(s.lowercase().replace('ı', 'i'), Normalizer.Form.NFD)
        return tmp.replace(Regex("\\p{M}+"), "")
            .replace('ş', 's').replace('ğ', 'g').replace('ü', 'u')
            .replace('ö', 'o').replace('ç', 'c')
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanName(s: String): String = s
        .replace(Regex("^(whatsapp|vatsap|wp|sms)\\s*(dan|den|ta|da)?\\s*"), "")
        .replace(Regex("\\s*(kisisine|kisisine|mesaj|yaz|gonder|at|ara)$"), "")
        .trim()

    // ================= ONAY SONRASI =================

    suspend fun execute(pending: PendingAction): Reply = withContext(Dispatchers.IO) {
        when (pending) {
            is PendingAction.Call -> {
                val found = device.findContactNumber(pending.contact)
                    ?: return@withContext Reply("${pending.contact} kişisini rehberde bulamadım.")
                val o = device.callNumber(found.second)
                Reply(o.say)
            }
            is PendingAction.Sms -> {
                val found = device.findContactNumber(pending.contact)
                    ?: return@withContext Reply("${pending.contact} kişisini rehberde bulamadım.")
                val o = device.sendSms(found.second, pending.message)
                Reply(o.say)
            }
            is PendingAction.WhatsAppMessage -> {
                val bridge = AccessibilityBridge.instance
                    ?: return@withContext Reply("WhatsApp'a yazmak için erişilebilirlik iznini açman gerekiyor.")
                val ok = bridge.sendWhatsAppMessage(pending.contact, pending.message)
                Reply(
                    if (ok) "WhatsApp'ta ${pending.contact} sohbetine mesajı yazdım ve gönderdim."
                    else "WhatsApp'ta ${pending.contact} sohbetini bulamadım."
                )
            }
        }
    }
}
