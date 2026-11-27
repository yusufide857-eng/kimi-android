package com.kimi.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.kimi.assistant.KimiApp
import com.kimi.assistant.R
import com.kimi.assistant.command.CommandRouter
import com.kimi.assistant.ui.MainActivity
import com.kimi.assistant.util.Prefs
import com.kimi.assistant.voice.AudioCapture
import com.kimi.assistant.voice.SpeechToText
import com.kimi.assistant.voice.VoiceProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Arka planda uyandırma kelimesini dinleyen ön plan servisi.
 * Kilit ekranı kapalıyken de çalışır (foreground service + wake lock).
 *
 * Akış:
 *  1. Kısa kayıtlar (2 sn) alınır.
 *  2. Kayıttan ses parmak izi çıkarılır ve kayıtlı profille karşılaştırılır.
 *  3. Sahibi değilse yok sayılır (yalnızca senin sesin).
 *  4. SpeechRecognizer ile metne çevrilir; uyandırma kelimesi geçmiyorsa yok sayılır.
 *  5. Uyandırma kelimesi varsa komut dinlenir, yönlendirilir, cevap sesli okunur.
 */
class KimiForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private lateinit var prefs: Prefs
    private lateinit var capture: AudioCapture
    private lateinit var stt: SpeechToText
    private lateinit var router: CommandRouter
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var paused = false

    override fun onCreate() {
        super.onCreate()
        val app = application as KimiApp
        prefs = app.prefs
        router = app.router
        capture = AudioCapture(this)
        stt = SpeechToText(this)
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Dinliyorum"))
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                paused = true
                updateNotification("Duraklatıldı")
                return START_STICKY
            }
            ACTION_RESUME -> {
                paused = false
                updateNotification("Dinliyorum")
                return START_STICKY
            }
        }
        if (loopJob?.isActive != true) startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        loopJob = scope.launch {
            while (isActive) {
                if (paused || inQuietHours()) {
                    delay(3000)
                    continue
                }
                runCatching { tick() }
                delay(400)
            }
        }
    }

    private fun inQuietHours(): Boolean {
        val s = prefs.quietStart
        val e = prefs.quietEnd
        if (s < 0 || e < 0) return false
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (s <= e) h in s until e else (h >= s || h < e)
    }

    /** Tek bir dinleme turu. */
    private suspend fun tick() {
        val wakeOn = prefs.wakeWordEnabled
        if (!wakeOn) {
            delay(2500)
            return
        }

        // 1) Kısa kayıt
        val pcm = capture.record(
            maxMillis = 2600,
            silenceStopMillis = 900,
        )
        if (pcm.isEmpty()) return

        // 2) Ses sahibi kontrolü
        val emb = VoiceProfile.embed(pcm)
        if (emb != null && prefs.voiceLockEnabled) {
            val sim = (application as KimiApp).voiceStore.bestMatch(emb)
            if (sim >= 0f && !router.isOwner(sim)) {
                // Başkası konuşuyor: yok say
                return
            }
        }

        // 3) Metne çevir
        val heard = stt.listenOnce()
        if (heard.isBlank()) return

        val wake = prefs.wakeWord.trim().lowercase()
        val normHeard = router.norm(heard)

        if (wake.isNotEmpty() && !normHeard.contains(router.norm(wake))) {
            return
        }

        // 4) Uyandırma kelimesinden sonraki komutu al
        var command = heard
        if (wake.isNotEmpty()) {
            val idx = normHeard.indexOf(router.norm(wake))
            if (idx >= 0) {
                // normalize edilmiş konumdan kabaca kırp
                command = heard.substring(minOf(heard.length, idx + wake.length)).trim()
            }
        }

        if (command.length < 3) {
            // Sadece "Kimi" dedi: tam komut için tekrar dinle
            updateNotification("Seni dinliyorum")
            val full = stt.listenOnce()
            if (full.isBlank()) return
            command = full
        }

        updateNotification("Komut: $command")

        // 5) Sahibin sesi mi, komutla birlikte tekrar doğrula
        val reply = router.handle(command)
        prefs.appendLog("${System.currentTimeMillis()}|$command|${reply.say}")

        if (reply.speak && prefs.speakReplies) {
            (application as KimiApp).let {
                com.kimi.assistant.voice.Speaker(this@KimiForegroundService).speak(reply.say)
            }
        }

        if (reply.pending != null) {
            ConfirmBus.request(reply.pending, reply.say)
            val i = Intent(this, com.kimi.assistant.ui.ConfirmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(i)
        }

        updateNotification("Dinliyorum")
        delay(1200)
    }

    /** Tek seferlik komut (karo/düğme ile). */
    fun listenOnceNow() {
        scope.launch {
            val heard = stt.listenOnce()
            if (heard.isBlank()) return@launch
            val reply = router.handle(heard)
            prefs.appendLog("${System.currentTimeMillis()}|$heard|${reply.say}")
            com.kimi.assistant.voice.Speaker(this@KimiForegroundService).speak(reply.say)
            if (reply.pending != null) {
                ConfirmBus.request(reply.pending, reply.say)
                startActivity(
                    Intent(this@KimiForegroundService, com.kimi.assistant.ui.ConfirmActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kimi::wake").apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Kimi dinleme",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Uyandırma kelimesi için arka plan dinlemesi"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, KimiForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("Kimi")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Durdur", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(text))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        runCatching { stt.destroy() }
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "kimi_listen"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.kimi.STOP"
        const val ACTION_PAUSE = "com.kimi.PAUSE"
        const val ACTION_RESUME = "com.kimi.RESUME"

        fun start(ctx: Context) {
            val i = Intent(ctx, KimiForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, KimiForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
