package com.voiceqa.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceqa.app.VoiceQaApplication
import com.voiceqa.app.data.AnswerEntity
import com.voiceqa.app.data.SessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryUiState(
    val sessions: List<SessionEntity> = emptyList(),
    val answers: List<AnswerEntity> = emptyList(),
    val isLoading: Boolean = true
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VoiceQaApplication
    private val sessionDao = app.database.sessionDao()
    private val answerDao = app.database.answerDao()
    private val segmentDao = app.database.segmentDao()

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            sessionDao.observeAllSessions().collect { sessions ->
                answerDao.observeAllAnswers().collect { answers ->
                    _uiState.value = HistoryUiState(
                        sessions = sessions,
                        answers = answers,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            sessionDao.clearAll()
            segmentDao.clearAll()
            answerDao.clearAll()
        }
    }
}
