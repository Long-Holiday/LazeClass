package com.voiceqa.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

open class TextToSpeechManager(private val context: Context? = null) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TextToSpeechManager"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeechText: String? = null

    init {
        context?.let {
            tts = TextToSpeech(it.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.CHINESE)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Chinese TTS language not supported, falling back to default")
                tts?.setLanguage(Locale.getDefault())
            }
            tts?.setSpeechRate(1.0f)
            tts?.setPitch(1.0f)
            isInitialized = true

            pendingSpeechText?.let { text ->
                speak(text)
                pendingSpeechText = null
            }
        } else {
            Log.e(TAG, "TTS Initialization failed with status: $status")
        }
    }

    open fun speak(text: String, flush: Boolean = false) {
        if (text.isBlank()) return
        if (!isInitialized) {
            pendingSpeechText = text
            return
        }

        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, queueMode, null, text.hashCode().toString())
    }

    open fun stop() {
        tts?.stop()
    }

    open fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
