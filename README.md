# Kimi — sesli Türkçe telefon asistanı

Telefonunda çalışan, **yalnızca senin sesinle** komut alan, onay isteyerek mesaj gönderen
Android asistanı.

## Özellikler

**Ses**
- Türkçe konuşmayı metne çevirme (cihazın kendi motoru, `tr-TR`)
- Uyandırma kelimesi (varsayılan: "Kimi") — arka planda, kilit ekranında da dinler
- **Yalnızca senin sesin:** kaydettiğin ses parmak iziyle eşleşmeyen komutlar yok sayılır
  (eşik ayarlanabilir, 5 örnek saklanır)
- Cevapları sesli okuma (TTS)

**Onaylı WhatsApp**
- "Kimi, WhatsApp'ta Ali'ye yaz: yarın 10'da görüşelim"
- Kimi sohbeti açar, mesajı yazar, **göndermeden önce onay ekranı çıkarır**
- Onaylamadan hiçbir mesaj gitmez

**Ekran kontrolü (erişilebilirlik)**
- "Reels sayfasına geç", "sonraki reel", "önceki reel", "yukarı kaydır"
- "geri", "ana ekran", "son uygulamalar", "bildirimleri aç"
- "beğen", "yorumları aç", "paylaş"

**Telefon işlevleri**
- Arama (onaylı), SMS (onaylı), kişi rehberinde arama
- Alarm, zamanlayıcı, takvim etkinliği
- Uygulama açma ("Instagram'ı aç", "Spotify'ı aç")
- Medya kontrolü (oynat/duraklat/sonraki/önceki)
- Fener, Wi-Fi paneli, ses seviyesi, sistem ayarları

**Yapay zekâ**
- Kendi API anahtarınla: OpenAI, Gemini, Anthropic, Groq, OpenRouter, DeepSeek
- Anahtar yalnızca telefonda saklanır, sadece seçtiğin sağlayıcıya gider
- Yerel çözülemeyen istekler modele gider ("bugün hava nasıl")

**Güvenlik ve kullanım**
- Geri alınamaz işlemler (mesaj, arama, SMS) her zaman onay ister
- Sessiz saatler
- Son 100 işlem kaydı
- Hızlı ayar karosu (bildirim panelinden tek dokunuş)
- Yeniden başlatmada dinlemeyi geri açar

## APK nasıl alınır

GitHub Actions her `main` push'unda APK üretir:

1. Depo → **Actions** → **Build APK** → en son çalışma
2. **Artifacts** → `kimi-debug-apk` indir
3. ZIP'i aç, `app-debug.apk` dosyasını telefona at
4. Telefonda: Ayarlar → Güvenlik → **Bilinmeyen kaynaklar** izni → APK'ya dokun → Kur

## Kurulum sonrası (sırayla)

1. **İzinleri ver** — Mikrofon, bildirim, kişiler, telefon, SMS, takvim
2. **Erişilebilirlik** — Ayarlar → Erişilebilirlik → Kimi Asistan → Aç
   (Reels kaydırma ve WhatsApp'a yazma bunu gerektirir)
3. **Sesimi kaydet** — "Sesim" sekmesi → 3 saniye konuş → kaydet (3 kez tekrarla daha iyi)
4. **API anahtarı** — Ayarlar sekmesi → sağlayıcı + anahtar
5. **Pil** — Uygulama bilgisi → Pil → **Kısıtlanmamış**
6. **Arka plan dinleme** — Ayarlar → uyandırma kelimesi aç → servis başlar

## Bilinen sınırlar (dürüst liste)

- **Ses tanıma güvenliği:** kullanılan yöntem hafif bir akustik parmak izi (mel bant
  istatistikleri). Aynı evdeki iki farklı sesi ayırır, TV/radyo sesini eler, ama
  profesyonel bir konuşmacı doğrulama modeli (ECAPA/d-vector) değildir. Bankacılık
  seviyesinde güvenlik beklemeyin.
- **WhatsApp otomasyonu** resmi API değil, erişilebilirlik üzerinden. WhatsApp arayüzü
  güncellenirse düğme metinleri değişebilir ve akış kırılabilir.
- **Uyandırma kelimesi** cihaz üzerinde çalışan bir motorla yapılıyor; sürekli dinleme
  pil tüketir. Pil optimizasyonundan muaf tutmak şart.
- **Play Store'a yayınlanamaz** bu haliyle: erişilebilirlik + otomatik mesaj kombinasyonu
  mağaza politikasına takılır. Kişisel kurulum (APK) için tasarlandı.
- Android 10+ üzerinde Wi-Fi doğrudan açılıp kapatılamaz, panel açılır.
- Kilit ekranından erişim, cihazın kilit ekranı güvenlik ayarlarına bağlıdır.

## Geliştirme

```bash
# yerel derleme (Android SDK gerekir)
./gradlew assembleDebug

# ses profili testleri
./gradlew testDebugUnitTest
```

Yapı: `app/src/main/java/com/kimi/assistant/`
- `voice/` — ses kaydı, tanıma, ses parmak izi, TTS
- `command/` — komut yönlendirici ve cihaz işlevleri
- `service/` — arka plan dinleme, erişilebilirlik, onay köprüsü
- `llm/` — yapay zekâ istemcisi
- `ui/` — Compose ekranları
