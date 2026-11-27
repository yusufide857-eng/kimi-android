package com.kimi.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kimi.assistant.KimiApp

/**
 * Telefon yeniden başladığında dinleme servisini geri açar (kullanıcı açtıysa).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = (context.applicationContext as? KimiApp)?.prefs ?: return
        if (prefs.wakeWordEnabled) {
            runCatching { KimiForegroundService.start(context) }
        }
    }
}
