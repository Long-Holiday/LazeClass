# 聊天软件界面与 LLM 原始文本直出实施计划 (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 VoiceQA 主界面改造为类似主流聊天软件的双向气泡对话流，识别的语音作为「我」发送的消息，LLM 返回的内容作为「对方」的消息直接展示（无复杂 JSON 解析），报错信息直接在气泡中外化展示以便调试，并提供一键清空聊天记录以释放存储空间的功能。

**Architecture:** 
采用 MVVM + Repository 架构。在领域层引入统一的 `ChatMessageItem` 模型，由 `SessionCoordinator` 驱动语音识别结果（User）与 LLM 响应（Assistant）的实时聊天流。LLM 通信层直接透传纯文本并在异常时构造诊断文本。UI 层使用 Jetpack Compose 的 `LazyColumn` 渲染左右气泡对话列表，底部集成录音状态与控制栏，顶部提供存储释放与清空确认。

**Tech Stack:** Kotlin 1.9.24, Android Jetpack Compose, Material 3, Room Database, Coroutines & StateFlow, OkHttp, JUnit4.

## Global Constraints

- 界面必须呈现为清晰的聊天应用气泡样式：右侧为「我」（用户语音），左侧为「对方」（LLM 响应）。
- 严禁对 LLM 响应做复杂的 JSON 强校验，直接获取 `choices[0].message.content` 文本。
- LLM 发生 HTTP 错误（如 401、429、500）或网络断开时，必须直接将错误详情构造为对方的红色错误气泡，绝不静默吞掉或中断崩溃。
- 提供一键清空聊天记录功能，必须同时清空 Room 数据表并执行 SQLite VACUUM 释放物理磁盘存储空间。
- 每次在请求执行 bash 命令前，先用简体中文说明该命令的作用。
- 保持现有的 MiniMax ASR 录音识别与连续监听前台服务机制完好可用。

---

### Task 1: 消息领域模型与 LLM 直出解析改造

**Files:**
- Create: `app/src/main/java/com/voiceqa/app/chat/ChatMessageItem.kt`
- Modify: `app/src/main/java/com/voiceqa/app/llm/LlmPromptFactory.kt`
- Modify: `app/src/main/java/com/voiceqa/app/llm/GenericLlmProvider.kt`
- Modify: `app/src/main/java/com/voiceqa/app/llm/FakeLlmProvider.kt`
- Modify: `app/src/test/java/com/voiceqa/app/llm/LlmPromptFactoryTest.kt`
- Modify: `app/src/test/java/com/voiceqa/app/llm/LlmResultValidatorTest.kt`

**Interfaces:**
- Produces:
  - `data class ChatMessageItem(val id: String, val sender: ChatSender, val content: String, val timestamp: Long, val isError: Boolean, val isThinking: Boolean)`
  - `enum class ChatSender { USER, ASSISTANT, SYSTEM }`
  - `LlmProvider.analyze(batch: AnalysisBatch, settings: LlmSettings, apiKey: String?): AnalysisResult`（返回包含直接文本与是否错误的统一结果）

- [ ] **Step 1: 创建 ChatMessageItem 与 ChatSender 领域模型**

在 `app/src/main/java/com/voiceqa/app/chat/ChatMessageItem.kt` 中创建：
```kotlin
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
```

- [ ] **Step 2: 调整 LlmPromptFactory 为纯文本直接对话**

修改 `app/src/main/java/com/voiceqa/app/llm/LlmPromptFactory.kt`：
```kotlin
package com.voiceqa.app.llm

import com.voiceqa.app.batching.AnalysisBatch

object LlmPromptFactory {

    const val SYSTEM_PROMPT = """你是一个乐于助人的智能助理。
请直接针对用户发送的语音转写内容进行清晰、准确、自然的直接回复。
禁止输出与回答无关的额外标记，无需使用 JSON 格式包装，直接输出纯文本回答。"""

    fun createUserPrompt(batch: AnalysisBatch): String {
        return if (batch.context.isBlank()) {
            batch.newText.trim()
        } else {
            """
            [前文背景]
            ${batch.context.trim()}

            [用户当前发言]
            ${batch.newText.trim()}
            """.trimIndent()
        }
    }
}
```

- [ ] **Step 3: 改造 GenericLlmProvider 直接返回 content 与外化错误信息**

