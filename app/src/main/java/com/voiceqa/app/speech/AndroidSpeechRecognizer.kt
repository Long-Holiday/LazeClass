package com.voiceqa.app.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class AndroidSpeechRecognizer(
    private val context: Context,
    private val scope: CoroutineScope
) : SpeechToText {

    companion object {
        private const val TAG = "AndroidSpeechRecognizer"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private val isListening = AtomicBoolean(false)
    private var currentLocale: String = "zh-CN"

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
            _events.tryEmit(SpeechEvent.Silence)
        }

        override fun onError(error: Int) {
            val errorMessage = getErrorDescription(error)
            Log.w(TAG, "onError: code=$error, msg=$errorMessage")

            // Transient silence/no-match errors should continue if in continuous listening mode
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                _events.tryEmit(SpeechEvent.Silence)
                if (isListening.get()) {
                    restartListeningDelayed(300)
                }
                return
            }

            _events.tryEmit(SpeechEvent.Error(error, errorMessage))

            if (isListening.get()) {
                // Recover from recognizer busy or server disconnected errors
                restartListeningDelayed(600)
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim()
            Log.d(TAG, "onResults: $text")

            if (!text.isNullOrEmpty()) {
                _events.tryEmit(SpeechEvent.Final(text))
            } else {
                _events.tryEmit(SpeechEvent.Silence)
            }

            // In continuous listening mode, start next recognition turn
            if (isListening.get()) {
                restartListeningDelayed(150)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim()
            if (!text.isNullOrEmpty()) {
                _events.tryEmit(SpeechEvent.Partial(text))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override suspend fun start(locale: String) {
        currentLocale = locale
        isListening.set(true)
        withContext(Dispatchers.Main) {
            initAndStartRecognizer()
        }
    }

    override suspend fun stop() {
        isListening.set(false)
        withContext(Dispatchers.Main) {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recognizer", e)
            }
        }
    }

    override fun release() {
        isListening.set(false)
        mainHandler.post {
            destroyRecognizer()
        }
    }

    private fun initAndStartRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _events.tryEmit(SpeechEvent.Error(-1, "设备不支持语音识别服务"))
            return
        }

        if (speechRecognizer == null) {
            speechRecognizer = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                Log.d(TAG, "Creating on-device speech recognizer")
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                Log.d(TAG, "Creating standard speech recognizer")
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            speechRecognizer?.setRecognitionListener(recognitionListener)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLocale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            _events.tryEmit(SpeechEvent.Error(-2, e.localizedMessage ?: "启动识别失败"))
        }
    }

    private fun restartListeningDelayed(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (isListening.get()) {
                try {
                    speechRecognizer?.cancel()
                    initAndStartRecognizer()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in restartListeningDelayed", e)
                }
            }
        }, delayMs)
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying speech recognizer", e)
        } finally {
            speechRecognizer = null
        }
    }

    private fun getErrorDescription(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
            SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
            SpeechRecognizer.ERROR_NETWORK -> "网络连接错误"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
            SpeechRecognizer.ERROR_NO_MATCH -> "未匹配到语音"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器繁忙"
            SpeechRecognizer.ERROR_SERVER -> "服务端错误"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "等待语音超时"
            else -> "未知识别错误 ($errorCode)"
        }
    }
}
