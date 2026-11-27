package com.kimi.assistant.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.kimi.assistant.KimiApp
import com.kimi.assistant.service.AccessibilityBridge
import com.kimi.assistant.service.KimiForegroundService
import com.kimi.assistant.voice.AudioCapture
import com.kimi.assistant.voice.Speaker
import com.kimi.assistant.voice.SpeechToText
import com.kimi.assistant.voice.VoiceProfile
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var listenNow by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        listenNow = intent?.action == ACTION_LISTEN_NOW
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = Color(0xFF0E1116)) {
                    KimiScreen(
                        onRequestPermissions = { requestAll() },
                        openSettings = { openAppSettings() },
                        openAccessibility = { openAccessibilitySettings() },
                        listenNow = listenNow,
                        consumeListenNow = { listenNow = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_LISTEN_NOW) listenNow = true
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private fun requestAll() {
        permLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
            )
        )
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        )
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    companion object {
        const val ACTION_LISTEN_NOW = "com.kimi.LISTEN_NOW"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KimiScreen(
    onRequestPermissions: () -> Unit,
    openSettings: () -> Unit,
    openAccessibility: () -> Unit,
    listenNow: Boolean,
    consumeListenNow: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as KimiApp
    val prefs = app.prefs
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Hazır") }
    var log by remember { mutableStateOf(prefs.readLog()) }
    var hasProfile by remember { mutableStateOf(app.voiceStore.hasProfile) }
    var accessibilityOn by remember { mutableStateOf(AccessibilityBridge.isReady) }

    // Dinamik ayar alanları
    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    var provider by remember { mutableStateOf(prefs.provider) }
    var model by remember { mutableStateOf(prefs.model) }
    var wakeOn by remember { mutableStateOf(prefs.wakeWordEnabled) }
    var voiceLock by remember { mutableStateOf(prefs.voiceLockEnabled) }
    var threshold by remember { mutableFloatStateOf(prefs.voiceThreshold) }
    var wakeWord by remember { mutableStateOf(prefs.wakeWord) }
    var multiOn by remember { mutableStateOf(prefs.multiModelEnabled) }
    var multiModels by remember { mutableStateOf(prefs.multiModels) }
    var councilMode by remember { mutableStateOf(prefs.councilMode) }
    var judgeModel by remember { mutableStateOf(prefs.judgeModel) }
    var lastMulti by remember { mutableStateOf(prefs.lastMultiSummary) }

    // Karo/düğmeden gelen "şimdi dinle"
    LaunchedEffect(listenNow) {
        if (listenNow) {
            consumeListenNow()
            listenOnce(ctx) { status = it }
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(Color(0xFF141922)).fillMaxWidth().padding(16.dp)) {
                Text("Kimi", color = Color(0xFF6C8EFF), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(status, color = Color(0xFF9AA4B2), fontSize = 13.sp)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF141922)) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Mic, null) },
                    label = { Text("Asistan") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Text("🔐") },
                    label = { Text("Sesim") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Text("⚙️") },
                    label = { Text("Ayarlar") }
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Text("📋") },
                    label = { Text("Kayıt") }
                )
            }
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            when (tab) {
                0 -> AssistantTab(
                    onListen = { listenOnce(ctx) { status = it } },
                    onStartService = {
                        KimiForegroundService.start(ctx)
                        status = "Arka plan dinleme açıldı"
                    },
                    onStopService = {
                        KimiForegroundService.stop(ctx)
                        status = "Arka plan dinleme kapatıldı"
                    },
                    onRequestPermissions = onRequestPermissions,
                    accessibilityOn = accessibilityOn,
                    openAccessibility = openAccessibility,
                )
                1 -> VoiceTab(
                    hasProfile = hasProfile,
                    threshold = threshold,
                    voiceLock = voiceLock,
                    onThreshold = { threshold = it; prefs.voiceThreshold = it },
                    onVoiceLock = { voiceLock = it; prefs.voiceLockEnabled = it },
                    onEnroll = {
                        scope.launch {
                            status = "3 saniye konuş..."
                            val cap = AudioCapture(ctx)
                            val pcm = cap.record(3500, silenceStopMillis = 2000)
                            val emb = VoiceProfile.embed(pcm)
                            if (emb == null) {
                                status = "Ses alınamadı, tekrar dene"
                            } else {
                                app.voiceStore.addSample(emb)
                                hasProfile = true
                                status = "Ses kaydedildi"
                                Speaker(ctx).speak("Sesini kaydettim.")
                            }
                        }
                    },
                    onClear = {
                        app.voiceStore.clear()
                        hasProfile = false
                        status = "Ses profili silindi"
                    },
                    onTest = {
                        scope.launch {
                            status = "Test için konuş..."
                            val cap = AudioCapture(ctx)
                            val pcm = cap.record(3500, silenceStopMillis = 2000)
                            val emb = VoiceProfile.embed(pcm)
                            if (emb == null) {
                                status = "Ses alınamadı"
                            } else {
                                val sim = app.voiceStore.bestMatch(emb)
                                status = if (sim < 0f) "Önce ses kaydet" else
                                    "Benzerlik %${(sim * 100).toInt()} — ${VoiceProfile.verdict(sim, threshold)}"
                            }
                        }
                    },
                )
                2 -> SettingsTab(
                    apiKey = apiKey,
                    provider = provider,
                    model = model,
                    wakeOn = wakeOn,
                    wakeWord = wakeWord,
                    multiOn = multiOn,
                    multiModels = multiModels,
                    councilMode = councilMode,
                    judgeModel = judgeModel,
                    lastMulti = lastMulti,
                    onMultiOn = {
                        multiOn = it
                        prefs.multiModelEnabled = it
                    },
                    onMultiModels = {
                        multiModels = it
                        prefs.multiModels = it
                    },
                    onCouncil = {
                        councilMode = it
                        prefs.councilMode = it
                    },
                    onJudge = {
                        judgeModel = it
                        prefs.judgeModel = it
                    },
                    onApiKey = { apiKey = it; prefs.apiKey = it },
                    onProvider = { provider = it; prefs.provider = it },
                    onModel = { model = it; prefs.model = it },
                    onWakeOn = {
                        wakeOn = it
                        prefs.wakeWordEnabled = it
                        if (it) KimiForegroundService.start(ctx) else KimiForegroundService.stop(ctx)
                    },
                    onWakeWord = { wakeWord = it; prefs.wakeWord = it },
                    onRequestPermissions = onRequestPermissions,
                    openSettings = openSettings,
                    openAccessibility = openAccessibility,
                    accessibilityOn = accessibilityOn,
                )
                3 -> LogTab(log = log, onRefresh = { log = prefs.readLog() }, onClear = {
                    prefs.clearLog(); log = emptyList()
                })
            }
        }
    }
}