修改 `app/src/main/java/com/voiceqa/app/llm/GenericLlmProvider.kt`：
不再通过 `LlmResultValidator` 进行强 JSON 校验。成功时提取 `content` 并作为 `QuestionAnswer(question = batch.newText, answer = content)` 返回；当遇到 HTTP 错误或网络异常时，将具体的错误描述构造成答案文本返回，并将 `hasQuestion = true`，以便上层直接在气泡展示：
```kotlin
// 解析响应成功逻辑
val rawAssistantContent = chatResponse.choices.firstOrNull()?.message?.content
    ?: "（模型未返回任何内容）"

return AnalysisResult(
    hasQuestion = true,
    questions = listOf(
        QuestionAnswer(
            question = batch.newText,
            answer = rawAssistantContent,
            sourceSegmentIds = batch.segmentIds
        )
    ),
    message = "已收到回复"
)
```
并在 `executeRequest` 中将异常转化为错误字符串：
```kotlin
if (code == 401 || code == 403) {
    return AnalysisResult(
        hasQuestion = true,
        questions = listOf(QuestionAnswer(batch.newText, "❌ [鉴权失败 (HTTP $code)] API Key 无效或未授权，请前往“设置”检查密钥配置。\n响应: $bodyString")),
        message = "鉴权失败"
    )
}
if (code == 429) {
    return AnalysisResult(
        hasQuestion = true,
        questions = listOf(QuestionAnswer(batch.newText, "❌ [请求限流 (HTTP 429)] 触发频率限制，请稍后再试。\n响应: $bodyString")),
        message = "触发限流"
    )
}
if (!resp.isSuccessful) {
    return AnalysisResult(
        hasQuestion = true,
        questions = listOf(QuestionAnswer(batch.newText, "❌ [服务异常 (HTTP $code)]\n响应: $bodyString")),
        message = "服务异常"
    )
}
```
网络 `IOException` 同理直接捕获并封装为包含详细错误信息的 `AnalysisResult`。

- [ ] **Step 4: 调整 FakeLlmProvider**

在 `app/src/main/java/com/voiceqa/app/llm/FakeLlmProvider.kt` 中：
```kotlin
class FakeLlmProvider : LlmProvider {
    override suspend fun analyze(
        batch: AnalysisBatch,
        settings: LlmSettings,
        apiKey: String?
    ): AnalysisResult {
        delay(500)
        val text = batch.newText.trim()
        val answer = "这是离线模拟回复：已收到您发送的“$text”。"
        return AnalysisResult(
            hasQuestion = true,
            questions = listOf(
                QuestionAnswer(question = text, answer = answer, sourceSegmentIds = batch.segmentIds)
            ),
            message = "已模拟回复"
        )
    }
}
```

- [ ] **Step 5: 更新单元测试并验证通过**

修改 `app/src/test/java/com/voiceqa/app/llm/LlmPromptFactoryTest.kt`，运行单元测试：
`./gradlew testDebugUnitTest --tests "com.voiceqa.app.llm.*"`
预期：PASS。

- [ ] **Step 6: 提交 Task 1 成果**

`git add . && git commit -m "feat: simplify LLM provider to direct text response and add ChatMessageItem"`

---

### Task 2: 会话调度器支持聊天流聚合与清空存储功能

**Files:**
- Modify: `app/src/main/java/com/voiceqa/app/session/SessionCoordinator.kt`

**Interfaces:**
- Consumes:
  - `ChatMessageItem`, `ChatSender` from Task 1
- Produces:
  - `SessionCoordinator.chatMessages: StateFlow<List<ChatMessageItem>>`
  - `suspend fun SessionCoordinator.clearChatHistory()`

- [ ] **Step 1: 在 SessionCoordinator 中维护 chatMessages 状态流**

在 `SessionCoordinator` 中增加：
```kotlin
private val _chatMessages = MutableStateFlow<List<ChatMessageItem>>(emptyList())
val chatMessages: StateFlow<List<ChatMessageItem>> = _chatMessages.asStateFlow()
```

- [ ] **Step 2: 语音分段完成时追加 USER 消息**

在 `onFinalTranscript(text: String)` 中：
除了写入 Room 与 `transcriptBuffer` 之外，生成一条 `ChatMessageItem`：
```kotlin
val userMsg = ChatMessageItem(
    id = "user_${System.currentTimeMillis()}_${generatedId}",
    sender = ChatSender.USER,
    content = trimmed,
    timestamp = now
)
_chatMessages.value = _chatMessages.value + userMsg
```

- [ ] **Step 3: 优化 analyzeOneBatch 逻辑并追加 ASSISTANT / 错误消息**

在 `analyzeOneBatch` 中：
在发送前可展示/记录 `AnalysisState.Sending` 状态；
收到结果后：
```kotlin
for (q in result.questions) {
    val isErr = q.answer.startsWith("❌")
    val assistantMsg = ChatMessageItem(
        id = "asst_${System.currentTimeMillis()}_${UUID.randomUUID()}",
        sender = ChatSender.ASSISTANT,
        content = q.answer,
        timestamp = System.currentTimeMillis(),
        isError = isErr
    )
    _chatMessages.value = _chatMessages.value + assistantMsg

    // 持久化到 answerDao
    val answerEntity = AnswerEntity(
        sessionId = batch.sessionId,
        batchId = batch.id,
        question = q.question,
        normalizedQuestionHash = TranscriptNormalizer.sha256(q.question),
        answer = q.answer,
        createdAt = System.currentTimeMillis()
    )
    answerDao.insertAnswer(answerEntity)

}
```

- [ ] **Step 4: 实现 clearChatHistory() 释放存储空间**

