package com.voiceqa.app.speech

import kotlinx.coroutines.flow.Flow

sealed interface SpeechEvent {
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    data class Error(val code: Int, val message: String) : SpeechEvent
    data object Silence : SpeechEvent
}

interface SpeechToText {
    val events: Flow<SpeechEvent>

    suspend fun start(locale: String = "zh-CN")
    suspend fun stop()
    fun release()
}
