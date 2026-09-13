package com.voiceqa.app.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.voiceqa.app.security.ApiKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Near-real-time MiniMax ASR adapter.
 *
 * Android records 16 kHz mono PCM. A lightweight adaptive VAD cuts the stream
 * at pauses, then each WAV segment is sent to MiniMax in order. MiniMax's SSE
 * response is exposed as partial text so the UI updates during decoding.
 */
class MiniMaxSpeechRecognizer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val apiKeyStore: ApiKeyStore,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) : SpeechToText {

    companion object {
        private const val ENDPOINT = "https://api.minimaxi.com/v1/speech_to_text"
        private const val MODEL = "asr-1.0"
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val FRAME_MILLIS = 20L
        private const val PRE_ROLL_MILLIS = 400L
        private const val END_SILENCE_MILLIS = 600L
        private const val MIN_VOICED_MILLIS = 180L
        private const val MAX_SEGMENT_MILLIS = 10_000L
        private const val MIN_VOICE_RMS = 90.0
        private const val INITIAL_NOISE_RMS = 40.0
        private const val NOISE_MULTIPLIER = 2.2
        private const val MAX_UPLOAD_ATTEMPTS = 3
        private const val ERROR_CAPTURE = -10
        private const val ERROR_API = -11
        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
    }

    private data class AudioChunk(val wavBytes: ByteArray)

    private class AsrRequestException(
        message: String,
        val retryable: Boolean
    ) : IOException(message)

    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    private val lifecycleMutex = Mutex()
    private val isListening = AtomicBoolean(false)
    private val flushRequested = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val stopTranscriptLock = Any()
    private val stopTranscripts = mutableListOf<String>()
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var uploadJob: Job? = null
    private var chunkChannel: Channel<AudioChunk>? = null

    override suspend fun start(locale: String) = lifecycleMutex.withLock {
        if (isListening.get()) return@withLock
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("没有麦克风录音权限")
        }

        val apiKey = apiKeyStore.load()?.trim()
        if (apiKey.isNullOrEmpty()) {
            throw IllegalStateException("未配置 MiniMax ASR API Key，请先在设置中填写")
        }

        val recorder = createAudioRecord()
        val channel = Channel<AudioChunk>(capacity = 8)
        audioRecord = recorder
        chunkChannel = channel
        flushRequested.set(false)
        stopping.set(false)
        synchronized(stopTranscriptLock) { stopTranscripts.clear() }
        isListening.set(true)

        try {
            withContext(Dispatchers.IO) { recorder.startRecording() }
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("麦克风录音启动失败")
            }

