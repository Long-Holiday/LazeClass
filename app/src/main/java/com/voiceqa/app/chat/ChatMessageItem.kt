package com.voiceqa.app.chat

enum class ChatSender {
    USER,
    ASSISTANT,
    SYSTEM
}

data class ChatMessageItem(
    val id: String,
    val sender: ChatSender,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    val isThinking: Boolean = false
)