在 `SessionCoordinator` 中实现：
```kotlin
suspend fun clearChatHistory() = withContext(Dispatchers.IO) {
    // 1. 清空所有 Room 数据表
    sessionDao.clearAll()
    segmentDao.clearAll()
    answerDao.clearAll()

    // 2. 触发 SQLite 物理压缩以释放存储空间
    runCatching {
        database.openHelper.writableDatabase.execSQL("VACUUM")
    }

    // 3. 重置内存队列与聊天记录
    transcriptBuffer.clear()
    TranscriptNormalizer.clearCache()
    _chatMessages.value = emptyList()
    _recentAnswers.value = emptyList()
    _confirmedTranscript.value = ""
    _lastMessage.value = "已清空所有聊天记录并释放存储空间"
}
```

- [ ] **Step 5: 运行单元测试**

运行单元测试确保分段缓冲与会话调度无报错：
`./gradlew testDebugUnitTest`
预期：PASS。

- [ ] **Step 6: 提交 Task 2 成果**

`git add . && git commit -m "feat: implement chat message stream and clearChatHistory in SessionCoordinator"`

---

### Task 3: 聊天界面与交互实现 (HomeScreen, HomeUiState, HomeViewModel)

**Files:**
- Modify: `app/src/main/java/com/voiceqa/app/ui/home/HomeUiState.kt`
- Modify: `app/src/main/java/com/voiceqa/app/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/voiceqa/app/ui/home/HomeScreen.kt`

**Interfaces:**
- Consumes:
  - `SessionCoordinator.chatMessages`, `SessionCoordinator.clearChatHistory()`
- Produces:
  - `HomeUiState.messages: List<ChatMessageItem>`
  - `HomeViewModel.onClearChatHistory()`
  - 聊天气泡界面与清空确认对话框

- [ ] **Step 1: 在 HomeUiState 中增加 messages 字段**

修改 `app/src/main/java/com/voiceqa/app/ui/home/HomeUiState.kt`：
```kotlin
data class HomeUiState(
    val captureState: CaptureState = CaptureState.Idle,
    val analysisState: AnalysisState = AnalysisState.Idle,
    val transcript: String = "",
    val partialText: String = "",
    val messages: List<ChatMessageItem> = emptyList(),
    val isContinuousMode: Boolean = true,
    val lastMessage: String? = null
)
```

- [ ] **Step 2: 在 HomeViewModel 中接入 chatMessages 与 clearChatHistory**

修改 `app/src/main/java/com/voiceqa/app/ui/home/HomeViewModel.kt`：
- 在状态流中结合 `coordinator.chatMessages`；
- 暴露 `fun onClearChatHistory() { viewModelScope.launch { coordinator.clearChatHistory() } }`；

- [ ] **Step 3: 重构 HomeScreen 界面**

修改 `app/src/main/java/com/voiceqa/app/ui/home/HomeScreen.kt`：
1. **TopAppBar**：
   - 增加“清空记录”按钮（带垃圾桶图标）；
   - 点击弹出 Material3 `AlertDialog`：标题为“清空聊天记录”，内容为“确定清空全部历史消息并释放本地存储空间吗？此操作不可逆。”；点击确定后调用 `viewModel.onClearChatHistory()`。
2. **消息列表区域 (LazyColumn)**：
   - 当 `messages.isEmpty()` 时，显示空状态占位引导图文：“点击下方开始监听，通过语音与 AI 展开对话”。
   - 遍历 `messages` 渲染：
     - `ChatSender.USER`：右对齐绿色/主色气泡，显示用户语音转写内容与时间。
     - `ChatSender.ASSISTANT`：左对齐浅灰气泡；若 `isError = true`，显示浅红边框和错误标识。
   - 当 `analysisState is AnalysisState.Sending` 时，在最下方渲染“对方正在思考中...”小气泡。
   - 配合 `rememberLazyListState()`，每次有新消息时平滑滚动到底部。
3. **底部操作与实时语音栏**：
   - 实时识别条：若 `captureState is CaptureState.Listening && partialText.isNotBlank()`，在输入栏上方悬浮药丸胶囊：“🎙️ 正在收音: $partialText”。
   - 控制条：
     - 大号开始/停止监听按钮；
     - 立即发送按钮（`onFlushNow`）。

- [ ] **Step 4: 编译检查与界面预览代码验证**

运行 `./gradlew compileDebugKotlin` 验证语法与依赖无任何错误。

- [ ] **Step 5: 提交 Task 3 成果**

`git add . && git commit -m "feat: revamp HomeScreen into modern Chat UI with bubble messages and clear history"`

---

### Task 4: 全局测试与 APK 构建验证

**Files:**
- Test files

- [ ] **Step 1: 运行所有单元测试**

`./gradlew testDebugUnitTest`
预期：所有单元测试无报错通过。

- [ ] **Step 2: 构建 Debug APK**

`./gradlew assembleDebug`
预期：BUILD SUCCESSFUL，产物生成在 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 3: 最终 Git 提交与完成标记**

`git add . && git commit -m "chore: verify tests and build debug apk successfully"`
