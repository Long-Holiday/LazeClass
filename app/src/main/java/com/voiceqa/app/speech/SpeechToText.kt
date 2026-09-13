package com.voiceqa.app.speech

import kotlinx.coroutines.flow.Flow

sealed interface SpeechEvent {
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    data class Error(
        val code: Int,
        val message: String,
        val isFatal: Boolean = false
    ) : SpeechEvent
    data class Status(val message: String) : SpeechEvent
    data object Silence : SpeechEvent
}

interface SpeechToText {
    val events: Flow<SpeechEvent>

    suspend fun start(locale: String = "zh-CN")
    /** Finalizes the current utterance without ending the listening session. */
    suspend fun finalizeCurrentText(): String? = null
    /** Stops recognition after pending audio has been finalized. */
    suspend fun stop(): String?
    fun release()
}