/**
 * Tek seferlik dinleme. Servis üzerinden çalışır: aynı anda iki tanıma motoru
 * açılmaz (çakışma "şimdi dinle" düğmesinin sessiz kalmasına yol açıyordu).
 */
private fun listenOnce(ctx: android.content.Context, onStatus: (String) -> Unit) {
    val app = ctx.applicationContext as KimiApp
    val before = app.prefs.readLog().firstOrNull()
    onStatus("Dinliyorum…")
    com.kimi.assistant.service.KimiForegroundService.listenOnce(ctx)
    // Kayıt defteri güncellenince sonucu göster
    Thread {
        repeat(40) {
            Thread.sleep(300)
            val now = app.prefs.readLog().firstOrNull()
            if (now != null && now != before) {
                val parts = now.split("|")
                onStatus(parts.getOrElse(1) { "Tamam" })
                return@Thread
            }
        }
    }.start()
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color(0xFF6C8EFF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(Color(0xFF171D26), RoundedCornerShape(14.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun AssistantTab(
    onListen: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRequestPermissions: () -> Unit,
    accessibilityOn: Boolean,
    openAccessibility: () -> Unit,
) {
    Button(
        onClick = onListen,
        modifier = Modifier.fillMaxWidth().height(110.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3550)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Mic, null, tint = Color(0xFF6C8EFF), modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            Text("Konuşmak için dokun", fontSize = 15.sp)
        }
    }

    Spacer(Modifier.height(12.dp))
    SectionTitle("İzinler")
    CardBox {
        Text(if (accessibilityOn) "✅ Erişilebilirlik açık" else "❌ Erişilebilirlik kapalı",
            color = if (accessibilityOn) Color(0xFF7BE495) else Color(0xFFFF8A80))
        Spacer(Modifier.height(8.dp))
        Button(onClick = openAccessibility) { Text("Erişilebilirlik ayarlarını aç") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRequestPermissions) { Text("Mikrofon ve diğer izinleri ver") }
    }

    SectionTitle("Arka plan dinleme")
    CardBox {
        Text("Uyandırma kelimesini duymak için servis açık kalmalı. Kilit ekranında da çalışır.",
            color = Color(0xFF9AA4B2), fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onStartService, modifier = Modifier.weight(1f)) { Text("Başlat") }
            OutlinedButton(onClick = onStopService, modifier = Modifier.weight(1f)) { Text("Durdur") }
        }
    }

    Spacer(Modifier.height(12.dp))
    SectionTitle("Örnek komutlar")
    CardBox {
        listOf(
            "Kimi, WhatsApp'ta Ali'ye yaz: yarın 10'da görüşelim",
            "Kimi, annemi ara",
            "Kimi, reels sayfasına geç",
            "Kimi, sonraki reel",
            "Kimi, alarm kur 7:30",
            "Kimi, Instagram'ı aç",
            "Kimi, feneri aç",
            "Kimi, 5 dakika zamanlayıcı kur",
            "Kimi, bugün hava nasıl",
        ).forEach {
            Text("• $it", color = Color(0xFFC7CFDA), fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp))
        }
    }
}

@Composable
private fun VoiceTab(
    hasProfile: Boolean,
    threshold: Float,
    voiceLock: Boolean,
    onThreshold: (Float) -> Unit,
    onVoiceLock: (Boolean) -> Unit,
    onEnroll: () -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit,
) {
    Text("Yalnızca senin sesin", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(
        "Sesi kaydedince Kimi başka birinin söylediği komutları yok sayar. " +
            "Yöntem hafif bir ses parmak izi; aynı evdeki farklı sesleri ayırır ama " +
            "profesyonel bir güvenlik sistemi değildir.",
        color = Color(0xFF9AA4B2), fontSize = 13.sp
    )

    Spacer(Modifier.height(16.dp))
    CardBox {
        Text(if (hasProfile) "✅ Kayıtlı ses profili var" else "❌ Kayıtlı ses yok",
            color = if (hasProfile) Color(0xFF7BE495) else Color(0xFFFF8A80))
        Spacer(Modifier.height(10.dp))
        Button(onClick = onEnroll, modifier = Modifier.fillMaxWidth()) {
            Text(if (hasProfile) "Yeni kayıt ekle" else "Sesimi kaydet (3 saniye)")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) { Text("Sesi test et") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClear) { Text("Profili sil", color = Color(0xFFFF8A80)) }
    }

    Spacer(Modifier.height(10.dp))
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = voiceLock, onCheckedChange = onVoiceLock)
            Spacer(Modifier.width(10.dp))
            Text("Sadece benim sesimle çalış", color = Color.White)
        }
        Spacer(Modifier.height(12.dp))
        Text("Eşik: %${(threshold * 100).toInt()} (yüksek = daha katı)", color = Color(0xFF9AA4B2), fontSize = 13.sp)
        Slider(value = threshold, onValueChange = onThreshold, valueRange = 0.5f..0.95f)
    }
}

