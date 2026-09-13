package com.voiceqa.app.ui.home

import com.voiceqa.app.batching.QuestionAnswer
import com.voiceqa.app.session.AnalysisState
import com.voiceqa.app.session.CaptureState

data class HomeUiState(
    val captureState: CaptureState = CaptureState.Idle,
    val analysisState: AnalysisState = AnalysisState.Idle,
    val transcript: String = "",
    val answers: List<QuestionAnswer> = emptyList(),
    val isContinuousMode: Boolean = true,
    val lastMessage: String? = null
)
