package com.kimi.assistant.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kimi.assistant.ui.MainActivity

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
        val i = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_LISTEN_NOW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivityAndCollapse(
            android.app.PendingIntent.getActivity(
                this, 0, i,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
    }
}
