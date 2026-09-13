package com.voiceqa.app

import android.app.Application
import com.voiceqa.app.data.AppDatabase
import com.voiceqa.app.security.AndroidKeystoreApiKeyStore
import com.voiceqa.app.security.ApiKeyStore
import com.voiceqa.app.session.SessionCoordinator
import com.voiceqa.app.settings.SettingsRepository

class VoiceQaApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var apiKeyStore: ApiKeyStore
        private set

    lateinit var asrApiKeyStore: ApiKeyStore
        private set

    lateinit var sessionCoordinator: SessionCoordinator
        private set

    override fun onCreate() {
        super.onCreate()

        database = AppDatabase.getInstance(this)
        settingsRepository = SettingsRepository(this)
        apiKeyStore = AndroidKeystoreApiKeyStore(this)
        asrApiKeyStore = AndroidKeystoreApiKeyStore(
            context = this,
            keyAlias = "voiceqa_minimax_asr_api_key",
            fileName = "minimax_asr_api_key.enc"
        )
        sessionCoordinator = SessionCoordinator(
            context = this,
            database = database,
            settingsRepository = settingsRepository,
            apiKeyStore = apiKeyStore,
            asrApiKeyStore = asrApiKeyStore
        )
    }

    override fun onTerminate() {
        super.onTerminate()
        sessionCoordinator.release()
    }
}
