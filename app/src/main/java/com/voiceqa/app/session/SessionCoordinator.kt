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
import com.voiceqa.app.speech.AndroidSpeechRecognizer
import com.voiceqa.app.speech.SpeechEvent
import com.voiceqa.app.speech.SpeechToText
import com.voiceqa.app.speech.TranscriptNormalizer
import com.voiceqa.app.tts.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class SessionCoordinator(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
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

    private val actualSpeechToText = speechToText ?: AndroidSpeechRecognizer(context, externalScope)
    private val actualLlmProvider = llmProvider ?: GenericLlmProvider()
    private val fakeLlmProvider = FakeLlmProvider()
    private val actualTtsManager = ttsManager ?: TextToSpeechManager(context)

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

    private val _confirmedTranscript = MutableStateFlow("")
    val confirmedTranscript: StateFlow<String> = _confirmedTranscript.asStateFlow()

    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    private var currentSessionId: String? = null
    private var speechCollectJob: Job? = null
    private var currentSettings: AppSettings = AppSettings()

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

    suspend fun startSession() {
        if (_captureState.value is CaptureState.Listening) return

        _captureState.value = CaptureState.Starting
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognizer", e)
            _captureState.value = CaptureState.Error(e.localizedMessage ?: "启动录音失败")
        }
    }

    suspend fun stopSession() {
        if (_captureState.value is CaptureState.Idle) return

        _captureState.value = CaptureState.Stopping
        speechCollectJob?.cancel()
        speechCollectJob = null

        try {
            actualSpeechToText.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognizer", e)
        }

        // Force flush remaining segments
        flush(FlushReason.STOP_LISTENING, force = true)

        // Update session entity endedAt
        currentSessionId?.let { sId ->
            val existing = sessionDao.getSessionById(sId)
            if (existing != null) {
                sessionDao.updateSession(existing.copy(endedAt = System.currentTimeMillis()))
            }
        }

        _captureState.value = CaptureState.Idle
    }

    suspend fun flushNow() {
        flush(FlushReason.USER_MANUAL, force = true)
    }

    private fun listenToSpeechEvents() {
        speechCollectJob?.cancel()
        speechCollectJob = externalScope.launch {
            actualSpeechToText.events.collect { event ->
                handleSpeechEvent(event)
            }
        }
    }

    private suspend fun handleSpeechEvent(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.Partial -> {
                // Partial text ONLY updates UI, never writes to DB or triggers LLM
                _captureState.value = CaptureState.Listening(partialText = event.text)
            }

            is SpeechEvent.Final -> {
                _captureState.value = CaptureState.Listening(partialText = "")
                onFinalTranscript(event.text)
            }

            is SpeechEvent.Silence -> {
                // Keep listening state without partial text
                if (_captureState.value is CaptureState.Listening) {
                    _captureState.value = CaptureState.Listening(partialText = "")
                }
            }

            is SpeechEvent.Error -> {
                Log.w(TAG, "SpeechEvent error: ${event.code} - ${event.message}")
                if (event.code != -1 && event.code != 7) { // Filter non-fatal timeouts
                    _lastMessage.value = "语音提示: ${event.message}"
                }
            }
        }
    }

    private suspend fun onFinalTranscript(text: String) {
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

        // Update confirmed transcript for UI
        _confirmedTranscript.value = if (_confirmedTranscript.value.isEmpty()) {
            trimmed
        } else {
            "${_confirmedTranscript.value}\n$trimmed"
        }

        val policy = currentPolicy()
        val pendingChars = transcriptBuffer.pendingChars()
        _analysisState.value = AnalysisState.Waiting(pendingChars)

        // 3. Evaluate batching triggers
        when {
            pendingChars >= policy.maximumChars -> {
                Log.d(TAG, "Triggering flush: MAXIMUM_CHARS reached ($pendingChars >= ${policy.maximumChars})")
                flush(FlushReason.MAXIMUM_CHARS, force = false)
            }
            else -> {
                Log.d(TAG, "Scheduling flush: silenceDelay=${policy.silenceTimeoutMs}ms, maxWait=${policy.maximumWaitMs}ms")
                batchScheduler.schedule(
                    silenceDelayMs = policy.silenceTimeoutMs,
                    maximumDelayMs = policy.maximumWaitMs
                )
            }
        }
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

        // Enqueue to single consumer channel
        analysisQueue.enqueue(batch)

        _analysisState.value = AnalysisState.Waiting(transcriptBuffer.pendingChars())
    }

    private suspend fun analyzeOneBatch(batch: AnalysisBatch) {
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

            // Commit batch in buffer
            transcriptBuffer.commit(batch.id)
            segmentDao.updateSegmentStatus(batch.segmentIds, SegmentStatus.COMMITTED)

            if (!result.hasQuestion || result.questions.isEmpty()) {
                _lastMessage.value = "本段未识别到问题"
                _analysisState.value = AnalysisState.Idle
                return
            }

            val newAnswers = mutableListOf<QuestionAnswer>()
            for (q in result.questions) {
                val normalized = TranscriptNormalizer.normalizeQuestion(q.question)
                val hash = TranscriptNormalizer.sha256(normalized)

                // 5-minute deduplication window check
                val isDuplicate = TranscriptNormalizer.isDuplicateAndRecord(hash)
                if (!isDuplicate) {
                    newAnswers.add(q)

                    // Insert to database
                    val answerEntity = AnswerEntity(
                        sessionId = batch.sessionId,
                        batchId = batch.id,
                        question = q.question,
                        normalizedQuestionHash = hash,
                        answer = q.answer,
                        createdAt = System.currentTimeMillis()
                    )
                    answerDao.insertAnswer(answerEntity)

                    // Speak with TTS if enabled
                    if (currentSettings.ttsEnabled) {
                        actualTtsManager.speak(q.answer)
                    }
                } else {
                    Log.d(TAG, "Duplicate question ignored: ${q.question}")
                }
            }

            if (newAnswers.isNotEmpty()) {
                _recentAnswers.value = _recentAnswers.value + newAnswers
                _lastMessage.value = "已识别 ${newAnswers.size} 个新问题并回答"
            } else {
                _lastMessage.value = "问题已在5分钟内回答过，已忽略"
            }

            _analysisState.value = AnalysisState.Idle
        } catch (e: Exception) {
            Log.e(TAG, "LLM analysis failed for batch ${batch.id}", e)
            // Rollback segments back to pending
            transcriptBuffer.rollback(batch.id)
            segmentDao.updateSegmentStatus(batch.segmentIds, SegmentStatus.PENDING)

            _analysisState.value = AnalysisState.Failed(batch.id, e.localizedMessage ?: "分析失败")
            _lastMessage.value = "分析异常: ${e.localizedMessage ?: "未知错误"}"
        }
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
