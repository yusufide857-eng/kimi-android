package com.kimi.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ekranı "gören" ve dokunan servis: Reels kaydırma, geri/home, WhatsApp'ta sohbet açma
 * ve mesaj yazma burada yapılır.
 *
 * Tüm WhatsApp gönderimleri CommandRouter tarafından ONAY alındıktan sonra çağrılır;
 * servis kendi başına mesaj göndermez.
 */
class KimiAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Şimdilik olay dinlemiyoruz; istekler doğrudan çağrılıyor.
    }

    override fun onInterrupt() {}

    // ==================== GEZİNME ====================

    fun swipeUp(duration: Long = 260) = swipe(0.5f, 0.78f, 0.5f, 0.22f, duration)
    fun swipeDown(duration: Long = 260) = swipe(0.5f, 0.22f, 0.5f, 0.78f, duration)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 260) {
        val dm = resources.displayMetrics
        val path = Path().apply {
            moveTo(x1 * dm.widthPixels, y1 * dm.heightPixels)
            lineTo(x2 * dm.widthPixels, y2 * dm.heightPixels)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        runCatching { dispatchGesture(gesture, null, null) }
    }

    fun back() {
        runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
    }

    fun home() {
        runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
    }

    fun recents() {
        runCatching { performGlobalAction(GLOBAL_ACTION_RECENTS) }
    }

    fun openNotifications() {
        runCatching { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }
    }

    /** Ekranda verilen metinlerden birini taşıyan ilk düğüme dokunur. */
    fun tapByText(texts: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            for (n in nodes) {
                if (n.isClickable) {
                    if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
                var p = n.parent
                var depth = 0
                while (p != null && depth < 4) {
                    if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    p = p.parent
                    depth++
                }
            }
        }
        return false
    }

    /** Rehberde/ekranda ada göre sohbet satırına dokunur. */
    private fun tapChatRow(contact: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val needles = listOf(contact, contact.lowercase(), contact.uppercase())
        for (needle in needles) {
            val nodes = root.findAccessibilityNodeInfosByText(needle) ?: continue
            for (n in nodes) {
                // Sohbet listesi satırları genelde tıklanabilir bir üst kapsayıcı içinde
                var p: AccessibilityNodeInfo? = n
                var depth = 0
                while (p != null && depth < 5) {
                    if (p.isClickable) {
                        if (p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    }
                    p = p.parent
                    depth++
                }
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        return false
    }

    private fun findEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard < 400) {
            guard++
            val n = queue.removeFirst()
            if (n.isEditable && n.isEnabled) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun typeInto(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * WhatsApp'ta kişiyi bulur, mesajı yazar ve gönderir.
     * NOT: Yalnızca kullanıcı onay verdikten sonra çağrılır.
     */
    suspend fun sendWhatsAppMessage(contact: String, message: String): Boolean =
        withContext(Dispatchers.Main) {
            runCatching {
                // 1) WhatsApp'ı aç
                val launch = packageManager.getLaunchIntentForPackage("com.whatsapp")
                    ?: return@withContext false
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
                delay(1800)

                // 2) Sohbet listesinde kişiyi bul
                var opened = tapChatRow(contact)
                if (!opened) {
                    // 3) Bulunamadıysa arama düğmesine dokun ve ara
                    tapByText(listOf("Search", "Ara", "Ara..."))
                    delay(700)
                    val search = findEditable(rootInActiveWindow)
                    if (search != null) {
                        typeInto(search, contact)
                        delay(1200)
                        opened = tapChatRow(contact)
                    }
                }
                if (!opened) return@withContext false

                delay(1200)

                // 4) Mesaj alanına yaz
                val input = findEditable(rootInActiveWindow) ?: return@withContext false
                if (!typeInto(input, message)) return@withContext false
                delay(600)

                // 5) Gönder
                val sent = tapByText(listOf("Send", "Gönder", "Gonder"))
                if (sent) return@withContext true

                // Gönder düğmesi metinsiz olabilir: içerik açıklamasına bak
                val root = rootInActiveWindow
                if (root != null) {
                    val nodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
                    if (nodes != null && nodes.isNotEmpty()) {
                        val btn = nodes[0]
                        if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withContext true
                        btn.parent?.let {
                            if (it.isClickable && it.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                                return@withContext true
                        }
                    }
                }
                false
            }.getOrDefault(false)
        }

    /** Instagram'da reels sekmesine geçer (ekstra yardımcı). */
    fun openReelsTab(): Boolean {
        if (!tapByText(listOf("Reels", "Reels sekmesi"))) return false
        scope.launch { delay(300) }
        return true
    }

    companion object {
        @Volatile
        var instance: KimiAccessibilityService? = null
            private set
    }
}

/** Servisin dışarıdan erişimi için kısa yol. */
object AccessibilityBridge {
    val instance: KimiAccessibilityService? get() = KimiAccessibilityService.instance
    val isReady: Boolean get() = KimiAccessibilityService.instance != null
}
