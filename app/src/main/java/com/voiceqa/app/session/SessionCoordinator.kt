package com.voiceqa.app.session

import android.content.Context
import android.util.Log
import com.voiceqa.app.batching.AnalysisBatch
import com.voiceqa.app.batching.AnalysisQueue
import com.voiceqa.app.batching.BatchPolicy
import com.voiceqa.app.batching.BatchScheduler
import com.voiceqa.app.batching.FlushReason
import com.voiceqa.app.batching.QuestionAnswer
import com.voiceqa.app.batching.TranscriptBuffer
import com.voiceqa.app.batching.TranscriptSegment
import com.voiceqa.app.chat.ChatMessageItem
import com.voiceqa.app.chat.ChatSender
import com.voiceqa.app.data.AnswerEntity
import com.voiceqa.app.data.AppDatabase
import com.voiceqa.app.data.SegmentStatus
import com.voiceqa.app.data.SessionEntity
import com.voiceqa.app.data.TranscriptSegmentEntity
import com.voiceqa.app.llm.FakeLlmProvider
import com.voiceqa.app.llm.GenericLlmProvider
import com.voiceqa.app.llm.LlmProvider
import com.voiceqa.app.llm.LlmSettings
import com.voiceqa.app.security.ApiKeyStore
import com.voiceqa.app.settings.AppSettings
import com.voiceqa.app.settings.SettingsRepository
import com.voiceqa.app.speech.MiniMaxSpeechRecognizer
import com.voiceqa.app.speech.SpeechEvent
import com.voiceqa.app.speech.SpeechToText
import com.voiceqa.app.speech.TranscriptNormalizer
import com.voiceqa.app.tts.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SessionCoordinator(
    private val context: Context? = null,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    private val asrApiKeyStore: ApiKeyStore = apiKeyStore,
    private val speechToText: SpeechToText? = null,
    private val llmProvider: LlmProvider? = null,
    private val ttsManager: TextToSpeechManager? = null,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        private const val TAG = "SessionCoordinator"
    }

    private val sessionDao = database.sessionDao()
    private val segmentDao = database.segmentDao()
    private val answerDao = database.answerDao()

    private val actualSpeechToText = speechToText ?: MiniMaxSpeechRecognizer(
        context = requireNotNull(context) { "Context must not be null when speechToText is not provided" },
        scope = externalScope,
        apiKeyStore = asrApiKeyStore
    )
    private val actualLlmProvider = llmProvider ?: GenericLlmProvider()
    private val fakeLlmProvider = FakeLlmProvider()
    private val actualTtsManager = ttsManager ?: TextToSpeechManager(
        requireNotNull(context) { "Context must not be null when ttsManager is not provided" }
    )

    private val transcriptBuffer = TranscriptBuffer()
    private val batchScheduler = BatchScheduler(externalScope) { reason ->
        flush(reason, force = false)
    }

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private val _analysisState = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val analysisState: StateFlow<AnalysisState> = _analysisState.asStateFlow()

    private val _recentAnswers = MutableStateFlow<List<QuestionAnswer>>(emptyList())
    val recentAnswers: StateFlow<List<QuestionAnswer>> = _recentAnswers.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessageItem>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessageItem>> = _chatMessages.asStateFlow()

    private val _confirmedTranscript = MutableStateFlow("")
    val confirmedTranscript: StateFlow<String> = _confirmedTranscript.asStateFlow()

    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    private var currentSessionId: String? = null
    private var speechCollectJob: Job? = null
    private var currentSettings: AppSettings = AppSettings()
    private val sessionTransitionMutex = Mutex()
    private val batchGenerations = ConcurrentHashMap<String, Long>()
    @Volatile
    private var contentGeneration = 0L

    private val analysisQueue = AnalysisQueue(externalScope) { batch ->
        analyzeOneBatch(batch)
    }

    init {
        externalScope.launch {
            // Recover any crashed in-flight segments to pending
            segmentDao.recoverInFlightToPending()

            // Observe settings
            settingsRepository.settingsFlow.collect { settings ->
                currentSettings = settings
            }
        }
        analysisQueue.start()
    }

    fun currentPolicy(): BatchPolicy {
        return BatchPolicy(
            silenceTimeoutMs = currentSettings.silenceTimeoutMs,
            maximumWaitMs = currentSettings.maximumWaitMs,
            maximumChars = currentSettings.maximumChars,
            minimumChars = currentSettings.minimumChars,
            contextChars = currentSettings.contextChars
        )
    }

    suspend fun startSession(): Boolean = sessionTransitionMutex.withLock {
        if (_captureState.value !is CaptureState.Idle &&
            _captureState.value !is CaptureState.Error
        ) return@withLock _captureState.value is CaptureState.Listening

        _captureState.value = CaptureState.Starting
        currentSettings = settingsRepository.settingsFlow.first()

        // A fatal recognizer callback may leave a session open. Close it before retrying.
        finishCurrentSessionRecord()
        val sessionId = UUID.randomUUID().toString()
        currentSessionId = sessionId

        // Clear in-memory buffers
        transcriptBuffer.clear()
        TranscriptNormalizer.clearCache()
        _recentAnswers.value = emptyList()
        _confirmedTranscript.value = ""
        _lastMessage.value = null

        // Record session in database
        val sessionEntity = SessionEntity(
            id = sessionId,
            startedAt = System.currentTimeMillis(),
            language = currentSettings.language
        )
        sessionDao.insertSession(sessionEntity)

        // Start listening to speech events
        listenToSpeechEvents()

        try {
            actualSpeechToText.start(currentSettings.language)
            _captureState.value = CaptureState.Listening("")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognizer", e)
            speechCollectJob?.cancel()
            speechCollectJob = null
            sessionDao.deleteSession(sessionId)
            currentSessionId = null
            _captureState.value = CaptureState.Error(e.localizedMessage ?: "启动录音失败")
            _lastMessage.value = e.localizedMessage ?: "启动录音失败"
            false
        }
    }

    suspend fun stopSession() = sessionTransitionMutex.withLock {
        if (_captureState.value is CaptureState.Idle ||
            _captureState.value is CaptureState.Stopping
        ) return@withLock

        _captureState.value = CaptureState.Stopping

        var pendingPartial: String? = null
        try {
            pendingPartial = actualSpeechToText.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognizer", e)
        }

        // A platform recognizer does not guarantee a final callback after stop.
        // Preserve the last partial result before forcing the remaining batch.
        pendingPartial?.let { onFinalTranscript(it) }

        speechCollectJob?.cancel()
        speechCollectJob = null

        // Force flush remaining segments
        flush(FlushReason.STOP_LISTENING, force = true)

        finishCurrentSessionRecord()
        _captureState.value = CaptureState.Idle
    }

    suspend fun flushNow() {
        // "立即发送" must also finalize text that is still only a partial result.
        if (_captureState.value is CaptureState.Listening) {
            runCatching { actualSpeechToText.finalizeCurrentText() }
                .onFailure { Log.w(TAG, "Unable to finalize current utterance", it) }
                .getOrNull()
                ?.let { onFinalTranscript(it) }
        }
        flush(FlushReason.USER_MANUAL, force = true)
    }

    private fun listenToSpeechEvents() {
        speechCollectJob?.cancel()
        // Register the SharedFlow subscriber before startListening(). Without an
        // undispatched start, very early partial/final callbacks can be dropped.
        speechCollectJob = externalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            actualSpeechToText.events.collect { event ->
                handleSpeechEvent(event)
            }
        }
    }

    private suspend fun handleSpeechEvent(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.Partial -> {
                // Partial text ONLY updates UI, never writes to DB or triggers LLM
                if (_captureState.value is CaptureState.Listening) {
                    _captureState.value = CaptureState.Listening(partialText = event.text)
                }
            }

            is SpeechEvent.Final -> {
                if (_captureState.value is CaptureState.Listening) {
                    _captureState.value = CaptureState.Listening(partialText = "")
                }
                onFinalTranscript(event.text)
            }

            is SpeechEvent.Silence -> {
                // Keep listening state without partial text
                if (_captureState.value is CaptureState.Listening) {
                    _captureState.value = CaptureState.Listening(partialText = "")
                }
            }

            is SpeechEvent.Status -> {
                _lastMessage.value = event.message
            }

            is SpeechEvent.Error -> {
                Log.w(TAG, "SpeechEvent error: ${event.code} - ${event.message}")
                if (event.isFatal) {
                    _captureState.value = CaptureState.Error(event.message)
                }
                _lastMessage.value = "语音提示: ${event.message}"
            }
        }
    }

    internal suspend fun onFinalTranscript(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val sessionId = currentSessionId ?: return
        val now = System.currentTimeMillis()

        // 1. Insert into database as PENDING
        val segmentEntity = TranscriptSegmentEntity(
            sessionId = sessionId,
            text = trimmed,
            startTimeMs = now - (trimmed.length * 200L).coerceAtLeast(1000L),
            endTimeMs = now,
            status = SegmentStatus.PENDING
        )
        val generatedId = segmentDao.insertSegment(segmentEntity)

        // 2. Append to in-memory TranscriptBuffer
        val domainSegment = TranscriptSegment(
            id = generatedId,
            sessionId = sessionId,
            text = trimmed,
            isFinal = true,
            startTimeMs = segmentEntity.startTimeMs,
            endTimeMs = segmentEntity.endTimeMs,
            createdAtMs = now
        )
        transcriptBuffer.append(domainSegment)

        val userMsg = ChatMessageItem(
            id = "user_${System.currentTimeMillis()}_${generatedId}",
            sender = ChatSender.USER,
            content = trimmed,
            timestamp = now
        )
        _chatMessages.update { it + userMsg }

        // Update confirmed transcript for UI
        _confirmedTranscript.value = if (_confirmedTranscript.value.isEmpty()) {
            trimmed
        } else {
            "${_confirmedTranscript.value}\n$trimmed"
        }

        val pendingChars = transcriptBuffer.pendingChars()
        _analysisState.value = AnalysisState.Waiting(pendingChars)

        // 3. 聊天模式下用户发言转写完成即立刻触发 AI 分析处理
        flush(FlushReason.USER_MANUAL, force = true)
    }

    private suspend fun flush(reason: FlushReason, force: Boolean) {
        batchScheduler.cancel()
        val sessionId = currentSessionId ?: return
        val policy = currentPolicy()

        // Get previously answered questions for this session
        val previouslyAnswered = answerDao.getRecentQuestionsForSession(sessionId, limit = 10)

        val batch = transcriptBuffer.snapshot(
            policy = policy,
            previouslyAnswered = previouslyAnswered,
            force = force
        ) ?: return

        Log.d(TAG, "Batch snapshot created: ${batch.id} (${batch.segmentIds.size} segments, reason=$reason)")

        // Update segment status in DB to IN_FLIGHT
        segmentDao.updateSegmentStatus(batch.segmentIds, SegmentStatus.IN_FLIGHT)
        batchGenerations[batch.id] = contentGeneration

        // Enqueue to single consumer channel
        _analysisState.value = AnalysisState.Waiting(transcriptBuffer.pendingChars())
        analysisQueue.enqueue(batch)
    }

    internal suspend fun analyzeOneBatch(batch: AnalysisBatch) {
        val batchGeneration = batchGenerations[batch.id] ?: contentGeneration
        if (batchGeneration != contentGeneration) return
        _analysisState.value = AnalysisState.Sending(batch.id)

        val provider = if (currentSettings.useFakeLlm) {
            fakeLlmProvider
        } else {
            actualLlmProvider
        }

        val llmSettings = LlmSettings(
            baseUrl = currentSettings.baseUrl,
            model = currentSettings.model,
            maximumOutputTokens = 300,
            temperature = 0.1
        )

        val apiKey = apiKeyStore.load()

        try {
            val result = provider.analyze(batch, llmSettings, apiKey)
            if (batchGeneration != contentGeneration) return

            // Commit batch in buffer
            transcriptBuffer.commit(batch.id)
            segmentDao.updateSegmentStatus(batch.segmentIds, SegmentStatus.COMMITTED)

            if (!result.hasQuestion || result.questions.isEmpty()) {
                _lastMessage.value = "本段未识别到问题"
                _analysisState.value = AnalysisState.Idle
                return
            }

            for (q in result.questions) {
                val isErr = q.isError || q.answer.startsWith("❌")
                val assistantMsg = ChatMessageItem(
                    id = "asst_${System.currentTimeMillis()}_${UUID.randomUUID()}",
                    sender = ChatSender.ASSISTANT,
                    content = q.answer,
                    timestamp = System.currentTimeMillis(),
                    isError = isErr
                )
                _chatMessages.update { it + assistantMsg }

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

                // TTS 朗读（仅正常回答且开启 TTS 时）
                if (!isErr && currentSettings.ttsEnabled) {
                    actualTtsManager.speak(q.answer)
                }
            }

            _recentAnswers.value = _recentAnswers.value + result.questions
            _lastMessage.value = result.message.ifBlank { "已收到回复" }
            _analysisState.value = AnalysisState.Idle
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (batchGeneration != contentGeneration) return
            Log.e(TAG, "LLM analysis failed for batch ${batch.id}", e)
            // Rollback segments back to pending
            transcriptBuffer.rollback(batch.id)
            segmentDao.updateSegmentStatus(batch.segmentIds, SegmentStatus.PENDING)

            _analysisState.value = AnalysisState.Failed(batch.id, e.localizedMessage ?: "分析失败")
            _lastMessage.value = "分析异常: ${e.localizedMessage ?: "未知错误"}"
        } finally {
            batchGenerations.remove(batch.id)
        }
    }

    suspend fun clearChatHistory() = withContext(Dispatchers.IO) {
        actualTtsManager.stop()
        batchScheduler.cancel()
        contentGeneration++
        batchGenerations.clear()

        // 1. 清空所有 Room 数据表
        sessionDao.clearAll()
        segmentDao.clearAll()
        answerDao.clearAll()

        // 2. 触发 SQLite 物理压缩以释放存储空间
        runCatching {
            database.openHelper.writableDatabase.execSQL("VACUUM")
        }

        // 3. 若当前处于会话中，恢复当前 session 记录
        val isSessionActive = _captureState.value is CaptureState.Listening || _captureState.value is CaptureState.Starting
        if (isSessionActive) {
            currentSessionId?.let { sId ->
                val sessionEntity = SessionEntity(
                    id = sId,
                    startedAt = System.currentTimeMillis(),
                    language = currentSettings.language
                )
                sessionDao.insertSession(sessionEntity)
            }
        } else {
            currentSessionId = null
        }

        // 4. 重置内存队列与聊天记录
        transcriptBuffer.clear()
        TranscriptNormalizer.clearCache()
        _chatMessages.value = emptyList()
        _recentAnswers.value = emptyList()
        _confirmedTranscript.value = ""
        _analysisState.value = AnalysisState.Idle
        _lastMessage.value = "已清空所有聊天记录并释放存储空间"
    }

    private suspend fun finishCurrentSessionRecord() {
        val sessionId = currentSessionId ?: return
        sessionDao.getSessionById(sessionId)?.let { existing ->
            if (existing.endedAt == null) {
                sessionDao.updateSession(existing.copy(endedAt = System.currentTimeMillis()))
            }
        }
        currentSessionId = null
    }

    fun speakAnswer(text: String) {
        actualTtsManager.speak(text, flush = true)
    }

    fun release() {
        speechCollectJob?.cancel()
        externalScope.launch {
            batchScheduler.cancel()
        }
        analysisQueue.stop()
        actualSpeechToText.release()
        actualTtsManager.shutdown()
    }
}
