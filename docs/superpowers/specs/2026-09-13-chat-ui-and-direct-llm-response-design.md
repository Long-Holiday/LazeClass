# 聊天软件界面与 LLM 原始文本直出设计规范 (Design Spec)

## 1. 目标与背景

当前 VoiceQA 软件采用“状态栏 + 实时转写固定文本框 + 提取问答卡片列表 + 底部操作栏”的展示架构，且依赖 LLM 输出严格格式的 JSON Schema 进行“问题抽取与回答”。这种方式存在以下问题：
1. **界面体验生硬**：缺乏类似即时通讯（IM）应用的自然对话流感受。
2. **解析脆弱**：当 LLM 返回纯文本、部分回答或服务报错时，JSON 校验器抛出异常导致界面无法正常展示回答。
3. **调试不便**：当 LLM 报错（如 HTTP 401、429、500 或网络超时）时，错误信息仅停留在日志或顶栏单行提示中，用户难以直观了解具体报错原因。
4. **缺少存储清理**：缺少一键清除本地聊天记录以释放存储空间的功能。

本方案旨在：
- 将应用主界面全面改造为现代**聊天软件风格界面（Chat UI）**。
- 将语音识别到的文字作为**「我」（User）**发送的消息。
- 将 LLM 返回的消息直接作为**「对方」（Assistant）**的消息展示，**不进行复杂 JSON 格式解析**。
- 遇接口或网络异常时，将报错详情直接作为对方气泡呈现，方便直观调试。
- 增加**清空聊天记录与释放磁盘空间**功能。

---

## 2. 核心架构与组件划分

### 2.1 整体架构视图

```text
[ 麦克风录音 (MiniMax ASR) ]
         │ (语音转写完成)
         ▼
[ SessionCoordinator ] ── 追加 ──► [ ChatMessage: USER ] ──► Room DB (transcript_segments)
         │
         │ 触发批处理 / 立即发送
         ▼
[ GenericLlmProvider ] ── OpenAI 兼容请求 (纯文本对话 Prompt)
         │
         ├── 成功返回 content ────► [ ChatMessage: ASSISTANT ] ────────► Room DB (answers)
         └── 发生网络/HTTP 异常 ──► [ ChatMessage: ASSISTANT(error) ] ─► Room DB (answers)
                                                │
                                                ▼
                                    [ HomeScreen (Compose) ]
                                    (左右气泡聊天流 + 底部语音输入栏)
```

### 2.2 模块与文件变更清单

1. **UI 层**：
   - `app/src/main/java/com/voiceqa/app/ui/home/HomeScreen.kt`:
     - 改造为顶部标题栏（带“清空记录”与“设置”等按钮）、中间 LazyColumn 聊天流（左右气泡）、底部语音控制与即时语音输入栏。
   - `app/src/main/java/com/voiceqa/app/ui/home/HomeUiState.kt`:
     - 增加聊天消息列表 `messages: List<ChatMessageItem>`。
   - `app/src/main/java/com/voiceqa/app/ui/home/HomeViewModel.kt`:
     - 聚合来自 Coordinator 的消息流，提供清空记录 `clearChatHistory()` 等操作接口。
2. **领域与调度层**：
   - `app/src/main/java/com/voiceqa/app/session/SessionCoordinator.kt`:
     - 维护当前会话的聊天消息流 `chatMessages: StateFlow<List<ChatMessageItem>>`。
     - 语音转写最终结果产生时，生成 `ChatSender.USER` 消息。
     - LLM 调用完成后，生成 `ChatSender.ASSISTANT` 消息；若出错，生成带 `isError = true` 的错误消息。
     - 提供 `clearChatHistory()` 方法，清空 Room 三张表与内存状态。
   - `app/src/main/java/com/voiceqa/app/batching/BatchPolicy.kt` / `ChatMessageItem.kt`:
     - 定义聊天消息模型与发送方枚举。
3. **LLM 通信层**：
   - `app/src/main/java/com/voiceqa/app/llm/LlmPromptFactory.kt`:
     - 调整 System Prompt 为通用对话助理，去除 JSON Schema 约束。
     - User Prompt 直接输入用户转写文本。
   - `app/src/main/java/com/voiceqa/app/llm/GenericLlmProvider.kt`:
     - 移除 `LlmResultValidator` 解析，提取 `choices[0].message.content` 作为纯文本直接返回。
     - 网络或 HTTP 报错时，返回带详细状态与响应体的错误描述文本，不抛出阻断性异常。
   - `app/src/main/java/com/voiceqa/app/llm/FakeLlmProvider.kt`:
     - 同步调整为返回模拟对话纯文本。
4. **数据存储层**：
   - `app/src/main/java/com/voiceqa/app/data/Daos.kt`:
     - 已具备 `sessionDao.clearAll()`、`segmentDao.clearAll()`、`answerDao.clearAll()`。
     - 补充必要的数据加载与清除方法。

---

## 3. 详细设计规范

### 3.1 聊天消息数据模型 (`ChatMessageItem`)

