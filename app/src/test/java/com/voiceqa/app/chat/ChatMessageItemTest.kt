package com.voiceqa.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageItemTest {

    @Test
    fun testChatSenderEnumValues() {
        val senders = ChatSender.values()
        assertEquals(3, senders.size)
        assertTrue(senders.contains(ChatSender.USER))
        assertTrue(senders.contains(ChatSender.ASSISTANT))
        assertTrue(senders.contains(ChatSender.SYSTEM))
    }

    @Test
    fun testChatMessageItemDefaultValues() {
        val now = System.currentTimeMillis()
        val item = ChatMessageItem(
            id = "msg-1",
            sender = ChatSender.USER,
            content = "你好，请问今天天气怎么样？"
        )

        assertEquals("msg-1", item.id)
        assertEquals(ChatSender.USER, item.sender)
        assertEquals("你好，请问今天天气怎么样？", item.content)
        assertTrue(item.timestamp >= now)
        assertFalse(item.isError)
        assertFalse(item.isThinking)
    }

    @Test
    fun testChatMessageItemCustomValues() {
        val item = ChatMessageItem(
            id = "msg-2",
            sender = ChatSender.ASSISTANT,
            content = "❌ 鉴权失败",
            timestamp = 1000L,
            isError = true,
            isThinking = false
        )

        assertEquals("msg-2", item.id)
        assertEquals(ChatSender.ASSISTANT, item.sender)
        assertEquals("❌ 鉴权失败", item.content)
        assertEquals(1000L, item.timestamp)
        assertTrue(item.isError)
        assertFalse(item.isThinking)
    }
}
