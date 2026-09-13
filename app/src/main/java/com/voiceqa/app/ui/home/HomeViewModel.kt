package com.voiceqa.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceqa.app.VoiceQaApplication
import com.voiceqa.app.batching.QuestionAnswer
import com.voiceqa.app.session.CaptureForegroundService
import com.voiceqa.app.session.CaptureState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

inline fun <T1, T2, T3, T4, T5, T6, R> combine6(
    flow1: Flow<T1>,
    flow2: Flow<T2>,
    flow3: Flow<T3>,
    flow4: Flow<T4>,
    flow5: Flow<T5>,
    flow6: Flow<T6>,
    crossinline transform: suspend (T1, T2, T3, T4, T5, T6) -> R
): Flow<R> = combine(flow1, flow2, flow3, flow4, flow5, flow6) { args: Array<Any?> ->
    @Suppress("UNCHECKED_CAST")
    transform(
        args[0] as T1,
        args[1] as T2,
        args[2] as T3,
        args[3] as T4,
        args[4] as T5,
        args[5] as T6
    )
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VoiceQaApplication
    private val coordinator = app.sessionCoordinator
    private val settingsRepository = app.settingsRepository

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine6(
                coordinator.captureState,
                coordinator.analysisState,
                coordinator.confirmedTranscript,
                coordinator.recentAnswers,
                coordinator.lastMessage,
                settingsRepository.settingsFlow
            ) { capture, analysis, transcript, answers, message, settings ->
                val fullTranscript = when (capture) {
                    is CaptureState.Listening -> {
                        if (capture.partialText.isNotEmpty()) {
                            if (transcript.isNotEmpty()) "$transcript\n[实时] ${capture.partialText}" else "[实时] ${capture.partialText}"
                        } else {
                            transcript
                        }
                    }
                    else -> transcript
                }

                HomeUiState(
                    captureState = capture,
                    analysisState = analysis,
                    transcript = fullTranscript,
                    answers = answers,
                    isContinuousMode = settings.continuousMode,
                    lastMessage = message
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    fun onStartListening() {
        viewModelScope.launch {
            val isContinuous = _uiState.value.isContinuousMode
            coordinator.startSession()
            if (isContinuous) {
                CaptureForegroundService.start(getApplication())
            }
        }
    }

    fun onStopListening() {
        viewModelScope.launch {
            val isContinuous = _uiState.value.isContinuousMode
            coordinator.stopSession()
            if (isContinuous) {
                CaptureForegroundService.stop(getApplication())
            }
        }
    }

    fun onFlushNow() {
        viewModelScope.launch {
            coordinator.flushNow()
        }
    }

    fun onSpeakAnswer(answer: QuestionAnswer) {
        coordinator.speakAnswer(answer.answer)
    }
}
