package com.voiceqa.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceqa.app.VoiceQaApplication
import com.voiceqa.app.settings.AppSettings
import com.voiceqa.app.settings.HistoryRetentionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-3.5-turbo",
    val apiKey: String = "",
    val hasSavedApiKey: Boolean = false,
    val language: String = "zh-CN",
    val silenceTimeoutMs: Long = 1_500L,
    val maximumWaitMs: Long = 10_000L,
    val maximumChars: Int = 120,
    val minimumChars: Int = 8,
    val contextChars: Int = 200,
    val ttsEnabled: Boolean = true,
    val continuousMode: Boolean = true,
    val historyRetention: HistoryRetentionPolicy = HistoryRetentionPolicy.PERMANENT,
    val useFakeLlm: Boolean = false,
    val isSavedMessageVisible: Boolean = false
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VoiceQaApplication
    private val settingsRepository = app.settingsRepository
    private val apiKeyStore = app.apiKeyStore

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val savedKey = apiKeyStore.load()
            settingsRepository.settingsFlow.collect { settings ->
                _uiState.value = _uiState.value.copy(
                    baseUrl = settings.baseUrl,
                    model = settings.model,
                    hasSavedApiKey = !savedKey.isNullOrBlank(),
                    language = settings.language,
                    silenceTimeoutMs = settings.silenceTimeoutMs,
                    maximumWaitMs = settings.maximumWaitMs,
                    maximumChars = settings.maximumChars,
                    minimumChars = settings.minimumChars,
                    contextChars = settings.contextChars,
                    ttsEnabled = settings.ttsEnabled,
                    continuousMode = settings.continuousMode,
                    historyRetention = settings.historyRetention,
                    useFakeLlm = settings.useFakeLlm
                )
            }
        }
    }

    fun onBaseUrlChanged(url: String) {
        _uiState.value = _uiState.value.copy(baseUrl = url)
    }

    fun onModelChanged(model: String) {
        _uiState.value = _uiState.value.copy(model = model)
    }

    fun onApiKeyChanged(key: String) {
        _uiState.value = _uiState.value.copy(apiKey = key)
    }

    fun onLanguageChanged(lang: String) {
        _uiState.value = _uiState.value.copy(language = lang)
    }

    fun onSilenceTimeoutChanged(timeout: Long) {
        _uiState.value = _uiState.value.copy(silenceTimeoutMs = timeout)
    }

    fun onMaxWaitChanged(waitMs: Long) {
        _uiState.value = _uiState.value.copy(maximumWaitMs = waitMs)
    }

    fun onMaxCharsChanged(chars: Int) {
        _uiState.value = _uiState.value.copy(maximumChars = chars)
    }

    fun onTtsEnabledChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(ttsEnabled = enabled)
    }

    fun onContinuousModeChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(continuousMode = enabled)
    }

    fun onHistoryRetentionChanged(policy: HistoryRetentionPolicy) {
        _uiState.value = _uiState.value.copy(historyRetention = policy)
    }

    fun onUseFakeLlmChanged(useFake: Boolean) {
        _uiState.value = _uiState.value.copy(useFakeLlm = useFake)
    }

    fun saveSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.apiKey.isNotBlank()) {
                apiKeyStore.save(state.apiKey)
            }

            settingsRepository.updateSettings(
                AppSettings(
                    baseUrl = state.baseUrl,
                    model = state.model,
                    language = state.language,
                    silenceTimeoutMs = state.silenceTimeoutMs,
                    maximumWaitMs = state.maximumWaitMs,
                    maximumChars = state.maximumChars,
                    minimumChars = state.minimumChars,
                    contextChars = state.contextChars,
                    ttsEnabled = state.ttsEnabled,
                    continuousMode = state.continuousMode,
                    historyRetention = state.historyRetention,
                    useFakeLlm = state.useFakeLlm
                )
            )

            val updatedKey = apiKeyStore.load()
            _uiState.value = _uiState.value.copy(
                hasSavedApiKey = !updatedKey.isNullOrBlank(),
                apiKey = "",
                isSavedMessageVisible = true
            )
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            apiKeyStore.clear()
            _uiState.value = _uiState.value.copy(
                hasSavedApiKey = false,
                apiKey = ""
            )
        }
    }
}
