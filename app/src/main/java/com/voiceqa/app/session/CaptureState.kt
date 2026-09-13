package com.voiceqa.app.session

sealed interface CaptureState {
    data object Idle : CaptureState
    data object Starting : CaptureState
    data class Listening(val partialText: String = "") : CaptureState
    data object Stopping : CaptureState
    data class Error(val message: String) : CaptureState
}

sealed interface AnalysisState {
    data object Idle : AnalysisState
    data class Waiting(val pendingChars: Int) : AnalysisState
    data class Sending(val batchId: String) : AnalysisState
    data class Failed(val batchId: String, val message: String) : AnalysisState
}
