package com.kimi.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label") ?: "Kimi hatırlatması"
        Toast.makeText(context, label, Toast.LENGTH_LONG).show()
    }
}