```kotlin
enum class ChatSender {
    USER,       // 「我」发出的消息（语音转写文本）
    ASSISTANT,  // 「对方」发出的消息（LLM 响应文本）
    SYSTEM      // 系统提示（如清空提示等）
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

### 3.2 UI 界面设计 (`HomeScreen.kt`)

1. **顶部导航栏 (TopAppBar)**：
   - 标题：“VoiceQA 智能对话”
   - 右侧操作按钮：
     - **清空记录按钮 (Icons.Default.DeleteOutline)**：点击弹出 Material3 确认弹窗（“确定清空所有聊天记录与本地存储空间吗？”），确认后触发清空。
     - **历史记录 (Icons.Default.History)**
     - **设置 (Icons.Default.Settings)**
2. **中间聊天消息列表 (LazyColumn)**：
   - 右侧气泡（User / 我）：
     - 主题主色调（PrimaryContainer），圆角气泡，右下角尖角。
     - 文本内容：识别到的语音段落。
     - 右侧辅助显示发送时间。
   - 左侧气泡（Assistant / 对方）：
     - 浅灰或对比色调（SurfaceVariant），圆角气泡，左下角尖角。
     - 文本内容：LLM 返回的原始纯文本。
     - 若 `isError = true`：
       - 边框为 Error 颜色，背景为浅红/ErrorContainer。
       - 显示醒目的错误标签（例如 `❌ [请求异常 HTTP 401]`）与详细报错内容。
     - 右上角或底部附带小型“朗读 (TTS)”图标按钮，支持随时语音朗读该条内容。
   - 思考中气泡（Thinking）：
     - 当 LLM 请求正在发送中（`AnalysisState.Sending`）时，在左侧呈现带有跳动圆点或“对方正在思考回复...”的轻量气泡，收到回复后替换为正式气泡。
3. **底部语音交互与输入栏**：
   - **实时转写临时条**：正在语音录音且有未定稿文本（`partialText`）时，在输入栏上方显示淡蓝色提示胶囊：`🎙️ 正在倾听: $partialText`。
   - **控制区域**：
     - 大号录音/监听主按钮：
       - 未监听状态：显示“开始监听”麦克风按钮（Primary 色）。
       - 监听中状态：显示“停止监听”按钮（Error 色），并伴有呼吸/波纹动画。
     - “立即发送”次要按钮（OutlinedButton）：支持不等待静音超时，强制立即发送当前已收录的语音。

### 3.3 LLM 交互与极简文本直出

#### System Prompt 规范
```text
你是一个乐于助人的智能助理。
请针对用户的内容进行清晰、准确、自然的直接回复。
禁止输出与回答无关的额外标记，无需使用 JSON 格式包装，直接输出纯文本回答。
```

#### GenericLlmProvider 简化与错误外化
- 请求格式保持为标准 OpenAI `chat/completions`。
- 获取响应时：
  - 若 `response.isSuccessful`：
    - 读取 JSON 并解析 `choices.firstOrNull()?.message?.content`。
    - 若 content 不为空，直接返回纯文本。
  - 若 `!response.isSuccessful` 或捕获到 `IOException` / 鉴权异常：
    - **不直接崩溃退出**，而是构造统一的易排查错误信息字符串：
      - 401: `"❌ [API 密钥无效 (401)] 请进入设置检查 API Key 是否正确配置。"`
      - 429: `"❌ [请求频率超限 (429)] 请求过于频繁，请稍后再试。"`
      - 500/5xx: `"❌ [服务端异常 (${response.code})] $bodyString"`
      - IOException: `"❌ [网络连接失败] 请检查设备网络或 Base URL 连通性: ${e.message}"`
    - 将此错误文本作为 `AnalysisResult` 直接返回，并标记为 `isError = true`。

### 3.4 存储空间释放 (`clearChatHistory`)

1. **数据库数据删除**：
   - 在 `SessionCoordinator` 中提供 `clearChatHistory()`：
     ```kotlin
     sessionDao.clearAll()
     segmentDao.clearAll()
     answerDao.clearAll()
     ```
2. **SQLite 磁盘空间回收 (VACUUM)**：
   - 执行 `database.openHelper.writableDatabase.execSQL("VACUUM")` 触发 SQLite 物理碎片整理与空间压缩，彻底释放存储空间。
3. **内存与界面重置**：
   - 清空 `transcriptBuffer`。
   - 重置 `chatMessages` 为 `emptyList()`。
   - 将 `_lastMessage` 设为 `"已清空所有聊天记录并释放存储空间"`。

---

## 4. 测试与验证计划

1. **单元测试回归**：
   - 更新 `LlmPromptFactoryTest.kt`：校验生成纯文本 System Prompt 及 User Prompt。
   - 更新 `GenericLlmProviderTest` / 针对文本输出与错误提取的单元测试。
   - 运行 `./gradlew testDebugUnitTest` 保证全部通过。
2. **编译验证**：
   - 运行 `./gradlew assembleDebug` 验证无编译错误。
3. **功能验证**：
   - 检查聊天气泡左右分布是否正确。
   - 检查错误发生时（如不配 Key）是否直接呈现清晰的调试气泡。
   - 检查点击清空后界面与数据库是否同步清空。
