package com.kimi.assistant

import android.app.Application
import com.kimi.assistant.command.CommandRouter
import com.kimi.assistant.llm.LlmClient
import com.kimi.assistant.util.Prefs
import com.kimi.assistant.voice.VoiceProfileStore

/**
 * Uygulama genelinde tek örnek olarak tutulan bağımlılıklar.
 * Basit tutmak için servis konteyneri (DI framework yok).
 */
class KimiApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var voiceStore: VoiceProfileStore
        private set
    lateinit var llm: LlmClient
        private set
    lateinit var router: CommandRouter
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        voiceStore = VoiceProfileStore(this)
        llm = LlmClient(prefs)
        router = CommandRouter(this, prefs, llm)
    }

    companion object {
        lateinit var instance: KimiApp
            private set
    }
}