@Composable
private fun SettingsTab(
    apiKey: String,
    provider: String,
    model: String,
    wakeOn: Boolean,
    wakeWord: String,
    multiOn: Boolean,
    multiModels: String,
    councilMode: Boolean,
    judgeModel: String,
    lastMulti: String,
    onMultiOn: (Boolean) -> Unit,
    onMultiModels: (String) -> Unit,
    onCouncil: (Boolean) -> Unit,
    onJudge: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onProvider: (String) -> Unit,
    onModel: (String) -> Unit,
    onWakeOn: (Boolean) -> Unit,
    onWakeWord: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    openSettings: () -> Unit,
    openAccessibility: () -> Unit,
    accessibilityOn: Boolean,
) {
    SectionTitle("Yapay zekâ (kendi API anahtarın)")
    CardBox {
        Text("Anahtar yalnızca bu telefonda saklanır ve sadece seçtiğin sağlayıcıya gönderilir.",
            color = Color(0xFF9AA4B2), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKey,
            label = { Text("API anahtarı") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = provider,
            onValueChange = onProvider,
            label = { Text("Sağlayıcı (openai / gemini / anthropic / groq / openrouter / deepseek)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model,
            onValueChange = onModel,
            label = { Text("Model (ör. gpt-4o-mini)") },
            modifier = Modifier.fillMaxWidth()
        )
    }

    SectionTitle("Çoklu yapay zekâ (aynı anda birden fazla model)")
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = multiOn, onCheckedChange = onMultiOn)
            Spacer(Modifier.width(10.dp))
            Text("Birden fazla modeli aynı anda çalıştır", color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Modelleri virgülle yaz. Her satır: sağlayıcı:model\n" +
                "Örnek: gemini:gemini-2.5-flash, openai:gpt-4o-mini, groq:llama-3.3-70b-versatile",
            color = Color(0xFF9AA4B2), fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = multiModels,
            onValueChange = onMultiModels,
            label = { Text("Modeller") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = councilMode, onCheckedChange = onCouncil)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Hakem modu", color = Color.White)
                Text(
                    "Açık: cevaplar bir hakem modelde birleştirilir (daha isabetli, daha yavaş). " +
                        "Kapalı: en hızlı cevap kullanılır.",
                    color = Color(0xFF9AA4B2), fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = judgeModel,
            onValueChange = onJudge,
            label = { Text("Hakem model (boşsa ilk model)") },
            modifier = Modifier.fillMaxWidth()
        )
        if (lastMulti.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Son yanıtlar: $lastMulti", color = Color(0xFF7BE495), fontSize = 11.sp)
        }
    }

    SectionTitle("Uyandırma kelimesi")
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = wakeOn, onCheckedChange = onWakeOn)
            Spacer(Modifier.width(10.dp))
            Text("Arka planda dinle", color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = wakeWord,
            onValueChange = onWakeWord,
            label = { Text("Uyandırma kelimesi") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Not: telefonun pil tasarrufu bu servisi kapatabilir. Ayarlardan Kimi'yi " +
                "pil optimizasyonundan muaf tutmak gerekir.",
            color = Color(0xFF9AA4B2), fontSize = 12.sp
        )
    }

    SectionTitle("İzinler")
    CardBox {
        Text(if (accessibilityOn) "✅ Erişilebilirlik açık" else "❌ Erişilebilirlik kapalı",
            color = if (accessibilityOn) Color(0xFF7BE495) else Color(0xFFFF8A80))
        Spacer(Modifier.height(8.dp))
        Button(onClick = openAccessibility, modifier = Modifier.fillMaxWidth()) {
            Text("Erişilebilirlik ayarları")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
            Text("İzinleri ver")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = openSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Uygulama bilgisi / pil")
        }
    }
}

@Composable
private fun LogTab(log: List<String>, onRefresh: () -> Unit, onClear: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onRefresh, modifier = Modifier.weight(1f)) { Text("Yenile") }
        OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text("Temizle") }
    }
    Spacer(Modifier.height(10.dp))
    if (log.isEmpty()) {
        Text("Henüz kayıt yok.", color = Color(0xFF9AA4B2))
    } else {
        log.forEach { line ->
            val parts = line.split("|")
            CardBox {
                Text(parts.getOrElse(1) { line }, color = Color.White, fontSize = 14.sp)
                Text(parts.getOrElse(2) { "" }, color = Color(0xFF9AA4B2), fontSize = 12.sp)
            }
        }
    }
}