            uploadJob = scope.launch(Dispatchers.IO) {
                processUploads(channel, apiKey, locale)
            }
            captureJob = scope.launch(Dispatchers.IO) {
                captureAudio(recorder, channel)
            }
            _events.tryEmit(SpeechEvent.Status("麦克风已就绪，请开始说话…"))
        } catch (error: Exception) {
            isListening.set(false)
            channel.close()
            runCatching { recorder.release() }
            clearSessionReferences()
            throw error
        }
    }

    override suspend fun finalizeCurrentText(): String? {
        if (isListening.get()) {
            flushRequested.set(true)
            _events.tryEmit(SpeechEvent.Status("正在提交当前语音…"))
        }
        return null
    }

    override suspend fun stop(): String? = lifecycleMutex.withLock {
        if (!isListening.getAndSet(false)) return@withLock null
        stopping.set(true)
        flushRequested.set(true)

        val recorder = audioRecord
        withContext(Dispatchers.IO) {
            runCatching {
                if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }
        }

        listOfNotNull(captureJob, uploadJob).joinAll()
        val finalText = synchronized(stopTranscriptLock) {
            stopTranscripts.joinToString(separator = " ").trim().takeIf { it.isNotEmpty() }
                .also { stopTranscripts.clear() }
        }
        stopping.set(false)
        clearSessionReferences()
        finalText
    }

    override fun release() {
        isListening.set(false)
        stopping.set(true)
        flushRequested.set(false)
        runCatching { audioRecord?.stop() }
        captureJob?.cancel()
        chunkChannel?.close()
        uploadJob?.cancel()
        runCatching { audioRecord?.release() }
        synchronized(stopTranscriptLock) { stopTranscripts.clear() }
        clearSessionReferences()
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes <= 0) {
            throw IllegalStateException("设备不支持 16 kHz 单声道录音")
        }
        val bufferBytes = maxOf(minBufferBytes * 2, SAMPLE_RATE * BYTES_PER_SAMPLE)

        fun build(source: Int) = AudioRecord(
            source,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )

        val voiceRecorder = build(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        if (voiceRecorder.state == AudioRecord.STATE_INITIALIZED) return voiceRecorder
        voiceRecorder.release()

        val micRecorder = build(MediaRecorder.AudioSource.MIC)
        if (micRecorder.state == AudioRecord.STATE_INITIALIZED) return micRecorder
        micRecorder.release()
        throw IllegalStateException("无法初始化麦克风录音器")
    }

    private suspend fun captureAudio(
        recorder: AudioRecord,
        channel: Channel<AudioChunk>
    ) {
        val samplesPerFrame = (SAMPLE_RATE * FRAME_MILLIS / 1_000L).toInt()
        val sampleBuffer = ShortArray(samplesPerFrame)
        val preRoll = ArrayDeque<ByteArray>()
        val maxPreRollFrames = (PRE_ROLL_MILLIS / FRAME_MILLIS).toInt()
        var segment: ByteArrayOutputStream? = null
        var silentMillis = 0L
        var voicedMillis = 0L
        var noiseRms = INITIAL_NOISE_RMS

        fun submitSegment() {
            val active = segment
            if (active != null && voicedMillis >= MIN_VOICED_MILLIS && active.size() > 0) {
                val wav = MiniMaxAsrProtocol.pcm16MonoToWav(active.toByteArray(), SAMPLE_RATE)
                if (channel.trySend(AudioChunk(wav)).isFailure) {
                    _events.tryEmit(
                        SpeechEvent.Error(ERROR_CAPTURE, "识别队列已满，已丢弃一段语音")
                    )
                } else {
                    _events.tryEmit(SpeechEvent.Status("语音已提交，正在识别…"))
                }
            }
            segment = null
            silentMillis = 0L
            voicedMillis = 0L
            preRoll.clear()
        }

        try {
            while (isListening.get()) {
                val count = recorder.read(sampleBuffer, 0, sampleBuffer.size)
                when (count) {
                    AudioRecord.ERROR_DEAD_OBJECT -> throw IOException("麦克风录音设备已断开")
                    AudioRecord.ERROR_INVALID_OPERATION -> throw IOException("麦克风录音状态异常")
                    AudioRecord.ERROR_BAD_VALUE -> throw IOException("麦克风缓冲参数异常")
                }
                if (count <= 0) continue

                val pcmFrame = shortsToLittleEndian(sampleBuffer, count)
                val frameMillis = count * 1_000L / SAMPLE_RATE
                val rms = calculateRms(sampleBuffer, count)
                val voiceThreshold = max(MIN_VOICE_RMS, noiseRms * NOISE_MULTIPLIER)
                val hasVoice = rms >= voiceThreshold

                if (segment == null) {
                    preRoll.addLast(pcmFrame)
                    while (preRoll.size > maxPreRollFrames) preRoll.removeFirst()

                    if (hasVoice) {
                        _events.tryEmit(SpeechEvent.Status("检测到语音，正在收音…"))
                        segment = ByteArrayOutputStream().also { output ->
                            preRoll.forEach(output::write)
                        }
                        voicedMillis = frameMillis
                    } else {
                        // Adapt slowly to device gain and room noise. The low initial
                        // floor prevents quiet voices from being classified as silence.
                        noiseRms = noiseRms * 0.98 + rms * 0.02
                    }
                } else {
                    segment?.write(pcmFrame)
                    if (hasVoice) {
                        voicedMillis += frameMillis
                        silentMillis = 0L
                    } else {
                        silentMillis += frameMillis
                    }

                    val segmentMillis = (segment?.size() ?: 0) * 1_000L /
                        (SAMPLE_RATE * BYTES_PER_SAMPLE)
                    if (silentMillis >= END_SILENCE_MILLIS ||
                        segmentMillis >= MAX_SEGMENT_MILLIS
                    ) {
                        submitSegment()
                    }
                }

                if (flushRequested.getAndSet(false)) submitSegment()
            }
        } catch (error: Exception) {
            if (isListening.get()) {
                isListening.set(false)
                _events.tryEmit(
                    SpeechEvent.Error(
                        ERROR_CAPTURE,
                        "录音失败: ${error.message ?: "未知错误"}",
                        isFatal = true
                    )
                )
            }
        } finally {
            submitSegment()
            channel.close()
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }
            recorder.release()
        }
    }

    private suspend fun processUploads(
        channel: Channel<AudioChunk>,
        apiKey: String,
        locale: String
    ) {
        for (chunk in channel) {
            try {
                val text = transcribeWithRetry(chunk.wavBytes, apiKey, locale)
                if (text.isNotBlank()) {
                    if (stopping.get()) {
                        synchronized(stopTranscriptLock) { stopTranscripts += text }
                    } else {
                        _events.emit(SpeechEvent.Final(text))
                    }
                } else {
                    _events.emit(SpeechEvent.Silence)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _events.emit(
                    SpeechEvent.Error(ERROR_API, error.message ?: "MiniMax ASR 请求失败")
                )
            }
        }
    }

    private suspend fun transcribeWithRetry(
        wavBytes: ByteArray,
        apiKey: String,
        locale: String
    ): String {
        var lastError: Exception? = null
        repeat(MAX_UPLOAD_ATTEMPTS) { attempt ->
            try {
                return transcribeOnce(wavBytes, apiKey, locale)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                val retryable = (error as? AsrRequestException)?.retryable
                    ?: (error is IOException)
                if (!retryable || attempt == MAX_UPLOAD_ATTEMPTS - 1) throw error
                _events.emit(
                    SpeechEvent.Status("语音识别暂时失败，正在重试（${attempt + 2}/$MAX_UPLOAD_ATTEMPTS）…")
                )
                delay(500L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("MiniMax ASR 请求失败")
    }

    private suspend fun transcribeOnce(
        wavBytes: ByteArray,
        apiKey: String,
        locale: String
    ): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("stream", "true")
            .addFormDataPart("file", "voiceqa-segment.wav", wavBytes.toRequestBody(WAV_MEDIA_TYPE))
            .build()

        val requestBuilder = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
        MiniMaxAsrProtocol.languageHeader(locale)?.let { language ->
            requestBuilder.header("language", language)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                val detail = MiniMaxAsrProtocol.parseError(responseBody)
                val message = when (response.code) {
                    400 -> "MiniMax ASR 请求参数或音频格式不正确"
                    401 -> "MiniMax ASR API Key 无效或未授权"
                    402 -> "MiniMax ASR 账户余额或额度不足"
                    413 -> "录音片段超过 MiniMax 上传大小限制"
                    422 -> "录音内容无法处理"
                    429 -> "MiniMax ASR 请求过于频繁"
                    in 500..599 -> "MiniMax ASR 服务暂时异常 (${response.code})"
                    else -> "MiniMax ASR 请求失败 (${response.code})"
                }
                throw AsrRequestException(
                    message = "$message${detail?.let { ": $it" }.orEmpty()}",
                    retryable = response.code == 429 || response.code >= 500
                )
            }

            val source = response.body?.source() ?: throw IOException("MiniMax ASR 返回空响应")
            val transcript = StringBuilder()
            var expectedIndex = 0
            var sawEvent = false
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                val event = MiniMaxAsrProtocol.parseStreamEvent(line) ?: continue
                sawEvent = true
                if (event.index < expectedIndex) continue
                if (event.index > expectedIndex) {
                    throw IOException("MiniMax ASR 流式响应序号不连续")
                }
                expectedIndex++
                transcript.append(event.delta)
                if (event.delta.isNotEmpty() && !stopping.get()) {
                    _events.emit(SpeechEvent.Partial(transcript.toString()))
                }
                if (event.finished) break
            }
            if (!sawEvent) throw IOException("无法解析 MiniMax ASR 流式响应")
            transcript.toString().trim()
        }
    }

    private fun clearSessionReferences() {
        audioRecord = null
        captureJob = null
        uploadJob = null
        chunkChannel = null
    }

    private fun shortsToLittleEndian(samples: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * BYTES_PER_SAMPLE)
        for (index in 0 until count) {
            val value = samples[index].toInt()
            bytes[index * 2] = (value and 0xff).toByte()
            bytes[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
        }
        return bytes
    }

    private fun calculateRms(samples: ShortArray, count: Int): Double {
        if (count <= 0) return 0.0
        var sumSquares = 0.0
        for (index in 0 until count) {
            val value = samples[index].toDouble()
            sumSquares += value * value
        }
        return sqrt(sumSquares / count)
    }
}
