package com.kimi.assistant.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Bildirim panelinden tek dokunuşla komut dinleme.
 */
class KimiTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Kimi"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // Kilit ekranında da çalışsın diye aktivite değil servis üzerinden dinliyoruz
        val i = Intent(this, KimiForegroundService::class.java).apply {
            action = KimiForegroundService.ACTION_LISTEN_ONCE
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }
}
