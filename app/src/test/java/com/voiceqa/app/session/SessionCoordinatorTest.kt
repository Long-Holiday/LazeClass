package com.voiceqa.app.session

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.voiceqa.app.batching.AnalysisBatch
import com.voiceqa.app.batching.AnalysisResult
import com.voiceqa.app.batching.QuestionAnswer
import com.voiceqa.app.chat.ChatMessageItem
import com.voiceqa.app.chat.ChatSender
import com.voiceqa.app.data.AnswerDao
import com.voiceqa.app.data.AnswerEntity
import com.voiceqa.app.data.AppDatabase
import com.voiceqa.app.data.SegmentDao
import com.voiceqa.app.data.SegmentStatus
import com.voiceqa.app.data.SessionDao
import com.voiceqa.app.data.SessionEntity
import com.voiceqa.app.data.TranscriptSegmentEntity
import com.voiceqa.app.llm.LlmPromptFactory
import com.voiceqa.app.llm.LlmProvider
import com.voiceqa.app.llm.LlmSettings
import com.voiceqa.app.security.ApiKeyStore
import com.voiceqa.app.settings.AppSettings
import com.voiceqa.app.settings.SettingsRepository
import com.voiceqa.app.speech.SpeechEvent
import com.voiceqa.app.speech.SpeechToText
import com.voiceqa.app.tts.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class SessionCoordinatorTest {

    private lateinit var fakeSessionDao: FakeSessionDao
    private lateinit var fakeSegmentDao: FakeSegmentDao
    private lateinit var fakeAnswerDao: FakeAnswerDao
    private lateinit var fakeDatabase: FakeAppDatabase
    private lateinit var fakeSettingsRepo: FakeSettingsRepository
    private lateinit var fakeApiKeyStore: FakeApiKeyStore
    private lateinit var fakeSpeechToText: FakeSpeechToText
    private lateinit var fakeTtsManager: FakeTtsManager
    private lateinit var testScope: CoroutineScope
    private var executedSqlStatements = mutableListOf<String>()

    @Before
    fun setUp() {
        executedSqlStatements.clear()
        fakeSessionDao = FakeSessionDao()
        fakeSegmentDao = FakeSegmentDao()
        fakeAnswerDao = FakeAnswerDao()
        fakeDatabase = FakeAppDatabase(
            fakeSessionDao = fakeSessionDao,
            fakeSegmentDao = fakeSegmentDao,
            fakeAnswerDao = fakeAnswerDao,
            onSqlExecuted = { executedSqlStatements.add(it) }
        )
        fakeSettingsRepo = FakeSettingsRepository()
        fakeApiKeyStore = FakeApiKeyStore()
        fakeSpeechToText = FakeSpeechToText()
        fakeTtsManager = FakeTtsManager()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    private fun createCoordinator(
        llmProvider: LlmProvider? = null,
        initialSettings: AppSettings = AppSettings()
    ): SessionCoordinator {
        fakeSettingsRepo = FakeSettingsRepository(initialSettings)
        return SessionCoordinator(
            context = null,
            database = fakeDatabase,
            settingsRepository = fakeSettingsRepo,
            apiKeyStore = fakeApiKeyStore,
            asrApiKeyStore = fakeApiKeyStore,
            speechToText = fakeSpeechToText,
            llmProvider = llmProvider,
            ttsManager = fakeTtsManager,
            externalScope = testScope
        )
    }

    @Test
    fun testInitialChatMessagesIsEmpty() {
        val coordinator = createCoordinator()
        assertTrue(coordinator.chatMessages.value.isEmpty())
        assertEquals(0, coordinator.chatMessages.value.size)
    }

    @Test
    fun testOnFinalTranscriptAppendsUserMessage() = runBlocking {
        val coordinator = createCoordinator()
        coordinator.startSession()

        val text = "今天天气怎么样？"
        coordinator.onFinalTranscript(text)

        val messages = coordinator.chatMessages.value
        assertEquals(1, messages.size)

        val userMsg = messages[0]
        assertEquals(ChatSender.USER, userMsg.sender)
        assertEquals(text, userMsg.content)
        assertFalse(userMsg.isError)
        assertTrue(userMsg.id.startsWith("user_"))
        assertTrue(userMsg.timestamp > 0)
    }

    @Test
    fun testBlankTranscriptDoesNotAppendMessage() = runBlocking {
        val coordinator = createCoordinator()
        coordinator.startSession()

        coordinator.onFinalTranscript("   ")
        coordinator.onFinalTranscript("")

        assertTrue(coordinator.chatMessages.value.isEmpty())
    }

    @Test
    fun testMultipleUserTranscriptsAccumulateInOrder() = runBlocking {
        val coordinator = createCoordinator()
        coordinator.startSession()

        coordinator.onFinalTranscript("第一条消息")
        coordinator.onFinalTranscript("第二条消息")
        coordinator.onFinalTranscript("第三条消息")

        val messages = coordinator.chatMessages.value
        assertEquals(3, messages.size)
        assertEquals("第一条消息", messages[0].content)
        assertEquals("第二条消息", messages[1].content)
        assertEquals("第三条消息", messages[2].content)
        assertTrue(messages.all { it.sender == ChatSender.USER })
    }

    @Test
    fun testAnalyzeOneBatchAppendsAssistantMessageAndSpeaksTts() = runBlocking {
        val testLlmProvider = object : LlmProvider {
            override suspend fun analyze(batch: AnalysisBatch, settings: LlmSettings, apiKey: String?): AnalysisResult {
                return AnalysisResult(
                    hasQuestion = true,
                    questions = listOf(
                        QuestionAnswer(
                            question = "故宫门票多少钱？",
                            answer = "故宫门票旺季60元，淡季40元。"
                        )
                    ),
                    message = "已收到回复"
                )
            }
        }

        val coordinator = createCoordinator(
            llmProvider = testLlmProvider,
            initialSettings = AppSettings(ttsEnabled = true)
        )
        coordinator.startSession()

        val batch = AnalysisBatch(
            id = "batch-1",
            sessionId = "session-test-1",
            segmentIds = listOf(1L),
            context = "",
            newText = "故宫门票多少钱？",
            previouslyAnswered = emptyList()
        )

        coordinator.analyzeOneBatch(batch)

        val messages = coordinator.chatMessages.value
        assertEquals(1, messages.size)

        val assistantMsg = messages[0]
        assertEquals(ChatSender.ASSISTANT, assistantMsg.sender)
        assertEquals("故宫门票旺季60元，淡季40元。", assistantMsg.content)
        assertFalse(assistantMsg.isError)
        assertTrue(assistantMsg.id.startsWith("asst_"))

        // Verify persisted to answerDao
        assertEquals(1, fakeAnswerDao.answers.size)
        assertEquals("故宫门票旺季60元，淡季40元。", fakeAnswerDao.answers[0].answer)

        // Verify spoken with TTS
        assertEquals(1, fakeTtsManager.spokenTexts.size)
        assertEquals("故宫门票旺季60元，淡季40元。", fakeTtsManager.spokenTexts[0])
    }

    @Test
    fun testAnalyzeOneBatchWithErrorMessageSetsIsErrorAndSkipsTts() = runBlocking {
        val testLlmProvider = object : LlmProvider {
            override suspend fun analyze(batch: AnalysisBatch, settings: LlmSettings, apiKey: String?): AnalysisResult {
                return AnalysisResult(
                    hasQuestion = true,
                    questions = listOf(
                        QuestionAnswer(
                            question = "测试网络",
                            answer = "❌ [网络异常] 无法连接到服务器"
                        )
                    ),
                    message = "网络异常"
                )
            }
        }

        val coordinator = createCoordinator(
            llmProvider = testLlmProvider,
            initialSettings = AppSettings(ttsEnabled = true)
        )
        coordinator.startSession()

        val batch = AnalysisBatch(
            id = "batch-err",
            sessionId = "session-test-err",
            segmentIds = listOf(2L),
            context = "",
            newText = "测试网络",
            previouslyAnswered = emptyList()
        )

        coordinator.analyzeOneBatch(batch)

        val messages = coordinator.chatMessages.value
        assertEquals(1, messages.size)

        val errMsg = messages[0]
        assertEquals(ChatSender.ASSISTANT, errMsg.sender)
        assertTrue(errMsg.isError)
        assertTrue(errMsg.content.startsWith("❌"))

        // Persisted to answerDao
        assertEquals(1, fakeAnswerDao.answers.size)

        // TTS should NOT speak error answers
        assertTrue(fakeTtsManager.spokenTexts.isEmpty())
    }

    @Test
    fun testTtsDisabledDoesNotSpeak() = runBlocking {
        val testLlmProvider = object : LlmProvider {
            override suspend fun analyze(batch: AnalysisBatch, settings: LlmSettings, apiKey: String?): AnalysisResult {
                return AnalysisResult(
                    hasQuestion = true,
                    questions = listOf(
                        QuestionAnswer(
                            question = "你好",
                            answer = "你好！有什么我可以帮你的？"
                        )
                    ),
                    message = "已收到回复"
                )
            }
        }

        val coordinator = createCoordinator(
            llmProvider = testLlmProvider,
            initialSettings = AppSettings(ttsEnabled = false)
        )
        coordinator.startSession()

        val batch = AnalysisBatch(
            id = "batch-2",
            sessionId = "session-test-2",
            segmentIds = listOf(3L),
            context = "",
            newText = "你好",
            previouslyAnswered = emptyList()
        )

        coordinator.analyzeOneBatch(batch)

        // TTS should NOT speak when ttsEnabled is false
        assertTrue(fakeTtsManager.spokenTexts.isEmpty())
        assertEquals(1, coordinator.chatMessages.value.size)
    }

    @Test
    fun testClearChatHistoryClearsDaosVacuumAndMemoryState() = runBlocking {
        val coordinator = createCoordinator()
        coordinator.startSession()

        // 1. Add user message
        coordinator.onFinalTranscript("第一条提问")
        assertEquals(1, coordinator.chatMessages.value.size)
        assertEquals(1, fakeSegmentDao.segments.size)
        assertEquals(1, fakeSessionDao.sessions.size)

        // 2. Add assistant message via analyzeOneBatch
        val batch = AnalysisBatch(
            id = "batch-clear-test",
            sessionId = fakeSessionDao.sessions[0].id,
            segmentIds = listOf(fakeSegmentDao.segments[0].id),
            context = "",
            newText = "第一条提问",
            previouslyAnswered = emptyList()
        )
        val dummyLlm = object : LlmProvider {
            override suspend fun analyze(batch: AnalysisBatch, settings: LlmSettings, apiKey: String?): AnalysisResult {
                return AnalysisResult(
                    hasQuestion = true,
                    questions = listOf(QuestionAnswer("第一条提问", "这是第一条回答")),
                    message = "已收到回复"
                )
            }
        }
        val coordinatorWithLlm = createCoordinator(llmProvider = dummyLlm)
        coordinatorWithLlm.startSession()
        coordinatorWithLlm.onFinalTranscript("用户的问题")
        coordinatorWithLlm.analyzeOneBatch(batch)

        assertTrue(coordinatorWithLlm.chatMessages.value.isNotEmpty())

        // 3. Invoke clearChatHistory()
        coordinatorWithLlm.clearChatHistory()

        // 4. Verify DAOs were cleared
        assertTrue("sessionDao.clearAll should be called", fakeSessionDao.clearAllCalled)
        assertTrue("segmentDao.clearAll should be called", fakeSegmentDao.clearAllCalled)
        assertTrue("answerDao.clearAll should be called", fakeAnswerDao.clearAllCalled)
        assertTrue("ttsManager.stop should be called", fakeTtsManager.stopCalled)

        // 5. Verify SQLite VACUUM was executed
        assertTrue(
            "SQLite VACUUM should be executed",
            executedSqlStatements.contains("VACUUM")
        )

        // 6. Verify in-memory states are reset
        assertTrue("chatMessages should be empty", coordinatorWithLlm.chatMessages.value.isEmpty())
        assertTrue("recentAnswers should be empty", coordinatorWithLlm.recentAnswers.value.isEmpty())
        assertEquals("", coordinatorWithLlm.confirmedTranscript.value)
        assertEquals("已清空所有聊天记录并释放存储空间", coordinatorWithLlm.lastMessage.value)
    }

    @Test
    fun testAnalyzeOneBatchRecognizesIsErrorFlag() = runBlocking {
        val testLlmProvider = object : LlmProvider {
            override suspend fun analyze(batch: AnalysisBatch, settings: LlmSettings, apiKey: String?): AnalysisResult {
                return AnalysisResult(
                    hasQuestion = true,
                    questions = listOf(
                        QuestionAnswer(
                            question = "故宫门票多少钱？",
                            answer = "自定义错误消息（未以红叉开头）",
                            isError = true
                        )
                    ),
                    message = "分析异常"
                )
            }
        }

        val coordinator = createCoordinator(
            llmProvider = testLlmProvider,
            initialSettings = AppSettings(ttsEnabled = true)
        )
        coordinator.startSession()

        val batch = AnalysisBatch(
            id = "batch-error-flag",
            sessionId = "session-test-error",
            segmentIds = listOf(1L),
            context = "",
            newText = "故宫门票多少钱？",
            previouslyAnswered = emptyList()
        )

        coordinator.analyzeOneBatch(batch)

        val messages = coordinator.chatMessages.value
        assertEquals(1, messages.size)
        assertTrue(messages[0].isError)
        // TTS should NOT speak when isError = true
        assertTrue(fakeTtsManager.spokenTexts.isEmpty())
    }

    @Test
    fun testStopSessionClearsCurrentSessionIdAndClearChatHistoryDoesNotRestore() = runBlocking {
        val coordinator = createCoordinator()
        coordinator.startSession()
        coordinator.onFinalTranscript("第一条消息")

        coordinator.stopSession()
        fakeSessionDao.clearAllCalled = false

        coordinator.clearChatHistory()

        assertTrue(fakeSessionDao.clearAllCalled)
        assertTrue(fakeSessionDao.sessions.isEmpty())
    }

    @Test
    fun testOnFinalTranscriptImmediatelyTriggersAiAnalysisWithoutStopping() = runBlocking {
        var analyzedBatch: AnalysisBatch? = null
        val testLlmProvider = object : LlmProvider {
            override suspend fun analyze(batch: AnalysisBatch, settings: LlmSettings, apiKey: String?): AnalysisResult {
                analyzedBatch = batch
                return AnalysisResult(
                    hasQuestion = true,
                    questions = listOf(QuestionAnswer("你好", "你好！很高兴为您服务。")),
                    message = "已收到回复"
                )
            }
        }

        val coordinator = createCoordinator(llmProvider = testLlmProvider)
        coordinator.startSession()

        // 发送短文本，只有2个字
        coordinator.onFinalTranscript("你好")

        // 验证：不需要调用 stopSession()，AI分析已被立即触发
        val messages = coordinator.chatMessages.value
        assertEquals(2, messages.size)
        assertEquals(ChatSender.USER, messages[0].sender)
        assertEquals("你好", messages[0].content)
        assertEquals(ChatSender.ASSISTANT, messages[1].sender)
        assertEquals("你好！很高兴为您服务。", messages[1].content)
        assertNotNull(analyzedBatch)
        assertEquals("[1] 你好", analyzedBatch?.newText)
        assertEquals("你好", LlmPromptFactory.createUserPrompt(analyzedBatch!!))
    }

    @Test
    fun testFlushNowFinalizesCurrentPartialBeforeAnalysis() = runBlocking {
        var analyzedText: String? = null
        val testLlmProvider = object : LlmProvider {
            override suspend fun analyze(
                batch: AnalysisBatch,
                settings: LlmSettings,
                apiKey: String?
            ): AnalysisResult {
                analyzedText = batch.newText
                return AnalysisResult(
                    hasQuestion = true,
                    questions = listOf(QuestionAnswer(batch.newText, "已处理")),
                    message = "已收到回复"
                )
            }
        }
        val coordinator = createCoordinator(llmProvider = testLlmProvider)
        fakeSpeechToText.finalizeResult = "还没等到自动切段的问题"
        coordinator.startSession()

        coordinator.flushNow()

        assertEquals(1, fakeSpeechToText.finalizeCalls)
        assertTrue(analyzedText?.contains("还没等到自动切段的问题") == true)
    }

    @Test
    fun testFailedRecognizerStartRemovesIncompleteSession() = runBlocking {
        val coordinator = createCoordinator()
        fakeSpeechToText.startError = IllegalStateException("录音启动失败")

        assertFalse(coordinator.startSession())

        assertTrue(fakeSessionDao.sessions.isEmpty())
        assertTrue(coordinator.captureState.value is CaptureState.Error)
        assertEquals("录音启动失败", coordinator.lastMessage.value)
    }

    // --- Test Doubles / Fakes ---

    class FakeSessionDao : SessionDao {
        val sessions = mutableListOf<SessionEntity>()
        var clearAllCalled = false

        override suspend fun insertSession(session: SessionEntity) {
            sessions.removeAll { it.id == session.id }
            sessions.add(session)
        }

        override suspend fun updateSession(session: SessionEntity) {
            insertSession(session)
        }

        override fun observeAllSessions(): Flow<List<SessionEntity>> = flowOf(sessions)

        override suspend fun getSessionById(sessionId: String): SessionEntity? {
            return sessions.find { it.id == sessionId }
        }

        override suspend fun deleteSession(sessionId: String) {
            sessions.removeAll { it.id == sessionId }
        }

        override suspend fun deleteSessionsOlderThan(cutoffTimestamp: Long) {
            sessions.removeAll { it.startedAt < cutoffTimestamp }
        }

        override suspend fun deleteOtherSessions(keepSessionId: String) {
            sessions.removeAll { it.id != keepSessionId }
        }

        override suspend fun clearAll() {
            clearAllCalled = true
            sessions.clear()
        }
    }

    class FakeSegmentDao : SegmentDao {
        val segments = mutableListOf<TranscriptSegmentEntity>()
        private var idCounter = 1L
        var clearAllCalled = false

        override suspend fun insertSegment(segment: TranscriptSegmentEntity): Long {
            val id = if (segment.id > 0) segment.id else idCounter++
            val entity = segment.copy(id = id)
            segments.add(entity)
            return id
        }

        override suspend fun insertSegments(segments: List<TranscriptSegmentEntity>): List<Long> {
            return segments.map { insertSegment(it) }
        }

        override fun observeSegmentsForSession(sessionId: String): Flow<List<TranscriptSegmentEntity>> {
            return flowOf(segments.filter { it.sessionId == sessionId })
        }

        override suspend fun getSegmentsForSession(sessionId: String): List<TranscriptSegmentEntity> {
            return segments.filter { it.sessionId == sessionId }
        }

        override suspend fun updateSegmentStatus(segmentIds: List<Long>, newStatus: SegmentStatus) {
            for (i in segments.indices) {
                if (segments[i].id in segmentIds) {
                    segments[i] = segments[i].copy(status = newStatus)
                }
            }
        }

        override suspend fun recoverInFlightToPending() {
            for (i in segments.indices) {
                if (segments[i].status == SegmentStatus.IN_FLIGHT) {
                    segments[i] = segments[i].copy(status = SegmentStatus.PENDING)
                }
            }
        }

        override suspend fun deleteSegmentsBySession(sessionId: String) {
            segments.removeAll { it.sessionId == sessionId }
        }

        override suspend fun deleteSegmentsOlderThan(cutoffTimestamp: Long) {
            segments.removeAll { it.startTimeMs < cutoffTimestamp }
        }

        override suspend fun deleteOtherSegments(keepSessionId: String) {
            segments.removeAll { it.sessionId != keepSessionId }
        }

        override suspend fun clearAll() {
            clearAllCalled = true
            segments.clear()
        }
    }

    class FakeAnswerDao : AnswerDao {
        val answers = mutableListOf<AnswerEntity>()
        private var idCounter = 1L
        var clearAllCalled = false

        override suspend fun insertAnswer(answer: AnswerEntity): Long {
            val id = if (answer.id > 0) answer.id else idCounter++
            val entity = answer.copy(id = id)
            answers.add(entity)
            return id
        }

        override suspend fun insertAnswers(answers: List<AnswerEntity>): List<Long> {
            return answers.map { insertAnswer(it) }
        }

        override fun observeAnswersForSession(sessionId: String): Flow<List<AnswerEntity>> {
            return flowOf(answers.filter { it.sessionId == sessionId })
        }

        override fun observeAllAnswers(): Flow<List<AnswerEntity>> = flowOf(answers)

        override suspend fun getRecentQuestionsForSession(sessionId: String, limit: Int): List<String> {
            return answers.filter { it.sessionId == sessionId }
                .sortedByDescending { it.createdAt }
                .take(limit)
                .map { it.question }
        }

        override suspend fun deleteAnswersBySession(sessionId: String) {
            answers.removeAll { it.sessionId == sessionId }
        }

        override suspend fun deleteAnswersOlderThan(cutoffTimestamp: Long) {
            answers.removeAll { it.createdAt < cutoffTimestamp }
        }

        override suspend fun deleteOtherAnswers(keepSessionId: String) {
            answers.removeAll { it.sessionId != keepSessionId }
        }

        override suspend fun clearAll() {
            clearAllCalled = true
            answers.clear()
        }
    }

    class FakeAppDatabase(
        val fakeSessionDao: FakeSessionDao = FakeSessionDao(),
        val fakeSegmentDao: FakeSegmentDao = FakeSegmentDao(),
        val fakeAnswerDao: FakeAnswerDao = FakeAnswerDao(),
        val onSqlExecuted: (String) -> Unit = {}
    ) : AppDatabase() {
        override fun sessionDao(): SessionDao = fakeSessionDao
        override fun segmentDao(): SegmentDao = fakeSegmentDao
        override fun answerDao(): AnswerDao = fakeAnswerDao

        override fun clearAllTables() {
            runBlocking {
                fakeSessionDao.clearAll()
                fakeSegmentDao.clearAll()
                fakeAnswerDao.clearAll()
            }
        }

        override fun createInvalidationTracker(): InvalidationTracker {
            return InvalidationTracker(this, "sessions", "transcript_segments", "answers")
        }

        override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper {
            return fakeOpenHelperInstance
        }

        private val fakeDb: SupportSQLiteDatabase = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") {
                val sql = args?.firstOrNull() as? String ?: ""
                onSqlExecuted(sql)
            }
            null
        } as SupportSQLiteDatabase

        private val fakeOpenHelperInstance: SupportSQLiteOpenHelper = Proxy.newProxyInstance(
            SupportSQLiteOpenHelper::class.java.classLoader,
            arrayOf(SupportSQLiteOpenHelper::class.java)
        ) { _, method, _ ->
            if (method.name == "getWritableDatabase") {
                fakeDb
            } else {
                null
            }
        } as SupportSQLiteOpenHelper

        override val openHelper: SupportSQLiteOpenHelper
            get() = fakeOpenHelperInstance
    }

    class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository(null) {
        private val _flow = MutableStateFlow(initial)
        override val settingsFlow: Flow<AppSettings> = _flow

        override suspend fun updateSettings(settings: AppSettings) {
            _flow.value = settings
        }
    }

    class FakeApiKeyStore(private var key: String? = "test-api-key") : ApiKeyStore {
        override suspend fun save(apiKey: String) { key = apiKey }
        override suspend fun load(): String? = key
        override suspend fun clear() { key = null }
    }

    class FakeTtsManager : TextToSpeechManager(null) {
        val spokenTexts = mutableListOf<String>()
        var shutdownCalled = false
        var stopCalled = false

        override fun speak(text: String, flush: Boolean) {
            spokenTexts.add(text)
        }

        override fun stop() {
            stopCalled = true
        }

        override fun shutdown() {
            shutdownCalled = true
        }
    }

    class FakeSpeechToText : SpeechToText {
        val eventsFlow = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 16)
        override val events: Flow<SpeechEvent> = eventsFlow
        var isStarted = false
        var stopResult: String? = null
        var finalizeResult: String? = null
        var finalizeCalls = 0
        var startError: Exception? = null

        override suspend fun start(locale: String) {
            startError?.let { throw it }
            isStarted = true
        }

        override suspend fun finalizeCurrentText(): String? {
            finalizeCalls++
            return finalizeResult.also { finalizeResult = null }
        }

        override suspend fun stop(): String? {
            isStarted = false
            return stopResult
        }

        override fun release() {}
    }
}
