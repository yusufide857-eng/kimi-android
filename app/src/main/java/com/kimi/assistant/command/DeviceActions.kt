package com.kimi.assistant.command

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Telefon işlevleri: arama, SMS, alarm, takvim, uygulama açma, medya, sistem anahtarları.
 * Hepsi Android'in resmi API'leriyle çalışır; ayrıcalık (root) gerekmez.
 */
class DeviceActions(private val context: Context) {

    data class Outcome(val ok: Boolean, val say: String, val needsConfirm: Boolean = false)

    // ---------- Kişiler ----------

    /** Ada göre kişi ara, ilk eşleşmenin telefon numarasını döndür. */
    suspend fun findContactNumber(name: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(name)
        )
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0) ?: name
                    val num = c.getString(1) ?: return@use null
                    return@withContext n to num
                }
            }
        }
        null
    }

    // ---------- Arama ----------

    /** Kullanıcı onayladıktan sonra çağrılır. */
    fun callNumber(number: String): Outcome {
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) {
            val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }
                .fold(
                    { Outcome(true, "arıyorum") },
                    { Outcome(false, "arama başlatılamadı") }
                )
        } else {
            val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            Outcome(true, "arama ekranını açtım, tuşa basman gerekiyor")
        }
    }

    // ---------- SMS ----------

    fun sendSms(number: String, text: String): Outcome {
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                putExtra("sms_body", text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
            return Outcome(true, "mesaj ekranını açtım, göndermek için dokunman gerekiyor")
        }
        return runCatching {
            val sm = if (android.os.Build.VERSION.SDK_INT >= 31)
                context.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sm.divideMessage(text)
            sm.sendMultipartTextMessage(number, null, parts, null, null)
            Outcome(true, "mesajı gönderdim")
        }.getOrElse { Outcome(false, "SMS gönderilemedi: ${it.message}") }
    }

    // ---------- Alarm ----------

    fun setAlarm(hour: Int, minute: Int, label: String = "Kimi alarmı"): Outcome {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(i); Outcome(true, "alarmı kurdum") }
            .getOrElse { Outcome(false, "alarm kurulamadı") }
    }

    fun setTimer(seconds: Int, label: String = "Kimi zamanlayıcı"): Outcome {
        val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(i); Outcome(true, "zamanlayıcıyı başlattım") }
            .getOrElse { Outcome(false, "zamanlayıcı kurulamadı") }
    }

    // ---------- Takvim ----------

    fun addEvent(title: String, beginMillis: Long, endMillis: Long, location: String? = null): Outcome {
        val i = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(i); Outcome(true, "etkinliği hazırladım") }
            .getOrElse { Outcome(false, "takvime eklenemedi") }
    }

    // ---------- Uygulamalar ----------

    /** Ada göre kurulu uygulamayı bulur ve açar. */
    suspend fun openApp(query: String): Outcome = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val q = normalize(query)
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val best = apps
            .mapNotNull { info ->
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrNull()
                    ?: return@mapNotNull null
                val n = normalize(label)
                val score = when {
                    n == q -> 100
                    n.startsWith(q) -> 80
                    n.contains(q) -> 60
                    q.contains(n) -> 40
                    else -> 0
                }
                if (score > 0) Triple(info.packageName, label, score) else null
            }
            .sortedByDescending { it.third }
            .firstOrNull()

        if (best == null) return@withContext Outcome(false, "“$query” adında bir uygulama bulamadım")

        val launch = pm.getLaunchIntentForPackage(best.first)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return@withContext Outcome(false, "${best.second} açılmıyor")
        runCatching { context.startActivity(launch) }
            .fold(
                { Outcome(true, "${best.second} açıldı") },
                { Outcome(false, "${best.second} açılamadı") }
            )
    }

    fun openWhatsAppChat(): Outcome = runCatching {
        val i = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
            ?: return Outcome(false, "WhatsApp kurulu görünmüyor")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        Outcome(true, "WhatsApp açıldı")
    }.getOrElse { Outcome(false, "WhatsApp açılamadı") }

    fun openInstagram(): Outcome = runCatching {
        val i = context.packageManager.getLaunchIntentForPackage("com.instagram.android")
            ?: return Outcome(false, "Instagram kurulu görünmüyor")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        Outcome(true, "Instagram açıldı")
    }.getOrElse { Outcome(false, "Instagram açılamadı") }

    // ---------- Medya ----------

    fun mediaControl(action: String): Outcome {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val key = when (action) {
            "play", "pause", "toggle" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
            else -> return Outcome(false, "bilinmeyen medya komutu")
        }
        return runCatching {
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
            Outcome(true, "tamam")
        }.getOrElse { Outcome(false, "medya kontrolü çalışmadı") }
    }

    // ---------- Sistem anahtarları ----------

    fun setFlashlight(on: Boolean): Outcome = runCatching {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { camId ->
            cm.getCameraCharacteristics(camId)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return Outcome(false, "fener bulunamadı")
        cm.setTorchMode(id, on)
        Outcome(true, if (on) "feneri açtım" else "feneri kapattım")
    }.getOrElse { Outcome(false, "fener çalışmadı") }

    @Suppress("DEPRECATION")
    fun setWifi(on: Boolean): Outcome = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // Android 10+ uygulamalar doğrudan açıp kapatamaz; panel açılır
            context.startActivity(
                Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Outcome(true, "Wi-Fi panelini açtım")
        } else {
            @Suppress("DEPRECATION")
            wm.isWifiEnabled = on
            Outcome(true, if (on) "Wi-Fi açık" else "Wi-Fi kapalı")
        }
    }.getOrElse { Outcome(false, "Wi-Fi değiştirilemedi") }

    fun volume(change: String): Outcome = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        when (change) {
            "up" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            "max" -> am.setStreamVolume(stream, am.getStreamMaxVolume(stream), AudioManager.FLAG_SHOW_UI)
        }
        Outcome(true, "ses ayarlandı")
    }.getOrElse { Outcome(false, "ses ayarlanamadı") }

    fun openSystemPanel(panel: String): Outcome {
        val action = when (panel) {
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "settings" -> Settings.ACTION_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Outcome(true, "ayarları açtım")
        }.getOrElse { Outcome(false, "ayarlar açılamadı") }
    }

    fun openUrl(url: String): Outcome = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Outcome(true, "açtım")
    }.getOrElse { Outcome(false, "bağlantı açılamadı") }

    private fun normalize(s: String): String = s.lowercase()
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        .replace(" ", "")
}
