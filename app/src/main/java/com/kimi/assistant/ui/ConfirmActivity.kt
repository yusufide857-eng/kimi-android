package com.kimi.assistant.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.kimi.assistant.KimiApp
import com.kimi.assistant.command.CommandRouter
import com.kimi.assistant.service.ConfirmBus
import com.kimi.assistant.service.KimiForegroundService
import com.kimi.assistant.voice.AudioCapture
import com.kimi.assistant.voice.Speaker
import com.kimi.assistant.voice.SpeechToText
import com.kimi.assistant.voice.VoiceProfile
import kotlinx.coroutines.launch

class ConfirmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val app = application as KimiApp
        val pending = ConfirmBus.pending
        val desc = ConfirmBus.description

        if (pending == null) {
            finish()
            return
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = Color(0xF20E1116)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Onay gerekiyor", color = Color(0xFF6C8EFF), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(16.dp))
                        Text(desc, color = Color.White, fontSize = 16.sp)
                        Spacer(Modifier.height(28.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            OutlinedButton(
                                onClick = {
                                    ConfirmBus.clear()
                                    finish()
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Vazgeç") }

                            Button(
                                onClick = {
                                    lifecycleScope.launch {
                                        val reply = app.router.execute(pending)
                                        Speaker(this@ConfirmActivity).speak(reply.say)
                                        ConfirmBus.clear()
                                        Toast.makeText(this@ConfirmActivity, reply.say, Toast.LENGTH_LONG).show()
                                        finish()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Onayla") }
                        }
                    }
                }
            }
        }
    }
}
