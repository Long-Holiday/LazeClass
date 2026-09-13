# VoiceQA - Android 端侧实时语音转写与智能问答应用 (V1)

VoiceQA 是一个单 APK、客户端 BYOK 直连 LLM、基于 MiniMax ASR 的智能问答 Android 应用。
支持按停顿/时间/长度批量发送，并由 LLM 同时完成问题识别与精准回答。

## 架构与技术选型

- **语言**: Kotlin 1.9.24
- **UI 框架**: Jetpack Compose + Material 3
- **架构模式**: MVVM + Repository
- **异步控制**: Kotlin Coroutines、Flow、Channel（单消费者请求队列）
- **语音识别**: MiniMax `asr-1.0`（16 kHz 单声道采集、自适应静音分段、SSE 增量转写）
- **网络通信**: OkHttp (超时与自动重试策略) + Kotlinx Serialization JSON
- **本地存储**: Room Database（会话、文本段落、问答历史三张表）
- **应用配置**: AndroidX DataStore Preferences
- **安全与加密**: Android Keystore + AES-GCM-256（安全保存在私有目录，禁止自动云备份）
- **语音播报**: Android `TextToSpeech`（仅朗读识别到的答案内容）
- **前台服务**: `CaptureForegroundService`（麦克风前台服务类型，支持常驻通知控制）

---

## 工程目录结构

```text
app/
├─ MainActivity.kt                          // 入口 Activity，权限申请与 Compose 导航
├─ VoiceQaApplication.kt                    // 全局 Application，初始化单例依赖
│
├─ ui/
│  ├─ home/
│  │  ├─ HomeScreen.kt                      // 状态栏、实时转写、问答卡片、控制操作栏
│  │  ├─ HomeViewModel.kt                   // 首页状态与交互管理
│  │  └─ HomeUiState.kt                     // 录音状态与分析状态的双独立状态持有
│  ├─ settings/
│  │  ├─ SettingsScreen.kt                  // API 地址、模型、密钥、批量策略、TTS 与模式切换
│  │  └─ SettingsViewModel.kt               // 设置读写与 Keystore 加密操作
│  └─ history/
│     ├─ HistoryScreen.kt                   // 历史问答列表与清空
│     └─ HistoryViewModel.kt                // 历史记录数据观察
│
├─ session/
│  ├─ SessionCoordinator.kt                 // 核心调度器：串联 STT、Buffer、Scheduler、Queue 与 Room
│  ├─ CaptureState.kt                       // CaptureState 与 AnalysisState 独立状态机
│  └─ CaptureForegroundService.kt           // 持续监听前台服务与常驻控制通知
│
├─ speech/
│  ├─ SpeechToText.kt                       // 语音识别抽象接口与事件
│  ├─ MiniMaxSpeechRecognizer.kt            // 录音、自适应静音分段、重试与 ASR 上传队列
│  ├─ MiniMaxAsrProtocol.kt                 // 语言映射、JSON/SSE 解析与 WAV 封装
│  └─ TranscriptNormalizer.kt               // 问句标点空格规范化与 5 分钟 SHA-256 去重
│
├─ batching/
│  ├─ BatchPolicy.kt                        // 批量策略参数（静音时间、最大等待、最大字数等）
│  ├─ TranscriptBuffer.kt                   // 段落缓冲、原子快照、回滚与提交
│  ├─ BatchScheduler.kt                     // 静音定时器与首段最大等待计时器
│  └─ AnalysisQueue.kt                      // 单一 Channel 消费队列，保证并发唯一
│
├─ llm/
│  ├─ LlmProvider.kt                        // LLM 抽象接口与配置
│  ├─ GenericLlmProvider.kt                 // OpenAI 兼容客户端（带重试、限流、超时的网络调用）
│  ├─ FakeLlmProvider.kt                    // 离线模拟 Provider（用于无 Key 快速演示验收）
│  ├─ LlmPromptFactory.kt                   // 严格 System Prompt 与格式化 User Prompt 生成
│  ├─ LlmDtos.kt                            // 请求与响应的序列化 DTO
│  └─ LlmResultValidator.kt                 // Markdown 代码块提取与结构化 JSON 校验
│
├─ data/
│  ├─ AppDatabase.kt                        // Room 数据库定义与单例
│  ├─ Daos.kt                               // SessionDao, SegmentDao, AnswerDao
│  └─ Entities.kt                           // SessionEntity, TranscriptSegmentEntity, AnswerEntity
│
├─ security/
│  └─ ApiKeyStore.kt                        // Android Keystore + AES-GCM 密钥安全存储
│
├─ tts/
│  └─ TextToSpeechManager.kt                // 答案朗读管理
│
└─ settings/
   ├─ AppSettings.kt                        // 设置数据模型
   └─ SettingsRepository.kt                 // DataStore Preferences 仓库
```

---

## 核心设计与特性落地

1. **双独立状态机**
   - 录音状态 `CaptureState`: `Idle`, `Starting`, `Listening(partialText)`, `Stopping`, `Error`
   - 分析状态 `AnalysisState`: `Idle`, `Waiting(pendingChars)`, `Sending(batchId)`, `Failed`
   - 录音与分析解耦，网络请求快慢绝不阻塞语音流式识别。

2. **批量发送与原子快照**
   - 触发条件：
     - 最终文本累计 ≥ 120 字 → 立即发送
     - 收到最终文本后静音 ≥ 1.5 秒 → 发送
     - 首段文字等待 ≥ 10 秒 → 发送
     - 用户点击“立即分析” → 强制发送
     - 用户停止监听 → 强制发送
   - 原子快照：取出当前段落加入 `IN_FLIGHT`，新文字继续放入新的 pending；成功后标记 `COMMITTED`，失败则回退到 pending 首部重新发送。

3. **上下文与 5 分钟去重**
   - 提交后的段落自动保留作为指代上下文（默认最近 200 字，附带 `[id]` 编号）。
   - 服务端提取问题后，在客户端进行标点、大小写和空白符号规范化。
   - 对规范化字符串计算 SHA-256，5 分钟内相同问题不重复展示与朗读。

4. **安全 BYOK 密钥管理**
   - 用户在设置页分别输入 LLM API Key 与 MiniMax ASR API Key。
   - 由 `AndroidKeyStore` 生成硬件保护的 AES-256 密钥（AES/GCM/NoPadding）。
   - 密文保存于应用私有目录，配置 `data_extraction_rules.xml` 禁止云端自动备份。
   - 严禁在日志、数据库中打印 Authorization Header 或明文 Key。

5. **MiniMax ASR 近实时转写**
   - 使用 `AudioRecord` 采集 16 kHz、16-bit、单声道 PCM。
   - 采用自适应环境噪声阈值，约 0.6 秒停顿即切分，最长 10 秒，以 WAV 文件调用 `/v1/speech_to_text`。
   - 请求启用 SSE 增量响应，服务端识别出的文字会逐步显示；网络抖动和 429/5xx 会自动退避重试。
   - 点击“立即发送”会主动结束当前音频段，无需等待自动静音切分。

6. **离线测试模式 (Fake LLM)**
   - 在设置页中勾选“离线模拟提供者 (Fake LLM)”，可以在无网络或无 API Key 环境下测试语音输入、问答展示、TTS 朗读与会话保存。

---

## 构建与测试

### 1. 运行单元测试
```bash
./gradlew testDebugUnitTest
```

### 2. 打包 Debug APK
```bash
./gradlew assembleDebug
```
产物位置：`app/build/outputs/apk/debug/app-debug.apk`
