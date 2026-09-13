package com.voiceqa.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "voice_qa_settings")

open class SettingsRepository(private val context: Context? = null) {

    private object PreferencesKeys {
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL = stringPreferencesKey("model")
        val LANGUAGE = stringPreferencesKey("language")
        val SILENCE_TIMEOUT_MS = longPreferencesKey("silence_timeout_ms")
        val MAXIMUM_WAIT_MS = longPreferencesKey("maximum_wait_ms")
        val MAXIMUM_CHARS = intPreferencesKey("maximum_chars")
        val MINIMUM_CHARS = intPreferencesKey("minimum_chars")
        val CONTEXT_CHARS = intPreferencesKey("context_chars")
        val CONTINUOUS_MODE = booleanPreferencesKey("continuous_mode")
        val HISTORY_RETENTION = stringPreferencesKey("history_retention")
        val USE_FAKE_LLM = booleanPreferencesKey("use_fake_llm")
    }

    open val settingsFlow: Flow<AppSettings> by lazy {
        context!!.settingsDataStore.data.map { prefs ->
            AppSettings(
                baseUrl = prefs[PreferencesKeys.BASE_URL] ?: "https://api.minimax.cn/v1",
                model = prefs[PreferencesKeys.MODEL] ?: "MiniMax-M3",
                language = prefs[PreferencesKeys.LANGUAGE] ?: "zh-CN",
                silenceTimeoutMs = prefs[PreferencesKeys.SILENCE_TIMEOUT_MS] ?: 1_500L,
                maximumWaitMs = prefs[PreferencesKeys.MAXIMUM_WAIT_MS] ?: 10_000L,
                maximumChars = prefs[PreferencesKeys.MAXIMUM_CHARS] ?: 120,
                minimumChars = prefs[PreferencesKeys.MINIMUM_CHARS] ?: 8,
                contextChars = prefs[PreferencesKeys.CONTEXT_CHARS] ?: 200,
                continuousMode = prefs[PreferencesKeys.CONTINUOUS_MODE] ?: true,
                historyRetention = prefs[PreferencesKeys.HISTORY_RETENTION]?.let {
                    try {
                        HistoryRetentionPolicy.valueOf(it)
                    } catch (e: Exception) {
                        HistoryRetentionPolicy.PERMANENT
                    }
                } ?: HistoryRetentionPolicy.PERMANENT,
                useFakeLlm = prefs[PreferencesKeys.USE_FAKE_LLM] ?: false
            )
        }
    }

    open suspend fun updateSettings(settings: AppSettings) {
        context!!.settingsDataStore.edit { prefs ->
            prefs[PreferencesKeys.BASE_URL] = settings.baseUrl
            prefs[PreferencesKeys.MODEL] = settings.model
            prefs[PreferencesKeys.LANGUAGE] = settings.language
            prefs[PreferencesKeys.SILENCE_TIMEOUT_MS] = settings.silenceTimeoutMs
            prefs[PreferencesKeys.MAXIMUM_WAIT_MS] = settings.maximumWaitMs
            prefs[PreferencesKeys.MAXIMUM_CHARS] = settings.maximumChars
            prefs[PreferencesKeys.MINIMUM_CHARS] = settings.minimumChars
            prefs[PreferencesKeys.CONTEXT_CHARS] = settings.contextChars
            prefs[PreferencesKeys.CONTINUOUS_MODE] = settings.continuousMode
            prefs[PreferencesKeys.HISTORY_RETENTION] = settings.historyRetention.name
            prefs[PreferencesKeys.USE_FAKE_LLM] = settings.useFakeLlm
        }
    }
}
