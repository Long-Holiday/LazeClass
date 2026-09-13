package com.voiceqa.app.ui.home

import com.voiceqa.app.chat.ChatMessageItem
import com.voiceqa.app.chat.ChatSender
import com.voiceqa.app.session.AnalysisState
import com.voiceqa.app.session.CaptureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateTest {

    @Test
    fun testHomeUiStateDefaults() {
        val state = HomeUiState()
        assertEquals(CaptureState.Idle, state.captureState)
        assertEquals(AnalysisState.Idle, state.analysisState)
        assertEquals("", state.transcript)
        assertEquals("", state.partialText)
        assertTrue(state.messages.isEmpty())
        assertTrue(state.isContinuousMode)
        assertNull(state.lastMessage)
    }

    @Test
    fun testHomeUiStateWithMessagesAndPartialText() {
        val msg1 = ChatMessageItem(
            id = "msg-1",
            sender = ChatSender.USER,
            content = "什么是量子力学？"
        )
        val msg2 = ChatMessageItem(
            id = "msg-2",
            sender = ChatSender.ASSISTANT,
            content = "量子力学是研究微观粒子运动规律的物理学分支。"
        )
        val state = HomeUiState(
            captureState = CaptureState.Listening("正在"),
            analysisState = AnalysisState.Waiting(10),
            transcript = "历史转写",
            partialText = "正在",
            messages = listOf(msg1, msg2),
            isContinuousMode = false,
            lastMessage = "测试消息"
        )

        assertEquals("正在", state.partialText)
        assertEquals(2, state.messages.size)
        assertEquals("msg-1", state.messages[0].id)
        assertEquals("msg-2", state.messages[1].id)
        assertEquals("测试消息", state.lastMessage)
    }
}
